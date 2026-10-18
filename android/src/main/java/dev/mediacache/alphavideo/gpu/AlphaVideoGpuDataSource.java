package dev.mediacache.alphavideo.gpu;

import android.net.Uri;

import java.io.IOException;
import java.util.Map;

/**
 * Where an animation's bytes come from: the built-in {@link AlphaVideoGpuHttpDataSource}, or an
 * app's own source (its HTTP client, a cache, a database, decryption). It has a Media3
 * {@code DataSource}'s shape, and the {@code alpha-video-gpu-media3} artifact adapts any
 * {@code DataSource.Factory} to it. It has the shape of the WebM player's
 * {@code AlphaVideoDataSource}, so one adapts to the other in a few lines.
 *
 * The player opens a read at a position and reads on in order; a seek far away opens another.
 * {@link #open}, {@link #read} and {@link #close} are called on one player thread at a time, and
 * may block. {@link #cancel} may be called from any thread.
 */
public interface AlphaVideoGpuDataSource {

    /** What the source knows about the file, from {@link #open}. */
    final class Info {
        public static final long UNKNOWN_SIZE = -1;

        /** The whole file's size in bytes (not what's left after the position), or {@link #UNKNOWN_SIZE}. */
        public final long size;
        /** An ETag, or any string that changes when the content does; null if none. */
        public final String etag;
        /** Last-Modified, for sources without an ETag; null if none. */
        public final String lastModified;

        public Info(long size) {
            this(size, null, null);
        }

        public Info(long size, String etag, String lastModified) {
            this.size = size >= 0 ? size : UNKNOWN_SIZE;
            this.etag = etag;
            this.lastModified = lastModified;
        }
    }

    /** Makes a source for each player; a source has one read open at a time. */
    interface Factory {
        AlphaVideoGpuDataSource create();
    }

    /**
     * Opens a read of {@code uri} from {@code position} to the end, closing any read still open.
     * Opening at or past the end gives a read that ends at once.
     *
     * @param headers the source's {@code headers}, for sources that make HTTP requests; may be empty
     */
    Info open(Uri uri, long position, Map<String, String> headers) throws IOException;

    /**
     * Reads up to {@code length} bytes, continuing from the last read, blocking for at least one.
     *
     * @return how many were read, or -1 at the end of the file
     */
    int read(byte[] buffer, int offset, int length) throws IOException;

    void close() throws IOException;

    /**
     * Makes an open or read in progress fail soon, from another thread, once the player no longer
     * needs it. Optional: without it, that read runs on until it returns.
     */
    default void cancel() {}
}
