# Changelog

The packages built from this repository share one version: `react-native-alpha-video-gpu` and `alpha-video-gpu-web` on npm, `dev.mediacache:alpha-video-gpu` on Maven Central, and the `AlphaVideoGPU` Swift package and `alpha-video-gpu` pod.

## 1.0.0 - Unreleased

First public release.

- Plays transparent animations stored as ASTC texture arrays in KTX2 files. Metal on iOS, OpenGL ES on Android, WebGL 2 on the web.
- Streaming (`stream`, on by default) and preloaded playback, with frames in VRAM (`memoryStrategy="vram"`, the default) or RAM. Streaming takes KTX2-internal zstd and uncompressed (memory-mapped) files, with a bounded ring and frame cache.
- Metadata from the encoder's `BasisPlayerMetadata` key. Files in the earlier experimental formats (Basis Universal UASTC, atlas packing, GLB containers, multi-part files, whole-file `.zst`) are rejected; they play on the `archive/basis-atlas-parts` branch.
- Sources: `https://`, `file://`, absolute paths, `asset://` (Android assets, the iOS bundle, the web root), and on Android `content://` and `res/raw` names. Downloads are cached once under `cacheKey` (IndexedDB on the web).
- Playback control: `play`, `pause`, `stop`, `seekToFrame` and `seekToTime` on every platform, plus `paused`, `autoPlay`, `loop` and `playbackSpeed`.
- Events: `onLoad`, `onFrameUpdate` (with `enableFrameEvents`), `onStateChange`, `onFinished` and `onError`. On the web, `onError` reports `ASTC_UNSUPPORTED` or `WEBGL_UNSUPPORTED` in `code`.
- A software ASTC decoder for emulators, devices and browsers without ASTC. On the web it's the same decoder compiled to WebAssembly, loaded only by browsers without `WEBGL_compressed_texture_astc`; they report `ASTC_UNSUPPORTED` only if WebAssembly is blocked as well.
- Web streaming no longer stalls when a seek restarts decoding in the same task as an earlier seek or `play()` (seen with `memoryStrategy="ram"`, after a seek back to the start).
- Colours reach the screen exactly as encoded on every platform. iOS devices sample ASTC as plain (LDR) data, like Android and the web; before 1.0.0 they sampled it as sRGB, which darkened mid-tones by about half and gave semi-transparent edges dark fringes.
- React Native: one `AlphaVideoGpuView` for Android, iOS and react-native-web, on both the new architecture (Fabric) and the old one.
- Standalone Android and iOS libraries, and `alpha-video-gpu-web` for plain JS/TS and React.
- Android libraries are linked for 16 KB pages.
