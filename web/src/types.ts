/**
 * KTX2 Player Core - Shared Types
 * 
 * Framework-agnostic type definitions used by the core engine
 * and all wrapper implementations (React Native Web, React, Vanilla JS).
 */

export interface FrameUV {
  minU: number;
  minV: number;
  maxU: number;
  maxV: number;
  layer?: number; // For texture array mode
}

export interface Metadata {
  frameCount: number;
  frameWidth: number;
  frameHeight: number;
  frameRate: number;
  framesPerRow: number;
  framesPerPart?: number;
  framesPerStrip?: number;
  partCount?: number;
  stripCount?: number;
  parts?: Array<{ file: string; columns: number; rows: number }>;
  strips?: Array<{ file: string; columns: number; rows: number }>;
  mode?: string;
  packingMode?: string; // 'atlas' or 'array'
  layerCount?: number;
}

export interface GLBParseResult {
  metadata: Metadata;
  ktx2Parts: Uint8Array[];
}

export interface KTX2LoadEvent {
  width: number;
  height: number;
  frameCount: number;
  frameRate: number;
  duration: number; // in seconds
}

export type PlayerState = 'idle' | 'loading' | 'ready' | 'playing' | 'paused' | 'stopped' | 'error';

export type MemoryStrategy = 'auto' | 'vram' | 'ram';

/**
 * Options for KTX2PlayerCore constructor.
 * Framework-agnostic — no React or React Native types.
 */
export interface KTX2PlayerCoreOptions {
  /** Whether to start playing automatically (default: true) */
  autoPlay?: boolean;
  /** Whether to loop the animation (default: true) */
  loop?: boolean;
  /** Playback speed multiplier (default: 1.0) */
  playbackSpeed?: number;
  /** Enable streaming mode for texture array playback (default: false) */
  stream?: boolean;

  // Streaming parameters
  /** GPU ring buffer slots (VRAM). Default: 16 */
  streamingRingSize?: number;
  /** CPU frame cache size (RAM). Default: 60 */
  streamingCacheSize?: number;
  /** Frames to decode ahead of playback. Default: 30 */
  streamingPrefetchAhead?: number;
  /** Memory strategy: 'auto' | 'vram' | 'ram'. Default: 'auto' */
  memoryStrategy?: MemoryStrategy;
  /** Log info traces to the console (warnings and errors always log). Default: false */
  verbose?: boolean;

  // Dependency injection — allows different bundling strategies
  /** Function that returns a Promise resolving to the Basis Universal module */
  basisTranscoderInit?: () => Promise<any>;
  /** ZSTDDecoder class constructor (for zstd decompression) */
  ZSTDDecoderClass?: new () => any;

  // Callbacks
  onStateChange?: (state: PlayerState) => void;
  onLoad?: (event: KTX2LoadEvent) => void;
  onFrameUpdate?: (frame: number, total: number) => void;
  onFinished?: () => void;
  onError?: (error: Error) => void;
  /** Call onFrameUpdate for every frame played; seeks always call it. Default: true */
  frameEvents?: boolean;
}

/**
 * Shared WebGL context resources — used to avoid browser's ~8-16 context limit.
 */
export interface SharedGLResources {
  gl: WebGL2RenderingContext | WebGLRenderingContext;
  canvas: HTMLCanvasElement;
  program: WebGLProgram;
  arrayProgram: WebGLProgram | null;
  positionBuffer: WebGLBuffer;
  texCoordBuffer: WebGLBuffer;
  refCount: number;
  maxWidth: number;
  maxHeight: number;
}

/**
 * Internal streaming decompression state.
 */
export interface StreamingDecompressState {
  generator: Generator<Uint8Array, void, unknown> | null;
  compressedChunks: Uint8Array[];
  headerBuffer: Uint8Array;
  headerBufferLength: number;
  headerParsed: boolean;
  ktx2Header: any;
  level0Offset: number;
  bytesPerLayer: number;
  gl: WebGL2RenderingContext | WebGLRenderingContext;
  frameAccumulator: Uint8Array | null;
  frameAccumulatorLength: number;
  lastLoggedFrame: number;
  bytesToSkipForHeader?: number;
  reachedEnd?: boolean;
}
