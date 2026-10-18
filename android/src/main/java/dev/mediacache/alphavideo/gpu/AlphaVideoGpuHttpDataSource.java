package dev.mediacache.alphavideo.gpu;

import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The built-in source for {@code http(s)://} URLs: {@code HttpURLConnection} with
 * {@code Range: bytes=N-} requests. A server that ignores ranges answers 200 with the whole file,
 * which is read from the position on; that works, but each seek then reads from the start.
 * Redirects are followed, between http and https too, keeping the headers.
 */
public final class AlphaVideoGpuHttpDataSource implements AlphaVideoGpuDataSource {

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 15000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 30000;
    public static final String DEFAULT_USER_AGENT = "AlphaVideoGpu/1.0";

    private static final int MAX_REDIRECTS = 5;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-\\d+/(\\d+|\\*)");
    private static final Pattern UNSATISFIED_RANGE = Pattern.compile("bytes\\s+\\*/(\\d+)");

    public static final class Factory implements AlphaVideoGpuDataSource.Factory {
        private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
        private String userAgent = DEFAULT_USER_AGENT;

        public Factory setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public Factory setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
            return this;
        }

        /** Sent unless the headers have a User-Agent. */
        public Factory setUserAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        @Override
        public AlphaVideoGpuDataSource create() {
            return new AlphaVideoGpuHttpDataSource(connectTimeoutMs, readTimeoutMs, userAgent);
        }
    }

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String userAgent;

    // Set on the reading thread, disconnected by cancel() from any
    private volatile HttpURLConnection connection;
    private volatile boolean cancelled = false;
    // Null once the read has ended
    private InputStream in;

    public AlphaVideoGpuHttpDataSource() {
        this(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, DEFAULT_USER_AGENT);
    }

    private AlphaVideoGpuHttpDataSource(int connectTimeoutMs, int readTimeoutMs, String userAgent) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.userAgent = userAgent;
    }

    @Override
    public Info open(Uri uri, long position, Map<String, String> headers) throws IOException {
        close();
        URL url = new URL(uri.toString());
        for (int redirects = 0; ; redirects++) {
            HttpURLConnection c = connect(url, position, headers);
            int status = c.getResponseCode();
            if (isRedirect(status)) {
                String location = c.getHeaderField("Location");
                close();
                if (location == null) throw new IOException("HTTP " + status + " without a Location for " + url);
                if (redirects == MAX_REDIRECTS) throw new IOException("Too many redirects for " + uri);
                url = new URL(url, location);
                continue;
            }
            String etag = c.getHeaderField("ETag");
            String lastModified = c.getHeaderField("Last-Modified");
            switch (status) {
                case HttpURLConnection.HTTP_PARTIAL: {
                    String contentRange = c.getHeaderField("Content-Range");
                    Matcher range = CONTENT_RANGE.matcher(String.valueOf(contentRange));
                    if (!range.lookingAt() || Long.parseLong(range.group(1)) != position) {
                        close();
                        throw new IOException("Asked " + url + " for bytes from " + position + " and got " + contentRange);
                    }
                    in = c.getInputStream();
                    String total = range.group(2);
                    return new Info(total.equals("*") ? Info.UNKNOWN_SIZE : Long.parseLong(total), etag, lastModified);
                }
                case HttpURLConnection.HTTP_OK: {
                    long size = c.getContentLengthLong();
                    in = c.getInputStream();
                    skip(position);
                    return new Info(size, etag, lastModified);
                }
                case 416: {
                    // At or past the end: a read that ends at once
                    Matcher range = UNSATISFIED_RANGE.matcher(String.valueOf(c.getHeaderField("Content-Range")));
                    long size = range.lookingAt() ? Long.parseLong(range.group(1)) : Info.UNKNOWN_SIZE;
                    close();
                    return new Info(size, etag, lastModified);
                }
                default:
                    close();
                    throw new IOException("HTTP " + status + " for " + url);
            }
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (in == null) return -1;
        try {
            return in.read(buffer, offset, length);
        } catch (IOException e) {
            if (cancelled) throw new IOException("Cancelled");
            throw e;
        }
    }

    @Override
    public void close() {
        InputStream stream = in;
        in = null;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
        HttpURLConnection c = connection;
        connection = null;
        if (c != null) c.disconnect();
    }

    @Override
    public void cancel() {
        cancelled = true;
        HttpURLConnection c = connection;
        if (c != null) c.disconnect();
    }

    private HttpURLConnection connect(URL url, long position, Map<String, String> headers) throws IOException {
        String protocol = url.getProtocol();
        if (!protocol.equals("https") && !protocol.equals("http")) {
            throw new IOException("Not an http(s) URL: " + url);
        }
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        connection = c;
        if (cancelled) {
            close();
            throw new IOException("Cancelled");
        }
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", userAgent);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            c.setRequestProperty(header.getKey(), header.getValue());
        }
        // Offsets are into the file as stored, so no transparent gzip
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Range", "bytes=" + position + "-");
        return c;
    }

    /** Reads past the first {@code count} bytes of a 200 answer; stops early at its end. */
    private void skip(long count) throws IOException {
        byte[] scratch = new byte[64 * 1024];
        while (count > 0) {
            int n = in.read(scratch, 0, (int) Math.min(scratch.length, count));
            if (n < 0) return;
            count -= n;
        }
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_MOVED_TEMP
            || status == HttpURLConnection.HTTP_SEE_OTHER || status == 307 || status == 308;
    }
}
