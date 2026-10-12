package dev.mediacache.alphavideo.gpu;

/**
 * JNI bindings to the native library: the Basis Universal transcoder, zstd and the GL upload
 * paths that {@link AlphaVideoGpuView} uses. Loading the class loads the library.
 */
final class AlphaVideoGpuNative {

    static {
        System.loadLibrary("alphavideogpu");
        nativeInit();
    }

    private AlphaVideoGpuNative() {}

    private static native void nativeInit();
    static native int[] nativeTranscodeKTX2(byte[] ktx2Data, int textureFormat);
    static native byte[] nativeTranscodeKTX2NoUpload(byte[] ktx2Data, int textureFormat);
    static native void nativeDeleteTexture(int textureId);
    static native byte[] nativeDecompressZstd(byte[] data, int offset, int compressedLength, int uncompressedLength);
    static native boolean nativeDecompressZstdToBuffer(byte[] data, int offset, int compressedLength, java.nio.ByteBuffer outputBuffer, int uncompressedLength);  // Zero-copy to direct ByteBuffer
    static native int[] nativeDecodeASTCToRGBA(byte[] astcData, int width, int height, int blockW, int blockH);
    static native int[] nativeLoadTextureArray(byte[] ktx2Data, boolean useAstc);

    // Streaming decompression methods (per-instance, returns/accepts stream ID)
    static native long nativeStreamingDecompressInit(byte[] compressedData);
    static native long nativeStreamingDecompressInitFromFd(int fd, long offset, long length);  // Memory-mapped, preferred; length < 0 = to end of file
    static native long nativeInternalZstdStreamingInit(int fd, long dataOffset, long compressedSize);  // Internal KTX2 zstd (supercompressionScheme=2); offset within the descriptor
    static native byte[] nativeStreamingDecompressContinue(long streamId, int maxOutputBytes);
    static native long nativeStreamingDecompressContinueToBuffer(long streamId, java.nio.ByteBuffer outputBuffer, int maxOutputBytes);  // Zero-copy
    static native void nativeStreamingDecompressEnd(long streamId);

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
