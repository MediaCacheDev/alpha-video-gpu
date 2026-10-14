import type { ViewStyle } from 'react-native';
import type React from 'react';

// Re-export native component types
export * from './AlphaVideoGpuViewNativeComponent';

// Source type for the wrapper component (see SourceType for the URI forms)
export type AlphaVideoGpuSource = import('./AlphaVideoGpuViewNativeComponent').SourceType;

// Props for the main AlphaVideoGpuView wrapper component (index.js)
export interface AlphaVideoGpuViewProps {
  /** Source object, or a bare URI string (legacy) */
  source?: AlphaVideoGpuSource | string | null;
  /** Start playing automatically (default: true) */
  autoPlay?: boolean;
  /** Loop the animation (default: true) */
  loop?: boolean;
  /** Playback speed multiplier (default: 1.0) */
  playbackSpeed?: number;
  /** Cache key for remote downloads (same as `source.cacheKey`) */
  cacheKey?: string;
  /** Pause playback */
  paused?: boolean;
  /** View style */
  style?: ViewStyle;
  // Every event has this shape on Android, iOS and web
  /** Callback when player state changes */
  onStateChange?: (event: { nativeEvent: { state: string } }) => void;
  /** Callback after a seek, and for every frame played with `enableFrameEvents` */
  onFrameUpdate?: (event: { nativeEvent: { frame: number; totalFrames: number } }) => void;
  /** Callback when playback finishes */
  onFinished?: (event: { nativeEvent: {} }) => void;
  /** Callback on error. `code` is set on web only: 'ASTC_UNSUPPORTED' or 'WEBGL_UNSUPPORTED' */
  onError?: (event: { nativeEvent: { error: string; code?: 'ASTC_UNSUPPORTED' | 'WEBGL_UNSUPPORTED' } }) => void;
  /** Callback when loaded */
  onLoad?: (event: { nativeEvent: { width: number; height: number; frameCount: number; frameRate: number; duration: number } }) => void;
  
  // Streaming parameters
  streamingRingSize?: number;
  streamingCacheSize?: number;
  streamingPrefetchAhead?: number;
  memoryStrategy?: 'auto' | 'vram' | 'ram';
  stream?: boolean;
  /** Send onFrameUpdate for every frame played; seeks always send it. Default: false */
  enableFrameEvents?: boolean;
  /** Log info traces. Warnings and errors always log. Default: false */
  verbose?: boolean;
}

/**
 * Seeks show the frame (clamped to the clip) and keep playing, or stay paused. They are ignored
 * until `onLoad`. With `stream`, a seek outside the buffered frames decodes the file again from
 * the start (or onwards from the decoder, for a seek ahead of it).
 */
export interface AlphaVideoGpuViewRef {
  /** Plays from the shown frame, or from the start once a clip that doesn't loop has finished. */
  play: () => void;
  pause: () => void;
  /** Pauses on the first frame. */
  stop: () => void;
  seekToFrame: (frame: number) => void;
  /** Seeks to the frame showing at that time, in seconds. */
  seekToTime: (seconds: number) => void;
}

// The actual component is defined in index.js, but we declare the type here
export declare const AlphaVideoGpuView: React.MemoExoticComponent<
  React.ForwardRefExoticComponent<AlphaVideoGpuViewProps & React.RefAttributes<AlphaVideoGpuViewRef>>
>;
