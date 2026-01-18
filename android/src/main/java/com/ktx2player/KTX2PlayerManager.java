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
        if (source != null && source.hasKey("uri") && !source.isNull("uri")) {
            sourceUri = source.getString("uri");
        }
        
        if (sourceUri == null || sourceUri.isEmpty()) {
            Log.d(TAG, "setSource: null or empty");
            view.setSource(null);
            return;
        }
        
        Log.d(TAG, "setSource: " + sourceUri.substring(0, Math.min(80, sourceUri.length())) + "...");
        
        // Handle different URI schemes
        if (sourceUri.startsWith("http://") || sourceUri.startsWith("https://")) {
            // HTTP URLs are handled by the View's internal download logic
            view.setSource(sourceUri);
        } else if (sourceUri.startsWith("file://")) {
            // Strip file:// prefix for local files
            String path = sourceUri.substring(7);
            view.setSource(path);
        } else if (sourceUri.startsWith("asset://") || sourceUri.startsWith("asset:/")) {
            // Load from Android assets
            loadFromAsset(view, sourceUri);
        } else if (isDrawableResourceName(sourceUri)) {
            // Load from bundled assets (release build)
            loadFromBundledAsset(view, sourceUri);
        } else {
            // Plain path - pass directly
            view.setSource(sourceUri);
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
    
    @ReactProp(name = "gpuPlayback", defaultBoolean = false)
    public void setGpuPlayback(KTX2TextureView view, boolean gpuPlayback) {
        view.setGpuPlayback(gpuPlayback);
    }
    
    @ReactProp(name = "paused", defaultBoolean = false)
    public void setPaused(KTX2TextureView view, boolean paused) {
        view.setPaused(paused);
    }
    
    @ReactProp(name = "streamingRingSize", defaultInt = 16)
    public void setStreamingRingSize(KTX2TextureView view, int size) {
        view.setStreamingRingSize(size);
    }
    
    @ReactProp(name = "streamingCacheSize", defaultInt = 60)
    public void setStreamingCacheSize(KTX2TextureView view, int size) {
        view.setStreamingCacheSize(size);
    }
    
    @ReactProp(name = "streamingPrefetchAhead", defaultInt = 30)
    public void setStreamingPrefetchAhead(KTX2TextureView view, int frames) {
        view.setStreamingPrefetchAhead(frames);
    }
    
    /**
     * Memory strategy for frame storage.
     * - "auto": Default behavior (VRAM for non-stream, RAM for stream)
     * - "vram": Store all frames in GPU VRAM. Best for many simultaneous players.
     * - "ram": Store decoded frames in RAM, upload per-frame. Lower VRAM usage.
     */
    @ReactProp(name = "memoryStrategy")
    public void setMemoryStrategy(KTX2TextureView view, @Nullable String strategy) {
        view.setMemoryStrategy(strategy != null ? strategy : "auto");
    }
    
    /**
     * Enable streaming mode for texture array playback.
     * When true: Use streaming decompression with ring buffer (constant memory usage).
     * When false (default): Load all frames at once.
     */
    @ReactProp(name = "stream", defaultBoolean = false)
    public void setStream(KTX2TextureView view, boolean stream) {
        view.setStream(stream);
    }
    
    /**
     * Set explicit part URIs for multi-part animations.
     * This is needed for bundled assets where sibling files can't be auto-discovered.
     */
    @ReactProp(name = "parts")
    public void setParts(KTX2TextureView view, @Nullable ReadableArray parts) {
        if (parts == null || parts.size() == 0) {
            view.setPartUris(null);
            return;
        }
        
        String[] partUris = new String[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            ReadableMap part = parts.getMap(i);
            if (part != null && part.hasKey("uri") && !part.isNull("uri")) {
                String uri = part.getString("uri");
                // Resolve the URI same as setSource
                partUris[i] = resolveUri(uri);
            }
        }
        view.setPartUris(partUris);
    }
    
    /**
     * Resolve a URI to a path that KTX2TextureView can load
     */
    private String resolveUri(String uri) {
        if (uri == null || uri.isEmpty()) return null;
        
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            return uri;
        } else if (uri.startsWith("file://")) {
            return uri.substring(7);
        } else if (uri.startsWith("asset://") || uri.startsWith("asset:/")) {
            return uri;
        } else if (isDrawableResourceName(uri)) {
            // For bundled assets, find the actual asset path
            String foundPath = findKtx2Asset(uri, "");
            if (foundPath != null) {
                return "asset://" + foundPath;
            }
        }
        return uri;
    }
    
    /**
     * Check if the URI looks like a bundled asset resource name (from release build)
     * These look like: "funapp_assets_app_components_hg6rchz240x240" (no slashes, no scheme, no dots)
     */
    private boolean isDrawableResourceName(String uri) {
        // Resource names don't have slashes, colons, or dots
        // They also don't start with common path prefixes
        if (uri == null || uri.isEmpty()) return false;
        if (uri.contains("/") || uri.contains(":") || uri.contains(".")) return false;
        // Check if it looks like a resource name (alphanumeric with underscores, starting with letter)
        return uri.matches("^[a-zA-Z][a-zA-Z0-9_]*$");
    }
    
    /**
     * Load KTX2 from bundled assets (for release build)
     * React Native bundles custom assets into the APK's assets folder.
     * We pass asset:// URI directly - KTX2TextureView reads via AssetManager.
     */
    private void loadFromBundledAsset(KTX2TextureView view, String resourceName) {
        assetExecutor.execute(() -> {
            try {
                Log.d(TAG, "loadFromBundledAsset: " + resourceName);
                
                // Search assets folder for matching .ktx2 or .ktx2.zst file
                String foundPath = findKtx2Asset(resourceName, "");
                if (foundPath != null) {
                    Log.d(TAG, "Found asset: " + foundPath);
                    // Pass asset:// URI - no copy needed, view reads directly via AssetManager
                    view.post(() -> view.setSource("asset://" + foundPath));
                    return;
                }
                
                // Fallback: try raw resources (requires copy since Resources API differs)
                int resourceId = reactContext.getResources().getIdentifier(
                    resourceName, "raw", reactContext.getPackageName());
                if (resourceId != 0) {
                    Log.d(TAG, "Found raw resource ID: " + resourceId);
                    copyResourceToCache(view, resourceName, resourceId);
                    return;
                }
                
                Log.e(TAG, "Bundled asset not found: " + resourceName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load bundled asset: " + resourceName, e);
            }
        });
    }
    
    /**
     * Copy from res/raw to cache (only needed for raw resources, not assets)
     * Detects Zstd files by magic bytes and preserves .zst extension
     */
    private void copyResourceToCache(KTX2TextureView view, String resourceName, int resourceId) {
        try {
            // First, detect if file is Zstd compressed by reading magic bytes
            InputStream detectStream = reactContext.getResources().openRawResource(resourceId);
            byte[] magic = new byte[4];
            int bytesRead = detectStream.read(magic);
            detectStream.close();
            
            // Zstd magic bytes: 0x28 0xB5 0x2F 0xFD
            boolean isZst = bytesRead == 4 && 
                (magic[0] & 0xFF) == 0x28 && 
                (magic[1] & 0xFF) == 0xB5 && 
                (magic[2] & 0xFF) == 0x2F && 
                (magic[3] & 0xFF) == 0xFD;
            
            Log.d(TAG, "Detected isZst=" + isZst + " from magic bytes");
            
            String extension = isZst ? ".ktx2.zst" : ".ktx2";
            File cacheFile = new File(reactContext.getCacheDir(), 
                "ktx2_" + resourceName.hashCode() + extension);
            
            if (!cacheFile.exists()) {
                InputStream is = reactContext.getResources().openRawResource(resourceId);
                FileOutputStream fos = new FileOutputStream(cacheFile);
                byte[] buffer = new byte[16384];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                fos.close();
                is.close();
            }
            
            view.post(() -> view.setSource(cacheFile.getAbsolutePath()));
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy resource to cache", e);
        }
    }
    
    /**
     * Search assets folder for a .ktx2 or .ktx2.zst file matching the resource name
     */
    private String findKtx2Asset(String resourceName, String path) {
        try {
            String[] files = reactContext.getAssets().list(path);
            if (files == null) return null;
            
            String searchKey = resourceName.substring(resourceName.lastIndexOf("_") + 1).toLowerCase();
            // Also check if resourceName contains "zst" hint (from mangled name like src_assets_test_streamktx2zst)
            boolean searchingForZst = resourceName.toLowerCase().contains("zst");
            
            for (String file : files) {
                String fullPath = path.isEmpty() ? file : path + "/" + file;
                
                // Check for both .ktx2.zst (streaming) and .ktx2 (regular)
                if (file.endsWith(".ktx2.zst") || file.endsWith(".ktx2")) {
                    // Normalize filename for comparison - remove both possible extensions
                    String normalized = file.replace(".ktx2.zst", "").replace(".ktx2", "").replace("-", "").replace("_", "").toLowerCase();
                    String normalizedSearchKey = searchKey.replace("zst", "").replace("-", "").replace("_", "");
                    
                    if (normalized.contains(normalizedSearchKey) || normalizedSearchKey.contains(normalized)) {
                        // If searching for zst, prefer .zst files
                        if (searchingForZst && file.endsWith(".ktx2.zst")) {
                            return fullPath;
                        } else if (!searchingForZst && file.endsWith(".ktx2") && !file.endsWith(".ktx2.zst")) {
                            return fullPath;
                        } else {
                            // Return whatever matches if no preference
                            return fullPath;
                        }
                    }
                }
                
                // Recurse into directories
                String[] subFiles = reactContext.getAssets().list(fullPath);
                if (subFiles != null && subFiles.length > 0) {
                    String found = findKtx2Asset(resourceName, fullPath);
                    if (found != null) return found;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
    
    /**
     * Load KTX2 from Android assets (asset:// URI scheme)
     * No copy needed - KTX2TextureView reads directly via AssetManager
     */
    private void loadFromAsset(KTX2TextureView view, String assetUri) {
        // Normalize to asset:// format and pass directly to view
        String normalizedUri = assetUri.startsWith("asset://") ? assetUri : 
            "asset://" + assetUri.replace("asset:/", "").replaceFirst("^/", "");
        view.setSource(normalizedUri);
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
