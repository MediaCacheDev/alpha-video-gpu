import type * as React from 'react';
import type { HostComponent, ViewProps } from 'react-native';
import type {
  DirectEventHandler,
  Double,
  Float,
  Int32,
  WithDefault,
} from 'react-native/Libraries/Types/CodegenTypes';
import codegenNativeCommands from 'react-native/Libraries/Utilities/codegenNativeCommands';
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';

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

type NativeSource = Readonly<{
  uri?: string;
  cacheKey?: string;
}>;

type LoadEvent = Readonly<{
  width: Int32;
  height: Int32;
  frameCount: Int32;
  frameRate: Double;
  duration: Double;
}>;

type FrameUpdateEvent = Readonly<{
  frame: Int32;
  totalFrames: Int32;
}>;

type StateChangeEvent = Readonly<{
  state: string;
}>;

type ErrorEvent = Readonly<{
  error: string;
}>;

export interface NativeProps extends ViewProps {
  source?: NativeSource;
  autoPlay?: WithDefault<boolean, true>;
  loop?: WithDefault<boolean, true>;
  playbackSpeed?: WithDefault<Float, 1.0>;
  /** Pause playback and decompression (useful for screen focus/blur) */
  paused?: WithDefault<boolean, false>;
  cacheKey?: string;

  // Streaming parameters (with stream)
  /** RAM-mode streaming: GPU ring slots frames are uploaded into. Unused in VRAM mode. Default: 16 */
  streamingRingSize?: WithDefault<Int32, 16>;
  /** Frames kept decoded while streaming: in CPU memory (RAM mode) or as the GPU ring (VRAM mode). Default: 60 */
  streamingCacheSize?: WithDefault<Int32, 60>;
  /** Frames to decode ahead of playback. Default: 30 */
  streamingPrefetchAhead?: WithDefault<Int32, 30>;

  /**
   * Memory strategy for frame storage.
   * - 'auto': Default behavior (VRAM for non-stream, RAM for stream)
   * - 'vram': Store all frames in GPU VRAM (texture array). Best for many simultaneous players.
   * - 'ram': Store decoded frames in RAM, upload per-frame. Lower VRAM usage.
   */
  memoryStrategy?: WithDefault<'auto' | 'vram' | 'ram', 'auto'>;

  /**
   * Enable streaming mode for texture array playback.
   * When true: Use streaming decompression with ring buffer (constant memory usage).
   * When false (default): Load all frames at once.
   * Only applies to packingMode='array' files.
   */
  stream?: WithDefault<boolean, false>;

  /** Send onFrameUpdate for every rendered frame. Default: false */
  enableFrameEvents?: WithDefault<boolean, false>;
  /** Log info traces (Logcat / Xcode console). Warnings and errors always log. Default: false */
  verbose?: WithDefault<boolean, false>;

  onLoad?: DirectEventHandler<LoadEvent>;
  onFrameUpdate?: DirectEventHandler<FrameUpdateEvent>;
  onStateChange?: DirectEventHandler<StateChangeEvent>;
  onFinished?: DirectEventHandler<Readonly<{}>>;
  onError?: DirectEventHandler<ErrorEvent>;
}

type NativeComponentType = HostComponent<NativeProps>;

interface NativeCommands {
  play: (viewRef: React.ElementRef<NativeComponentType>) => void;
  pause: (viewRef: React.ElementRef<NativeComponentType>) => void;
  stop: (viewRef: React.ElementRef<NativeComponentType>) => void;
  seekToFrame: (viewRef: React.ElementRef<NativeComponentType>, frame: Int32) => void;
  seekToTime: (viewRef: React.ElementRef<NativeComponentType>, seconds: Double) => void;
}

export const Commands: NativeCommands = codegenNativeCommands<NativeCommands>({
  supportedCommands: ['play', 'pause', 'stop', 'seekToFrame', 'seekToTime'],
});

export default codegenNativeComponent<NativeProps>('AlphaVideoGpuView') as NativeComponentType;
