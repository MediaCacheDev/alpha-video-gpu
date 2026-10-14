package dev.mediacache.alphavideo.gpu;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

/**
 * A local file the player can read in place: a file, an APK asset, a raw resource, or a
 * content:// URI.
 *
 * Preloaded playback reads the whole file through {@link #openStream}, which works for every kind.
 * Streaming playback memory-maps a descriptor range from {@link #openFd}, which for assets and raw
 * resources requires them to be stored uncompressed in the APK. Remote URLs are not sources; they
 * are downloaded by {@link AlphaVideoGpuDownloader} and played as files.
 */
final class AlphaVideoGpuSource {

    private enum Kind { FILE, ASSET, RAW, CONTENT }

    private static final String ASSET_SCHEME = "asset://";
    // What Image.resolveAssetSource(require(...)) gives in a release build: a res/raw name
    private static final Pattern RAW_RESOURCE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Kind kind;
    private final String location;

    private AlphaVideoGpuSource(Kind kind, String location) {
        this.kind = kind;
        this.location = location;
    }

    static AlphaVideoGpuSource file(String path) {
        return new AlphaVideoGpuSource(Kind.FILE, path);
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

    byte[] readAll(Context context) throws IOException {
        try (InputStream in = openStream(context)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
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
