package dev.mediacache.alphavideo.gpu;

import android.net.Uri;

import java.io.IOException;
import java.util.Map;

/**
 * Where {@link AlphaVideoGpuReader}'s bytes come from: the built-in {@link HttpFetcher}, or an app's
 * data source through the range-source add-on ({@link AppDataSource}). The shape of range-source's
 * {@code RangeDataSource}, so that the player reads URLs without the add-on too.
 */
interface Fetcher {

    final class Info {
        static final long UNKNOWN_SIZE = -1;

        final long size;
        final String etag;
        final String lastModified;

        Info(long size, String etag, String lastModified) {
            this.size = size >= 0 ? size : UNKNOWN_SIZE;
            this.etag = etag;
            this.lastModified = lastModified;
        }
    }

    Info open(Uri uri, long position, Map<String, String> headers) throws IOException;

    /** How many bytes were read, or -1 at the end of the file. */
    int read(byte[] buffer, int offset, int length) throws IOException;

    void close() throws IOException;

    void cancel();
}
