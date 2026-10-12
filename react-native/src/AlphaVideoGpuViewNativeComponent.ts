import type { ViewProps, HostComponent } from 'react-native';
import { requireNativeComponent } from 'react-native';

/**
 * Where to load from:
 * - `https://…` / `http://…`: downloaded once into the app cache (named by `cacheKey`, else a hash of the URL)
 * - `file:///abs/path` or `/abs/path`: read in place
 * - `asset://relative/path`: Android APK `assets/`, the iOS app bundle, or the site root on web
 * - Android only: `content://…`, or a res/raw name (what `require()` resolves to in a release build)
 */
export interface SourceType {
  uri?: string;
  /** Stable identity for the download cache, for URLs that change while the file doesn't (presigned URLs) */
  cacheKey?: string;
}

export interface AlphaVideoGpuLoadEvent {
  width: number;
  height: number;
  frameCount: number;
  frameRate: number;
  duration: number;
}

export interface NativeProps extends ViewProps {
  source?: SourceType | null;
  onLoad?: (event: { nativeEvent: AlphaVideoGpuLoadEvent }) => void;
  /**
   * For multi-part animations whose part files can't be found next to `source` (res/raw,
   * content://, presigned URLs): every part's source, first part containing the metadata.
   */
  parts?: ReadonlyArray<SourceType> | null;
  autoPlay?: boolean;
  loop?: boolean;
  playbackSpeed?: number;
  /** Pause playback and decompression (useful for screen focus/blur) */
  paused?: boolean;
  cacheKey?: string;
  
  // Streaming parameters (for .ktx2.zst files)
  /** RAM-mode streaming: GPU ring slots frames are uploaded into. Unused in VRAM mode. Default: 16 */
  streamingRingSize?: number;
  /** Frames kept decoded while streaming: in CPU memory (RAM mode) or as the GPU ring (VRAM mode). Default: 60 */
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
  
  /**
   * Enable streaming mode for texture array playback.
   * When true: Use streaming decompression with ring buffer (constant memory usage).
   * When false (default): Load all frames at once.
   * Only applies to packingMode='array' files.
   */
  stream?: boolean;

  /** Send onFrameUpdate for every rendered frame. Default: false */
  enableFrameEvents?: boolean;
  /** Log info traces (Logcat / Xcode console). Warnings and errors always log. Default: false */
  verbose?: boolean;
}

export default requireNativeComponent<NativeProps>(
  'AlphaVideoGpuView'
) as HostComponent<NativeProps>;
