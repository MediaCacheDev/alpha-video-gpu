/**
 * AlphaVideoGpuView - React Native Web Implementation
 *
 * GPU-accelerated transparent alpha video player.
 * Thin wrapper around AlphaVideoGpuCore (alpha-video-gpu-web) for React Native Web.
 * Same API as native iOS/Android implementations.
 *
 * The zstd decoder (JS + WASM) comes bundled in alpha-video-gpu-web.
 */

import React, { forwardRef, useImperativeHandle, useRef, useEffect, useMemo } from 'react';
import { View, StyleSheet } from 'react-native';

import {
  AlphaVideoGpuCore,
  ZSTDDecoder,
  type AlphaVideoGpuError,
  type AlphaVideoGpuErrorCode,
  type AlphaVideoGpuLoadEvent,
  type ByteSource,
} from 'alpha-video-gpu-web';

export type { AlphaVideoGpuErrorCode, AlphaVideoGpuLoadEvent, PlayerState, ByteSource } from 'alpha-video-gpu-web';

/**
 * Where to load from (on web, `asset://` and http(s) URLs apply):
 * - `https://…` / `http://…`: read by range as it plays, and kept as `storage` says (on web in the
 *   browser's storage, OPFS, else IndexedDB)
 * - `file:///abs/path` or `/abs/path`: read in place
 * - `asset://relative/path`: Android APK `assets/`, the iOS app bundle, or the site root on web
 * - Android only: `content://…`, or a res/raw name (what `require()` resolves to in a release build)
 *
 * On web, `source` can also be a Blob or File, or a ByteSource that reads by offset.
 */
export interface SourceType {
  uri?: string;
  /** What the store keeps the source under, for URLs that change while the file doesn't (presigned URLs) */
  cacheKey?: string;
  /** Sent with every request for the URL, e.g. Authorization; given to a `dataSource` too */
  headers?: Record<string, string>;
  /**
   * Read the file through the data source registered under this name: range-source's
   * `RangeDataSources` on Android and iOS, and range-source-web's on the web. MediaCache
   * registers as `'mediacache'` with `cache.registerRangeDataSource()`.
   */
  dataSource?: string;
}

type DataSourceFactory = (uri: string, init?: { headers?: Record<string, string> }) => ByteSource;

/** range-source-web's `RangeDataSources`, read from its global symbol so this package needn't depend on it. */
function registeredDataSource(name: string): DataSourceFactory | undefined {
  const scope = globalThis as { [key: symbol]: Map<string, DataSourceFactory> | undefined };
  return scope[Symbol.for('range-source.dataSources')]?.get(name);
}

export interface AlphaVideoGpuViewProps {
  source?: SourceType | string | Blob | ByteSource | null;
  autoPlay?: boolean;
  loop?: boolean;
  playbackSpeed?: number;
  /** Pause playback and decompression (useful for screen focus/blur) */
  paused?: boolean;
  cacheKey?: string;
  /** Where a URL's bytes are kept between plays; on web 'disk' is the browser's storage. Read on mount */
  storage?: 'auto' | 'disk' | 'memory' | 'none';
  /** The store's cap in bytes (default 512 MB). Read on mount */
  maxStorageBytes?: number;
  style?: any;
  testID?: string;
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

