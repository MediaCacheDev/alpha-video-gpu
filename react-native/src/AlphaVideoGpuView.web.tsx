/**
 * AlphaVideoGpuView - React Native Web Implementation
 *
 * GPU-accelerated transparent alpha video player.
 * Thin wrapper around AlphaVideoGpuCore (alpha-video-gpu-web) for React Native Web.
 * Same API as native iOS/Android implementations.
 *
 * The Basis Universal and zstd decoders (JS + WASM) come bundled in alpha-video-gpu-web.
 */

import React, { forwardRef, useImperativeHandle, useRef, useEffect, useMemo } from 'react';
import { View, StyleSheet } from 'react-native';

import {
  AlphaVideoGpuCore,
  initBasisTranscoder,
  ZSTDDecoder,
  type AlphaVideoGpuError,
  type AlphaVideoGpuErrorCode,
  type AlphaVideoGpuLoadEvent,
} from 'alpha-video-gpu-web';

export type { AlphaVideoGpuErrorCode, AlphaVideoGpuLoadEvent, PlayerState } from 'alpha-video-gpu-web';

/**
 * Where to load from (on web, `asset://` and http(s) URLs apply):
 * - `https://…` / `http://…`: downloaded once into the app cache (named by `cacheKey`, else a hash of the URL)
 * - `file:///abs/path` or `/abs/path`: read in place
 * - `asset://relative/path`: Android APK `assets/`, the iOS app bundle, or the site root on web
 * - Android only: `content://…`, or a res/raw name (what `require()` resolves to in a release build)
 */
export interface SourceType {
  uri?: string;
  /** Stable identity for the cache, for URLs that change while the file doesn't (presigned URLs) */
  cacheKey?: string;
}

export interface AlphaVideoGpuViewProps {
  source?: SourceType | string | null;
  /** For multi-part animations: array of all part sources (first part contains metadata) */
  parts?: SourceType[] | null;
  autoPlay?: boolean;
  loop?: boolean;
  playbackSpeed?: number;
  /** Pause playback and decompression (useful for screen focus/blur) */
  paused?: boolean;
  cacheKey?: string;
  style?: any;
  // Events have the native shape, so the same handlers work on every platform
  /** Called when file metadata is loaded, provides dimensions and frame info */
  onLoad?: (event: { nativeEvent: AlphaVideoGpuLoadEvent }) => void;
  onStateChange?: (event: { nativeEvent: { state: string } }) => void;
  onFrameUpdate?: (event: { nativeEvent: { frame: number; totalFrames: number } }) => void;
  onFinished?: (event: { nativeEvent: {} }) => void;
  /** `code` is set for the errors a page may want to handle, such as 'ASTC_UNSUPPORTED' */
  onError?: (event: { nativeEvent: { error: string; code?: AlphaVideoGpuErrorCode } }) => void;
  /** Send onFrameUpdate for every frame played; seeks always send it. Default: false */
  enableFrameEvents?: boolean;

  // Streaming parameters (for .ktx2.zst files)
  /** RAM-mode streaming: GPU ring slots frames are uploaded into. Unused in VRAM mode. Default: 16 */
  streamingRingSize?: number;
  /** Frames kept decoded while streaming: in CPU memory (RAM mode) or as the GPU ring (VRAM mode). Default: 60 */
  streamingCacheSize?: number;
  /** Frames to decode ahead of playback. Default: 30 */
  streamingPrefetchAhead?: number;
  /** Memory strategy: 'auto' | 'vram' | 'ram'. Default: 'auto' */
  memoryStrategy?: 'auto' | 'vram' | 'ram';
  /** Enable streaming mode for texture array playback. Default: false */
  stream?: boolean;
  /** Log info traces to the console. Default: false */
  verbose?: boolean;
}

// ============================================================================
// AlphaVideoGpuView Component — Thin React Native Web wrapper
// ============================================================================

export interface AlphaVideoGpuViewRef {
  play: () => void;
  pause: () => void;
  stop: () => void;
  seekToFrame: (frame: number) => void;
  seekToTime: (seconds: number) => void;
}

