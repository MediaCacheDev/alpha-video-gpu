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
  /** Multi-part animations: every part's source, when the parts can't be found next to `source` */
  parts?: ReadonlyArray<AlphaVideoGpuSource> | null;
  /** Cache key for remote downloads (same as `source.cacheKey`) */
  cacheKey?: string;
  /** Pause playback */
  paused?: boolean;
  /** View style */
  style?: ViewStyle;
  /** Callback when player state changes */
  onStateChange?: (event: { nativeEvent: { state: string } }) => void;
  /** Callback on each frame update */
  onFrameUpdate?: (event: { nativeEvent: { frame: number } }) => void;
  /** Callback when playback finishes */
  onFinished?: () => void;
  /** Callback on error */
  onError?: (event: { nativeEvent: { error: string } }) => void;
  /** Callback when loaded */
  onLoad?: (event: { nativeEvent: { width: number; height: number; frameCount: number; frameRate: number; duration: number } }) => void;
  
  // Streaming parameters
  streamingRingSize?: number;
  streamingCacheSize?: number;
  streamingPrefetchAhead?: number;
  memoryStrategy?: 'auto' | 'vram' | 'ram';
  stream?: boolean;
  /** Send onFrameUpdate for every rendered frame (native). Default: false */
  enableFrameEvents?: boolean;
  /** Log info traces. Warnings and errors always log. Default: false */
  verbose?: boolean;
}

// The actual component is defined in index.js, but we declare the type here
export declare const AlphaVideoGpuView: React.MemoExoticComponent<React.FC<AlphaVideoGpuViewProps>>;
