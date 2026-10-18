package dev.mediacache.alphavideo.gpu;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The seek table texenc writes after a KTX2 level's zstd frames: zstd's seekable format
 * (contrib/seekable_format), a skippable frame listing each frame's compressed and decompressed
 * size, ending with a 9-byte footer. The decoder can start at any frame. A level without one is
 * a single stream, which decodes only from its start.
 *
 * It sits at the end of the level, so the player reads the level's last few KB for it, not the
 * level: {@link #tailBytesNeeded}, then {@link #parseTail}.
 */
final class ZstdSeekTable {
    private static final int SEEK_TABLE_MAGIC = 0x184D2A5E;
    private static final int SEEKABLE_FOOTER_MAGIC = 0x8F92EAB1;
    private static final int FOOTER_SIZE = 9;
    private static final int SKIPPABLE_HEADER_SIZE = 8;
    private static final int CHECKSUM_FLAG = 0x80;
    private static final int RESERVED_BITS = 0x7C;

    /** Bytes of the level's tail to read first; enough for the table of any clip under 8000 seek points. */
    static final int TAIL_GUESS = 64 * 1024;

    /** Where each frame starts in the level data, ascending from 0. */
    final long[] compressedOffsets;
    /** Where each frame's output starts in the decompressed level, ascending from 0. */
    final long[] decompressedOffsets;

    private ZstdSeekTable(long[] compressedOffsets, long[] decompressedOffsets) {
        this.compressedOffsets = compressedOffsets;
        this.decompressedOffsets = decompressedOffsets;
    }

    /**
     * How many bytes from the level's end the table takes, footer included, read from the last
     * bytes of the level ({@code tail}, at least the 9-byte footer); -1 when there is no table.
     */
    static long tailBytesNeeded(byte[] tail) {
        if (tail.length < FOOTER_SIZE) return -1;
        ByteBuffer data = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        int end = tail.length;
        if (data.getInt(end - 4) != SEEKABLE_FOOTER_MAGIC) return -1;
        int descriptor = tail[end - 5] & 0xFF;
        if ((descriptor & RESERVED_BITS) != 0) return -1;
        long frameCount = data.getInt(end - FOOTER_SIZE) & 0xFFFFFFFFL;
        int entrySize = (descriptor & CHECKSUM_FLAG) != 0 ? 12 : 8;
        return SKIPPABLE_HEADER_SIZE + frameCount * entrySize + FOOTER_SIZE;
    }

    /**
     * The table in the last bytes of a level ({@code tail}, ending where the level ends), or null
     * when there is none, it doesn't fit in {@code tail}, or it doesn't describe exactly the
     * frames before it.
     */
    @Nullable
    static ZstdSeekTable parseTail(byte[] tail, long levelLength, long decompressedLength) {
        if (levelLength < SKIPPABLE_HEADER_SIZE + FOOTER_SIZE || tail.length > levelLength) return null;
        long needed = tailBytesNeeded(tail);
        if (needed < 0 || needed > tail.length) return null;
        ByteBuffer data = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        int end = tail.length;
        int descriptor = tail[end - 5] & 0xFF;
        long frameCount = data.getInt(end - FOOTER_SIZE) & 0xFFFFFFFFL;
        int entrySize = (descriptor & CHECKSUM_FLAG) != 0 ? 12 : 8;

        long tableSize = needed - SKIPPABLE_HEADER_SIZE;
        int tableStart = (int) (end - needed);
        if (frameCount == 0) return null;
        if (data.getInt(tableStart) != SEEK_TABLE_MAGIC) return null;
        if ((data.getInt(tableStart + 4) & 0xFFFFFFFFL) != tableSize) return null;

        long[] compressedOffsets = new long[(int) frameCount];
        long[] decompressedOffsets = new long[(int) frameCount];
        long compressed = 0;
        long decompressed = 0;
        for (int i = 0; i < frameCount; i++) {
            int entry = tableStart + SKIPPABLE_HEADER_SIZE + i * entrySize;
            compressedOffsets[i] = compressed;
            decompressedOffsets[i] = decompressed;
            compressed += data.getInt(entry) & 0xFFFFFFFFL;
            decompressed += data.getInt(entry + 4) & 0xFFFFFFFFL;
        }
        if (compressed != levelLength - needed || decompressed != decompressedLength) return null;
        return new ZstdSeekTable(compressedOffsets, decompressedOffsets);
    }

    /** The table at the end of the level [levelOffset, levelOffset + levelLength) of a whole file. */
    @Nullable
    static ZstdSeekTable parse(byte[] file, long levelOffset, long levelLength, long decompressedLength) {
        if (levelOffset < 0 || levelLength < 0 || levelOffset + levelLength > file.length) return null;
        byte[] level = Arrays.copyOfRange(file, (int) levelOffset, (int) (levelOffset + levelLength));
        return parseTail(level, levelLength, decompressedLength);
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
