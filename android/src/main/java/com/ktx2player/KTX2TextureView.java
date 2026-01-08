package com.ktx2player;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.TextureView;

import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.UIManagerHelper;
import com.facebook.react.uimanager.events.EventDispatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Arrays;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * KTX2TextureView - TextureView-based KTX2 player with proper transparency support.
 * 
 * Uses TextureView instead of GLSurfaceView to allow proper view stacking
 * with alpha transparency in React Native.
 */
public class KTX2TextureView extends TextureView implements TextureView.SurfaceTextureListener {
    private static final String TAG = "KTX2TextureView";
    
    // GL constants
    private static final int GL_COMPRESSED_RGBA_ASTC_4x4_KHR = 0x93B0;
    private static final int GL_COMPRESSED_RGBA8_ETC2_EAC = 0x9278;
    
    // React Native context
    private ReactContext mReactContext;
    
    // Source
    private String mSource;
    private String mCacheKey;
    private boolean mSourceLoaded = false;
    
    // Playback settings
    private boolean autoPlay = true;
    private boolean loop = true;
    private float playbackSpeed = 1.0f;
    private boolean enableFrameEvents = false;  // Disabled by default for performance
    private boolean gpuPlayback = false;  // GPU-controlled frame timing (experimental)
    private final AtomicBoolean isPaused = new AtomicBoolean(false);  // Pause from React prop
    
    // Animation state
    private int currentFrame = 0;
    private int totalFrames = 0;
    private float frameRate = 30.0f;
    private long frameIntervalNanos;
    
    // Grid layout
    private int framesPerRow = 0;
    private int framesPerPart = 0;
    private int partCount = 0;
    
    // GLB container support
    private boolean isGLBContainer = false;
    private List<byte[]> glbKtx2Parts = null;
    private int frameWidth = 0;
    private int frameHeight = 0;
    
    // Part info
    private static class PartInfo {
        int index;
        int startFrame;
        int endFrame;
        int frameCount;
        int rows;
        int columns;
        String file;
    }
    private List<PartInfo> partInfoList = new ArrayList<>();
    
    // Explicit part URIs (for bundled assets where sibling files can't be auto-discovered)
    private String[] explicitPartUris = null;
    
    
    // OpenGL resources
    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;
    
    private int program = 0;
    private int[] textureIds = new int[0];
    private int[] textureWidths = new int[0];
    private int[] textureHeights = new int[0];
    private int uniformFrameUVLocation = -1;
    private int uniformTextureLocation = -1;
    
    // GPU playback uniforms
    private int uniformElapsedTimeLocation = -1;
    private int uniformFpsLocation = -1;
    private int uniformTotalFramesLocation = -1;
    private int uniformFramesPerRowLocation = -1;
    private int uniformFramesPerPartLocation = -1;
    private int uniformFrameWidthLocation = -1;
    private int uniformFrameHeightLocation = -1;
    private int uniformTextureWidthLocation = -1;
    private int uniformTextureHeightLocation = -1;
    private int uniformGpuPlaybackLocation = -1;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    
    // Cached texture format
    private int cachedTextureFormat = -1;
    
    // Native ASTC mode flag
    private boolean isNativeASTC = false;
    
    // Stream mode flag from metadata (for internal KTX2 streaming)
    private boolean metadataStreamMode = false;
    
    // Texture array mode (for packingMode: 'array')
    private boolean useTextureArray = false;
    private int textureArrayId = 0;
    private int textureArrayLayerCount = 0;
    private boolean streamingRingBufferMode = false; // True when using ring buffer for streaming
    private int streamingRingSize = 0; // Size of ring buffer (for layer index calculation)
    
    // RAM-heavy mode storage (for memoryStrategy: 'ram' in non-stream mode)
    // Uses contiguous direct ByteBuffer for zero-copy GPU uploads
    private ByteBuffer ramFrameBuffer = null;  // All decoded frames in single contiguous buffer
    private int ramBytesPerFrame = 0;          // Size of each frame in bytes
    private int ramFrameCount = 0;             // Total number of frames
    private boolean ramHeavyMode = false;      // True when using RAM-heavy non-stream mode
    private int ramUploadedFrame = -1;         // Currently uploaded frame in GPU ring buffer
    
    // Configurable streaming parameters (set via props)
    private int configStreamingRingSize = 16;      // GPU ring buffer slots (VRAM)
    private int configStreamingCacheSize = 60;     // CPU frame cache size (RAM)
    private int configStreamingPrefetchAhead = 30; // Frames to decode ahead of playback
    
    // Memory strategy: "auto", "vram", "ram"
    // - auto: VRAM for non-stream, RAM for stream (default behavior)
    // - vram: All frames in GPU VRAM (best for many simultaneous players)
    // - ram: Decoded frames in RAM, upload per-frame (lower VRAM usage)
    private String memoryStrategy = "auto";
    
    // Threading
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    private final AtomicBoolean isPlaybackRunning = new AtomicBoolean(false);  // Guards against concurrent startPlayback calls
    
    // Shaders
    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "  gl_Position = aPosition;\n" +
        "  vTexCoord = aTexCoord;\n" +
        "}\n";

    private static final String FRAGMENT_SHADER =
        "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
        "precision highp float;\n" +
        "precision highp sampler2D;\n" +
        "#else\n" +
        "precision mediump float;\n" +
        "precision mediump sampler2D;\n" +
        "#endif\n" +
        "varying vec2 vTexCoord;\n" +
        "uniform sampler2D uTexture;\n" +
        "uniform vec4 uFrameUV;\n" +
        "// GPU playback uniforms\n" +
        "uniform float uElapsedTime;\n" +
        "uniform float uFps;\n" +
        "uniform float uTotalFrames;\n" +
        "uniform float uFramesPerRow;\n" +
        "uniform float uFramesPerPart;\n" +
        "uniform float uFrameWidth;\n" +
        "uniform float uFrameHeight;\n" +
        "uniform float uTextureWidth;\n" +
        "uniform float uTextureHeight;\n" +
        "uniform int uGpuPlayback;\n" +
        "void main() {\n" +
        "  vec2 uv;\n" +
        "  if (uGpuPlayback == 1) {\n" +
        "    // GPU-controlled frame calculation\n" +
        "    float frameIndex = floor(mod(uElapsedTime * uFps, uTotalFrames));\n" +
        "    float frameInPart = floor(mod(frameIndex, uFramesPerPart));\n" +
        "    float col = floor(mod(frameInPart, uFramesPerRow));\n" +
        "    float row = floor(frameInPart / uFramesPerRow);\n" +
        "    float minU = (col * uFrameWidth) / uTextureWidth;\n" +
        "    float maxU = ((col + 1.0) * uFrameWidth) / uTextureWidth;\n" +
        "    float minV = (row * uFrameHeight) / uTextureHeight;\n" +
        "    float maxV = ((row + 1.0) * uFrameHeight) / uTextureHeight;\n" +
        "    uv = vec2(mix(minU, maxU, vTexCoord.x), mix(minV, maxV, vTexCoord.y));\n" +
        "  } else {\n" +
        "    // CPU-controlled frame (original behavior)\n" +
        "    uv = mix(uFrameUV.xy, uFrameUV.zw, vTexCoord);\n" +
        "  }\n" +
        "  vec4 color = texture2D(uTexture, uv);\n" +
        "  gl_FragColor = color;\n" +
        "}\n";
    
    // Texture array shader (for packingMode: 'array')
    private static final String TEXTURE_ARRAY_FRAGMENT_SHADER =
        "#version 300 es\n" +
        "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
        "precision highp float;\n" +
        "precision highp sampler2DArray;\n" +
        "#else\n" +
        "precision mediump float;\n" +
        "precision mediump sampler2DArray;\n" +
        "#endif\n" +
        "in vec2 vTexCoord;\n" +
        "out vec4 fragColor;\n" +
        "uniform sampler2DArray uTextureArray;\n" +
        "uniform float uLayer;\n" +
        "void main() {\n" +
        "  fragColor = texture(uTextureArray, vec3(vTexCoord, uLayer));\n" +
        "}\n";
    
    private static final String TEXTURE_ARRAY_VERTEX_SHADER =
        "#version 300 es\n" +
        "in vec4 aPosition;\n" +
        "in vec2 aTexCoord;\n" +
        "out vec2 vTexCoord;\n" +
        "void main() {\n" +
        "  gl_Position = aPosition;\n" +
        "  vTexCoord = aTexCoord;\n" +
        "}\n";
    
    // Texture array shader program and uniforms
    private int arrayProgram = 0;
    private int arrayUniformLayerLocation = -1;
    
