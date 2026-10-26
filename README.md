# alpha-video-gpu

<p align="center">
  <img src="docs/alpha-video-gpu.avif" alt="Transparent ASTC videos playing over a checkerboard in the demo app on Android" width="360">
  <br>
  <a href="https://github.com/MediaCacheDev/alpha-video-test">Compare transparent formats in the demo app</a>
</p>

Plays transparent (alpha) videos stored as ASTC texture arrays in KTX2 files, drawn with Metal on iOS, OpenGL ES on Android and WebGL on the web. Frames go to the GPU without being decoded, so CPU and battery use stay low. It comes as a React Native component, as standalone Android and iOS libraries, and as a web package.

| Platform | Package | View |
|---|---|---|
| React Native (Android, iOS, web) | npm `react-native-alpha-video-gpu` | `AlphaVideoGpuView` |
| Android | Maven `dev.mediacache:alpha-video-gpu` | `dev.mediacache.alphavideo.gpu.AlphaVideoGpuView` |
| iOS | Swift Package Manager, product `AlphaVideoGPU` (`AlphaVideoGPUCore` without range-source); CocoaPods `alpha-video-gpu` | `AlphaVideoGpuView` |
| Web (plain JS/TS, React) | npm `alpha-video-gpu-web` | `createAlphaVideoGpuPlayer`, `AlphaVideoGpuPlayer` |

