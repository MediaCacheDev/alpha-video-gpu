package dev.mediacache.alphavideo.gpu;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.net.Uri;
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


import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays transparent animations stored as ASTC KTX2 texture arrays, one frame per layer, drawn
 * with OpenGL ES 3.0. The layers are zstd-supercompressed or uncompressed. Without ASTC support
 * (emulators) frames are decoded to RGBA in software.
 *
 * A TextureView rather than a GLSurfaceView, so it stacks and blends with the views around it.
 * Call its setters and playback methods on the main thread; {@link PlayerCallback} methods are
 * called there too.
 */
public class AlphaVideoGpuView extends TextureView implements TextureView.SurfaceTextureListener {
    private static final String TAG = "AlphaVideoGpuView";

    /** Player events. Every method has an empty default, so implement only what you need. */
    public interface PlayerCallback {
        /** The animation is loaded: frame size in pixels, frame count and frames per second. */
        default void onLoad(int width, int height, int frameCount, double frameRate) {}
        /** The shown frame changed, after seekToFrame, and every frame when frame events are on. */
        default void onFrameUpdate(int frame, int totalFrames) {}
        /** "loading", "ready", "playing", "paused" or "stopped". */
        default void onStateChange(String state) {}
        /** Playback reached the last frame with looping off. */
        default void onFinished() {}
        default void onError(String error) {}
        /** An error with its code, such as {@link #ERROR_NO_SOURCE_MODULE}, or null. Calls {@link #onError(String)} unless overridden. */
        default void onError(String error, @Nullable String code) {
            onError(error);
        }
    }

    /**
     * {@link PlayerCallback#onError(String, String)}'s code for a data source
     * ({@link #setDataSource}) without the range-source add-on, which reads them.
     */
    public static final String ERROR_NO_SOURCE_MODULE = "NO_SOURCE_MODULE";
    // A URL played without the add-on is warned of once per process
    private static final AtomicBoolean warnedWithoutAddOn = new AtomicBoolean(false);
    
    // GL constants - ASTC formats for all 14 block sizes
    private static final int GL_COMPRESSED_RGBA_ASTC_4x4_KHR   = 0x93B0;
    private static final int GL_COMPRESSED_RGBA_ASTC_5x4_KHR   = 0x93B1;
    private static final int GL_COMPRESSED_RGBA_ASTC_5x5_KHR   = 0x93B2;
    private static final int GL_COMPRESSED_RGBA_ASTC_6x5_KHR   = 0x93B3;
    private static final int GL_COMPRESSED_RGBA_ASTC_6x6_KHR   = 0x93B4;
    private static final int GL_COMPRESSED_RGBA_ASTC_8x5_KHR   = 0x93B5;
    private static final int GL_COMPRESSED_RGBA_ASTC_8x6_KHR   = 0x93B6;
    private static final int GL_COMPRESSED_RGBA_ASTC_8x8_KHR   = 0x93B7;
    private static final int GL_COMPRESSED_RGBA_ASTC_10x5_KHR  = 0x93B8;
    private static final int GL_COMPRESSED_RGBA_ASTC_10x6_KHR  = 0x93B9;
    private static final int GL_COMPRESSED_RGBA_ASTC_10x8_KHR  = 0x93BA;
    private static final int GL_COMPRESSED_RGBA_ASTC_10x10_KHR = 0x93BB;
    private static final int GL_COMPRESSED_RGBA_ASTC_12x10_KHR = 0x93BC;
    private static final int GL_COMPRESSED_RGBA_ASTC_12x12_KHR = 0x93BD;

    // How long the zstd stream decoder catches up to a seek before the render loop runs again
    private static final long SKIP_SLICE_NANOS = 16_000_000L;
    
    private PlayerCallback playerCallback;
    
    // Where what a reader fetches is kept: cacheDir/alpha-video-gpu on disk, or the memory store's name
    private static final String STORE_NAME = "alpha-video-gpu";
    public static final long DEFAULT_MAX_STORAGE_BYTES = 512L * 1024 * 1024;
    public static final String STORAGE_AUTO = "auto";
    public static final String STORAGE_DISK = "disk";
    public static final String STORAGE_MEMORY = "memory";
    public static final String STORAGE_NONE = "none";

    // Source
    private String mSource;
    private String mCacheKey;
    private Map<String, String> mHeaders = Collections.emptyMap();
    private String mDataSource;
    private String mStorage = STORAGE_AUTO;
    private long mMaxStorageBytes = DEFAULT_MAX_STORAGE_BYTES;
    // The cache key the currently running playback was started for. Compared against
    // mCacheKey to tell a re-signed URL for the same object apart from a new asset.
    private String mPlayingCacheKey;
    // What the running playback reads with, taken when it starts
    private Map<String, String> mPlayingHeaders = Collections.emptyMap();
    private String mPlayingDataSource;
    private volatile boolean mSourceLoaded = false;
    
    // Playback settings
    private boolean autoPlay = true;
    private boolean loop = true;
    private float playbackSpeed = 1.0f;
    private boolean enableFrameEvents = false;  // Disabled by default for performance
    private final AtomicBoolean isPaused = new AtomicBoolean(false);  // setPaused, kept apart from pause()
    
    // Animation state
    private int currentFrame = 0;
    private volatile int totalFrames = 0;
    // The frame the render loop shows next, set from the main thread; -1 when there is none
    private final AtomicInteger seekRequest = new AtomicInteger(-1);
    // A clip that doesn't loop has reached its last frame; playing again starts it over
    private volatile boolean ended = false;
    private double frameRate = Ktx2Metadata.DEFAULT_FRAME_RATE;
    
    private int frameWidth = 0;
    private int frameHeight = 0;
    
    private volatile boolean verbose = false;
    
    
    // OpenGL resources
    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;
    
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    
    // The GPU samples ASTC; without it (emulators) frames are decoded to RGBA in software
    private boolean astcSupported = false;
    
    // ASTC block dimensions and GL format (derived from vkFormat, default 4x4)
    private int astcBlockWidth = 4;
    private int astcBlockHeight = 4;
    private int astcGlFormat = GL_COMPRESSED_RGBA_ASTC_4x4_KHR;
    
    private int textureArrayId = 0;
    private int textureArrayLayerCount = 0;
    private boolean streamingRingBufferMode = false; // True when using ring buffer for streaming
    private int streamingRingSize = 0; // Size of ring buffer (for layer index calculation)
    private boolean pbosInitialized = false; // True when PBOs are initialized for async uploads
    
    // RAM-heavy mode storage (for memoryStrategy: 'ram' in non-stream mode)
    // Uses contiguous direct ByteBuffer for zero-copy GPU uploads
    private ByteBuffer ramFrameBuffer = null;  // All decoded frames in single contiguous buffer
    private int ramBytesPerFrame = 0;          // Size of each frame in bytes
    private int ramFrameCount = 0;             // Total number of frames
    private boolean ramHeavyMode = false;      // True when using RAM-heavy non-stream mode
    private int ramUploadedFrame = -1;         // Currently uploaded frame in GPU ring buffer
    
    // Frames decoded ahead of the shown one while streaming (set via props). Streaming keeps
    // only these, so it also sizes the GPU ring (vram) or the CPU cache (ram).
    private int configStreamingPrefetchAhead = 8;
    // The ram strategy's GPU ring: the previous frame, the shown one and the next two
    private static final int RAM_RING_SIZE = 4;
    
    // Memory strategy: "vram", "ram"
    // - vram (default): frames in GPU VRAM (best for many simultaneous players)
    // - ram: Decoded frames in RAM, upload per-frame (lower VRAM usage)
    private String memoryStrategy = "vram";
    
    // Stream prop - when true, use streaming decompression for texture arrays
    private boolean streamProp = true;
    
    // Threading
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    private final AtomicBoolean isPlaybackRunning = new AtomicBoolean(false);  // Guards against concurrent startPlayback calls
    // Set when startPlayback() is called while a previous worker is still unwinding.
    // stopPlayback() only raises a flag, so the worker exits asynchronously; without
    // recording the owed restart the view would stay stopped until the surface was
    // destroyed and recreated (i.e. until the user navigated away and back).
    private final AtomicBoolean pendingRestart = new AtomicBoolean(false);
    
    // Shaders
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
    
    public AlphaVideoGpuView(Context context) {
        super(context);
        init(context);
    }
    
