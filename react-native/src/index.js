import React, { forwardRef, useImperativeHandle, useMemo, useRef } from 'react';
import { View, StyleSheet, Platform, UIManager, findNodeHandle } from 'react-native';

const NATIVE_VIEW = 'AlphaVideoGpuView';

// Platform-specific imports
let NativeAlphaVideoGpuView;
let WebAlphaVideoGpuView;

if (Platform.OS === 'web') {
  // Resolves to AlphaVideoGpuView.web.tsx; native bundles get the empty AlphaVideoGpuView.js
  WebAlphaVideoGpuView = require('./AlphaVideoGpuView').default;
} else {
  // Native implementation using codegenNativeComponent
  // This supports both New Architecture (Fabric) and Legacy Architecture (auto-fallback)
  NativeAlphaVideoGpuView = require('./AlphaVideoGpuViewNativeComponent').default;
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
 *
 * The ref has play(), pause(), stop(), seekToFrame(frame) and seekToTime(seconds).
 */
export const AlphaVideoGpuView = React.memo(forwardRef(({
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
}, ref) => {
  const viewRef = useRef(null);

  useImperativeHandle(ref, () => {
    if (Platform.OS === 'web') {
      const call = (name, args) => viewRef.current?.[name]?.(...args);
      return {
        play: () => call('play', []),
        pause: () => call('pause', []),
        stop: () => call('stop', []),
        seekToFrame: frame => call('seekToFrame', [frame]),
        seekToTime: seconds => call('seekToTime', [seconds]),
      };
    }
    const send = (name, args) => {
      const handle = viewRef.current ? findNodeHandle(viewRef.current) : null;
      if (handle == null) return;
      UIManager.dispatchViewManagerCommand(
        handle,
        UIManager.getViewManagerConfig(NATIVE_VIEW)?.Commands?.[name] ?? name,
        args
      );
    };
    return {
      play: () => send('play', []),
      pause: () => send('pause', []),
      stop: () => send('stop', []),
      seekToFrame: frame => send('seekToFrame', [frame]),
      seekToTime: seconds => send('seekToTime', [seconds]),
    };
  }, []);
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
        ref={viewRef}
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

  return (
    <NativeAlphaVideoGpuView
      ref={viewRef}
      source={normalizedSource}
      autoPlay={autoPlay}
      loop={loop}
      playbackSpeed={playbackSpeed}
      cacheKey={effectiveCacheKey}
      style={[styles.container, style]}
      onStateChange={onStateChange}
      onFrameUpdate={onFrameUpdate}
      onFinished={onFinished}
      onError={onError}
      {...otherProps}
    />
  );
}));

AlphaVideoGpuView.displayName = 'AlphaVideoGpuView';

const styles = StyleSheet.create({
  container: {
    // Note: backgroundColor not supported on GLSurfaceView
  },
});
