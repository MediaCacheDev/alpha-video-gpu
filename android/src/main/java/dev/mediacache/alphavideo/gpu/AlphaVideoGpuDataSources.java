package dev.mediacache.alphavideo.gpu;

import java.util.HashMap;
import java.util.Map;

/**
 * Data sources by name, for React Native: register a factory here, then pass
 * {@code source={{ uri, dataSource: 'name' }}} to {@code AlphaVideoGpuView}. Thread-safe.
 */
public final class AlphaVideoGpuDataSources {

    private static final Map<String, AlphaVideoGpuDataSource.Factory> factories = new HashMap<>();

    private AlphaVideoGpuDataSources() {}

    /** Registers {@code factory} under {@code name}, replacing any registered before. */
    public static synchronized void register(String name, AlphaVideoGpuDataSource.Factory factory) {
        factories.put(name, factory);
    }

    public static synchronized void unregister(String name) {
        factories.remove(name);
    }

    /** The factory registered under {@code name}, or null. */
    public static synchronized AlphaVideoGpuDataSource.Factory get(String name) {
        return factories.get(name);
    }
}
