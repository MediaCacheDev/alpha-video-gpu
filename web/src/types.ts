/**
 * KTX2 Player Core - Shared Types
 * 
 * Framework-agnostic type definitions used by the core engine
 * and all wrapper implementations (React Native Web, React, Vanilla JS).
 */

export interface Metadata {
  frameCount: number;
  frameWidth: number;
  frameHeight: number;
  frameRate: number;
  mode?: string; // 'astc'
  packingMode?: string; // 'array'
  layerCount?: number;
}

export interface KTX2LoadEvent {
  width: number;
  height: number;
  frameCount: number;
  frameRate: number;
  duration: number; // in seconds
}

export type PlayerState = 'idle' | 'loading' | 'ready' | 'playing' | 'paused' | 'stopped' | 'error';

export type MemoryStrategy = 'vram' | 'ram';

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
  /** Enable streaming mode for texture array playback (default: true) */
  stream?: boolean;

  // Streaming parameters
  /** RAM-mode streaming: GPU ring slots frames are uploaded into. Unused in VRAM mode. Default: 16 */
  streamingRingSize?: number;
  /** Frames kept decoded while streaming: in CPU memory (RAM mode) or as the GPU ring (VRAM mode). Default: 60 */
  streamingCacheSize?: number;
  /** Frames to decode ahead of playback. Default: 30 */
  streamingPrefetchAhead?: number;
  /** Memory strategy: 'vram' | 'ram'. Default: 'vram' */
  memoryStrategy?: MemoryStrategy;
  /** Log info traces to the console (warnings and errors always log). Default: false */
  verbose?: boolean;

  // Dependency injection — allows different bundling strategies
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
  arrayProgram: WebGLProgram | null;
  positionBuffer: WebGLBuffer;
  texCoordBuffer: WebGLBuffer;
  refCount: number;
  maxWidth: number;
  maxHeight: number;
}
