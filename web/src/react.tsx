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
import type { KTX2LoadEvent, PlayerState, MemoryStrategy } from './types';
import { ZSTDDecoder } from './bundled-deps';

export type { KTX2LoadEvent as AlphaVideoGpuLoadEvent, PlayerState, MemoryStrategy };

export interface AlphaVideoGpuPlayerProps {
  /** Source URL for the KTX2 file */
  src?: string;
  /** Stable identity for the IndexedDB cache, for URLs that change while the file doesn't */
  cacheKey?: string;
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
  streamingRingSize?: number;
  streamingCacheSize?: number;
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
  width = 300,
  height = 300,
  className,
  style,
  autoPlay = true,
  loop = true,
  playbackSpeed = 1.0,
  paused = false,
  stream = false,
  streamingRingSize,
  streamingCacheSize,
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
  const loadedSrcRef = useRef<string | null>(null);

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
      streamingRingSize,
      streamingCacheSize,
      streamingPrefetchAhead,
      memoryStrategy,
      verbose,
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
    const identity = cacheKey || src;
    if (loadedSrcRef.current === identity) return;
    loadedSrcRef.current = identity;
    core.setSource(src, cacheKey);
    return () => { loadedSrcRef.current = null; core.resetSource(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cacheKey || src]);

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
