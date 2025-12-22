import React, { useMemo } from 'react';
import { View, StyleSheet } from 'react-native';

// Import native component using codegenNativeComponent
// This supports both New Architecture (Fabric) and Legacy Architecture (auto-fallback)
import NativeKTX2PlayerView from './src/KTX2PlayerViewNativeComponent';

/**
 * KTX2PlayerView - React Native component for playing KTX2 animated textures
 * 
 * Features:
 * - Supports KTX2 files with Basis Universal compression
 * - GPU-accelerated playback using OpenGL ES
 * - Automatic transcoding to device-optimal format (ASTC, ETC2, etc.)
 * - Multiple source formats: HTTP/HTTPS URLs, local files, Android assets
 * 
 * @param {Object} props - Component props
 * @param {Object|string} props.source - Source object with {uri, path, cacheKey} or string path (legacy)
 * @param {boolean} [props.autoPlay=true] - Start playing automatically
 * @param {boolean} [props.loop=true] - Loop the animation
 * @param {number} [props.playbackSpeed=1.0] - Playback speed multiplier
 * @param {string} [props.cacheKey] - Custom cache key for remote downloads
 * @param {Object} [props.style] - View style
 */
export const KTX2PlayerView = React.memo(({
  source,
  autoPlay = true,
  loop = true,
  playbackSpeed = 1.0,
  cacheKey,
  style,
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
  
  // Memoize props to prevent unnecessary re-renders
  const nativeProps = useMemo(() => ({
    source: normalizedSource,
    autoPlay,
    loop,
    playbackSpeed,
    cacheKey,
  }), [normalizedSource, autoPlay, loop, playbackSpeed, cacheKey]);

  if (!source) {
    return <View style={style} />;
  }

  return (
    <NativeKTX2PlayerView
      {...nativeProps}
      style={[styles.container, style]}
      {...otherProps}
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
