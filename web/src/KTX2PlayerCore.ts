/**
 * KTX2PlayerCore - Framework-agnostic WebGL engine for KTX2 animated textures.
 *
 * This is the single source of truth for all web-based KTX2 playback logic.
 * Framework wrappers (React Native Web, React, Vanilla JS) should be thin
 * layers that create/destroy a KTX2PlayerCore instance and map props/options.
 */

import type {
  Metadata,
  KTX2LoadEvent,
  PlayerState,
  MemoryStrategy,
  KTX2PlayerCoreOptions,
} from './types';

import {
  getSharedGL,
  releaseSharedGL,
  getSharedGLResources,
  ensureSharedCanvasSize,
  createDedicatedGL,
} from './webgl-context';

import {
  extractKTX2Metadata,
  ASTC_FORMAT_MAP,
  getASTCBytesPerLayer,
} from './ktx2-parser';

import { getCachedFile, setCachedFile } from './cache';
import { AlphaVideoGpuError, requireASTC } from './errors';

// Re-export types for convenience
export type {
  Metadata,
  KTX2LoadEvent,
  PlayerState,
  MemoryStrategy,
  KTX2PlayerCoreOptions,
};

/** asset://path names the app's bundled media; on the web it is served from the site root. */
function resolveWebUri(uri: string): string {
  return uri.startsWith('asset://') ? '/' + uri.slice('asset://'.length).replace(/^\/+/, '') : uri;
}

function clampInt(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, Math.round(value)));
}

// ============================================================================
// KTX2PlayerCore
// ============================================================================

export class KTX2PlayerCore {
  // -- Configuration --
  private _autoPlay: boolean;
  private _loop: boolean;
  private _playbackSpeed: number;
  private _stream: boolean;
  private _streamingRingSize: number;
  private _streamingCacheSize: number;
  private _streamingPrefetchAhead: number;
  private _memoryStrategy: MemoryStrategy;
  private _verbose: boolean;

  // -- Dependency injection --
  private _ZSTDDecoderClass: (new () => any) | null;

  // -- Callbacks --
  private _onStateChange: ((state: PlayerState) => void) | null;
  private _onLoad: ((event: KTX2LoadEvent) => void) | null;
  private _onFrameUpdate: ((frame: number, total: number) => void) | null;
  private _frameEvents: boolean;
  private _onFinished: (() => void) | null;
  private _onError: ((error: Error) => void) | null;

  // -- Canvas & GL --
  private _canvas: HTMLCanvasElement;
  private _ctx2d: CanvasRenderingContext2D | null = null;
  private _usingSharedGL = false;
  private _gl: WebGL2RenderingContext | WebGLRenderingContext | null = null;
  private _arrayProgram: WebGLProgram | null = null;
  private _positionBuffer: WebGLBuffer | null = null;
  private _texCoordBuffer: WebGLBuffer | null = null;

  // -- Textures --
  private _textureArray: WebGLTexture | null = null;

  // -- Playback --
  private _metadata: Metadata | null = null;
  private _state: PlayerState = 'idle';
  private _currentFrame = 0;
  private _animationFrameId: number | null = null;
  private _lastFrameTime = 0;
  private _loadedSource: string | null = null;

  // -- Decoders --
  private _zstdDecoder: any = null;

  // -- Streaming --
  private _streamingMode = false;
  private _streamingCancelled = false;
  private _streamingFrameCache: Map<number, Uint8Array> = new Map();
  private _useVramStrategy = false;
  private _streamingRingBuffer: WebGLTexture | null = null;
  private _streamingRingSlots: number[] = [];
  private _streamingRingActualSize = 0;
  private _streamingDecodePos = 0;
  private _streamingFramesReady = 0;
  private _streamingLoopCount = 0;
  private _streamingCompressedChunks: Uint8Array[] | null = null;
  private _streamingDecompressState: any = null;
  private _streamingBytesPerLayer = 0;
  private _streamingWidth = 0;
  private _streamingHeight = 0;
  private _streamingLayerCount = 0;
  private _streamingLevel0Offset = 0;
  private _streamingAstcFormat = 0;
  // Frames decoded before this one are dropped: the decoder is catching up to a seek
  private _streamingSkipTo = 0;
  private _backgroundDecodeRunning = false;

  // A clip that doesn't loop has reached its last frame; playing again starts it over
  private _ended = false;
  private _pausedByProp = false;
  // Polls for a frame a seek owes while not playing, until it is decoded
  private _owedFrameId: number | null = null;

  // -- RAM mode (non-streaming texture arrays with memoryStrategy='ram') --
  private _ramHeavyMode = false;
  private _ramFrameData: Uint8Array | null = null;
  private _ramBytesPerFrame = 0;
  private _ramFrameCount = 0;
  private _ramUploadedFrame = -1;
  private _ramAstcFormat = 0;
  private _ramWidth = 0;
  private _ramHeight = 0;

  // ======== Constructor ========

  constructor(canvas: HTMLCanvasElement, options: KTX2PlayerCoreOptions = {}) {
    this._canvas = canvas;
    this._autoPlay = options.autoPlay ?? true;
    this._loop = options.loop ?? true;
    this._playbackSpeed = options.playbackSpeed ?? 1.0;
    this._stream = options.stream ?? true;
    // Same ranges as Android and iOS; a size of 0 would leave the ring with no slots.
    this._streamingRingSize = clampInt(options.streamingRingSize ?? 16, 4, 64);
    this._streamingCacheSize = clampInt(options.streamingCacheSize ?? 60, 10, 200);
    this._streamingPrefetchAhead = clampInt(options.streamingPrefetchAhead ?? 30, 5, 100);
    this._memoryStrategy = options.memoryStrategy === 'ram' ? 'ram' : 'vram';
    this._verbose = options.verbose ?? false;
    this._ZSTDDecoderClass = options.ZSTDDecoderClass ?? null;
    this._onStateChange = options.onStateChange ?? null;
    this._onLoad = options.onLoad ?? null;
    this._onFrameUpdate = options.onFrameUpdate ?? null;
    this._onFinished = options.onFinished ?? null;
    this._onError = options.onError ?? null;
    this._frameEvents = options.frameEvents ?? true;
  }