    public AlphaVideoGpuView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    public AlphaVideoGpuView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }
    
    private void init(Context context) {
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
    
    private void notifyStateChange(String state) {
        if (playerCallback != null) playerCallback.onStateChange(state);
    }
    
    private void notifyLoad() {
        if (playerCallback != null) playerCallback.onLoad(frameWidth, frameHeight, totalFrames, frameRate);
    }
    
    private void notifyFrameUpdate() {
        if (playerCallback != null) playerCallback.onFrameUpdate(currentFrame, totalFrames);
    }
    
    private void notifyFinished() {
        if (playerCallback != null) playerCallback.onFinished();
    }
    
    private void notifyError(String error) {
        notifyError(error, null);
    }

    private void notifyError(String error, @Nullable String code) {
        Log.e(TAG, "Error: " + error);
        if (playerCallback != null) playerCallback.onError(error, code);
    }
    
    // Public API

    /** Receives this view's events on the main thread; null to stop. */
    public void setPlayerCallback(@Nullable PlayerCallback callback) {
        playerCallback = callback;
    }

    /**
     * What to play, or null to stop: https:// or http:// (read by range as it plays, and kept as
     * {@link #setStorage} says), file:// or an absolute path, asset:// (APK assets), content://,
     * a res/raw resource name, or with {@link #setDataSource} any URI that data source reads.
     * Set the other source properties first. Playback starts once the view's surface exists.
     */
    public void setSource(String source) {
        // Callers (React re-renders, for one) set the same source again, and for an unchanged source the
        // stop-then-start below is pure churn: it discards the decoded ring buffer
        // and briefly leaves the view with no running worker. Only act when the value
        // actually changed, or when nothing is currently playing (so a stalled or
        // finished player can still be revived by setting the same source again).
        boolean playing = isPlaybackRunning.get() && !shouldStop.get();
        boolean sameReads = mHeaders.equals(mPlayingHeaders) && Objects.equals(mDataSource, mPlayingDataSource);
        if (source != null && source.equals(mSource) && playing && sameReads) {
            logD("setSource: unchanged and still playing, ignoring");
            return;
        }

        // A presigned URL is re-signed every time the app lists its media, so the URL
        // changes while still addressing the same object. The cache key is the stable
        // identity, so when it matches what is already on screen this is a credential
        // refresh rather than a new asset, and restarting would visibly interrupt a
        // loop that is supposed to run until unmount.
        if (source != null && playing && sameReads
                && mCacheKey != null && !mCacheKey.isEmpty()
                && mCacheKey.equals(mPlayingCacheKey)) {
            logD("setSource: same cacheKey (" + mCacheKey + ") still playing, ignoring URL change");
            mSource = source;
            return;
        }

        logD("setSource: " + (source != null ? source.substring(0, Math.min(80, source.length())) : "null"));
        
        stopPlayback();
        mSource = source;
        mSourceLoaded = false;
        
        if (source != null && isAvailable()) {
            startPlayback();
        }
    }
    
    /**
     * Names what the store keeps a URL's bytes under, instead of the URL, for URLs that change
     * while the file doesn't (presigned URLs). A source with the same cache key as the one playing
     * keeps playing. Set it before the source.
     */
    public void setCacheKey(String cacheKey) {
        mCacheKey = cacheKey;
        logD("setCacheKey: " + cacheKey);
    }

    /** Sent with every request for an http(s) source, and given to a data source; null for none. */
    public void setHeaders(@Nullable Map<String, String> headers) {
        mHeaders = headers == null || headers.isEmpty()
            ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    /**
     * Reads the source through the data source registered under this name with the range-source
     * add-on ({@code dev.mediacache.rangesource.RangeDataSources.register}), whatever its scheme;
     * null for the built-in ones.
     */
    public void setDataSource(@Nullable String name) {
        mDataSource = name == null || name.isEmpty() ? null : name;
    }

    /**
     * Where a URL's or data source's bytes are kept between plays: {@code "disk"} (in the cache
     * folder), {@code "memory"} (for the process's lifetime) or {@code "none"}. {@code "auto"}, the
     * default, is disk for URLs and none for a data source, whose caching is its own. The store is
     * partial: it keeps what has been played, and a clip held whole plays again offline.
     */
    public void setStorage(@Nullable String storage) {
        switch (storage == null ? STORAGE_AUTO : storage) {
            case STORAGE_DISK:
            case STORAGE_MEMORY:
            case STORAGE_NONE:
                mStorage = storage;
                break;
            default:
                mStorage = STORAGE_AUTO;
        }
    }

    /**
     * The cap of this player's store, over every clip in it, least recently played out first;
     * 0 or less for the default, 512 MB. Every view shares the store, under the cap set last.
     */
    public void setMaxStorageBytes(long maxStorageBytes) {
        mMaxStorageBytes = maxStorageBytes > 0 ? maxStorageBytes : DEFAULT_MAX_STORAGE_BYTES;
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
    
    public void setStreamingPrefetchAhead(int frames) {
        this.configStreamingPrefetchAhead = Math.max(1, Math.min(100, frames));
        logD("setStreamingPrefetchAhead: " + this.configStreamingPrefetchAhead);
    }
    
    /**
     * Set memory strategy for frame storage.
     * - "vram" (default): frames in GPU VRAM (best for many simultaneous players)
     * - "ram": Decoded frames in RAM, upload per-frame (lower VRAM usage)
     * Any other value means "vram".
     */
    public void setMemoryStrategy(String strategy) {
        this.memoryStrategy = "ram".equals(strategy) ? "ram" : "vram";
        logD("setMemoryStrategy: " + this.memoryStrategy);
    }
    
    /**
     * Set streaming mode for texture array playback.
     * When true (default): Use streaming decompression with ring buffer (constant memory usage).
     * When false: Load all frames at once.
     */
    public void setStream(boolean stream) {
        this.streamProp = stream;
        logD("setStream: " + stream);
    }
    
    /**
     * Pauses or resumes playback, keeping the loaded frames (for screen focus and blur).
     * Resuming plays again only when autoPlay is on.
     */
    public void setPaused(boolean paused) {
        boolean wasPaused = isPaused.getAndSet(paused);
        if (paused != wasPaused) {
            logD("setPaused: " + paused);
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
    
    /** Logs debug and info traces when true; warnings and errors are always logged. */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }
    
    /** Plays from the shown frame, or from the start once a clip that doesn't loop has ended. */
    public void play() {
        if (mSourceLoaded && !isPlaying.getAndSet(true)) {
            notifyStateChange("playing");
        }
    }
    
    public void pause() {
        if (isPlaying.getAndSet(false)) {
            notifyStateChange("paused");
        }
    }
    
    /** Pauses on the first frame. */
    public void stop() {
        isPlaying.set(false);
        seekToFrame(0);
        notifyStateChange("stopped");
    }
    
    /**
     * Shows that frame (clamped to the clip) and keeps playing or stays paused. Ignored until
     * onLoad. In stream mode the frames before it are decoded again, unless they are buffered.
     */
    public void seekToFrame(int frame) {
        int frames = totalFrames;
        if (!mSourceLoaded || frames <= 0) return;
        int target = Math.max(0, Math.min(frame, frames - 1));
        seekRequest.set(target);
        if (playerCallback != null) playerCallback.onFrameUpdate(target, frames);
    }

    /** Seeks to the frame showing at that time, in seconds. */
    public void seekToTime(double seconds) {
        double frame = Math.floor(Math.max(seconds, 0) * frameRate + 1e-6);
        seekToFrame((int) Math.min(frame, Integer.MAX_VALUE));
    }
    
    /** Stops playback and its threads. Detaching the view from its window does the same. */
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
        
        // Prevent concurrent playback attempts. A previous worker may still be
        // unwinding after stopPlayback() raised shouldStop; in that case the restart
        // is owed rather than redundant, so record it and let the exiting worker
        // relaunch. Returning without doing this leaves shouldStop raised with no
        // worker running, which strands the view until the surface is recreated.
        if (!isPlaybackRunning.compareAndSet(false, true)) {
            logD("startPlayback: worker still running, queueing restart");
            pendingRestart.set(true);
            return;
        }
        
        shouldStop.set(false);
        mPlayingCacheKey = mCacheKey;
        mPlayingHeaders = mHeaders;
        mPlayingDataSource = mDataSource;
        
        try {
            getOrCreateExecutor().execute(() -> {
                try {
                    playAnimation();
                } catch (Exception e) {
                    Log.e(TAG, "Playback error", e);
                    mainHandler.post(() -> notifyError(e.getMessage()));
                } finally {
                    cleanupGL();
                    mPlayingCacheKey = null;
                    isPlaybackRunning.set(false);
                    // Clear the guard first, then service any restart requested while
                    // this worker was shutting down. Hopping to the main thread keeps
                    // the view/surface reads in startPlayback() on the UI thread.
                    if (pendingRestart.getAndSet(false) && !isDestroyed.get()) {
                        mainHandler.post(this::startPlayback);
                    }
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
        final String uri = mSource;
        final AlphaVideoGpuSource local;
        try {
            local = resolveSource(uri, mPlayingCacheKey);
        } catch (NoSourceModuleException e) {
            mainHandler.post(() -> notifyError(e.getMessage(), ERROR_NO_SOURCE_MODULE));
            return;
        } catch (IOException | IllegalArgumentException e) {
            if (shouldStop.get()) return;  // stopped while opening
            mainHandler.post(() -> notifyError("Loading " + uri + " failed: " + e.getMessage()));
            return;
        }
        try {
            if (!shouldStop.get()) playSource(local);
        } finally {
            AlphaVideoGpuReader reader = local.reader();
            if (reader != null) reader.close();
        }
    }

    private void playSource(AlphaVideoGpuSource local) {
        // Initialize OpenGL
        SurfaceTexture surfaceTexture = getSurfaceTexture();
        if (surfaceTexture == null || !initGL(surfaceTexture)) {
            mainHandler.post(() -> notifyError("Failed to initialize OpenGL"));
            return;
        }
        
        astcSupported = detectASTCSupport();
        
        // The header, level index and metadata only; streaming reads the level as it plays
        byte[] header;
        try {
            header = readHeader(local);
        } catch (IOException e) {
            if (shouldStop.get()) return;
            mainHandler.post(() -> notifyError("Failed to read " + local + ": " + e.getMessage()));
            return;
        }
        
        String metadataError = applyMetadata(Ktx2Metadata.read(header));
        if (metadataError != null) {
            mainHandler.post(() -> notifyError(metadataError));
            return;
        }
        
        if (streamProp) {
            // Check supercompressionScheme from KTX2 header
            int supercompressionScheme = getSupercompressionScheme(header);
            logI("Streaming mode enabled (stream=true), supercompression=" + supercompressionScheme);
            
            if (supercompressionScheme == 2) {
                playInternalZstdStreamingAnimation(local, header);
                return;
            } else if (supercompressionScheme == 0) {
                // Uncompressed - use mmap streaming
                playUncompressedStreamingAnimation(local);
                return;
            } else {
                Log.w(TAG, "Unsupported supercompression for streaming: " + supercompressionScheme + ", falling back to non-stream");
            }
        }
        
        // Load textures (non-streaming path)
        try {
            loadTextureArray(local);
        } catch (Exception e) {
            if (shouldStop.get()) return;
            mainHandler.post(() -> notifyError("Failed to load textures: " + e.getMessage()));
            return;
        }
        
        // Initialize shaders
        if (!initShaders()) {
            mainHandler.post(() -> notifyError("Failed to initialize shaders"));
            return;
        }
        
        announceLoaded();
        
        logD("Starting render loop");
        
        FrameClock clock = new FrameClock(frameIntervalNanos());
        currentFrame = 0;
        boolean frameOwed = true;
        boolean wasPlaying = false;
        try {
            while (!shouldStop.get() && !isDestroyed.get()) {
                int seek = takeSeek();
                if (seek >= 0) {
                    currentFrame = seek;
                    frameOwed = true;
                    wasPlaying = false;
                }
                
                if (!isPlaying.get()) {
                    if (frameOwed) {
                        renderFrame();
                        frameOwed = false;
                    }
                    wasPlaying = false;
                    Thread.sleep(16);
                    continue;
                }
                if (!wasPlaying) {
                    clock.restart();
                    wasPlaying = true;
                }
                
                renderFrame();
                frameOwed = false;
                if (enableFrameEvents) {
                    mainHandler.post(() -> notifyFrameUpdate());
                }
                clock.awaitNext();
                
                if (currentFrame + 1 < totalFrames) {
                    currentFrame++;
                } else if (loop) {
                    currentFrame = 0;
                } else {
                    finish();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reports the load, and starts playing when autoPlay is on and the view isn't paused. */
    private void announceLoaded() {
        ended = false;
        seekRequest.set(-1);
        mSourceLoaded = true;
        mainHandler.post(() -> {
            notifyLoad();
            notifyStateChange("ready");
        });
        if (autoPlay && !isPaused.get()) {
            isPlaying.set(true);
            mainHandler.post(() -> notifyStateChange("playing"));
        }
    }

    private long frameIntervalNanos() {
        return (long) (1_000_000_000.0 / (frameRate * playbackSpeed));
    }

    /**
     * The frame a render loop should show next, or -1 to carry on: the requested seek, or
     * frame 0 when a clip that ended is played again.
     */
    private int takeSeek() {
        int seek = seekRequest.getAndSet(-1);
        if (seek < 0 && ended && isPlaying.get()) seek = 0;
        if (seek >= 0) ended = false;
        return seek;
    }

    /** Holds the last frame of a clip that doesn't loop. */
    private void finish() {
        ended = true;
        isPlaying.set(false);
        mainHandler.post(() -> {
            notifyFinished();
            notifyStateChange("stopped");
        });
    }

    /** Paces frames from the moment playback starts, resumes or seeks. */
    private static final class FrameClock {
        private final long intervalNs;
        private long dueNs;

        FrameClock(long intervalNs) {
            this.intervalNs = intervalNs;
        }

        /** The current frame is due now. */
        void restart() {
            dueNs = System.nanoTime();
        }

        /** Sleeps until the next frame is due. */
        void awaitNext() throws InterruptedException {
            dueNs += intervalNs;
            long sleepNs = dueNs - System.nanoTime();
            if (sleepNs > 1_000_000) {
                Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000));
            } else if (sleepNs < -intervalNs) {
                // Too far behind to catch up (a slow decode), so pace from now
                dueNs = System.nanoTime();
            }
        }
    }
    
    /**
     * Drops the next {@code bytes} of decompressed data: first what the accumulator holds, then
     * the rest through {@code bytesToSkip} as the stream is read.
     */
    private static void skipDecodedBytes(ByteBuffer accumulator, long[] bytesToSkip, long bytes) {
        int held = accumulator.position();
        if (held > bytes) {
            accumulator.flip();
            accumulator.position((int) bytes);
            accumulator.compact();
        } else {
            accumulator.clear();
            bytesToSkip[0] += bytes - held;
        }
    }

    /** Forgets every decoded frame after a seek, returning the CPU buffers to the pool. */
    private static void dropStreamedFrames(java.util.Map<Integer, byte[]> frameCache,
                                           @Nullable java.util.Queue<byte[]> bufferPool,
                                           int[] ringSlots, int bytesPerLayer) {
        if (bufferPool != null) {
            for (byte[] buffer : frameCache.values()) {
                if (buffer.length == bytesPerLayer) bufferPool.offer(buffer);
            }
        }
        frameCache.clear();
        Arrays.fill(ringSlots, -1);
    }
    
    /**
     * The file's first bytes, through the end of its key/value data: the header, the level index
     * and the metadata. 16 KB, or more when the key/value data runs past it.
     */
    private byte[] readHeader(AlphaVideoGpuSource local) throws IOException {
        final int guess = 16 * 1024;
        byte[] head = local.readHead(getContext(), guess);
        if (head.length < 80) throw new IOException("Too short for a KTX2 file (" + head.length + " bytes)");
        ByteBuffer header = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        long kvdEnd = (header.getInt(56) & 0xFFFFFFFFL) + (header.getInt(60) & 0xFFFFFFFFL);
        if (kvdEnd <= head.length || head.length < guess) return head;
        if (kvdEnd > 16L * 1024 * 1024) throw new IOException("Key/value data of " + kvdEnd + " bytes");
        return local.readHead(getContext(), (int) kvdEnd);
    }

    /**
     * The seek table at the end of the level, read from the level's last bytes; null when the
     * level is one stream.
     */
    @Nullable
    private ZstdSeekTable readSeekTable(AlphaVideoGpuSource local, long levelOffset, long levelLength,
                                        long decompressedLength) throws IOException {
        if (levelLength <= 0) return null;
        long levelEnd = levelOffset + levelLength;
        int count = (int) Math.min(levelLength, ZstdSeekTable.TAIL_GUESS);
        byte[] tail = local.readRange(getContext(), levelEnd - count, count);
        long needed = ZstdSeekTable.tailBytesNeeded(tail);
        if (needed > tail.length && needed <= levelLength && needed <= Integer.MAX_VALUE) {
            tail = local.readRange(getContext(), levelEnd - needed, (int) needed);
        }
        return ZstdSeekTable.parseTail(tail, levelLength, decompressedLength);
    }
    
    /**
     * Play uncompressed streaming animation from .ktx2 file.
     * Uses memory-mapped file for true zero-copy frame uploads.
     * This is the fastest streaming mode - no decompression overhead.
     * Does not require pre-loading the file into memory - reads header from mmap.
     */
    private void playUncompressedStreamingAnimation(AlphaVideoGpuSource local) {
        logI("Starting uncompressed streaming animation: " + local);
        
        // Memory-map the file for zero-copy reads - do this FIRST to avoid loading file into RAM.
        // A URL or data source is read whole into memory instead: uncompressed frames are as
        // large as decoded ones, so such files are for local playback, and zstd ones stream.
        final ByteBuffer mmapBuffer;
        try {
            mmapBuffer = local.reader() != null ? local.readAllDirect(getContext()) : local.map(getContext());
        } catch (Exception e) {
            if (shouldStop.get()) return;
            Log.e(TAG, "Failed to read " + local + ": " + e.getMessage());
            mainHandler.post(() -> notifyError("Failed to stream " + local + ": " + e.getMessage()));
            return;
        }
        mmapBuffer.order(ByteOrder.LITTLE_ENDIAN);
        logI("Streaming from " + mmapBuffer.capacity() + " bytes " + (local.reader() != null ? "in memory" : "mapped"));
        
        // Parse KTX2 header directly from mmap buffer (no RAM copy needed)
        int vkFormat = mmapBuffer.getInt(12);
        int pixelWidth = mmapBuffer.getInt(20);
        int pixelHeight = mmapBuffer.getInt(24);
        int layerCount = mmapBuffer.getInt(32);
        if (layerCount == 0) layerCount = 1;
        long level0ByteOffset = mmapBuffer.getLong(80);
        
        // Configure ASTC format from vkFormat
        configureASTCFromVkFormat(vkFormat);
        
        // Calculate bytes per frame using dynamic ASTC block size
        int bytesPerLayer = astcBytesPerLayer(pixelWidth, pixelHeight);
        
        logI("Uncompressed streaming: " + pixelWidth + "x" + pixelHeight + ", " + 
              layerCount + " layers, " + bytesPerLayer + " bytes/layer (ASTC " + astcBlockWidth + "x" + astcBlockHeight + "), data offset=" + level0ByteOffset);
        
        // Frames are read from the mapping as they're shown, nothing ahead, so a small ring does
        // for either memory strategy
        boolean useVramStrategy = !"ram".equals(memoryStrategy);
        boolean useAstc = astcSupported;
        int gpuCacheSize = Math.min(RAM_RING_SIZE, layerCount);
        
        logI("Uncompressed streaming params: strategy=" + memoryStrategy + 
              ", gpuCache=" + gpuCacheSize + ", vramMode=" + useVramStrategy);
        
        // Create GPU texture array
        textureArrayId = AlphaVideoGpuNative.nativeCreateTextureArray(pixelWidth, pixelHeight, gpuCacheSize, useAstc, astcGlFormat);
        if (textureArrayId == 0) {
            mainHandler.post(() -> notifyError("Failed to create texture array for uncompressed streaming"));
            return;
        }
        
        logI("Created uncompressed streaming texture array: ID=" + textureArrayId + 
              ", " + pixelWidth + "x" + pixelHeight + ", " + gpuCacheSize + " GPU slots");
        
        // Initialize ring buffer tracking
        streamingRingBufferMode = true;
        streamingRingSize = gpuCacheSize;
        int[] ringSlots = new int[gpuCacheSize];
        Arrays.fill(ringSlots, -1);
        
        // Initialize PBOs for async uploads (only for ASTC compressed format)
        if (useAstc) {
            pbosInitialized = AlphaVideoGpuNative.nativeInitPBOs(textureArrayId, bytesPerLayer);
            if (pbosInitialized) {
                logI("PBOs initialized for uncompressed streaming: " + bytesPerLayer + " bytes per buffer");
            }
        }
        
        // Initialize shaders
        if (!initShaders()) {
            mainHandler.post(() -> notifyError("Failed to initialize shaders"));
            return;
        }
        
        totalFrames = layerCount;
        announceLoaded();
        
        // Frame timing - use metadata frameRate (already parsed)
        long frameIntervalNs = frameIntervalNanos();
        logI("Uncompressed streaming timing: frameRate=" + frameRate + ", playbackSpeed=" + playbackSpeed + 
              ", frameIntervalNs=" + frameIntervalNs + " (" + (frameIntervalNs / 1_000_000.0) + "ms)");
        
        // Capture finals for lambda
        final int finalWidth = pixelWidth;
        final int finalHeight = pixelHeight;
        final int finalBytesPerLayer = bytesPerLayer;
        final int finalLayerCount = layerCount;
        final int finalGpuCacheSize = gpuCacheSize;
        final long finalDataOffset = level0ByteOffset;
        final boolean finalUseAstc = useAstc;
        
        // Main playback loop - simple and fast for uncompressed data. Any frame can be read
        // straight from the mapping, so a seek only moves the playhead.
        int debugFrameCounter = 0;
        FrameClock clock = new FrameClock(frameIntervalNs);
        currentFrame = 0;
        boolean frameOwed = true;
        boolean wasPlaying = false;
        try {
            while (!shouldStop.get() && !isDestroyed.get()) {
                int seek = takeSeek();
                if (seek >= 0) {
                    currentFrame = seek;
                    frameOwed = true;
                    wasPlaying = false;
                }
                
                boolean playing = isPlaying.get();
                if (!playing && !frameOwed) {
                    wasPlaying = false;
                    Thread.sleep(16);
                    continue;
                }
                
                // Calculate GPU slot for this frame
                int gpuSlot = currentFrame % finalGpuCacheSize;
                
                // Check if frame needs to be uploaded
                if (ringSlots[gpuSlot] != currentFrame) {
                    // Calculate offset in mmap buffer for this frame
                    long frameOffset = finalDataOffset + (long)currentFrame * finalBytesPerLayer;
                    
                    // Upload directly from mmap buffer (true zero-copy!). The limit goes back
                    // to the end first, since a frame after the last one read lies past it.
                    mmapBuffer.clear();
                    mmapBuffer.position((int)frameOffset);
                    mmapBuffer.limit((int)frameOffset + finalBytesPerLayer);
                    ByteBuffer frameSlice = mmapBuffer.slice();
                    frameSlice.order(ByteOrder.nativeOrder());
                    
                    // Use PBO for async upload when available
                    boolean uploaded = pbosInitialized ?
                        AlphaVideoGpuNative.nativeUploadTextureArrayLayerPBO(
                            textureArrayId, gpuSlot, finalWidth, finalHeight, 
                            frameSlice, finalBytesPerLayer, finalUseAstc,
                            astcGlFormat, astcBlockWidth, astcBlockHeight) :
                        AlphaVideoGpuNative.nativeUploadTextureArrayLayerFromBuffer(
                            textureArrayId, gpuSlot, finalWidth, finalHeight, 
                            frameSlice, finalBytesPerLayer, finalUseAstc,
                            astcGlFormat, astcBlockWidth, astcBlockHeight);
                    
                    if (uploaded) {
                        ringSlots[gpuSlot] = currentFrame;
                    }
                }
                
                // Render (includes eglSwapBuffers)
                renderTextureArrayFrame();
                frameOwed = false;
                if (!playing) {
                    wasPlaying = false;
                    continue;
                }
                if (!wasPlaying) {
                    clock.restart();
                    wasPlaying = true;
                }
                if (enableFrameEvents) {
                    mainHandler.post(() -> notifyFrameUpdate());
                }
                
                // Debug logging every 30 frames
                if (debugFrameCounter % 30 == 0) {
                    logD("Uncompressed frame " + currentFrame + "/" + finalLayerCount + 
                          ", gpuSlot=" + (currentFrame % finalGpuCacheSize) + 
                          ", ringSize=" + streamingRingSize);
                }
                debugFrameCounter++;
                
                // Frame timing - wait until next frame time
                clock.awaitNext();
                
                if (currentFrame + 1 < finalLayerCount) {
                    currentFrame++;
                } else if (loop) {
                    currentFrame = 0;
                    logI("Uncompressed streaming loop restart");
                } else {
                    finish();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        logI("Uncompressed streaming animation ended");
    }
    
    /**
     * Play streaming animation from KTX2 file with internal zstd compression (supercompressionScheme=2).
     * Only the level0 data is compressed, so the header is read directly and the layers are
     * decompressed as they play.
     * 
     * A URL or data source is read as it decodes, without waiting: frames whose bytes haven't
     * arrived are simply not ready yet, and the shown frame stays.
     *
     * @param local The .ktx2 file
     * @param headerData The file's header, through its key/value data (level0 offset and size)
     */
    private void playInternalZstdStreamingAnimation(AlphaVideoGpuSource local, byte[] headerData) {
        logI("Starting internal zstd streaming animation: " + local);
        
        // Parse KTX2 header to get level0 info
        ByteBuffer header = ByteBuffer.wrap(headerData).order(ByteOrder.LITTLE_ENDIAN);
        int vkFormat = header.getInt(12);
        int pixelWidth = header.getInt(20);
        int pixelHeight = header.getInt(24);
        int layerCount = header.getInt(32);
        if (layerCount == 0) layerCount = 1;
        long level0Offset = header.getLong(80);
        long level0Length = header.getLong(88);  // Compressed size
        long level0UncompressedLength = header.getLong(96);
        
        // Configure ASTC format from vkFormat
        configureASTCFromVkFormat(vkFormat);
        
        logI("Internal zstd: " + pixelWidth + "x" + pixelHeight + ", " + layerCount + " layers, " +
              "compressed=" + level0Length + ", uncompressed=" + level0UncompressedLength + ", vkFormat=" + vkFormat);
        
        // Calculate bytes per layer using dynamic ASTC block size
        int bytesPerLayer = astcBytesPerLayer(pixelWidth, pixelHeight);
        
        // Verify uncompressed size matches expected
        long expectedUncompressed = (long)bytesPerLayer * layerCount;
        if (level0UncompressedLength != expectedUncompressed) {
            Log.w(TAG, "Uncompressed size mismatch: header=" + level0UncompressedLength + ", expected=" + expectedUncompressed);
        }
        final ZstdSeekTable seekTable;
        try {
            seekTable = readSeekTable(local, level0Offset, level0Length, level0UncompressedLength);
        } catch (IOException e) {
            if (shouldStop.get()) return;
            mainHandler.post(() -> notifyError("Failed to read " + local + ": " + e.getMessage()));
            return;
        }
        logI(seekTable != null ? "Seek table: " + seekTable.size() + " seek points" : "No seek table: seeks decode from the start");
        
        // Initialize internal zstd streaming decompression over a memory-mapped range
        final long[] streamId = {0};
        try {
            streamId[0] = openInternalZstdStream(local, level0Offset, level0Length);
        } catch (IOException e) {
            mainHandler.post(() -> notifyError("Failed to stream " + local + ": " + e.getMessage()));
            return;
        }
        if (streamId[0] == 0) {
            mainHandler.post(() -> notifyError("Failed to initialize internal zstd streaming"));
            return;
        }
        
        totalFrames = layerCount;
        frameWidth = pixelWidth;
        frameHeight = pixelHeight;
        
        boolean useAstc = astcSupported;
        
        // Determine memory strategy
        final boolean useVramStrategy = !"ram".equals(memoryStrategy);
        final int PREFETCH_AHEAD = configStreamingPrefetchAhead;
        // The shown frame, the ones decoded after it, and the previous frame, which the GPU may
        // still be drawing
        final int framesKept = PREFETCH_AHEAD + 2;
        final int GPU_CACHE_SIZE = useVramStrategy ? framesKept : RAM_RING_SIZE;
        final int CPU_CACHE_SIZE = useVramStrategy ? 5 : framesKept;
        
        logI("Internal zstd streaming params: strategy=" + memoryStrategy + 
              ", gpuCache=" + GPU_CACHE_SIZE + ", cpuCache=" + CPU_CACHE_SIZE + 
              ", prefetch=" + PREFETCH_AHEAD);
        
        // Create texture array
        int textureLayerCount = Math.min(GPU_CACHE_SIZE, layerCount);
        textureArrayId = AlphaVideoGpuNative.nativeCreateTextureArray(pixelWidth, pixelHeight, textureLayerCount, useAstc, astcGlFormat);
        if (textureArrayId == 0) {
            mainHandler.post(() -> notifyError("Failed to create texture array for internal zstd streaming"));
            AlphaVideoGpuNative.nativeStreamingDecompressEnd(streamId[0]);
            return;
        }
        textureArrayLayerCount = textureLayerCount;
        
        logI("Created internal zstd streaming texture array: ID=" + textureArrayId + 
              ", " + pixelWidth + "x" + pixelHeight + ", " + textureLayerCount + " GPU slots");
        
        streamingRingBufferMode = true;
        streamingRingSize = textureLayerCount;
        
        // Initialize PBOs for async uploads (only for ASTC compressed format)
        if (useAstc) {
            pbosInitialized = AlphaVideoGpuNative.nativeInitPBOs(textureArrayId, bytesPerLayer);
            if (pbosInitialized) {
                logI("PBOs initialized for internal zstd streaming: " + bytesPerLayer + " bytes per buffer");
            }
        }
        
        // Initialize shaders
        if (!initShaders()) {
            mainHandler.post(() -> notifyError("Failed to initialize shaders"));
            AlphaVideoGpuNative.nativeStreamingDecompressEnd(streamId[0]);
            return;
        }
        
        // Track ring buffer slots
        int[] ringSlots = new int[textureLayerCount];
        Arrays.fill(ringSlots, -1);
        
        // Decompression state - NO header to skip since we start at level0 data directly
        final int[] decodePos = {0};
        final boolean[] decompressEnded = {false};
        // The last decode stopped for bytes still on their way (a URL or data source)
        final boolean[] inputPending = {false};
        // Why the stream failed (corrupt data, or a read that failed after retries), else null
        final String[] streamError = {null};
        // The frames before a seek target
        final long[] bytesToSkip = {0};
        
        // Buffer for decompression
        final int bufferSize = 64 * 1024;
        final ByteBuffer decompressBuffer = ByteBuffer.allocateDirect(bufferSize);
        
        // Frame accumulator and cache
        final ByteBuffer frameAccumulatorBuffer;
        final java.util.Queue<byte[]> bufferPool;
        final java.util.Map<Integer, byte[]> frameCache;
        
        if (useVramStrategy) {
            frameAccumulatorBuffer = ByteBuffer.allocateDirect(bytesPerLayer + bufferSize);
            bufferPool = null;
            frameCache = new java.util.concurrent.ConcurrentHashMap<>();
        } else {
            // RAM mode: DirectByteBuffer for accumulation (zero-copy decompression),
            // byte[] pool for CPU cache storage
            frameAccumulatorBuffer = ByteBuffer.allocateDirect(bytesPerLayer + bufferSize);
            final int POOL_SIZE = CPU_CACHE_SIZE;
            bufferPool = new java.util.concurrent.ConcurrentLinkedQueue<>();
            for (int i = 0; i < POOL_SIZE; i++) {
                bufferPool.offer(new byte[bytesPerLayer]);
            }
            frameCache = new java.util.concurrent.ConcurrentHashMap<>();
        }
        // Both modes now use DirectByteBuffer for accumulation
        
        final int finalWidth = pixelWidth;
        final int finalHeight = pixelHeight;
        final int finalBytesPerLayer = bytesPerLayer;
        final int finalLayerCount = layerCount;
        final boolean finalUseAstc = useAstc;
        final int finalGpuCacheSize = textureLayerCount;
        final long finalLevel0Offset = level0Offset;
        final long finalLevel0Length = level0Length;
        
        announceLoaded();
        
        // Track loop iterations for wrap-around prefetching
        final int[] decoderLoopIteration = {0};
        final int vPosModulus = 2 * totalFrames;
        
        // Helper: Decode frames into cache
        java.util.function.IntConsumer decodeFrames = (virtualPlaybackPos) -> {
            int currentVirtualDecodePos = (decoderLoopIteration[0] * totalFrames + decodePos[0]) % vPosModulus;
            int framesToDecode = (currentVirtualDecodePos - virtualPlaybackPos + vPosModulus) % vPosModulus;
            // framesToDecode counts the shown frame
            int targetFramesAhead = PREFETCH_AHEAD + 1;
            // A long catch-up to a seek goes on over several calls, so new seeks and stops get in
            long skipDeadline = System.nanoTime() + SKIP_SLICE_NANOS;
            inputPending[0] = false;
            if (streamError[0] != null) return;
            
            while (framesToDecode < targetFramesAhead) {
                // Once decompression has ended and the last frames are out of the accumulator (a
                // 64 KB chunk can hold several small frames), restart for the next loop
                boolean framesLeft = frameAccumulatorBuffer.position() >= finalBytesPerLayer;
                if (decompressEnded[0] && !framesLeft) {
                    if (!loop) break;
                    // Restart decompression for next loop
                    decoderLoopIteration[0]++;
                    decodePos[0] = 0;
                    frameAccumulatorBuffer.clear();  // Both modes use DirectByteBuffer now
                    decompressEnded[0] = !restartInternalZstdStream(streamId, local, finalLevel0Offset, finalLevel0Length, 0);
                    if (decompressEnded[0]) break;
                    logI("Internal zstd prefetch wrap-around: restarting for loop " + decoderLoopIteration[0]);
                    continue;
                }
                
                while (bytesToSkip[0] > 0 && !decompressEnded[0]) {
                    if (System.nanoTime() > skipDeadline) return;
                    decompressBuffer.clear();
                    long result = AlphaVideoGpuNative.nativeStreamingDecompressContinueToBuffer(streamId[0], decompressBuffer, bufferSize);
                    if (result == -1) { streamError[0] = streamFailure(local, streamId[0]); return; }
                    int status = (int)(result >> 32);
                    int bytesWritten = (int)(result & 0xFFFFFFFFL);
                    if (status == AlphaVideoGpuNative.STREAM_PENDING) { inputPending[0] = true; return; }
                    if (bytesWritten >= bytesToSkip[0]) {
                        decompressBuffer.position((int) bytesToSkip[0]);
                        decompressBuffer.limit(bytesWritten);
                        frameAccumulatorBuffer.put(decompressBuffer);
                        bytesToSkip[0] = 0;
                    } else {
                        bytesToSkip[0] -= bytesWritten;
                    }
                    if (status == AlphaVideoGpuNative.STREAM_DONE) { decompressEnded[0] = true; break; }
                }
                if (decompressEnded[0] && frameAccumulatorBuffer.position() < finalBytesPerLayer) continue;
                
                int accumulatorSize = frameAccumulatorBuffer.position();
                
                // Decompress until we have a full frame
                while (accumulatorSize < finalBytesPerLayer && !decompressEnded[0]) {
                    decompressBuffer.clear();
                    long result = AlphaVideoGpuNative.nativeStreamingDecompressContinueToBuffer(streamId[0], decompressBuffer, bufferSize);
                    if (result == -1) { streamError[0] = streamFailure(local, streamId[0]); return; }
                    int status = (int)(result >> 32);
                    int bytesWritten = (int)(result & 0xFFFFFFFFL);
                    if (status == AlphaVideoGpuNative.STREAM_PENDING) { inputPending[0] = true; return; }
                    if (bytesWritten > 0) {
                        decompressBuffer.limit(bytesWritten);
                        decompressBuffer.position(0);
                        // Zero-copy: transfer directly between ByteBuffers (both modes)
                        frameAccumulatorBuffer.put(decompressBuffer);
                        accumulatorSize += bytesWritten;
                    }
                    if (status == AlphaVideoGpuNative.STREAM_DONE) { decompressEnded[0] = true; break; }
                }
                
                if (accumulatorSize >= finalBytesPerLayer) {
                    int virtualPos = (decoderLoopIteration[0] * totalFrames + decodePos[0]) % vPosModulus;
                    int actualFrame = decodePos[0];
                    decodePos[0]++;
                    
                    if (useVramStrategy) {
                        int gpuSlot = actualFrame % finalGpuCacheSize;
                        int slotCurrentVPos = ringSlots[gpuSlot];
                        int slotAge = (virtualPlaybackPos - slotCurrentVPos + vPosModulus) % vPosModulus;
                        boolean slotIsOld = slotAge > 0 && slotAge < vPosModulus / 2;
                        boolean canOverwrite = (slotCurrentVPos == -1) || (slotCurrentVPos == virtualPos) || slotIsOld;
                        
                        if (canOverwrite && ringSlots[gpuSlot] != virtualPos) {
                            frameAccumulatorBuffer.flip();
                            // Use PBO for async upload when available
                            boolean uploaded = pbosInitialized ?
                                AlphaVideoGpuNative.nativeUploadTextureArrayLayerPBO(
                                    textureArrayId, gpuSlot, finalWidth, finalHeight, 
                                    frameAccumulatorBuffer, finalBytesPerLayer, finalUseAstc,
                                    astcGlFormat, astcBlockWidth, astcBlockHeight) :
                                AlphaVideoGpuNative.nativeUploadTextureArrayLayerFromBuffer(
                                    textureArrayId, gpuSlot, finalWidth, finalHeight, 
                                    frameAccumulatorBuffer, finalBytesPerLayer, finalUseAstc,
                                    astcGlFormat, astcBlockWidth, astcBlockHeight);
                            if (uploaded) {
                                ringSlots[gpuSlot] = virtualPos;
                            }
                            if (frameAccumulatorBuffer.remaining() > finalBytesPerLayer) {
                                frameAccumulatorBuffer.position(finalBytesPerLayer);
                                frameAccumulatorBuffer.compact();
                            } else {
                                frameAccumulatorBuffer.clear();
                            }
                        } else {
                            byte[] tempData = new byte[finalBytesPerLayer];
                            frameAccumulatorBuffer.flip();
                            frameAccumulatorBuffer.get(tempData, 0, finalBytesPerLayer);
                            frameCache.put(virtualPos, tempData);
                            if (frameAccumulatorBuffer.remaining() > 0) {
                                frameAccumulatorBuffer.compact();
                            } else {
                                frameAccumulatorBuffer.clear();
                            }
                        }
                    } else {
                        // RAM mode: Copy from DirectByteBuffer to pooled byte[] for CPU cache
                        byte[] frameData = bufferPool.poll();
                        if (frameData == null) {
                            frameData = new byte[finalBytesPerLayer];
                        }
                        // Single copy: DirectByteBuffer -> pooled byte[]
                        frameAccumulatorBuffer.flip();
                        frameAccumulatorBuffer.get(frameData, 0, finalBytesPerLayer);
                        frameCache.put(virtualPos, frameData);
                        // Keep remaining data in accumulator
                        if (frameAccumulatorBuffer.remaining() > 0) {
                            frameAccumulatorBuffer.compact();
                        } else {
                            frameAccumulatorBuffer.clear();
                        }
                    }
                    
                    int newDecodePos = (decoderLoopIteration[0] * totalFrames + decodePos[0]) % vPosModulus;
                    framesToDecode = (newDecodePos - virtualPlaybackPos + vPosModulus) % vPosModulus;
                } else if (!decompressEnded[0]) {
                    break;
                }
            }
        };
        
        // Helper: Evict old frames
        java.util.function.IntConsumer evictOldFrames = (virtualPlaybackPos) -> {
            java.util.List<Integer> toRemove = new java.util.ArrayList<>();
            if (useVramStrategy) {
                for (Integer virtualPos : frameCache.keySet()) {
                    int age = (virtualPlaybackPos - virtualPos + vPosModulus) % vPosModulus;
                    if (age > 0 && age < vPosModulus / 2) {
                        toRemove.add(virtualPos);
                    }
                }
            } else {
                for (Integer virtualPos : frameCache.keySet()) {
                    int distance = (virtualPos - virtualPlaybackPos + vPosModulus) % vPosModulus;
                    boolean isBehind = distance > vPosModulus - 2;
                    boolean isAhead = distance <= CPU_CACHE_SIZE;
                    if (!isBehind && !isAhead) {
                        toRemove.add(virtualPos);
                    }
                }
            }
            for (Integer virtualPos : toRemove) {
                byte[] buffer = frameCache.remove(virtualPos);
                if (buffer != null && bufferPool != null && buffer.length == finalBytesPerLayer) {
                    bufferPool.offer(buffer);
                }
            }
        };
        
        // Helper: Ensure frame in GPU
        java.util.function.IntConsumer ensureFrameInGpu = (virtualPos) -> {
            int actualFrame = virtualPos % totalFrames;
            int gpuSlot = actualFrame % finalGpuCacheSize;
            if (ringSlots[gpuSlot] == virtualPos) return;
            byte[] frameData = frameCache.get(virtualPos);
            if (frameData == null) return;
            boolean uploaded = AlphaVideoGpuNative.nativeUploadTextureArrayLayer(
                textureArrayId, gpuSlot, finalWidth, finalHeight, frameData, finalUseAstc,
                astcGlFormat, astcBlockWidth, astcBlockHeight);
            if (uploaded) {
                ringSlots[gpuSlot] = virtualPos;
                if (useVramStrategy) {
                    frameCache.remove(virtualPos);
                }
            }
        };
        
        // Main playback loop
        FrameClock clock = new FrameClock(frameIntervalNanos());
        // Only its parity matters: virtual positions are taken modulo 2 * totalFrames
        int playbackLoopIteration = 0;
        currentFrame = 0;
        boolean frameOwed = true;
        boolean wasPlaying = false;
        // Per pass, for tuning streamingPrefetchAhead: the fewest frames decoded after the shown
        // one, and how often playback waited for the decoder
        int fewestAhead = totalFrames;
        int stalls = 0;
        boolean stalled = false;
        
        try {
            while (!shouldStop.get() && !isDestroyed.get()) {
                int seek = takeSeek();
                if (seek >= 0) {
                    boolean samePass = decoderLoopIteration[0] % 2 == playbackLoopIteration % 2;
                    // Frames from the shown one up to the decoder are buffered (in the cache or the ring)
                    boolean buffered = samePass && seek >= currentFrame && seek < decodePos[0];
                    if (!buffered) {
                        // Carry on decoding when that is no further from the target than the
                        // nearest seek point (the level start, without a seek table)
                        long target = (long) seek * finalBytesPerLayer;
                        long[] seekPoint = ZstdSeekTable.seekPointBefore(seekTable, target);
                        long decoded = (long) decodePos[0] * finalBytesPerLayer
                            + frameAccumulatorBuffer.position() - bytesToSkip[0];
                        if (samePass && seek >= decodePos[0] && !decompressEnded[0] && decoded >= seekPoint[1]) {
                            skipDecodedBytes(frameAccumulatorBuffer, bytesToSkip,
                                (long) (seek - decodePos[0]) * finalBytesPerLayer);
                        } else {
                            decompressEnded[0] = !restartInternalZstdStream(
                                streamId, local, finalLevel0Offset, finalLevel0Length, seekPoint[0]);
                            frameAccumulatorBuffer.clear();
                            bytesToSkip[0] = target - seekPoint[1];
                        }
                        decodePos[0] = seek;
                        decoderLoopIteration[0] = 0;
                        playbackLoopIteration = 0;
                        dropStreamedFrames(frameCache, bufferPool, ringSlots, finalBytesPerLayer);
                    }
                    currentFrame = seek;
                    frameOwed = true;
                    wasPlaying = false;
                }
                
                boolean playing = isPlaying.get();
                if (!playing && !frameOwed) {
                    wasPlaying = false;
                    Thread.sleep(16);
                    continue;
                }
                
                int virtualPlaybackPos = (playbackLoopIteration * totalFrames + currentFrame) % vPosModulus;
                decodeFrames.accept(virtualPlaybackPos);
                if (streamError[0] != null) {
                    final String error = streamError[0];
                    mainHandler.post(() -> notifyError(error));
                    break;
                }
                evictOldFrames.accept(virtualPlaybackPos);
                
                int currentGpuSlot = currentFrame % finalGpuCacheSize;
                boolean frameInGpu = ringSlots[currentGpuSlot] == virtualPlaybackPos;
                boolean frameInCpuCache = frameCache.containsKey(virtualPlaybackPos);
                if (!frameInGpu && !frameInCpuCache) {
                    // Still catching up to a seek: decodeFrames goes on with it
                    if (bytesToSkip[0] > 0 && !decompressEnded[0] && !inputPending[0]) continue;
                    if (playing && !stalled) {
                        stalled = true;
                        stalls++;
                    }
                    // Bytes on their way are expected; anything else is a stall worth a warning
                    if (!inputPending[0]) {
                        Log.w(TAG, "Internal zstd frame not ready: frame=" + currentFrame + ", vPos=" + virtualPlaybackPos);
                    }
                    Thread.sleep(8);
                    continue;
                }
                
                ensureFrameInGpu.accept(virtualPlaybackPos);
                if (!useVramStrategy) {
                    for (int i = 1; i <= 2; i++) {
                        int nextVirtualPos = (virtualPlaybackPos + i) % vPosModulus;
                        int nextGpuSlot = ((currentFrame + i) % totalFrames) % finalGpuCacheSize;
                        // Past the loop point a frame can map to the shown frame's slot
                        if (nextGpuSlot == currentGpuSlot) break;
                        if (frameCache.containsKey(nextVirtualPos) || ringSlots[nextGpuSlot] == nextVirtualPos) {
                            ensureFrameInGpu.accept(nextVirtualPos);
                        }
                    }
                }
                
                renderFrame();
                frameOwed = false;
                if (!playing) {
                    wasPlaying = false;
                    continue;
                }
                if (!wasPlaying) {
                    clock.restart();
                    wasPlaying = true;
                }
                stalled = false;
                int decodedVPos = (decoderLoopIteration[0] * totalFrames + decodePos[0]) % vPosModulus;
                int ahead = (decodedVPos - virtualPlaybackPos + vPosModulus) % vPosModulus - 1;
                fewestAhead = Math.min(fewestAhead, ahead);
                if (enableFrameEvents) {
                    mainHandler.post(() -> notifyFrameUpdate());
                }
                clock.awaitNext();
                
                if (currentFrame + 1 < totalFrames) {
                    currentFrame++;
                } else {
                    logI("Pass buffer: fewest frames ahead " + fewestAhead + " of " + PREFETCH_AHEAD
                        + ", stalls " + stalls);
                    fewestAhead = totalFrames;
                    stalls = 0;
                    if (loop) {
                        currentFrame = 0;
                        playbackLoopIteration = (playbackLoopIteration + 1) % 2;
                        logI("Internal zstd loop transition: playbackLoop=" + playbackLoopIteration);
                    } else {
                        finish();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            AlphaVideoGpuNative.nativeStreamingDecompressEnd(streamId[0]);
        }
        logI("Internal zstd streaming animation ended");
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
        
        logD("OpenGL ES 3.0 initialized successfully");
        return true;
    }
    
    private boolean detectASTCSupport() {
        String extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        if (extensions != null && extensions.contains("GL_KHR_texture_compression_astc_ldr")) {
            logI("The GPU decodes ASTC");
            return true;
        }
        Log.w(TAG, "No GPU ASTC support: decoding frames to RGBA in software");
        return false;
    }
    
    private boolean initShaders() {
        int vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, TEXTURE_ARRAY_VERTEX_SHADER);
        if (vertexShader == 0) {
            Log.e(TAG, "Failed to compile texture array vertex shader");
            return false;
        }
        
        int fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, TEXTURE_ARRAY_FRAGMENT_SHADER);
        if (fragmentShader == 0) {
            GLES30.glDeleteShader(vertexShader);
            Log.e(TAG, "Failed to compile texture array fragment shader");
            return false;
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
            return false;
        }
        
        GLES30.glDeleteShader(vertexShader);
        GLES30.glDeleteShader(fragmentShader);
        
        arrayUniformLayerLocation = GLES30.glGetUniformLocation(arrayProgram, "uLayer");
        logD("Texture array shader initialized (uLayer location: " + arrayUniformLayerLocation + ")");
        return true;
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
    
    /** Takes the frame size, count and rate from the file; returns an error unless it plays. */
    private String applyMetadata(Ktx2Metadata metadata) {
        if (metadata == null) return "Not a KTX2 file";
        if (!metadata.isAstcArray()) {
            return "Only ASTC texture arrays play (this file has vkFormat " + metadata.vkFormat + " and "
                    + metadata.frameCount + " layers); encode it with texenc";
        }
        frameWidth = metadata.width;
        frameHeight = metadata.height;
        totalFrames = metadata.frameCount;
        textureArrayLayerCount = metadata.frameCount;
        frameRate = metadata.frameRate;
        logI("Metadata: " + totalFrames + " frames, " + frameWidth + "x" + frameHeight + ", " + frameRate + " fps");
        return null;
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
     * Configure ASTC block dimensions and GL format from a KTX2 vkFormat value.
     * VkFormat 157-184 are ASTC UNORM/SRGB pairs for all 14 block sizes.
     */
    private void configureASTCFromVkFormat(int vkFormat) {
        // Normalize to base (UNORM) value
        int base = (vkFormat % 2 == 0) ? vkFormat - 1 : vkFormat;
        switch (base) {
            case 157: astcBlockWidth = 4;  astcBlockHeight = 4;  astcGlFormat = GL_COMPRESSED_RGBA_ASTC_4x4_KHR;   break;
            case 159: astcBlockWidth = 5;  astcBlockHeight = 4;  astcGlFormat = GL_COMPRESSED_RGBA_ASTC_5x4_KHR;   break;
            case 161: astcBlockWidth = 5;  astcBlockHeight = 5;  astcGlFormat = GL_COMPRESSED_RGBA_ASTC_5x5_KHR;   break;
            case 163: astcBlockWidth = 6;  astcBlockHeight = 5;  astcGlFormat = GL_COMPRESSED_RGBA_ASTC_6x5_KHR;   break;
            case 165: astcBlockWidth = 6;  astcBlockHeight = 6;  astcGlFormat = GL_COMPRESSED_RGBA_ASTC_6x6_KHR;   break;
            case 167: astcBlockWidth = 8;  astcBlockHeight = 5;  astcGlFormat = GL_COMPRESSED_RGBA_ASTC_8x5_KHR;   break;
            case 169: astcBlockWidth = 8;  astcBlockHeight = 6;  astcGlFormat = GL_COMPRESSED_RGBA_ASTC_8x6_KHR;   break;
            case 171: astcBlockWidth = 8;  astcBlockHeight = 8;  astcGlFormat = GL_COMPRESSED_RGBA_ASTC_8x8_KHR;   break;
            case 173: astcBlockWidth = 10; astcBlockHeight = 5;  astcGlFormat = GL_COMPRESSED_RGBA_ASTC_10x5_KHR;  break;
            case 175: astcBlockWidth = 10; astcBlockHeight = 6;  astcGlFormat = GL_COMPRESSED_RGBA_ASTC_10x6_KHR;  break;
            case 177: astcBlockWidth = 10; astcBlockHeight = 8;  astcGlFormat = GL_COMPRESSED_RGBA_ASTC_10x8_KHR;  break;
            case 179: astcBlockWidth = 10; astcBlockHeight = 10; astcGlFormat = GL_COMPRESSED_RGBA_ASTC_10x10_KHR; break;
            case 181: astcBlockWidth = 12; astcBlockHeight = 10; astcGlFormat = GL_COMPRESSED_RGBA_ASTC_12x10_KHR; break;
            case 183: astcBlockWidth = 12; astcBlockHeight = 12; astcGlFormat = GL_COMPRESSED_RGBA_ASTC_12x12_KHR; break;
            default:  astcBlockWidth = 4;  astcBlockHeight = 4;  astcGlFormat = GL_COMPRESSED_RGBA_ASTC_4x4_KHR;   break;
        }
        logI("ASTC format: " + astcBlockWidth + "x" + astcBlockHeight + " (vkFormat=" + vkFormat + ", glFormat=0x" + Integer.toHexString(astcGlFormat) + ")");
    }
    
    /**
     * Calculate bytes per layer for the current ASTC block size.
     * All ASTC blocks are 16 bytes (128 bits) regardless of block dimensions.
     */
    private int astcBytesPerLayer(int width, int height) {
        int blocksX = (width + astcBlockWidth - 1) / astcBlockWidth;
        int blocksY = (height + astcBlockHeight - 1) / astcBlockHeight;
        return blocksX * blocksY * 16;
    }
    
    /**
     * Load texture array to RAM without GPU upload (for RAM-heavy mode).
     * Returns Object[] = {width, height, layerCount, bytesPerLayer, ByteBuffer frameData}
     * Uses a single contiguous direct ByteBuffer for zero-copy GPU uploads.
     */
    private Object[] loadTextureArrayToRam(ByteBuffer ktx2Data, boolean useAstc) {
        try {
            // Parse KTX2 header to get dimensions and layer count
            if (ktx2Data.capacity() < 104) {
                Log.e(TAG, "KTX2 data too small for header");
                return null;
            }
            
            ByteBuffer header = ktx2Data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            
            // Check magic
            if (header.get(0) != (byte)0xAB || header.get(1) != 0x4B) {
                Log.e(TAG, "Invalid KTX2 magic");
                return null;
            }
            
            int vkFormat = header.getInt(12);
            int pixelWidth = header.getInt(20);
            int pixelHeight = header.getInt(24);
            int layerCount = header.getInt(32);
            if (layerCount == 0) layerCount = 1;
            int supercompressionScheme = header.getInt(44);  // Offset 44, not 48
            
            // Configure ASTC format from vkFormat
            configureASTCFromVkFormat(vkFormat);
            
            // Get level 0 data offset and length
            long level0ByteOffset = header.getLong(80);
            long level0ByteLength = header.getLong(88);
            
            // Calculate ASTC frame size using dynamic block dimensions
            int bytesPerLayer = astcBytesPerLayer(pixelWidth, pixelHeight);
            int totalSize = bytesPerLayer * layerCount;
            
            logD("KTX2 header: " + pixelWidth + "x" + pixelHeight + ", " + layerCount + 
                  " layers, supercompression=" + supercompressionScheme);
            
            // Allocate direct ByteBuffer for zero-copy GPU uploads
            ByteBuffer frameBuffer = ByteBuffer.allocateDirect(totalSize);
            frameBuffer.order(ByteOrder.nativeOrder());
            
            if (supercompressionScheme == 2) { // Zstd
                // Decompress directly into the direct ByteBuffer
                boolean success = AlphaVideoGpuNative.nativeDecompressZstdToBuffer(
                    ktx2Data, level0ByteOffset, level0ByteLength, frameBuffer, totalSize);
                if (!success) {
                    Log.e(TAG, "Zstd decompression to buffer failed");
                    return null;
                }
            } else if (supercompressionScheme == 0) { // None
                // Copy uncompressed data directly to buffer
                ByteBuffer level = ktx2Data.duplicate();
                level.limit((int) (level0ByteOffset + level0ByteLength)).position((int) level0ByteOffset);
                frameBuffer.put(level);
                frameBuffer.rewind();
            } else {
                Log.e(TAG, "Unsupported supercompression: " + supercompressionScheme);
                return null;
            }
            
            logD("Decoded " + layerCount + " frames to direct ByteBuffer, " + 
                  bytesPerLayer + " bytes each, total " + (totalSize / 1024) + " KB");
            
            return new Object[] { pixelWidth, pixelHeight, layerCount, bytesPerLayer, frameBuffer };
        } catch (Exception e) {
            Log.e(TAG, "Failed to load texture array to RAM: " + e.getMessage());
            return null;
        }
    }
    
    private void loadTextureArray(AlphaVideoGpuSource local) throws Exception {
        logD("Loading texture array from: " + local);
        
        // Off the Java heap, whose cap (256 MB on many phones) a large clip can pass
        ByteBuffer ktx2Data = local.readAllDirect(getContext());
        
        boolean useAstc = astcSupported;
        
        // Check memory strategy for non-stream texture array mode
        // "ram" mode: Decode to RAM, upload per-frame (lower VRAM usage)
        // "vram" mode: Upload all to GPU
        boolean useRamMode = "ram".equals(memoryStrategy);
        
        if (useRamMode) {
            logD("RAM-heavy mode: Decoding texture array to RAM, will upload per-frame");
            
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
            textureArrayId = AlphaVideoGpuNative.nativeCreateTextureArray(texWidth, texHeight, gpuRingSize, useAstc, astcGlFormat);
            if (textureArrayId == 0) {
                throw new Exception("Failed to create GPU ring buffer for RAM mode");
            }
            textureArrayLayerCount = gpuRingSize;
            ramHeavyMode = true;
            ramUploadedFrame = -1;
            
            logD("RAM-heavy texture array: " + layerCount + " frames in direct ByteBuffer (" + 
                  (ramFrameCount * ramBytesPerFrame / 1024) + " KB), " +
                  gpuRingSize + " slots in GPU ring buffer");
        } else {
            logD("VRAM mode: Loading texture array with ASTC=" + useAstc);
            
            int[] result = AlphaVideoGpuNative.nativeLoadTextureArray(ktx2Data, useAstc);
            if (result == null || result.length < 4) {
                throw new Exception("Failed to load texture array");
            }
            
            textureArrayId = result[0];
            int texWidth = result[1];
            int texHeight = result[2];
            textureArrayLayerCount = result[3];
            ramHeavyMode = false;
            
            logD("Loaded texture array ID " + textureArrayId + 
                  " (" + texWidth + "x" + texHeight + ", " + textureArrayLayerCount + " layers)");
        }
    }
    
    private void renderFrame() {
        if (eglDisplay == null || eglSurface == null || textureArrayId == 0) {
            return;
        }
        renderTextureArrayFrame();
    }
    
    /**
     * Draws the current frame: samples its layer of the texture array.
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
                boolean useAstc = astcSupported;
                boolean uploaded = AlphaVideoGpuNative.nativeUploadTextureArrayLayerFromBufferWithOffset(
                    textureArrayId, slot, frameWidth, frameHeight, ramFrameBuffer, offset, ramBytesPerFrame, useAstc,
                    astcGlFormat, astcBlockWidth, astcBlockHeight);
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
    
    private void cleanupGL() {
        if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            // The native side finds this view's PBOs by the current context
            if (pbosInitialized && textureArrayId != 0) {
                AlphaVideoGpuNative.nativeCleanupPBOs(textureArrayId);
                pbosInitialized = false;
            }
            
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            
            // Destroying the context below frees the texture array and program
            textureArrayId = 0;
            arrayProgram = 0;
            
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
    
    private static boolean isRemote(String uri) {
        return uri.startsWith("https://") || uri.startsWith("http://");
    }

    /**
     * What a source URI reads from: a local file in place, or for a URL or a data source a range
     * reader, opened here. Blocks on the network.
     */
    private AlphaVideoGpuSource resolveSource(String uri, String cacheKey) throws IOException {
        final String dataSourceName = mPlayingDataSource;
        final boolean appSource = dataSourceName != null;
        if (!appSource && !isRemote(uri)) return AlphaVideoGpuSource.parse(uri);
        if (!RangeSourceLink.available()) {
            if (appSource) {
                throw new NoSourceModuleException("The data source \"" + dataSourceName + "\" needs the "
                    + "range-source add-on (dev.mediacache:range-source; for React Native, react-native-range-source), "
                    + "which the app doesn't include");
            }
            warnWithoutAddOn();
        }

        final Fetcher source;
        if (appSource) {
            source = AppDataSource.create(dataSourceName);
            if (source == null) {
                throw new IOException("No data source is registered as \"" + dataSourceName
                    + "\" (RangeDataSources.register)");
            }
        } else {
            source = new HttpFetcher();
        }
        AlphaVideoGpuReader.Storage storage = storageFor(appSource);
        String storeDir = storage == AlphaVideoGpuReader.Storage.DISK
            ? new File(getContext().getCacheDir(), STORE_NAME).getPath() : STORE_NAME;
        String key = cacheKey != null && !cacheKey.isEmpty() ? cacheKey : uri;
        AlphaVideoGpuReader reader = AlphaVideoGpuReader.open(source, Uri.parse(uri), mPlayingHeaders, storage,
            storeDir, key, mMaxStorageBytes, getContext().getCacheDir().getPath());
        logD("Opened " + uri + " (" + reader.size() + " bytes, storage " + storage + ")");
        return AlphaVideoGpuSource.reader(reader, shouldStop);
    }

    /** A data source without the range-source add-on: reported with {@link #ERROR_NO_SOURCE_MODULE}. */
    private static final class NoSourceModuleException extends IOException {
        NoSourceModuleException(String message) {
            super(message);
        }
    }

    private void warnWithoutAddOn() {
        if (warnedWithoutAddOn.compareAndSet(false, true)) {
            Log.w(TAG, "range-source isn't installed (dev.mediacache:range-source; for React Native, "
                + "npm i react-native-range-source): URLs are downloaded whole for each play, without range "
                + "requests, storage, maxStorageBytes or cacheKey");
        }
    }

    private AlphaVideoGpuReader.Storage storageFor(boolean appSource) {
        switch (mStorage) {
            case STORAGE_DISK: return AlphaVideoGpuReader.Storage.DISK;
            case STORAGE_MEMORY: return AlphaVideoGpuReader.Storage.MEMORY;
            case STORAGE_NONE: return AlphaVideoGpuReader.Storage.NONE;
            default: return appSource ? AlphaVideoGpuReader.Storage.NONE : AlphaVideoGpuReader.Storage.DISK;
        }
    }

    /** What to report when a zstd stream fails: a read that failed after its retries, or corrupt data. */
    private static String streamFailure(AlphaVideoGpuSource local, long streamId) {
        String why = AlphaVideoGpuNative.nativeStreamingError(streamId);
        if (why == null) why = "unknown error";
        return local.reader() != null ? "Reading " + local + " failed: " + why : "Cannot decode " + local + ": " + why;
    }

    /**
     * Points the stream at a zstd frame of the level (0 is its start), opening it again if an
     * earlier open failed. False when the stream can't be used.
     */
    private boolean restartInternalZstdStream(long[] streamId, AlphaVideoGpuSource local,
                                              long level0Offset, long level0Length, long compressedOffset) {
        if (streamId[0] == 0) {
            try {
                streamId[0] = openInternalZstdStream(local, level0Offset, level0Length);
            } catch (IOException e) {
                Log.e(TAG, "Cannot reopen " + local + ": " + e.getMessage());
                return false;
            }
            if (streamId[0] == 0) return false;
        }
        return AlphaVideoGpuNative.nativeStreamingRestartAt(streamId[0], compressedOffset);
    }

    /**
     * Starts a zstd stream over the level0 data of an internally compressed KTX2: memory-mapped in
     * place, or read from the source's range reader.
     */
    private long openInternalZstdStream(AlphaVideoGpuSource local, long level0Offset, long level0Length) throws IOException {
        AlphaVideoGpuReader reader = local.reader();
        if (reader != null) {
            return AlphaVideoGpuNative.nativeReaderZstdStreamingInit(reader.handle(), level0Offset, level0Length);
        }
        try (AssetFileDescriptor afd = local.openFd(getContext())) {
            return AlphaVideoGpuNative.nativeInternalZstdStreamingInit(
                afd.getParcelFileDescriptor().getFd(), afd.getStartOffset() + level0Offset, level0Length);
        }
    }

    private void logD(String message) {
        if (verbose) Log.d(TAG, message);
    }

    private void logI(String message) {
        if (verbose) Log.i(TAG, message);
    }

    // TextureView.SurfaceTextureListener implementation
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        logD("onSurfaceTextureAvailable: " + width + "x" + height);
        isDestroyed.set(false);
        shouldStop.set(false);
        // Always start playback if we have a source - handles screen navigation
        if (mSource != null) {
            startPlayback();
        }
    }
    
    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        logD("onSurfaceTextureSizeChanged: " + width + "x" + height);
    }
    
    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        logD("onSurfaceTextureDestroyed");
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
        logD("onDetachedFromWindow");
        destroy();
    }
    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        logD("onAttachedToWindow");
        isDestroyed.set(false);
    }
}
