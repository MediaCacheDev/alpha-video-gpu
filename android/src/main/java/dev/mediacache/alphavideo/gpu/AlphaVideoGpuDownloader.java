package dev.mediacache.alphavideo.gpu;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads remote files into the app's cache directory once, so they can be memory-mapped and
 * replayed. Eviction, expiry and re-signing URLs are left to the app (for example a media cache
 * that hands the player a file path).
 */
final class AlphaVideoGpuDownloader {

    private AlphaVideoGpuDownloader() {}

    static boolean isRemote(String uri) {
        return uri.startsWith("https://") || uri.startsWith("http://");
    }

    /**
     * Returns the cached file for {@code url}, downloading it first if needed.
     *
     * @param cacheKey optional file name (may include subfolders) under the cache directory; when
     *                 empty, the name is derived from the URL
     * @param cancelled checked between chunks; a cancelled download leaves nothing in the cache
     */
    static File fetch(Context context, String url, String cacheKey, AtomicBoolean cancelled) throws IOException {
        File cacheFile = cacheFileFor(context.getCacheDir(), url, cacheKey);
        if (cacheFile.length() > 0) return cacheFile;

        File dir = cacheFile.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create cache directory " + dir);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        File tempFile = null;
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "AlphaVideoGpu/1.0");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status + " for " + url);
            }

            // Several views may fetch the same URL at once; each writes its own temp file and the
            // rename makes whichever finishes first visible as a complete file.
            tempFile = File.createTempFile("download", ".part", dir);
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (cancelled.get()) throw new InterruptedIOException("Download cancelled");
                    out.write(buffer, 0, n);
                }
            }
            if (!tempFile.renameTo(cacheFile) && cacheFile.length() == 0) {
                throw new IOException("Cannot write " + cacheFile);
            }
            return cacheFile;
        } finally {
            connection.disconnect();
            if (tempFile != null) tempFile.delete();
        }
    }

    private static File cacheFileFor(File cacheDir, String url, String cacheKey) throws IOException {
        if (cacheKey == null || cacheKey.isEmpty()) {
            return new File(new File(cacheDir, "alpha-video-gpu"), sha256(url));
        }
        File file = new File(cacheDir, cacheKey).getCanonicalFile();
        if (!file.getPath().startsWith(cacheDir.getCanonicalPath() + File.separator)) {
            throw new IOException("cacheKey must stay inside the cache directory: " + cacheKey);
        }
        return file;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
