package dev.mediacache.alphavideo.gpu;

import android.net.Uri;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A native range reader over an {@link AlphaVideoGpuDataSource}: blocks in memory and in an
 * optional store, fetched on the reader's own thread. One playback owns it, opens it on its
 * worker thread and closes it there; a zstd stream over it keeps its own native reference.
 */
final class AlphaVideoGpuReader {

    static {
        System.loadLibrary("alphavideogpu");
    }

    /** Where a reader keeps what it fetches between plays. */
    enum Storage { DISK, MEMORY, NONE }

    /** What {@link #tryRead} returns while the bytes are on their way. */
    static final int PENDING = -2;

    // How long readFully waits between polls
    private static final long POLL_MS = 4;

    // 0 once closed
    private long handle;
    private final String description;

    private AlphaVideoGpuReader(long handle, String description) {
        this.handle = handle;
        this.description = description;
    }

    /**
     * Opens the source at 0 to learn its size and version, unless the store holds it whole. Blocks
     * on the network.
     *
     * @param storeDir the folder of the disk store, or the name of the memory one
     * @param key what the store calls this file
     */
    static AlphaVideoGpuReader open(AlphaVideoGpuDataSource source, Uri uri, Map<String, String> headers,
                                    Storage storage, String storeDir, String key, long maxStorageBytes) throws IOException {
        String[] error = new String[1];
        long handle = nativeOpen(new Upstream(source, uri, headers), storage.ordinal(), storeDir, key,
            maxStorageBytes, error);
        if (handle == 0) throw new IOException(error[0] != null ? error[0] : "Cannot open " + uri);
        return new AlphaVideoGpuReader(handle, uri.toString());
    }

    /** The native reference, for a zstd stream to take its own; 0 once closed. */
    long handle() {
        return handle;
    }

    /**
     * Reads what is held at {@code position}, without waiting: how many bytes (fewer at a block's
     * end), 0 at the end of the file, -1 if fetching them failed (see {@link #lastError}), or
     * {@link #PENDING}, having asked for them.
     */
    int tryRead(long position, byte[] buffer, int offset, int length) {
        if (handle == 0) return -1;
        return nativeTryRead(handle, position, buffer, offset, length);
    }

    /**
     * Reads {@code length} bytes at {@code position}, fewer only at the end of the file. Waits for
     * them; throws if they can't be fetched, or once {@code cancelled} is set.
     */
    int readFully(long position, byte[] buffer, int offset, int length, AtomicBoolean cancelled) throws IOException {
        int total = 0;
        while (total < length) {
            int n = tryRead(position + total, buffer, offset + total, length - total);
            if (n == PENDING) {
                if (cancelled.get()) throw new InterruptedIOException("Cancelled");
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted");
                }
                continue;
            }
            if (n < 0) throw new IOException("Reading " + description + " failed: " + lastError());
            if (n == 0) break;
            total += n;
        }
        return total;
    }

    /** The file's size, or -1 while it isn't known. */
    long size() {
        return handle != 0 ? nativeSize(handle) : -1;
    }

    String lastError() {
        String error = handle != 0 ? nativeLastError(handle) : null;
        return error != null && !error.isEmpty() ? error : "the reader is closed";
    }

    /** Drops this reference; the fetch stops once a zstd stream over it has ended too. */
    void close() {
        long h = handle;
        handle = 0;
        if (h != 0) nativeRelease(h);
    }

    @Override
    public String toString() {
        return description;
    }

    /**
     * The data source as the native reader calls it, on the reader's fetch thread, with failures
     * as return values.
     */
    static final class Upstream {
        // cancel() comes from whichever thread closes the reader, and an HTTPS disconnect can
        // write to the socket.
        private static final ExecutorService cancelExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "AlphaVideoGpuCancel");
            t.setDaemon(true);
            return t;
        });

        private final AlphaVideoGpuDataSource source;
        private final Uri uri;
        private final Map<String, String> headers;
        // Read by native code after open() and failures
        String etag;
        String lastModified;
        String error;

        Upstream(AlphaVideoGpuDataSource source, Uri uri, Map<String, String> headers) {
            this.source = source;
            this.uri = uri;
            this.headers = headers;
        }

        /** The file's size, -1 if not known, or -2 with {@link #error} set. */
        long open(long position) {
            try {
                AlphaVideoGpuDataSource.Info info = source.open(uri, position, headers);
                etag = info.etag;
                lastModified = info.lastModified;
                return info.size;
            } catch (Exception e) {
                error = describe(e);
                return -2;
            }
        }

        /** How many bytes were read into {@code buffer}, 0 at the end, or -1 with {@link #error} set. */
        int read(byte[] buffer, int length) {
            try {
                int n;
                do {
                    n = source.read(buffer, 0, length);
                } while (n == 0);
                return n < 0 ? 0 : n;
            } catch (Exception e) {
                error = describe(e);
                return -1;
            }
        }

        void close() {
            try {
                source.close();
            } catch (Exception ignored) {
            }
        }

        void cancel() {
            cancelExecutor.execute(() -> {
                try {
                    source.cancel();
                } catch (Exception ignored) {
                }
            });
        }

        private static String describe(Exception e) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
        }
    }

    /**
     * @param storage a {@link Storage} ordinal
     * @param error set to why, when it returns 0
     * @return a reference to the reader, or 0
     */
    private static native long nativeOpen(Upstream upstream, int storage, String storeDir, String key,
                                          long maxStorageBytes, String[] error);
    private static native void nativeRelease(long reference);
    private static native int nativeTryRead(long reference, long position, byte[] buffer, int offset, int length);
    private static native long nativeSize(long reference);
    private static native String nativeLastError(long reference);
}
