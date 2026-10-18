package dev.mediacache.alphavideo.gpu.media3;

import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.mediacache.alphavideo.gpu.AlphaVideoGpuDataSource;

/**
 * An {@link AlphaVideoGpuDataSource} over a Media3 {@link DataSource}, so that the player reads
 * through the app's {@code DataSource.Factory}: OkHttp, Cronet, a {@code CacheDataSource}, or
 * MediaCache's. Register one for React Native:
 *
 * <pre>
 * AlphaVideoGpuDataSources.register("media3", Media3AlphaVideoGpuDataSource.factory(dataSourceFactory));
 * </pre>
 *
 * Media3 sources can't be cancelled, so a read in progress when the player closes finishes in the
 * background. A source without a total size or an ETag in its response headers (a cache, a file)
 * still plays; the player's store just can't tell when it changes.
 */
@OptIn(markerClass = UnstableApi.class)
public final class Media3AlphaVideoGpuDataSource implements AlphaVideoGpuDataSource {

    private static final Pattern CONTENT_RANGE_TOTAL = Pattern.compile("bytes\\s+[^/]*/(\\d+)");

    /** A factory making one Media3 source per player from {@code upstream}. */
    public static AlphaVideoGpuDataSource.Factory factory(DataSource.Factory upstream) {
        return () -> new Media3AlphaVideoGpuDataSource(upstream.createDataSource());
    }

    private final DataSource dataSource;
    // An open was attempted; Media3 sources are closed after a failed open too
    private boolean opened = false;

    public Media3AlphaVideoGpuDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Info open(Uri uri, long position, Map<String, String> headers) throws IOException {
        close();
        DataSpec spec = new DataSpec.Builder()
            .setUri(uri)
            .setPosition(position)
            .setHttpRequestHeaders(headers)
            .build();
        opened = true;
        long length = dataSource.open(spec);
        Map<String, List<String>> response = dataSource.getResponseHeaders();
        long size = sizeFromContentRange(header(response, "Content-Range"));
        if (size < 0 && length != C.LENGTH_UNSET) size = position + length;
        return new Info(size, header(response, "ETag"), header(response, "Last-Modified"));
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int n = dataSource.read(buffer, offset, length);
        return n == C.RESULT_END_OF_INPUT ? -1 : n;
    }

    @Override
    public void close() throws IOException {
        if (!opened) return;
        opened = false;
        dataSource.close();
    }

    private static long sizeFromContentRange(String contentRange) {
        if (contentRange == null) return Info.UNKNOWN_SIZE;
        Matcher m = CONTENT_RANGE_TOTAL.matcher(contentRange);
        return m.lookingAt() ? Long.parseLong(m.group(1)) : Info.UNKNOWN_SIZE;
    }

    /** The first value of a response header, by name in any case; null if absent. */
    private static String header(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }
}
