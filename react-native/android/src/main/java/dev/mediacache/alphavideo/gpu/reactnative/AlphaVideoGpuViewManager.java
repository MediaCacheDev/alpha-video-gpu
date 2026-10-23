package dev.mediacache.alphavideo.gpu.reactnative;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;

import dev.mediacache.alphavideo.gpu.AlphaVideoGpuView;

import java.util.LinkedHashMap;
import java.util.Map;

/** React Native view manager for {@link AlphaVideoGpuView}. */
public class AlphaVideoGpuViewManager extends AlphaVideoGpuViewManagerSpec<AlphaVideoGpuView> {

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
    @Override
    public void setSource(AlphaVideoGpuView view, @Nullable ReadableMap source) {
        String sourceUri = null;
        if (source != null && source.hasKey("uri") && !source.isNull("uri")) {
            sourceUri = source.getString("uri");
        }

        // Adopt a cacheKey carried inside the source map before touching the source:
        // setSource() starts the read that uses the key, and React gives no
        // ordering guarantee between this setter and the standalone `cacheKey` one, so
        // relying on that prop alone can store under the URL instead.
        if (source != null && source.hasKey("cacheKey") && !source.isNull("cacheKey")) {
            String sourceCacheKey = source.getString("cacheKey");
            if (sourceCacheKey != null && !sourceCacheKey.isEmpty()) {
                view.setCacheKey(sourceCacheKey);
            }
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (source != null && source.hasKey("headers") && !source.isNull("headers")) {
            ReadableArray flat = source.getArray("headers");
            for (int i = 0; flat != null && i + 1 < flat.size(); i += 2) {
                headers.put(flat.getString(i), flat.getString(i + 1));
            }
        }
        view.setHeaders(headers);
        view.setDataSource(source != null && source.hasKey("dataSource") && !source.isNull("dataSource")
            ? source.getString("dataSource") : null);
        view.setSource(sourceUri == null || sourceUri.isEmpty() ? null : sourceUri);
    }

    @ReactProp(name = "autoPlay", defaultBoolean = true)
    @Override
    public void setAutoPlay(AlphaVideoGpuView view, boolean autoPlay) {
        view.setAutoPlay(autoPlay);
    }

    @ReactProp(name = "loop", defaultBoolean = true)
    @Override
    public void setLoop(AlphaVideoGpuView view, boolean loop) {
        view.setLoop(loop);
    }

    @ReactProp(name = "playbackSpeed", defaultFloat = 1.0f)
    @Override
    public void setPlaybackSpeed(AlphaVideoGpuView view, float speed) {
        view.setPlaybackSpeed(speed);
    }
    
    @ReactProp(name = "cacheKey")
    @Override
    public void setCacheKey(AlphaVideoGpuView view, @Nullable String cacheKey) {
        // Pass cacheKey directly to the View so it can use it for downloads
        view.setCacheKey(cacheKey);
    }
    
    /** "auto" (default), "disk", "memory" or "none"; see AlphaVideoGpuView.setStorage. */
    @ReactProp(name = "storage")
    @Override
    public void setStorage(AlphaVideoGpuView view, @Nullable String storage) {
        view.setStorage(storage);
    }

    /** 0 for the default, 512 MB. */
    @ReactProp(name = "maxStorageBytes", defaultDouble = 0)
    @Override
    public void setMaxStorageBytes(AlphaVideoGpuView view, double maxStorageBytes) {
        view.setMaxStorageBytes((long) maxStorageBytes);
    }

    @ReactProp(name = "enableFrameEvents", defaultBoolean = false)
    @Override
    public void setEnableFrameEvents(AlphaVideoGpuView view, boolean enable) {
        view.setEnableFrameEvents(enable);
    }
    
    @ReactProp(name = "paused", defaultBoolean = false)
    @Override
    public void setPaused(AlphaVideoGpuView view, boolean paused) {
        view.setPaused(paused);
    }
    
    @ReactProp(name = "streamingPrefetchAhead", defaultInt = 8)
    @Override
    public void setStreamingPrefetchAhead(AlphaVideoGpuView view, int frames) {
        view.setStreamingPrefetchAhead(frames);
    }
    
    /**
     * Memory strategy for frame storage.
     * - "vram" (default): Store frames in GPU VRAM. Best for many simultaneous players.
     * - "ram": Store decoded frames in RAM, upload per-frame. Lower VRAM usage.
     */
    @ReactProp(name = "memoryStrategy")
    @Override
    public void setMemoryStrategy(AlphaVideoGpuView view, @Nullable String strategy) {
        view.setMemoryStrategy(strategy != null ? strategy : "vram");
    }
    
    /**
     * Enable streaming mode for texture array playback.
     * When true (default): Use streaming decompression with ring buffer (constant memory usage).
     * When false: Load all frames at once.
     */
    @ReactProp(name = "stream", defaultBoolean = true)
    @Override
    public void setStream(AlphaVideoGpuView view, boolean stream) {
        view.setStream(stream);
    }
    
    @ReactProp(name = "verbose", defaultBoolean = false)
    @Override
    public void setVerbose(AlphaVideoGpuView view, boolean verbose) {
        view.setVerbose(verbose);
    }

    @Override
    public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
        return PlayerEventForwarder.exportedEvents();
    }

    @Override
    public void play(AlphaVideoGpuView view) {
        view.play();
    }

    @Override
    public void pause(AlphaVideoGpuView view) {
        view.pause();
    }

    @Override
    public void stop(AlphaVideoGpuView view) {
        view.stop();
    }

    @Override
    public void seekToFrame(AlphaVideoGpuView view, int frame) {
        view.seekToFrame(frame);
    }

    @Override
    public void seekToTime(AlphaVideoGpuView view, double seconds) {
        view.seekToTime(seconds);
    }

    @Override
    public void onDropViewInstance(@NonNull AlphaVideoGpuView view) {
        super.onDropViewInstance(view);
        view.destroy();
    }
}
