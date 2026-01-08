import type { ViewProps, HostComponent } from 'react-native';
import { requireNativeComponent } from 'react-native';

export interface SourceType {
  uri?: string;
  cacheKey?: string;
}

export interface NativeProps extends ViewProps {
  source?: SourceType | null;
  /** For multi-part animations: array of all part sources (first part contains metadata) */
  parts?: ReadonlyArray<SourceType> | null;
  autoPlay?: boolean;
  loop?: boolean;
  playbackSpeed?: number;
  /** Pause playback and decompression (useful for screen focus/blur) */
  paused?: boolean;
  cacheKey?: string;
  /**
   * Enable GPU-controlled playback timing (experimental).
   * When true: GPU calculates frame index from elapsed time in shader.
   * When false (default): CPU controls frame timing in render loop.
   * GPU mode may improve performance with many simultaneous animations.
   */
  gpuPlayback?: boolean;
  
  // Streaming parameters (for .ktx2.zst files)
  /** GPU ring buffer slots (VRAM). Default: 16 */
  streamingRingSize?: number;
  /** CPU frame cache size (RAM). Default: 60 */
  streamingCacheSize?: number;
  /** Frames to decode ahead of playback. Default: 30 */
  streamingPrefetchAhead?: number;
  
  /**
   * Memory strategy for frame storage.
   * - 'auto': Default behavior (VRAM for non-stream, RAM for stream)
   * - 'vram': Store all frames in GPU VRAM (texture array). Best for many simultaneous players.
   * - 'ram': Store decoded frames in RAM, upload per-frame. Lower VRAM usage.
   * Default: 'auto'
   */
  memoryStrategy?: 'auto' | 'vram' | 'ram';
}

export default requireNativeComponent<NativeProps>(
  'KTX2PlayerView'
) as HostComponent<NativeProps>;
