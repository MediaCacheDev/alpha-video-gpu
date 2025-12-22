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
import com.facebook.react.uimanager.events.RCTEventEmitter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
    
    // Source and metadata
    private String mSource;
    private String mMetadata;
    private String mCacheKey;
    private boolean mSourceLoaded = false;
    
    // Playback settings
    private boolean autoPlay = true;
    private boolean loop = true;
    private float playbackSpeed = 1.0f;
    private boolean enableFrameEvents = false;  // Disabled by default for performance
    
    // Animation state
    private int currentFrame = 0;
    private int totalFrames = 0;
    private float frameRate = 30.0f;
    private long frameIntervalNanos;
    
    // Grid layout
    private int framesPerRow = 0;
    private int framesPerPart = 0;
    private int partCount = 0;
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
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    
    // Cached texture format
    private int cachedTextureFormat = -1;
    
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
        "void main() {\n" +
        "  vec2 uv = mix(uFrameUV.xy, uFrameUV.zw, vTexCoord);\n" +
        "  vec4 color = texture2D(uTexture, uv);\n" +
        "  gl_FragColor = color;\n" +
        "}\n";
    
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
            executor = Executors.newSingleThreadExecutor();
        }
        return executor;
    }
    
    private synchronized void shutdownExecutor() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            executor = null;
        }
    }
    
    // React Native event sending
    private void sendEvent(String eventName, WritableMap params) {
        if (mReactContext != null) {
            mReactContext.getJSModule(RCTEventEmitter.class)
                    .receiveEvent(getId(), eventName, params);
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
        setSource(source, null);
    }
    
    public void setSource(String source, String metadata) {
        Log.d(TAG, "setSource: " + (source != null ? source.substring(0, Math.min(80, source.length())) : "null"));
        
        stopPlayback();
        mSource = source;
        mMetadata = metadata;
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
        
        // Parse metadata
        try {
            parseMetadata(mMetadata);
        } catch (Exception e) {
            mainHandler.post(() -> notifyError("Failed to parse metadata: " + e.getMessage()));
            return;
        }
        
        // Detect texture format
        detectAndCacheTextureFormat();
        
        // Load textures
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
        
        do {
            long loopStartTime = System.nanoTime();
            currentFrame = 0;
            
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
        } while (loop && !shouldStop.get() && !isDestroyed.get());
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
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        
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
        
        Log.d(TAG, "Shaders initialized successfully");
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
        
        Log.i(TAG, "Metadata: " + totalFrames + " frames, " + frameWidth + "x" + frameHeight +
                ", " + frameRate + " fps, " + partCount + " parts");
    }
    
    private void loadPartTextures(String ktx2Path) throws Exception {
        String basePath = ktx2Path.replaceAll("_\\d+\\.ktx2$", "").replaceAll("\\.ktx2$", "");
        
        List<Integer> loadedTextureIds = new ArrayList<>();
        List<Integer> loadedTextureWidths = new ArrayList<>();
        List<Integer> loadedTextureHeights = new ArrayList<>();
        
        if (partCount > 1 && !partInfoList.isEmpty()) {
            Log.d(TAG, "Loading " + partCount + " part files...");
            
            for (int i = 0; i < partCount; i++) {
                String partPath;
                if (i < partInfoList.size() && partInfoList.get(i).file != null) {
                    File ktx2File = new File(ktx2Path);
                    File parentDir = ktx2File.getParentFile();
                    if (parentDir != null) {
                        partPath = new File(parentDir, partInfoList.get(i).file).getAbsolutePath();
                    } else {
                        partPath = partInfoList.get(i).file;
                    }
                } else {
                    partPath = basePath + "_" + i + ".ktx2";
                }
                
                Log.d(TAG, "Loading part " + i + ": " + partPath);
                
                byte[] ktx2Data = readFileBytes(partPath);
                if (ktx2Data == null) {
                    throw new Exception("Failed to read part file: " + partPath);
                }
                
                int[] result = KTX2PlayerModule.nativeTranscodeKTX2(ktx2Data, cachedTextureFormat);
                if (result == null || result.length < 3) {
                    throw new Exception("Failed to transcode part " + i);
                }
                
                loadedTextureIds.add(result[0]);
                loadedTextureWidths.add(result[1]);
                loadedTextureHeights.add(result[2]);
                
                Log.d(TAG, "Loaded part " + i + " texture ID " + result[0] + " (" + result[1] + "x" + result[2] + ")");
            }
        } else {
            Log.d(TAG, "Loading single KTX2 file: " + ktx2Path);
            
            byte[] ktx2Data = readFileBytes(ktx2Path);
            if (ktx2Data == null) {
                throw new Exception("Failed to read KTX2 file: " + ktx2Path);
            }
            
            int[] result = KTX2PlayerModule.nativeTranscodeKTX2(ktx2Data, cachedTextureFormat);
            if (result == null || result.length < 3) {
                throw new Exception("Failed to transcode KTX2 file");
            }
            
            loadedTextureIds.add(result[0]);
            loadedTextureWidths.add(result[1]);
            loadedTextureHeights.add(result[2]);
            
            Log.d(TAG, "Loaded texture ID " + result[0] + " (" + result[1] + "x" + result[2] + ")");
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
    
    private void renderFrame() {
        if (eglDisplay == null || eglSurface == null || textureIds == null || textureIds.length == 0) {
            return;
        }
        
        GLES30.glClearColor(0, 0, 0, 0);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glViewport(0, 0, getWidth(), getHeight());
        GLES30.glUseProgram(program);
        
        // Calculate frame UV
        float[] uv = calculateFrameUV();
        GLES30.glUniform4f(uniformFrameUVLocation, uv[0], uv[1], uv[2], uv[3]);
        
        // Bind texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        int partIndex = getCurrentPartIndex();
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
    
    private float[] calculateFrameUV() {
        if (totalFrames == 0 || textureIds.length == 0) {
            return new float[]{0, 0, 1, 1};
        }

        int partIndex = currentFrame / framesPerPart;
        int frameInPart = currentFrame % framesPerPart;

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
        return Math.min(currentFrame / framesPerPart, textureIds.length - 1);
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
                
                cacheFile = new File(cacheDir, hash + ".ktx2");
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
            
            File tempFile = new File(getContext().getCacheDir(), "ktx2_tmp_" + tempSuffix + ".ktx2");
            
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
