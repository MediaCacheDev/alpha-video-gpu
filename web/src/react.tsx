/**
 * AlphaVideoGpuPlayer - React-only Wrapper (no React Native dependency)
 *
 * GPU-accelerated transparent alpha video player.
 *
 * Usage:
 *   import { AlphaVideoGpuPlayer } from 'alpha-video-gpu-web/react';
 *
 *   <AlphaVideoGpuPlayer
 *     src="https://example.com/animation.ktx2"
 *     width={300}
 *     height={300}
 *     autoPlay
 *     loop
 *   />
 *
 * Self-contained: the ZSTD decoder (JS + WASM) is bundled inline.
 * No external files or <script> tags needed.
 */

import React, { forwardRef, useImperativeHandle, useRef, useEffect, useMemo } from 'react';
import { KTX2PlayerCore } from './KTX2PlayerCore';
import type { KTX2LoadEvent, PlayerState, MemoryStrategy, KTX2Source, KTX2Storage } from './types';
import { ZSTDDecoder } from './bundled-deps';

export type {
  KTX2LoadEvent as AlphaVideoGpuLoadEvent,
  KTX2Source as AlphaVideoGpuSource,
  KTX2Storage as AlphaVideoGpuStorage,
  PlayerState,
  MemoryStrategy,
};

export interface AlphaVideoGpuPlayerProps {
  /** The KTX2 file: a URL, a Blob or File, bytes in memory, or a ByteSource */
  src?: KTX2Source;
  /** Names the stored copy, for URLs that change while the file doesn't (presigned URLs) */
  cacheKey?: string;
  /** Sent with every request for a URL source, e.g. Authorization; a change doesn't reload */
  headers?: Record<string, string>;
  /** Where a URL's bytes are kept between plays. Default: 'auto' (OPFS, else IndexedDB). Read on mount */
  storage?: KTX2Storage;
  /** Cap on the stored bytes across clips. Default: 512 MB. Read on mount */
  maxStorageBytes?: number;
  /** Canvas width in pixels */
  width?: number;
  /** Canvas height in pixels */
  height?: number;
  /** CSS class for the canvas element */
  className?: string;
  /** Inline styles for the canvas element */
  style?: React.CSSProperties;

  autoPlay?: boolean;
  loop?: boolean;
  playbackSpeed?: number;
  paused?: boolean;
  stream?: boolean;

  // Streaming parameters
  streamingPrefetchAhead?: number;
  memoryStrategy?: MemoryStrategy;
  /** Log info traces to the console. Default: false */
  verbose?: boolean;

  // Callbacks
  onLoad?: (event: KTX2LoadEvent) => void;
  onStateChange?: (state: PlayerState) => void;
  onFrameUpdate?: (frame: number, total: number) => void;
  onFinished?: () => void;
  onError?: (error: Error) => void;
}

/**
 * Seeks show the frame (clamped to the clip) and keep playing, or stay paused. They are ignored
 * until the source has loaded.
 */
export interface AlphaVideoGpuPlayerRef {
  /** Plays from the shown frame, or from the start once a clip that doesn't loop has finished. */
  play: () => void;
  pause: () => void;
  /** Pauses on the first frame. */
  stop: () => void;
  seekToFrame: (frame: number) => void;
  /** Seeks to the frame showing at that time, in seconds. */
  seekToTime: (seconds: number) => void;
}

export const AlphaVideoGpuPlayer = forwardRef<AlphaVideoGpuPlayerRef, AlphaVideoGpuPlayerProps>(({
  src,
  cacheKey,
  headers,
  storage,
  maxStorageBytes,
  width = 300,
  height = 300,
  className,
  style,
  autoPlay = true,
  loop = true,
  playbackSpeed = 1.0,
  paused = false,
  stream = true,
  streamingPrefetchAhead,
  memoryStrategy,
  verbose = false,
  onLoad,
  onStateChange,
  onFrameUpdate,
  onFinished,
  onError,
}, ref) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const coreRef = useRef<KTX2PlayerCore | null>(null);
  const loadedSrcRef = useRef<unknown>(null);
  const headersRef = useRef(headers);
  headersRef.current = headers;

  useImperativeHandle(ref, () => ({
    play: () => coreRef.current?.play(),
    pause: () => coreRef.current?.pause(),
    stop: () => coreRef.current?.stop(),
    seekToFrame: (frame: number) => coreRef.current?.seekToFrame(frame),
    seekToTime: (seconds: number) => coreRef.current?.seekToTime(seconds),
  }), []);

  // Create core on mount
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || coreRef.current) return;

    coreRef.current = new KTX2PlayerCore(canvas, {
      autoPlay,
      loop,
      playbackSpeed,
      stream,
      streamingPrefetchAhead,
      memoryStrategy,
      verbose,
      storage,
      maxStorageBytes,
      ZSTDDecoderClass: ZSTDDecoder as any,
      onStateChange,
      onLoad,
      onFrameUpdate,
      onFinished,
      onError,
    });

    return () => {
      coreRef.current?.destroy();
      coreRef.current = null;
      loadedSrcRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Keep callbacks in sync
  useEffect(() => {
    coreRef.current?.updateCallbacks({ onStateChange, onLoad, onFrameUpdate, onFinished, onError });
  }, [onStateChange, onLoad, onFrameUpdate, onFinished, onError]);

  // Load source
  useEffect(() => {
    const core = coreRef.current;
    if (!core || !src) return;
    const identity = (typeof src === 'string' && cacheKey) || src;
    if (loadedSrcRef.current === identity) return;
    loadedSrcRef.current = identity;
    core.setSource(src, { cacheKey, headers: headersRef.current });
    return () => { loadedSrcRef.current = null; core.resetSource(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [(typeof src === 'string' && cacheKey) || src]);

  useEffect(() => { if (coreRef.current) coreRef.current.verbose = verbose; }, [verbose]);

  // Playback speed
  useEffect(() => { if (coreRef.current) coreRef.current.playbackSpeed = playbackSpeed; }, [playbackSpeed]);

  // Loop
  useEffect(() => { if (coreRef.current) coreRef.current.loop = loop; }, [loop]);

  // Paused
  useEffect(() => { coreRef.current?.setPaused(paused); }, [paused]);

  const canvasStyle = useMemo<React.CSSProperties>(() => ({
    display: 'block',
    ...style,
  }), [style]);

  return (
    <canvas
      ref={canvasRef}
      width={width}
      height={height}
      className={className}
      style={canvasStyle}
    />
  );
});

AlphaVideoGpuPlayer.displayName = 'AlphaVideoGpuPlayer';
