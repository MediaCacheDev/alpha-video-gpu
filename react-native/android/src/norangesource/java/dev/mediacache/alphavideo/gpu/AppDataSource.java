package dev.mediacache.alphavideo.gpu;

/**
 * Stands in for android/src/rangesource's AppDataSource when the app hasn't installed
 * react-native-range-source, so the package builds without range-source's classes. Never
 * reached: {@link RangeSourceLink#available()} is false without the add-on.
 */
final class AppDataSource {

    private AppDataSource() {}

    static Fetcher create(String name) {
        return null;
    }
}
