package dev.mediacache.alphavideo.gpu;

import android.util.Log;

/**
 * The range-source add-on ({@code dev.mediacache:range-source}), if the app has it: its native
 * table of functions, which {@link AlphaVideoGpuReader} reads URLs and data sources through. The player
 * depends on it, so it's there unless the app's build excludes it. Without it, a URL is downloaded
 * whole into a temporary file (the basic input), and data sources, storage and cache keys aren't
 * available.
 */
final class RangeSourceLink {
    private static final String TAG = "AlphaVideoGpu";
    // Its RangeSource class has a static plugin(), the address of its RangeSourcePlugin
    private static final String ADD_ON = "dev.mediacache.rangesource.RangeSource";

    static {
        System.loadLibrary("alphavideogpu");
    }

    private static final long PLUGIN = discover();

    private RangeSourceLink() {}

    /** The add-on's table, or 0 without it. */
    static long plugin() {
        return PLUGIN;
    }

    static boolean available() {
        return PLUGIN != 0;
    }

    private static long discover() {
        Class<?> addOn;
        try {
            addOn = Class.forName(ADD_ON);
        } catch (ClassNotFoundException e) {
            return 0;
        } catch (LinkageError e) {
            Log.e(TAG, "range-source is there but its native library didn't load", e);
            return 0;
        }
        try {
            return nativeUsablePlugin((Long) addOn.getMethod("plugin").invoke(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.e(TAG, "range-source didn't give its table", e);
            return 0;
        }
    }

    /** {@code plugin} if this player can use it (its ABI and functions), else 0, logging why. */
    private static native long nativeUsablePlugin(long plugin);
}
