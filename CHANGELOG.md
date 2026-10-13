# Changelog

The packages built from this repository share one version: `react-native-alpha-video-gpu` and `alpha-video-gpu-web` on npm, `dev.mediacache:alpha-video-gpu` on Maven Central, and the `AlphaVideoGPU` Swift package and `alpha-video-gpu` pod.

## 1.0.0 - Unreleased

First public release.

- Plays transparent animations stored as KTX2 texture arrays: native ASTC, or Basis Universal UASTC transcoded at load. Metal on iOS, OpenGL ES on Android, WebGL 2 or 1 on the web.
- Preloaded playback in VRAM or RAM, and streaming with `stream`: KTX2-internal zstd, whole-file `.zst` and uncompressed (memory-mapped) files, with a bounded ring and frame cache.
- Atlas and multi-part animations, with metadata from the encoder's `BasisPlayerMetadata` key.
- Sources: `https://`, `file://`, absolute paths, `asset://` (Android assets, the iOS bundle, the web root), and on Android `content://` and `res/raw` names. Downloads are cached once under `cacheKey` (IndexedDB on the web).
- Playback control: `play`, `pause`, `stop`, `seekToFrame` and `seekToTime` on every platform, plus `paused`, `autoPlay`, `loop` and `playbackSpeed`.
- Events: `onLoad`, `onFrameUpdate` (with `enableFrameEvents`), `onStateChange`, `onFinished` and `onError`. On the web, `onError` reports `ASTC_UNSUPPORTED` or `WEBGL_UNSUPPORTED` in `code`.
- A software RGBA fallback for emulators and devices without ASTC.
- React Native: one `AlphaVideoGpuView` for Android, iOS and react-native-web, on both the new architecture (Fabric) and the old one.
- Standalone Android and iOS libraries with no React Native dependency, and `alpha-video-gpu-web` for plain JS/TS and React.
- Android libraries are linked for 16 KB pages.
