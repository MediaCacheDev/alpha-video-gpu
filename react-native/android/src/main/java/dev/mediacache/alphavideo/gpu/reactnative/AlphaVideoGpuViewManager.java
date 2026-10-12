package dev.mediacache.alphavideo.gpu.reactnative;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;

import dev.mediacache.alphavideo.gpu.AlphaVideoGpuView;

import java.util.Map;

/** React Native view manager for {@link AlphaVideoGpuView}. */
public class AlphaVideoGpuViewManager extends SimpleViewManager<AlphaVideoGpuView> {

    public static final String REACT_CLASS = "AlphaVideoGpuView";

    @Override
    @NonNull
    public String getName() {
        return REACT_CLASS;
    }

    @Override
    @NonNull
    protected AlphaVideoGpuView createViewInstance(@NonNull ThemedReactContext reactContext) {
        AlphaVideoGpuView view = new AlphaVideoGpuView(reactContext);
        view.setPlayerCallback(new PlayerEventForwarder(view));
        return view;
    }

    @ReactProp(name = "source")
    public void setSource(AlphaVideoGpuView view, @Nullable ReadableMap source) {
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
    public void setAutoPlay(AlphaVideoGpuView view, boolean autoPlay) {
        view.setAutoPlay(autoPlay);
    }

    @ReactProp(name = "loop", defaultBoolean = true)
    public void setLoop(AlphaVideoGpuView view, boolean loop) {
        view.setLoop(loop);
    }

    @ReactProp(name = "playbackSpeed", defaultFloat = 1.0f)
    public void setPlaybackSpeed(AlphaVideoGpuView view, float speed) {
        view.setPlaybackSpeed(speed);
    }
    
    @ReactProp(name = "cacheKey")
    public void setCacheKey(AlphaVideoGpuView view, @Nullable String cacheKey) {
        // Pass cacheKey directly to the View so it can use it for downloads
        view.setCacheKey(cacheKey);
    }
    
    @ReactProp(name = "enableFrameEvents", defaultBoolean = false)
    public void setEnableFrameEvents(AlphaVideoGpuView view, boolean enable) {
        view.setEnableFrameEvents(enable);
    }
    
    @ReactProp(name = "paused", defaultBoolean = false)
    public void setPaused(AlphaVideoGpuView view, boolean paused) {
        view.setPaused(paused);
    }
    
    @ReactProp(name = "streamingRingSize", defaultInt = 16)
    public void setStreamingRingSize(AlphaVideoGpuView view, int size) {
        view.setStreamingRingSize(size);
    }
    
    @ReactProp(name = "streamingCacheSize", defaultInt = 60)
    public void setStreamingCacheSize(AlphaVideoGpuView view, int size) {
        view.setStreamingCacheSize(size);
    }
    
    @ReactProp(name = "streamingPrefetchAhead", defaultInt = 30)
    public void setStreamingPrefetchAhead(AlphaVideoGpuView view, int frames) {
        view.setStreamingPrefetchAhead(frames);
    }
    
    /**
     * Memory strategy for frame storage.
     * - "auto": Default behavior (VRAM for non-stream, RAM for stream)
     * - "vram": Store all frames in GPU VRAM. Best for many simultaneous players.
     * - "ram": Store decoded frames in RAM, upload per-frame. Lower VRAM usage.
     */
    @ReactProp(name = "memoryStrategy")
    public void setMemoryStrategy(AlphaVideoGpuView view, @Nullable String strategy) {
        view.setMemoryStrategy(strategy != null ? strategy : "auto");
    }
    
    /**
     * Enable streaming mode for texture array playback.
     * When true: Use streaming decompression with ring buffer (constant memory usage).
     * When false (default): Load all frames at once.
     */
    @ReactProp(name = "stream", defaultBoolean = false)
    public void setStream(AlphaVideoGpuView view, boolean stream) {
        view.setStream(stream);
    }
    
    /**
     * Explicit part sources for multi-part animations, for sources whose sibling files can't be
     * found from the source URI (res/raw, content://, presigned URLs).
     */
    @ReactProp(name = "parts")
    public void setParts(AlphaVideoGpuView view, @Nullable ReadableArray parts) {
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
    public void setVerbose(AlphaVideoGpuView view, boolean verbose) {
        view.setVerbose(verbose);
    }

    @Override
    public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
        return PlayerEventForwarder.exportedEvents();
    }

    @Override
    public void receiveCommand(@NonNull AlphaVideoGpuView view, String commandId, @Nullable ReadableArray args) {
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
            case "seekToTime":
                if (args != null && args.size() > 0) {
                    view.seekToTime(args.getDouble(0));
                }
                break;
        }
    }

    @Override
    public void onDropViewInstance(@NonNull AlphaVideoGpuView view) {
        super.onDropViewInstance(view);
        view.destroy();
    }
}
