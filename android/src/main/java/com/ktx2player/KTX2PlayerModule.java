package com.ktx2player;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.module.annotations.ReactModule;

/**
 * Native module for KTX2 texture transcoding.
 * Provides JNI bindings to the Basis Universal transcoder.
 */
@ReactModule(name = KTX2PlayerModule.NAME)
public class KTX2PlayerModule extends ReactContextBaseJavaModule {

    public static final String NAME = "KTX2PlayerModule";
    private static boolean sLibraryLoaded = false;

    static {
        try {
            System.loadLibrary("ktx2player");
            sLibraryLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            sLibraryLoaded = false;
        }
    }

    public KTX2PlayerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        if (sLibraryLoaded) {
            nativeInit();
        }
    }

    @Override
    @NonNull
    public String getName() {
        return NAME;
    }

    public static boolean isLibraryLoaded() {
        return sLibraryLoaded;
    }

    // Native methods used by KTX2TextureView
    private static native void nativeInit();
    public static native int[] nativeTranscodeKTX2(byte[] ktx2Data, int textureFormat);
    public static native byte[] nativeTranscodeKTX2NoUpload(byte[] ktx2Data, int textureFormat);
    public static native void nativeDeleteTexture(int textureId);
    public static native byte[] nativeDecompressZstd(byte[] data, int offset, int compressedLength, int uncompressedLength);
    public static native boolean nativeDecompressZstdToBuffer(byte[] data, int offset, int compressedLength, java.nio.ByteBuffer outputBuffer, int uncompressedLength);  // Zero-copy to direct ByteBuffer
    public static native int[] nativeDecodeASTCToRGBA(byte[] astcData, int width, int height);
    public static native int[] nativeLoadTextureArray(byte[] ktx2Data, boolean useAstc);
    
    // Streaming decompression methods (per-instance, returns/accepts stream ID)
    public static native long nativeStreamingDecompressInit(byte[] compressedData);
    public static native long nativeStreamingDecompressInitFromFile(String filePath);  // Memory-mapped, preferred
    public static native byte[] nativeStreamingDecompressContinue(long streamId, int maxOutputBytes);
    public static native long nativeStreamingDecompressContinueToBuffer(long streamId, java.nio.ByteBuffer outputBuffer, int maxOutputBytes);  // Zero-copy
    public static native void nativeStreamingDecompressEnd(long streamId);
    
    // Streaming texture array methods
    public static native int nativeCreateTextureArray(int width, int height, int layerCount, boolean useAstc);
    public static native boolean nativeUploadTextureArrayLayer(int textureId, int layer, int width, int height, byte[] astcData, boolean useAstc);
    public static native boolean nativeUploadTextureArrayLayerFromBuffer(int textureId, int layer, int width, int height, java.nio.ByteBuffer buffer, int dataLength, boolean useAstc);  // Zero-copy from direct ByteBuffer
    public static native boolean nativeUploadTextureArrayLayerFromBufferWithOffset(int textureId, int layer, int width, int height, java.nio.ByteBuffer buffer, int offset, int dataLength, boolean useAstc);  // Zero-copy with offset
}
