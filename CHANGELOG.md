# Changelog

The packages built from this repository share one version: `react-native-alpha-video-gpu` and `alpha-video-gpu-web` on npm, `dev.mediacache:alpha-video-gpu` and `dev.mediacache:alpha-video-gpu-media3` on Maven Central, and the `AlphaVideoGPU` Swift package and `alpha-video-gpu` pod.

## 1.0.0 - Unreleased

First public release.

- Plays transparent animations stored as ASTC texture arrays in KTX2 files. Metal on iOS, OpenGL ES on Android, WebGL 2 on the web.
- Streaming (`stream`, on by default) and preloaded playback, with frames in VRAM (`memoryStrategy="vram"`, the default) or RAM. Streaming takes KTX2-internal zstd and uncompressed (memory-mapped) files, with a bounded ring and frame cache.
- Metadata from the encoder's `BasisPlayerMetadata` key. Files in the earlier experimental formats (Basis Universal UASTC, atlas packing, GLB containers, multi-part files, whole-file `.zst`) are rejected; they play on the `archive/basis-atlas-parts` branch.
- Sources: `https://`, `file://`, absolute paths, `asset://` (Android assets, the iOS bundle, the web root), and on Android `content://` and `res/raw` names.
- Android and iOS read http(s) files by range requests as they play, through the C++ reader of [alpha-video-source](https://github.com/MediaCacheDev/alpha-video-source), instead of downloading them whole first. `source.headers` go with every request.
  - A streamed zstd file starts after its header and seek table, and is then read 256 KB at a time as it decodes. A seek reads from its seek point. Uncompressed files, and every file with `stream` off, are read whole first. Servers without range support work too.
  - Reads never block the render loop: a frame whose bytes haven't arrived is decoded on a later tick. A dropped connection is retried 3 times. A read that still fails reports `Reading <uri> failed: …` through `onError`, and a source that can't be opened reports `Loading <uri> failed: …`.
  - `storage` (`'auto'`, `'disk'`, `'memory'`, `'none'`) keeps what was played between plays, partially: a clip played through plays again offline. `maxStorageBytes` caps it (512 MB by default), and `cacheKey` names the stored copy. Replaces the whole-file download cache.
  - Data sources: an app registers one under a name (`AlphaVideoGpuDataSources`, on both platforms) and names it in `source.dataSource`, to read the bytes with its own HTTP client, cache or decryption. `dev.mediacache:alpha-video-gpu-media3` adapts any Media3 `DataSource.Factory` on Android. The interfaces have the WebM player's shape, as separate types.
  - On iOS, setting `source` loads it on the next main-queue turn, so the other properties can be set in any order, and changing the source frees the previous one's resources first.
- The web player reads files by range instead of downloading them whole, through [alpha-video-source](https://github.com/MediaCacheDev/alpha-video-source).
  - A URL streams after its first few hundred kilobytes, and a seek fetches from its seek point on. Servers without range support are read progressively.
  - `setSource(source, { cacheKey, headers })` also takes a `Blob` or `File`, an `ArrayBuffer` or `Uint8Array`, or a byte source (`{ size, read(offset, length, signal) }`) for an app's own fetcher or cache. `headers` go with every request. The React component and React Native Web take the same, through `src` or `source`.
  - `storage` keeps what was played between visits: `'auto'` (OPFS, else IndexedDB; the default for URLs), `'opfs'`, `'indexeddb'`, `'cache'`, `'memory'`, `'none'`, or a custom store. `maxStorageBytes` caps it (512 MB by default), evicting the least recently played. A clip stored whole plays again offline. A partial copy is dropped when the file's `ETag`, `Last-Modified` or length changes.
  - With `'none'`, clips up to 16 MB stay in memory, so loops don't fetch again. And with any storage, a streamed clip whose frames all fit the streaming buffer loops without reading or decoding again.
  - Replaces the IndexedDB whole-file cache, and its `getCachedFile` and `setCachedFile` exports.
- Playback control: `play`, `pause`, `stop`, `seekToFrame` and `seekToTime` on every platform, plus `paused`, `autoPlay`, `loop` and `playbackSpeed`.
- Events: `onLoad`, `onFrameUpdate` (with `enableFrameEvents`), `onStateChange`, `onFinished` and `onError`. On the web, `onError` reports `ASTC_UNSUPPORTED` or `WEBGL_UNSUPPORTED` in `code`.
- A software ASTC decoder for emulators, devices and browsers without ASTC. On the web it's the same decoder compiled to WebAssembly, loaded only by browsers without `WEBGL_compressed_texture_astc`; they report `ASTC_UNSUPPORTED` only if WebAssembly is blocked as well.
- Streaming seeks start at seek points. When a zstd level ends with a seek table (zstd's seekable format, written by alpha-video-gpu-encoder's `--seek-interval`), a seek starts decoding at the zstd frame before its target, or carries on from the decoder if that is closer. Files without a table play as before, and seek from the start of the file.
  - A long catch-up to a seek runs in slices: 8 ms per task on the web, per display tick on iOS, and 16 ms per render-loop pass on Android, so drawing, input, later seeks and teardown aren't held up.
  - Streamed zstd levels made of several frames decode on every platform. iOS stopped decoding at each frame end, and its seeks stopped skipping there, landing on the wrong frame.
  - Decoders no longer lose the last bytes when the input runs out while zstd still holds output (Android, iOS and the web). The web decoder also stops on corrupt data, where it used to loop forever.
  - The web player feeds zstd 256 KB of input at a time and decodes straight into frame buffers. Before, every start copied the whole level into WebAssembly memory, and each frame was gathered by re-copying a growing buffer.
- Web streaming no longer stalls when a seek restarts decoding in the same task as an earlier seek or `play()` (seen with `memoryStrategy="ram"`, after a seek back to the start).
- Colours reach the screen exactly as encoded on every platform. iOS devices sample ASTC as plain (LDR) data, like Android and the web; before 1.0.0 they sampled it as sRGB, which darkened mid-tones by about half and gave semi-transparent edges dark fringes.
- React Native: one `AlphaVideoGpuView` for Android, iOS and react-native-web, on both the new architecture (Fabric) and the old one.
- Standalone Android and iOS libraries, and `alpha-video-gpu-web` for plain JS/TS and React.
- Android libraries are linked for 16 KB pages.