    private static final float[] VERTEX_DATA = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };
    
    private static final float[] TEXCOORD_DATA = {
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    };
    
    public KTX2TextureView(Context context) {
        super(context);
        init(context);
    }
    
    public KTX2TextureView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    public KTX2TextureView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }
    
    private void init(Context context) {
        if (context instanceof ReactContext) {
            mReactContext = (ReactContext) context;
        }
        
        setSurfaceTextureListener(this);
        setOpaque(false);  // Critical for transparency
        isDestroyed.set(false);
        
        // Don't intercept touches
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        
        // Setup vertex buffers
        vertexBuffer = ByteBuffer.allocateDirect(VERTEX_DATA.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(VERTEX_DATA);
        vertexBuffer.position(0);
        
        texCoordBuffer = ByteBuffer.allocateDirect(TEXCOORD_DATA.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(TEXCOORD_DATA);
        texCoordBuffer.position(0);
        
        frameIntervalNanos = (long) (1_000_000_000L / frameRate);
    }
    
    private synchronized ExecutorService getOrCreateExecutor() {
        if (executor == null || executor.isShutdown()) {
            // Use a thread pool with 2 threads to allow preloading while playback runs
            executor = Executors.newFixedThreadPool(2);
        }
        return executor;
    }
    
    private synchronized void shutdownExecutor() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            executor = null;
        }
    }
    
    // React Native event sending (new architecture compatible)
    private void sendEvent(String eventName, WritableMap params) {
        if (mReactContext != null) {
            int surfaceId = UIManagerHelper.getSurfaceId(this);
            EventDispatcher eventDispatcher = UIManagerHelper.getEventDispatcherForReactTag(mReactContext, getId());
            if (eventDispatcher != null) {
                eventDispatcher.dispatchEvent(new KTX2Event(surfaceId, getId(), eventName, params));
            }
        }
    }
    
    // Custom event class for new architecture
    private static class KTX2Event extends com.facebook.react.uimanager.events.Event<KTX2Event> {
        private final String eventName;
        private final WritableMap params;
        
        KTX2Event(int surfaceId, int viewId, String eventName, WritableMap params) {
            super(surfaceId, viewId);
            this.eventName = eventName;
            this.params = params;
        }
        
        @Override
        public String getEventName() {
            return eventName;
        }
        
        @Override
        protected WritableMap getEventData() {
            return params;
        }
    }
    
    private void notifyStateChange(String state) {
        WritableMap event = Arguments.createMap();
        event.putString("state", state);
        sendEvent("onStateChange", event);
    }
    
    private void notifyFrameUpdate() {
        WritableMap event = Arguments.createMap();
        event.putInt("frame", currentFrame);
        event.putInt("totalFrames", totalFrames);
        sendEvent("onFrameUpdate", event);
    }
    
    private void notifyFinished() {
        sendEvent("onFinished", Arguments.createMap());
    }
    
    private void notifyError(String error) {
        WritableMap event = Arguments.createMap();
        event.putString("error", error);
        sendEvent("onError", event);
        Log.e(TAG, "Error: " + error);
    }
    
    // Public API
    public void setSource(String source) {
        Log.d(TAG, "setSource: " + (source != null ? source.substring(0, Math.min(80, source.length())) : "null"));
        
        stopPlayback();
        mSource = source;
        mSourceLoaded = false;
        
        if (source != null && isAvailable()) {
            startPlayback();
        }
    }
    
    public void setCacheKey(String cacheKey) {
        mCacheKey = cacheKey;
        Log.d(TAG, "setCacheKey: " + cacheKey);
    }
    
    public void setAutoPlay(boolean autoPlay) {
        this.autoPlay = autoPlay;
    }
    
    public void setLoop(boolean loop) {
        this.loop = loop;
    }
    
    public void setPlaybackSpeed(float speed) {
        this.playbackSpeed = speed;
    }
    
    public void setEnableFrameEvents(boolean enable) {
        this.enableFrameEvents = enable;
    }
    
    public void setGpuPlayback(boolean gpuPlayback) {
        this.gpuPlayback = gpuPlayback;
        Log.d(TAG, "setGpuPlayback: " + gpuPlayback);
    }
    
    public void setStreamingRingSize(int size) {
        this.configStreamingRingSize = Math.max(4, Math.min(64, size));
        Log.d(TAG, "setStreamingRingSize: " + this.configStreamingRingSize);
    }
    
    public void setStreamingCacheSize(int size) {
        this.configStreamingCacheSize = Math.max(10, Math.min(200, size));
        Log.d(TAG, "setStreamingCacheSize: " + this.configStreamingCacheSize);
    }
    
    public void setStreamingPrefetchAhead(int frames) {
        this.configStreamingPrefetchAhead = Math.max(5, Math.min(100, frames));
        Log.d(TAG, "setStreamingPrefetchAhead: " + this.configStreamingPrefetchAhead);
    }
    
    /**
     * Set memory strategy for frame storage.
     * - "auto": Default behavior (VRAM for non-stream, RAM for stream)
     * - "vram": All frames in GPU VRAM (best for many simultaneous players)
     * - "ram": Decoded frames in RAM, upload per-frame (lower VRAM usage)
     */
    public void setMemoryStrategy(String strategy) {
        if ("vram".equals(strategy) || "ram".equals(strategy)) {
            this.memoryStrategy = strategy;
        } else {
            this.memoryStrategy = "auto";
        }
        Log.d(TAG, "setMemoryStrategy: " + this.memoryStrategy);
    }
    
    /**
     * Set paused state from React prop.
     * When paused, playback stops but resources are retained.
     */
    public void setPaused(boolean paused) {
        boolean wasPaused = isPaused.getAndSet(paused);
        if (paused != wasPaused) {
            Log.d(TAG, "setPaused: " + paused);
            if (paused) {
                // Stop playback
                isPlaying.set(false);
            } else {
                // Resume playback if we were playing before and source is loaded
                if (mSourceLoaded && autoPlay) {
                    isPlaying.set(true);
                }
            }
        }
    }
    
    /**
     * Set explicit part URIs for multi-part animations.
     * Used when bundled assets can't be auto-discovered from sibling files.
     */
    public void setPartUris(String[] partUris) {
        this.explicitPartUris = partUris;
        Log.d(TAG, "setPartUris: " + (partUris != null ? partUris.length + " parts" : "null"));
    }
    
    public void play() {
        if (!isPlaying.get() && textureIds != null && textureIds.length > 0) {
            isPlaying.set(true);
            shouldStop.set(false);
            notifyStateChange("playing");
        }
    }
    
    public void pause() {
        if (isPlaying.get()) {
            isPlaying.set(false);
            notifyStateChange("paused");
        }
    }
    
    public void stop() {
        isPlaying.set(false);
        shouldStop.set(true);
        currentFrame = 0;
        notifyStateChange("stopped");
    }
    
    public void seekToFrame(int frame) {
        currentFrame = Math.max(0, Math.min(frame, totalFrames - 1));
        notifyFrameUpdate();
    }
    
    public void destroy() {
        isDestroyed.set(true);
        shouldStop.set(true);
        isPlaying.set(false);
        shutdownExecutor();
    }
    
    private void startPlayback() {
        if (mSource == null || !isAvailable() || isDestroyed.get()) {
            return;
        }
        
        // Prevent concurrent playback attempts
        if (!isPlaybackRunning.compareAndSet(false, true)) {
            Log.d(TAG, "startPlayback: already running, skipping");
            return;
        }
        
        shouldStop.set(false);
        
        try {
            getOrCreateExecutor().execute(() -> {
                try {
                    playAnimation();
                } catch (Exception e) {
                    Log.e(TAG, "Playback error", e);
                    mainHandler.post(() -> notifyError(e.getMessage()));
                } finally {
                    cleanupGL();
                    isPlaybackRunning.set(false);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to start playback", e);
            isPlaybackRunning.set(false);
        }
    }
    
    private void stopPlayback() {
        shouldStop.set(true);
        isPlaying.set(false);
    }
    
    private void playAnimation() {
        String localPath = mSource;
        
        // Handle HTTP URLs
        if (mSource.startsWith("http://") || mSource.startsWith("https://")) {
            localPath = downloadToCache(mSource);
            if (localPath == null) {
                mainHandler.post(() -> notifyError("Failed to download KTX2 file"));
                return;
            }
        } else if (mSource.startsWith("file://")) {
            localPath = mSource.substring(7);
        }
        
        // Initialize OpenGL
        SurfaceTexture surfaceTexture = getSurfaceTexture();
        if (surfaceTexture == null || !initGL(surfaceTexture)) {
            mainHandler.post(() -> notifyError("Failed to initialize OpenGL"));
            return;
        }
        
        // Detect texture format first (needed for transcoding)
        detectAndCacheTextureFormat();
        
        // Check for streaming mode (.ktx2.zst files)
        boolean isStreamingZst = localPath.endsWith(".zst") || localPath.contains(".zst?") || 
                                  localPath.matches(".*\\.ktx2\\.[a-f0-9]+\\.zst.*");
        if (isStreamingZst) {
            Log.i(TAG, "Detected streaming mode (.zst): " + localPath);
            playStreamingAnimation(localPath);
            return;
        }
        
        // Check for uncompressed streaming mode BEFORE loading file into RAM
        // This avoids allocating ~29MB per player instance for streaming files
        if (checkAndPlayUncompressedStreaming(localPath)) {
            return; // Handled by uncompressed streaming path
        }
        
        // Read file data (only for non-streaming paths)
        byte[] fileData = null;
        try {
            fileData = readFileBytes(localPath);
        } catch (IOException e) {
            mainHandler.post(() -> notifyError("Failed to read file: " + e.getMessage()));
            return;
        }
        
        // Reset GLB state
        isGLBContainer = false;
        glbKtx2Parts = null;
        
        // Check if this is a GLB container or KTX2 file
        String metadataJson;
        if (isGLBData(fileData)) {
            Log.i(TAG, "Detected GLB container");
            metadataJson = parseGLBContainer(fileData);
            if (metadataJson == null) {
                mainHandler.post(() -> notifyError("Failed to parse GLB container"));
                return;
            }
        } else {
            // Regular KTX2 file
            metadataJson = extractKTX2Metadata(fileData);
            if (metadataJson == null) {
                mainHandler.post(() -> notifyError("No metadata found in KTX2 file. Ensure the file was encoded with metadata injection."));
                return;
            }
            Log.i(TAG, "Using embedded KTX2 metadata");
        }
        
        // Parse metadata
        try {
            parseMetadata(metadataJson);
            Log.i(TAG, "isNativeASTC: " + isNativeASTC);
        } catch (Exception e) {
            mainHandler.post(() -> notifyError("Failed to parse metadata: " + e.getMessage()));
            return;
        }
        
        // Check for internal streaming mode with Zstd compression (streamMode: true in metadata)
        if (metadataStreamMode && useTextureArray) {
            // Check supercompressionScheme from KTX2 header
            int supercompressionScheme = getSupercompressionScheme(fileData);
            Log.i(TAG, "Internal streaming mode detected, supercompression=" + supercompressionScheme);
            
            if (supercompressionScheme == 2) {
                // Internal Zstd compression - use streaming decompression
                playStreamingAnimation(localPath);
                return;
            } else {
                Log.w(TAG, "Unsupported supercompression for streaming: " + supercompressionScheme + ", falling back to non-stream");
            }
        }
        
        // Load textures (non-streaming path)
        try {
            loadPartTextures(localPath);
        } catch (Exception e) {
            mainHandler.post(() -> notifyError("Failed to load textures: " + e.getMessage()));
            return;
        }
        
        // Initialize shaders
        if (!initShaders()) {
            mainHandler.post(() -> notifyError("Failed to initialize shaders"));
            return;
        }
        
        mSourceLoaded = true;
        mainHandler.post(() -> notifyStateChange("ready"));
        
        // Start playing if autoPlay
        if (autoPlay) {
            isPlaying.set(true);
            mainHandler.post(() -> notifyStateChange("playing"));
        }
        
        // Render loop
        long frameIntervalNs = (long) (1_000_000_000.0 / (frameRate * playbackSpeed));
        
        // GPU playback: use elapsed time instead of frame counting
        long gpuPlaybackStartTime = System.nanoTime();
        gpuElapsedTime = 0.0f;
        
        Log.d(TAG, "Starting render loop (gpuPlayback=" + gpuPlayback + ")");
        
        do {
            long loopStartTime = System.nanoTime();
            currentFrame = 0;
            
            if (gpuPlayback) {
                // GPU playback mode: simpler loop, GPU calculates frame from elapsed time
                while (!shouldStop.get() && !isDestroyed.get()) {
                    if (isPlaying.get()) {
                        // Update elapsed time
                        gpuElapsedTime = (System.nanoTime() - gpuPlaybackStartTime) / 1_000_000_000.0f;
                        
                        // Handle looping
                        float animDuration = totalFrames / (frameRate * playbackSpeed);
                        if (!loop && gpuElapsedTime >= animDuration) {
                            mainHandler.post(() -> {
                                notifyFinished();
                                notifyStateChange("stopped");
                            });
                            break;
                        }
                        
                        renderFrame();
                        
                        // Sleep to target ~60fps render rate (GPU handles frame timing)
                        try {
                            Thread.sleep(16);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    } else {
                        // Paused - render current frame, don't advance time
                        renderFrame();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            } else {
                // CPU playback mode: original frame-by-frame loop
                while (!shouldStop.get() && !isDestroyed.get() && currentFrame < totalFrames) {
                    if (isPlaying.get()) {
                        renderFrame();
                        if (enableFrameEvents) {
                            mainHandler.post(() -> notifyFrameUpdate());
                        }
                        
                        currentFrame++;
                        if (currentFrame >= totalFrames) {
                            if (loop) {
                                currentFrame = 0;
                                loopStartTime = System.nanoTime();
                            } else {
                                mainHandler.post(() -> {
                                    notifyFinished();
                                    notifyStateChange("stopped");
                                });
                                break;
                            }
                        }
                        
                        long targetTimeNs = loopStartTime + (currentFrame * frameIntervalNs);
                        long sleepNs = targetTimeNs - System.nanoTime();
                        
                        if (sleepNs > 1_000_000) {
                            try {
                                Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    } else {
                        // Paused - still render current frame but sleep longer
                        renderFrame();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        } while (loop && !shouldStop.get() && !isDestroyed.get() && !gpuPlayback);
    }
    
    /**
     * Play streaming animation from .ktx2.zst file.
     * Uses progressive decompression and ring buffer for memory efficiency.
     */
    private void playStreamingAnimation(String localPath) {
        Log.i(TAG, "Starting streaming animation: " + localPath);
        
        // Try to use memory-mapped file init (preferred - avoids copying to Java heap)
        // Falls back to byte array init for HTTP URLs or assets
        final long[] streamId = {0};
        final String[] effectivePath = {localPath};
        boolean useMmap = false;
        
        // Check if this is a local file path that can be memory-mapped
        if (!localPath.startsWith("http://") && !localPath.startsWith("https://") &&
            !localPath.startsWith("asset://") && !localPath.startsWith("assets://")) {
            
            // For file:// URLs, strip the prefix
            String filePath = localPath.startsWith("file://") ? localPath.substring(7) : localPath;
            File file = new File(filePath);
            
            if (file.exists() && file.canRead()) {
                Log.i(TAG, "Using memory-mapped file init: " + filePath);
                streamId[0] = KTX2PlayerModule.nativeStreamingDecompressInitFromFile(filePath);
                effectivePath[0] = filePath;
                useMmap = true;
            }
        }
        
        // Fallback: download/read to byte array (for HTTP URLs, assets, or if mmap failed)
        byte[] compressedData = null;
        if (streamId[0] == 0) {
            try {
                // For HTTP URLs, download to cache first
                String pathToRead = localPath;
                if (localPath.startsWith("http://") || localPath.startsWith("https://")) {
                    pathToRead = downloadToCache(localPath);
                    if (pathToRead == null) {
                        mainHandler.post(() -> notifyError("Failed to download streaming file"));
                        return;
                    }
                    effectivePath[0] = pathToRead;
                    
                    // Try mmap on the cached file
                    File cachedFile = new File(pathToRead);
                    if (cachedFile.exists() && cachedFile.canRead()) {
                        Log.i(TAG, "Using memory-mapped cached file: " + pathToRead);
                        streamId[0] = KTX2PlayerModule.nativeStreamingDecompressInitFromFile(pathToRead);
                        useMmap = true;
                    }
                }
                
                // If still no stream ID, fall back to byte array
                if (streamId[0] == 0) {
                    compressedData = readFileBytes(pathToRead);
                    Log.i(TAG, "Using byte array init (fallback): " + compressedData.length + " bytes");
                    streamId[0] = KTX2PlayerModule.nativeStreamingDecompressInit(compressedData);
                }
            } catch (IOException e) {
                mainHandler.post(() -> notifyError("Failed to read streaming file: " + e.getMessage()));
                return;
            }
        }
        
        if (streamId[0] == 0) {
            mainHandler.post(() -> notifyError("Failed to initialize streaming decompression"));
            return;
        }
        
        // For loop restart, we need to remember the path (mmap) or keep the data (fallback)
        final boolean finalUseMmap = useMmap;
        final String finalPath = effectivePath[0];
        final byte[] finalCompressedData = useMmap ? null : compressedData;  // Only keep if not using mmap
        
        // Buffer size for decompression chunks
        final int bufferSize = 64 * 1024; // 64KB chunks
        
        // Decompress header first to get dimensions
        ByteArrayOutputStream headerStream = new ByteArrayOutputStream();
        int width = 0, height = 0, layerCount = 0;
        long level0Offset = 0;
        boolean headerParsed = false;
        
        while (!headerParsed) {
            byte[] chunk = KTX2PlayerModule.nativeStreamingDecompressContinue(streamId[0], bufferSize);
            if (chunk == null || chunk.length == 0) {
                mainHandler.post(() -> notifyError("Streaming decompression failed"));
                KTX2PlayerModule.nativeStreamingDecompressEnd(streamId[0]);
                return;
            }
            
            byte status = chunk[0];
            if (status == 2) { // error
                mainHandler.post(() -> notifyError("Streaming decompression error"));
                KTX2PlayerModule.nativeStreamingDecompressEnd(streamId[0]);
                return;
            }
            
            // Append data (skip status byte)
            if (chunk.length > 1) {
                headerStream.write(chunk, 1, chunk.length - 1);
            }
            
            // Try to parse header once we have enough data
            byte[] headerData = headerStream.toByteArray();
            if (headerData.length >= 104 && !headerParsed) {
                // Check KTX2 magic
                if (headerData[0] == (byte)0xAB && headerData[1] == 0x4B) {
                    ByteBuffer bb = ByteBuffer.wrap(headerData).order(ByteOrder.LITTLE_ENDIAN);
                    width = bb.getInt(20);
                    height = bb.getInt(24);
                    layerCount = bb.getInt(32);
                    level0Offset = bb.getLong(80);
                    headerParsed = true;
                    Log.i(TAG, "Streaming texture: " + width + "x" + height + ", " + layerCount + " layers, offset=" + level0Offset);
                }
            }
            
            if (status == 1) { // done
                break;
            }
        }
        
        if (!headerParsed || layerCount == 0) {
            mainHandler.post(() -> notifyError("Failed to parse KTX2 header from stream"));
            KTX2PlayerModule.nativeStreamingDecompressEnd(streamId[0]);
            return;
        }
        
        // Extract metadata from header
        byte[] headerData = headerStream.toByteArray();
        String metadataJson = extractKTX2Metadata(headerData);
        if (metadataJson == null) {
            mainHandler.post(() -> notifyError("No metadata found in streaming KTX2"));
            KTX2PlayerModule.nativeStreamingDecompressEnd(streamId[0]);
            return;
        }
        
        // Parse metadata
        try {
            parseMetadata(metadataJson);
        } catch (Exception e) {
            mainHandler.post(() -> notifyError("Failed to parse metadata: " + e.getMessage()));
            KTX2PlayerModule.nativeStreamingDecompressEnd(streamId[0]);
            return;
        }
        
        // Use texture array mode
        useTextureArray = true;
        totalFrames = layerCount;
        frameWidth = width;
        frameHeight = height;
        
        // Check for ASTC support
        boolean useAstc = (cachedTextureFormat == GL_COMPRESSED_RGBA_ASTC_4x4_KHR);
        
        // Determine effective memory strategy for streaming
        // - "auto" or "ram": Cache in CPU RAM, small ring buffer in GPU VRAM
        // - "vram": Cache in GPU VRAM (larger texture array), minimal CPU RAM
        final boolean useVramStrategy = "vram".equals(memoryStrategy);
        
        // Streaming parameters (from props or defaults)
        // RAM mode: small GPU ring buffer, larger CPU cache
        // VRAM mode: larger GPU cache (replaces CPU cache), minimal CPU buffer
        final int GPU_CACHE_SIZE = useVramStrategy ? configStreamingCacheSize : configStreamingRingSize;
        final int CPU_CACHE_SIZE = useVramStrategy ? 5 : configStreamingCacheSize;  // Minimal CPU cache for VRAM mode
        final int PREFETCH_AHEAD = configStreamingPrefetchAhead;  // Same prefetch behavior for both modes
        
        Log.i(TAG, "Streaming params: strategy=" + memoryStrategy + 
              ", gpuCache=" + GPU_CACHE_SIZE + ", cpuCache=" + CPU_CACHE_SIZE + 
              ", prefetch=" + PREFETCH_AHEAD + ", vramMode=" + useVramStrategy);
        
        // Create texture array:
        // - VRAM mode: Larger GPU cache (e.g., 60 slots) - this IS the sliding window
        // - RAM mode: Small ring buffer (e.g., 16 slots) - CPU RAM is the sliding window
        int textureLayerCount = Math.min(GPU_CACHE_SIZE, layerCount);
        textureArrayId = KTX2PlayerModule.nativeCreateTextureArray(width, height, textureLayerCount, useAstc);
        if (textureArrayId == 0) {
            mainHandler.post(() -> notifyError("Failed to create texture array"));
            KTX2PlayerModule.nativeStreamingDecompressEnd(streamId[0]);
            return;
        }
        textureArrayLayerCount = textureLayerCount;
        
        if (useVramStrategy) {
            Log.i(TAG, "Created VRAM streaming cache: ID=" + textureArrayId + ", " + width + "x" + height + 
                  ", " + textureLayerCount + " GPU slots (total frames: " + layerCount + ")");
        } else {
            Log.i(TAG, "Created RAM streaming ring buffer: ID=" + textureArrayId + ", " + width + "x" + height + 
                  ", " + textureLayerCount + " GPU slots, " + CPU_CACHE_SIZE + " CPU cache (total frames: " + layerCount + ")");
        }
        
        // Both modes use ring buffer rotation - the difference is WHERE the cache lives
        // RAM mode: CPU cache is primary, GPU ring buffer is secondary
        // VRAM mode: GPU cache is primary (larger), CPU cache is minimal (just decode buffer)
        streamingRingBufferMode = true;  // Both modes use ring buffer slot calculation
        streamingRingSize = textureLayerCount;
        
        // Initialize shaders
        if (!initShaders()) {
            mainHandler.post(() -> notifyError("Failed to initialize shaders"));
            KTX2PlayerModule.nativeStreamingDecompressEnd(streamId[0]);
            return;
        }
        
        // Calculate bytes per layer for streaming decode
        // IMPORTANT: The KTX2 file always contains ASTC data (zstd compressed).
        // Transcoding to RGBA32 happens at GPU upload time in nativeUploadTextureArrayLayer.
        // So for streaming decode, we always use ASTC size regardless of useAstc flag.
        int blocksX = (width + 3) / 4;
        int blocksY = (height + 3) / 4;
        int bytesPerLayer = blocksX * blocksY * 16;  // ASTC 4x4: 16 bytes per block
        
        // Track which virtual position is in each GPU slot (for both RAM and VRAM modes)
        // ringSlots[slot] = virtualPos of frame currently in that GPU slot (-1 if empty)
        int[] ringSlots = new int[textureLayerCount];
        java.util.Arrays.fill(ringSlots, -1);
        
        // Decompression state
        final int[] decodePos = {0};  // Current sequential decode position
        final int[] bytesToSkip = {(int) level0Offset};
        final boolean[] decompressEnded = {false};
        
        // Pre-allocated direct ByteBuffer for zero-copy decompression
        final ByteBuffer decompressBuffer = ByteBuffer.allocateDirect(bufferSize);
        
        // VRAM mode: Use direct ByteBuffer for frame accumulation (zero-copy to GPU)
        // RAM mode: Use byte[] pool for CPU cache
        final ByteBuffer frameAccumulatorBuffer;
        final java.util.Queue<byte[]> bufferPool;
        final java.util.Map<Integer, byte[]> frameCache;
        
        if (useVramStrategy) {
            // VRAM mode: Single direct ByteBuffer for accumulation, upload directly to GPU
            // No byte[] pool needed - frames go straight from ByteBuffer to GPU
            frameAccumulatorBuffer = ByteBuffer.allocateDirect(bytesPerLayer + bufferSize);
            bufferPool = null;
            frameCache = new java.util.concurrent.ConcurrentHashMap<>();  // Only for blocked frames
            Log.i(TAG, "VRAM mode: Pre-allocated direct frame buffer: " + (bytesPerLayer + bufferSize) + " bytes");
        } else {
            // RAM mode: byte[] pool for CPU cache (existing behavior)
            frameAccumulatorBuffer = null;
            final int POOL_SIZE = CPU_CACHE_SIZE + 5;
            bufferPool = new java.util.concurrent.ConcurrentLinkedQueue<>();
            for (int i = 0; i < POOL_SIZE; i++) {
                bufferPool.offer(new byte[bytesPerLayer]);
            }
            frameCache = new java.util.concurrent.ConcurrentHashMap<>();
            Log.i(TAG, "RAM mode: Pre-allocated buffer pool: " + POOL_SIZE + " buffers x " + bytesPerLayer + " bytes = " + 
                  (POOL_SIZE * bytesPerLayer / 1024) + " KB");
        }
        
        // For RAM mode, keep ByteArrayOutputStream for accumulation
        ByteArrayOutputStream frameAccumulator = useVramStrategy ? null : new ByteArrayOutputStream(bytesPerLayer + bufferSize);
        Log.i(TAG, "Pre-allocated direct decompression buffer: " + bufferSize + " bytes");
        
        // Skip header bytes from already-read data
        byte[] existingData = headerStream.toByteArray();
        if (existingData.length > bytesToSkip[0]) {
            int dataToWrite = existingData.length - bytesToSkip[0];
            if (useVramStrategy) {
                frameAccumulatorBuffer.put(existingData, bytesToSkip[0], dataToWrite);
            } else {
                frameAccumulator.write(existingData, bytesToSkip[0], dataToWrite);
            }
            bytesToSkip[0] = 0;
        } else {
            bytesToSkip[0] -= existingData.length;
        }
        
        final int finalWidth = width;
        final int finalHeight = height;
        final int finalBytesPerLayer = bytesPerLayer;
        final boolean finalUseAstc = useAstc;
        final int finalGpuCacheSize = textureLayerCount;  // GPU cache size (ring buffer or VRAM cache)
        final long finalLevel0Offset = level0Offset;
        
        mSourceLoaded = true;
        mainHandler.post(() -> notifyStateChange("ready"));
        
        if (autoPlay && !isPaused.get()) {
            isPlaying.set(true);
            mainHandler.post(() -> notifyStateChange("playing"));
        }
        
        // Track which "loop iteration" the decoder is on (for wrap-around prefetching)
        final int[] decoderLoopIteration = {0};
        
        // Virtual positions are bounded to [0, 2*totalFrames) to prevent overflow
        // This is sufficient to distinguish current loop from next loop frames
        final int vPosModulus = 2 * totalFrames;
        
        // Helper: Decode frames into CPU cache (up to prefetch ahead, with wrap-around)
        // virtualPlaybackPos = bounded position that distinguishes current vs next loop
        // VRAM mode: Uses direct ByteBuffer, uploads directly to GPU (zero-copy)
        // RAM mode: Uses byte[] pool and ByteArrayOutputStream
        java.util.function.IntConsumer decodeFrames = (virtualPlaybackPos) -> {
            // Calculate how many frames ahead we need to decode
            // Use relative distance to handle wraparound correctly
            int currentVirtualDecodePos = (decoderLoopIteration[0] * totalFrames + decodePos[0]) % vPosModulus;
            int framesToDecode = (currentVirtualDecodePos - virtualPlaybackPos + vPosModulus) % vPosModulus;
            int targetFramesAhead = PREFETCH_AHEAD;
            
            while (framesToDecode < targetFramesAhead) {
                
                // If decompression ended for current loop, restart for next loop (if looping)
                if (decompressEnded[0]) {
                    if (!loop) break;
                    // Restart decompression for next loop
                    decoderLoopIteration[0]++;
                    decodePos[0] = 0;
                    decompressEnded[0] = false;
                    if (useVramStrategy) {
                        frameAccumulatorBuffer.clear();
                    } else {
                        frameAccumulator.reset();
                    }
                    bytesToSkip[0] = (int) finalLevel0Offset;
                    KTX2PlayerModule.nativeStreamingDecompressEnd(streamId[0]);
                    // Use mmap if available, otherwise fall back to byte array
                    if (finalUseMmap) {
                        streamId[0] = KTX2PlayerModule.nativeStreamingDecompressInitFromFile(finalPath);
                    } else {
                        streamId[0] = KTX2PlayerModule.nativeStreamingDecompressInit(finalCompressedData);
                    }
                    Log.i(TAG, "Prefetch wrap-around: restarting decompression for loop " + decoderLoopIteration[0]);
                    continue;
                }
                
                // Get current accumulator size
                int accumulatorSize = useVramStrategy ? frameAccumulatorBuffer.position() : frameAccumulator.size();
                
                // Skip header bytes if needed (using zero-copy ByteBuffer)
                while (bytesToSkip[0] > 0 && !decompressEnded[0]) {
                    decompressBuffer.clear();
                    long result = KTX2PlayerModule.nativeStreamingDecompressContinueToBuffer(streamId[0], decompressBuffer, bufferSize);
                    if (result == -1) { decompressEnded[0] = true; break; }
                    int status = (int)(result >> 32);
                    int bytesWritten = (int)(result & 0xFFFFFFFFL);
                    if (bytesWritten >= bytesToSkip[0]) {
                        decompressBuffer.position(bytesToSkip[0]);
                        decompressBuffer.limit(bytesWritten);
                        int dataToWrite = bytesWritten - bytesToSkip[0];
                        if (useVramStrategy) {
                            // Zero-copy: transfer directly between ByteBuffers
                            ByteBuffer slice = decompressBuffer.slice();
                            slice.limit(dataToWrite);
                            frameAccumulatorBuffer.put(slice);
                        } else {
                            byte[] temp = new byte[dataToWrite];
                            decompressBuffer.get(temp);
                            frameAccumulator.write(temp, 0, temp.length);
                        }
                        bytesToSkip[0] = 0;
                    } else {
                        bytesToSkip[0] -= bytesWritten;
                    }
                    if (status == 1) { decompressEnded[0] = true; break; }
                }
                
                if (decompressEnded[0]) continue; // Will restart on next iteration if looping
                
                // Update accumulator size after header skip
                accumulatorSize = useVramStrategy ? frameAccumulatorBuffer.position() : frameAccumulator.size();
                
                // Decompress until we have a full frame
                while (accumulatorSize < finalBytesPerLayer && !decompressEnded[0]) {
                    decompressBuffer.clear();
                    long result = KTX2PlayerModule.nativeStreamingDecompressContinueToBuffer(streamId[0], decompressBuffer, bufferSize);
                    if (result == -1) { decompressEnded[0] = true; break; }
                    int status = (int)(result >> 32);
                    int bytesWritten = (int)(result & 0xFFFFFFFFL);
                    if (bytesWritten > 0) {
                        decompressBuffer.limit(bytesWritten);
                        decompressBuffer.position(0);
                        if (useVramStrategy) {
                            // Zero-copy: transfer directly between ByteBuffers
                            frameAccumulatorBuffer.put(decompressBuffer);
                        } else {
                            byte[] temp = new byte[bytesWritten];
                            decompressBuffer.get(temp);
                            frameAccumulator.write(temp, 0, temp.length);
                        }
                        accumulatorSize += bytesWritten;
                    }
                    if (status == 1) { decompressEnded[0] = true; break; }
                }
                
                if (accumulatorSize >= finalBytesPerLayer) {
                    int virtualPos = (decoderLoopIteration[0] * totalFrames + decodePos[0]) % vPosModulus;
                    int actualFrame = decodePos[0];
                    decodePos[0]++;
                    
                    if (useVramStrategy) {
                        // VRAM mode: Upload directly from ByteBuffer to GPU (zero-copy)
                        int gpuSlot = actualFrame % finalGpuCacheSize;
                        int slotCurrentVPos = ringSlots[gpuSlot];
                        int slotAge = (virtualPlaybackPos - slotCurrentVPos + vPosModulus) % vPosModulus;
                        boolean slotIsOld = slotAge > 0 && slotAge < vPosModulus / 2;
                        boolean canOverwrite = (slotCurrentVPos == -1) || 
                                               (slotCurrentVPos == virtualPos) ||
                                               slotIsOld;
                        
                        if (canOverwrite && ringSlots[gpuSlot] != virtualPos) {
                            // Prepare buffer for reading
                            frameAccumulatorBuffer.flip();
                            boolean uploaded = KTX2PlayerModule.nativeUploadTextureArrayLayerFromBuffer(
                                textureArrayId, gpuSlot, finalWidth, finalHeight, 
                                frameAccumulatorBuffer, finalBytesPerLayer, finalUseAstc);
                            if (uploaded) {
                                ringSlots[gpuSlot] = virtualPos;
                            }
                            // Keep remaining data (if any) for next frame
                            if (frameAccumulatorBuffer.remaining() > finalBytesPerLayer) {
                                frameAccumulatorBuffer.position(finalBytesPerLayer);
                                frameAccumulatorBuffer.compact();
                            } else {
                                frameAccumulatorBuffer.clear();
                            }
                        } else {
                            // Can't upload yet - slot blocked. Store in CPU cache temporarily
                            byte[] tempData = new byte[finalBytesPerLayer];
                            frameAccumulatorBuffer.flip();
                            frameAccumulatorBuffer.get(tempData, 0, finalBytesPerLayer);
                            frameCache.put(virtualPos, tempData);
                            // Keep remaining data
                            if (frameAccumulatorBuffer.remaining() > 0) {
                                frameAccumulatorBuffer.compact();
                            } else {
                                frameAccumulatorBuffer.clear();
                            }
                        }
                    } else {
                        // RAM mode: Use byte[] pool (existing behavior)
                        byte[] frameData = bufferPool.poll();
                        if (frameData == null) {
                            frameData = new byte[finalBytesPerLayer];
                            Log.w(TAG, "Buffer pool exhausted, allocating new buffer");
                        }
                        
                        byte[] accData = frameAccumulator.toByteArray();
                        System.arraycopy(accData, 0, frameData, 0, finalBytesPerLayer);
                        frameCache.put(virtualPos, frameData);
                        
                        // Keep remaining data
                        frameAccumulator.reset();
                        if (accData.length > finalBytesPerLayer) {
                            frameAccumulator.write(accData, finalBytesPerLayer, accData.length - finalBytesPerLayer);
                        }
                    }
                    
                    // Recalculate frames decoded for loop condition
                    int newDecodePos = (decoderLoopIteration[0] * totalFrames + decodePos[0]) % vPosModulus;
                    framesToDecode = (newDecodePos - virtualPlaybackPos + vPosModulus) % vPosModulus;
                } else if (!decompressEnded[0]) {
                    break; // No more data available right now
                }
            }
        };
        
        // Helper: Evict old frames from CPU cache and return buffers to pool
        // In VRAM mode, only evict frames that have already been played
        // In RAM mode, use sliding window based on CPU_CACHE_SIZE
        // Uses relative distance to handle bounded virtual position wraparound
        java.util.function.IntConsumer evictOldFrames = (virtualPlaybackPos) -> {
            // Collect buffers to return to pool before removing from cache
            java.util.List<Integer> toRemove = new java.util.ArrayList<>();
            
            if (useVramStrategy) {
                // VRAM mode: Only evict frames that have been played
                // Use relative distance to handle wraparound
                for (Integer virtualPos : frameCache.keySet()) {
                    int age = (virtualPlaybackPos - virtualPos + vPosModulus) % vPosModulus;
                    // Frame is "old" if it's behind playback (age > 0) but not wrapped around (age < half modulus)
                    if (age > 0 && age < vPosModulus / 2) {
                        toRemove.add(virtualPos);
                    }
                }
            } else {
                // RAM mode: Use sliding window with relative distance
                for (Integer virtualPos : frameCache.keySet()) {
                    int distance = (virtualPos - virtualPlaybackPos + vPosModulus) % vPosModulus;
                    // Keep frames within [-2, +CPU_CACHE_SIZE] window
                    // distance > vPosModulus - 2 means frame is 1-2 behind (wrapped)
                    // distance <= CPU_CACHE_SIZE means frame is ahead
                    boolean isBehind = distance > vPosModulus - 2;
                    boolean isAhead = distance <= CPU_CACHE_SIZE;
                    if (!isBehind && !isAhead) {
                        toRemove.add(virtualPos);
                    }
                }
            }
            
            // Return buffers to pool and remove from cache
            for (Integer virtualPos : toRemove) {
                byte[] buffer = frameCache.remove(virtualPos);
                // Only return to pool in RAM mode (VRAM mode has no pool)
                if (buffer != null && bufferPool != null && buffer.length == finalBytesPerLayer) {
                    bufferPool.offer(buffer);  // Return to pool for reuse
                }
            }
        };
        
        // Helper: Ensure frame is in GPU - takes virtual position
        // Both modes use ring buffer slots, but:
        // - VRAM mode: Larger GPU cache, upload happens during decode (or here if delayed)
        // - RAM mode: Smaller GPU ring buffer, keep frames in CPU cache
        java.util.function.IntConsumer ensureFrameInGpu = (virtualPos) -> {
            int actualFrame = virtualPos % totalFrames;
            int gpuSlot = actualFrame % finalGpuCacheSize;
            
            // Check if this exact virtual position is already loaded in this slot
            if (ringSlots[gpuSlot] == virtualPos) return; // Already loaded
            
            byte[] frameData = frameCache.get(virtualPos);
            if (frameData == null) return; // Not decoded yet (or already in GPU for VRAM mode)
            
            boolean uploaded = KTX2PlayerModule.nativeUploadTextureArrayLayer(
                textureArrayId, gpuSlot, finalWidth, finalHeight, frameData, finalUseAstc);
            if (uploaded) {
                ringSlots[gpuSlot] = virtualPos;
                
                // VRAM mode: Free CPU buffer now (was kept because slot was blocked during decode)
                // No buffer pool in VRAM mode - just remove from cache
                if (useVramStrategy) {
                    frameCache.remove(virtualPos);
                }
                // RAM mode: Keep in CPU cache (CPU is the primary cache)
            }
        };
        
        // Note: restartDecompression is now handled inside decodeFrames for wrap-around prefetching
        
        long frameIntervalNs = (long) (1_000_000_000.0 / (frameRate * playbackSpeed));
        long loopStartTime = System.nanoTime();
        int playbackLoopIteration = 0;
        
        // Main playback loop - uses virtual frame positions for seamless looping
        // vPosModulus is defined above (2 * totalFrames) to prevent overflow
        while (!shouldStop.get() && !isDestroyed.get()) {
            currentFrame = 0;
            loopStartTime = System.nanoTime();
            
            while (!shouldStop.get() && !isDestroyed.get() && currentFrame < totalFrames) {
                // Virtual position = bounded position that distinguishes current vs next loop
                int virtualPlaybackPos = (playbackLoopIteration * totalFrames + currentFrame) % vPosModulus;
                
                // Check paused state
                if (isPaused.get()) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        KTX2PlayerModule.nativeStreamingDecompressEnd(streamId[0]);
                        return;
                    }
                    continue;
                }
                
                // Decode frames ahead (prefetch with wrap-around)
                decodeFrames.accept(virtualPlaybackPos);
                
                // Evict old frames from CPU cache
                evictOldFrames.accept(virtualPlaybackPos);
                
                // Check if current frame is ready
                // For both modes: check if frame is in CPU cache or already in GPU slot
                int currentGpuSlot = currentFrame % finalGpuCacheSize;
                boolean frameInGpu = ringSlots[currentGpuSlot] == virtualPlaybackPos;
                boolean frameInCpuCache = frameCache.containsKey(virtualPlaybackPos);
                    
                if (frameInGpu || frameInCpuCache) {
                    // Ensure current frame is in GPU (for RAM mode, or delayed VRAM uploads)
                    ensureFrameInGpu.accept(virtualPlaybackPos);
                    
                    // RAM mode only: prefetch next few frames to GPU
                    // VRAM mode: frames are uploaded during decode, don't prefetch here
                    // (prefetching here can overwrite unplayed frames in the ring buffer)
                    if (!useVramStrategy) {
                        for (int i = 1; i <= 2; i++) {
                            int nextVirtualPos = virtualPlaybackPos + i;
                            int nextGpuSlot = ((currentFrame + i) % totalFrames) % finalGpuCacheSize;
                            boolean nextInGpu = ringSlots[nextGpuSlot] == nextVirtualPos;
                            if (frameCache.containsKey(nextVirtualPos) || nextInGpu) {
                                ensureFrameInGpu.accept(nextVirtualPos);
                            }
                        }
                    }
                    
                    if (isPlaying.get()) {
                        renderFrame();
                        if (enableFrameEvents) {
                            mainHandler.post(() -> notifyFrameUpdate());
                        }
                        
                        currentFrame++;
                        if (currentFrame >= totalFrames) {
                            if (loop) {
                                break; // Will continue in outer loop with incremented playbackLoopIteration
                            } else {
                                mainHandler.post(() -> {
                                    notifyFinished();
                                    notifyStateChange("stopped");
                                });
                                KTX2PlayerModule.nativeStreamingDecompressEnd(streamId[0]);
                                return;
                            }
                        }
                        
                        long targetTimeNs = loopStartTime + (currentFrame * frameIntervalNs);
                        long sleepNs = targetTimeNs - System.nanoTime();
                        
                        if (sleepNs > 1_000_000) {
                            try {
                                Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                KTX2PlayerModule.nativeStreamingDecompressEnd(streamId[0]);
                                return;
                            }
                        }
                    }
                } else {
                    // Frame not ready yet - wait for decompression
                    Log.w(TAG, "Frame not ready: frame=" + currentFrame + ", vPos=" + virtualPlaybackPos +
                          ", gpuSlot=" + currentGpuSlot + ", ringSlots[" + currentGpuSlot + "]=" + ringSlots[currentGpuSlot] +
                          ", inCache=" + frameInCpuCache + ", cacheSize=" + frameCache.size() +
                          ", playbackLoop=" + playbackLoopIteration + ", decoderLoop=" + decoderLoopIteration[0]);
                    try {
                        Thread.sleep(8);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        KTX2PlayerModule.nativeStreamingDecompressEnd(streamId[0]);
                        return;
                    }
                }
            }
            
            // End of one loop iteration
            if (!loop) break;
            playbackLoopIteration++;
            Log.i(TAG, "Loop transition: now on playbackLoop=" + playbackLoopIteration + 
                  ", decoderLoop=" + decoderLoopIteration[0] + ", decodePos=" + decodePos[0] +
                  ", cacheSize=" + frameCache.size() + ", ringSlots[0]=" + ringSlots[0]);
        }
        
        KTX2PlayerModule.nativeStreamingDecompressEnd(streamId[0]);
        Log.i(TAG, "Streaming animation ended");
    }
    
    /**
     * Check if file is uncompressed streaming KTX2 and play it if so.
     * Reads only the header (~1KB) to detect streaming mode - avoids loading entire file into RAM.
     * @return true if handled as uncompressed streaming, false otherwise
     */
    private boolean checkAndPlayUncompressedStreaming(String localPath) {
        Log.d(TAG, "checkAndPlayUncompressedStreaming: checking " + localPath);
        try {
            String filePath = localPath.startsWith("file://") ? localPath.substring(7) : localPath;
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(filePath, "r");
            
            // Read just the header (first 256 bytes is enough for KTX2 header + basic metadata check)
            byte[] headerBytes = new byte[256];
            int bytesRead = raf.read(headerBytes);
            if (bytesRead < 100) {
                Log.d(TAG, "checkAndPlayUncompressedStreaming: file too small (" + bytesRead + " bytes)");
                raf.close();
                return false; // Too small to be valid KTX2
            }
            
            ByteBuffer header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
            
            // Check KTX2 magic number (first 12 bytes)
            byte[] magic = new byte[12];
            header.get(magic);
            byte[] ktx2Magic = {(byte)0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, (byte)0xBB, 0x0D, 0x0A, 0x1A, 0x0A};
            boolean isKtx2 = java.util.Arrays.equals(magic, ktx2Magic);
            if (!isKtx2) {
                Log.d(TAG, "checkAndPlayUncompressedStreaming: not a KTX2 file (magic mismatch)");
                raf.close();
                return false; // Not a KTX2 file
            }
            
            // Check supercompressionScheme at offset 44
            int supercompressionScheme = header.getInt(44);
            Log.d(TAG, "checkAndPlayUncompressedStreaming: supercompression=" + supercompressionScheme);
            if (supercompressionScheme != 0) {
                raf.close();
                return false; // Not uncompressed - let other paths handle it
            }
            
            // Read KVD offset and length from header (bytes 56-59 and 60-63, little-endian uint32)
            int kvdByteOffset = header.getInt(56);
            int kvdByteLength = header.getInt(60);
            Log.d(TAG, "checkAndPlayUncompressedStreaming: kvdOffset=" + kvdByteOffset + ", kvdLength=" + kvdByteLength);
            
            if (kvdByteLength == 0 || kvdByteOffset == 0) {
                Log.d(TAG, "checkAndPlayUncompressedStreaming: no metadata section");
                raf.close();
                return false; // No metadata
            }
            
            // Read metadata section
            raf.seek(kvdByteOffset);
            byte[] metadataBytes = new byte[Math.min(kvdByteLength, 4096)]; // Cap at 4KB for safety
            raf.read(metadataBytes);
            raf.close();
            
            // Extract metadata JSON using the same logic as extractKTX2Metadata
            String metadataJson = extractMetadataJsonFromKvdBytes(metadataBytes);
            if (metadataJson == null) {
                Log.d(TAG, "checkAndPlayUncompressedStreaming: failed to extract metadata JSON from KVD");
                return false;
            }
            
            // Check for streamMode in metadata
            boolean hasStreamMode = metadataJson.contains("\"streamMode\":true") || metadataJson.contains("\"streamMode\": true");
            Log.d(TAG, "checkAndPlayUncompressedStreaming: hasStreamMode=" + hasStreamMode);
            if (!hasStreamMode) {
                return false; // Not streaming mode
            }
            
            // Parse metadata to set up isNativeASTC, frameRate, etc.
            try {
                parseMetadata(metadataJson);
                Log.i(TAG, "Uncompressed streaming detected: isNativeASTC=" + isNativeASTC);
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse metadata for uncompressed streaming: " + e.getMessage());
                return false;
            }
            
            // All checks passed - this is uncompressed streaming mode
            Log.i(TAG, "Detected uncompressed streaming KTX2 (supercompression=0, streamMode=true) - using mmap streaming");
            playUncompressedStreamingAnimation(localPath);
            return true;
            
        } catch (Exception e) {
            Log.w(TAG, "Error checking for uncompressed streaming: " + e.getMessage());
            e.printStackTrace();
            return false; // Fall back to normal path
        }
    }
    
    /**
     * Extract JSON metadata from KTX2 metadata bytes (simple JSON extraction).
     */
    private String extractMetadataJsonFromBytes(byte[] metadataBytes) {
        try {
            String str = new String(metadataBytes, java.nio.charset.StandardCharsets.UTF_8);
            int jsonStart = str.indexOf('{');
            int jsonEnd = str.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                return str.substring(jsonStart, jsonEnd + 1);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract metadata JSON: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Extract BasisPlayerMetadata JSON from KVD (Key-Value Data) section bytes.
     * Parses the KTX2 KVD format to find the BasisPlayerMetadata entry.
     */
    private String extractMetadataJsonFromKvdBytes(byte[] kvdBytes) {
        try {
            if (kvdBytes == null || kvdBytes.length < 4) return null;
            
            int pos = 0;
            int kvdEnd = kvdBytes.length;
            
            while (pos < kvdEnd) {
                if (pos + 4 > kvdBytes.length) break;
                
                ByteBuffer entryBuf = ByteBuffer.wrap(kvdBytes, pos, 4).order(ByteOrder.LITTLE_ENDIAN);
                int entryLength = entryBuf.getInt();
                pos += 4;
                
                if (entryLength == 0 || pos + entryLength > kvdBytes.length) break;
                
                // Find key (NUL-terminated string)
                int keyEnd = pos;
                while (keyEnd < pos + entryLength && kvdBytes[keyEnd] != 0) {
                    keyEnd++;
                }
                
                String key = new String(kvdBytes, pos, keyEnd - pos, "UTF-8");
                
                if ("BasisPlayerMetadata".equals(key)) {
                    // Value starts after key's NUL terminator
                    int valueStart = keyEnd + 1;
                    int valueEnd = pos + entryLength;
                    
                    // Find actual value end (may have NUL terminator)
                    while (valueEnd > valueStart && kvdBytes[valueEnd - 1] == 0) {
                        valueEnd--;
                    }
                    
                    String metadataJson = new String(kvdBytes, valueStart, valueEnd - valueStart, "UTF-8");
                    Log.d(TAG, "extractMetadataJsonFromKvdBytes: found BasisPlayerMetadata");
                    return metadataJson;
                }
                
                // Move to next entry (with padding to 4-byte alignment)
                pos += entryLength;
                pos += (4 - (entryLength % 4)) % 4;
            }
            
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract KVD metadata: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Play uncompressed streaming animation from .ktx2 file.
     * Uses memory-mapped file for true zero-copy frame uploads.
     * This is the fastest streaming mode - no decompression overhead.
     * Does not require pre-loading the file into memory - reads header from mmap.
     */
    private void playUncompressedStreamingAnimation(String localPath) {
        Log.i(TAG, "Starting uncompressed streaming animation: " + localPath);
        
        // Memory-map the file for zero-copy reads - do this FIRST to avoid loading file into RAM
        final ByteBuffer mmapBuffer;
        try {
            String filePath = localPath.startsWith("file://") ? localPath.substring(7) : localPath;
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(filePath, "r");
            java.nio.channels.FileChannel channel = raf.getChannel();
            mmapBuffer = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size());
            mmapBuffer.order(ByteOrder.LITTLE_ENDIAN);
            Log.i(TAG, "Memory-mapped file: " + channel.size() + " bytes");
        } catch (Exception e) {
            Log.e(TAG, "Failed to memory-map file: " + e.getMessage());
            mainHandler.post(() -> notifyError("Failed to memory-map file: " + e.getMessage()));
            return;
        }
        
        // Parse KTX2 header directly from mmap buffer (no RAM copy needed)
        int pixelWidth = mmapBuffer.getInt(20);
        int pixelHeight = mmapBuffer.getInt(24);
        int layerCount = mmapBuffer.getInt(32);
        if (layerCount == 0) layerCount = 1;
        long level0ByteOffset = mmapBuffer.getLong(80);
        
        // Calculate bytes per frame
        int blocksX = (pixelWidth + 3) / 4;
        int blocksY = (pixelHeight + 3) / 4;
        int bytesPerLayer = blocksX * blocksY * 16;
        
        Log.i(TAG, "Uncompressed streaming: " + pixelWidth + "x" + pixelHeight + ", " + 
              layerCount + " layers, " + bytesPerLayer + " bytes/layer, data offset=" + level0ByteOffset);
        
        // Determine GPU cache size based on memory strategy
        boolean useVramStrategy = "vram".equals(memoryStrategy);
        boolean useAstc = isNativeASTC && isASTCSupported();
        int gpuCacheSize = useVramStrategy ? 
            Math.min(configStreamingCacheSize, layerCount) : 
            Math.min(configStreamingRingSize, layerCount);
        
        Log.i(TAG, "Uncompressed streaming params: strategy=" + memoryStrategy + 
              ", gpuCache=" + gpuCacheSize + ", vramMode=" + useVramStrategy);
        
        // Create GPU texture array
        textureArrayId = KTX2PlayerModule.nativeCreateTextureArray(pixelWidth, pixelHeight, gpuCacheSize, useAstc);
        if (textureArrayId == 0) {
            mainHandler.post(() -> notifyError("Failed to create texture array for uncompressed streaming"));
            return;
        }
        
        Log.i(TAG, "Created uncompressed streaming texture array: ID=" + textureArrayId + 
              ", " + pixelWidth + "x" + pixelHeight + ", " + gpuCacheSize + " GPU slots");
        
        // Initialize ring buffer tracking
        streamingRingBufferMode = true;
        streamingRingSize = gpuCacheSize;
        int[] ringSlots = new int[gpuCacheSize];
        Arrays.fill(ringSlots, -1);
        
        // Initialize shaders
        if (!initShaders()) {
            mainHandler.post(() -> notifyError("Failed to initialize shaders"));
            return;
        }
        
        mSourceLoaded = true;
        mainHandler.post(() -> notifyStateChange("ready"));
        
        if (autoPlay) {
            isPlaying.set(true);
            mainHandler.post(() -> notifyStateChange("playing"));
        }
        
        // Frame timing - use metadata frameRate (already parsed)
        long frameIntervalNs = (long) (1_000_000_000.0 / (frameRate * playbackSpeed));
        Log.i(TAG, "Uncompressed streaming timing: frameRate=" + frameRate + ", playbackSpeed=" + playbackSpeed + 
              ", frameIntervalNs=" + frameIntervalNs + " (" + (frameIntervalNs / 1_000_000.0) + "ms)");
        
        // Capture finals for lambda
        final int finalWidth = pixelWidth;
        final int finalHeight = pixelHeight;
        final int finalBytesPerLayer = bytesPerLayer;
        final int finalLayerCount = layerCount;
        final int finalGpuCacheSize = gpuCacheSize;
        final long finalDataOffset = level0ByteOffset;
        final boolean finalUseAstc = useAstc;
        
        // Main playback loop - simple and fast for uncompressed data
        int debugFrameCounter = 0;
        while (!shouldStop.get() && !isDestroyed.get()) {
            long loopStartTime = System.nanoTime();
            currentFrame = 0;
            
            while (!shouldStop.get() && !isDestroyed.get() && currentFrame < finalLayerCount) {
                // Check paused state
                if (isPaused.get()) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                
                // Calculate GPU slot for this frame
                int gpuSlot = currentFrame % finalGpuCacheSize;
                
                // Check if frame needs to be uploaded
                if (ringSlots[gpuSlot] != currentFrame) {
                    // Calculate offset in mmap buffer for this frame
                    long frameOffset = finalDataOffset + (long)currentFrame * finalBytesPerLayer;
                    
                    // Upload directly from mmap buffer (true zero-copy!)
                    mmapBuffer.position((int)frameOffset);
                    mmapBuffer.limit((int)frameOffset + finalBytesPerLayer);
                    ByteBuffer frameSlice = mmapBuffer.slice();
                    frameSlice.order(ByteOrder.nativeOrder());
                    
                    boolean uploaded = KTX2PlayerModule.nativeUploadTextureArrayLayerFromBuffer(
                        textureArrayId, gpuSlot, finalWidth, finalHeight, 
                        frameSlice, finalBytesPerLayer, finalUseAstc);
                    
                    if (uploaded) {
                        ringSlots[gpuSlot] = currentFrame;
                    }
                }
                
                // Render (includes eglSwapBuffers)
                renderTextureArrayFrame();
                
                // Debug logging every 30 frames
                if (debugFrameCounter % 30 == 0) {
                    Log.d(TAG, "Uncompressed frame " + currentFrame + "/" + finalLayerCount + 
                          ", gpuSlot=" + (currentFrame % finalGpuCacheSize) + 
                          ", ringSize=" + streamingRingSize);
                }
                debugFrameCounter++;
                
                // Frame timing - wait until next frame time
                long frameEndTime = loopStartTime + (long)(currentFrame + 1) * frameIntervalNs;
                long now = System.nanoTime();
                long sleepNs = frameEndTime - now;
                if (sleepNs > 0) {
                    try {
                        Thread.sleep(sleepNs / 1_000_000, (int)(sleepNs % 1_000_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                
                if (isPlaying.get()) {
                    currentFrame++;
                }
            }
            
            // End of one loop iteration
            if (!loop) break;
            
            // Reset for next loop
            Arrays.fill(ringSlots, -1);
            Log.i(TAG, "Uncompressed streaming loop restart");
        }
        
        Log.i(TAG, "Uncompressed streaming animation ended");
    }
    
    private boolean initGL(SurfaceTexture surfaceTexture) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "Unable to get EGL display");
            return false;
        }
        
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "Unable to initialize EGL");
            return false;
        }
        
        // EGL_OPENGL_ES3_BIT_KHR = 0x40 (not available in all SDK levels)
        int[] configAttribs = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        };
        
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            Log.e(TAG, "Unable to choose EGL config");
            return false;
        }
        
        int[] contextAttribs = {
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "Unable to create EGL context");
            return false;
        }
        
        int[] surfaceAttribs = { EGL14.EGL_NONE };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surfaceTexture, surfaceAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "Unable to create EGL surface");
            return false;
        }
        
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "Unable to make EGL context current");
            return false;
        }
        
        // Enable blending for transparency
        // Use premultiplied alpha blending (RGB is pre-multiplied by alpha in encoder)
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        
        Log.d(TAG, "OpenGL ES 3.0 initialized successfully");
        return true;
    }
    
    private void detectAndCacheTextureFormat() {
        String extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        
        if (extensions != null) {
            if (extensions.contains("GL_KHR_texture_compression_astc_ldr")) {
                Log.i(TAG, "Using ASTC 4x4 format");
                cachedTextureFormat = GL_COMPRESSED_RGBA_ASTC_4x4_KHR;
                return;
            }
            if (extensions.contains("GL_OES_compressed_ETC2_RGBA8_texture") ||
                extensions.contains("ETC2")) {
                Log.i(TAG, "Using ETC2 RGBA format");
                cachedTextureFormat = GL_COMPRESSED_RGBA8_ETC2_EAC;
                return;
            }
        }
        Log.w(TAG, "Using RGBA32 fallback format");
        cachedTextureFormat = GLES30.GL_RGBA;
    }
    
    private boolean initShaders() {
        int vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER);
        if (vertexShader == 0) return false;
        
        int fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (fragmentShader == 0) {
            GLES30.glDeleteShader(vertexShader);
            return false;
        }
        
        program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vertexShader);
        GLES30.glAttachShader(program, fragmentShader);
        GLES30.glLinkProgram(program);
        
        int[] linkStatus = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "Program link error: " + GLES30.glGetProgramInfoLog(program));
            GLES30.glDeleteProgram(program);
            return false;
        }
        
        GLES30.glDeleteShader(vertexShader);
        GLES30.glDeleteShader(fragmentShader);
        
        uniformTextureLocation = GLES30.glGetUniformLocation(program, "uTexture");
        uniformFrameUVLocation = GLES30.glGetUniformLocation(program, "uFrameUV");
        
        // GPU playback uniforms
        uniformElapsedTimeLocation = GLES30.glGetUniformLocation(program, "uElapsedTime");
        uniformFpsLocation = GLES30.glGetUniformLocation(program, "uFps");
        uniformTotalFramesLocation = GLES30.glGetUniformLocation(program, "uTotalFrames");
        uniformFramesPerRowLocation = GLES30.glGetUniformLocation(program, "uFramesPerRow");
        uniformFramesPerPartLocation = GLES30.glGetUniformLocation(program, "uFramesPerPart");
        uniformFrameWidthLocation = GLES30.glGetUniformLocation(program, "uFrameWidth");
        uniformFrameHeightLocation = GLES30.glGetUniformLocation(program, "uFrameHeight");
        uniformTextureWidthLocation = GLES30.glGetUniformLocation(program, "uTextureWidth");
        uniformTextureHeightLocation = GLES30.glGetUniformLocation(program, "uTextureHeight");
        uniformGpuPlaybackLocation = GLES30.glGetUniformLocation(program, "uGpuPlayback");
        
        Log.d(TAG, "Shaders initialized successfully (gpuPlayback uniforms: " + 
              (uniformGpuPlaybackLocation >= 0 ? "found" : "not found") + ")");
        
        // Initialize texture array shader (for packingMode: 'array')
        initTextureArrayShader();
        
        return true;
    }
    
    /**
     * Initialize the texture array shader program for packingMode: 'array'.
     */
    private void initTextureArrayShader() {
        int vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, TEXTURE_ARRAY_VERTEX_SHADER);
        if (vertexShader == 0) {
            Log.e(TAG, "Failed to compile texture array vertex shader");
            return;
        }
        
        int fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, TEXTURE_ARRAY_FRAGMENT_SHADER);
        if (fragmentShader == 0) {
            GLES30.glDeleteShader(vertexShader);
            Log.e(TAG, "Failed to compile texture array fragment shader");
            return;
        }
        
        arrayProgram = GLES30.glCreateProgram();
        GLES30.glAttachShader(arrayProgram, vertexShader);
        GLES30.glAttachShader(arrayProgram, fragmentShader);
        GLES30.glLinkProgram(arrayProgram);
        
        int[] linkStatus = new int[1];
        GLES30.glGetProgramiv(arrayProgram, GLES30.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "Texture array program link error: " + GLES30.glGetProgramInfoLog(arrayProgram));
            GLES30.glDeleteProgram(arrayProgram);
            arrayProgram = 0;
            return;
        }
        
        GLES30.glDeleteShader(vertexShader);
        GLES30.glDeleteShader(fragmentShader);
        
        arrayUniformLayerLocation = GLES30.glGetUniformLocation(arrayProgram, "uLayer");
        Log.d(TAG, "Texture array shader initialized (uLayer location: " + arrayUniformLayerLocation + ")");
    }
    
    private int compileShader(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        
        int[] compileStatus = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "Shader compile error: " + GLES30.glGetShaderInfoLog(shader));
            GLES30.glDeleteShader(shader);
            return 0;
        }
        
        return shader;
    }
    
    /**
     * Check if data is a GLB container (magic: "glTF" = 0x46546C67)
     */
    private boolean isGLBData(byte[] data) {
        if (data == null || data.length < 12) return false;
        return data[0] == 0x67 && data[1] == 0x6C && data[2] == 0x54 && data[3] == 0x46;
    }
    
    /**
     * Parse GLB container and extract metadata JSON and KTX2 parts.
     * Returns metadata JSON string, populates glbKtx2Parts list.
     */
    private String parseGLBContainer(byte[] data) {
        try {
            if (!isGLBData(data)) return null;
            
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            
            // Read header
            int magic = buffer.getInt(0);
            int version = buffer.getInt(4);
            int totalLength = buffer.getInt(8);
            
            if (version != 2) {
                Log.w(TAG, "Unsupported GLB version: " + version);
                return null;
            }
            
            // Read JSON chunk (starts at byte 12)
            int offset = 12;
            int jsonChunkLength = buffer.getInt(offset);
            offset += 4;
            int jsonChunkType = buffer.getInt(offset);
            offset += 4;
            
            if (jsonChunkType != 0x4E4F534A) { // "JSON"
                Log.w(TAG, "Expected JSON chunk, got: " + Integer.toHexString(jsonChunkType));
                return null;
            }
            
            // Parse JSON metadata
            String jsonData = new String(data, offset, jsonChunkLength, "UTF-8").trim();
            offset += jsonChunkLength;
            
            // Read BIN chunk
            int binChunkLength = buffer.getInt(offset);
            offset += 4;
            int binChunkType = buffer.getInt(offset);
            offset += 4;
            
            if (binChunkType != 0x004E4942) { // "BIN\0"
                Log.w(TAG, "Expected BIN chunk, got: " + Integer.toHexString(binChunkType));
                return null;
            }
            
            int binDataStart = offset;
            
            // Parse metadata to get ktx2Parts offsets
            JSONObject metadata = new JSONObject(jsonData);
            JSONArray ktx2PartsArray = metadata.optJSONArray("ktx2Parts");
            
            if (ktx2PartsArray == null || ktx2PartsArray.length() == 0) {
                Log.w(TAG, "No ktx2Parts found in GLB metadata");
                return null;
            }
            
            // Extract KTX2 parts from BIN chunk
            glbKtx2Parts = new ArrayList<>();
            for (int i = 0; i < ktx2PartsArray.length(); i++) {
                JSONObject partInfo = ktx2PartsArray.getJSONObject(i);
                int byteOffset = partInfo.getInt("byteOffset");
                int byteLength = partInfo.getInt("byteLength");
                
                byte[] partData = new byte[byteLength];
                System.arraycopy(data, binDataStart + byteOffset, partData, 0, byteLength);
                glbKtx2Parts.add(partData);
            }
            
            Log.i(TAG, "Parsed GLB container: " + glbKtx2Parts.size() + " KTX2 parts");
            isGLBContainer = true;
            
            return jsonData;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse GLB container: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Extract BasisPlayerMetadata from KTX2 key-value data section.
     * Returns null if not found.
     */
    private String extractKTX2Metadata(byte[] ktx2Data) {
        try {
            if (ktx2Data == null || ktx2Data.length < 80) return null;
            
            // Check KTX2 magic: «KTX 20»
            if (ktx2Data[0] != (byte)0xAB || ktx2Data[1] != 0x4B || ktx2Data[2] != 0x54 || ktx2Data[3] != 0x58) {
                return null;
            }
            
            // Read KVD offset and length from header (bytes 56-63, little-endian)
            ByteBuffer header = ByteBuffer.wrap(ktx2Data).order(ByteOrder.LITTLE_ENDIAN);
            int kvdByteOffset = header.getInt(56);
            int kvdByteLength = header.getInt(60);
            
            if (kvdByteLength == 0 || kvdByteOffset + kvdByteLength > ktx2Data.length) {
                return null;
            }
            
            // Parse key-value entries
            int pos = kvdByteOffset;
            int kvdEnd = kvdByteOffset + kvdByteLength;
            
            while (pos < kvdEnd) {
                if (pos + 4 > ktx2Data.length) break;
                
                ByteBuffer entryBuf = ByteBuffer.wrap(ktx2Data, pos, 4).order(ByteOrder.LITTLE_ENDIAN);
                int entryLength = entryBuf.getInt();
                pos += 4;
                
                if (entryLength == 0 || pos + entryLength > ktx2Data.length) break;
                
                // Find key (NUL-terminated string)
                int keyEnd = pos;
                while (keyEnd < pos + entryLength && ktx2Data[keyEnd] != 0) {
                    keyEnd++;
                }
                
                String key = new String(ktx2Data, pos, keyEnd - pos, "UTF-8");
                
                if ("BasisPlayerMetadata".equals(key)) {
                    // Value starts after key's NUL terminator
                    int valueStart = keyEnd + 1;
                    int valueEnd = pos + entryLength;
                    
                    // Find actual value end (may have NUL terminator)
                    while (valueEnd > valueStart && ktx2Data[valueEnd - 1] == 0) {
                        valueEnd--;
                    }
                    
                    String metadataJson = new String(ktx2Data, valueStart, valueEnd - valueStart, "UTF-8");
                    Log.d(TAG, "Extracted embedded metadata from KTX2: " + metadataJson.substring(0, Math.min(200, metadataJson.length())) + "...");
                    return metadataJson;
                }
                
                // Move to next entry (with padding to 4-byte alignment)
                pos += entryLength;
                pos += (4 - (entryLength % 4)) % 4;
            }
            
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract KTX2 metadata: " + e.getMessage());
            return null;
        }
    }
    
    private void parseMetadata(String json) throws Exception {
        if (json == null || json.isEmpty()) {
            throw new Exception("Metadata is null or empty");
        }
        
        JSONObject metadata = new JSONObject(json);
        
        frameWidth = metadata.getInt("frameWidth");
        frameHeight = metadata.getInt("frameHeight");
        totalFrames = metadata.getInt("frameCount");
        frameRate = (float) metadata.getDouble("frameRate");
        framesPerRow = metadata.optInt("framesPerRow", 1);
        // Support both new 'part' and legacy 'strip' terminology
        framesPerPart = metadata.optInt("framesPerPart", metadata.optInt("framesPerStrip", totalFrames));
        partCount = metadata.optInt("partCount", metadata.optInt("stripCount", 1));
        
        frameIntervalNanos = (long) (1_000_000_000L / frameRate);
        
        // Parse parts array (support both 'parts' and legacy 'strips')
        partInfoList.clear();
        JSONArray partsArray = metadata.has("parts") ? metadata.getJSONArray("parts") : 
                              (metadata.has("strips") ? metadata.getJSONArray("strips") : null);
        if (partsArray != null) {
            for (int i = 0; i < partsArray.length(); i++) {
                JSONObject partObj = partsArray.getJSONObject(i);
                PartInfo info = new PartInfo();
                info.index = partObj.optInt("index", i);
                info.startFrame = partObj.optInt("startFrame", i * framesPerPart);
                info.endFrame = partObj.optInt("endFrame", info.startFrame + framesPerPart - 1);
                info.frameCount = partObj.optInt("frameCount", framesPerPart);
                info.rows = partObj.optInt("rows", (framesPerPart + framesPerRow - 1) / framesPerRow);
                info.columns = partObj.optInt("columns", framesPerRow);
                info.file = partObj.optString("file", null);
                partInfoList.add(info);
                Log.d(TAG, "Part " + i + ": " + info.file + " (" + info.rows + "x" + info.columns + ")");
            }
        }
        
        // Check if this is native ASTC mode
        String mode = metadata.optString("mode", "uastc");
        isNativeASTC = "astc".equals(mode);
        
        // Check for texture array packing mode
        String packingMode = metadata.optString("packingMode", "atlas");
        useTextureArray = "array".equals(packingMode);
        textureArrayLayerCount = metadata.optInt("layerCount", totalFrames);
        
        // Check for stream mode (internal streaming flag in metadata)
        metadataStreamMode = metadata.optBoolean("streamMode", false);
        
        Log.i(TAG, "Metadata: " + totalFrames + " frames, " + frameWidth + "x" + frameHeight +
                ", " + frameRate + " fps, " + partCount + " parts");
        Log.i(TAG, "Mode: '" + mode + "', isNativeASTC=" + isNativeASTC + ", packingMode=" + packingMode + 
                ", streamMode=" + metadataStreamMode);
    }
    
    /**
     * Get supercompression scheme from KTX2 header.
     * Returns 0 for uncompressed, 2 for Zstd, or -1 on error.
     */
    private int getSupercompressionScheme(byte[] ktx2Data) {
        if (ktx2Data == null || ktx2Data.length < 48) return -1;
        ByteBuffer header = ByteBuffer.wrap(ktx2Data).order(ByteOrder.LITTLE_ENDIAN);
        return header.getInt(44);
    }
    
    /**
     * Check if ASTC texture compression is supported by the GPU.
     */
    private boolean isASTCSupported() {
        return cachedTextureFormat == GL_COMPRESSED_RGBA_ASTC_4x4_KHR;
    }
    
    /**
     * Decompress ASTC data from KTX2 without uploading to GPU.
     * Used for background thread preloading - upload happens on render thread.
     * Returns Object[] { byte[] textureData, int width, int height } or null on failure.
     */
    private Object[] decompressASTCFromKTX2(byte[] ktx2Data) {
        try {
            if (ktx2Data == null || ktx2Data.length < 80) return null;
            
            ByteBuffer header = ByteBuffer.wrap(ktx2Data).order(ByteOrder.LITTLE_ENDIAN);
            
            int pixelWidth = header.getInt(20);
            int pixelHeight = header.getInt(24);
            int supercompressionScheme = header.getInt(44);
            
            long level0ByteOffset = header.getLong(80);
            long level0ByteLength = header.getLong(88);
            long level0UncompressedLength = header.getLong(96);
            
            byte[] textureData;
            
            if (supercompressionScheme == 2) {
                textureData = KTX2PlayerModule.nativeDecompressZstd(
                    ktx2Data, (int)level0ByteOffset, (int)level0ByteLength, (int)level0UncompressedLength);
                if (textureData == null) {
                    return null;
                }
            } else if (supercompressionScheme == 0) {
                textureData = new byte[(int)level0ByteLength];
                System.arraycopy(ktx2Data, (int)level0ByteOffset, textureData, 0, (int)level0ByteLength);
            } else {
                return null;
            }
            
            return new Object[] { textureData, pixelWidth, pixelHeight };
        } catch (Exception e) {
            Log.e(TAG, "Failed to decompress ASTC: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Upload decompressed ASTC data to GPU. Must be called on render thread.
     */
    private int[] uploadASTCToGPU(byte[] textureData, int pixelWidth, int pixelHeight) {
        try {
            if (!isASTCSupported()) {
                return KTX2PlayerModule.nativeDecodeASTCToRGBA(textureData, pixelWidth, pixelHeight);
            }
            
            int[] textureIds = new int[1];
            GLES30.glGenTextures(1, textureIds, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureIds[0]);
            
            ByteBuffer textureBuffer = ByteBuffer.allocateDirect(textureData.length)
                .order(ByteOrder.nativeOrder());
            textureBuffer.put(textureData);
            textureBuffer.position(0);
            
            GLES30.glCompressedTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GL_COMPRESSED_RGBA_ASTC_4x4_KHR,
                pixelWidth, pixelHeight, 0, textureData.length, textureBuffer);
            
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            
            Log.d(TAG, "ASTC uploaded on render thread: ID=" + textureIds[0] + ", " + pixelWidth + "x" + pixelHeight);
            return new int[] { textureIds[0], pixelWidth, pixelHeight };
        } catch (Exception e) {
            Log.e(TAG, "Failed to upload ASTC: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Upload transcoded texture data to GPU. Must be called on render thread.
     * Handles both compressed (ASTC/ETC2) and uncompressed (RGBA) formats.
     */
    private int[] uploadTranscodedToGPU(byte[] textureData, int pixelWidth, int pixelHeight, boolean isCompressed) {
        try {
            int[] textureIds = new int[1];
            GLES30.glGenTextures(1, textureIds, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureIds[0]);
            
            ByteBuffer textureBuffer = ByteBuffer.allocateDirect(textureData.length)
                .order(ByteOrder.nativeOrder());
            textureBuffer.put(textureData);
            textureBuffer.position(0);
            
            if (isCompressed) {
                // Use cached format (ASTC or ETC2)
                GLES30.glCompressedTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, cachedTextureFormat,
                    pixelWidth, pixelHeight, 0, textureData.length, textureBuffer);
            } else {
                // RGBA uncompressed
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                    pixelWidth, pixelHeight, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, textureBuffer);
            }
            
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            
            Log.d(TAG, "Transcoded texture uploaded on render thread: ID=" + textureIds[0] + 
                    ", " + pixelWidth + "x" + pixelHeight + ", compressed=" + isCompressed);
            return new int[] { textureIds[0], pixelWidth, pixelHeight };
        } catch (Exception e) {
            Log.e(TAG, "Failed to upload transcoded texture: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Load native ASTC data directly from KTX2 file without transcoding.
     * Handles Zstandard decompression if needed.
     * Falls back to software ASTC->RGBA decoding on emulator/unsupported devices.
     * Returns [textureId, width, height] or null on failure.
     */
    private int[] loadNativeASTCFromKTX2(byte[] ktx2Data) {
        try {
            if (ktx2Data == null || ktx2Data.length < 80) return null;
            
            ByteBuffer header = ByteBuffer.wrap(ktx2Data).order(ByteOrder.LITTLE_ENDIAN);
            
            // Parse KTX2 header
            int vkFormat = header.getInt(12);
            int pixelWidth = header.getInt(20);
            int pixelHeight = header.getInt(24);
            int supercompressionScheme = header.getInt(44);
            
            Log.d(TAG, "Native ASTC - VkFormat=" + vkFormat + ", " + pixelWidth + "x" + pixelHeight + 
                  ", supercompression=" + supercompressionScheme);
            
            // Read level index (after 80-byte header)
            long level0ByteOffset = header.getLong(80);
            long level0ByteLength = header.getLong(88);
            long level0UncompressedLength = header.getLong(96);
            
            Log.d(TAG, "Level 0 - offset=" + level0ByteOffset + ", length=" + level0ByteLength + 
                  ", uncompressed=" + level0UncompressedLength);
            
            byte[] textureData;
            
            if (supercompressionScheme == 2) {
                // Zstandard compression - use native decompression
                Log.d(TAG, "Decompressing Zstandard data...");
                textureData = KTX2PlayerModule.nativeDecompressZstd(
                    ktx2Data, (int)level0ByteOffset, (int)level0ByteLength, (int)level0UncompressedLength);
                if (textureData == null) {
                    Log.e(TAG, "Zstd decompression failed");
                    return null;
                }
                Log.d(TAG, "Decompressed " + textureData.length + " bytes");
            } else if (supercompressionScheme == 0) {
                // No supercompression
                textureData = new byte[(int)level0ByteLength];
                System.arraycopy(ktx2Data, (int)level0ByteOffset, textureData, 0, (int)level0ByteLength);
            } else {
                Log.e(TAG, "Unsupported supercompression scheme: " + supercompressionScheme);
                return null;
            }
            
            // Check if ASTC is supported
            if (!isASTCSupported()) {
                // Fallback: decode ASTC to RGBA in software (for emulator)
                Log.w(TAG, "ASTC not supported - using software decode fallback (emulator mode)");
                Log.w(TAG, "Note: ASTC textures require a real device for optimal performance");
                return KTX2PlayerModule.nativeDecodeASTCToRGBA(textureData, pixelWidth, pixelHeight);
            }
            
            // Upload to GPU as compressed texture
            int[] textureIds = new int[1];
            GLES30.glGenTextures(1, textureIds, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureIds[0]);
            
            ByteBuffer textureBuffer = ByteBuffer.allocateDirect(textureData.length)
                .order(ByteOrder.nativeOrder());
            textureBuffer.put(textureData);
            textureBuffer.position(0);
            
            GLES30.glCompressedTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GL_COMPRESSED_RGBA_ASTC_4x4_KHR,
                pixelWidth,
                pixelHeight,
                0,
                textureData.length,
                textureBuffer
            );
            
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            
            Log.d(TAG, "Native ASTC texture uploaded: ID=" + textureIds[0] + ", " + pixelWidth + "x" + pixelHeight);
            
            return new int[] { textureIds[0], pixelWidth, pixelHeight };
        } catch (Exception e) {
            Log.e(TAG, "Failed to load native ASTC: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Load texture array to RAM without GPU upload (for RAM-heavy mode).
     * Returns Object[] = {width, height, layerCount, bytesPerLayer, ByteBuffer frameData}
     * Uses a single contiguous direct ByteBuffer for zero-copy GPU uploads.
     */
    private Object[] loadTextureArrayToRam(byte[] ktx2Data, boolean useAstc) {
        try {
            // Parse KTX2 header to get dimensions and layer count
            if (ktx2Data.length < 104) {
                Log.e(TAG, "KTX2 data too small for header");
                return null;
            }
            
            ByteBuffer header = ByteBuffer.wrap(ktx2Data).order(ByteOrder.LITTLE_ENDIAN);
            
            // Check magic
            if (ktx2Data[0] != (byte)0xAB || ktx2Data[1] != 0x4B) {
                Log.e(TAG, "Invalid KTX2 magic");
                return null;
            }
            
            int pixelWidth = header.getInt(20);
            int pixelHeight = header.getInt(24);
            int layerCount = header.getInt(32);
            if (layerCount == 0) layerCount = 1;
            int supercompressionScheme = header.getInt(44);  // Offset 44, not 48
            
            // Get level 0 data offset and length
            long level0ByteOffset = header.getLong(80);
            long level0ByteLength = header.getLong(88);
            
            // Calculate ASTC frame size
            int blocksX = (pixelWidth + 3) / 4;
            int blocksY = (pixelHeight + 3) / 4;
            int bytesPerLayer = blocksX * blocksY * 16;
            int totalSize = bytesPerLayer * layerCount;
            
            Log.d(TAG, "KTX2 header: " + pixelWidth + "x" + pixelHeight + ", " + layerCount + 
                  " layers, supercompression=" + supercompressionScheme);
            
            // Allocate direct ByteBuffer for zero-copy GPU uploads
            ByteBuffer frameBuffer = ByteBuffer.allocateDirect(totalSize);
            frameBuffer.order(ByteOrder.nativeOrder());
            
            if (supercompressionScheme == 2) { // Zstd
                // Decompress directly into the direct ByteBuffer
                boolean success = KTX2PlayerModule.nativeDecompressZstdToBuffer(
                    ktx2Data, (int)level0ByteOffset, (int)level0ByteLength, frameBuffer, totalSize);
                if (!success) {
                    Log.e(TAG, "Zstd decompression to buffer failed");
                    return null;
                }
            } else if (supercompressionScheme == 0) { // None
                // Copy uncompressed data directly to buffer
                frameBuffer.put(ktx2Data, (int)level0ByteOffset, (int)level0ByteLength);
                frameBuffer.rewind();
            } else {
                Log.e(TAG, "Unsupported supercompression: " + supercompressionScheme);
                return null;
            }
            
            Log.d(TAG, "Decoded " + layerCount + " frames to direct ByteBuffer, " + 
                  bytesPerLayer + " bytes each, total " + (totalSize / 1024) + " KB");
            
            return new Object[] { pixelWidth, pixelHeight, layerCount, bytesPerLayer, frameBuffer };
        } catch (Exception e) {
            Log.e(TAG, "Failed to load texture array to RAM: " + e.getMessage());
            return null;
        }
    }
    
    private void loadPartTextures(String ktx2Path) throws Exception {
        String basePath = ktx2Path.replaceAll("_\\d+\\.ktx2$", "").replaceAll("\\.ktx2$", "");
        
        List<Integer> loadedTextureIds = new ArrayList<>();
        List<Integer> loadedTextureWidths = new ArrayList<>();
        List<Integer> loadedTextureHeights = new ArrayList<>();
        
        // Check if we're loading from GLB container
        if (isGLBContainer && glbKtx2Parts != null && glbKtx2Parts.size() > 0) {
            Log.d(TAG, "Loading " + glbKtx2Parts.size() + " KTX2 parts from GLB container...");
            
            for (int i = 0; i < glbKtx2Parts.size(); i++) {
                byte[] ktx2Data = glbKtx2Parts.get(i);
                
                int[] result;
                if (isNativeASTC) {
                    Log.d(TAG, "Using native ASTC loader for GLB part " + i);
                    result = loadNativeASTCFromKTX2(ktx2Data);
                } else {
                    result = KTX2PlayerModule.nativeTranscodeKTX2(ktx2Data, cachedTextureFormat);
                }
                
                if (result != null && result.length >= 3) {
                    loadedTextureIds.add(result[0]);
                    loadedTextureWidths.add(result[1]);
                    loadedTextureHeights.add(result[2]);
                    Log.d(TAG, "Loaded GLB part " + i + " texture ID " + result[0] + " (" + result[1] + "x" + result[2] + ")");
                } else {
                    Log.e(TAG, "Failed to load GLB part " + i);
                }
            }
        } else {
            // Check if we have explicit part URIs (from React Native parts prop)
            boolean useExplicitParts = explicitPartUris != null && explicitPartUris.length > 1;
            int actualPartCount = useExplicitParts ? explicitPartUris.length : partCount;
            
            if (actualPartCount > 1 && (useExplicitParts || !partInfoList.isEmpty())) {
                // Load all parts from separate files
                Log.d(TAG, "Loading " + actualPartCount + " part files" + (useExplicitParts ? " (explicit URIs)" : "") + "...");
                
                for (int i = 0; i < actualPartCount; i++) {
                    int[] result = loadSinglePart(i, ktx2Path, basePath, useExplicitParts);
                    if (result != null) {
                        loadedTextureIds.add(result[0]);
                        loadedTextureWidths.add(result[1]);
                        loadedTextureHeights.add(result[2]);
                    }
                }
            } else if (useTextureArray) {
                // Load texture array (packingMode: 'array')
                Log.d(TAG, "Loading texture array from: " + ktx2Path);
                
                byte[] ktx2Data = readFileBytes(ktx2Path);
                if (ktx2Data == null) {
                    throw new Exception("Failed to read KTX2 file: " + ktx2Path);
                }
                
                boolean useAstc = isNativeASTC && isASTCSupported();
                
                // Check memory strategy for non-stream texture array mode
                // "ram" mode: Decode to RAM, upload per-frame (lower VRAM usage)
                // "auto" or "vram" mode: Upload all to GPU (current behavior)
                boolean useRamMode = "ram".equals(memoryStrategy);
                
                if (useRamMode) {
                    Log.d(TAG, "RAM-heavy mode: Decoding texture array to RAM, will upload per-frame");
                    
                    // Decode all frames to RAM using nativeLoadTextureArrayToRam
                    // This returns frame data in a contiguous direct ByteBuffer (zero-copy)
                    Object[] ramResult = loadTextureArrayToRam(ktx2Data, useAstc);
                    if (ramResult == null) {
                        throw new Exception("Failed to decode texture array to RAM");
                    }
                    
                    int texWidth = (Integer) ramResult[0];
                    int texHeight = (Integer) ramResult[1];
                    int layerCount = (Integer) ramResult[2];
                    ramBytesPerFrame = (Integer) ramResult[3];
                    ramFrameBuffer = (ByteBuffer) ramResult[4];
                    ramFrameCount = layerCount;
                    
                    // Create small ring buffer on GPU (2 frames for double buffering)
                    int gpuRingSize = 2;
                    textureArrayId = KTX2PlayerModule.nativeCreateTextureArray(texWidth, texHeight, gpuRingSize, useAstc);
                    if (textureArrayId == 0) {
                        throw new Exception("Failed to create GPU ring buffer for RAM mode");
                    }
                    textureArrayLayerCount = gpuRingSize;
                    ramHeavyMode = true;
                    ramUploadedFrame = -1;
                    
                    Log.d(TAG, "RAM-heavy texture array: " + layerCount + " frames in direct ByteBuffer (" + 
                          (ramFrameCount * ramBytesPerFrame / 1024) + " KB), " +
                          gpuRingSize + " slots in GPU ring buffer");
                    
                    loadedTextureIds.add(textureArrayId);
                    loadedTextureWidths.add(texWidth);
                    loadedTextureHeights.add(texHeight);
                } else {
                    Log.d(TAG, "VRAM mode: Loading texture array with ASTC=" + useAstc);
                    
                    int[] result = KTX2PlayerModule.nativeLoadTextureArray(ktx2Data, useAstc);
                    if (result == null || result.length < 4) {
                        throw new Exception("Failed to load texture array");
                    }
                    
                    textureArrayId = result[0];
                    int texWidth = result[1];
                    int texHeight = result[2];
                    textureArrayLayerCount = result[3];
                    ramHeavyMode = false;
                    
                    Log.d(TAG, "Loaded texture array ID " + textureArrayId + 
                          " (" + texWidth + "x" + texHeight + ", " + textureArrayLayerCount + " layers)");
                    
                    // For texture arrays, we don't use the regular texture list
                    // Just set up minimal state for the render loop
                    loadedTextureIds.add(textureArrayId);
                    loadedTextureWidths.add(texWidth);
                    loadedTextureHeights.add(texHeight);
                }
            } else {
                Log.d(TAG, "Loading single KTX2 file: " + ktx2Path + (isNativeASTC ? " (native ASTC)" : ""));
                
                byte[] ktx2Data = readFileBytes(ktx2Path);
                if (ktx2Data == null) {
                    throw new Exception("Failed to read KTX2 file: " + ktx2Path);
                }
                
                int[] result;
                if (isNativeASTC) {
                    Log.d(TAG, "Using native ASTC loader");
                    result = loadNativeASTCFromKTX2(ktx2Data);
                } else {
                    result = KTX2PlayerModule.nativeTranscodeKTX2(ktx2Data, cachedTextureFormat);
                }
                
                if (result == null || result.length < 3) {
                    throw new Exception("Failed to " + (isNativeASTC ? "load native ASTC" : "transcode") + " KTX2 file");
                }
                
                loadedTextureIds.add(result[0]);
                loadedTextureWidths.add(result[1]);
                loadedTextureHeights.add(result[2]);
                
                Log.d(TAG, (isNativeASTC ? "Loaded native ASTC" : "Transcoded") + " texture ID " + 
                      result[0] + " (" + result[1] + "x" + result[2] + ")");
            }
        }
        
        textureIds = new int[loadedTextureIds.size()];
        textureWidths = new int[loadedTextureWidths.size()];
        textureHeights = new int[loadedTextureHeights.size()];
        for (int i = 0; i < loadedTextureIds.size(); i++) {
            textureIds[i] = loadedTextureIds.get(i);
            textureWidths[i] = loadedTextureWidths.get(i);
            textureHeights[i] = loadedTextureHeights.get(i);
        }
        
        Log.d(TAG, "Loaded " + textureIds.length + " textures");
    }
    
    /**
     * Get the path/URL for a specific part file.
     */
    private String getPartPath(int partIndex, String ktx2Path, String basePath, boolean useExplicitParts) {
        if (useExplicitParts && partIndex < explicitPartUris.length && explicitPartUris[partIndex] != null) {
            // Use explicit URI from parts prop
            return explicitPartUris[partIndex];
        } else if (partIndex < partInfoList.size() && partInfoList.get(partIndex).file != null) {
            // Use filename from metadata
            File ktx2File = new File(ktx2Path);
            File parentDir = ktx2File.getParentFile();
            if (parentDir != null) {
                return new File(parentDir, partInfoList.get(partIndex).file).getAbsolutePath();
            } else {
                return partInfoList.get(partIndex).file;
            }
        } else {
            // Fallback: construct path from base name
            return basePath + "_" + partIndex + ".ktx2";
        }
    }
    
    /**
     * Load a single part texture. Used by both normal and stream mode.
     */
    private int[] loadSinglePart(int partIndex, String ktx2Path, String basePath, boolean useExplicitParts) throws Exception {
        String partPath = getPartPath(partIndex, ktx2Path, basePath, useExplicitParts);
        
        Log.d(TAG, "Loading part " + partIndex + ": " + partPath);
        
        byte[] ktx2Data = readFileBytes(partPath);
        if (ktx2Data == null) {
            throw new Exception("Failed to read part file: " + partPath);
        }
        
        int[] result;
        if (isNativeASTC) {
            Log.d(TAG, "Using native ASTC loader for part " + partIndex);
            result = loadNativeASTCFromKTX2(ktx2Data);
        } else {
            result = KTX2PlayerModule.nativeTranscodeKTX2(ktx2Data, cachedTextureFormat);
        }
        
        if (result == null || result.length < 3) {
            throw new Exception("Failed to " + (isNativeASTC ? "load native ASTC" : "transcode") + " part " + partIndex);
        }
        
        Log.d(TAG, (isNativeASTC ? "Loaded native ASTC" : "Transcoded") + " part " + partIndex + 
              " texture ID " + result[0] + " (" + result[1] + "x" + result[2] + ")");
        
        return result;
    }
    
    // GPU playback elapsed time (in seconds)
    private float gpuElapsedTime = 0.0f;
    
    private void renderFrame() {
        if (eglDisplay == null || eglSurface == null) {
            return;
        }
        
        // Check for texture array mode vs regular texture mode
        if (useTextureArray && textureArrayId != 0) {
            renderTextureArrayFrame();
            return;
        }
        
        if (textureIds == null || textureIds.length == 0) {
            return;
        }
        
        GLES30.glClearColor(0, 0, 0, 0);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glViewport(0, 0, getWidth(), getHeight());
        GLES30.glUseProgram(program);
        
        // Set GPU playback mode
        GLES30.glUniform1i(uniformGpuPlaybackLocation, gpuPlayback ? 1 : 0);
        
        if (gpuPlayback) {
            // GPU-controlled playback: pass elapsed time and animation parameters to shader
            GLES30.glUniform1f(uniformElapsedTimeLocation, gpuElapsedTime);
            GLES30.glUniform1f(uniformFpsLocation, frameRate * playbackSpeed);
            GLES30.glUniform1f(uniformTotalFramesLocation, (float) totalFrames);
            GLES30.glUniform1f(uniformFramesPerRowLocation, (float) framesPerRow);
            GLES30.glUniform1f(uniformFramesPerPartLocation, (float) framesPerPart);
            GLES30.glUniform1f(uniformFrameWidthLocation, (float) frameWidth);
            GLES30.glUniform1f(uniformFrameHeightLocation, (float) frameHeight);
            
            // Get texture dimensions for current part
            int partIndex = getCurrentPartIndexForGpu();
            int texW = partIndex < textureWidths.length ? textureWidths[partIndex] : framesPerRow * frameWidth;
            int texH = partIndex < textureHeights.length ? textureHeights[partIndex] : ((framesPerPart + framesPerRow - 1) / framesPerRow) * frameHeight;
            GLES30.glUniform1f(uniformTextureWidthLocation, (float) texW);
            GLES30.glUniform1f(uniformTextureHeightLocation, (float) texH);
        } else {
            // CPU-controlled playback: calculate frame UV on CPU
            float[] uv = calculateFrameUV();
            GLES30.glUniform4f(uniformFrameUVLocation, uv[0], uv[1], uv[2], uv[3]);
        }
        
        // Bind texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        int partIndex = gpuPlayback ? getCurrentPartIndexForGpu() : getCurrentPartIndex();
        
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureIds[partIndex]);
        GLES30.glUniform1i(uniformTextureLocation, 0);
        
        // Set vertex attributes
        int positionHandle = GLES30.glGetAttribLocation(program, "aPosition");
        GLES30.glEnableVertexAttribArray(positionHandle);
        GLES30.glVertexAttribPointer(positionHandle, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer);
        
        int texCoordHandle = GLES30.glGetAttribLocation(program, "aTexCoord");
        GLES30.glEnableVertexAttribArray(texCoordHandle);
        GLES30.glVertexAttribPointer(texCoordHandle, 2, GLES30.GL_FLOAT, false, 0, texCoordBuffer);
        
        // Draw
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        
        GLES30.glDisableVertexAttribArray(positionHandle);
        GLES30.glDisableVertexAttribArray(texCoordHandle);
        
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }
    
    /**
     * Render a frame using texture array (for packingMode: 'array').
     * Uses GL_TEXTURE_2D_ARRAY and samples the layer corresponding to currentFrame.
     */
    private void renderTextureArrayFrame() {
        if (arrayProgram == 0) {
            Log.e(TAG, "Texture array shader not initialized");
            return;
        }
        
        // RAM-heavy mode: Upload current frame to GPU ring buffer on-demand (zero-copy from direct ByteBuffer)
        if (ramHeavyMode && ramFrameBuffer != null && currentFrame < ramFrameCount) {
            if (ramUploadedFrame != currentFrame) {
                // Upload to slot 0 (single-buffered for simplicity)
                // Use zero-copy upload with offset into contiguous ByteBuffer
                int slot = 0;
                int offset = currentFrame * ramBytesPerFrame;
                boolean useAstc = (cachedTextureFormat == GL_COMPRESSED_RGBA_ASTC_4x4_KHR);
                boolean uploaded = KTX2PlayerModule.nativeUploadTextureArrayLayerFromBufferWithOffset(
                    textureArrayId, slot, frameWidth, frameHeight, ramFrameBuffer, offset, ramBytesPerFrame, useAstc);
                if (uploaded) {
                    ramUploadedFrame = currentFrame;
                }
            }
        }
        
        GLES30.glClearColor(0, 0, 0, 0);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glViewport(0, 0, getWidth(), getHeight());
        GLES30.glUseProgram(arrayProgram);
        
        // Set layer uniform:
        // - RAM-heavy mode: Always slot 0 (we upload current frame there)
        // - Streaming ring buffer mode: currentFrame % ringSize
        // - VRAM mode (non-stream or VRAM-heavy stream): currentFrame directly
        int layerIndex;
        if (ramHeavyMode) {
            layerIndex = 0;  // Always render from slot 0 where we just uploaded
        } else if (streamingRingBufferMode) {
            layerIndex = currentFrame % streamingRingSize;
        } else {
            layerIndex = currentFrame;
        }
        GLES30.glUniform1f(arrayUniformLayerLocation, (float) layerIndex);
        
        // Bind texture array
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureArrayId);
        int textureLocation = GLES30.glGetUniformLocation(arrayProgram, "uTextureArray");
        GLES30.glUniform1i(textureLocation, 0);
        
        // Set vertex attributes
        int positionHandle = GLES30.glGetAttribLocation(arrayProgram, "aPosition");
        GLES30.glEnableVertexAttribArray(positionHandle);
        GLES30.glVertexAttribPointer(positionHandle, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer);
        
        int texCoordHandle = GLES30.glGetAttribLocation(arrayProgram, "aTexCoord");
        GLES30.glEnableVertexAttribArray(texCoordHandle);
        GLES30.glVertexAttribPointer(texCoordHandle, 2, GLES30.GL_FLOAT, false, 0, texCoordBuffer);
        
        // Draw
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        
        GLES30.glDisableVertexAttribArray(positionHandle);
        GLES30.glDisableVertexAttribArray(texCoordHandle);
        
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }
    
    private int getCurrentPartIndexForGpu() {
        // Calculate current frame from elapsed time for GPU playback
        int frameIndex = (int) (gpuElapsedTime * frameRate * playbackSpeed) % totalFrames;
        if (framesPerPart <= 0) return 0;
        int partIndex = frameIndex / framesPerPart;
        return Math.min(partIndex, textureIds.length - 1);
    }
    
    private float[] calculateFrameUV() {
        if (totalFrames == 0 || textureIds.length == 0) {
            return new float[]{0, 0, 1, 1};
        }

        int frameToRender = currentFrame;

        int partIndex = frameToRender / framesPerPart;
        int frameInPart = frameToRender % framesPerPart;

        int col = frameInPart % framesPerRow;
        int row = frameInPart / framesPerRow;

        int partWidth, partHeight;
        if (partIndex < partInfoList.size()) {
            PartInfo info = partInfoList.get(partIndex);
            partWidth = info.columns * frameWidth;
            partHeight = info.rows * frameHeight;
        } else if (partIndex < textureWidths.length) {
            partWidth = textureWidths[partIndex];
            partHeight = textureHeights[partIndex];
        } else {
            partWidth = framesPerRow * frameWidth;
            partHeight = ((framesPerPart + framesPerRow - 1) / framesPerRow) * frameHeight;
        }

        float minU = (col * frameWidth) / (float) partWidth;
        float maxU = ((col + 1) * frameWidth) / (float) partWidth;
        float minV = (row * frameHeight) / (float) partHeight;
        float maxV = ((row + 1) * frameHeight) / (float) partHeight;

        return new float[]{minU, minV, maxU, maxV};
    }
    
    private int getCurrentPartIndex() {
        if (framesPerPart <= 0) return 0;
        int partIndex = currentFrame / framesPerPart;
        return Math.min(partIndex, textureIds.length - 1);
    }
    
    private void cleanupGL() {
        if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            
            // Delete textures
            if (textureIds != null) {
                for (int texId : textureIds) {
                    if (texId != 0) {
                        KTX2PlayerModule.nativeDeleteTexture(texId);
                    }
                }
                textureIds = new int[0];
            }
            
            if (program != 0) {
                GLES30.glDeleteProgram(program);
                program = 0;
            }
            
            // Reset streaming ring buffer mode
            streamingRingBufferMode = false;
            streamingRingSize = 0;
            
            // Clean up RAM-heavy mode resources
            ramFrameBuffer = null;
            ramBytesPerFrame = 0;
            ramFrameCount = 0;
            ramHeavyMode = false;
            ramUploadedFrame = -1;
            
            if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = null;
            }
            
            if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = null;
            }
            
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = null;
        }
    }
    
    private byte[] readFileBytes(String path) throws IOException {
        InputStream is = null;
        
        // Handle HTTP URLs (e.g., from Metro bundler in debug mode)
        // Download and CACHE to avoid re-downloading during playback
        if (path.startsWith("http://") || path.startsWith("https://")) {
            String cachedPath = downloadPartToCache(path);
            if (cachedPath == null) {
                throw new IOException("Failed to download and cache: " + path);
            }
            // Now read from the cached file
            File cachedFile = new File(cachedPath);
            is = new FileInputStream(cachedFile);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    bos.write(buffer, 0, len);
                }
                return bos.toByteArray();
            } finally {
                is.close();
            }
        }
        
        if (path.startsWith("assets://") || path.startsWith("asset://")) {
            String assetPath = path.replaceFirst("assets?://", "");
            is = getContext().getAssets().open(assetPath);
        } else {
            File file = new File(path);
            if (file.exists()) {
                is = new FileInputStream(file);
            } else {
                try {
                    is = getContext().getAssets().open(path);
                } catch (IOException e) {
                    return null;
                }
            }
        }
        
        if (is == null) return null;
        
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        } finally {
            is.close();
        }
    }
    
    /**
     * Download bytes from an HTTP URL (used for Metro bundler in debug mode)
     */
    private byte[] downloadBytes(String urlString) throws IOException {
        java.net.URL url = new java.net.URL(urlString);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "KTX2Player/1.0");
        
        int responseCode = conn.getResponseCode();
        if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
            Log.e(TAG, "Download failed: HTTP " + responseCode + " for " + urlString);
            throw new IOException("HTTP " + responseCode);
        }
        
        Log.d(TAG, "Downloaded " + conn.getContentLength() + " bytes from " + urlString.substring(0, Math.min(80, urlString.length())));
        
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        }
    }
    
    /**
     * Download a part file to cache. Uses URL hash as cache key.
     * Returns the cached file path, or null on failure.
     */
    private String downloadPartToCache(String urlString) {
        try {
            // Generate hash from URL for cache key
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(urlString.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            String hash = sb.toString().substring(0, 16);
            
            File cacheDir = new File(getContext().getCacheDir(), "ktx2-parts-cache");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            
            File cacheFile = new File(cacheDir, hash + ".ktx2");
            
            // Return cached file if it exists
            if (cacheFile.exists()) {
                Log.d(TAG, "Using cached part: " + cacheFile.getAbsolutePath());
                return cacheFile.getAbsolutePath();
            }
            
            // Download to cache
            Log.d(TAG, "Downloading part to cache: " + urlString.substring(0, Math.min(80, urlString.length())));
            
            java.net.URL url = new java.net.URL(urlString);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "KTX2Player/1.0");
            
            int responseCode = conn.getResponseCode();
            if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Part download failed: HTTP " + responseCode);
                return null;
            }
            
            // Download to temp file first, then rename
            File tempFile = new File(cacheDir, hash + ".tmp");
            
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                int totalBytes = 0;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }
                Log.d(TAG, "Downloaded " + totalBytes + " bytes to cache");
            }
            
            // Rename temp to final
            if (tempFile.renameTo(cacheFile)) {
                return cacheFile.getAbsolutePath();
            } else {
                // Fallback: copy if rename fails
                tempFile.delete();
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to download part to cache: " + e.getMessage());
            return null;
        }
    }
    
    private String downloadToCache(String urlString) {
        try {
            File cacheFile;
            String tempSuffix;
            
            if (mCacheKey != null && !mCacheKey.isEmpty()) {
                cacheFile = new File(getContext().getCacheDir(), mCacheKey);
                File parentDir = cacheFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                tempSuffix = String.valueOf(mCacheKey.hashCode());
                Log.d(TAG, "Using custom cacheKey: " + mCacheKey);
            } else {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] hashBytes = md.digest(urlString.getBytes());
                StringBuilder sb = new StringBuilder();
                for (byte b : hashBytes) {
                    sb.append(String.format("%02x", b));
                }
                String hash = sb.toString().substring(0, 16);
                
                File cacheDir = new File(getContext().getCacheDir(), "ktx2-cache");
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs();
                }
                
                // Preserve .zst extension for streaming files
                String extension = ".ktx2";
                if (urlString.contains(".zst")) {
                    extension = ".ktx2.zst";
                }
                cacheFile = new File(cacheDir, hash + extension);
                tempSuffix = hash;
            }
            
            if (cacheFile.exists()) {
                Log.d(TAG, "Using cached: " + cacheFile.getAbsolutePath());
                return cacheFile.getAbsolutePath();
            }
            
            java.net.URL url = new java.net.URL(urlString);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "KTX2Player/1.0");
            
            int responseCode = conn.getResponseCode();
            if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Download failed: HTTP " + responseCode);
                return null;
            }
            
            // Preserve extension for temp file (matches cacheFile extension)
            String tempExtension = urlString.contains(".zst") ? ".ktx2.zst" : ".ktx2";
            File tempFile = new File(getContext().getCacheDir(), "ktx2_tmp_" + tempSuffix + tempExtension);
            
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                int totalBytes = 0;
                while ((bytesRead = in.read(buffer)) != -1) {
                    if (shouldStop.get()) {
                        tempFile.delete();
                        return null;
                    }
                    out.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }
                Log.d(TAG, "Downloaded " + totalBytes + " bytes");
            }
            
            if (tempFile.renameTo(cacheFile)) {
                return cacheFile.getAbsolutePath();
            } else {
                return tempFile.getAbsolutePath();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            return null;
        }
    }
    
    // TextureView.SurfaceTextureListener implementation
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "onSurfaceTextureAvailable: " + width + "x" + height);
        isDestroyed.set(false);
        shouldStop.set(false);
        // Always start playback if we have a source - handles screen navigation
        if (mSource != null) {
            startPlayback();
        }
    }
    
    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "onSurfaceTextureSizeChanged: " + width + "x" + height);
    }
    
    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.d(TAG, "onSurfaceTextureDestroyed");
        isDestroyed.set(true);
        shouldStop.set(true);
        isPlaying.set(false);
        isPlaybackRunning.set(false);  // Reset for clean restart
        mSourceLoaded = false;  // Reset so playback restarts when surface is available again
        shutdownExecutor();
        return true;
    }
    
    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // No-op
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Log.d(TAG, "onDetachedFromWindow");
        destroy();
    }
    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Log.d(TAG, "onAttachedToWindow");
        isDestroyed.set(false);
    }
}
