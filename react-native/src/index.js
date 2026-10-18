import React, { forwardRef, useImperativeHandle, useMemo, useRef } from 'react';
import { View, StyleSheet, Platform } from 'react-native';

// Platform-specific imports
let NativeAlphaVideoGpuView;
let NativeCommands;
let WebAlphaVideoGpuView;

if (Platform.OS === 'web') {
  // Resolves to AlphaVideoGpuView.web.tsx; native bundles get the empty AlphaVideoGpuView.js
  WebAlphaVideoGpuView = require('./AlphaVideoGpuView').default;
} else {
  // Codegen spec: a Fabric component on the new architecture, a view manager on the old one
  const nativeComponent = require('./AlphaVideoGpuViewNativeComponent');
  NativeAlphaVideoGpuView = nativeComponent.default;
  NativeCommands = nativeComponent.Commands;
}

/**
 * AlphaVideoGpuView - React Native component for GPU-accelerated alpha video playback
 * 
 * Features:
 * - GPU texture compression (ASTC) for efficient transparent video
 * - GPU-accelerated playback (Metal on iOS, OpenGL ES on Android, WebGL on Web)
 * - Low CPU/battery usage compared to traditional video codecs
 * - Sources: https:// (read by range, and stored), file:// or absolute paths, asset://, on Android
 *   content:// and res/raw, and with source.dataSource anything a registered native data source reads
 * - Works on iOS, Android, and Web (React Native Web)
 * 
 * @param {Object} props - Component props
 * @param {Object|string} props.source - Source object {uri, cacheKey, headers, dataSource}, or a bare URI string (legacy)
 * @param {boolean} [props.autoPlay=true] - Start playing automatically
 * @param {boolean} [props.loop=true] - Loop the animation
 * @param {number} [props.playbackSpeed=1.0] - Playback speed multiplier
 * @param {string} [props.cacheKey] - What the store keeps the source under, instead of its URL
 * @param {string} [props.storage='auto'] - 'auto', 'disk', 'memory' or 'none': where a URL's or data source's bytes are kept
 * @param {number} [props.maxStorageBytes] - The store's cap in bytes (default 512 MB)
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
    const send = (name, ...args) => {
      if (viewRef.current) NativeCommands[name](viewRef.current, ...args);
    };
    return {
      play: () => send('play'),
      pause: () => send('pause'),
      stop: () => send('stop'),
      seekToFrame: frame => send('seekToFrame', frame),
      seekToTime: seconds => send('seekToTime', seconds),
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

  // A key passed as a prop or inside `source` both count; the prop wins.
  const effectiveCacheKey = cacheKey != null ? cacheKey : normalizedSource?.cacheKey;

  // Codegen has no string maps, so headers go to native as [name, value, ...]. Keyed by value,
  // so that a source object made inline on each render isn't a new source.
  const { uri, headers, dataSource } = normalizedSource ?? {};
  const headersKey = headers ? JSON.stringify(headers) : '';
  const nativeSource = useMemo(
    () => ({
      uri,
      cacheKey: normalizedSource?.cacheKey,
      headers: headers ? [].concat(...Object.entries(headers)) : undefined,
      dataSource,
    }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [uri, normalizedSource?.cacheKey, headersKey, dataSource],
  );

  if (!source) {
    return <View style={style} />;
  }

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
      source={nativeSource}
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