  // ======== Public API ========

  get state(): PlayerState { return this._state; }
  get currentFrame(): number { return this._currentFrame; }
  get totalFrames(): number { return this._metadata?.frameCount ?? 0; }
  get frameRate(): number { return this._metadata?.frameRate ?? 30; }
  get duration(): number { return this.totalFrames / this.frameRate; }
  get currentTime(): number { return this._currentFrame / this.frameRate; }
  get metadataInfo(): Metadata | null { return this._metadata; }

  /** Whether a source starts playing once loaded. */
  set autoPlay(v: boolean) { this._autoPlay = v; }
  get autoPlay(): boolean { return this._autoPlay; }

  /** Whether onFrameUpdate fires for every frame played, not only for seeks. */
  set frameEvents(v: boolean) { this._frameEvents = v; }
  get frameEvents(): boolean { return this._frameEvents; }

  set loop(v: boolean) { this._loop = v; }
  get loop(): boolean { return this._loop; }

  set playbackSpeed(v: number) {
    this._playbackSpeed = v;
    if (this._state === 'playing') {
      this._stopAnimationLoop();
      this._startAnimationLoop();
    }
  }
  get playbackSpeed(): number { return this._playbackSpeed; }

  /** Update callbacks after construction (useful for wrappers where callbacks change). */
  updateCallbacks(cbs: Partial<Pick<KTX2PlayerCoreOptions,
    'onStateChange' | 'onLoad' | 'onFrameUpdate' | 'onFinished' | 'onError'
  >>): void {
    if (cbs.onStateChange !== undefined) this._onStateChange = cbs.onStateChange;
    if (cbs.onLoad !== undefined) this._onLoad = cbs.onLoad;
    if (cbs.onFrameUpdate !== undefined) this._onFrameUpdate = cbs.onFrameUpdate;
    if (cbs.onFinished !== undefined) this._onFinished = cbs.onFinished;
    if (cbs.onError !== undefined) this._onError = cbs.onError;
  }

  /** Plays from the shown frame, or from the start once a clip that doesn't loop has ended. */
  play(): void {
    if (this._state === 'paused') {
      this.resume();
    } else if (this._state === 'ready' || this._state === 'stopped') {
      if (this._ended) this.seekToFrame(0);
      this._streamingCancelled = false;
      this._setState('playing');
      this._kickStreamingDecode();
      this._lastFrameTime = performance.now();
      this._startAnimationLoop();
    }
  }

  pause(): void {
    if (this._state === 'playing') {
      this._setState('paused');
      this._streamingCancelled = true;
      this._stopAnimationLoop();
    }
  }

  resume(): void {
    if (this._state === 'paused') {
      this._streamingCancelled = false;
      this._setState('playing');
      this._kickStreamingDecode();
      this._lastFrameTime = performance.now();
      this._startAnimationLoop();
    }
  }

  /**
   * Holds playback and decoding, keeping the frames (the `paused` prop, for screen focus and
   * blur). Unpausing plays again only when autoPlay is on; a source loaded while paused waits.
   */
  setPaused(paused: boolean): void {
    this._pausedByProp = paused;
    if (paused) {
      this.pause();
    } else if (this._autoPlay && (this._state === 'paused' || this._state === 'ready')) {
      this.play();
    }
  }

  /** Pauses on the first frame. */
  stop(): void {
    this._setState('stopped');
    this._stopAnimationLoop();
    this.seekToFrame(0);
  }

  /**
   * Shows that frame (clamped to the clip) and keeps playing or stays paused. Ignored until
   * the source has loaded. While streaming, a seek outside the buffered frames decodes again.
   */
  seekToFrame(frame: number): void {
    const total = this._metadata?.frameCount ?? 0;
    if (!total || this._state === 'idle' || this._state === 'loading' || this._state === 'error') return;
    const target = Math.max(0, Math.min(Math.floor(frame) || 0, total - 1));
    this._ended = false;
    if (this._streamingMode) this._moveStreamTo(target);
    this._currentFrame = target;
    this._lastFrameTime = performance.now();
    this._onFrameUpdate?.(target, total);
    // While playing, the animation loop draws it on its next frame
    if (this._state !== 'playing') this._drawWhenReady();
  }

  /** Seeks to the frame showing at that time, in seconds. */
  seekToTime(seconds: number): void {
    this.seekToFrame(Math.floor((seconds > 0 ? seconds : 0) * this.frameRate + 1e-6));
  }

  set verbose(v: boolean) { this._verbose = v; }

  /** Reset loaded source tracking so next setSource will reload even the same URI. */
  resetSource(): void {
    this._loadedSource = null;
  }

