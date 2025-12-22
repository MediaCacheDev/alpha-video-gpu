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
    public static native void nativeDeleteTexture(int textureId);
}
