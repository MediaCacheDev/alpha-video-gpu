package dev.mediacache.alphavideo.gpu;

/**
 * JNI bindings to the native library: zstd decompression, the software ASTC decoder and the GL
 * upload paths that {@link AlphaVideoGpuView} uses. Loading the class loads the library.
 */
final class AlphaVideoGpuNative {

    static {
        System.loadLibrary("alphavideogpu");
    }

    private AlphaVideoGpuNative() {}

    static native void nativeDeleteTexture(int textureId);
    static native boolean nativeDecompressZstdToBuffer(byte[] data, int offset, int compressedLength, java.nio.ByteBuffer outputBuffer, int uncompressedLength);  // Zero-copy to direct ByteBuffer
    static native int[] nativeLoadTextureArray(byte[] ktx2Data, boolean useAstc);

    // Streaming decompression methods (per-instance, returns/accepts stream ID)
    static native long nativeInternalZstdStreamingInit(int fd, long dataOffset, long compressedSize);  // Internal KTX2 zstd (supercompressionScheme=2); offset within the descriptor
    static native long nativeReaderZstdStreamingInit(long readerReference, long levelOffset, long compressedSize);  // The same over an AlphaVideoGpuReader, without waiting for its bytes
    static native long nativeStreamingDecompressContinueToBuffer(long streamId, java.nio.ByteBuffer outputBuffer, int maxOutputBytes);  // Zero-copy; status in the high 32 bits (STREAM_*)
    static native boolean nativeStreamingRestartAt(long streamId, long compressedOffset);  // At a zstd frame start in the level
    static native String nativeStreamingError(long streamId);  // Why the last call returned -1
    static native void nativeStreamingDecompressEnd(long streamId);

    /** nativeStreamingDecompressContinueToBuffer statuses: more to come, done, or a reader's bytes on their way. */
    static final int STREAM_MORE = 0;
    static final int STREAM_DONE = 1;
    static final int STREAM_PENDING = 2;

    // Streaming texture array methods
    static native int nativeCreateTextureArray(int width, int height, int layerCount, boolean useAstc, int glFormat);
    static native boolean nativeUploadTextureArrayLayer(int textureId, int layer, int width, int height, byte[] astcData, boolean useAstc, int glFormat, int blockW, int blockH);
    static native boolean nativeUploadTextureArrayLayerFromBuffer(int textureId, int layer, int width, int height, java.nio.ByteBuffer buffer, int dataLength, boolean useAstc, int glFormat, int blockW, int blockH);  // Zero-copy from direct ByteBuffer
    static native boolean nativeUploadTextureArrayLayerFromBufferWithOffset(int textureId, int layer, int width, int height, java.nio.ByteBuffer buffer, int offset, int dataLength, boolean useAstc, int glFormat, int blockW, int blockH);  // Zero-copy with offset

    // PBO-based async upload methods (reduces CPU stalls during texture upload)
    static native boolean nativeInitPBOs(int textureId, int bufferSize);  // Initialize double-buffered PBOs for a texture array
    static native void nativeCleanupPBOs(int textureId);  // Cleanup PBOs when texture array is destroyed
    static native boolean nativeUploadTextureArrayLayerPBO(int textureId, int layer, int width, int height, java.nio.ByteBuffer buffer, int dataLength, boolean useAstc, int glFormat, int blockW, int blockH);  // Async upload via PBO
}
