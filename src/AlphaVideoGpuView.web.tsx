/**
 * AlphaVideoGpuView - React Native Web Implementation
 *
 * GPU-accelerated transparent alpha video player.
 * Thin wrapper around KTX2PlayerCore for React Native Web.
 * Same API as native iOS/Android implementations.
 *
 * All dependencies are bundled - no external scripts needed:
 * - Basis Universal: JS + WASM inlined in basis-transcoder.ts
 * - ZSTD decoder: JS + WASM inlined in zstddec-stream-bundled.js
 */

import React, { useRef, useEffect, useMemo } from 'react';
import { View, StyleSheet } from 'react-native';

// Import bundled dependencies
import { initBasisTranscoder as initBundledBasis } from './basis-transcoder';
import { ZSTDDecoder as ZSTDDecoderClass } from './zstddec-stream-bundled.js';

// Import core engine
import { KTX2PlayerCore } from './web/KTX2PlayerCore';
import type { KTX2LoadEvent } from './web/types';

// Re-export types from core for API compatibility
export type { KTX2LoadEvent as AlphaVideoGpuLoadEvent, PlayerState } from './web/types';
export type { KTX2LoadEvent } from './web/types'; // Legacy alias

export interface SourceType {
  uri?: string;
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
  /** Called when file metadata is loaded, provides dimensions and frame info */
  onLoad?: (event: KTX2LoadEvent) => void;
  onStateChange?: (state: string) => void;
  onFrameUpdate?: (frame: number, total: number) => void;
  onFinished?: () => void;
  onError?: (error: Error) => void;

  // Streaming parameters (for .ktx2.zst files)
  /** GPU ring buffer slots (VRAM). Default: 16 */
  streamingRingSize?: number;
  /** CPU frame cache size (RAM). Default: 60 */
  streamingCacheSize?: number;
  /** Frames to decode ahead of playback. Default: 30 */
  streamingPrefetchAhead?: number;
  /** Memory strategy: 'auto' | 'vram' | 'ram'. Default: 'auto' */
  memoryStrategy?: 'auto' | 'vram' | 'ram';
  /** Enable streaming mode for texture array playback. Default: false */
  stream?: boolean;
}

// ============================================================================
// AlphaVideoGpuView Component — Thin React Native Web wrapper
// ============================================================================

export const AlphaVideoGpuView: React.FC<AlphaVideoGpuViewProps> = ({
  source,
  parts,
  autoPlay = true,
  loop = true,
  playbackSpeed = 1.0,
  paused = false,
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
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const coreRef = useRef<KTX2PlayerCore | null>(null);
  const loadedSourceRef = useRef<string | null>(null);

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

    coreRef.current = new KTX2PlayerCore(canvas, {
      autoPlay,
      loop,
      playbackSpeed,
      stream,
      streamingRingSize,
      streamingCacheSize,
      streamingPrefetchAhead,
      memoryStrategy,
      basisTranscoderInit: initBundledBasis,
      ZSTDDecoderClass: ZSTDDecoderClass as any,
      onStateChange,
      onLoad,
      onFrameUpdate,
      onFinished,
      onError,
    });

    return () => {
      coreRef.current?.destroy();
      coreRef.current = null;
      loadedSourceRef.current = null;
    };
    // Only create/destroy on mount/unmount
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Keep callbacks in sync with latest props
  useEffect(() => {
    coreRef.current?.updateCallbacks({
      onStateChange,
      onLoad,
      onFrameUpdate,
      onFinished,
      onError,
    });
  }, [onStateChange, onLoad, onFrameUpdate, onFinished, onError]);

  // Load source when it changes
  useEffect(() => {
    const core = coreRef.current;
    const uri = normalizedSource?.uri;
    if (!core || !uri || loadedSourceRef.current === uri) return;

    loadedSourceRef.current = uri;
    const partUris = parts?.map(p => p.uri).filter(Boolean) as string[] | undefined;
    core.setSource(uri, partUris);

    return () => {
      loadedSourceRef.current = null;
      core.resetSource();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [normalizedSource?.uri]);

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
    const core = coreRef.current;
    if (!core) return;

    if (paused) {
      core.pause();
    } else {
      if (core.state === 'paused') {
        core.resume();
      }
    }
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
};

const styles = StyleSheet.create({
  container: {
    overflow: 'hidden',
  },
});

AlphaVideoGpuView.displayName = 'AlphaVideoGpuView';

// Legacy alias for backwards compatibility
export const KTX2PlayerView = AlphaVideoGpuView;
export type KTX2PlayerViewProps = AlphaVideoGpuViewProps;

export default AlphaVideoGpuView;
