package dev.mediacache.alphavideo.gpu;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import androidx.annotation.Nullable;

/**
 * What the player reads: a local file it can read in place (a file, an APK asset, a raw resource,
 * or a content:// URI), or a range reader over a URL or an app's data source.
 *
 * Headers, ranges and whole files are read through {@link #openStream}, which works for every
 * kind. Streaming playback of a local file memory-maps a descriptor range from {@link #openFd},
 * which for assets and raw resources requires them to be stored uncompressed in the APK; a reader
 * is streamed from {@link #reader} instead.
 */
final class AlphaVideoGpuSource {

    private enum Kind { FILE, ASSET, RAW, CONTENT, READER }

    private static final String ASSET_SCHEME = "asset://";
    // What Image.resolveAssetSource(require(...)) gives in a release build: a res/raw name
    private static final Pattern RAW_RESOURCE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Kind kind;
    private final String location;
    @Nullable private final AlphaVideoGpuReader reader;
    // Stops the reader's waits once the playback stops
    @Nullable private final AtomicBoolean cancelled;

    private AlphaVideoGpuSource(Kind kind, String location) {
        this(kind, location, null, null);
    }

    private AlphaVideoGpuSource(Kind kind, String location, @Nullable AlphaVideoGpuReader reader,
                                @Nullable AtomicBoolean cancelled) {
        this.kind = kind;
        this.location = location;
        this.reader = reader;
        this.cancelled = cancelled;
    }

    static AlphaVideoGpuSource file(String path) {
        return new AlphaVideoGpuSource(Kind.FILE, path);
    }

    /** Reads through {@code reader}; its reads give up once {@code cancelled} is set. */
    static AlphaVideoGpuSource reader(AlphaVideoGpuReader reader, AtomicBoolean cancelled) {
        return new AlphaVideoGpuSource(Kind.READER, reader.toString(), reader, cancelled);
    }

    /** The range reader of a URL or data source; null for a local file. */
    @Nullable
    AlphaVideoGpuReader reader() {
        return reader;
    }

    /**
     * Parses {@code file:///abs/path}, {@code /abs/path}, {@code asset://relative/path},
     * {@code content://...}, or a bare res/raw resource name.
     *
     * @throws IllegalArgumentException for anything else
     */
    static AlphaVideoGpuSource parse(String uri) {
        if (uri.startsWith(ASSET_SCHEME)) {
            String path = Uri.decode(uri.substring(ASSET_SCHEME.length()));
            while (path.startsWith("/")) path = path.substring(1);
            if (path.isEmpty()) throw new IllegalArgumentException("Empty asset path: " + uri);
            return new AlphaVideoGpuSource(Kind.ASSET, path);
        }
        if (uri.startsWith("content://")) {
            return new AlphaVideoGpuSource(Kind.CONTENT, uri);
        }
        if (uri.startsWith("file://")) {
            String path = Uri.parse(uri).getPath();
            if (path == null || path.isEmpty()) throw new IllegalArgumentException("Bad file URI: " + uri);
            return file(path);
        }
        if (uri.startsWith("/")) {
            return file(uri);
        }
        if (RAW_RESOURCE_NAME.matcher(uri).matches()) {
            return new AlphaVideoGpuSource(Kind.RAW, uri);
        }
        throw new IllegalArgumentException(
            "Unsupported source '" + uri + "'. Use https://, file://, an absolute path, asset:// or content://");
    }

