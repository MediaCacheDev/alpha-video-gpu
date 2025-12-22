package com.ktx2player;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.module.annotations.ReactModule;

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

    @ReactMethod
    public void createPlayer(ReadableMap options, Promise promise) {
        String playerId = "player_" + System.currentTimeMillis();
        KTX2PlayerRegistry.getInstance().createPlayer(playerId, getReactApplicationContext(), options);
        promise.resolve(playerId);
    }

    @ReactMethod
    public void load(String playerId, String source, Promise promise) {
        try {
            KTX2Player player = KTX2PlayerRegistry.getInstance().getPlayer(playerId);
            if (player != null) {
                player.load(source);
                promise.resolve(true);
            } else {
                promise.reject("PLAYER_NOT_FOUND", "Player not found: " + playerId);
            }
        } catch (Exception e) {
            promise.reject("LOAD_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void play(String playerId, Promise promise) {
        try {
            KTX2Player player = KTX2PlayerRegistry.getInstance().getPlayer(playerId);
            if (player != null) {
                player.play();
                promise.resolve(true);
            } else {
                promise.reject("PLAYER_NOT_FOUND", "Player not found: " + playerId);
            }
        } catch (Exception e) {
            promise.reject("PLAY_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void pause(String playerId, Promise promise) {
        try {
            KTX2Player player = KTX2PlayerRegistry.getInstance().getPlayer(playerId);
            if (player != null) {
                player.pause();
                promise.resolve(true);
            } else {
                promise.reject("PLAYER_NOT_FOUND", "Player not found: " + playerId);
            }
        } catch (Exception e) {
            promise.reject("PAUSE_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void stop(String playerId, Promise promise) {
        try {
            KTX2Player player = KTX2PlayerRegistry.getInstance().getPlayer(playerId);
            if (player != null) {
                player.stop();
                promise.resolve(true);
            } else {
                promise.reject("PLAYER_NOT_FOUND", "Player not found: " + playerId);
            }
        } catch (Exception e) {
            promise.reject("STOP_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void destroy(String playerId, Promise promise) {
        KTX2PlayerRegistry.getInstance().destroyPlayer(playerId);
        promise.resolve(true);
    }

    // Native methods
    private static native void nativeInit();
    public static native int[] nativeTranscodeKTX2(byte[] ktx2Data, int textureFormat);
    public static native void nativeDeleteTexture(int textureId);
}