  /**
   * Load a source: an http(s) or relative URL, or asset://path (served from the site root),
   * holding an ASTC KTX2 texture array.
   *
   * @param cacheKey stable identity for the IndexedDB cache, for URLs that change while the file
   *                 doesn't (presigned URLs); defaults to the URL
   */
  async setSource(sourceUri: string, cacheKey?: string): Promise<void> {
    try {
      if (this._loadedSource === sourceUri) return;
      this._loadedSource = sourceUri;
      const uri = resolveWebUri(sourceUri);
      this._setState('loading');
      this._stopAnimationLoop();
      this._streamingCancelled = false;
      this._currentFrame = 0;
      this._ended = false;
      this._streamingSkipTo = 0;

      const gl = this._gl || this._initGL();
      if (!gl) return;

      this._cleanupTextures(gl);
      this._cleanupStreamingState(gl);
      this._cleanupRamState();

      // Fetch (with IndexedDB cache)
      const cacheId = cacheKey || uri;
      let arrayBuffer = await getCachedFile(cacheId, this._verbose);
      if (!arrayBuffer) {
        const response = await fetch(uri);
        if (!response.ok) throw new Error(`Failed to fetch: ${response.status}`);
        arrayBuffer = await response.arrayBuffer();
        setCachedFile(cacheId, arrayBuffer, this._verbose);
      }
      const data = new Uint8Array(arrayBuffer);

      await this._loadKTX2Source(gl, data);

      this._currentFrame = 0;
      this._finishLoad();
    } catch (error) {
      console.error('[AlphaVideoGpu] Load error:', error);
      this._setState('error');
      this._onError?.(error as Error);
    }
  }

  private _log(...args: unknown[]): void {
    if (this._verbose) console.log(...args);
  }

  /** Tear down all resources. Call when the player is no longer needed. */
  destroy(): void {
    this._streamingCancelled = true;
    this._stopAnimationLoop();
    if (this._owedFrameId !== null) cancelAnimationFrame(this._owedFrameId);
    this._owedFrameId = null;
    const gl = this._gl;
    if (gl) {
      if (this._textureArray) gl.deleteTexture(this._textureArray);
      if (this._streamingRingBuffer) gl.deleteTexture(this._streamingRingBuffer);
      if (!this._usingSharedGL) {
        if (this._arrayProgram) gl.deleteProgram(this._arrayProgram);
        if (this._positionBuffer) gl.deleteBuffer(this._positionBuffer);
        if (this._texCoordBuffer) gl.deleteBuffer(this._texCoordBuffer);
      }
    }
    if (this._usingSharedGL) {
      releaseSharedGL();
      this._usingSharedGL = false;
    }
    this._streamingMode = false;
    this._streamingFrameCache.clear();
    this._streamingCompressedChunks = null;
    this._closeStreamingGenerator();
    this._streamingDecompressState = null;
    this._ramFrameData = null;
    this._ramHeavyMode = false;
    this._loadedSource = null;
    this._state = 'idle';
  }

  // ======== Private: State ========

  private _setState(newState: PlayerState): void {
    if (this._state === newState) return;
    this._state = newState;
    this._onStateChange?.(newState);
  }

  private _finishLoad(): void {
    this._setState('ready');
    if (this._autoPlay && !this._pausedByProp) {
      this._setState('playing');
      this._lastFrameTime = performance.now();
      this._startAnimationLoop();
    } else {
      this._drawWhenReady();
    }
  }

  // ======== Private: GL Init ========

  private _initGL(): WebGL2RenderingContext | WebGLRenderingContext | null {
    // Try shared context first
    const shared = getSharedGL();
    if (shared) {
      const ctx2d = this._canvas.getContext('2d', { alpha: true });
      if (ctx2d) {
        this._ctx2d = ctx2d;
        this._usingSharedGL = true;
        this._gl = shared.gl;
        this._arrayProgram = shared.arrayProgram;
        this._positionBuffer = shared.positionBuffer;
        this._texCoordBuffer = shared.texCoordBuffer;
        return shared.gl;
      }
    }

    // Fallback: dedicated context
    const dedicated = createDedicatedGL(this._canvas);
    if (!dedicated) {
      this._onError?.(new AlphaVideoGpuError('WEBGL_UNSUPPORTED', 'WebGL is not supported'));
      return null;
    }

    this._usingSharedGL = false;
    this._gl = dedicated.gl;
    this._arrayProgram = dedicated.arrayProgram;
    this._positionBuffer = dedicated.positionBuffer;
    this._texCoordBuffer = dedicated.texCoordBuffer;
    return dedicated.gl;
  }

  // ======== Private: ZSTD ========

  private async _ensureZstdDecoder(): Promise<any> {
    if (this._zstdDecoder) return this._zstdDecoder;
    if (this._ZSTDDecoderClass) {
      this._zstdDecoder = new this._ZSTDDecoderClass();
    } else if (typeof (globalThis as any).ZSTDDecoder !== 'undefined') {
      this._zstdDecoder = new (globalThis as any).ZSTDDecoder();
    } else {
      throw new Error('ZSTDDecoder not available');
    }
    await this._zstdDecoder.init();
    return this._zstdDecoder;
  }

  // ======== Private: Cleanup ========

  private _cleanupTextures(gl: WebGLRenderingContext): void {
    if (this._textureArray) { gl.deleteTexture(this._textureArray); this._textureArray = null; }
  }

  private _cleanupStreamingState(gl: WebGLRenderingContext): void {
    if (this._streamingRingBuffer) { gl.deleteTexture(this._streamingRingBuffer); this._streamingRingBuffer = null; }
    this._streamingMode = false;
    this._streamingFrameCache.clear();
    this._streamingCompressedChunks = null;
    this._closeStreamingGenerator();
    this._streamingDecompressState = null;
  }

  private _cleanupRamState(): void {
    this._ramFrameData = null;
    this._ramHeavyMode = false;
    this._ramBytesPerFrame = 0;
    this._ramFrameCount = 0;
    this._ramUploadedFrame = -1;
  }

  // ======== Private: Emit ========

  private _emitLoad(metadata: Metadata): void {
    this._onLoad?.({
      width: metadata.frameWidth,
      height: metadata.frameHeight,
      frameCount: metadata.frameCount,
      frameRate: metadata.frameRate,
      duration: metadata.frameCount / metadata.frameRate,
    });
  }