See it next to the other formats in the [demo app](#demo-and-related-packages).

## alpha-video-gpu or alpha-video?

alpha-video-gpu doesn't depend on a video codec. The GPU samples the frames as they are stored, so playback takes little CPU, needs no hardware video decoder, and runs wherever the GPU supports ASTC textures, which covers more devices than VP9 or AV1 hardware decoding. [alpha-video](https://github.com/MediaCacheDev/alpha-video) plays WebM VP9 and AV1 instead, for when file size matters more.

| | [alpha-video](https://github.com/MediaCacheDev/alpha-video) | alpha-video-gpu (this package) |
|---|---|---|
| **Format** | WebM: VP9 with alpha, or dual-stream VP9/AV1 | KTX2: an ASTC texture array, one layer per frame |
| **Made with** | ffmpeg, or any tool that exports alpha WebM | [alpha-video-gpu-encoder](https://github.com/MediaCacheDev/alpha-video-gpu-encoder) (`texenc`) |
| **Decoding** | Every frame: libvpx or dav1d on the CPU, or MediaCodec on Android | None |
| **CPU and battery** | A decode per frame, or one pass up front with `mode="preload"` | Low: zstd at most, and the GPU does the rest |
| **What the device needs** | Nothing for software decoding. Hardware decoding needs a VP9 or AV1 decoder (Android only), and each player takes 2 of its few instances | ASTC texture support: every iPhone and iPad since the A8 (2014), nearly every Android GPU, Apple silicon Macs and mobile browsers |
| **Many players at once** | Limited by the CPU, or by the hardware decoder's instances: 20 small loops saturated a budget phone | Dozens: 50 small uncompressed loops used under half of the same phone's CPU |
| **File size** | Smallest | About 4 to 6 times larger at similar quality |
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
- Frame size, count and rate from standard KTX2 fields (see [Metadata](#metadata))
- **Streaming playback** for long videos with minimal memory footprint
- **Uncompressed ASTC streaming** for many concurrent players

### Drawing size

Each frame is stored at one size, without mipmaps (the smaller, pre-filtered copies a GPU uses when it shrinks a texture), and the player samples it with bilinear filtering. At 100%, one texel per pixel on screen, or enlarged, frames look as clean as the source. Shrunk to less than half their size, each pixel on screen comes from the four texels nearest it and the rest are skipped, so edges turn jagged and fine detail sparkles as the clip moves, although the frames themselves are clean. The same clip played as video and scaled down the same way can look smoother.

So draw ASTC at 100% where you can: encode each clip at the size it appears on screen, in physical pixels (a 150 pt view on a 3x screen is 450 px). Down to half size still looks fine.

---

## Video Codecs vs GPU Texture Streaming

ASTC makes a different trade-off from video codecs. VP9 and AV1 spend CPU on every frame to make files as small as possible. ASTC is a format the GPU samples directly, so playback takes as little CPU and memory traffic as possible and leaves the CPU to the rest of the app, even with many animations on screen. The cost is file size.

```
VP9 / AV1:  file → decode on the CPU → YUV → convert to RGBA → upload → draw
ASTC:       file → zstd (or nothing) → upload as is → draw (the GPU decodes while sampling)
```

The measurements below use two clips: a glow (400×400, 181 frames, 30 fps, mostly transparent) and a manta ray (1920×1080, 424 frames, 29.97 fps, mostly opaque footage). Each was encoded from the same frames into every format.

### File size

| Clip | VP9 (CRF 18) | AV1 (CRF 28) | ASTC 4x4 | ASTC 6x6 | ASTC 8x8 |
|------|-----:|-----:|-----:|-----:|-----:|
| Glow | 1.0 MB | 0.60 MB | 5.5 MB | 2.5 MB | 1.4 MB |
| Manta | 7.0 MB | 6.4 MB | 95 MB | 63 MB | 38 MB |

At similar quality (PSNR), ASTC came out about 4 times the size of VP9 for the glow and 5.5 times for the manta. The encoder's README has the full tables, with quality at each block size and video at two quality settings.

### On a budget Android phone

Measured on a Samsung Galaxy A52s (Snapdragon 778G, 6 GB, Android 14), an inexpensive phone from 2021, in Release builds of the [demo app](#demo-and-related-packages). CPU is the whole phone's, as cores kept busy, so it includes Android's media processes and compositing; the phone has 8 cores. The phone has a VP9 hardware decoder but no AV1 one.

**One player** (each clip looping on its own, CPU above an empty screen):

| Clip | ASTC 4x4 | ASTC 8x8 | VP9, libvpx | VP9, MediaCodec | AV1, dav1d |
|------|-----:|-----:|-----:|-----:|-----:|
| Glow | 0.7 cores | 0.5 cores | 0.7 cores | 1.3 cores | 0.7 cores |
| Manta | 0.8 cores | 0.7 cores | 3.7 cores | 3.9 cores | 3.1 cores |

One small clip costs about the same in any format: most of that is Android redrawing the view each frame. At 1080p, video kept 3 to 4 cores busy and ASTC under one. MediaCodec decodes VP9's colour in hardware, but the alpha plane is still decoded and combined on the CPU.

**Many players** (glow loops in the demo's main screen, each in its own view):

| Players | Format | CPU | App memory | Demo UI |
|---------|--------|----:|-----------:|--------:|
| none | | 0.8 cores | 136 MB | 113 fps |
| 8 | ASTC, zstd | 2.1 cores | 452 MB | 60 fps |
| 8 | VP9, libvpx | 3.4 cores | 298 MB | 54 fps |
| 20 | ASTC, uncompressed | 2.4 cores | 371 MB | 52 fps |
| 20 | ASTC, zstd | 3.1 cores | 908 MB | 52 fps |
| 20 | VP9, libvpx | 8 cores (all) | 462 MB | 15 fps |
| 20 | VP9, decoder `auto` | 8 cores (all) | 428 MB | 3 fps |
| 50 | ASTC, uncompressed | 3.7 cores | 699 MB | 41 fps |
| 50 | ASTC, zstd | 8 cores (all) | 2.1 GB | 3 fps |

"Demo UI" is the frame rate of the demo's own interface on the phone's 120 Hz screen, which shows how much the players leave for the rest of an app. The VP9 here is the demo's glow file (5.7 MB, high quality), so it takes more decoding than the CRF 18 file above. With 20 or more players, use uncompressed files (`--zstd 0`): each zstd player decompresses on its own, and took about 40 MB of memory here, against about 12 MB uncompressed.

### When to use each

**Use video codecs ([alpha-video](https://github.com/MediaCacheDev/alpha-video)) when:**
- Download or app size comes first
- The content is long or high-resolution footage, especially opaque
- Only one or two videos play at a time

**Use ASTC (alpha-video-gpu) when:**
- Many animations play at once (UI effects, stickers, glows)
- The CPU should stay free for the app, or battery use matters
- Seeking and scrubbing need to be frame-exact
- Behaviour should be the same on every platform

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

**npm**, package `react-native-alpha-video-gpu`, with [range-source](#streaming-and-storage) for URLs:

```bash
npm install react-native-alpha-video-gpu react-native-range-source
cd ios && pod install
```

`react-native-range-source` reads URLs by range, keeps them on disk or in memory, and reads [data sources](#data-sources). React Native only links an app's own dependencies, so it's in the install line rather than a dependency of the player. Without it, a URL is downloaded whole for each play ([Without range-source](#without-range-source)). One install serves `react-native-alpha-video` too.

```tsx
import { AlphaVideoGpuView } from 'react-native-alpha-video-gpu';

<AlphaVideoGpuView
  source={{ uri: 'https://example.com/video.ktx2', cacheKey: 'intro.ktx2' }} // cacheKey names the stored copy; default, the URL
  storage="auto"              // where the bytes are kept between plays; see Streaming and storage
  maxStorageBytes={512 * 1024 * 1024} // the store's cap, over every clip in it
  style={{ width: 300, height: 300 }}
  autoPlay={true}             // the default; false waits for play()
  loop={true}                 // the default; false plays once, then calls onFinished
  paused={false}              // a hold, for example while the screen is hidden
  playbackSpeed={1.0}
  stream={true}               // decodes a window of frames while playing (default); false loads every frame
  memoryStrategy="vram"       // frames on the GPU (default), or 'ram'; see Options
  streamingPrefetchAhead={8}  // stream: frames kept decoded ahead, 1 to 100; see Buffering
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

It brings [range-source](https://github.com/MediaCacheDev/range-source) (`dev.mediacache:range-source`, about 320 KB per architecture), which reads URLs by range and keeps them between plays. To leave it out ([Without range-source](#without-range-source)):

```gradle
implementation('dev.mediacache:alpha-video-gpu:1.0.0') {
    exclude group: 'dev.mediacache', module: 'range-source'
}
```

To read videos through a Media3 `DataSource.Factory`, add `dev.mediacache:range-source-media3` ([Data sources](#data-sources)).

`AlphaVideoGpuView` is a `TextureView` in `dev.mediacache.alphavideo.gpu`. It takes the same sources and options as the React Native component, through setters:

```kotlin
val view = AlphaVideoGpuView(context)
view.setCacheKey("intro.ktx2")         // what the stored copy is named; default, the URL
view.setStorage("auto")                // where the bytes are kept between plays; see Streaming and storage
view.setMaxStorageBytes(512L * 1024 * 1024)  // the store's cap, over every clip in it
view.setAutoPlay(true)                 // the default; false waits for play()
view.setLoop(true)                     // the default; false plays once, then calls onFinished
view.setPaused(false)                  // a hold, for example while the screen is hidden
view.setPlaybackSpeed(1.0f)
view.setStream(true)                   // decodes a window of frames while playing (default); false loads every frame
view.setMemoryStrategy("vram")         // frames on the GPU (default), or "ram"; see Options
view.setStreamingPrefetchAhead(8)      // stream: frames kept decoded ahead, 1 to 100; see Buffering
view.setEnableFrameEvents(false)       // true calls onFrameUpdate for every frame
view.setVerbose(false)                 // true logs info traces to Logcat
view.setPlayerCallback(object : AlphaVideoGpuView.PlayerCallback {
    override fun onLoad(width: Int, height: Int, frameCount: Int, frameRate: Double) {}
    override fun onStateChange(state: String) {}
    override fun onFrameUpdate(frame: Int, totalFrames: Int) {}
    override fun onFinished() {}
    override fun onError(error: String, code: String?) {}  // code: AlphaVideoGpuView.ERROR_NO_SOURCE_MODULE, or null
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
.package(url: "https://github.com/MediaCacheDev/alpha-video-gpu", from: "1.0.1")
// in the target's dependencies:
.product(name: "AlphaVideoGPU", package: "alpha-video-gpu")
```

In an Xcode project, add the package (File > Add Package Dependencies) and pick `AlphaVideoGPU` for the app target. The module is `AlphaVideoGPU` either way.

Both bring [range-source](https://github.com/MediaCacheDev/range-source), which reads URLs by range and keeps them between plays: the pod's `Source` subspec, and the package's `AlphaVideoGPU` product. To leave it out ([Without range-source](#without-range-source)), take the pod's core alone, `pod 'alpha-video-gpu', '1.0.0', :subspecs => ['Core']`, or the package's `AlphaVideoGPUCore` product instead of `AlphaVideoGPU`. These are products rather than a package trait because Xcode before 26.4 applies no package traits, default ones included.

`AlphaVideoGpuView` is an Objective-C `MTKView`, so it works from Swift and Objective-C:

```swift
import AlphaVideoGPU

let view = AlphaVideoGpuView(frame: CGRect(x: 0, y: 0, width: 200, height: 200))
view.cacheKey = "intro.ktx2"           // what the stored copy is named; default, the URL
view.storage = "auto"                  // where the bytes are kept between plays; see Streaming and storage
view.maxStorageBytes = 512 * 1024 * 1024  // the store's cap, over every clip in it
view.autoPlay = true                   // the default; false waits for play
view.loop = true                       // the default; false plays once, then calls onFinished
view.playbackPaused = false            // a hold; MTKView's own `paused` is used by the view
view.playbackSpeed = 1.0
view.stream = true                     // decodes a window of frames while playing (default); false loads every frame
view.memoryStrategy = "vram"           // frames on the GPU (default), or "ram"; see Options
view.streamingPrefetchAhead = 8        // stream: frames kept decoded ahead, 1 to 100; see Buffering
view.enableFrameEvents = false         // true calls onFrameUpdate for every frame
view.verbose = false                   // true logs info traces to the Xcode console
view.onLoad = { width, height, frameCount, frameRate in }
view.onStateChange = { state in }
view.onFrameUpdate = { frame, totalFrames in }
view.onFinished = { }
view.onError = { error, code in }      // code: AlphaVideoGpuErrorNoSourceModule, or nil
view.source = "https://example.com/video.ktx2"  // loads on the next main-queue turn
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
      source={{ uri: 'https://example.com/video.ktx2', cacheKey: 'intro.ktx2' }} // cacheKey names the stored copy; default, the URL
      storage="auto"              // where the bytes are kept between plays; see Streaming and storage
      maxStorageBytes={512 * 1024 * 1024} // the store's cap, over every clip in it
      style={{ width: 300, height: 300 }}
      autoPlay={true}             // the default; false waits for play()
      loop={true}                 // the default; false plays once, then calls onFinished
      paused={false}              // a hold, for example while the screen is hidden
      playbackSpeed={1.0}
      stream={true}               // decodes a window of frames while playing (default); false loads every frame
      memoryStrategy="vram"       // frames on the GPU (default), or 'ram'; see Options
      streamingPrefetchAhead={8}  // stream: frames kept decoded ahead, 1 to 100; see Buffering
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

The examples under [Installation](#installation), [Usage](#usage) and [Web Support](#web-support) set every option, each to its default. The memory options (`stream`, `memoryStrategy` and `streamingPrefetchAhead`) and the storage options (`storage`, `maxStorageBytes`, `cacheKey`) are read when a source loads, so set them before the source; on the web, pass them when creating the player, and `cacheKey` with `setSource`. `autoPlay`, `loop`, `paused`, `playbackSpeed`, `enableFrameEvents` and `verbose` can change at any time.

Where the frames live:

| | `stream` off | `stream` on (the default) |
|---|---|---|
| `'vram'` (the default) | Every frame in a GPU texture array | `streamingPrefetchAhead` + 2 frames in a GPU texture array, decoded straight into it |
| `'ram'` | Every frame in RAM, uploaded to the GPU as it's shown. Android and web; iOS loads every frame into VRAM | `streamingPrefetchAhead` + 2 frames in RAM, uploaded into 4 GPU slots as they're shown |

`streamingPrefetchAhead` is the only size to set: how many frames are kept decoded after the shown one, 1 to 100 (values outside are clamped). See [Buffering](#buffering) for the trade-off and [Playback Modes](#playback-modes) for when to use each mode.

### Sources

`source.uri` takes one of these forms. Anything else is reported through `onError`.

| Form | iOS | Android | Web | Notes |
|------|-----|---------|-----|-------|
| `https://…`, `http://…` | ✓ | ✓ | ✓ | Read by range requests as it plays, and kept between plays; see [Streaming and storage](#streaming-and-storage). Without range-source on Android and iOS, downloaded whole. `source.headers` go with every request. |
| anything, with `source.dataSource` | ✓ | ✓ | ✓ | Read by a data source the app registered under that name; see [Data sources](#data-sources). On react-native-web the name is registered with range-source-web; a web app holding the player passes a byte source instead (below). |
| `file:///abs/path`, `/abs/path` | ✓ | ✓ | — | Read in place. |
| `asset://path/in/bundle` | ✓ | ✓ | ✓ | Android: APK `assets/`. iOS: the app bundle's resource folder. Web: the site root (`/path/in/bundle`). |
| `content://…` | — | ✓ | — | Read through the `ContentResolver`. |
| bare resource name | — | ✓ | — | A `res/raw` resource, which is what `require()` resolves to in an Android release build. |

Nothing is copied: bundled assets, raw resources and local files are read where they are.

On the web, `source` can also be a `Blob` or `File`, or a byte source, `{ size, read(offset, length, signal) }`, that reads the file from the app's own code (a fetcher, a cache, decryption). See [the web package's README](web/README.md#sources-and-storage).

#### Streaming and storage

An http(s) source is read by HTTP range requests as it plays, through the same reader on every platform ([range-source](https://github.com/MediaCacheDev/range-source)). It's included on Android and iOS, and in React Native it's `react-native-range-source` ([Installation](#installation)). On Android and iOS:

- With `stream` on and a zstd file (the encoder's default), playback starts after a few small reads: the header, and the seek table at the end of the level. The level is then read from its start, 256 KB at a time as the decoder needs it, and a seek reads from the seek point before its target. A file written as one zstd stream has no seek table, so a far seek reads on from where the decoder is.
- An uncompressed file, and any file with `stream` off, is read whole before it plays, in one request.
- A server without range support works too. The file is then read from the start, and a seek reads it again up to where it lands.
- A dropped connection is retried 3 times, carrying on from where it stopped. A read that still fails is reported through `onError` as `Reading <uri> failed: …`, and playback stops on the last frame shown. A source that can't be opened (a 401, say) reports `Loading <uri> failed: …`.
- If the network is slower than the clip, playback waits for the bytes and the UI stays responsive.
- iOS needs the file's size, which servers send as `Content-Length` or `Content-Range`.

`storage` sets where the bytes are kept between plays:

| Value | Kept |
|---|---|
| `'auto'` (default) | `'disk'` for a URL, `'none'` for a data source |
| `'disk'` | In the app's cache folder: `alpha-video-gpu` in `getCacheDir()` on Android, in `Caches` on iOS. The system may clear it when space runs low |
| `'memory'` | In memory, for the app's lifetime |
| `'none'` | Nowhere: each play fetches again |

Storage is partial: it keeps the ranges played so far, and a clip held whole plays again without the network, offline too. `maxStorageBytes` (default 512 MB) caps the store across every clip, evicting the least recently played. Every view shares one disk store and one memory store, so the cap set last applies to it. A copy is dropped when the server's `ETag`, `Last-Modified` or length no longer matches it. A clip held whole isn't checked again; give a changed file a new `cacheKey` or URL.

Local files, bundled files, `content://` URIs and `res/raw` resources are read in place and never stored. On react-native-web, `'disk'` is the browser's storage, as `'auto'` (OPFS, else IndexedDB); the web package also takes `'opfs'`, `'indexeddb'`, `'cache'` and stores of an app's own ([Web Support](#web-support)).

#### Without range-source

An app that leaves range-source out (to have no network or disk code of ours, or with its own caching) still plays local files, bundled files, `content://` URIs and `res/raw` resources as before. A URL is downloaded whole, with one request from the start, into a temporary file, and plays as it arrives:
- a seek past what has arrived waits for it;
- loops and seeks back read the file;
- nothing is kept between plays, so a replay downloads it again;
- `headers` are sent.

`storage`, `maxStorageBytes` and `cacheKey` have no effect, and the player logs once that range-source isn't installed. A data source reports `onError` with the code `NO_SOURCE_MODULE` (`AlphaVideoGpuView.ERROR_NO_SOURCE_MODULE` on Android, `AlphaVideoGpuErrorNoSourceModule` on iOS), with a message naming the add-on.

#### Cache keys

Stored clips are named by `cacheKey` if you give one, otherwise by the URL. Set `cacheKey` when the URL changes while the file doesn't (presigned URLs), so a new URL still finds the stored copy, and doesn't reload a view that is already playing it:

```jsx
<AlphaVideoGpuView source={{ uri: presignedUrl, cacheKey: 'videos/glow.ktx2' }} />
```

`cacheKey` can also be passed as a top-level prop; it wins over `source.cacheKey`.

#### Data sources

An app that fetches, caches or decrypts the bytes itself registers a data source under a name, and names it in `source`. The player asks it for byte ranges as it plays, and passes `uri` and `headers` through, so the URI can be anything the data source understands:

```tsx
<AlphaVideoGpuView source={{ uri: 'https://cdn.example.com/intro.ktx2', dataSource: 'media3' }} />
```

A data source's bytes aren't stored by default, since it usually caches them itself; `storage="disk"` or `"memory"` stores them too. On the native views, call `setDataSource("name")` (Android) or set `dataSource = "name"` (iOS) before the source, with `setHeaders` or `headers`.

Data sources are range-source's, so they need it ([Without range-source](#without-range-source)), and one registered once serves this player and the WebM player ([alpha-video](https://github.com/MediaCacheDev/alpha-video)):

**Android:** implement `dev.mediacache.rangesource.RangeDataSource`. `open(uri, position, headers)` starts a read at `position` and returns the file's size and version (`new Info(size, etag, lastModified)`). `read` and `close` follow, and `cancel` (optional) stops a read blocked on the network from another thread. Register a factory, which makes one per player:

```kotlin
RangeDataSources.register("mine") { MyDataSource() }
```

To read through a Media3 `DataSource.Factory` (OkHttp, Cronet, a `CacheDataSource`), add range-source's adapter, which uses the app's own `androidx.media3:media3-datasource` (compiled against 1.8.0):

```gradle
implementation 'dev.mediacache:range-source-media3:1.0.0'
```

```kotlin
RangeDataSources.register("media3", Media3RangeDataSource.factory(dataSourceFactory))
```

The built-in fetcher is public too, to register with other timeouts or a user agent: `RangeHttpDataSource.Factory().setConnectTimeoutMs(…).setReadTimeoutMs(…).setUserAgent(…)`.

**iOS:** conform to `RangeDataSource`. Each call reads a window of a few megabytes: call `response` once with the file's size and version, then `data` with the bytes in order, then `completion`. One instance serves every player, so a read mustn't depend on the one before:

```swift
import RangeSource

final class MyDataSource: NSObject, RangeDataSource {
    func read(url: URL, offset: Int64, length: Int64, headers: [String: String],
              response: @escaping (RangeDataInfo) -> Void,
              data: @escaping (Data) -> Void,
              completion: @escaping (Error?) -> Void) -> RangeCancellable {
        // response(RangeDataInfo(size: total, etag: etag, lastModified: nil)), data(…), completion(nil)
    }
}

RangeDataSources.register(MyDataSource(), name: "mine")
```

**React Native:** register in native code, as above, in `MainApplication` (Android) and `AppDelegate` (iOS, `import RangeSource`). On react-native-web, register a function that makes a byte source for a URI with range-source-web (1.2.0 or later): `RangeDataSources.register('mine', (uri, { headers }) => myByteSource(uri, headers))`.

**MediaCache** is a data source on Android, iOS, React Native (react-native-web included) and the web. `cache.registerRangeDataSource()` registers it as `'mediacache'`. The player's files then stream through the cache: each byte is fetched once, shared with every other player of the file, and kept under the cache's eviction, TTL and offline replay. See [Transparent Video](https://github.com/MediaCacheDev/media-cache#transparent-video-alpha-video-and-alpha-video-gpu) in MediaCache's README.

MediaCache needs range-source on Android, iOS and React Native: MediaCache fetches and stores the bytes, and range-source passes the player's reads to it. range-source is included by default ([Installation](#installation)), but an app that left it out has to add it back, and `registerRangeDataSource` returns false without it. On the web, the player's range-source-web is always included.

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

In debug builds that URI is the Metro dev server (`http://…`, read and stored like any URL). In release builds it's a `file://` path in the iOS bundle, or a `res/raw` name on Android. On web, `Image.resolveAssetSource` doesn't exist; use the module value directly when it's a string, or `expo-asset`'s `Asset.fromModule(module).uri`.

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

The player reads everything from standard KTX 2.0 fields, so it needs no custom metadata:

| What | Where in the file |
|------|-------------------|
| Frame width and height | Header: `pixelWidth`, `pixelHeight` |
| Frame count | Header: `layerCount` (one layer per frame) |
| ASTC block size | Header: `vkFormat` |
| Frame rate | Key/value data: [`KTXanimData`](https://registry.khronos.org/KTX/specs/2.0/ktxspec.v2.html#_ktxanimdata), the time per frame as a fraction, so 29.97 fps (1001/30000 s) is exact. Without it, 30 fps |

`onLoad` reports `frameRate` as a decimal (29.97…), and `seekToTime` uses it.

Only ASTC texture arrays play. Files in other formats (UASTC and other Basis Universal files, atlas packing, GLB containers, multi-part files) are rejected with an error; encode them again with `texenc`. Files from earlier encoders, which also carried a JSON `BasisPlayerMetadata` entry, still play, at 30 fps.


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
  streamingPrefetchAhead: 8,   // stream: frames kept decoded ahead, 1 to 100; see Buffering
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
  streamingPrefetchAhead={8}
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

**Why `vram` is the default:** when streaming, it holds `streamingPrefetchAhead` + 2 frames on the GPU and almost nothing in RAM, where `ram` holds the same frames in RAM plus 4 GPU slots. So it uses less memory in total and copies each frame once, and on Android the upload runs asynchronously through a pixel buffer. Its frames stay out of the Java heap on Android (whose limit is often 256–512 MB) and the JavaScript heap on the web. Phones share physical memory between CPU and GPU, so on iOS the textures still count toward the app's memory limit, but `vram` still uses less of it. The ring is allocated as one texture array when the source loads; a clip too large for it reports `onError`.

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
  streamingPrefetchAhead={8}
/>
```

#### Streaming Props

| Prop | Default | Description |
|------|---------|-------------|
| `stream` | `true` | Stream a texture array instead of loading every frame |
| `memoryStrategy` | `"vram"` | Where the decoded frames are kept: `"vram"` on the GPU (minimizes RAM), `"ram"` in RAM with 4 GPU slots (minimizes VRAM) |
| `streamingPrefetchAhead` | `8` | Frames kept decoded after the shown one (1–100); see [Buffering](#buffering) |

#### VRAM Streaming (Default)

Frames are decoded straight into a GPU texture array of `streamingPrefetchAhead` + 2 layers, used as a ring: frame n goes in layer n modulo the size. With `streamingPrefetchAhead={5}`, 7 layers:

```
[GPU texture array: 5 + 2 = 7 layers]
Frame 100  layer 2   previous frame, which the GPU may still be drawing
Frame 101  layer 3   ← shown
Frame 102  layer 4   ┐
Frame 103  layer 5   │
Frame 104  layer 6   │ decoded ahead (streamingPrefetchAhead = 5)
Frame 105  layer 0   │
Frame 106  layer 1   ┘
When frame 102 shows, frame 107 is decoded into layer 2, over frame 100
```

#### RAM Streaming

The same frames are decoded into RAM instead, and each is uploaded into one of 4 GPU slots as it's about to show. That keeps VRAM at 4 frames whatever `streamingPrefetchAhead` is, and costs an upload per frame shown.

```
[RAM: 5 + 2 = 7 frames]                 [GPU: 4 slots]
Frames 100–106 (101 shown, 102–106 ahead) → the shown frame and the next ones to show
```

#### Why Streaming?

Streaming enables playback of arbitrarily long videos with **fixed memory footprint**:
- A 10,000 frame video uses the same memory as a 100 frame video
- Memory is set by one prop, `streamingPrefetchAhead`
- Ideal for long-form content, digital signage, or memory-constrained devices

#### Buffering

`streamingPrefetchAhead` is the only buffer setting. The decoder runs ahead of the playhead until that many frames after the shown one are ready, and a frame's space is reused once it's behind the playhead, so the player holds `streamingPrefetchAhead` + 2 frames: the previous one, the shown one and the ones ahead. With `vram` they are the GPU ring itself, so its size follows from the same setting; with `ram` they are in RAM, and the GPU has 4 slots whatever the setting.

The frames ahead are a safety margin. Decompressing zstd usually keeps well ahead, but some stretches take longer than a frame's time: the restart at each loop, other players or the app busy on the CPU, or the bytes of a URL still arriving. While frames are left ahead, playback carries on through them; when none are left, the shown frame stays up until the next is ready. A larger margin rides out longer slow stretches and costs memory, 1 byte a pixel per frame in `astc4x4` (less for larger blocks, and 4 bytes on the web without GPU ASTC):

| Frame size | One frame (`astc4x4`) | At the default (10 frames) |
|---|---|---|
| 400×400 | 0.16 MB | 1.6 MB |
| 1080×1080 | 1.2 MB | 12 MB |
| 1920×1080 | 2.1 MB | 21 MB |

The default, 8, is about a quarter of a second at 30 fps, the same as alpha-video's. Decompressing zstd is lighter work than decoding video, so it is a generous margin; go higher for large frames on low-end phones, or on the web to keep short loops in the ring.

It doesn't change the decoding work, only how far ahead it's done, and it doesn't buffer bytes: with range-source, the reads ahead of the decoder are its own (see [Streaming and storage](#streaming-and-storage)). Uncompressed files decode nothing ahead and ignore it. On the web a clip whose frames all fit in the `vram` ring loops without decoding again, so a larger value can save the CPU work of short loops there.

To tune it on Android and iOS, turn on `verbose`. At the end of every pass through the clip the player logs a line like `Pass buffer: fewest frames ahead 6 of 8, stalls 0`: the fewest frames it had ready during the pass, and how many times it had none and held a frame. Watch a few loops on the slowest device you support, with as many players as the app shows at once. If the fewest stays well above 0 and there are no stalls, a smaller value saves memory; with stalls, a larger one helps if they come from short slow stretches, but if decoding can't keep up on average no margin will, and fewer players at once or an uncompressed file will.

### Encoding for Streaming

The encoder's defaults (ASTC texture array, KTX2-internal zstd) already produce a streamable file; play it with `stream`.

#### Seeking in zstd files

A zstd decoder can only start at the beginning of a zstd frame. By default the encoder starts a new frame every 8 MB of ASTC and ends the level with a seek table in zstd's [seekable format](https://github.com/facebook/zstd/tree/dev/contrib/seekable_format). A streaming seek then starts at the frame before its target, unless carrying on from the decoder is closer. On an M4 Pro the slowest seek took 6 ms, against 1.5 s (5 minutes of 360p) and 2.9 s (60 s of 1080p) for the same clips as one stream; the 8 MB seek points cost 0.8% and 1.6% in size.

`--seek-interval 0` writes one stream instead, the smallest file, for clips that only play and loop. Players that don't know the table, and every other zstd tool, read both layouts as ordinary zstd. This player reads one-stream files too, and seeks in them by decoding from the start.

---

## Uncompressed ASTC Streaming

For the most players at once and the least CPU, uncompressed files skip zstd entirely. The KTX2 file holds the ASTC blocks as they are, and the player uploads them straight from the memory-mapped file.

### When to Use

- **Dozens of simultaneous players** on one screen
- **Always-on displays** (kiosks, digital signage), where CPU and battery add up
- **Smooth playback while the CPU is busy** with the rest of the app
- **Storage is not a constraint** (local assets, fast network)

### Performance Characteristics

The glow clip (400×400, 181 frames) on the budget phone from [the measurements above](#on-a-budget-android-phone):

| Metric | Zstd compressed | Uncompressed (`--zstd 0`) |
|--------|-----------------|---------------|
| File size | 4.9 MB | 29 MB (6x larger) |
| CPU during playback | Zstd decode + upload | Upload only |
| 20 players | 3.1 cores, 908 MB, demo UI at 52 fps | 2.4 cores, 371 MB, demo UI at 52 fps |
| 50 players | All 8 cores, 2.1 GB, demo UI at 3 fps | 3.7 cores, 699 MB, demo UI at 41 fps |
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
/>
```

### How It Works

1. **Memory-mapped file access** - The KTX2 file is mmap'd, allowing the OS to page frames in/out efficiently
2. **Direct GPU upload** - ASTC blocks are copied directly to GPU texture memory (no Zstd decompression needed)
3. **Ring buffer playback** - Each frame is copied into a ring of 4 GPU slots as it's shown, so only 4 frames are in GPU memory at once. Nothing is decoded ahead, so `streamingPrefetchAhead` and `memoryStrategy` don't apply
4. **No decoding on the CPU** - On devices with ASTC hardware, each frame is a copy from the mapped file to the GPU

### Trade-offs

| Pros | Cons |
|------|------|
| The lowest CPU use: no decompression | 6x larger file size than zstd |
| Scales to dozens of players (50 on a budget phone) | Higher storage/bandwidth requirements |
| Consistent frame timing | Not suitable for slow networks |
| Lower battery consumption | Larger app bundle if bundled |
| No decode latency on startup | — |

### Simulator Behavior

On iOS/Android simulators (no hardware ASTC), uncompressed streaming still requires software ASTC→RGBA decode. The player performs this decode on a background thread to keep the UI responsive, but performance will be significantly lower than on real devices.

---

## Performance Characteristics

- **CPU:** Low: zstd decompression for compressed files while streaming, frame uploads, and the platform redrawing the view for each new frame. On Android each player is a `TextureView`, and that redraw is most of what one small player costs. See [the measurements](#on-a-budget-android-phone).
- **Memory:** GPU texture memory = the ASTC size (ASTC decompresses on GPU)
- **Load time:** Direct upload of the ASTC blocks

### Playback Mode Comparison

| Mode | Load Time | Memory | CPU (Playback) | Best For |
|------|-----------|--------|----------------|----------|
| Non-streaming VRAM | Slow (all frames) | High VRAM, minimal RAM | Lowest | Short videos, best performance |
| Non-streaming RAM | Slow (all frames) | High RAM, minimal VRAM | Low (uploads) | VRAM-constrained devices |
| Streaming VRAM (Zstd) | Fast (prefetch) | Low VRAM ring buffer | Low (zstd) | Long videos, fixed memory |
| Streaming RAM (Zstd) | Fast (prefetch) | Medium RAM cache, minimal VRAM | Low (zstd) | Long videos, VRAM-constrained |
| Streaming Uncompressed | Fast (mmap) | Low VRAM ring buffer | Lowest | Many players at once, always-on displays |

Best suited for:
- Looping UI effects (glows, shimmers, highlights)
- Transparent overlays
- Kiosk/always-on displays
- Any video where consistent frame timing matters
- **Many simultaneous players** (dozens, with uncompressed files)

Not ideal for:
- Long-form video content
- Audio-synced playback
- Bandwidth-constrained streaming (use Zstd compression)

---

## Props Reference

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `source` | `{ uri: string, cacheKey?: string, headers?: Record<string, string>, dataSource?: string }` | — | What to play; see [Sources](#sources). On web also a `Blob` or a byte source, and `dataSource` names a data source registered with range-source-web |
| `style` | `ViewStyle` | — | Container style |
| `autoPlay` | `boolean` | `true` | Start playback once loaded |
| `loop` | `boolean` | `true` | Loop playback |
| `paused` | `boolean` | `false` | Pause playback (and streaming decompression) |
| `playbackSpeed` | `number` | `1.0` | Speed multiplier |
| `cacheKey` | `string` | — | Names the stored copy (default, the URL); overrides `source.cacheKey`. See [Cache keys](#cache-keys) |
| `storage` | `"auto"` \| `"disk"` \| `"memory"` \| `"none"` | `"auto"` | Where the bytes are kept between plays; see [Streaming and storage](#streaming-and-storage) |
| `maxStorageBytes` | `number` | 512 MB | The store's cap, over every clip in it |
| `memoryStrategy` | `"vram"` \| `"ram"` | `"vram"` | Where frames are kept; see [Options](#options) |
| `stream` | `boolean` | `true` | Stream a texture array with a ring buffer |
| `streamingPrefetchAhead` | `number` | `8` | Frames kept decoded after the shown one while streaming (1–100): a safety margin that costs memory. The player keeps this many plus 2, on the GPU with `vram` and in RAM with `ram` (see [Buffering](#buffering)) |
| `enableFrameEvents` | `boolean` | `false` | Send `onFrameUpdate` for every frame played, not only after seeks |
| `verbose` | `boolean` | `false` | Log info traces (Logcat, Xcode console, browser console); warnings and errors always log |
| `onLoad` | `({ nativeEvent: { width, height, frameCount, frameRate, duration } }) => void` | — | Metadata is known |
| `onStateChange` | `({ nativeEvent: { state } }) => void` | — | `state` is `"loading"`, `"ready"`, `"playing"`, `"paused"` or `"stopped"` |
| `onFrameUpdate` | `({ nativeEvent: { frame, totalFrames } }) => void` | — | After a seek, and for every frame with `enableFrameEvents` |
| `onFinished` | `() => void` | — | A non-looping video reached its last frame |
| `onError` | `({ nativeEvent: { error, code? } }) => void` | — | Loading or playback failed. `code` is set for errors an app may handle: `'NO_SOURCE_MODULE'` on Android and iOS ([Without range-source](#without-range-source)), `'ASTC_UNSUPPORTED'` or `'WEBGL_UNSUPPORTED'` on the web |

The events have the same shape on Android, iOS and web. Playback is controlled with props (`paused`, `autoPlay`, a new `source`) and with the ref (see [Usage](#usage)).

---

## Repository layout

| Path | What | Package |
|---|---|---|
| `android/` | The Android library: `AlphaVideoGpuView`, source loading, and the JNI code for zstd, reading through range-source or the basic download, the software ASTC decoder and texture uploads (`src/main/cpp`). Builds on its own (`./gradlew` in that folder) | `dev.mediacache:alpha-video-gpu` |
| `ios/Sources/AlphaVideoGPU/` | The iOS library: `AlphaVideoGpuView` (Objective-C++) and source loading, with its public header in `include/`. `ios/Tests` has its tests with and without range-source | SwiftPM `AlphaVideoGPU` (`Package.swift`), CocoaPods `alpha-video-gpu` (`alpha-video-gpu.podspec`) |
| `third_party/zstd/` | zstd 1.4.9 (single-file library), compiled into both native libraries | |
| `third_party/range-source/` | The headers of [range-source](https://github.com/MediaCacheDev/range-source): its C interface, which the players find the add-on through at runtime, and the basic input. `scripts/vendor_range_source.sh` updates it | |
| `react-native/` | `src/` (the component), `android/` (the view manager; its Gradle module compiles `android/` too), `ios/` (a subclass of the iOS view with React props) | npm `react-native-alpha-video-gpu` (`package.json`, `react-native-alpha-video-gpu.podspec`) |
| `web/` | The WebGL player for browsers, with its React wrapper. `npm run build` there builds `dist/` | npm `alpha-video-gpu-web` |
| `scripts/publish-android.sh` | `local` publishes the Android library to `~/.m2`; `central` signs it and uploads it to the Maven Central Portal (see the script for the variables) | |

A clone builds with no setup step, since the decoders are in the repo (`third_party/` for the native libraries, `web/src/` for the web package):

```bash
cd web && npm ci && npm run build                     # web: dist/
cd android && ./gradlew assembleRelease               # Android: the AAR (needs the Android SDK and NDK)
xcodebuild -scheme AlphaVideoGPU -destination 'generic/platform=iOS Simulator' build   # iOS, from the repo root
```

A release is the tag `v<version>`: Swift Package Manager resolves it, and `alpha-video-gpu.podspec` fetches its sources from it. After pushing the tag, `pod trunk push alpha-video-gpu.podspec` (only while trunk accepts new versions). [range-source](https://github.com/MediaCacheDev/range-source) is released first on every channel, since the players depend on it. Publish `alpha-video-gpu-web` to npm before `react-native-alpha-video-gpu`, which depends on it.

## Tests

The fixtures in `tests/fixtures/` are test cards from the tester app, encoded to KTX2 with 4×4, 6×6 and 8×8 ASTC blocks, as one zstd stream and with a seek table, and described in `expected.json`. Every frame carries its index as a row of cells in the colour and in the alpha, beside colour swatches and an alpha ramp, so a test can tell which frame it got and how far off its colours are. They're copies of a few of the tester app's cards (`npm run testcards` in [alpha-video-test](https://github.com/MediaCacheDev/alpha-video-test)); regenerate them there.

| Where | Command | What it checks |
|---|---|---|
| Web, under Node | `cd web && npm test` | The KTX2 parser, the seek table, streaming reads and seeks in zstd and uncompressed files, and every frame of the fixtures through the software ASTC decoder, read back by its frame code |
| Android, on the host | `cd android && ./gradlew testReleaseUnitTest` | The seek table parser (JUnit) |
| iOS, on the host | `clang -O1 -Ithird_party -Iios/Sources/AlphaVideoGPU ios/Tests/zstd_seek_test.c third_party/zstd/zstd.c -o /tmp/zstd_seek_test && /tmp/zstd_seek_test` | The seek table, reads and seeks (`zstd_seek.h`), from the repository root |
| iOS simulator | `xcodebuild test -scheme AlphaVideoGPU-Package -destination 'platform=iOS Simulator,name=iPhone 16 Pro'`, and the same with `-scheme WithoutRangeSource-Package` in `ios/Tests/WithoutRangeSource` | With range-source (`SourceTests`), a data source registered with it. Without it (a package taking the `AlphaVideoGPUCore` product), a file plays and a data source reports `NO_SOURCE_MODULE`. `RANGE_SOURCE_PATH` points them at a range-source checkout instead of its release |
| Web, in a browser | `web/test/browser/index.html`, see below | Every streaming and memory mode on every fixture in a real browser: frame codes after seeks and in playback, from the canvas |
| Android and iOS devices | the tester app's `test:pixels` and `test:streaming` | Every mode and memory strategy on every card, pixel by pixel, and streaming; see below |

The browser page isn't automated. Build `web/dist/` (`cd web && npm run build`), serve this repository over HTTP, and open `web/test/browser/index.html`; results show on the page and in `window.results`. range-source's test server supports range requests, which the streaming modes use:

```bash
cd ../range-source/web && npm run serve -- <path to this repo> --port 8766
# then open http://localhost:8766/web/test/browser/index.html
```

`?astc=off` hides `WEBGL_compressed_texture_astc` to test the software decoder, `?only=<regex>` picks cases, `?cards=<path>` plays other cards (the tester app's include more sizes and layouts), and `?storage=`, `?replay` and `?input=` exercise storage and sources (see the [web README](web/README.md#testing)). Add `--no-range` to the server for one without range support.

On Android and iOS the tester app plays the cards through the React Native component. In [alpha-video-test](https://github.com/MediaCacheDev/alpha-video-test#tests), with this repo's tarball installed and a Release build on an emulator, phone or simulator:

```bash
npm run test:pixels -- --platform android --player gpu     # default (VRAM streaming), stream ram and preload, on every card
npm run test:pixels -- --platform ios --player gpu
npm run test:pixels -- --platform ios --player gpu --only 'astc4x4_seek' --frames 0,37   # one fixture, two frames
npm run test:pixels -- --platform android --player gpu --playback      # playback and looping
npm run test:streaming -- --platform android --player gpu  # range requests, seek table, storage, headers, Media3 data source
```

The tester app installs `react-native-range-source`. Without it (`npm uninstall react-native-range-source`, then rebuild), `test:streaming` checks the basic input instead: one download per play, and `NO_SOURCE_MODULE` for a data source.

`npm pack` here (and in `web/`) makes the tarballs the tester app installs; rebuild its app after installing them. Everything, in order:

```bash
(cd web && npm test)
(cd android && ./gradlew testReleaseUnitTest)
clang -O1 -Ithird_party -Iios/Sources/AlphaVideoGPU ios/Tests/zstd_seek_test.c third_party/zstd/zstd.c -o /tmp/zstd_seek_test && /tmp/zstd_seek_test
xcodebuild test -scheme AlphaVideoGPU-Package -destination 'platform=iOS Simulator,name=iPhone 16 Pro'
(cd ios/Tests/WithoutRangeSource && xcodebuild test -scheme WithoutRangeSource-Package -destination 'platform=iOS Simulator,name=iPhone 16 Pro')
# the browser page, with ?astc=off too; then, in alpha-video-test: test:pixels and test:streaming with --player gpu on each platform
```

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
