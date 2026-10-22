package dev.mediacache.alphavideo.gpu;

import android.net.Uri;

import java.io.IOException;
import java.util.Map;

import dev.mediacache.rangesource.RangeDataSource;
import dev.mediacache.rangesource.RangeDataSources;

/**
 * An app's data source, registered with range-source's {@code RangeDataSources}, as a
 * {@link Fetcher}. The only class that touches the add-on's types: load it only once
 * {@link RangeSourceLink#available()}, as an app may leave the add-on out.
 */
final class AppDataSource implements Fetcher {

    private final RangeDataSource source;

    private AppDataSource(RangeDataSource source) {
        this.source = source;
    }

    /** A new source from the factory registered under {@code name}, or null if there's none. */
    static Fetcher create(String name) {
        RangeDataSource.Factory factory = RangeDataSources.get(name);
        return factory != null ? new AppDataSource(factory.create()) : null;
    }

    @Override
    public Info open(Uri uri, long position, Map<String, String> headers) throws IOException {
        RangeDataSource.Info info = source.open(uri, position, headers);
        return new Info(info.size, info.etag, info.lastModified);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        return source.read(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
        source.close();
    }

    @Override
    public void cancel() {
        source.cancel();
    }
}
