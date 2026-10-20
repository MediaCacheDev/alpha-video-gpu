package dev.mediacache.alphavideo.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * A KTX2 file's frame metadata: size, frame count and vkFormat from the header, and the frame rate
 * from KTXanimData, the KTX 2.0 key for array textures whose layers are animation frames (per-frame
 * duration / timescale seconds, so 29.97 fps is exact).
 */
final class Ktx2Metadata {
    /** Frame rate for files without KTXanimData. */
    static final double DEFAULT_FRAME_RATE = 30.0;

    private static final byte[] ANIM_DATA_KEY = "KTXanimData".getBytes(StandardCharsets.US_ASCII);

    final int vkFormat;
    final int width;
    final int height;
    final int frameCount;
    final double frameRate;

    private Ktx2Metadata(int vkFormat, int width, int height, int frameCount, double frameRate) {
        this.vkFormat = vkFormat;
        this.width = width;
        this.height = height;
        this.frameCount = frameCount;
        this.frameRate = frameRate;
    }

    /** Reads data holding the file through its key/value data; null if it isn't KTX2. */
    static Ktx2Metadata read(byte[] ktx2) {
        if (ktx2 == null || ktx2.length < 80 || ktx2[0] != (byte) 0xAB || ktx2[1] != 0x4B
                || ktx2[2] != 0x54 || ktx2[3] != 0x58) {
            return null;
        }
        ByteBuffer header = ByteBuffer.wrap(ktx2).order(ByteOrder.LITTLE_ENDIAN);
        double animFrameRate = animFrameRate(ktx2, header.getInt(56), header.getInt(60));
        return new Ktx2Metadata(header.getInt(12), header.getInt(20), header.getInt(24), header.getInt(32),
                animFrameRate > 0 ? animFrameRate : DEFAULT_FRAME_RATE);
    }

    /** ASTC (VkFormat 157-184, either colour space) in an array texture: the only files that play. */
    boolean isAstcArray() {
        return vkFormat >= 157 && vkFormat <= 184 && frameCount >= 1;
    }

    /** The frame rate from KTXanimData in the key/value data, or 0 if there's none. */
    private static double animFrameRate(byte[] ktx2, int kvdOffset, int kvdLength) {
        if (kvdOffset < 0 || kvdLength <= 0) return 0;
        ByteBuffer data = ByteBuffer.wrap(ktx2).order(ByteOrder.LITTLE_ENDIAN);
        long kvdEnd = Math.min((long) kvdOffset + kvdLength, ktx2.length);
        long pos = kvdOffset;
        while (pos + 4 <= kvdEnd) {
            long entryLength = data.getInt((int) pos) & 0xFFFFFFFFL;
            int entryStart = (int) pos + 4;
            if (entryLength == 0 || entryStart + entryLength > kvdEnd) break;
            int keyEnd = entryStart;
            while (keyEnd < entryStart + entryLength && ktx2[keyEnd] != 0) keyEnd++;
            if (isAnimDataKey(ktx2, entryStart, keyEnd) && entryStart + entryLength >= keyEnd + 1 + 12) {
                long duration = data.getInt(keyEnd + 1) & 0xFFFFFFFFL;
                long timescale = data.getInt(keyEnd + 5) & 0xFFFFFFFFL;
                return duration > 0 && timescale > 0 ? (double) timescale / duration : 0;
            }
            pos = entryStart + entryLength + (4 - entryLength % 4) % 4;
        }
        return 0;
    }

    private static boolean isAnimDataKey(byte[] ktx2, int start, int end) {
        if (end - start != ANIM_DATA_KEY.length) return false;
        for (int i = 0; i < ANIM_DATA_KEY.length; i++) {
            if (ktx2[start + i] != ANIM_DATA_KEY[i]) return false;
        }
        return true;
    }
}
