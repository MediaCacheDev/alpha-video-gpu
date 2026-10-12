# alpha-video-gpu

Plays transparent (alpha) animations stored as GPU-compressed KTX2 textures (ASTC, or Basis Universal UASTC), drawn with Metal on iOS, OpenGL ES on Android and WebGL on the web. Frames go to the GPU without being decoded, so CPU and battery use stay low. It comes as a React Native component, as standalone Android and iOS libraries, and as a web package.

| Platform | Package | View |
|---|---|---|
| React Native (Android, iOS, web) | npm `alpha-video-gpu` | `AlphaVideoGpuView` |
| Android | Maven `dev.mediacache:alpha-video-gpu` | `dev.mediacache.alphavideo.gpu.AlphaVideoGpuView` |
| iOS | Swift Package Manager, product `AlphaVideoGPU`; CocoaPods `alpha-video-gpu` | `AlphaVideoGpuView` |
| Web (plain JS/TS, React) | npm `alpha-video-gpu-web` | `createAlphaVideoGpuPlayer`, `AlphaVideoGpuPlayer` |

> **Looking for codec-based playback?** See [alpha-video](https://github.com/danielkpl2/alpha-video) for WebM VP9/AV1 with smaller file sizes.

## Overview

This library renders animations from KTX2 texture files containing sprite sheets. Frames are packed into GPU-compressed textures and uploaded once, then animated by updating UV coordinates on the GPU.

**Key characteristics:**
- Single texture upload per animation part (no per-frame decoding)
- Hardware texture compression (ASTC on iOS/Android, transcoded formats on web)
- Software RGBA fallback for emulators and unsupported devices
- Metadata embedded in KTX2 files or provided separately
- **Streaming playback** for long animations with minimal memory footprint
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
- Many simultaneous animations (UI effects, particles, stickers)
- Battery/CPU usage is critical
- Low latency is required
- Content is short-to-medium (seconds to ~1 minute)
- Consistent cross-platform behavior needed

### The Math

For a 30fps, 400×400 animation with alpha:

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

## Encoding Modes

This player supports two encoding formats. Files are made with the separate [`alpha-video-gpu-encoder`](https://github.com/danielkpl2/alpha-video-gpu-encoder) package (CLI `texenc`).

### Native ASTC

Direct ASTC 4x4 block compression using `toktx` from the KTX-Software tools. Produces the highest visual quality but requires ASTC hardware support for GPU-accelerated playback.

- **Pros:** Best quality, no runtime transcoding
- **Cons:** Larger files than UASTC+RDO, no GPU benefit on emulators

### UASTC (Basis Universal)

Universal texture format that transcodes at runtime to the device's native GPU format (ASTC, ETC2, BC7, etc.).

- **Pros:** Smaller files with RDO, universal GPU support
- **Cons:** Slight quality loss vs native ASTC, transcoding overhead on first load

---

## Encoding Examples

```bash
npm install -g alpha-video-gpu-encoder

# Native ASTC texture array, KTX2-internal zstd (the default; streams with `stream`)
texenc --input ./frames --output animation.ktx2 --fps 30 --astc-quality exhaustive --astc-perceptual

# UASTC (transcodes at runtime; plays on any GPU, including desktop browsers)
texenc --input ./frames --output animation.ktx2 --fps 30 --uastc --uastc-rdo 0

# Uncompressed ASTC (largest file, no decompression; mmap streaming)
texenc --input ./frames --output animation.ktx2 --fps 30 --zstd 0

# Whole-file zstd wrapper (always streams)
texenc --input ./frames --output animation.ktx2 --fps 30 --outer-compression
```

See the encoder's README for every option, video input and the memory-efficient stream encoder.

---

## Emulator / Fallback Behavior

On devices without hardware ASTC support (iOS Simulator, Android Emulator, some browsers), the player automatically decodes ASTC textures to RGBA in software.

| Platform | Hardware Support | Fallback |
|----------|------------------|----------|
| iOS Device | ASTC (Metal) | — |
| iOS Simulator | None | Software ASTC→RGBA decode |
| Android Device | ASTC or ETC2 | ETC2 or software decode |
| Android Emulator | Usually none | Software ASTC→RGBA decode |
| Web (desktop) | Varies (ASTC/BC7/ETC2) | Error if ASTC unsupported |

**Important:** Both ASTC and UASTC modes fall back to uncompressed RGBA on emulators. There is no GPU compression benefit on emulators—they exist purely for functional testing. Always test performance on real devices.

---

## Installation

### React Native

```bash
npm install alpha-video-gpu
cd ios && pod install
```

The Basis Universal transcoder and zstd are compiled in from `third_party/`; there are no other native dependencies. On the web the component uses [`alpha-video-gpu-web`](#web-support), which is installed with it.

### Android without React Native

```gradle
dependencies {
    implementation 'dev.mediacache:alpha-video-gpu:1.0.0'
}
```

`AlphaVideoGpuView` is a `TextureView` in `dev.mediacache.alphavideo.gpu`. It takes the same sources and options as the React Native component, through setters:

```kotlin
val view = AlphaVideoGpuView(context)
view.setStream(true)
view.setPlayerCallback(object : AlphaVideoGpuView.PlayerCallback {
    override fun onLoad(width: Int, height: Int, frameCount: Int, frameRate: Double) {}
    override fun onError(error: String) {}
    override fun onFinished() {}
})
view.setSource("asset://media/animation.ktx2")
container.addView(view)
```

Every `PlayerCallback` method has an empty default; `onStateChange` and `onFrameUpdate` (with `setEnableFrameEvents(true)`) are the others. Callbacks arrive on the main thread. `play()`, `pause()`, `stop()` and `seekToFrame()` control playback; detaching the view, or `destroy()`, frees it. http(s) sources need the `INTERNET` permission in the app's manifest. Needs `minSdk` 24 and OpenGL ES 3.0.

### iOS without React Native

In Xcode, add the package `https://github.com/danielkpl2/alpha-video-gpu` (File → Add Package Dependencies), or in `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/danielkpl2/alpha-video-gpu", exact: "1.0.0"),
],
targets: [
    .target(name: "MyApp", dependencies: [
        .product(name: "AlphaVideoGPU", package: "alpha-video-gpu"),
    ]),
]
```

Or with CocoaPods:

```ruby
pod 'alpha-video-gpu', '1.0.0'
```

CocoaPods trunk is read-only from 2 December 2026, so later versions are used through the podspec at their tag: `pod 'alpha-video-gpu', :podspec => 'https://raw.githubusercontent.com/danielkpl2/alpha-video-gpu/v<version>/alpha-video-gpu.podspec'`. The module is `AlphaVideoGPU` either way.

`AlphaVideoGpuView` is an Objective-C `MTKView`, so it works from Swift and Objective-C:

```swift
import AlphaVideoGPU

let view = AlphaVideoGpuView(frame: CGRect(x: 0, y: 0, width: 200, height: 200))
view.stream = true
view.onLoad = { width, height, frameCount, frameRate in }
view.onError = { error in }
view.onFinished = { }
view.source = "asset://media/animation.ktx2"
container.addSubview(view)
```

`MTKView` already has `paused` and `delegate`, which the view uses itself: pause playback with `playbackPaused`, and get events through the `onLoad`, `onFrameUpdate`, `onStateChange`, `onFinished` and `onError` blocks, which are called on the main queue. Setting `source` to `nil` stops playback and frees the textures. iOS 13 or later.

---

## Usage

```jsx
import { AlphaVideoGpuView } from 'alpha-video-gpu';

<AlphaVideoGpuView
  style={{ width: 400, height: 400 }}
  source={{ uri: 'https://example.com/animation.ktx2' }}
  onStateChange={({ nativeEvent }) => console.log(nativeEvent.state)}
  onError={({ nativeEvent }) => console.error(nativeEvent.error)}
/>
```

### Sources

`source.uri` takes one of these forms. Anything else is reported through `onError`.

| Form | iOS | Android | Web | Notes |
|------|-----|---------|-----|-------|
| `https://…`, `http://…` | ✓ | ✓ | ✓ | Downloaded once into the app cache, then read from disk. Web caches in IndexedDB. |
| `file:///abs/path`, `/abs/path` | ✓ | ✓ | — | Read in place. |
| `asset://path/in/bundle` | ✓ | ✓ | ✓ | Android: APK `assets/`. iOS: the app bundle's resource folder. Web: the site root (`/path/in/bundle`). |
| `content://…` | — | ✓ | — | Read through the `ContentResolver`. |
| bare resource name | — | ✓ | — | A `res/raw` resource, which is what `require()` resolves to in an Android release build. |

Nothing is copied: bundled assets, raw resources and local files are read where they are.

#### Cache keys

Downloads are named by `cacheKey` if you give one, otherwise by a hash of the URL. Set `cacheKey` when the URL changes while the file doesn't (presigned URLs), so a new URL still hits the cache and doesn't reload the view:

```jsx
<AlphaVideoGpuView source={{ uri: presignedUrl, cacheKey: 'animations/glow.ktx2' }} />
```

`cacheKey` can also be passed as a top-level prop; it wins over `source.cacheKey`. Native downloads go to the app's cache directory (`<cache>/<cacheKey>`, or `<cache>/alpha-video-gpu/<sha256>`), so the OS may clear them.

#### Bundled with `require()`

Register the extensions with Metro:

```js
// metro.config.js
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

const defaultConfig = getDefaultConfig(__dirname);
module.exports = mergeConfig(defaultConfig, {
  resolver: {
    assetExts: [...defaultConfig.resolver.assetExts, 'ktx2', 'glb', 'zst'],
  },
});
```

Then resolve the module to a URI:

```jsx
import { Image } from 'react-native';

const animation = Image.resolveAssetSource(require('./assets/animation.ktx2'));

<AlphaVideoGpuView source={{ uri: animation.uri }} />
```

In debug builds that URI is the Metro dev server (`http://…`, downloaded and cached like any URL). In release builds it's a `file://` path in the iOS bundle, or a `res/raw` name on Android. On web, `Image.resolveAssetSource` doesn't exist; use the module value directly when it's a string, or `expo-asset`'s `Asset.fromModule(module).uri`.

#### Streaming from Android assets and resources

Android compresses APK entries it doesn't recognise, and a compressed entry can't be memory-mapped. Non-streaming playback reads it anyway, but `stream` needs the file stored uncompressed:

```groovy
// android/app/build.gradle
android {
  androidResources {
    noCompress += ['ktx2', 'zst', 'glb']
  }
}
```

Without it, `stream` on an `asset://` or `res/raw` file fails with an error naming this setting. A whole-file zstd wrapper still plays, but is read into memory first instead of being mapped.

---

## Multi-Part Animations

Large atlas animations are split across several textures. By default the encoder packs them into one **GLB** file, which loads like any other source.

With `--no-glb`, the parts are separate `.ktx2` files and the first one's metadata lists the others by file name. The player looks for them next to `source`: in the same folder for `file://`, `/` and `asset://` sources, and at the same URL path for `http(s)://` sources (a part of a source with `cacheKey: 'glow.ktx2'` is cached as `glow_1.ktx2`, and so on).

When they can't be found that way (res/raw names, `content://`, presigned URLs), list every part with `parts`, the first one being the file with the metadata:

```jsx
<AlphaVideoGpuView
  source={{ uri: urls[0], cacheKey: 'anim_0.ktx2' }}
  parts={urls.map((uri, i) => ({ uri, cacheKey: `anim_${i}.ktx2` }))}
/>
```

All parts are loaded before playback starts.

---

## Metadata

Animation metadata (frame count, dimensions, layout) is embedded in the KTX2 file's key-value data section by the encoder. The player reads this automatically.

For multi-part animations (large frame counts split across multiple textures), the encoder packages all parts into a single **GLB container** by default. GLB is the Khronos Group's binary glTF format, used here as a convenient container for multiple KTX2 textures.

### GLB Container Format

The GLB container includes:
- **JSON chunk**: Animation metadata with byte offsets for each KTX2 part
- **BIN chunk**: Concatenated KTX2 texture data

```json
{
  "frameCount": 120,
  "frameWidth": 400,
  "frameHeight": 400,
  "frameRate": 30,
  "framesPerRow": 4,
  "framesPerPart": 64,
  "partCount": 2,
  "mode": "astc",
  "container": "glb",
  "ktx2Parts": [
    { "byteOffset": 0, "byteLength": 1234567, "columns": 4, "rows": 16 },
    { "byteOffset": 1234567, "byteLength": 987654, "columns": 4, "rows": 14 }
  ]
}
```

### Legacy Multi-Part Format

For separate KTX2 files (using `--no-glb` encoder flag), metadata is embedded in the first part:

```json
{
  "frameCount": 120,
  "frameWidth": 400,
  "frameHeight": 400,
  "frameRate": 30,
  "framesPerRow": 4,
  "framesPerPart": 64,
  "partCount": 2,
  "mode": "astc",
  "parts": [
    { "file": "anim_0.ktx2", "columns": 4, "rows": 16 },
    { "file": "anim_1.ktx2", "columns": 4, "rows": 14 }
  ]
}
```

---

## Web Support

On React Native Web, `AlphaVideoGpuView` draws with the WebGL player from [`alpha-video-gpu-web`](web). Without React Native, install that package on its own:

```bash
npm install alpha-video-gpu-web
```

```ts
import { createAlphaVideoGpuPlayer } from 'alpha-video-gpu-web';

const player = createAlphaVideoGpuPlayer(document.querySelector('canvas')!, {
  autoPlay: true,
  loop: true,
  onLoad: ({ width, height, frameCount, frameRate }) => {},
  onError: (error) => console.error(error),
});
player.setSource('https://example.com/animation.ktx2');
// player.pause(), player.resume(), player.seekToFrame(n), player.destroy()
```

```tsx
import { AlphaVideoGpuPlayer } from 'alpha-video-gpu-web/react';

<AlphaVideoGpuPlayer src="/animation.ktx2" width={300} height={300} autoPlay loop />
```

The zstd decoder and the Basis transcoder (JS and WebAssembly) are bundled inline, so no extra scripts or files need to be served. React is an optional peer dependency, needed only for `alpha-video-gpu-web/react`.

### Web Texture Format Support

| Format | Browser Support | Notes |
|--------|-----------------|-------|
| **Native ASTC** | Mobile browsers, some desktop | Requires `WEBGL_compressed_texture_astc` |
| **UASTC** | All (transcoded) | Transcodes to BC7/ETC2/RGBA at runtime |
| **Zstd compression** | All | Bundled WASM decoder |

Native ASTC files need `WEBGL_compressed_texture_astc`, which most desktop browsers on Windows and Linux lack; there is no software fallback on the web, so the player reports an error. Encode with `--uastc` if desktop browsers matter.

---

## Playback Modes

The player supports multiple playback strategies optimized for different use cases.

### Memory Strategy: VRAM vs RAM

The `memoryStrategy` prop controls where the main frame buffer is located. The goal is to **maximize** usage of one memory type while **minimizing** the other:

| Strategy | Main Buffer | Behavior | Performance |
|----------|-------------|----------|-------------|
| `vram` | GPU VRAM | Maximizes VRAM, minimizes RAM | **Generally preferred** - VRAM is separate from app memory, can use 2GB+ without affecting app performance |
| `ram` | CPU RAM | Maximizes RAM, minimizes VRAM | Use when VRAM is constrained - but note that high RAM usage (1GB+) can cause app slowdowns |

The default, `auto`, uses VRAM for non-streaming playback and RAM for streaming.

**Recommendation:** Use `vram` mode unless you have a specific reason to minimize GPU memory usage. VRAM is allocated separately from your app's heap and doesn't contribute to memory pressure warnings.

### Non-Streaming (Default)

All frames are loaded into memory before playback begins. Best for short-to-medium animations. Works with both **texture arrays** and **atlas** packing modes, and supports both **Native ASTC** and **UASTC** encoding.

```jsx
<AlphaVideoGpuView
  source={{ uri: 'https://example.com/animation.ktx2' }}
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
  source={{ uri: 'https://example.com/animation.ktx2' }}
  memoryStrategy="ram"
  autoPlay
  loop
/>
```

### Streaming Mode

For very long animations or memory-constrained scenarios, streaming mode decodes frames on-demand during playback using a ring buffer. Only a small window of frames is kept in memory at any time, providing **constant and predictable memory usage** regardless of animation length.

Streaming works with **texture arrays** (`--mode array`, the encoder default) of **native ASTC**. It's used when:

- the file is a whole-file zstd wrapper (`--outer-compression`, usually `.ktx2.zst`). These always stream.
- `stream` is set and the file uses KTX2-internal zstd (the encoder default) or no compression (`--zstd 0`).

```jsx
<AlphaVideoGpuView
  source={{ uri: 'https://example.com/animation.ktx2' }}
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
| `stream` | `false` | Stream a texture array instead of loading every frame |
| `memoryStrategy` | `"auto"` | `"vram"` = GPU ring buffer (minimizes RAM), `"ram"` = CPU cache + GPU ring buffer (minimizes VRAM) |
| `streamingRingSize` | `16` | GPU texture slots in the ring buffer (4–64) |
| `streamingCacheSize` | `60` | (RAM mode only) decoded frames kept in CPU memory |
| `streamingPrefetchAhead` | `30` | Frames decoded ahead of the playback position (5–100) |

#### VRAM Streaming

Frames are decoded directly into a GPU texture ring buffer. Minimal CPU RAM usage - only the ring buffer exists in VRAM.

```
[GPU Ring Buffer: 16 slots]
Slot 0: Frame 100  ← Currently displaying
Slot 1: Frame 101
Slot 2: Frame 102
...
Slot 15: Frame 115 ← Prefetched ahead
```

#### RAM Streaming

Frames are first decoded to a CPU RAM cache, then uploaded to a smaller GPU ring buffer on demand. Minimizes VRAM usage while providing a larger decode-ahead buffer.

```
[CPU Cache: 60 frames] → [GPU Ring Buffer: 16 slots]
Frames 80-139 cached    Frames 100-119 in GPU
```

#### Why Streaming?

Streaming enables playback of arbitrarily long animations with **fixed memory footprint**:
- A 10,000 frame animation uses the same memory as a 100 frame animation
- Memory usage is fully configurable via ring buffer and cache size props
- Ideal for long-form content, digital signage, or memory-constrained devices

### Encoding for Streaming

The encoder's defaults (native ASTC, texture array, KTX2-internal zstd) already produce a streamable file; play it with `stream`. `--outer-compression` produces a whole-file zstd wrapper that the player streams while decompressing, without the `stream` prop.

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
texenc --input ./frames --output animation_uncompressed.ktx2 --fps 30 --zstd 0
```

`--zstd 0` leaves the ASTC blocks uncompressed, so the player can memory-map the file and upload frames without decompressing them.

### Usage

```jsx
<AlphaVideoGpuView
  source={{ uri: 'https://example.com/animation_uncompressed.ktx2' }}
  stream
  memoryStrategy="vram"
  streamingRingSize={16}
/>
```

### How It Works

1. **Memory-mapped file access** - The KTX2 file is mmap'd, allowing the OS to page frames in/out efficiently
2. **Direct GPU upload** - ASTC blocks are copied directly to GPU texture memory (no Zstd decompression needed)
3. **Ring buffer playback** - Only `streamingRingSize` frames are in GPU memory at once
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

- **CPU:** Near-zero during playback (UV updates only)
- **Memory:** GPU texture memory = uncompressed size (ASTC decompresses on GPU)
- **Battery:** Significantly lower than video playback
- **Load time:** One-time transcode (UASTC) or direct upload (ASTC)

### Playback Mode Comparison

| Mode | Encoding | Packing | Load Time | Memory | CPU (Playback) | Best For |
|------|----------|---------|-----------|--------|----------------|----------|
| Non-streaming VRAM | ASTC or UASTC | Array or Atlas | Slow (all frames) | High VRAM, minimal RAM | Near-zero | Short animations, best performance |
| Non-streaming RAM | ASTC or UASTC | Array or Atlas | Slow (all frames) | High RAM, minimal VRAM | Low (uploads) | VRAM-constrained devices |
| Streaming VRAM (Zstd) | ASTC only | Array only | Fast (prefetch) | Low VRAM ring buffer | Medium (decode) | Long animations, fixed memory |
| Streaming RAM (Zstd) | ASTC only | Array only | Fast (prefetch) | Medium RAM cache, minimal VRAM | Medium (decode) | Long animations, VRAM-constrained |
| Streaming Uncompressed | ASTC only | Array only | Fast (mmap) | Low VRAM ring buffer | Near-zero | Massive concurrency, battery-critical |

Best suited for:
- Looping UI effects (glows, shimmers, highlights)
- Transparent overlays
- Kiosk/always-on displays
- Any animation where consistent frame timing matters
- **High-concurrency scenarios** (50-100+ simultaneous animations)

Not ideal for:
- Long-form video content
- Audio-synced playback
- Bandwidth-constrained streaming (use Zstd compression)

---

## Props Reference

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `source` | `{ uri: string, cacheKey?: string }` | — | What to play; see [Sources](#sources) |
| `style` | `ViewStyle` | — | Container style |
| `autoPlay` | `boolean` | `true` | Start playback once loaded |
| `loop` | `boolean` | `true` | Loop playback |
| `paused` | `boolean` | `false` | Pause playback (and streaming decompression) |
| `playbackSpeed` | `number` | `1.0` | Speed multiplier |
| `cacheKey` | `string` | — | Cache identity for downloads; overrides `source.cacheKey` |
| `parts` | `Array<{ uri: string, cacheKey?: string }>` | — | Every part of a multi-part animation, when they can't be found next to `source` |
| `memoryStrategy` | `"auto"` \| `"vram"` \| `"ram"` | `"auto"` | Where frames are kept |
| `stream` | `boolean` | `false` | Stream a texture array with a ring buffer |
| `streamingRingSize` | `number` | `16` | GPU texture slots for streaming |
| `streamingCacheSize` | `number` | `60` | CPU cache size for RAM streaming |
| `streamingPrefetchAhead` | `number` | `30` | Frames to decode ahead |
| `gpuPlayback` | `boolean` | `false` | Experimental: compute the frame index in the shader |
| `enableFrameEvents` | `boolean` | `false` | Send `onFrameUpdate` for every rendered frame (native) |
| `verbose` | `boolean` | `false` | Log info traces (Logcat, Xcode console, browser console); warnings and errors always log |
| `onLoad` | `({ nativeEvent: { width, height, frameCount, frameRate, duration } }) => void` | — | Metadata is known |
| `onStateChange` | `({ nativeEvent: { state } }) => void` | — | `state` is `"loading"`, `"ready"`, `"playing"`, `"paused"` or `"stopped"` |
| `onFrameUpdate` | `({ nativeEvent: { frame, totalFrames } }) => void` | — | Needs `enableFrameEvents` on native |
| `onFinished` | `() => void` | — | A non-looping animation reached its last frame |
| `onError` | `({ nativeEvent: { error } }) => void` | — | Loading or playback failed |

Playback is controlled with props (`paused`, `autoPlay`, a new `source`); there is no imperative ref API yet.

---

## Repository layout

| Path | What | Package |
|---|---|---|
| `android/` | The Android library: `AlphaVideoGpuView`, source loading, and the JNI transcoder (`src/main/cpp`). Builds on its own (`./gradlew` in that folder) | `dev.mediacache:alpha-video-gpu` |
| `ios/Sources/AlphaVideoGPU/` | The iOS library: `AlphaVideoGpuView` (Objective-C++), with its public header in `include/` | SwiftPM `AlphaVideoGPU` (`Package.swift`), CocoaPods `alpha-video-gpu` (`alpha-video-gpu.podspec`) |
| `third_party/basis_universal/` | The Basis Universal 1.16.4 transcoder and zstd, compiled into both native libraries | |
| `react-native/` | `src/` (the component), `android/` (the view manager; its Gradle module compiles `android/` too), `ios/` (a subclass of the iOS view with React props) | npm `alpha-video-gpu` (`package.json`, `react-native-alpha-video-gpu.podspec`) |
| `web/` | The WebGL player for browsers, with its React wrapper. `npm run build` there inlines the decoders and builds `dist/` | npm `alpha-video-gpu-web` |
| `scripts/publish-android.sh` | `local` publishes the Android library to `~/.m2`; `central` signs it and uploads it to the Maven Central Portal (see the script for the variables) | |

A release is the tag `v<version>`: Swift Package Manager resolves it, and `alpha-video-gpu.podspec` fetches its sources from it. After pushing the tag, `pod trunk push alpha-video-gpu.podspec` (only while trunk accepts new versions). Publish `alpha-video-gpu-web` to npm before `alpha-video-gpu`, which depends on it.

## Requirements

- React Native 0.71+
- Android: minSdkVersion 24, OpenGL ES 3.0; compileSdkVersion comes from the app (default 36)
- iOS: iOS 13.0+, Metal
- Web: WebGL 2 (falls back to WebGL 1)

---

## License

The code in this repository is MIT. The Basis Universal transcoder and the ASTC decoder are Apache-2.0, and zstd is BSD-3-Clause; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
