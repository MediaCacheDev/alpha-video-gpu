import React, { useMemo } from 'react';
import { View, StyleSheet, Platform } from 'react-native';

// Platform-specific imports
let NativeAlphaVideoGpuView;
let WebAlphaVideoGpuView;

if (Platform.OS === 'web') {
  // Web implementation using WebGL + Basis Universal
  WebAlphaVideoGpuView = require('./src/AlphaVideoGpuView.web').default;
} else {
  // Native implementation using codegenNativeComponent
  // This supports both New Architecture (Fabric) and Legacy Architecture (auto-fallback)
  NativeAlphaVideoGpuView = require('./src/AlphaVideoGpuViewNativeComponent').default;
}

/**
 * AlphaVideoGpuView - React Native component for GPU-accelerated alpha video playback
 * 
 * Features:
 * - GPU texture compression (ASTC) for efficient transparent video
 * - GPU-accelerated playback (Metal on iOS, OpenGL ES on Android, WebGL on Web)
 * - Low CPU/battery usage compared to traditional video codecs
 * - Sources: https:// (cached), file:// or absolute paths, asset://, and on Android content:// and res/raw
 * - Works on iOS, Android, and Web (React Native Web)
 * 
 * @param {Object} props - Component props
 * @param {Object|string} props.source - Source object {uri, cacheKey}, or a bare URI string (legacy)
 * @param {boolean} [props.autoPlay=true] - Start playing automatically
 * @param {boolean} [props.loop=true] - Loop the animation
 * @param {number} [props.playbackSpeed=1.0] - Playback speed multiplier
 * @param {string} [props.cacheKey] - Custom cache key for remote downloads
 * @param {Function} [props.onStateChange] - Callback when player state changes
 * @param {Function} [props.onFrameUpdate] - Callback on each frame update
 * @param {Function} [props.onFinished] - Callback when playback finishes
 * @param {Function} [props.onError] - Callback on error
 * @param {Object} [props.style] - View style
 */
export const AlphaVideoGpuView = React.memo(({
  source,
  autoPlay = true,
  loop = true,
  playbackSpeed = 1.0,
  cacheKey,
  style,
  onStateChange,
  onFrameUpdate,
  onFinished,
  onError,
  ...otherProps
}) => {
  // Normalize source to object format for consistency
  const normalizedSource = useMemo(() => {
    if (!source) return null;
    
    // If source is already an object, use it as-is
    if (typeof source === 'object') {
      return source;
    }
    
    // Legacy string support - convert to object
    return { uri: source };
  }, [source]);

  if (!source) {
    return <View style={style} />;
  }

  // A key passed as a prop or inside `source` both count; the prop wins.
  const effectiveCacheKey = cacheKey != null ? cacheKey : normalizedSource?.cacheKey;

  // Web implementation
  if (Platform.OS === 'web') {
    return (
      <WebAlphaVideoGpuView
        source={normalizedSource}
        cacheKey={effectiveCacheKey}
        autoPlay={autoPlay}
        loop={loop}
        playbackSpeed={playbackSpeed}
        style={style}
        onStateChange={onStateChange}
        onFrameUpdate={onFrameUpdate}
        onFinished={onFinished}
        onError={onError}
        {...otherProps}
      />
    );
  }

  // Native implementation (iOS/Android)
  // Map 'paused' prop to 'reactPaused' on iOS (to avoid conflict with MTKView's paused property)
  const { paused, ...restOtherProps } = otherProps;
  const nativeProps = {
    source: normalizedSource,
    autoPlay,
    loop,
    playbackSpeed,
    cacheKey: effectiveCacheKey,
    ...(Platform.OS === 'ios' && paused !== undefined ? { reactPaused: paused } : { paused }),
  };

  return (
    <NativeAlphaVideoGpuView
      {...nativeProps}
      style={[styles.container, style]}
      {...restOtherProps}
    />
  );
});

AlphaVideoGpuView.displayName = 'AlphaVideoGpuView';

const styles = StyleSheet.create({
  container: {
    // Note: backgroundColor not supported on GLSurfaceView
  },
});

// Legacy alias for backwards compatibility
export const KTX2PlayerView = AlphaVideoGpuView;

// Default export
export default AlphaVideoGpuView;