    /** The caller closes the descriptor; native code duplicates it if it needs to keep reading. */
    AssetFileDescriptor openFd(Context context) throws IOException {
        switch (kind) {
            case READER:
                throw new IOException(location + " is read by range, not from a descriptor");
            case ASSET:
                try {
                    return context.getAssets().openFd(location);
                } catch (FileNotFoundException e) {
                    if (!assetExists(context)) throw new FileNotFoundException("Asset not found: " + location);
                    throw compressedInApk("Asset " + location);
                }
            case RAW: {
                Resources resources = context.getResources();
                try {
                    return resources.openRawResourceFd(rawResourceId(context));
                } catch (Resources.NotFoundException e) {
                    throw compressedInApk("Resource res/raw/" + location);
                }
            }
            case CONTENT: {
                AssetFileDescriptor afd = context.getContentResolver()
                    .openAssetFileDescriptor(Uri.parse(location), "r");
                if (afd == null) throw new FileNotFoundException("Cannot open " + location);
                return afd;
            }
            default: {
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                    new File(location), ParcelFileDescriptor.MODE_READ_ONLY);
                return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
            }
        }
    }

    InputStream openStream(Context context) throws IOException {
        switch (kind) {
            case READER:
                return new ReaderInputStream(reader, cancelled);
            case ASSET:
                return context.getAssets().open(location);
            case RAW:
                return context.getResources().openRawResource(rawResourceId(context));
            case CONTENT: {
                InputStream in = context.getContentResolver().openInputStream(Uri.parse(location));
                if (in == null) throw new FileNotFoundException("Cannot open " + location);
                return in;
            }
            default:
                return new FileInputStream(location);
        }
    }

    /** Up to {@code count} bytes from the start of the file (fewer if the file is shorter). */
    byte[] readHead(Context context, int count) throws IOException {
        try (InputStream in = openStream(context)) {
            byte[] head = new byte[count];
            int total = 0;
            int n;
            while (total < count && (n = in.read(head, total, count - total)) != -1) {
                total += n;
            }
            return total == count ? head : java.util.Arrays.copyOf(head, total);
        }
    }

    /** {@code count} bytes starting at {@code offset}, or fewer if the file ends first. */
    byte[] readRange(Context context, long offset, int count) throws IOException {
        try (InputStream in = openStream(context)) {
            long skipped = 0;
            while (skipped < offset) {
                long n = in.skip(offset - skipped);
                if (n <= 0) {
                    if (in.read() == -1) return new byte[0];
                    n = 1;
                }
                skipped += n;
            }
            byte[] range = new byte[count];
            int total = 0;
            int n;
            while (total < count && (n = in.read(range, total, count - total)) != -1) {
                total += n;
            }
            return total == count ? range : java.util.Arrays.copyOf(range, total);
        }
    }

    /**
     * Memory-maps a local file; the mapping stays valid after the descriptor is closed. Assets and
     * raw resources must be stored uncompressed in the APK.
     */
    ByteBuffer map(Context context) throws IOException {
        try (AssetFileDescriptor afd = openFd(context);
             FileInputStream in = new FileInputStream(afd.getFileDescriptor())) {
            FileChannel channel = in.getChannel();
            long start = afd.getStartOffset();
            long length = afd.getLength() >= 0 ? afd.getLength() : channel.size() - start;
            return channel.map(FileChannel.MapMode.READ_ONLY, start, length);
        }
    }

    /**
     * The whole file in a direct buffer, for uploads straight from it, never on the Java heap: a
     * local file mapped where it can be, a reader's file read into one.
     */
    ByteBuffer readAllDirect(Context context) throws IOException {
        if (reader == null) {
            try {
                return map(context);
            } catch (IOException e) {
                // An entry compressed in the APK can only be read as a stream
                try (InputStream in = openStream(context)) {
                    return readDirect(in);
                }
            }
        }
        long size = reader.size();
        if (size < 0 || size > Integer.MAX_VALUE) {
            try (InputStream in = openStream(context)) {
                return readDirect(in);
            }
        }
        ByteBuffer out = ByteBuffer.allocateDirect((int) size);
        byte[] chunk = new byte[256 * 1024];
        for (long pos = 0; pos < size; ) {
            int n = reader.readFully(pos, chunk, 0, (int) Math.min(chunk.length, size - pos), cancelled);
            if (n == 0) throw new IOException(location + " ended at " + pos + " of " + size + " bytes");
            out.put(chunk, 0, n);
            pos += n;
        }
        out.rewind();
        return out;
    }

    /** A stream of unknown length into a direct buffer exactly as long, grown by doubling. */
    private static ByteBuffer readDirect(InputStream in) throws IOException {
        ByteBuffer out = ByteBuffer.allocateDirect(1024 * 1024);
        byte[] chunk = new byte[256 * 1024];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (out.remaining() < n) {
                long grown = Math.max(2L * out.capacity(), (long) out.position() + n);
                if (grown > Integer.MAX_VALUE) throw new IOException("The file is over 2 GB");
                ByteBuffer bigger = ByteBuffer.allocateDirect((int) grown);
                out.flip();
                bigger.put(out);
                out = bigger;
            }
            out.put(chunk, 0, n);
        }
        out.flip();
        // Native code reads the buffer's capacity as the file's length
        return out.slice();
    }

    /** A reader's bytes in order from 0, as a stream; skipping moves the position without reading. */
    private static final class ReaderInputStream extends InputStream {
        private final AlphaVideoGpuReader reader;
        private final AtomicBoolean cancelled;
        private long position = 0;

        ReaderInputStream(AlphaVideoGpuReader reader, AtomicBoolean cancelled) {
            this.reader = reader;
            this.cancelled = cancelled;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == 1 ? one[0] & 0xFF : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            int n = reader.readFully(position, buffer, offset, length, cancelled);
            if (n == 0) return -1;
            position += n;
            return n;
        }

        @Override
        public long skip(long n) {
            if (n <= 0) return 0;
            long size = reader.size();
            long skipped = size >= 0 ? Math.max(0, Math.min(n, size - position)) : n;
            position += skipped;
            return skipped;
        }
    }

    private int rawResourceId(Context context) throws FileNotFoundException {
        int id = context.getResources().getIdentifier(location, "raw", context.getPackageName());
        if (id == 0) throw new FileNotFoundException("No res/raw resource named " + location);
        return id;
    }

    private static IOException compressedInApk(String what) {
        // openFd and openRawResourceFd only work on entries stored uncompressed in the APK.
        return new IOException(what + " is compressed in the APK, so it can't be streamed in place; add its "
            + "extension to android.androidResources.noCompress in the app's build.gradle");
    }

    private boolean assetExists(Context context) {
        int slash = location.lastIndexOf('/');
        String dir = slash < 0 ? "" : location.substring(0, slash);
        String name = location.substring(slash + 1);
        try {
            String[] names = context.getAssets().list(dir);
            if (names == null) return false;
            for (String n : names) {
                if (n.equals(name)) return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    @Override
    public String toString() {
        switch (kind) {
            case ASSET: return ASSET_SCHEME + location;
            case RAW: return "res/raw/" + location;
            default: return location;
        }
    }
}
