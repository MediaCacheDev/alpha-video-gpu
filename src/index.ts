import type { ViewStyle } from 'react-native';
import type React from 'react';

// Re-export native component types
export * from './AlphaVideoGpuViewNativeComponent';

// Source type for the wrapper component
export interface AlphaVideoGpuSource {
  uri?: string;
  path?: string;
  cacheKey?: string;
}

// Props for the main AlphaVideoGpuView wrapper component (index.js)
export interface AlphaVideoGpuViewProps {
  /** Source object with uri/path, or string path (legacy) */
  source?: AlphaVideoGpuSource | string | null;
  /** Start playing automatically (default: true) */
  autoPlay?: boolean;
  /** Loop the animation (default: true) */
  loop?: boolean;
  /** Playback speed multiplier (default: 1.0) */
  playbackSpeed?: number;
  /** Custom cache key for remote downloads */
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
  gpuPlayback?: boolean;
}

// The actual component is defined in index.js, but we declare the type here
export declare const AlphaVideoGpuView: React.MemoExoticComponent<React.FC<AlphaVideoGpuViewProps>>;

// Legacy alias for backwards compatibility
export declare const KTX2PlayerView: typeof AlphaVideoGpuView;

// Default export
export default AlphaVideoGpuView;
