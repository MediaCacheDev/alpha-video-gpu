package dev.mediacache.alphavideo.gpu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Ktx2MetadataTest {
    private static final Path FIXTURES = Paths.get("..", "tests", "fixtures");

    /** A 400x400, 181-layer header with the given vkFormat, then one KTXanimData entry if timing is given. */
    private static byte[] ktx2(int vkFormat, int[] durationAndTimescale) {
        int kvdLength = durationAndTimescale == null ? 0 : 4 + 12 + 12;
        ByteBuffer file = ByteBuffer.allocate(80 + kvdLength).order(ByteOrder.LITTLE_ENDIAN);
        file.put(new byte[] {(byte) 0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, (byte) 0xBB, 0x0D, 0x0A, 0x1A, 0x0A});
        file.putInt(12, vkFormat).putInt(20, 400).putInt(24, 400).putInt(32, 181);
        file.putInt(56, 80).putInt(60, kvdLength);
        if (durationAndTimescale != null) {
            file.position(80);
            file.putInt(12 + 12).put("KTXanimData\0".getBytes(StandardCharsets.US_ASCII));
            file.putInt(durationAndTimescale[0]).putInt(durationAndTimescale[1]).putInt(0);
        }
        return file.array();
    }

    @Test
    public void readsFractionalFrameRatesExactly() {
        Ktx2Metadata metadata = Ktx2Metadata.read(ktx2(157, new int[] {1001, 30000}));
        assertNotNull(metadata);
        assertEquals(30000.0 / 1001, metadata.frameRate, 0);
        assertEquals(400, metadata.width);
        assertEquals(400, metadata.height);
        assertEquals(181, metadata.frameCount);
        assertTrue(metadata.isAstcArray());
    }

    @Test
    public void playsFilesWithoutAnimDataAt30Fps() {
        assertEquals(30.0, Ktx2Metadata.read(ktx2(157, null)).frameRate, 0);
        assertEquals(30.0, Ktx2Metadata.read(ktx2(157, new int[] {0, 30})).frameRate, 0);
    }

    @Test
    public void acceptsOnlyAstcArrays() {
        assertFalse(Ktx2Metadata.read(ktx2(37, null)).isAstcArray());  // VK_FORMAT_R8G8B8A8_UNORM
        assertTrue(Ktx2Metadata.read(ktx2(184, null)).isAstcArray());  // ASTC 12x12 sRGB
        assertNull(Ktx2Metadata.read(new byte[100]));
    }

    @Test
    public void readsTheFixtures() throws IOException {
        for (String name : new String[] {"card_383x217/astc4x4.ktx2", "card_383x217/astc4x4_stream.ktx2", "card_256x256/astc8x8.ktx2"}) {
            Ktx2Metadata metadata = Ktx2Metadata.read(Files.readAllBytes(FIXTURES.resolve(name)));
            assertNotNull(name, metadata);
            assertEquals(name, 30.0, metadata.frameRate, 0);
            assertEquals(name, 60, metadata.frameCount);
            assertTrue(name, metadata.isAstcArray());
        }
    }
}
