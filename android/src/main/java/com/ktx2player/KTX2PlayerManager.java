package com.ktx2player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * React Native ViewManager for KTX2PlayerView.
 */
public class KTX2PlayerManager extends SimpleViewManager<KTX2TextureView> {

    public static final String REACT_CLASS = "KTX2PlayerView";
    private static final String TAG = "KTX2PlayerMgr";
    
    private final ReactApplicationContext reactContext;
    private final ExecutorService assetExecutor = Executors.newFixedThreadPool(2);
    
    public KTX2PlayerManager(ReactApplicationContext reactContext) {
        this.reactContext = reactContext;
    }

    @Override
    @NonNull
    public String getName() {
        return REACT_CLASS;
    }

    @Override
    @NonNull
    protected KTX2TextureView createViewInstance(@NonNull ThemedReactContext reactContext) {
        return new KTX2TextureView(reactContext);
    }

    @ReactProp(name = "source")
    public void setSource(KTX2TextureView view, @Nullable ReadableMap source) {
        String sourceUri = null;
        String metadata = null;
        if (source != null) {
            if (source.hasKey("uri") && !source.isNull("uri")) {
                sourceUri = source.getString("uri");
            } else if (source.hasKey("path") && !source.isNull("path")) {
                sourceUri = source.getString("path");
            }
            if (source.hasKey("metadata") && !source.isNull("metadata")) {
                metadata = source.getString("metadata");
            }
        }
        
        if (sourceUri == null || sourceUri.isEmpty()) {
            Log.d(TAG, "setSource: null or empty");
            view.setSource(null);
            return;
        }
        
        Log.d(TAG, "setSource: " + sourceUri.substring(0, Math.min(80, sourceUri.length())) + "...");
        if (metadata != null) {
            Log.d(TAG, "metadata provided: " + metadata.substring(0, Math.min(80, metadata.length())) + "...");
        }
        
        // Handle different URI schemes like WebMAlpha
        if (sourceUri.startsWith("http://") || sourceUri.startsWith("https://")) {
            // HTTP URLs are handled by the View's internal download logic
            view.setSource(sourceUri, metadata);
        } else if (sourceUri.startsWith("file://")) {
            // Strip file:// prefix for local files
            String path = sourceUri.substring(7);
            view.setSource(path, metadata);
        } else if (sourceUri.startsWith("asset://") || sourceUri.startsWith("asset:/")) {
            // Load from Android assets
            loadFromAsset(view, sourceUri, metadata);
        } else {
            // Plain path - pass directly
            view.setSource(sourceUri, metadata);
        }
    }

    @ReactProp(name = "autoPlay", defaultBoolean = true)
    public void setAutoPlay(KTX2TextureView view, boolean autoPlay) {
        view.setAutoPlay(autoPlay);
    }

    @ReactProp(name = "loop", defaultBoolean = true)
    public void setLoop(KTX2TextureView view, boolean loop) {
        view.setLoop(loop);
    }

    @ReactProp(name = "playbackSpeed", defaultFloat = 1.0f)
    public void setPlaybackSpeed(KTX2TextureView view, float speed) {
        view.setPlaybackSpeed(speed);
    }
    
    @ReactProp(name = "cacheKey")
    public void setCacheKey(KTX2TextureView view, @Nullable String cacheKey) {
        // Pass cacheKey directly to the View so it can use it for downloads
        view.setCacheKey(cacheKey);
    }
    
    @ReactProp(name = "enableFrameEvents", defaultBoolean = false)
    public void setEnableFrameEvents(KTX2TextureView view, boolean enable) {
        view.setEnableFrameEvents(enable);
    }
    
    /**
     * Load KTX2 from Android assets by copying to cache first
     */
    private void loadFromAsset(KTX2TextureView view, String assetUri, String metadata) {
        final String assetPath = assetUri.replace("asset://", "").replace("asset:/", "")
            .replaceFirst("^/", "");
        
        assetExecutor.execute(() -> {
            try {
                // Copy KTX2 file
                File tempKtx2File = new File(reactContext.getCacheDir(), 
                    "ktx2_asset_" + assetPath.hashCode() + ".ktx2");
                
                if (!tempKtx2File.exists()) {
                    InputStream is = reactContext.getAssets().open(assetPath);
                    FileOutputStream fos = new FileOutputStream(tempKtx2File);
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                    fos.close();
                    is.close();
                    Log.d(TAG, "Copied KTX2 asset to cache: " + tempKtx2File.getAbsolutePath());
                }
                
                view.post(() -> view.setSource(tempKtx2File.getAbsolutePath(), metadata));
            } catch (Exception e) {
                Log.e(TAG, "Failed to load asset: " + assetPath, e);
            }
        });
    }

    @Override
    public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
        return MapBuilder.<String, Object>builder()
                .put("onFrameUpdate", MapBuilder.of("registrationName", "onFrameUpdate"))
                .put("onStateChange", MapBuilder.of("registrationName", "onStateChange"))
                .put("onFinished", MapBuilder.of("registrationName", "onFinished"))
                .put("onError", MapBuilder.of("registrationName", "onError"))
                .build();
    }

    @Override
    public void receiveCommand(@NonNull KTX2TextureView view, String commandId, @Nullable ReadableArray args) {
        switch (commandId) {
            case "play":
                view.play();
                break;
            case "pause":
                view.pause();
                break;
            case "stop":
                view.stop();
                break;
            case "seekToFrame":
                if (args != null && args.size() > 0) {
                    view.seekToFrame(args.getInt(0));
                }
                break;
        }
    }

    @Override
    public void onDropViewInstance(@NonNull KTX2TextureView view) {
        super.onDropViewInstance(view);
        view.destroy();
    }
}
