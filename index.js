import React, { useMemo } from 'react';
import { View, StyleSheet, Platform } from 'react-native';

// Platform-specific imports
let NativeKTX2PlayerView;
let WebKTX2PlayerView;

if (Platform.OS === 'web') {
  // Web implementation using WebGL + Basis Universal
  WebKTX2PlayerView = require('./src/KTX2PlayerView.web').default;
} else {
  // Native implementation using codegenNativeComponent
  // This supports both New Architecture (Fabric) and Legacy Architecture (auto-fallback)
  NativeKTX2PlayerView = require('./src/KTX2PlayerViewNativeComponent').default;
}

/**
 * KTX2PlayerView - React Native component for playing KTX2 animated textures
 * 
 * Features:
 * - Supports KTX2 files with Basis Universal compression
 * - GPU-accelerated playback (Metal on iOS, OpenGL ES on Android, WebGL on Web)
 * - Automatic transcoding to device-optimal format (ASTC, ETC2, BC7, etc.)
 * - Multiple source formats: HTTP/HTTPS URLs, local files, Android assets
 * - Works on iOS, Android, and Web (React Native Web)
 * 
 * @param {Object} props - Component props
 * @param {Object|string} props.source - Source object with {uri, path, cacheKey} or string path (legacy)
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
export const KTX2PlayerView = React.memo(({
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

  // Web implementation
  if (Platform.OS === 'web') {
    return (
      <WebKTX2PlayerView
        source={normalizedSource}
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
    cacheKey,
    ...(Platform.OS === 'ios' && paused !== undefined ? { reactPaused: paused } : { paused }),
  };

  return (
    <NativeKTX2PlayerView
      {...nativeProps}
      style={[styles.container, style]}
      {...restOtherProps}
    />
  );
});

KTX2PlayerView.displayName = 'KTX2PlayerView';

const styles = StyleSheet.create({
  container: {
    // Note: backgroundColor not supported on GLSurfaceView
  },
});

// Default export
export default KTX2PlayerView;
