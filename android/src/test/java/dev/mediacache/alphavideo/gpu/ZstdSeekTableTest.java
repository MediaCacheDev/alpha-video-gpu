package dev.mediacache.alphavideo.gpu;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ZstdSeekTableTest {
    private static final Path FIXTURES = Paths.get("..", "tests", "fixtures");
    private static final int ZSTD_FRAME_MAGIC = 0xFD2FB528;

    /** A file holding a level at offset 16: frame bytes (contents unread), then the seek table. */
    private static byte[] fileWithLevel(int[][] frames, boolean checksums) {
        int entrySize = checksums ? 12 : 8;
        int framesSize = 0;
        for (int[] frame : frames) framesSize += frame[0];
        int tableSize = frames.length * entrySize + 9;
        ByteBuffer file = ByteBuffer.allocate(16 + framesSize + 8 + tableSize).order(ByteOrder.LITTLE_ENDIAN);
        file.position(16 + framesSize);
        file.putInt(0x184D2A5E).putInt(tableSize);
        for (int[] frame : frames) {
            file.putInt(frame[0]).putInt(frame[1]);
            if (checksums) file.putInt(0);
        }
        file.putInt(frames.length).put((byte) (checksums ? 0x80 : 0)).putInt(0x8F92EAB1);
        return file.array();
    }

    private static ZstdSeekTable parseLevel(byte[] file, long decompressedLength) {
        return ZstdSeekTable.parse(file, 16, file.length - 16, decompressedLength);
    }

    private static final int[][] FRAMES = {{100, 4000}, {50, 4000}, {70, 2000}};

    @Test
    public void readsWhereEachFrameStarts() {
        ZstdSeekTable table = parseLevel(fileWithLevel(FRAMES, false), 10000);
        assertNotNull(table);
        assertArrayEquals(new long[] {0, 100, 150}, table.compressedOffsets);
        assertArrayEquals(new long[] {0, 4000, 8000}, table.decompressedOffsets);
    }

    @Test
    public void readsTablesWithChecksums() {
        ZstdSeekTable table = parseLevel(fileWithLevel(FRAMES, true), 10000);
        assertNotNull(table);
        assertArrayEquals(new long[] {0, 100, 150}, table.compressedOffsets);
    }

    @Test
    public void rejectsTablesThatDoNotDescribeTheLevel() {
        assertNull(parseLevel(fileWithLevel(FRAMES, false), 10001));
        byte[] shorter = fileWithLevel(FRAMES, false);
        assertNull(ZstdSeekTable.parse(shorter, 17, shorter.length - 17, 10000));
        byte[] reserved = fileWithLevel(FRAMES, false);
        reserved[reserved.length - 5] = 0x04;
        assertNull(parseLevel(reserved, 10000));
        assertNull(parseLevel(fileWithLevel(new int[0][], false), 0));
        assertNull(ZstdSeekTable.parse(new byte[8], 0, 8, 0));
        assertNull(ZstdSeekTable.parse(new byte[100], 50, 60, 0));  // past the end of the file
    }

    @Test
    public void findsTheLastFrameAtOrBeforeTheOffset() {
        ZstdSeekTable table = parseLevel(fileWithLevel(FRAMES, false), 10000);
        assertArrayEquals(new long[] {0, 0}, ZstdSeekTable.seekPointBefore(table, 0));
        assertArrayEquals(new long[] {0, 0}, ZstdSeekTable.seekPointBefore(table, 3999));
        assertArrayEquals(new long[] {100, 4000}, ZstdSeekTable.seekPointBefore(table, 4000));
        assertArrayEquals(new long[] {150, 8000}, ZstdSeekTable.seekPointBefore(table, 9999));
        assertArrayEquals(new long[] {0, 0}, ZstdSeekTable.seekPointBefore(null, 123456));
    }

    private static void checkFixture(String name, int expectedSeekPoints) throws IOException {
        byte[] file = Files.readAllBytes(FIXTURES.resolve(name));
        ByteBuffer header = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        long levelOffset = header.getLong(80);
        ZstdSeekTable table = ZstdSeekTable.parse(file, levelOffset, header.getLong(88), header.getLong(96));
        if (expectedSeekPoints == 0) {
            assertNull(name, table);
            return;
        }
        assertNotNull(name, table);
        assertEquals(name, expectedSeekPoints, table.size());
        for (long offset : table.compressedOffsets) {
            assertEquals(name + " frame at " + offset, ZSTD_FRAME_MAGIC, header.getInt((int) (levelOffset + offset)));
        }
    }

    @Test
    public void findsTheFixturesSeekPoints() throws IOException {
        checkFixture("card_383x217/astc4x4_seek.ktx2", 20);
        checkFixture("card_256x256/astc4x4_seek.ktx2", 15);
        checkFixture("card_383x217/astc4x4.ktx2", 0);
        checkFixture("card_256x256/astc4x4_stream.ktx2", 0);
    }
}
