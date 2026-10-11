/**
 * KTX2PlayerCore - Framework-agnostic WebGL engine for KTX2 animated textures.
 *
 * This is the single source of truth for all web-based KTX2 playback logic.
 * Framework wrappers (React Native Web, React, Vanilla JS) should be thin
 * layers that create/destroy a KTX2PlayerCore instance and map props/options.
 */

import type {
  FrameUV,
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
  isGLBContainer,
  parseGLBContainer,
  extractKTX2Metadata,
  detectTextureFormat,
  getGLCompressedFormat,
  ASTC_FORMAT_MAP,
  getASTCBlockSize,
  getASTCBytesPerLayer,
} from './ktx2-parser';

import { getCachedFile, setCachedFile } from './cache';

// Re-export types for convenience
export type {
  FrameUV,
  Metadata,
  KTX2LoadEvent,
  PlayerState,
  MemoryStrategy,
  KTX2PlayerCoreOptions,
};

// ---- Basis Transcoder Singleton ----

let basisModulePromise: Promise<any> | null = null;
let basisModule: any = null;

/** asset://path names the app's bundled media; on the web it is served from the site root. */
function resolveWebUri(uri: string): string {
  return uri.startsWith('asset://') ? '/' + uri.slice('asset://'.length).replace(/^\/+/, '') : uri;
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
  private _basisTranscoderInit: (() => Promise<any>) | null;
  private _ZSTDDecoderClass: (new () => any) | null;

  // -- Callbacks --
  private _onStateChange: ((state: PlayerState) => void) | null;
  private _onLoad: ((event: KTX2LoadEvent) => void) | null;
  private _onFrameUpdate: ((frame: number, total: number) => void) | null;
  private _onFinished: (() => void) | null;
  private _onError: ((error: Error) => void) | null;

  // -- Canvas & GL --
  private _canvas: HTMLCanvasElement;
  private _ctx2d: CanvasRenderingContext2D | null = null;
  private _usingSharedGL = false;
  private _gl: WebGL2RenderingContext | WebGLRenderingContext | null = null;
  private _program: WebGLProgram | null = null;
  private _arrayProgram: WebGLProgram | null = null;
  private _positionBuffer: WebGLBuffer | null = null;
  private _texCoordBuffer: WebGLBuffer | null = null;

  // -- Textures --
  private _textures: WebGLTexture[] = [];
  private _textureArray: WebGLTexture | null = null;
  private _useTextureArray = false;

  // -- Playback --
  private _frameUVs: FrameUV[] = [];
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
  private _streamingDecompressPaused = false;
  private _streamingLoopCount = 0;
  private _streamingCompressedChunks: Uint8Array[] | null = null;
  private _streamingDecompressState: any = null;
  private _streamingBytesPerLayer = 0;
  private _streamingWidth = 0;
  private _streamingHeight = 0;
  private _streamingLayerCount = 0;
  private _streamingLevel0Offset = 0;
  private _streamingAstcFormat = 0;
  private _streamingIsInternalZstd = false;

  // -- RAM mode (non-streaming texture arrays with memoryStrategy='ram') --
  private _ramHeavyMode = false;
  private _ramFrameData: Uint8Array | null = null;
  private _ramBytesPerFrame = 0;
  private _ramFrameCount = 0;
  private _ramUploadedFrame = -1;
  private _ramAstcFormat = 0;
  private _ramWidth = 0;
  private _ramHeight = 0;

  // ======== Static Factory: Self-contained with bundled WASM deps ========

  /**
   * Create a KTX2PlayerCore with bundled Basis Universal + ZSTD decoders.
   * Both JS and WASM are inlined — no external files or <script> tags needed.
   */
  static createBundled(
    canvas: HTMLCanvasElement,
    options: Omit<KTX2PlayerCoreOptions, 'basisTranscoderInit' | 'ZSTDDecoderClass'> = {}
  ): KTX2PlayerCore {
    // Lazy import to avoid pulling WASM blobs when not needed
    const { initBasisTranscoder, ZSTDDecoder } =
      require('./bundled-deps') as typeof import('./bundled-deps');

    return new KTX2PlayerCore(canvas, {
      ...options,
      basisTranscoderInit: initBasisTranscoder,
      ZSTDDecoderClass: ZSTDDecoder as any,
    });
  }

  // ======== Constructor ========

  constructor(canvas: HTMLCanvasElement, options: KTX2PlayerCoreOptions = {}) {
    this._canvas = canvas;
    this._autoPlay = options.autoPlay ?? true;
    this._loop = options.loop ?? true;
    this._playbackSpeed = options.playbackSpeed ?? 1.0;
    this._stream = options.stream ?? false;
    this._streamingRingSize = options.streamingRingSize ?? 16;
    this._streamingCacheSize = options.streamingCacheSize ?? 60;
    this._streamingPrefetchAhead = options.streamingPrefetchAhead ?? 30;
    this._memoryStrategy = options.memoryStrategy ?? 'auto';
    this._verbose = options.verbose ?? false;
    this._basisTranscoderInit = options.basisTranscoderInit ?? null;
    this._ZSTDDecoderClass = options.ZSTDDecoderClass ?? null;
    this._onStateChange = options.onStateChange ?? null;
    this._onLoad = options.onLoad ?? null;
    this._onFrameUpdate = options.onFrameUpdate ?? null;
    this._onFinished = options.onFinished ?? null;
    this._onError = options.onError ?? null;
  }

  // ======== Public API ========

  get state(): PlayerState { return this._state; }
  get currentFrame(): number { return this._currentFrame; }
  get totalFrames(): number { return this._metadata?.frameCount ?? 0; }
  get frameRate(): number { return this._metadata?.frameRate ?? 30; }
  get duration(): number { return this.totalFrames / this.frameRate; }
  get currentTime(): number { return this._currentFrame / this.frameRate; }
  get metadataInfo(): Metadata | null { return this._metadata; }

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

  play(): void {
    if (this._state === 'ready' || this._state === 'paused' || this._state === 'stopped') {
      this._setState('playing');
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
      if (this._streamingMode && this._streamingDecompressState && !this._streamingDecompressState.reachedEnd) {
        this._continueStreamingDecompression();
      }
      this._lastFrameTime = performance.now();
      this._startAnimationLoop();
    }
  }

  stop(): void {
    this._setState('stopped');
    this._stopAnimationLoop();
    this._currentFrame = 0;
  }

  seekToFrame(frame: number): void {
    if (this._metadata) {
      this._currentFrame = Math.max(0, Math.min(frame, this._metadata.frameCount - 1));
      this._render();
    }
  }

  set verbose(v: boolean) { this._verbose = v; }

  /** Reset loaded source tracking so next setSource will reload even the same URI. */
  resetSource(): void {
    this._loadedSource = null;
  }

  /**
   * Load a source: an http(s) or relative URL, or asset://path (served from the site root).
   * Handles .ktx2, .ktx2.zst, .glb, multi-part, streaming, etc.
   *
   * @param cacheKey stable identity for the IndexedDB cache, for URLs that change while the file
   *                 doesn't (presigned URLs); defaults to the URL
   */
  async setSource(sourceUri: string, partUris?: string[], cacheKey?: string): Promise<void> {
    try {
      if (this._loadedSource === sourceUri) return;
      this._loadedSource = sourceUri;
      const uri = resolveWebUri(sourceUri);
      partUris = partUris?.map(resolveWebUri);
      this._setState('loading');
      this._streamingCancelled = false;

      const gl = this._gl || this._initGL();
      if (!gl) return;

      const BasisModule = await this._initBasisTranscoder();
      const targetFormat = detectTextureFormat(gl, BasisModule);

      this._cleanupTextures(gl);
      this._cleanupStreamingState(gl);
      this._cleanupRamState();

      // Streaming .zst
      const isStreamingZst = uri.endsWith('.zst') || uri.includes('.zst?') || /\.ktx2\.[a-f0-9]+\.zst/.test(uri);
      if (isStreamingZst) {
        if (!(gl instanceof WebGL2RenderingContext) || !this._arrayProgram) {
          throw new Error('Streaming mode requires WebGL2');
        }
        const success = await this._loadStreamingTextureArray(gl, uri);
        if (!success) throw new Error('Failed to load streaming texture array');
        this._finishLoad();
        return;
      }

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

      if (isGLBContainer(data)) {
        await this._loadGLBSource(gl, data, BasisModule, targetFormat);
      } else {
        await this._loadKTX2Source(gl, data, BasisModule, targetFormat, uri, partUris);
      }

      if (!this._useTextureArray) {
        this._frameUVs = this._calculateFrameUVs(this._metadata!);
      }
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
    const gl = this._gl;
    if (gl) {
      this._textures.forEach(tex => gl.deleteTexture(tex));
      if (this._textureArray) gl.deleteTexture(this._textureArray);
      if (this._streamingRingBuffer) gl.deleteTexture(this._streamingRingBuffer);
      if (!this._usingSharedGL) {
        if (this._program) gl.deleteProgram(this._program);
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
    this._render();
    if (this._autoPlay) {
      this._setState('playing');
      this._lastFrameTime = performance.now();
      this._startAnimationLoop();
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
        this._program = shared.program;
        this._arrayProgram = shared.arrayProgram;
        this._positionBuffer = shared.positionBuffer;
        this._texCoordBuffer = shared.texCoordBuffer;
        return shared.gl;
      }
    }

    // Fallback: dedicated context
    const dedicated = createDedicatedGL(this._canvas);
    if (!dedicated) {
      this._onError?.(new Error('WebGL not supported'));
      return null;
    }

    this._usingSharedGL = false;
    this._gl = dedicated.gl;
    this._program = dedicated.program;
    this._arrayProgram = dedicated.arrayProgram;
    this._positionBuffer = dedicated.positionBuffer;
    this._texCoordBuffer = dedicated.texCoordBuffer;
    return dedicated.gl;
  }

  // ======== Private: Basis Transcoder ========

  private async _initBasisTranscoder(): Promise<any> {
    if (basisModule) return basisModule;
    if (basisModulePromise) return basisModulePromise;

    basisModulePromise = (async () => {
      if (this._basisTranscoderInit) {
        basisModule = await this._basisTranscoderInit();
      } else {
        let attempts = 0;
        while (typeof (globalThis as any).BASIS === 'undefined' && attempts < 100) {
          await new Promise(r => setTimeout(r, 50));
          attempts++;
        }
        if (typeof (globalThis as any).BASIS === 'undefined') {
          throw new Error('Basis Universal not loaded');
        }
        basisModule = await (globalThis as any).BASIS();
        basisModule.initializeBasis();
      }
      return basisModule;
    })();
    return basisModulePromise;
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
    this._textures.forEach(tex => gl.deleteTexture(tex));
    this._textures = [];
    if (this._textureArray) { gl.deleteTexture(this._textureArray); this._textureArray = null; }
    this._useTextureArray = false;
  }

  private _cleanupStreamingState(gl: WebGLRenderingContext): void {
    if (this._streamingRingBuffer) { gl.deleteTexture(this._streamingRingBuffer); this._streamingRingBuffer = null; }
    this._streamingMode = false;
    this._streamingFrameCache.clear();
    this._streamingCompressedChunks = null;
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

  // ======== Private: Frame UVs ========

  private _calculateFrameUVs(metadata: Metadata): FrameUV[] {
    const uvs: FrameUV[] = [];
    const { frameCount, frameWidth, frameHeight, framesPerRow } = metadata;
    const framesPerPart = metadata.framesPerPart || metadata.framesPerStrip || frameCount;
    const parts = metadata.parts || metadata.strips || [];

    for (let i = 0; i < frameCount; i++) {
      const partIndex = Math.floor(i / framesPerPart);
      const frameInPart = i % framesPerPart;
      const col = frameInPart % framesPerRow;
      const row = Math.floor(frameInPart / framesPerRow);

      let partWidth: number, partHeight: number;
      if (parts[partIndex]) {
        partWidth = parts[partIndex].columns * frameWidth;
        partHeight = parts[partIndex].rows * frameHeight;
      } else {
        partWidth = framesPerRow * frameWidth;
        partHeight = Math.ceil(framesPerPart / framesPerRow) * frameHeight;
      }

      uvs.push({
        minU: (col * frameWidth) / partWidth,
        minV: (row * frameHeight) / partHeight,
        maxU: ((col + 1) * frameWidth) / partWidth,
        maxV: ((row + 1) * frameHeight) / partHeight,
      });
    }
    return uvs;
  }

  // ======== Private: Load Native ASTC KTX2 ========

  private async _loadNativeASTCKTX2(
    gl: WebGLRenderingContext, data: Uint8Array
  ): Promise<WebGLTexture | null> {
    const astcExt = gl.getExtension('WEBGL_compressed_texture_astc');
    if (!astcExt) throw new Error('ASTC not supported');

    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    const vkFormat = view.getUint32(12, true);
    const pixelWidth = view.getUint32(20, true);
    const pixelHeight = view.getUint32(24, true);
    const supercompressionScheme = view.getUint32(44, true);

    const glFormat = ASTC_FORMAT_MAP[vkFormat];
    if (!glFormat) throw new Error(`Unsupported ASTC format: VkFormat ${vkFormat}`);

    const levelIndexOffset = 80;
    const level0ByteOffset = Number(view.getBigUint64(levelIndexOffset, true));
    const level0ByteLength = Number(view.getBigUint64(levelIndexOffset + 8, true));

    let textureData: Uint8Array;
    if (supercompressionScheme === 2) {
      const compressedData = new Uint8Array(data.buffer, data.byteOffset + level0ByteOffset, level0ByteLength);
      const level0UncompressedLength = Number(view.getBigUint64(levelIndexOffset + 16, true));
      const decoder = await this._ensureZstdDecoder();
      textureData = decoder.decode(compressedData, level0UncompressedLength);
      this._log(`[AlphaVideoGpu] WASM zstd decompressed ${level0ByteLength} -> ${textureData.length} bytes`);
    } else if (supercompressionScheme === 0) {
      textureData = new Uint8Array(data.buffer, data.byteOffset + level0ByteOffset, level0ByteLength);
    } else {
      throw new Error(`Unsupported supercompression: ${supercompressionScheme}`);
    }

    const texture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.compressedTexImage2D(gl.TEXTURE_2D, 0, glFormat, pixelWidth, pixelHeight, 0, textureData);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    return texture;
  }

  // ======== Private: Transcode UASTC KTX2 ========

  private async _transcodeKTX2(
    gl: WebGLRenderingContext, BasisModule: any, data: Uint8Array, targetFormat: number
  ): Promise<WebGLTexture | null> {
    const ktx2File = new BasisModule.KTX2File(data);
    if (!ktx2File.isValid()) { ktx2File.close(); ktx2File.delete(); throw new Error('Invalid KTX2 file'); }
    if (!ktx2File.startTranscoding()) { ktx2File.close(); ktx2File.delete(); throw new Error('Failed to start transcoding'); }

    const width = ktx2File.getWidth();
    const height = ktx2File.getHeight();
    const levels = ktx2File.getLevels();
    const hasAlpha = ktx2File.getHasAlpha();

    const texture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, texture);

    for (let level = 0; level < levels; level++) {
      const dstSize = ktx2File.getImageTranscodedSizeInBytes(level, 0, 0, targetFormat);
      const dst = new Uint8Array(dstSize);
      if (!ktx2File.transcodeImage(dst, level, 0, 0, targetFormat, 0, -1, -1)) {
        ktx2File.close(); ktx2File.delete();
        throw new Error(`Failed to transcode level ${level}`);
      }
      const lw = Math.max(1, width >> level);
      const lh = Math.max(1, height >> level);
      if (targetFormat === BasisModule.cTFRGBA32) {
        gl.texImage2D(gl.TEXTURE_2D, level, gl.RGBA, lw, lh, 0, gl.RGBA, gl.UNSIGNED_BYTE, dst);
      } else {
        const glFmt = getGLCompressedFormat(gl, targetFormat, hasAlpha);
        gl.compressedTexImage2D(gl.TEXTURE_2D, level, glFmt, lw, lh, 0, dst);
      }
    }

    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, levels > 1 ? gl.LINEAR_MIPMAP_LINEAR : gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    ktx2File.close(); ktx2File.delete();
    return texture;
  }

  // ======== Private: Load GLB Source ========

  private async _loadGLBSource(
    gl: WebGLRenderingContext, data: Uint8Array, BasisModule: any, targetFormat: number
  ): Promise<void> {
    this._log('[AlphaVideoGpu] Detected GLB container');
    const glbResult = parseGLBContainer(data);
    if (!glbResult) throw new Error('Failed to parse GLB container');

    const { metadata, ktx2Parts } = glbResult;
    this._metadata = metadata;
    this._emitLoad(metadata);

    const isNativeASTC = metadata.mode === 'astc';
    const isTextureArray = metadata.packingMode === 'array';

    this._log(`[AlphaVideoGpu] Metadata: ${metadata.frameCount} frames, ${metadata.frameWidth}x${metadata.frameHeight}, mode=${metadata.mode}, packing=${metadata.packingMode || 'atlas'}`);

    if (isTextureArray && gl instanceof WebGL2RenderingContext && this._arrayProgram) {
      this._log('[AlphaVideoGpu] Using texture array mode');
      const success = await this._loadTextureArray(gl, ktx2Parts[0], metadata);
      if (!success) throw new Error('Failed to load texture array');
      this._textures.push(gl.createTexture()!);
    } else {
      for (const partData of ktx2Parts) {
        const tex = isNativeASTC
          ? await this._loadNativeASTCKTX2(gl, partData)
          : await this._transcodeKTX2(gl, BasisModule, partData, targetFormat);
        if (tex) this._textures.push(tex);
      }
    }
  }

  // ======== Private: Load KTX2 Source ========

  private async _loadKTX2Source(
    gl: WebGLRenderingContext, data: Uint8Array, BasisModule: any, targetFormat: number,
    uri: string, partUris?: string[]
  ): Promise<void> {
    const metadata = extractKTX2Metadata(data);
    if (!metadata) throw new Error('No metadata found in KTX2 file. Ensure the file was encoded with metadata injection.');

    this._log('[AlphaVideoGpu] Using embedded KTX2 metadata');
    this._log(`[AlphaVideoGpu] Metadata: ${metadata.frameCount} frames, ${metadata.frameWidth}x${metadata.frameHeight}, mode=${metadata.mode}, packing=${metadata.packingMode || 'atlas'}`);
    this._metadata = metadata;
    this._emitLoad(metadata);

    const partCount = metadata.partCount || metadata.stripCount || 1;
    const isNativeASTC = metadata.mode === 'astc';
    const isTextureArray = metadata.packingMode === 'array';

    if (isTextureArray && gl instanceof WebGL2RenderingContext && this._arrayProgram) {
      this._log('[AlphaVideoGpu] Using texture array mode');
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
      this._textures.push(gl.createTexture()!);
    } else {
      const useExplicitParts = partUris && partUris.length > 1;
      const actualPartCount = useExplicitParts ? partUris!.length : partCount;

      if (actualPartCount > 1) {
        const metadataParts = metadata.parts || metadata.strips || [];
        const basePath = uri.replace(/(_\d+)?\.(ktx2|basis)$/i, '');
        for (let i = 0; i < actualPartCount; i++) {
          let partUrl: string;
          if (useExplicitParts && partUris![i]) {
            partUrl = partUris![i];
          } else {
            const partFile = metadataParts[i]?.file || `${basePath}_${i}.ktx2`;
            partUrl = partFile.startsWith('http') ? partFile : uri.substring(0, uri.lastIndexOf('/') + 1) + partFile;
          }
          const partResponse = await fetch(partUrl);
          const partData = new Uint8Array(await partResponse.arrayBuffer());
          const tex = isNativeASTC
            ? await this._loadNativeASTCKTX2(gl, partData)
            : await this._transcodeKTX2(gl, BasisModule, partData, targetFormat);
          if (tex) this._textures.push(tex);
        }
      } else {
        const tex = isNativeASTC
          ? await this._loadNativeASTCKTX2(gl, data)
          : await this._transcodeKTX2(gl, BasisModule, data, targetFormat);
        if (tex) this._textures.push(tex);
      }
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

      const astcExt = gl.getExtension('WEBGL_compressed_texture_astc');
      if (!astcExt) throw new Error('ASTC texture compression not supported. Texture arrays require ASTC support.');
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
      this._useTextureArray = true;

      const uvs: FrameUV[] = [];
      for (let i = 0; i < metadata.frameCount; i++) {
        uvs.push({ minU: 0, minV: 0, maxU: 1, maxV: 1, layer: i });
      }
      this._frameUVs = uvs;

      this._log(`[AlphaVideoGpu] ✓ Texture array loaded: ${width}x${height}, ${layerCount} layers`);
      return true;
    } catch (error) {
      console.error('[AlphaVideoGpu] Failed to load texture array:', error);
      return false;
    }
  }

  // ======== Private: Streaming Ring Buffer Init ========

  private _initStreamingRingBuffer(
    gl: WebGL2RenderingContext, width: number, height: number,
    astcFormat: number, layerCount: number, vkFormat?: number
  ): void {
    const useVram = this._memoryStrategy === 'vram';
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

      const astcExt = gl.getExtension('WEBGL_compressed_texture_astc');
      if (!astcExt) throw new Error('ASTC texture compression not supported');
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

      for (let i = 0; i < actualRingSize; i++) {
        const frameOffset = level0Offset + (i * bytesPerLayer);
        const frameData = new Uint8Array(fullDataRef.buffer, fullDataRef.byteOffset + frameOffset, bytesPerLayer);
        gl.bindTexture(gl.TEXTURE_2D_ARRAY, this._streamingRingBuffer);
        gl.compressedTexSubImage3D(gl.TEXTURE_2D_ARRAY, 0, 0, 0, i % actualRingSize, width, height, 1, astcFormat, frameData);
        this._streamingRingSlots[i % actualRingSize] = i;
        this._streamingFrameCache.set(i, frameData.slice());
      }

      // Proxy cache for on-demand frame loading
      const originalCache = this._streamingFrameCache;
      const loadFrameOnDemand = (frameIndex: number): Uint8Array | null => {
        const frameOffset = level0Offset + (frameIndex * bytesPerLayer);
        if (frameOffset + bytesPerLayer > fullDataRef.length) return null;
        return new Uint8Array(fullDataRef.buffer, fullDataRef.byteOffset + frameOffset, bytesPerLayer);
      };

      this._streamingFrameCache = new Map();
      const proxyCache = {
        get: (frameIndex: number): Uint8Array | undefined => {
          if (originalCache.has(frameIndex)) return originalCache.get(frameIndex);
          const frameData = loadFrameOnDemand(frameIndex);
          if (frameData) { originalCache.set(frameIndex, frameData.slice()); return originalCache.get(frameIndex); }
          return undefined;
        },
        has: (frameIndex: number): boolean => frameIndex >= 0 && frameIndex < layerCount,
        set: (frameIndex: number, data: Uint8Array) => { originalCache.set(frameIndex, data); },
        delete: (frameIndex: number) => { originalCache.delete(frameIndex); },
        clear: () => { originalCache.clear(); },
        get size() { return originalCache.size; },
        keys: () => originalCache.keys(),
      };
      this._streamingFrameCache = proxyCache as any;

      const uvs: FrameUV[] = [];
      for (let i = 0; i < metadata.frameCount; i++) uvs.push({ minU: 0, minV: 0, maxU: 1, maxV: 1, layer: i });
      this._frameUVs = uvs;

      this._textureArray = this._streamingRingBuffer;
      this._useTextureArray = true;

      this._log(`[AlphaVideoGpu] ✓ Uncompressed streaming ready: ${metadata.frameCount} frames @ ${metadata.frameRate} fps`);
      return true;
    } catch (error) {
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

      const astcExt = gl.getExtension('WEBGL_compressed_texture_astc');
      if (!astcExt) throw new Error('ASTC texture compression not supported');
      const astcFormat = ASTC_FORMAT_MAP[vkFormat];
      if (!astcFormat) throw new Error(`Unsupported ASTC VkFormat: ${vkFormat}`);

      const decoder = await this._ensureZstdDecoder();
      const compressedData = new Uint8Array(ktx2Data.buffer, ktx2Data.byteOffset + level0Offset, level0Length);

      this._initStreamingRingBuffer(gl, width, height, astcFormat, layerCount, vkFormat);

      this._streamingMode = true;
      this._streamingIsInternalZstd = true;
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

      const uvs: FrameUV[] = [];
      for (let i = 0; i < metadata.frameCount; i++) uvs.push({ minU: 0, minV: 0, maxU: 1, maxV: 1, layer: i });
      this._frameUVs = uvs;

      this._textureArray = this._streamingRingBuffer;
      this._useTextureArray = true;

      this._startBackgroundDecompression();

      this._log(`[AlphaVideoGpu] ✓ Internal zstd streaming ready: ${metadata.frameCount} frames @ ${metadata.frameRate} fps`);
      return true;
    } catch (error) {
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
    const decompressLoop = async () => {
      const layerCount = this._streamingLayerCount;
      while (!this._streamingCancelled && this._streamingDecodePos < layerCount) {
        const framesAhead = this._streamingDecodePos - this._currentFrame;
        if (framesAhead < this._streamingPrefetchAhead) {
          const frameData = await this._decodeNextFrame();
          if (frameData) {
            const frameIndex = this._streamingDecodePos;
            this._streamingFrameCache.set(frameIndex, frameData);
            this._streamingDecodePos++;
            this._streamingFramesReady = this._streamingDecodePos;
          } else {
            break;
          }
        } else {
          await new Promise(resolve => setTimeout(resolve, 16));
        }
      }
    };
    decompressLoop();
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

  // ======== Private: Continue Streaming Decompression (outer zstd) ========

  private _continueStreamingDecompression(): void {
    if (this._streamingCancelled) return;

    const state = this._streamingDecompressState;
    if (!state || state.reachedEnd) return;

    const currentFrame = this._currentFrame;
    const decodePos = this._streamingDecodePos;
    const frameCache = this._streamingFrameCache;
    const bytesPerLayer = this._streamingBytesPerLayer;

    if (decodePos > currentFrame + this._streamingPrefetchAhead) {
      if (!this._streamingDecompressPaused) this._streamingDecompressPaused = true;
      this._evictOldFramesFromCache(currentFrame);
      if (!this._streamingCancelled) {
        requestAnimationFrame(() => this._continueStreamingDecompression());
      }
      return;
    }

    this._streamingDecompressPaused = false;
    this._evictOldFramesFromCache(currentFrame);

    const startTime = performance.now();
    const maxTimePerBatch = 8;
    const maxCacheSize = this._useVramStrategy ? 5 : this._streamingCacheSize;

    while (performance.now() - startTime < maxTimePerBatch) {
      if (frameCache.size >= maxCacheSize) break;
      if (state.frameAccumulatorLength > bytesPerLayer * 2) break;

      const result = state.generator.next();
      if (result.done) {
        state.reachedEnd = true;
        this._log(`[AlphaVideoGpu] ✓ Streaming decompression complete: ${decodePos} frames decoded`);
        break;
      }

      const chunk = result.value;

      // Skip header bytes on loop restart
      if (state.bytesToSkipForHeader > 0) {
        const toSkip = Math.min(chunk.length, state.bytesToSkipForHeader);
        state.bytesToSkipForHeader -= toSkip;
        if (toSkip < chunk.length) {
          const remaining = chunk.subarray(toSkip);
          if (state.frameAccumulator && state.frameAccumulatorLength + remaining.length <= state.frameAccumulator.length) {
            state.frameAccumulator.set(remaining, state.frameAccumulatorLength);
            state.frameAccumulatorLength += remaining.length;
          }
        }
        continue;
      }

      // Parse header first (only on first pass)
      if (!state.headerParsed) {
        const spaceLeft = state.headerBuffer.length - state.headerBufferLength;
        const toCopy = Math.min(chunk.length, spaceLeft);
        state.headerBuffer.set(chunk.subarray(0, toCopy), state.headerBufferLength);
        state.headerBufferLength += toCopy;

        if (state.headerBufferLength >= 200) {
          const view = new DataView(state.headerBuffer.buffer);
          if (state.headerBuffer[0] === 0xAB && state.headerBuffer[1] === 0x4B) {
            state.headerParsed = true;
            state.level0Offset = Number(view.getBigUint64(80, true));
            this._streamingLevel0Offset = state.level0Offset;
            state.frameAccumulator = new Uint8Array(bytesPerLayer * 2);
            state.frameAccumulatorLength = 0;
            const headerRemaining = state.headerBufferLength - state.level0Offset;
            if (headerRemaining > 0) {
              state.frameAccumulator.set(
                state.headerBuffer.subarray(state.level0Offset, state.headerBufferLength), 0
              );
              state.frameAccumulatorLength = headerRemaining;
            }
            if (toCopy < chunk.length) {
              const remaining = chunk.subarray(toCopy);
              state.frameAccumulator.set(remaining, state.frameAccumulatorLength);
              state.frameAccumulatorLength += remaining.length;
            }
          }
        }
        continue;
      }

      // Accumulate frame data
      const maxAccumulatorSize = bytesPerLayer * 4;
      if (state.frameAccumulatorLength + chunk.length > state.frameAccumulator.length) {
        const newSize = Math.min(state.frameAccumulator.length * 2, maxAccumulatorSize);
        if (newSize > state.frameAccumulator.length) {
          const newAccum = new Uint8Array(newSize);
          newAccum.set(state.frameAccumulator.subarray(0, state.frameAccumulatorLength));
          state.frameAccumulator = newAccum;
        }
      }
      if (state.frameAccumulatorLength + chunk.length <= state.frameAccumulator.length) {
        state.frameAccumulator.set(chunk, state.frameAccumulatorLength);
        state.frameAccumulatorLength += chunk.length;
      }

      // Extract complete frames
      while (state.frameAccumulatorLength >= bytesPerLayer) {
        if (frameCache.size >= maxCacheSize) break;
        const frameData = state.frameAccumulator.slice(0, bytesPerLayer);
        frameCache.set(this._streamingDecodePos, frameData);
        this._streamingDecodePos++;
        this._streamingFramesReady = this._streamingDecodePos;
        const remaining = state.frameAccumulatorLength - bytesPerLayer;
        if (remaining > 0) {
          state.frameAccumulator.copyWithin(0, bytesPerLayer, state.frameAccumulatorLength);
        }
        state.frameAccumulatorLength = remaining;
      }
    }

    if (!state.reachedEnd && !this._streamingCancelled) {
      const cacheIsFull = frameCache.size >= maxCacheSize;
      if (!cacheIsFull) {
        requestAnimationFrame(() => this._continueStreamingDecompression());
      } else {
        this._streamingDecompressPaused = true;
      }
    }
  }

  // ======== Private: Handle Streaming Loop ========

  private _handleStreamingLoop(): void {
    this._streamingLoopCount++;
    this._log(`[AlphaVideoGpu] 🔄 Loop #${this._streamingLoopCount} - restarting decompression`);

    this._streamingFrameCache.clear();
    this._streamingDecodePos = 0;
    this._streamingFramesReady = 0;
    this._streamingDecompressPaused = false;

    const state = this._streamingDecompressState;

    // Drain old generator to free WASM resources
    if (state?.generator && !state.reachedEnd) {
      try {
        let result = state.generator.next();
        while (!result.done) result = state.generator.next();
      } catch { /* ignore */ }
    }

    const compressedChunks = this._streamingCompressedChunks;
    if (compressedChunks && compressedChunks.length > 0 && this._zstdDecoder?.decodeStreaming) {
      const generator = this._zstdDecoder.decodeStreaming(compressedChunks);

      if (state) {
        state.generator = generator;
        if (this._streamingIsInternalZstd) {
          state.frameAccumulator = new Uint8Array(0);
          state.totalBytesDecoded = 0;
          state.totalFramesDecoded = 0;
        } else {
          state.reachedEnd = false;
          state.frameAccumulatorLength = 0;
          state.bytesToSkipForHeader = this._streamingLevel0Offset;
          const bytesPerLayer = this._streamingBytesPerLayer;
          if (bytesPerLayer > 0) {
            state.frameAccumulator = new Uint8Array(bytesPerLayer * 2);
          }
        }
      }

      if (this._streamingIsInternalZstd) {
        this._startBackgroundDecompression();
      } else {
        this._continueStreamingDecompression();
      }
    }
  }

  // ======== Private: Load Streaming Texture Array (.ktx2.zst) ========

  private async _loadStreamingTextureArray(
    gl: WebGL2RenderingContext, uri: string,
    onProgress?: (loaded: number, total: number, layersReady: number) => void
  ): Promise<boolean> {
    try {
      this._log('[AlphaVideoGpu] Starting streaming load:', uri);

      const decoder = await this._ensureZstdDecoder();
      if (typeof decoder.decodeStreaming !== 'function') {
        throw new Error('decodeStreaming not available. Include zstddec-stream.js');
      }

      const response = await fetch(uri);
      if (!response.ok) throw new Error(`Failed to fetch: ${response.status}`);

      const contentLength = parseInt(response.headers.get('content-length') || '0');
      const reader = response.body?.getReader();
      if (!reader) throw new Error('ReadableStream not supported');

      const compressedChunks: Uint8Array[] = [];
      let receivedLength = 0;
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        compressedChunks.push(value);
        receivedLength += value.length;
        onProgress?.(receivedLength, contentLength, 0);
      }

      this._log(`[AlphaVideoGpu] Downloaded ${receivedLength} bytes, parsing header...`);
      this._streamingCompressedChunks = compressedChunks;

      // Parse header from first decompressed chunk
      const generator = decoder.decodeStreaming(compressedChunks);
      let headerBuffer = new Uint8Array(256);
      let headerBufferLength = 0;
      let headerParsed = false;
      let width = 0, height = 0, layerCount = 0, vkFormat = 0, level0Offset = 0;

      for (const chunk of generator) {
        if (headerBufferLength + chunk.length > headerBuffer.length) {
          const newSize = Math.max(headerBuffer.length * 2, headerBufferLength + chunk.length);
          const newBuf = new Uint8Array(newSize);
          newBuf.set(headerBuffer.subarray(0, headerBufferLength));
          headerBuffer = newBuf;
        }
        headerBuffer.set(chunk, headerBufferLength);
        headerBufferLength += chunk.length;

        if (headerBufferLength >= 200 && !headerParsed) {
          const view = new DataView(headerBuffer.buffer);
          if (headerBuffer[0] === 0xAB && headerBuffer[1] === 0x4B) {
            vkFormat = view.getUint32(12, true);
            width = view.getUint32(20, true);
            height = view.getUint32(24, true);
            layerCount = view.getUint32(32, true);
            level0Offset = Number(view.getBigUint64(80, true));
            headerParsed = true;
            break;
          }
        }
      }

      if (!headerParsed) throw new Error('Failed to parse KTX2 header from stream');

      this._log(`[AlphaVideoGpu] Streaming texture: ${width}x${height}, ${layerCount} layers`);

      const metadata = extractKTX2Metadata(headerBuffer.subarray(0, Math.min(headerBufferLength, level0Offset + 100)));
      if (!metadata) throw new Error('No metadata found in streaming KTX2');
      this._metadata = metadata;
      this._emitLoad(metadata);

      this._streamingLayerCount = layerCount;
      this._streamingLevel0Offset = level0Offset;

      const astcExt = gl.getExtension('WEBGL_compressed_texture_astc');
      if (!astcExt) throw new Error('ASTC texture compression not supported');
      const astcFormat = ASTC_FORMAT_MAP[vkFormat];
      if (!astcFormat) throw new Error(`Unsupported ASTC VkFormat: ${vkFormat}`);

      this._initStreamingRingBuffer(gl, width, height, astcFormat, layerCount, vkFormat);

      this._streamingMode = true;
      this._streamingIsInternalZstd = false;
      this._streamingFrameCache = new Map();
      this._streamingDecodePos = 0;
      this._streamingFramesReady = 0;

      const uvs: FrameUV[] = [];
      for (let i = 0; i < metadata.frameCount; i++) uvs.push({ minU: 0, minV: 0, maxU: 1, maxV: 1, layer: i });
      this._frameUVs = uvs;

      this._textureArray = this._streamingRingBuffer;
      this._useTextureArray = true;
      this._textures.push(gl.createTexture()!);

      const bytesPerLayer = getASTCBytesPerLayer(width, height, vkFormat);

      const newGenerator = decoder.decodeStreaming(compressedChunks);
      this._streamingDecompressState = {
        generator: newGenerator,
        reachedEnd: false,
        headerBuffer: new Uint8Array(256),
        headerBufferLength: 0,
        headerParsed: true,
        level0Offset: level0Offset,
        frameAccumulator: new Uint8Array(bytesPerLayer * 2),
        frameAccumulatorLength: 0,
        bytesToSkipForHeader: level0Offset,
      };

      this._continueStreamingDecompression();

      this._log(`[AlphaVideoGpu] ✓ Streaming initialized: ${width}x${height}, ring=${this._streamingRingActualSize}, cache=${this._streamingCacheSize}`);
      return true;
    } catch (error) {
      console.error('[AlphaVideoGpu] Streaming load failed:', error);
      return false;
    }
  }

  // ======== Private: Render Frame ========

  private _render(): void {
    const gl = this._gl;
    const program = this._program;
    const frameUVs = this._frameUVs;
    const textures = this._textures;
    const metadata = this._metadata;

    if (!gl || !frameUVs.length || !metadata) return;

    const currentFrame = this._currentFrame;
    const frameUV = frameUVs[currentFrame];
    if (!frameUV) return;

    // If using shared GL context, ensure shared canvas is large enough and set viewport
    if (this._usingSharedGL && getSharedGLResources()) {
      const frameWidth = metadata.frameWidth;
      const frameHeight = metadata.frameHeight;
      ensureSharedCanvasSize(frameWidth, frameHeight);
      gl.viewport(0, 0, frameWidth, frameHeight);
    }

    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);

    // Texture array rendering (WebGL2)
    if (this._useTextureArray && this._arrayProgram && this._textureArray) {
      const arrayProgram = this._arrayProgram;
      const gl2 = gl as WebGL2RenderingContext;

      let layerIndex: number;

      if (this._streamingMode) {
        // Check if frame is ready
        if (currentFrame >= this._streamingFramesReady) {
          if (this._streamingDecompressPaused) {
            this._streamingDecompressPaused = false;
            this._continueStreamingDecompression();
          }
          return;
        }

        const slot = this._ensureFrameInRingBuffer(currentFrame);
        if (slot === -1) {
          if (this._streamingDecompressPaused) {
            this._streamingDecompressPaused = false;
            this._continueStreamingDecompression();
          }
          return;
        }
        layerIndex = slot;

        // Prefetch next frames
        for (let i = 1; i <= 3; i++) {
          const nextFrame = currentFrame + i;
          if (nextFrame < metadata.frameCount) {
            this._ensureFrameInRingBuffer(nextFrame);
          }
        }

        // Resume decompression if paused and cache has room
        const maxCacheSize = this._useVramStrategy ? 5 : this._streamingCacheSize;
        if (this._streamingDecompressPaused && this._streamingFrameCache.size < maxCacheSize) {
          this._streamingDecompressPaused = false;
          this._continueStreamingDecompression();
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
        layerIndex = frameUV.layer !== undefined ? frameUV.layer : currentFrame;
      }

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
    } else {
      // Standard 2D texture rendering (atlas mode)
      if (!program || !textures.length) return;

      const framesPerPart = metadata.framesPerPart || metadata.framesPerStrip || metadata.frameCount;
      const partIndex = Math.floor(currentFrame / framesPerPart);
      const texture = textures[partIndex] || textures[0];

      gl.useProgram(program);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.uniform1i(gl.getUniformLocation(program, 'u_texture'), 0);
      gl.uniform4f(gl.getUniformLocation(program, 'u_frameUV'), frameUV.minU, frameUV.minV, frameUV.maxU, frameUV.maxV);

      const posLoc = gl.getAttribLocation(program, 'a_position');
      gl.bindBuffer(gl.ARRAY_BUFFER, this._positionBuffer);
      gl.enableVertexAttribArray(posLoc);
      gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 0, 0);

      const texLoc = gl.getAttribLocation(program, 'a_texCoord');
      gl.bindBuffer(gl.ARRAY_BUFFER, this._texCoordBuffer);
      gl.enableVertexAttribArray(texLoc);
      gl.vertexAttribPointer(texLoc, 2, gl.FLOAT, false, 0, 0);

      gl.drawArrays(gl.TRIANGLES, 0, 6);
    }

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
  }

  // ======== Private: Animation Loop ========

  private _startAnimationLoop(): void {
    if (this._animationFrameId) return;

    const metadata = this._metadata;
    if (!metadata) return;

    const frameInterval = 1000 / metadata.frameRate;

    const animate = (timestamp: number) => {
      if (this._state !== 'playing') return;

      const elapsed = timestamp - this._lastFrameTime;

      if (elapsed >= frameInterval / this._playbackSpeed) {
        this._lastFrameTime = timestamp - (elapsed % (frameInterval / this._playbackSpeed));

        this._render();
        this._onFrameUpdate?.(this._currentFrame, metadata.frameCount);

        this._currentFrame++;

        if (this._currentFrame >= metadata.frameCount) {
          if (this._loop) {
            this._currentFrame = 0;
            if (this._streamingMode && this._streamingFrameCache) {
              this._handleStreamingLoop();
            }
          } else {
            this._setState('stopped');
            this._onFinished?.();
            return;
          }
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