export const AlphaVideoGpuView = forwardRef<AlphaVideoGpuViewRef, AlphaVideoGpuViewProps>(({
  source,
  parts,
  autoPlay = true,
  loop = true,
  playbackSpeed = 1.0,
  paused = false,
  cacheKey,
  style,
  onLoad,
  onStateChange,
  onFrameUpdate,
  onFinished,
  onError,
  streamingRingSize = 16,
  streamingCacheSize = 60,
  streamingPrefetchAhead = 30,
  memoryStrategy = 'auto',
  stream = false,
  enableFrameEvents = false,
  verbose = false,
}, ref) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const coreRef = useRef<AlphaVideoGpuCore | null>(null);
  const loadedSourceRef = useRef<string | null>(null);
  const callbacksRef = useRef({ onLoad, onStateChange, onFrameUpdate, onFinished, onError });
  callbacksRef.current = { onLoad, onStateChange, onFrameUpdate, onFinished, onError };

  useImperativeHandle(ref, () => ({
    play: () => coreRef.current?.play(),
    pause: () => coreRef.current?.pause(),
    stop: () => coreRef.current?.stop(),
    seekToFrame: (frame: number) => coreRef.current?.seekToFrame(frame),
    seekToTime: (seconds: number) => coreRef.current?.seekToTime(seconds),
  }), []);

  // Normalize source prop to { uri } format
  const normalizedSource = useMemo(() => {
    if (!source) return null;
    if (typeof source === 'string') return { uri: source };
    return source;
  }, [source]);

  // Create core instance when canvas is available
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || coreRef.current) return;

    coreRef.current = new AlphaVideoGpuCore(canvas, {
      autoPlay,
      loop,
      playbackSpeed,
      stream,
      streamingRingSize,
      streamingCacheSize,
      streamingPrefetchAhead,
      memoryStrategy,
      verbose,
      frameEvents: enableFrameEvents,
      basisTranscoderInit: initBasisTranscoder,
      ZSTDDecoderClass: ZSTDDecoder,
      onStateChange: state => callbacksRef.current.onStateChange?.({ nativeEvent: { state } }),
      onLoad: event => callbacksRef.current.onLoad?.({ nativeEvent: event }),
      onFrameUpdate: (frame, totalFrames) =>
        callbacksRef.current.onFrameUpdate?.({ nativeEvent: { frame, totalFrames } }),
      onFinished: () => callbacksRef.current.onFinished?.({ nativeEvent: {} }),
      onError: error => {
        const { code } = error as AlphaVideoGpuError;
        callbacksRef.current.onError?.({ nativeEvent: code ? { error: error.message, code } : { error: error.message } });
      },
    });

    return () => {
      coreRef.current?.destroy();
      coreRef.current = null;
      loadedSourceRef.current = null;
    };
    // Only create/destroy on mount/unmount
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // What identifies "the same video". A presigned URL is re-signed every time the app
  // lists its media, so the URL changes while still addressing the same object; keying
  // the load on it would resetSource() and refetch on each refresh, visibly restarting
  // a loop that should run until unmount. The cache key is the stable identity.
  const sourceCacheKey = normalizedSource?.cacheKey || cacheKey;
  const sourceIdentity = sourceCacheKey || normalizedSource?.uri || null;

  // Load source when it changes
  useEffect(() => {
    const core = coreRef.current;
    const uri = normalizedSource?.uri;
    if (!core || !uri || !sourceIdentity || loadedSourceRef.current === sourceIdentity) return;

    loadedSourceRef.current = sourceIdentity;
    const partUris = parts?.map(p => p.uri).filter(Boolean) as string[] | undefined;
    core.setSource(uri, partUris, sourceCacheKey);

    return () => {
      loadedSourceRef.current = null;
      core.resetSource();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sourceIdentity]);

  useEffect(() => {
    if (coreRef.current) {
      coreRef.current.verbose = verbose;
    }
  }, [verbose]);

  useEffect(() => {
    if (coreRef.current) {
      coreRef.current.frameEvents = enableFrameEvents;
    }
  }, [enableFrameEvents]);

  // Handle playback speed changes
  useEffect(() => {
    if (coreRef.current) {
      coreRef.current.playbackSpeed = playbackSpeed;
    }
  }, [playbackSpeed]);

  // Handle loop changes
  useEffect(() => {
    if (coreRef.current) {
      coreRef.current.loop = loop;
    }
  }, [loop]);

  // Handle paused prop changes
  useEffect(() => {
    coreRef.current?.setPaused(paused);
  }, [paused]);

  // Get canvas dimensions from style
  const canvasStyle = useMemo(() => {
    const flatStyle = StyleSheet.flatten(style) || {};
    return {
      width: (flatStyle as any).width || 300,
      height: (flatStyle as any).height || 300,
    };
  }, [style]);

  return (
    <View style={[styles.container, style]}>
      <canvas
        ref={canvasRef}
        width={canvasStyle.width}
        height={canvasStyle.height}
        style={{ width: '100%', height: '100%' }}
      />
    </View>
  );
});

const styles = StyleSheet.create({
  container: {
    overflow: 'hidden',
  },
});

AlphaVideoGpuView.displayName = 'AlphaVideoGpuView';

export default AlphaVideoGpuView;