  // ======== Private: Load KTX2 Source ========

  private async _loadKTX2Source(gl: WebGLRenderingContext, data: Uint8Array): Promise<void> {
    const metadata = extractKTX2Metadata(data);
    if (!metadata) throw new Error('No metadata found in KTX2 file. Ensure the file was encoded with metadata injection.');
    if (metadata.mode !== 'astc' || metadata.packingMode !== 'array') {
      throw new Error(`Only ASTC texture arrays play (this file is mode '${metadata.mode ?? ''}', packing '${metadata.packingMode ?? ''}'); encode it with texenc`);
    }
    if (!(gl instanceof WebGL2RenderingContext) || !this._arrayProgram) {
      throw new AlphaVideoGpuError('WEBGL_UNSUPPORTED', 'Texture arrays need WebGL2');
    }

    this._log(`[AlphaVideoGpu] Metadata: ${metadata.frameCount} frames, ${metadata.frameWidth}x${metadata.frameHeight}`);
    this._metadata = metadata;
    this._emitLoad(metadata);

    if (this._stream) {
      const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
      const supercompressionScheme = view.getUint32(44, true);
      if (supercompressionScheme === 2) {
        this._log('[AlphaVideoGpu] Using internal zstd streaming mode (stream=true)');
        const success = await this._loadInternalZstdStreamingTextureArray(gl, data, metadata);
        if (!success) throw new Error('Failed to load internal zstd streaming texture array');
      } else if (supercompressionScheme === 0) {
        this._log('[AlphaVideoGpu] Using uncompressed streaming mode (stream=true)');
        const success = await this._loadUncompressedStreamingTextureArray(gl, data, metadata);
        if (!success) throw new Error('Failed to load uncompressed streaming texture array');
      } else {
        throw new Error(`Unsupported supercompressionScheme for streaming: ${supercompressionScheme}`);
      }
    } else {
      const success = await this._loadTextureArray(gl, data, metadata);
      if (!success) throw new Error('Failed to load texture array');
    }
  }

  // ======== Private: Load Texture Array (non-streaming) ========

