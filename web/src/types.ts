/**
 * KTX2 Player Core - Shared Types
 * 
 * Framework-agnostic type definitions used by the core engine
 * and all wrapper implementations (React Native Web, React, Vanilla JS).
 */

import type { ByteSource, ByteStore, StorageKind } from 'alpha-video-source';

/**
 * What a player plays: a URL (http(s), relative, or asset://path), a Blob or File, bytes in
 * memory, or a ByteSource that reads by offset (an app's own fetcher or cache).
 */
export type KTX2Source = string | Blob | ArrayBuffer | Uint8Array | ByteSource;

export interface KTX2SourceOptions {
  /**
   * Names the stored copy, for URLs that change while the file doesn't (presigned URLs).
   * Defaults to the URL. A Blob or ByteSource is stored only with storage set and a cacheKey.
   */
  cacheKey?: string;
  /** Sent with every request for a URL, e.g. Authorization. */
  headers?: Record<string, string>;
}

/**
 * Where streamed bytes are kept between plays: 'auto' (OPFS, else IndexedDB), 'opfs',
 * 'indexeddb', 'cache' (the Cache API), 'memory' (this page only), 'none', or your own store.
 */
export type KTX2Storage = StorageKind | ByteStore;

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
  /**
   * Where a URL's bytes are kept between plays, read back by range. Default: 'auto' for URLs,
   * 'none' for Blobs, memory and ByteSources. With 'none', a clip under 16 MB still stays in
   * memory for loops.
   */
  storage?: KTX2Storage;
  /** Cap on the bytes stored across every clip, evicting the least recently played. Default: 512 MB */
  maxStorageBytes?: number;

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
