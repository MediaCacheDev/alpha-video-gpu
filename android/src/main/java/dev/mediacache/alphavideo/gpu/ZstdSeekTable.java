package dev.mediacache.alphavideo.gpu;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The seek table texenc writes after a KTX2 level's zstd frames: zstd's seekable format
 * (contrib/seekable_format), a skippable frame listing each frame's compressed and decompressed
 * size, ending with a 9-byte footer. The decoder can start at any frame. A level without one is
 * a single stream, which decodes only from its start.
 */
final class ZstdSeekTable {
    private static final int SEEK_TABLE_MAGIC = 0x184D2A5E;
    private static final int SEEKABLE_FOOTER_MAGIC = 0x8F92EAB1;
    private static final int FOOTER_SIZE = 9;
    private static final int SKIPPABLE_HEADER_SIZE = 8;
    private static final int CHECKSUM_FLAG = 0x80;
    private static final int RESERVED_BITS = 0x7C;

    /** Where each frame starts in the level data, ascending from 0. */
    final long[] compressedOffsets;
    /** Where each frame's output starts in the decompressed level, ascending from 0. */
    final long[] decompressedOffsets;

    private ZstdSeekTable(long[] compressedOffsets, long[] decompressedOffsets) {
        this.compressedOffsets = compressedOffsets;
        this.decompressedOffsets = decompressedOffsets;
    }

    /**
     * The table at the end of the level [levelOffset, levelOffset + levelLength) of the file, or
     * null when there is none or it doesn't describe exactly the frames before it.
     */
    @Nullable
    static ZstdSeekTable parse(byte[] file, long levelOffset, long levelLength, long decompressedLength) {
        if (levelLength < SKIPPABLE_HEADER_SIZE + FOOTER_SIZE || levelOffset < 0
                || levelOffset + levelLength > file.length) {
            return null;
        }
        ByteBuffer data = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        int end = (int) (levelOffset + levelLength);
        if (data.getInt(end - 4) != SEEKABLE_FOOTER_MAGIC) return null;
        int descriptor = file[end - 5] & 0xFF;
        if ((descriptor & RESERVED_BITS) != 0) return null;
        long frameCount = data.getInt(end - FOOTER_SIZE) & 0xFFFFFFFFL;
        int entrySize = (descriptor & CHECKSUM_FLAG) != 0 ? 12 : 8;

        long tableSize = frameCount * entrySize + FOOTER_SIZE;
        long tableStart = end - SKIPPABLE_HEADER_SIZE - tableSize;
        if (frameCount == 0 || tableStart < levelOffset) return null;
        if (data.getInt((int) tableStart) != SEEK_TABLE_MAGIC) return null;
        if ((data.getInt((int) tableStart + 4) & 0xFFFFFFFFL) != tableSize) return null;

        long[] compressedOffsets = new long[(int) frameCount];
        long[] decompressedOffsets = new long[(int) frameCount];
        long compressed = 0;
        long decompressed = 0;
        for (int i = 0; i < frameCount; i++) {
            int entry = (int) tableStart + SKIPPABLE_HEADER_SIZE + i * entrySize;
            compressedOffsets[i] = compressed;
            decompressedOffsets[i] = decompressed;
            compressed += data.getInt(entry) & 0xFFFFFFFFL;
            decompressed += data.getInt(entry + 4) & 0xFFFFFFFFL;
        }
        if (compressed != tableStart - levelOffset || decompressed != decompressedLength) return null;
        return new ZstdSeekTable(compressedOffsets, decompressedOffsets);
    }

    /**
     * Where to start decoding to reach that decompressed offset: {compressed offset, decompressed
     * offset} of the last frame starting at or before it, or the level start without a table.
     */
    static long[] seekPointBefore(@Nullable ZstdSeekTable table, long decompressedOffset) {
        if (table == null) return new long[] {0, 0};
        long[] starts = table.decompressedOffsets;
        int low = 0;
        int high = starts.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (starts[mid] <= decompressedOffset) low = mid;
            else high = mid - 1;
        }
        return new long[] {table.compressedOffsets[low], starts[low]};
    }

    int size() {
        return compressedOffsets.length;
    }
}
