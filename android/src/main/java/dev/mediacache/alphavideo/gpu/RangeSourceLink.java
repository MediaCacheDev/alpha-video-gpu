package dev.mediacache.alphavideo.gpu;

/**
 * The range-source add-on, if the app has it: its native table of functions, which
 * {@link AlphaVideoGpuReader} reads URLs and data sources through. Without it, a URL is downloaded
 * whole into a temporary file (the basic input), and data sources, storage and cache keys aren't
 * available.
 */
final class RangeSourceLink {

    static {
        System.loadLibrary("alphavideogpu");
    }

    private static final long PLUGIN = nativeBuiltInPlugin();

    private RangeSourceLink() {}

    /** The add-on's table, or 0 without it. */
    static long plugin() {
        return PLUGIN;
    }

    static boolean available() {
        return PLUGIN != 0;
    }

    /** The add-on's reader and stores, built into this library until the add-on is its own package. */
    private static native long nativeBuiltInPlugin();
}
