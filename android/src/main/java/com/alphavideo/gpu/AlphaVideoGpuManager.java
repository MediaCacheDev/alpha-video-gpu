package com.alphavideo.gpu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;

import java.util.Map;

/**
 * React Native ViewManager for AlphaVideoGpuView.
 */
public class AlphaVideoGpuManager extends SimpleViewManager<AlphaVideoGpuTextureView> {

    public static final String REACT_CLASS = "AlphaVideoGpuView";

    public AlphaVideoGpuManager(ReactApplicationContext reactContext) {
    }

    @Override
    @NonNull
    public String getName() {
        return REACT_CLASS;
    }

    @Override
    @NonNull
    protected AlphaVideoGpuTextureView createViewInstance(@NonNull ThemedReactContext reactContext) {
        return new AlphaVideoGpuTextureView(reactContext);
    }

    @ReactProp(name = "source")
    public void setSource(AlphaVideoGpuTextureView view, @Nullable ReadableMap source) {
        String sourceUri = null;
        if (source != null && source.hasKey("uri") && !source.isNull("uri")) {
            sourceUri = source.getString("uri");
        }

        // Adopt a cacheKey carried inside the source map before touching the source:
        // setSource() starts the download that reads the key, and React gives no
        // ordering guarantee between this setter and the standalone `cacheKey` one, so
        // relying on that prop alone can download under a URL-derived name instead.
        if (source != null && source.hasKey("cacheKey") && !source.isNull("cacheKey")) {
            String sourceCacheKey = source.getString("cacheKey");
            if (sourceCacheKey != null && !sourceCacheKey.isEmpty()) {
                view.setCacheKey(sourceCacheKey);
            }
        }
        
        view.setSource(sourceUri == null || sourceUri.isEmpty() ? null : sourceUri);
    }

    @ReactProp(name = "autoPlay", defaultBoolean = true)
    public void setAutoPlay(AlphaVideoGpuTextureView view, boolean autoPlay) {
        view.setAutoPlay(autoPlay);
    }

    @ReactProp(name = "loop", defaultBoolean = true)
    public void setLoop(AlphaVideoGpuTextureView view, boolean loop) {
        view.setLoop(loop);
    }

    @ReactProp(name = "playbackSpeed", defaultFloat = 1.0f)
    public void setPlaybackSpeed(AlphaVideoGpuTextureView view, float speed) {
        view.setPlaybackSpeed(speed);
    }
    
    @ReactProp(name = "cacheKey")
    public void setCacheKey(AlphaVideoGpuTextureView view, @Nullable String cacheKey) {
        // Pass cacheKey directly to the View so it can use it for downloads
        view.setCacheKey(cacheKey);
    }
    
    @ReactProp(name = "enableFrameEvents", defaultBoolean = false)
    public void setEnableFrameEvents(AlphaVideoGpuTextureView view, boolean enable) {
        view.setEnableFrameEvents(enable);
    }
    
    @ReactProp(name = "gpuPlayback", defaultBoolean = false)
    public void setGpuPlayback(AlphaVideoGpuTextureView view, boolean gpuPlayback) {
        view.setGpuPlayback(gpuPlayback);
    }
    
    @ReactProp(name = "paused", defaultBoolean = false)
    public void setPaused(AlphaVideoGpuTextureView view, boolean paused) {
        view.setPaused(paused);
    }
    
    @ReactProp(name = "streamingRingSize", defaultInt = 16)
    public void setStreamingRingSize(AlphaVideoGpuTextureView view, int size) {
        view.setStreamingRingSize(size);
    }
    
    @ReactProp(name = "streamingCacheSize", defaultInt = 60)
    public void setStreamingCacheSize(AlphaVideoGpuTextureView view, int size) {
        view.setStreamingCacheSize(size);
    }
    
    @ReactProp(name = "streamingPrefetchAhead", defaultInt = 30)
    public void setStreamingPrefetchAhead(AlphaVideoGpuTextureView view, int frames) {
        view.setStreamingPrefetchAhead(frames);
    }
    
    /**
     * Memory strategy for frame storage.
     * - "auto": Default behavior (VRAM for non-stream, RAM for stream)
     * - "vram": Store all frames in GPU VRAM. Best for many simultaneous players.
     * - "ram": Store decoded frames in RAM, upload per-frame. Lower VRAM usage.
     */
    @ReactProp(name = "memoryStrategy")
    public void setMemoryStrategy(AlphaVideoGpuTextureView view, @Nullable String strategy) {
        view.setMemoryStrategy(strategy != null ? strategy : "auto");
    }
    
    /**
     * Enable streaming mode for texture array playback.
     * When true: Use streaming decompression with ring buffer (constant memory usage).
     * When false (default): Load all frames at once.
     */
    @ReactProp(name = "stream", defaultBoolean = false)
    public void setStream(AlphaVideoGpuTextureView view, boolean stream) {
        view.setStream(stream);
    }
    
    /**
     * Explicit part sources for multi-part animations, for sources whose sibling files can't be
     * found from the source URI (res/raw, content://, presigned URLs).
     */
    @ReactProp(name = "parts")
    public void setParts(AlphaVideoGpuTextureView view, @Nullable ReadableArray parts) {
        if (parts == null || parts.size() == 0) {
            view.setParts(null, null);
            return;
        }

        String[] uris = new String[parts.size()];
        String[] cacheKeys = new String[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            ReadableMap part = parts.getMap(i);
            if (part == null) continue;
            if (part.hasKey("uri") && !part.isNull("uri")) uris[i] = part.getString("uri");
            if (part.hasKey("cacheKey") && !part.isNull("cacheKey")) cacheKeys[i] = part.getString("cacheKey");
        }
        view.setParts(uris, cacheKeys);
    }

    @ReactProp(name = "verbose", defaultBoolean = false)
    public void setVerbose(AlphaVideoGpuTextureView view, boolean verbose) {
        view.setVerbose(verbose);
    }

    @Override
    public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
        return MapBuilder.<String, Object>builder()
                .put("onLoad", MapBuilder.of("registrationName", "onLoad"))
                .put("onFrameUpdate", MapBuilder.of("registrationName", "onFrameUpdate"))
                .put("onStateChange", MapBuilder.of("registrationName", "onStateChange"))
                .put("onFinished", MapBuilder.of("registrationName", "onFinished"))
                .put("onError", MapBuilder.of("registrationName", "onError"))
                .build();
    }

    @Override
    public void receiveCommand(@NonNull AlphaVideoGpuTextureView view, String commandId, @Nullable ReadableArray args) {
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
    public void onDropViewInstance(@NonNull AlphaVideoGpuTextureView view) {
        super.onDropViewInstance(view);
        view.destroy();
    }
}