  private async _loadTextureArray(
    gl: WebGL2RenderingContext, ktx2Data: Uint8Array, metadata: Metadata
  ): Promise<boolean> {
    try {
      const useRamMode = this._memoryStrategy === 'ram';
      const view = new DataView(ktx2Data.buffer, ktx2Data.byteOffset, ktx2Data.byteLength);
      const vkFormat = view.getUint32(12, true);
      const width = view.getUint32(20, true);
      const height = view.getUint32(24, true);
      const layerCount = view.getUint32(32, true);
      const supercompressionScheme = view.getUint32(44, true);

      this._log(`[AlphaVideoGpu] Texture array: ${width}x${height}, ${layerCount} layers (${useRamMode ? 'RAM' : 'VRAM'} mode)`);

      const level0Offset = Number(view.getBigUint64(80, true));
      const level0Length = Number(view.getBigUint64(88, true));
      const level0UncompressedLength = Number(view.getBigUint64(96, true));

      let compressedData: Uint8Array;
      if (supercompressionScheme === 2) {
        const zstdData = new Uint8Array(ktx2Data.buffer, ktx2Data.byteOffset + level0Offset, level0Length);
        const decoder = await this._ensureZstdDecoder();
        compressedData = decoder.decode(zstdData, level0UncompressedLength);
        this._log(`[AlphaVideoGpu] WASM zstd decompressed ${level0Length} -> ${compressedData.length} bytes`);
      } else {
        compressedData = new Uint8Array(ktx2Data.buffer, ktx2Data.byteOffset + level0Offset, level0Length);
      }

      requireASTC(gl);
      const astcFormat = ASTC_FORMAT_MAP[vkFormat];
      if (!astcFormat) throw new Error(`Unsupported ASTC VkFormat: ${vkFormat}`);

      const textureArray = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D_ARRAY, textureArray);

      if (useRamMode) {
        const gpuRingSize = 2;
        const bytesPerLayer = getASTCBytesPerLayer(width, height, vkFormat);
        const emptyData = new Uint8Array(bytesPerLayer * gpuRingSize);
        gl.compressedTexImage3D(gl.TEXTURE_2D_ARRAY, 0, astcFormat, width, height, gpuRingSize, 0, emptyData);

        this._ramFrameData = compressedData;
        this._ramBytesPerFrame = bytesPerLayer;
        this._ramFrameCount = layerCount;
        this._ramUploadedFrame = -1;
        this._ramAstcFormat = astcFormat;
        this._ramWidth = width;
        this._ramHeight = height;
        this._ramHeavyMode = true;

        const cpuMB = (compressedData.length / (1024 * 1024)).toFixed(1);
        const gpuMB = (bytesPerLayer * gpuRingSize / (1024 * 1024)).toFixed(2);
        this._log(`[AlphaVideoGpu] RAM mode: ${layerCount} frames in CPU (${cpuMB}MB), ${gpuRingSize} slots in GPU (${gpuMB}MB)`);
      } else {
        this._ramHeavyMode = false;
        gl.compressedTexImage3D(gl.TEXTURE_2D_ARRAY, 0, astcFormat, width, height, layerCount, 0, compressedData);
        const glError = gl.getError();
        if (glError !== gl.NO_ERROR) {
          console.error('[AlphaVideoGpu] WebGL error after texture array upload:', glError);
          return false;
        }
        const dataMB = (compressedData.length / (1024 * 1024)).toFixed(1);
        this._log(`[AlphaVideoGpu] VRAM mode: ${dataMB}MB uploaded to GPU`);
      }

      gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

      this._textureArray = textureArray;

      this._log(`[AlphaVideoGpu] ✓ Texture array loaded: ${width}x${height}, ${layerCount} layers`);
      return true;
    } catch (error) {
      if (error instanceof AlphaVideoGpuError) throw error;
      console.error('[AlphaVideoGpu] Failed to load texture array:', error);
      return false;
    }
  }

  // ======== Private: Streaming Ring Buffer Init ========

  private _initStreamingRingBuffer(
    gl: WebGL2RenderingContext, width: number, height: number,
    astcFormat: number, layerCount: number, vkFormat?: number
  ): void {
    const useVram = this._memoryStrategy !== 'ram';
    this._useVramStrategy = useVram;

    const gpuCacheSize = useVram ? this._streamingCacheSize : this._streamingRingSize;
    const ringSize = Math.min(gpuCacheSize, layerCount);

    this._log(`[AlphaVideoGpu] Memory strategy: ${this._memoryStrategy} (useVram=${useVram}), GPU cache: ${ringSize} slots`);

    const bytesPerLayer = vkFormat != null
      ? getASTCBytesPerLayer(width, height, vkFormat)
      : Math.ceil(width / 4) * Math.ceil(height / 4) * 16; // fallback for legacy callers

    const ringBuffer = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D_ARRAY, ringBuffer);
    gl.texStorage3D(gl.TEXTURE_2D_ARRAY, 1, astcFormat, width, height, ringSize);
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

    this._streamingRingBuffer = ringBuffer;
    this._streamingRingActualSize = ringSize;
    this._streamingRingSlots = new Array(ringSize).fill(-1);
    this._streamingBytesPerLayer = bytesPerLayer;
    this._streamingWidth = width;
    this._streamingHeight = height;
    this._streamingAstcFormat = astcFormat;

    this._log(`[AlphaVideoGpu] Ring buffer initialized: ${ringSize} slots, ${bytesPerLayer} bytes/layer`);
  }

  // ======== Private: Uncompressed Streaming ========

  private async _loadUncompressedStreamingTextureArray(
    gl: WebGL2RenderingContext, ktx2Data: Uint8Array, metadata: Metadata
  ): Promise<boolean> {
    try {
      this._log('[AlphaVideoGpu] Starting uncompressed streaming playback...');
      const view = new DataView(ktx2Data.buffer, ktx2Data.byteOffset, ktx2Data.byteLength);
      const vkFormat = view.getUint32(12, true);
      const width = view.getUint32(20, true);
      const height = view.getUint32(24, true);
      const layerCount = view.getUint32(32, true);
      const supercompressionScheme = view.getUint32(44, true);

      if (supercompressionScheme !== 0) throw new Error('Uncompressed streaming requires supercompressionScheme=0');

      const level0Offset = Number(view.getBigUint64(80, true));
      const bytesPerLayer = getASTCBytesPerLayer(width, height, vkFormat);

      this._log(`[AlphaVideoGpu] Uncompressed streaming: ${width}x${height}, ${layerCount} layers, ${bytesPerLayer} bytes/layer`);

      requireASTC(gl);
      const astcFormat = ASTC_FORMAT_MAP[vkFormat];
      if (!astcFormat) throw new Error(`Unsupported ASTC VkFormat: ${vkFormat}`);

      this._initStreamingRingBuffer(gl, width, height, astcFormat, layerCount, vkFormat);

      this._streamingMode = true;
      this._streamingLayerCount = layerCount;
      this._streamingLevel0Offset = level0Offset;
      this._streamingFrameCache = new Map();
      this._streamingDecodePos = layerCount;
      this._streamingFramesReady = layerCount;

      const fullDataRef = ktx2Data;
      const actualRingSize = this._streamingRingActualSize;

      // Every frame is a view into the file, so the "cache" holds nothing and copies nothing
      const frameView = (frameIndex: number): Uint8Array | undefined => {
        const frameOffset = level0Offset + (frameIndex * bytesPerLayer);
        if (frameIndex < 0 || frameOffset + bytesPerLayer > fullDataRef.length) return undefined;
        return new Uint8Array(fullDataRef.buffer, fullDataRef.byteOffset + frameOffset, bytesPerLayer);
      };

      for (let i = 0; i < actualRingSize; i++) {
        gl.bindTexture(gl.TEXTURE_2D_ARRAY, this._streamingRingBuffer);
        gl.compressedTexSubImage3D(gl.TEXTURE_2D_ARRAY, 0, 0, 0, i, width, height, 1, astcFormat, frameView(i)!);
        this._streamingRingSlots[i] = i;
      }

      const fileFrames = {
        get: frameView,
        has: (frameIndex: number): boolean => frameIndex >= 0 && frameIndex < layerCount,
        set: () => {},
        delete: () => {},
        clear: () => {},
        size: 0,
        keys: () => [][Symbol.iterator](),
      };
      this._streamingFrameCache = fileFrames as any;

      this._textureArray = this._streamingRingBuffer;

      this._log(`[AlphaVideoGpu] ✓ Uncompressed streaming ready: ${metadata.frameCount} frames @ ${metadata.frameRate} fps`);
      return true;
    } catch (error) {
      if (error instanceof AlphaVideoGpuError) throw error;
      console.error('[AlphaVideoGpu] Uncompressed streaming load failed:', error);
      return false;
    }
  }

  // ======== Private: Internal Zstd Streaming ========

  private async _loadInternalZstdStreamingTextureArray(
    gl: WebGL2RenderingContext, ktx2Data: Uint8Array, metadata: Metadata
  ): Promise<boolean> {
    try {
      this._log('[AlphaVideoGpu] Starting internal zstd streaming playback...');
      const view = new DataView(ktx2Data.buffer, ktx2Data.byteOffset, ktx2Data.byteLength);
      const vkFormat = view.getUint32(12, true);
      const width = view.getUint32(20, true);
      const height = view.getUint32(24, true);
      const layerCount = view.getUint32(32, true);

      const level0Offset = Number(view.getBigUint64(80, true));
      const level0Length = Number(view.getBigUint64(88, true));
      const level0UncompressedLength = Number(view.getBigUint64(96, true));

      const bytesPerLayer = getASTCBytesPerLayer(width, height, vkFormat);

      this._log(`[AlphaVideoGpu] Internal zstd streaming: ${width}x${height}, ${layerCount} layers, compressed=${level0Length}, uncompressed=${level0UncompressedLength}`);

      requireASTC(gl);
      const astcFormat = ASTC_FORMAT_MAP[vkFormat];
      if (!astcFormat) throw new Error(`Unsupported ASTC VkFormat: ${vkFormat}`);

      const decoder = await this._ensureZstdDecoder();
      const compressedData = new Uint8Array(ktx2Data.buffer, ktx2Data.byteOffset + level0Offset, level0Length);

      this._initStreamingRingBuffer(gl, width, height, astcFormat, layerCount, vkFormat);

      this._streamingMode = true;
      this._streamingLayerCount = layerCount;
      this._streamingLevel0Offset = level0Offset;
      this._streamingFrameCache = new Map();
      this._streamingDecodePos = 0;
      this._streamingFramesReady = 0;
      this._streamingCompressedChunks = [compressedData];

      const generator = decoder.decodeStreaming(this._streamingCompressedChunks);
      this._streamingDecompressState = {
        generator,
        frameAccumulator: new Uint8Array(0),
        totalBytesDecoded: 0,
        totalFramesDecoded: 0,
      };

      // Pre-decode initial frames
      const actualRingSize = this._streamingRingActualSize;
      const prefetchCount = Math.min(this._streamingPrefetchAhead, layerCount);
      this._log(`[AlphaVideoGpu] Pre-decoding ${prefetchCount} frames...`);

      for (let i = 0; i < prefetchCount && !this._streamingCancelled; i++) {
        const frameData = await this._decodeNextFrame();
        if (!frameData) break;
        this._streamingFrameCache.set(i, frameData);
        this._streamingDecodePos = i + 1;
        this._streamingFramesReady = i + 1;
        if (i < actualRingSize) {
          gl.bindTexture(gl.TEXTURE_2D_ARRAY, this._streamingRingBuffer);
          gl.compressedTexSubImage3D(gl.TEXTURE_2D_ARRAY, 0, 0, 0, i, width, height, 1, astcFormat, frameData);
          this._streamingRingSlots[i] = i;
        }
      }

      this._textureArray = this._streamingRingBuffer;

      this._startBackgroundDecompression();

      this._log(`[AlphaVideoGpu] ✓ Internal zstd streaming ready: ${metadata.frameCount} frames @ ${metadata.frameRate} fps`);
      return true;
    } catch (error) {
      if (error instanceof AlphaVideoGpuError) throw error;
      console.error('[AlphaVideoGpu] Internal zstd streaming load failed:', error);
      return false;
    }
  }

  // ======== Private: Decode Next Frame (internal zstd) ========

  private async _decodeNextFrame(): Promise<Uint8Array | null> {
    const state = this._streamingDecompressState;
    if (!state || !state.generator) return null;

    const bytesPerLayer = this._streamingBytesPerLayer;

    while (state.frameAccumulator.length < bytesPerLayer) {
      const result = state.generator.next();
      if (result.done) {
        if (state.frameAccumulator.length >= bytesPerLayer) break;
        return null;
      }
      const chunk = result.value as Uint8Array;
      if (chunk && chunk.length > 0) {
        const newAccum = new Uint8Array(state.frameAccumulator.length + chunk.length);
        newAccum.set(state.frameAccumulator);
        newAccum.set(chunk, state.frameAccumulator.length);
        state.frameAccumulator = newAccum;
      }
    }

    if (state.frameAccumulator.length >= bytesPerLayer) {
      const frameData = state.frameAccumulator.slice(0, bytesPerLayer);
      state.frameAccumulator = state.frameAccumulator.slice(bytesPerLayer);
      state.totalFramesDecoded++;
      return frameData;
    }
    return null;
  }

  // ======== Private: Background Decompression (internal zstd) ========

  private _startBackgroundDecompression(): void {
    if (this._backgroundDecodeRunning) return;
    this._backgroundDecodeRunning = true;
    const decompressLoop = async () => {
      const layerCount = this._streamingLayerCount;
      // In RAM mode frames past the cache window would be evicted as soon as they're decoded
      const aheadLimit = this._useVramStrategy
        ? this._streamingPrefetchAhead
        : Math.min(this._streamingPrefetchAhead, this._streamingCacheSize);
      while (!this._streamingCancelled && this._streamingDecodePos < layerCount) {
        const framesAhead = this._streamingDecodePos - this._currentFrame;
        if (framesAhead < aheadLimit) {
          const frameData = await this._decodeNextFrame();
          if (frameData) {
            const frameIndex = this._streamingDecodePos;
            if (frameIndex >= this._streamingSkipTo) this._streamingFrameCache.set(frameIndex, frameData);
            this._streamingDecodePos++;
            this._streamingFramesReady = this._streamingDecodePos;
            this._evictOldFramesFromCache(this._currentFrame);
          } else {
            break;
          }
        } else {
          await new Promise(resolve => setTimeout(resolve, 16));
        }
      }
    };
    decompressLoop().finally(() => { this._backgroundDecodeRunning = false; });
  }

  // ======== Private: Ensure Frame In Ring Buffer ========

  private _ensureFrameInRingBuffer(frameIndex: number): number {
    const gl = this._gl as WebGL2RenderingContext;
    if (!gl || !this._streamingRingBuffer) return -1;

    const ringSize = this._streamingRingActualSize;
    const ringSlots = this._streamingRingSlots;
    const frameCache = this._streamingFrameCache;

    const existingSlot = ringSlots.indexOf(frameIndex);
    if (existingSlot !== -1) return existingSlot;

    const frameData = frameCache.get(frameIndex);
    if (!frameData) return -1;

    const slot = frameIndex % ringSize;

    gl.bindTexture(gl.TEXTURE_2D_ARRAY, this._streamingRingBuffer);
    gl.compressedTexSubImage3D(
      gl.TEXTURE_2D_ARRAY, 0, 0, 0, slot,
      this._streamingWidth, this._streamingHeight, 1,
      this._streamingAstcFormat, frameData
    );
    ringSlots[slot] = frameIndex;
    return slot;
  }

  // ======== Private: Evict Old Frames From Cache ========

  private _evictOldFramesFromCache(currentPlaybackFrame: number): void {
    const frameCache = this._streamingFrameCache;
    const metadata = this._metadata;
    if (!metadata) return;

    const toRemove: number[] = [];

    if (this._useVramStrategy) {
      for (const frameIndex of frameCache.keys()) {
        if (frameIndex < currentPlaybackFrame - 2) toRemove.push(frameIndex);
      }
      if (frameCache.size - toRemove.length > 5) {
        const sorted = Array.from(frameCache.keys()).sort((a, b) => a - b);
        for (const frameIndex of sorted) {
          if (frameIndex < currentPlaybackFrame && !toRemove.includes(frameIndex)) {
            toRemove.push(frameIndex);
            if (frameCache.size - toRemove.length <= 5) break;
          }
        }
      }
    } else {
      const minFrame = currentPlaybackFrame - 2;
      const maxFrame = currentPlaybackFrame + this._streamingCacheSize;
      for (const frameIndex of frameCache.keys()) {
        if (frameIndex < minFrame || frameIndex > maxFrame) toRemove.push(frameIndex);
      }
    }

    for (const frameIndex of toRemove) frameCache.delete(frameIndex);

    if (frameCache.size > this._streamingCacheSize + 10) {
      console.warn(`[AlphaVideoGpu] Frame cache too large: ${frameCache.size} frames (max: ${this._streamingCacheSize}), playback: ${currentPlaybackFrame}`);
    }
  }

  // ======== Private: Handle Streaming Loop ========

  private _handleStreamingLoop(): void {
    // Uncompressed streaming reads any frame straight from the file
    if (!this._streamingDecompressState) return;
    this._streamingLoopCount++;
    this._log(`[AlphaVideoGpu] 🔄 Loop #${this._streamingLoopCount} - restarting decompression`);
    this._restartStreamingDecode(0);
  }

  /** Starts the streaming decoder, unless it is already running. */
  private _kickStreamingDecode(): void {
    const state = this._streamingDecompressState;
    if (!this._streamingMode || !state || this._streamingCancelled) return;
    this._startBackgroundDecompression();
  }

  /**
   * Points the streaming decoder at target. Frames from target up to the decoder that are all
   * still buffered are kept; a target ahead of the decoder is skipped to; otherwise decoding
   * starts over.
   */
  private _moveStreamTo(target: number): void {
    const state = this._streamingDecompressState;
    if (!state) return;
    const decodePos = this._streamingDecodePos;
    let buffered = target < decodePos;
    for (let f = target; buffered && f < decodePos; f++) {
      buffered = this._streamingFrameCache.has(f) || this._streamingRingSlots.includes(f);
    }
    if (buffered) return;

    if (target >= decodePos && decodePos < this._streamingLayerCount) {
      this._streamingFrameCache.clear();
      this._streamingSkipTo = target;
      this._kickStreamingDecode();
    } else {
      this._restartStreamingDecode(target);
    }
  }

  /** Closes the zstd stream, which frees its WASM buffers; a no-op once it has finished. */
  private _closeStreamingGenerator(): void {
    try {
      this._streamingDecompressState?.generator?.return?.();
    } catch { /* already closed */ }
  }

  /** Decodes from the start again, dropping the frames before skipTo. */
  private _restartStreamingDecode(skipTo: number): void {
    this._streamingFrameCache.clear();
    this._streamingDecodePos = 0;
    this._streamingFramesReady = 0;
    this._streamingSkipTo = skipTo;

    const state = this._streamingDecompressState;
    this._closeStreamingGenerator();

    const compressedChunks = this._streamingCompressedChunks;
    if (compressedChunks && compressedChunks.length > 0 && this._zstdDecoder?.decodeStreaming) {
      const generator = this._zstdDecoder.decodeStreaming(compressedChunks);

      if (state) {
        state.generator = generator;
        state.frameAccumulator = new Uint8Array(0);
        state.totalBytesDecoded = 0;
        state.totalFramesDecoded = 0;
      }

      this._kickStreamingDecode();
    }
  }

  /** Draws the shown frame once it is decoded, for a seek while not playing. */
  private _drawWhenReady(): void {
    if (this._owedFrameId !== null) cancelAnimationFrame(this._owedFrameId);
    this._owedFrameId = null;
    // A paused player decodes only what the seek needs
    const pauseDecodeAfter = this._state === 'paused';
    this._streamingCancelled = false;
    this._kickStreamingDecode();
    const attempt = () => {
      this._owedFrameId = null;
      if (this._state === 'playing') return;
      if (this._render()) {
        if (pauseDecodeAfter && this._state === 'paused') this._streamingCancelled = true;
        return;
      }
      this._owedFrameId = requestAnimationFrame(attempt);
    };
    attempt();
  }

  // ======== Private: Render Frame ========

  /** Draws the current frame; false when it isn't decoded yet, leaving the last one showing. */
  private _render(): boolean {
    const gl = this._gl;
    const metadata = this._metadata;
    const arrayProgram = this._arrayProgram;

    if (!gl || !metadata || !arrayProgram || !this._textureArray) return false;

    const currentFrame = this._currentFrame;
    if (currentFrame >= metadata.frameCount) return false;

    const prepareTarget = () => {
      // If using shared GL context, ensure shared canvas is large enough and set viewport
      if (this._usingSharedGL && getSharedGLResources()) {
        const frameWidth = metadata.frameWidth;
        const frameHeight = metadata.frameHeight;
        ensureSharedCanvasSize(frameWidth, frameHeight);
        gl.viewport(0, 0, frameWidth, frameHeight);
      }
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
    };

    const gl2 = gl as WebGL2RenderingContext;

    let layerIndex: number;

    if (this._streamingMode) {
      // Check if frame is ready
      const slot = currentFrame < this._streamingFramesReady ? this._ensureFrameInRingBuffer(currentFrame) : -1;
      if (slot === -1) return false;
      layerIndex = slot;

      // Prefetch next frames
      for (let i = 1; i <= 3; i++) {
        const nextFrame = currentFrame + i;
        if (nextFrame < metadata.frameCount) {
          this._ensureFrameInRingBuffer(nextFrame);
        }
      }
    } else if (this._ramHeavyMode && this._ramFrameData) {
      // Non-streaming RAM mode: upload current frame on demand
      if (currentFrame !== this._ramUploadedFrame) {
        const offset = currentFrame * this._ramBytesPerFrame;
        const frameData = new Uint8Array(
          this._ramFrameData.buffer,
          this._ramFrameData.byteOffset + offset,
          this._ramBytesPerFrame
        );

        gl2.bindTexture(gl2.TEXTURE_2D_ARRAY, this._textureArray);
        gl2.compressedTexSubImage3D(
          gl2.TEXTURE_2D_ARRAY, 0, 0, 0, 0,
          this._ramWidth, this._ramHeight, 1,
          this._ramAstcFormat, frameData
        );
        this._ramUploadedFrame = currentFrame;
      }
      layerIndex = 0;
    } else {
      // Non-streaming VRAM mode
      layerIndex = currentFrame;
    }

    prepareTarget();
    gl2.useProgram(arrayProgram);
    gl2.activeTexture(gl2.TEXTURE0);
    gl2.bindTexture(gl2.TEXTURE_2D_ARRAY, this._textureArray);
    gl2.uniform1i(gl2.getUniformLocation(arrayProgram, 'u_textureArray'), 0);
    gl2.uniform1f(gl2.getUniformLocation(arrayProgram, 'u_layer'), layerIndex);

    const posLoc = gl2.getAttribLocation(arrayProgram, 'a_position');
    gl2.bindBuffer(gl2.ARRAY_BUFFER, this._positionBuffer);
    gl2.enableVertexAttribArray(posLoc);
    gl2.vertexAttribPointer(posLoc, 2, gl2.FLOAT, false, 0, 0);

    const texLoc = gl2.getAttribLocation(arrayProgram, 'a_texCoord');
    gl2.bindBuffer(gl2.ARRAY_BUFFER, this._texCoordBuffer);
    gl2.enableVertexAttribArray(texLoc);
    gl2.vertexAttribPointer(texLoc, 2, gl2.FLOAT, false, 0, 0);

    gl2.drawArrays(gl2.TRIANGLES, 0, 6);

    // If using shared GL context, copy rendered frame to visible canvas
    if (this._usingSharedGL && this._ctx2d) {
      const sharedRes = getSharedGLResources();
      if (sharedRes) {
        const canvas = this._canvas;
        const ctx2d = this._ctx2d;
        const sharedCanvas = sharedRes.canvas;
        const frameWidth = metadata.frameWidth;
        const frameHeight = metadata.frameHeight;

        // WebGL renders with Y=0 at bottom, but 2D canvas has Y=0 at top
        const srcY = sharedCanvas.height - frameHeight;

        ctx2d.clearRect(0, 0, canvas.width, canvas.height);
        ctx2d.drawImage(
          sharedCanvas,
          0, srcY, frameWidth, frameHeight,
          0, 0, canvas.width, canvas.height
        );
      }
    }
    return true;
  }

  // ======== Private: Animation Loop ========

  private _startAnimationLoop(): void {
    if (this._animationFrameId) return;

    const metadata = this._metadata;
    if (!metadata) return;

    const frameInterval = 1000 / metadata.frameRate;

    const animate = (timestamp: number) => {
      this._animationFrameId = null;
      if (this._state !== 'playing') return;

      const elapsed = timestamp - this._lastFrameTime;

      // A frame that isn't decoded yet holds playback rather than being skipped
      if (elapsed >= frameInterval / this._playbackSpeed && this._render()) {
        this._lastFrameTime = timestamp - (elapsed % (frameInterval / this._playbackSpeed));
        if (this._frameEvents) this._onFrameUpdate?.(this._currentFrame, metadata.frameCount);

        if (this._currentFrame + 1 < metadata.frameCount) {
          this._currentFrame++;
        } else if (this._loop) {
          this._currentFrame = 0;
          if (this._streamingMode && this._streamingFrameCache) {
            this._handleStreamingLoop();
          }
        } else {
          // The last frame stays on screen
          this._ended = true;
          this._setState('stopped');
          this._onFinished?.();
          return;
        }
      }

      this._animationFrameId = requestAnimationFrame(animate);
    };

    this._animationFrameId = requestAnimationFrame(animate);
  }

  private _stopAnimationLoop(): void {
    if (this._animationFrameId) {
      cancelAnimationFrame(this._animationFrameId);
      this._animationFrameId = null;
    }
  }
}