  // Streaming parameters (with stream)
  /** Frames decoded ahead of the shown one, all streaming keeps. Default: 8 */
  streamingPrefetchAhead?: number;
  /** Memory strategy: 'vram' | 'ram'. Default: 'vram' */
  memoryStrategy?: 'vram' | 'ram';
  /** Enable streaming mode for texture array playback. Default: true */
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
  autoPlay = true,
  loop = true,
  playbackSpeed = 1.0,
  paused = false,
  cacheKey,
  storage,
  maxStorageBytes,
  style,
  testID,
  onLoad,
  onStateChange,
  onFrameUpdate,
  onFinished,
  onError,
  streamingPrefetchAhead = 8,
  memoryStrategy = 'vram',
  stream = true,
  enableFrameEvents = false,
  verbose = false,
}, ref) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const coreRef = useRef<AlphaVideoGpuCore | null>(null);
  const loadedSourceRef = useRef<unknown>(null);
  const callbacksRef = useRef({ onLoad, onStateChange, onFrameUpdate, onFinished, onError });
  callbacksRef.current = { onLoad, onStateChange, onFrameUpdate, onFinished, onError };

  useImperativeHandle(ref, () => ({
    play: () => coreRef.current?.play(),
    pause: () => coreRef.current?.pause(),
    stop: () => coreRef.current?.stop(),
    seekToFrame: (frame: number) => coreRef.current?.seekToFrame(frame),
    seekToTime: (seconds: number) => coreRef.current?.seekToTime(seconds),
  }), []);

  // A Blob or ByteSource plays as it is; anything else is normalized to { uri } format
  const directSource = isDirectSource(source) ? source : null;
  const normalizedSource = useMemo((): SourceType | null => {
    if (!source || isDirectSource(source)) return null;
    if (typeof source === 'string') return { uri: source };
    return source;
  }, [source]);
  const headersRef = useRef(normalizedSource?.headers);
  headersRef.current = normalizedSource?.headers;

  // Create core instance when canvas is available
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || coreRef.current) return;

    coreRef.current = new AlphaVideoGpuCore(canvas, {
      autoPlay,
      loop,
      playbackSpeed,
      stream,
      streamingPrefetchAhead,
      memoryStrategy,
      verbose,
      frameEvents: enableFrameEvents,
      storage: storage === 'disk' ? 'auto' : storage,
      maxStorageBytes: maxStorageBytes && maxStorageBytes > 0 ? maxStorageBytes : undefined,
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
  const dataSource = normalizedSource?.dataSource;
  const keyed = sourceCacheKey || normalizedSource?.uri;
  const sourceIdentity = directSource || (keyed && dataSource ? `${dataSource}:${keyed}` : keyed) || null;

  // Load source when it changes
  useEffect(() => {
    const core = coreRef.current;
    const uri = normalizedSource?.uri;
    if (!core || !sourceIdentity || loadedSourceRef.current === sourceIdentity) return;

    if (dataSource && uri) {
      // The data source keeps the bytes; the player stores them too only when storage is set
      const factory = registeredDataSource(dataSource);
      if (!factory) {
        callbacksRef.current.onError?.({ nativeEvent: { error: `No data source is registered on the web under "${dataSource}"` } });
        return;
      }
      loadedSourceRef.current = sourceIdentity;
      const stored = storage === 'disk' || storage === 'memory';
      core.setSource(factory(uri, { headers: headersRef.current }), stored ? { cacheKey: keyed } : {});
      return () => {
        loadedSourceRef.current = null;
        core.resetSource();
      };
    }

    const toLoad = directSource || uri;
    if (!toLoad) return;
    loadedSourceRef.current = sourceIdentity;
    core.setSource(toLoad, { cacheKey: sourceCacheKey, headers: headersRef.current });

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
    <View testID={testID} style={[styles.container, style]}>
      <canvas
        ref={canvasRef}
        width={canvasStyle.width}
        height={canvasStyle.height}
        style={{ width: '100%', height: '100%' }}
      />
    </View>
  );
});

function isDirectSource(source: AlphaVideoGpuViewProps['source']): source is Blob | ByteSource {
  if (!source || typeof source !== 'object') return false;
  return (typeof Blob !== 'undefined' && source instanceof Blob) || typeof (source as ByteSource).read === 'function';
}

const styles = StyleSheet.create({
  container: {
    overflow: 'hidden',
  },
});

AlphaVideoGpuView.displayName = 'AlphaVideoGpuView';

export default AlphaVideoGpuView;
