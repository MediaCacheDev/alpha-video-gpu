# alpha-video-gpu

Plays transparent (alpha) videos stored as ASTC texture arrays in KTX2 files, drawn with Metal on iOS, OpenGL ES on Android and WebGL on the web. Frames go to the GPU without being decoded, so CPU and battery use stay low. It comes as a React Native component, as standalone Android and iOS libraries, and as a web package.

| Platform | Package | View |
|---|---|---|
| React Native (Android, iOS, web) | npm `react-native-alpha-video-gpu` | `AlphaVideoGpuView` |
| Android | Maven `dev.mediacache:alpha-video-gpu` | `dev.mediacache.alphavideo.gpu.AlphaVideoGpuView` |
| iOS | Swift Package Manager, product `AlphaVideoGPU`; CocoaPods `alpha-video-gpu` | `AlphaVideoGpuView` |
| Web (plain JS/TS, React) | npm `alpha-video-gpu-web` | `createAlphaVideoGpuPlayer`, `AlphaVideoGpuPlayer` |

See it next to the other formats in the [demo app](#demo-and-related-packages).

## alpha-video-gpu or alpha-video?

alpha-video-gpu doesn't depend on a video codec. There's nothing to decode, so it uses almost no CPU, needs no hardware video decoder, and runs wherever the GPU supports ASTC textures, which covers more devices than VP9 or AV1 hardware decoding. [alpha-video](https://github.com/MediaCacheDev/alpha-video) plays WebM VP9 and AV1 instead, for when file size matters more.

| | [alpha-video](https://github.com/MediaCacheDev/alpha-video) | alpha-video-gpu (this package) |
|---|---|---|
| **Format** | WebM: VP9 with alpha, or dual-stream VP9/AV1 | KTX2: an ASTC texture array, one layer per frame |
| **Made with** | ffmpeg, or any tool that exports alpha WebM | [alpha-video-gpu-encoder](https://github.com/MediaCacheDev/alpha-video-gpu-encoder) (`texenc`) |
| **Decoding** | Every frame: libvpx or dav1d on the CPU, or MediaCodec on Android | None |
| **CPU and battery** | A decode per frame, or one pass up front with `mode="preload"` | Near zero |
| **What the device needs** | Nothing for software decoding. Hardware decoding needs a VP9 or AV1 decoder (Android only), and each player takes 2 of its few instances | ASTC texture support: every iPhone and iPad since the A8 (2014), nearly every Android GPU, Apple silicon Macs and mobile browsers |
| **Many players at once** | Limited by the CPU, or by the hardware decoder's instances | Dozens: each one is just a texture |
| **File size** | Smallest | Several times larger |
| **Seeking** | Decodes from the previous keyframe | Any frame directly |
| **Web** | `<video>` in Chrome and Firefox | WebGL where the browser has ASTC |
| **Best for** | Long or large clips, downloads where size matters, files from standard video tools | UI effects, stickers and glows, many simultaneous loops, short to medium videos |

[Video Codecs vs GPU Texture Streaming](#video-codecs-vs-gpu-texture-streaming) below goes through the trade-offs in detail. [alpha-video-ffmpegkit](https://github.com/MediaCacheDev/alpha-video-ffmpegkit) was the first attempt at transparent WebM, on FFmpegKit, and stays as a reference; use alpha-video for WebM.

## Overview

This library renders videos from KTX2 files that hold an ASTC texture array, one frame per layer. The ASTC blocks go to the GPU as they are, and the GPU decodes them while sampling.

**Key characteristics:**
- No per-frame decoding: frames are uploaded as compressed ASTC blocks
- Hardware texture compression on iOS, Android and the mobile web
- Software ASTC decoding on emulators and devices without ASTC
- Metadata embedded in the KTX2 file by the encoder
- **Streaming playback** for long videos with minimal memory footprint
- **Uncompressed ASTC streaming** for many concurrent players

---

## Video Codecs vs GPU Texture Streaming

This section explains why GPU texture streaming exists and when to use it instead of traditional video codecs.

### The Traditional Video Pipeline

Video codecs (H.264, VP9, AV1) are designed for maximum compression:

```
Encoded File → CPU Decode → YUV/YUVA → Convert to RGBA → Upload to GPU → Render
     │              │            │              │              │
   Small        Heavy CPU     Planar        Per-frame      Bandwidth
   files        per frame     format        conversion     limited
```

**Per-frame work:**
1. **Decode** - CPU decompresses each frame using complex algorithms (motion compensation, DCT, entropy coding)
2. **Color convert** - YUV→RGBA conversion (or YUVA for alpha)
3. **GPU upload** - Transfer decoded pixels to GPU memory every frame

**Characteristics:**
- Excellent compression (10-50x smaller than raw)
- High CPU usage during playback
- Battery drain on mobile
- Latency from decode pipeline
- Alpha support varies (VP9/AV1 support YUVA, H.264 does not)

### The GPU Texture Pipeline

GPU texture streaming pre-compresses frames into GPU-native formats:

```
KTX2 File → Zstd Decompress → ASTC Data → Direct GPU Upload → Render
    │             │               │               │
  Larger      ~1GB/s CPU      Already GPU      Zero CPU
  files       (trivial)        format         per frame
```

**Per-frame work:**
1. **Decompress** - Zstd runs at ~1GB/s, trivial CPU load
2. **Upload** - ASTC data goes directly to GPU (no conversion)
3. **Render** - GPU decompresses ASTC in hardware during sampling

**Characteristics:**
- Moderate compression (3-10x smaller than raw, larger than video codecs)
- Near-zero CPU usage during playback
- Minimal battery drain
- No decode latency
- Native alpha support (ASTC includes alpha channel)

### Side-by-Side Comparison

| Aspect | Video Codec (VP9/AV1) | GPU Texture (ASTC) |
|--------|----------------------|-------------------|
| **File size** | Smallest (10-50x compression) | Larger (3-10x compression) |
| **CPU per frame** | High (decode + convert) | Near-zero (GPU does decompression) |
| **Battery impact** | Significant | Minimal |
| **Memory bandwidth** | RGBA upload every frame | Compressed upload, GPU expands |
| **Alpha support** | VP9/AV1 only (YUVA) | Native (ASTC 4-channel) |
| **Startup latency** | Codec init + first decode | Immediate |
| **Concurrent players** | CPU-limited (2-4 typical) | GPU-limited (10-50+ typical) |
| **Hardware decode** | Requires video decoder chip | Uses texture sampling hardware |

### When to Use Each

**Use video codecs (alpha-video) when:**
- File size is critical (bandwidth-constrained)
- Playing 1-2 videos at a time
- Content is long-form (minutes+)
- Device has hardware video decoder

**Use GPU textures (alpha-video-gpu) when:**
- Many simultaneous videos (UI effects, particles, stickers)
- Battery/CPU usage is critical
- Low latency is required
- Content is short-to-medium (seconds to ~1 minute)
- Consistent cross-platform behavior needed

### The Math

For a 30fps, 400×400 video with alpha:

**Video codec (VP9 YUVA):**
- File: ~50KB/sec of video
- CPU: Decode + YUV→RGBA = ~5-15ms per frame
- Memory: 400×400×4 = 640KB RGBA uploaded per frame
- 10 concurrent = 50-150ms CPU per frame (drops frames)

**GPU texture (ASTC 6×6):**
- File: ~200KB/sec of video (4x larger)
- CPU: Zstd decompress = ~0.2ms per frame
- Memory: 400×400×0.44 = 71KB ASTC uploaded per frame (GPU expands)
- 10 concurrent = 2ms CPU per frame (smooth 60fps)

### Streaming Modes

Both approaches support streaming for long content:

| Mode | Video Codec | GPU Texture |
|------|-------------|-------------|
| **Buffering** | Decode ahead into frame buffer | Decompress ahead into ring buffer |
| **Memory** | Decoded RGBA frames in RAM | Compressed ASTC in ring buffer |
| **Seeking** | Keyframe-based | Frame-accurate (every frame is a keyframe) |

---

## Encoding

Files are made with the separate [`alpha-video-gpu-encoder`](https://github.com/MediaCacheDev/alpha-video-gpu-encoder) package (CLI `texenc`), which compresses frames to ASTC with `toktx` from KTX-Software (or `astcenc` for the stream encoder).

```bash
npm install -g alpha-video-gpu-encoder

# ASTC texture array, KTX2-internal zstd (the default; streams with `stream`)
texenc --input ./frames --output video.ktx2 --fps 30 --astc-quality exhaustive --astc-perceptual

# Uncompressed ASTC (largest file, no decompression; mmap streaming)
texenc --input ./frames --output video.ktx2 --fps 30 --zstd 0
```

See the encoder's README for every option, video input and the memory-efficient stream encoder.

---

## Emulator / Fallback Behavior

On devices and browsers without hardware ASTC support (iOS Simulator, Android Emulator, browsers without `WEBGL_compressed_texture_astc`), the player automatically decodes ASTC textures to RGBA in software, with the same decoder everywhere (compiled to WebAssembly on the web; see [Web Texture Format Support](#web-texture-format-support)).

| Platform | Hardware Support | Fallback |
|----------|------------------|----------|
| iOS Device | ASTC (Metal) | — |
| iOS Simulator | None | Software ASTC→RGBA decode |
| Android Device | ASTC (nearly all) | Software ASTC→RGBA decode |
| Android Emulator | Usually none | Software ASTC→RGBA decode |
| Web (mobile) | ASTC | — |
| Web (desktop) | ASTC where the browser has `WEBGL_compressed_texture_astc` | Software ASTC→RGBA decode (WebAssembly) |

**Important:** On emulators frames are decoded to uncompressed RGBA. There is no GPU compression benefit on emulators—they exist purely for functional testing. Always test performance on real devices. The same goes for browsers without ASTC, where decoding costs CPU time on the main thread and textures take 4 bytes a pixel.

---

## Installation

### React Native

**npm**, package `react-native-alpha-video-gpu`:

```bash
npm install react-native-alpha-video-gpu
cd ios && pod install
```

```tsx
import { AlphaVideoGpuView } from 'react-native-alpha-video-gpu';

<AlphaVideoGpuView
  source={{ uri: 'https://example.com/video.ktx2', cacheKey: 'intro.ktx2' }} // cacheKey: http(s) only
  style={{ width: 300, height: 300 }}
  autoPlay={true}             // the default; false waits for play()
  loop={true}                 // the default; false plays once, then calls onFinished
  paused={false}              // a hold, for example while the screen is hidden
  playbackSpeed={1.0}
  stream={true}               // decodes a window of frames while playing (default); false loads every frame
  memoryStrategy="vram"       // frames on the GPU (default), or 'ram'; see Options
  streamingRingSize={16}      // stream with 'ram': GPU slots, 4 to 64
  streamingCacheSize={60}     // stream: frames kept decoded, 10 to 200
  streamingPrefetchAhead={30} // stream: frames decoded ahead, 5 to 100
  enableFrameEvents={false}   // true calls onFrameUpdate for every frame
  verbose={false}             // true logs info traces; warnings and errors always log
  onLoad={({ nativeEvent }) => console.log(nativeEvent.frameCount, nativeEvent.frameRate)}
  onStateChange={({ nativeEvent }) => console.log(nativeEvent.state)}
  onFrameUpdate={({ nativeEvent }) => console.log(nativeEvent.frame, nativeEvent.totalFrames)}
  onFinished={() => console.log('played once')}
  onError={({ nativeEvent }) => console.error(nativeEvent.error, nativeEvent.code)}
/>
```

The example sets every option; [Options](#options) says when each one takes effect. zstd is compiled in from `third_party/`; there are no other native dependencies. On the web the component uses [`alpha-video-gpu-web`](#web-support), which is installed with it.

The view works on both React Native architectures: the new one (Fabric, the default since 0.76) and the old one (`newArchEnabled=false`, up to 0.81). It is tested on 0.83, and on 0.81 with the new architecture turned off.

### Android

**Maven Central**, artifact `dev.mediacache:alpha-video-gpu`:

```gradle
dependencies {
    implementation 'dev.mediacache:alpha-video-gpu:1.0.0'
}
```

`AlphaVideoGpuView` is a `TextureView` in `dev.mediacache.alphavideo.gpu`. It takes the same sources and options as the React Native component, through setters:

```kotlin
val view = AlphaVideoGpuView(context)
view.setCacheKey("intro.ktx2")         // http(s) only: the download's name; default, a hash of the URL
view.setAutoPlay(true)                 // the default; false waits for play()
view.setLoop(true)                     // the default; false plays once, then calls onFinished
view.setPaused(false)                  // a hold, for example while the screen is hidden
view.setPlaybackSpeed(1.0f)
view.setStream(true)                   // decodes a window of frames while playing (default); false loads every frame
view.setMemoryStrategy("vram")         // frames on the GPU (default), or "ram"; see Options
view.setStreamingRingSize(16)          // stream with "ram": GPU slots, 4 to 64
view.setStreamingCacheSize(60)         // stream: frames kept decoded, 10 to 200
view.setStreamingPrefetchAhead(30)     // stream: frames decoded ahead, 5 to 100
view.setEnableFrameEvents(false)       // true calls onFrameUpdate for every frame
view.setVerbose(false)                 // true logs info traces to Logcat
view.setPlayerCallback(object : AlphaVideoGpuView.PlayerCallback {
    override fun onLoad(width: Int, height: Int, frameCount: Int, frameRate: Double) {}
    override fun onStateChange(state: String) {}
    override fun onFrameUpdate(frame: Int, totalFrames: Int) {}
    override fun onFinished() {}
    override fun onError(error: String) {}
})
view.setSource("https://example.com/video.ktx2")
container.addView(view)
```

Every `PlayerCallback` method has an empty default; `onStateChange` and `onFrameUpdate` (with `setEnableFrameEvents(true)`) are the others. Callbacks arrive on the main thread. `play()`, `pause()`, `stop()`, `seekToFrame()` and `seekToTime()` control playback, and `setPaused()` holds it as described under [Usage](#usage); detaching the view, or `destroy()`, frees it. http(s) sources need the `INTERNET` permission in the app's manifest. Needs `minSdk` 24 and OpenGL ES 3.0.

### iOS

**CocoaPods**, pod `alpha-video-gpu`:

```ruby
pod 'alpha-video-gpu', '1.0.0'
```

**Swift Package Manager**, package `alpha-video-gpu`, product `AlphaVideoGPU`:

```swift
.package(url: "https://github.com/MediaCacheDev/alpha-video-gpu", from: "1.0.0")
// in the target's dependencies:
.product(name: "AlphaVideoGPU", package: "alpha-video-gpu")
```

The module is `AlphaVideoGPU` either way.

`AlphaVideoGpuView` is an Objective-C `MTKView`, so it works from Swift and Objective-C:

```swift
import AlphaVideoGPU

let view = AlphaVideoGpuView(frame: CGRect(x: 0, y: 0, width: 200, height: 200))
view.cacheKey = "intro.ktx2"           // http(s) only: the download's name; default, a hash of the URL
view.autoPlay = true                   // the default; false waits for play
view.loop = true                       // the default; false plays once, then calls onFinished
view.playbackPaused = false            // a hold; MTKView's own `paused` is used by the view
view.playbackSpeed = 1.0
view.stream = true                     // decodes a window of frames while playing (default); false loads every frame
view.memoryStrategy = "vram"           // frames on the GPU (default), or "ram"; see Options
view.streamingRingSize = 16            // stream with "ram": GPU slots, 4 to 64
view.streamingCacheSize = 60           // stream: frames kept decoded, 10 to 200
view.streamingPrefetchAhead = 30       // stream: frames decoded ahead, 5 to 100
view.enableFrameEvents = false         // true calls onFrameUpdate for every frame
view.verbose = false                   // true logs info traces to the Xcode console
view.onLoad = { width, height, frameCount, frameRate in }
view.onStateChange = { state in }
view.onFrameUpdate = { frame, totalFrames in }
view.onFinished = { }
view.onError = { error in }
view.source = "https://example.com/video.ktx2"  // set last: loading starts at once
container.addSubview(view)
```

`MTKView` already has `paused` and `delegate`, which the view uses itself: pause playback with `playbackPaused`, control it with `play`, `pause`, `stop`, `seekToFrame:` and `seekToTime:`, and get events through the `onLoad`, `onFrameUpdate`, `onStateChange`, `onFinished` and `onError` blocks, which are called on the main queue. Setting `source` to `nil` stops playback and frees the textures. iOS 13 or later.

---

## Usage

```jsx
import { useRef } from 'react';
import { AlphaVideoGpuView } from 'react-native-alpha-video-gpu';

function MyComponent() {
  const player = useRef(null);  // player.current?.seekToTime(1.5), play(), pause(), stop(), seekToFrame(n)
  return (
    <AlphaVideoGpuView
      ref={player}
      source={{ uri: 'https://example.com/video.ktx2', cacheKey: 'intro.ktx2' }} // cacheKey: http(s) only
      style={{ width: 300, height: 300 }}
      autoPlay={true}             // the default; false waits for play()
      loop={true}                 // the default; false plays once, then calls onFinished
      paused={false}              // a hold, for example while the screen is hidden
      playbackSpeed={1.0}
      stream={true}               // decodes a window of frames while playing (default); false loads every frame
      memoryStrategy="vram"       // frames on the GPU (default), or 'ram'; see Options
      streamingRingSize={16}      // stream with 'ram': GPU slots, 4 to 64
      streamingCacheSize={60}     // stream: frames kept decoded, 10 to 200
      streamingPrefetchAhead={30} // stream: frames decoded ahead, 5 to 100
      enableFrameEvents={false}   // true calls onFrameUpdate for every frame
      verbose={false}             // true logs info traces; warnings and errors always log
      onLoad={({ nativeEvent }) => console.log(nativeEvent.frameCount, nativeEvent.frameRate)}
      onStateChange={({ nativeEvent }) => console.log(nativeEvent.state)}
      onFrameUpdate={({ nativeEvent }) => console.log(nativeEvent.frame, nativeEvent.totalFrames)}
      onFinished={() => console.log('played once')}
      onError={({ nativeEvent }) => console.error(nativeEvent.error, nativeEvent.code)}
    />
  );
}
```

A ref controls playback: `play()`, `pause()`, `stop()` (pauses on the first frame), `seekToFrame(frame)` and `seekToTime(seconds)`. A seek shows the frame, clamped to the clip, and playback carries on from it or stays paused; seeks before `onLoad` are ignored. With `stream`, a seek outside the buffered frames decodes again, from the nearest seek point before it or onwards from the decoder, whichever is closer. Files from the encoder have a seek point every 8 MB of ASTC by default (its `--seek-interval`), so a seek decodes at most that much, a few milliseconds. A file written as one zstd stream (`--seek-interval 0`, or an older encoder) has only the start of the file. Seeking far into one of those decodes everything before the target, in slices that don't block the UI. `autoPlay` sets how a video starts: off, it waits for `play()`. `paused` is a hold to turn on and off, for example while the screen is hidden; unpausing plays again when `autoPlay` is on, and with `autoPlay` off it takes `play()`. A clip that doesn't loop stops on its last frame, and `play()` starts it over.


### Options

The examples under [Installation](#installation), [Usage](#usage) and [Web Support](#web-support) set every option, each to its default. The memory options (`stream`, `memoryStrategy` and the three sizes) are read when a source loads, so set them before the source; on the web, pass them when creating the player. `autoPlay`, `loop`, `paused`, `playbackSpeed`, `enableFrameEvents` and `verbose` can change at any time.

Sizes outside their range are clamped to it. Where the frames live, and which size applies:

| | `stream` off | `stream` on (the default) |
|---|---|---|
| `'vram'` (the default) | Every frame in a GPU texture array | A GPU ring of `streamingCacheSize` frames, decoded straight into it |
| `'ram'` | Every frame in RAM, uploaded to the GPU as it's shown. Android and web; iOS loads every frame into VRAM | `streamingCacheSize` decoded frames in RAM, uploaded into a GPU ring of `streamingRingSize` slots |

`streamingPrefetchAhead` is how far ahead of the playhead the decoder runs, within the cache. See [Playback Modes](#playback-modes) for when to use each.

### Sources

`source.uri` takes one of these forms. Anything else is reported through `onError`.

| Form | iOS | Android | Web | Notes |
|------|-----|---------|-----|-------|
| `https://…`, `http://…` | ✓ | ✓ | ✓ | Downloaded once into the app cache, then read from disk. The web reads it with range requests, keeping what it played in OPFS (or IndexedDB). `source.headers` go with every request (web only for now). |
| `file:///abs/path`, `/abs/path` | ✓ | ✓ | — | Read in place. |
| `asset://path/in/bundle` | ✓ | ✓ | ✓ | Android: APK `assets/`. iOS: the app bundle's resource folder. Web: the site root (`/path/in/bundle`). |
| `content://…` | — | ✓ | — | Read through the `ContentResolver`. |
| bare resource name | — | ✓ | — | A `res/raw` resource, which is what `require()` resolves to in an Android release build. |

Nothing is copied: bundled assets, raw resources and local files are read where they are.

On the web, `source` can also be a `Blob` or `File`, or a byte source, `{ size, read(offset, length, signal) }`, that reads the file from the app's own code (a fetcher, a cache, decryption). See [the web package's README](web/README.md#sources-and-storage).

#### Cache keys

Downloads are named by `cacheKey` if you give one, otherwise by a hash of the URL. Set `cacheKey` when the URL changes while the file doesn't (presigned URLs), so a new URL still hits the cache and doesn't reload the view:

```jsx
<AlphaVideoGpuView source={{ uri: presignedUrl, cacheKey: 'videos/glow.ktx2' }} />
```

`cacheKey` can also be passed as a top-level prop; it wins over `source.cacheKey`. Native downloads go to the app's cache directory (`<cache>/<cacheKey>`, or `<cache>/alpha-video-gpu/<sha256>`), so the OS may clear them. On the web, stored clips are named the same way, and the browser may evict them.

#### Bundled with `require()`

Register the extensions with Metro:

```js
// metro.config.js
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

const defaultConfig = getDefaultConfig(__dirname);
module.exports = mergeConfig(defaultConfig, {
  resolver: {
    assetExts: [...defaultConfig.resolver.assetExts, 'ktx2'],
  },
});
```

Then resolve the module to a URI:

```jsx
import { Image } from 'react-native';

const video = Image.resolveAssetSource(require('./assets/video.ktx2'));

<AlphaVideoGpuView source={{ uri: video.uri }} />
```

In debug builds that URI is the Metro dev server (`http://…`, downloaded and cached like any URL). In release builds it's a `file://` path in the iOS bundle, or a `res/raw` name on Android. On web, `Image.resolveAssetSource` doesn't exist; use the module value directly when it's a string, or `expo-asset`'s `Asset.fromModule(module).uri`.

#### Streaming from Android assets and resources

Android compresses APK entries it doesn't recognise, and a compressed entry can't be memory-mapped. Non-streaming playback (`stream={false}`) reads it anyway, but streaming, the default, needs the file stored uncompressed, and reports `onError` otherwise:

```groovy
// android/app/build.gradle
android {
  androidResources {
    noCompress += ['ktx2']
  }
}
```

Without it, `stream` on an `asset://` or `res/raw` file fails with an error naming this setting.

---

## Metadata

Video metadata is embedded by the encoder in the KTX2 file's key-value data, under the key `BasisPlayerMetadata` (the name is kept so older files still read). The player reads it automatically:

```json
{
  "frameCount": 120,
  "frameWidth": 400,
  "frameHeight": 400,
  "frameRate": 30,
  "mode": "astc",
  "packingMode": "array",
  "layerCount": 120
}
```

Files from older encoders in other modes (UASTC, atlas packing, GLB containers, multi-part files) are rejected with an error; encode them again with `texenc`.


---

## Web Support

On React Native Web, `AlphaVideoGpuView` draws with the WebGL player from [`alpha-video-gpu-web`](web). For other web apps, install that package on its own:

```bash
npm install alpha-video-gpu-web
```

```ts
import { createAlphaVideoGpuPlayer } from 'alpha-video-gpu-web';

const player = createAlphaVideoGpuPlayer(document.querySelector('canvas')!, {
  autoPlay: true,              // false waits for play()
  loop: true,                  // false plays once, then calls onFinished
  playbackSpeed: 1.0,
  stream: true,                // decodes a window of frames while playing (default); false loads every frame
  memoryStrategy: 'vram',      // frames on the GPU (default), or 'ram'; see Options
  streamingRingSize: 16,       // stream with 'ram': GPU slots, 4 to 64
  streamingCacheSize: 60,      // stream: frames kept decoded, 10 to 200
  streamingPrefetchAhead: 30,  // stream: frames decoded ahead, 5 to 100
  frameEvents: true,           // the web default; false calls onFrameUpdate only after seeks
  verbose: false,              // true logs info traces to the browser console
  storage: 'auto',             // where played bytes are kept: OPFS, else IndexedDB; or 'indexeddb', 'cache', 'memory', 'none'
  maxStorageBytes: 512 * 1024 * 1024, // this player's cap across every clip, least recently played evicted first
  onLoad: ({ width, height, frameCount, frameRate }) => {},
  onStateChange: (state) => {},
  onFrameUpdate: (frame, total) => {},
  onFinished: () => {},
  onError: (error) => console.error(error),  // an AlphaVideoGpuError; see its `code`
});
player.setSource('https://example.com/video.ktx2', { cacheKey: 'intro.ktx2', headers: {} });  // also a Blob, bytes or a byte source
player.setPaused(false);
// player.play(), player.pause(), player.resume(), player.stop(), player.seekToFrame(n),
// player.seekToTime(seconds), player.destroy()
// Changeable later: player.autoPlay, .loop, .playbackSpeed, .frameEvents, .verbose
```

```tsx
import { AlphaVideoGpuPlayer } from 'alpha-video-gpu-web/react';

<AlphaVideoGpuPlayer
  src="https://example.com/video.ktx2"
  cacheKey="intro.ktx2"          // names the stored copy; default, the URL
  width={300}
  height={300}
  className="video"
  style={{ display: 'block' }}
  autoPlay={true}
  loop={true}
  paused={false}
  playbackSpeed={1.0}
  stream={true}
  memoryStrategy="vram"
  streamingRingSize={16}
  streamingCacheSize={60}
  streamingPrefetchAhead={30}
  verbose={false}
  onLoad={({ frameCount }) => {}}
  onStateChange={(state) => {}}
  onFrameUpdate={(frame, total) => {}}
  onFinished={() => {}}
  onError={(error) => console.error(error)}
/>
```

The zstd decoder (JS and WebAssembly) is bundled inline, so no extra scripts or files need to be served. React is an optional peer dependency, needed only for `alpha-video-gpu-web/react`.

### Web Texture Format Support

| Format | Browser Support | Notes |
|--------|-----------------|-------|
| **ASTC** | All with WebGL 2 | Sampled by the GPU with `WEBGL_compressed_texture_astc`, else decoded to RGBA in WebAssembly |
| **Zstd compression** | All | Bundled WASM decoder |

Without `WEBGL_compressed_texture_astc`, frames are decoded to RGBA as they're uploaded, by a 42 KB WebAssembly decoder that only those browsers download. Preloaded playback then keeps the clip as ASTC and decodes the shown frame; [the web package's README](web/README.md#without-gpu-astc) has the costs. If WebAssembly is blocked too (a Content-Security-Policy without `'wasm-unsafe-eval'`), `onError` gets an `AlphaVideoGpuError` whose `code` is `'ASTC_UNSUPPORTED'` (`nativeEvent.code` in the React Native component); show something else in its place.

---

## Playback Modes

The player supports multiple playback strategies optimized for different use cases.

### Memory Strategy: VRAM vs RAM

The `memoryStrategy` prop controls where the main frame buffer is located. The goal is to **maximize** usage of one memory type while **minimizing** the other:

| Strategy | Main Buffer | Behavior | Performance |
|----------|-------------|----------|-------------|
| `vram` (default) | GPU VRAM | Maximizes VRAM, minimizes RAM | **Generally preferred** - frames are decoded straight into GPU textures, outside the app's heap |
| `ram` | CPU RAM | Maximizes RAM, minimizes VRAM | Use when VRAM is constrained - but note that high RAM usage (1GB+) can cause app slowdowns |

On iOS, playback without `stream` always keeps every frame in VRAM.

**Why `vram` is the default:** when streaming, it holds `streamingCacheSize` frames on the GPU and almost nothing in RAM, where `ram` holds the same frames in RAM plus a GPU ring of `streamingRingSize`. So it uses less memory in total and copies each frame once, and on Android the upload runs asynchronously through a pixel buffer. Its frames stay out of the Java heap on Android (whose limit is often 256–512 MB) and the JavaScript heap on the web. Phones share physical memory between CPU and GPU, so on iOS the textures still count toward the app's memory limit, but `vram` still uses less of it. The ring is allocated as one texture array when the source loads; a clip too large for it reports `onError`.

### Non-Streaming

With `stream={false}`, all frames are loaded into memory before playback begins. Best for short-to-medium videos.

```jsx
<AlphaVideoGpuView
  source={{ uri: 'https://example.com/video.ktx2' }}
  stream={false}
  autoPlay
  loop
/>
```

**Non-streaming memory behavior:**

| Strategy | Description |
|----------|-------------|
| `vram` | All frames loaded directly to GPU VRAM, skipping RAM entirely. Fastest playback, instant frame switching. |
| `ram` | All frames held in CPU RAM, streamed to a small VRAM buffer on demand. Minimizes VRAM while RAM holds all data. |

```jsx
<AlphaVideoGpuView
  source={{ uri: 'https://example.com/video.ktx2' }}
  stream={false}
  memoryStrategy="ram"
  autoPlay
  loop
/>
```

### Streaming Mode (Default)

For very long videos or memory-constrained scenarios, streaming mode decodes frames on-demand during playback using a ring buffer. Only a small window of frames is kept in memory at any time, providing **constant and predictable memory usage** regardless of video length.

Streaming is on unless `stream` is `false`, for files with KTX2-internal zstd (the encoder default) or no compression (`--zstd 0`).

The `streamMode` metadata key that older encoders wrote is ignored; only the `stream` prop decides.

```jsx
<AlphaVideoGpuView
  source={{ uri: 'https://example.com/video.ktx2' }}
  stream
  memoryStrategy="vram"
  streamingRingSize={16}
  streamingCacheSize={60}
  streamingPrefetchAhead={30}
/>
```

#### Streaming Props

| Prop | Default | Description |
|------|---------|-------------|
| `stream` | `true` | Stream a texture array instead of loading every frame |
| `memoryStrategy` | `"vram"` | `"vram"` = GPU ring buffer (minimizes RAM), `"ram"` = CPU cache + GPU ring buffer (minimizes VRAM) |
| `streamingRingSize` | `16` | RAM mode: GPU texture slots that frames are uploaded into (4–64). Unused in VRAM mode |
| `streamingCacheSize` | `60` | Frames kept decoded (10–200): in CPU memory in RAM mode, and as the GPU ring's slots in VRAM mode |
| `streamingPrefetchAhead` | `30` | Frames decoded ahead of the playback position (5–100) |

#### VRAM Streaming (Default)

Frames are decoded directly into a GPU texture ring buffer of `streamingCacheSize` slots. Minimal CPU RAM usage - only the ring buffer exists in VRAM.

```
[GPU Ring Buffer: 60 slots]
Slot 0: Frame 100  ← Currently displaying
Slot 1: Frame 101
Slot 2: Frame 102
...
Slot 59: Frame 159 ← Prefetched ahead
```

#### RAM Streaming

Frames are first decoded to a CPU RAM cache of `streamingCacheSize` frames, then uploaded to a smaller GPU ring buffer of `streamingRingSize` slots on demand. Minimizes VRAM usage while providing a larger decode-ahead buffer.

```
[CPU Cache: 60 frames] → [GPU Ring Buffer: 16 slots]
Frames 80-139 cached    Frames 100-119 in GPU
```

#### Why Streaming?

Streaming enables playback of arbitrarily long videos with **fixed memory footprint**:
- A 10,000 frame video uses the same memory as a 100 frame video
- Memory usage is fully configurable via ring buffer and cache size props
- Ideal for long-form content, digital signage, or memory-constrained devices

### Encoding for Streaming

The encoder's defaults (ASTC texture array, KTX2-internal zstd) already produce a streamable file; play it with `stream`.

#### Seeking in zstd files

A zstd decoder can only start at the beginning of a zstd frame. By default the encoder starts a new frame every 8 MB of ASTC and ends the level with a seek table in zstd's [seekable format](https://github.com/facebook/zstd/tree/dev/contrib/seekable_format). A streaming seek then starts at the frame before its target, unless carrying on from the decoder is closer. On an M4 Pro the slowest seek took 6 ms, against 1.5 s (5 minutes of 360p) and 2.9 s (60 s of 1080p) for the same clips as one stream; the 8 MB seek points cost 0.8% and 1.6% in size.

`--seek-interval 0` writes one stream instead, the smallest file, for clips that only play and loop. Players that don't know the table, and every other zstd tool, read both layouts as ordinary zstd. This player reads one-stream files too, and seeks in them by decoding from the start.

---

## Uncompressed ASTC Streaming

For maximum playback performance and massive concurrency, uncompressed ASTC streaming eliminates all runtime Zstd decompression overhead. The KTX2 file contains raw ASTC texture data that can be uploaded directly to the GPU.

### When to Use

- **50-100+ simultaneous players** on a single device
- **Battery-critical applications** (kiosks, digital signage)
- **Guaranteed smooth playback** regardless of device CPU
- **Storage is not a constraint** (local assets, fast network)

### Performance Characteristics

| Metric | Zstd compressed | Uncompressed (`--zstd 0`) |
|--------|-----------------|---------------|
| File size | ~5 MB | ~29 MB (6x larger) |
| CPU during playback | Zstd decode + upload | Upload only |
| Concurrent players | 10-20 typical | 50-100+ tested |
| Battery impact | Moderate | Minimal |
| Memory bandwidth | Lower | Higher |

### Encoding Uncompressed ASTC

```bash
texenc --input ./frames --output video_uncompressed.ktx2 --fps 30 --zstd 0
```

`--zstd 0` leaves the ASTC blocks uncompressed, so the player can memory-map the file and upload frames without decompressing them.

### Usage

```jsx
<AlphaVideoGpuView
  source={{ uri: 'https://example.com/video_uncompressed.ktx2' }}
  stream
  memoryStrategy="ram"
  streamingRingSize={16}
/>
```

### How It Works

1. **Memory-mapped file access** - The KTX2 file is mmap'd, allowing the OS to page frames in/out efficiently
2. **Direct GPU upload** - ASTC blocks are copied directly to GPU texture memory (no Zstd decompression needed)
3. **Ring buffer playback** - Only `streamingCacheSize` frames (`streamingRingSize` with `memoryStrategy="ram"`) are in GPU memory at once
4. **Zero CPU decode** - On real devices with ASTC hardware, CPU usage approaches zero during playback

### Trade-offs

| Pros | Cons |
|------|------|
| Near-zero CPU usage | 5-6x larger file size |
| Scales to 50-100+ concurrent players | Higher storage/bandwidth requirements |
| Consistent frame timing | Not suitable for slow networks |
| Lower battery consumption | Larger app bundle if bundled |
| No decode latency on startup | — |

### Simulator Behavior

On iOS/Android simulators (no hardware ASTC), uncompressed streaming still requires software ASTC→RGBA decode. The player performs this decode on a background thread to keep the UI responsive, but performance will be significantly lower than on real devices.

---

## Performance Characteristics

- **CPU:** Near-zero during playback (frame uploads only, or none when every frame is in VRAM)
- **Memory:** GPU texture memory = the ASTC size (ASTC decompresses on GPU)
- **Battery:** Significantly lower than video playback
- **Load time:** Direct upload of the ASTC blocks

### Playback Mode Comparison

| Mode | Load Time | Memory | CPU (Playback) | Best For |
|------|-----------|--------|----------------|----------|
| Non-streaming VRAM | Slow (all frames) | High VRAM, minimal RAM | Near-zero | Short videos, best performance |
| Non-streaming RAM | Slow (all frames) | High RAM, minimal VRAM | Low (uploads) | VRAM-constrained devices |
| Streaming VRAM (Zstd) | Fast (prefetch) | Low VRAM ring buffer | Medium (decode) | Long videos, fixed memory |
| Streaming RAM (Zstd) | Fast (prefetch) | Medium RAM cache, minimal VRAM | Medium (decode) | Long videos, VRAM-constrained |
| Streaming Uncompressed | Fast (mmap) | Low VRAM ring buffer | Near-zero | Massive concurrency, battery-critical |

Best suited for:
- Looping UI effects (glows, shimmers, highlights)
- Transparent overlays
- Kiosk/always-on displays
- Any video where consistent frame timing matters
- **High-concurrency scenarios** (50-100+ simultaneous videos)

Not ideal for:
- Long-form video content
- Audio-synced playback
- Bandwidth-constrained streaming (use Zstd compression)

---

## Props Reference

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `source` | `{ uri: string, cacheKey?: string, headers? }` | — | What to play; see [Sources](#sources). On web also a `Blob` or a byte source |
| `style` | `ViewStyle` | — | Container style |
| `autoPlay` | `boolean` | `true` | Start playback once loaded |
| `loop` | `boolean` | `true` | Loop playback |
| `paused` | `boolean` | `false` | Pause playback (and streaming decompression) |
| `playbackSpeed` | `number` | `1.0` | Speed multiplier |
| `cacheKey` | `string` | — | Cache identity for downloads; overrides `source.cacheKey` |
| `memoryStrategy` | `"vram"` \| `"ram"` | `"vram"` | Where frames are kept; see [Options](#options) |
| `stream` | `boolean` | `true` | Stream a texture array with a ring buffer |
| `streamingRingSize` | `number` | `16` | GPU texture slots for RAM-mode streaming (4–64) |
| `streamingCacheSize` | `number` | `60` | Frames kept decoded while streaming (10–200): in CPU memory in RAM mode, in the GPU ring in VRAM mode |
| `streamingPrefetchAhead` | `number` | `30` | Frames to decode ahead (5–100) |
| `enableFrameEvents` | `boolean` | `false` | Send `onFrameUpdate` for every frame played, not only after seeks |
| `verbose` | `boolean` | `false` | Log info traces (Logcat, Xcode console, browser console); warnings and errors always log |
| `onLoad` | `({ nativeEvent: { width, height, frameCount, frameRate, duration } }) => void` | — | Metadata is known |
| `onStateChange` | `({ nativeEvent: { state } }) => void` | — | `state` is `"loading"`, `"ready"`, `"playing"`, `"paused"` or `"stopped"` |
| `onFrameUpdate` | `({ nativeEvent: { frame, totalFrames } }) => void` | — | After a seek, and for every frame with `enableFrameEvents` |
| `onFinished` | `() => void` | — | A non-looping video reached its last frame |
| `onError` | `({ nativeEvent: { error, code? } }) => void` | — | Loading or playback failed. `code` is set on web only: `'ASTC_UNSUPPORTED'` or `'WEBGL_UNSUPPORTED'` |

The events have the same shape on Android, iOS and web. Playback is controlled with props (`paused`, `autoPlay`, a new `source`) and with the ref (see [Usage](#usage)).

---

## Repository layout

| Path | What | Package |
|---|---|---|
| `android/` | The Android library: `AlphaVideoGpuView`, source loading, and the JNI code for zstd, the software ASTC decoder and texture uploads (`src/main/cpp`). Builds on its own (`./gradlew` in that folder) | `dev.mediacache:alpha-video-gpu` |
| `ios/Sources/AlphaVideoGPU/` | The iOS library: `AlphaVideoGpuView` (Objective-C++), with its public header in `include/` | SwiftPM `AlphaVideoGPU` (`Package.swift`), CocoaPods `alpha-video-gpu` (`alpha-video-gpu.podspec`) |
| `third_party/zstd/` | zstd 1.4.9 (single-file library), compiled into both native libraries | |
| `react-native/` | `src/` (the component), `android/` (the view manager; its Gradle module compiles `android/` too), `ios/` (a subclass of the iOS view with React props) | npm `react-native-alpha-video-gpu` (`package.json`, `react-native-alpha-video-gpu.podspec`) |
| `web/` | The WebGL player for browsers, with its React wrapper. `npm run build` there builds `dist/` | npm `alpha-video-gpu-web` |
| `scripts/publish-android.sh` | `local` publishes the Android library to `~/.m2`; `central` signs it and uploads it to the Maven Central Portal (see the script for the variables) | |

A clone builds with no setup step, since the decoders are in the repo (`third_party/` for the native libraries, `web/src/` for the web package):

```bash
cd web && npm ci && npm run build                     # web: dist/
cd android && ./gradlew assembleRelease               # Android: the AAR (needs the Android SDK and NDK)
xcodebuild -scheme AlphaVideoGPU -destination 'generic/platform=iOS Simulator' build   # iOS, from the repo root
```

Tests that run on the host, on the fixtures in `tests/fixtures/` (test-card encodes, described in `expected.json`):

```bash
cd web && npm test                                    # web: parsers, seek table, streaming zstd and uncompressed reads and seeks
cd android && ./gradlew testReleaseUnitTest           # Android: the seek table parser (JUnit)
clang -O1 -Ithird_party -Iios/Sources/AlphaVideoGPU ios/Tests/zstd_seek_test.c third_party/zstd/zstd.c -o /tmp/zstd_seek_test && /tmp/zstd_seek_test   # iOS: seek table, reads and seeks
```

`web/test/browser/` plays the cards in a browser (see the web README); the demo app's pixel tests play them on Android and iOS.

A release is the tag `v<version>`: Swift Package Manager resolves it, and `alpha-video-gpu.podspec` fetches its sources from it. After pushing the tag, `pod trunk push alpha-video-gpu.podspec` (only while trunk accepts new versions). Publish `alpha-video-gpu-web` to npm before `react-native-alpha-video-gpu`, which depends on it.

## Requirements

- React Native 0.71+
- Android: minSdkVersion 24, OpenGL ES 3.0; compileSdkVersion comes from the app (default 36)
- iOS: iOS 13.0+, Metal
- Web: WebGL 2; ASTC on the GPU needs `WEBGL_compressed_texture_astc`, and without it WebAssembly

## Demo and related packages

[alpha-video-test](https://github.com/MediaCacheDev/alpha-video-test) is a React Native app (Android, iOS and web) that plays the same video in each of these formats side by side, with FPS, CPU and memory readouts, so you can compare them on your own device.

- [alpha-video-gpu-encoder](https://github.com/MediaCacheDev/alpha-video-gpu-encoder): makes the KTX2 files for this player (`texenc`).
- [alpha-video](https://github.com/MediaCacheDev/alpha-video): WebM VP9 and AV1 with alpha, decoded in software by a shared C++ core or in hardware by MediaCodec on Android. The smallest files.
- [alpha-video-ffmpegkit](https://github.com/MediaCacheDev/alpha-video-ffmpegkit): the first attempt at WebM alpha, on FFmpegKit, kept as a reference.
- [react-native-apng-avif](https://github.com/MediaCacheDev/react-native-apng-avif): animated APNG and AVIF with alpha.
- [alpha-glow-button](https://github.com/MediaCacheDev/alpha-glow-button): buttons whose glow is a transparent video.

---

## License

The code in this repository is MIT. The ASTC decoder is Apache-2.0, and zstd is BSD-3-Clause; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
