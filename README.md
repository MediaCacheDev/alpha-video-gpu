# React Native KTX2 Player

A GPU-accelerated texture animation player for React Native (iOS, Android, Web). Plays pre-encoded KTX2 sprite sheet animations with hardware texture compression support.

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

## Encoding Modes

This player supports two encoding formats. Both require a separate encoder tool (not included in this package).

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

The encoder is a separate tool (`encode-basis.js`). Example commands:

### Maximum Quality (Native ASTC)

```bash
node encode-basis.js \
  --input ./frames \
  --output ./animation.ktx2 \
  --width 400 \
  --height 400 \
  --fps 30 \
  --no-mipmap \
  --astc \
  --astc-quality exhaustive \
  --astc-block 4x4 \
  --zstd 22 \
  --astc-perceptual
```

### Maximum Quality (UASTC)

```bash
node encode-basis.js \
  --input ./frames \
  --output ./animation.ktx2 \
  --width 400 \
  --height 400 \
  --fps 30 \
  --no-mipmap \
  --uastc \
  --uastc-rdo 0
```

> **Note:** `--uastc-rdo 0` disables rate-distortion optimization for maximum quality. Use `--uastc-rdo 0.5` to `--uastc-rdo 3.0` for smaller files with some quality tradeoff.

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

```bash
npm install react-native-ktx2player

# iOS
cd ios && pod install
```

Basis Universal transcoder is bundled. No additional native dependencies required.

---

## Usage

### Remote URL

```jsx
import { KTX2PlayerView } from 'react-native-ktx2player';

<KTX2PlayerView
  style={{ width: 400, height: 400 }}
  source={{ uri: 'https://example.com/animation.ktx2' }}
  autoPlay
  loop
/>
```

Remote files are automatically cached. Use `cacheKey` for explicit cache control:

```jsx
<KTX2PlayerView
  style={{ width: 400, height: 400 }}
  source={{ uri: 'https://example.com/animation.ktx2' }}
  cacheKey="animations/glow.ktx2"
  autoPlay
  loop
  onStateChange={({ nativeEvent }) => console.log(nativeEvent.state)}
  onError={({ nativeEvent }) => console.error(nativeEvent.error)}
/>
```

### Local Bundled Asset (Recommended)

Include a `.ktx2` or `.glb` file in your React Native project and resolve it correctly for each platform.

#### Cross-Platform Asset Resolution

```jsx
import { KTX2PlayerView } from 'react-native-ktx2player';
import { Image, Platform } from 'react-native';

// Import the asset
const glbAsset = require('./assets/animation.glb');

// Cross-platform asset resolver
function resolveAsset(asset) {
  // Native platforms: use Image.resolveAssetSource
  if (Platform.OS !== 'web') {
    const resolved = Image.resolveAssetSource(asset);
    return resolved?.uri || null;
  }
  
  // Web: Metro may return a string path directly or a number ID
  if (typeof asset === 'string') {
    return asset; // Already a URL path
  }
  
  // Web with number ID: use expo-asset to resolve
  if (typeof asset === 'number') {
    const { Asset } = require('expo-asset');
    const resolved = Asset.fromModule(asset);
    return resolved.uri;
  }
  
  // Object with uri property
  if (asset?.uri) {
    return asset.uri;
  }
  
  return null;
}

// Usage
const animationUri = resolveAsset(glbAsset);

<KTX2PlayerView
  style={{ width: 400, height: 400 }}
  source={{ uri: animationUri }}
  autoPlay
  loop
/>
```

#### Platform-Specific Behavior

| Platform | `require()` returns | Resolution method |
|----------|---------------------|-------------------|
| **iOS/Android (Debug)** | Number (asset ID) | `Image.resolveAssetSource()` → Metro URL |
| **iOS/Android (Release)** | Number (asset ID) | `Image.resolveAssetSource()` → File path |
| **Web (Expo)** | String (URL path) | Use directly |
| **Web (Metro)** | Number (asset ID) | `expo-asset` → URL path |

#### Simple Native-Only Usage

If you don't need web support, the simpler approach works:

```jsx
import { KTX2PlayerView } from 'react-native-ktx2player';
import { Image } from 'react-native';

const ktx2Asset = require('./assets/animation.ktx2');
const resolvedAsset = Image.resolveAssetSource(ktx2Asset);

<KTX2PlayerView
  style={{ width: 400, height: 400 }}
  source={{ uri: resolvedAsset.uri }}
  autoPlay
  loop
/>
```

#### Metro Configuration

Ensure your asset extensions are registered in `metro.config.js`:

```js
// metro.config.js
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

const config = {
  resolver: {
    assetExts: [...getDefaultConfig(__dirname).resolver.assetExts, 'ktx2', 'glb', 'zst'],
  },
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
```

### Android Assets Folder (Alternative)

Place the file directly in Android's assets folder:

```
android/app/src/main/assets/animations/glow.ktx2
```

Then use the `asset://` URI scheme:

```jsx
<KTX2PlayerView
  style={{ width: 400, height: 400 }}
  source={{ uri: 'asset://animations/glow.ktx2' }}
  autoPlay
  loop
/>
```

> **Note:** This approach is Android-only. For cross-platform, use the bundled asset method above.

### Local File Path

For files on the device filesystem:

```jsx
<KTX2PlayerView
  style={{ width: 400, height: 400 }}
  source={{ uri: 'file:///path/to/animation.ktx2' }}
  autoPlay
  loop
/>
```

---

## Multi-Part Animations

For large animations that exceed GPU texture size limits, the encoder splits frames across multiple KTX2 files. All parts are loaded into GPU memory at startup.

```jsx
<KTX2PlayerView
  source={{ uri: 'https://example.com/animation.ktx2' }}
  parts={[
    { uri: 'https://example.com/anim_0.ktx2' },
    { uri: 'https://example.com/anim_1.ktx2' },
    { uri: 'https://example.com/anim_2.ktx2' },
  ]}
  autoPlay
  loop
/>
```

The first part (`source`) contains the animation metadata. The `parts` array provides explicit URIs for all parts. The player loads and decodes all parts before playback begins, then switches between textures as frames advance.

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

### Metro Configuration for GLB

To use GLB files as bundled assets in React Native, add the extension to your `metro.config.js`:

```javascript
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

const config = {
  resolver: {
    assetExts: [...getDefaultConfig(__dirname).resolver.assetExts, 'ktx2', 'glb'],
  },
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
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

Works with React Native Web. Requires external scripts loaded before your app bundle.

### Required Scripts

```html
<!-- Zstandard decompression (for zstd-compressed KTX2/GLB files) -->
<script src="https://cdn.jsdelivr.net/npm/fzstd@0.1.1/umd/index.js"></script>

<!-- Basis Universal transcoder (for UASTC files) -->
<script src="/basis_transcoder.js"></script>
```

Copy the Basis transcoder to your public directory:

```bash
cp node_modules/react-native-ktx2player/web/libs/basis_transcoder.js public/
cp node_modules/react-native-ktx2player/web/libs/basis_transcoder.wasm public/
```

### Web Texture Format Support

| Format | Browser Support | Notes |
|--------|-----------------|-------|
| **Native ASTC** | Mobile browsers, some desktop | Requires `WEBGL_compressed_texture_astc` |
| **UASTC** | All (transcoded) | Transcodes to BC7/ETC2/RGBA at runtime |
| **Zstd compression** | All (with fzstd) | Requires fzstd library loaded |

### Expo Web Build

For Expo web builds, inject the scripts in your build process. Example post-build script:

```javascript
// Add to your build pipeline
const scripts = `
  <script src="https://cdn.jsdelivr.net/npm/fzstd@0.1.1/umd/index.js"></script>
  <script src="/basis_transcoder.js"></script>
`;
// Inject into <head> of dist/index.html
```

---

## Playback Modes

The player supports multiple playback strategies optimized for different use cases.

### Memory Strategy: VRAM vs RAM

The `streamingStrategy` prop controls where the main frame buffer is located. The goal is to **maximize** usage of one memory type while **minimizing** the other:

| Strategy | Main Buffer | Behavior | Performance |
|----------|-------------|----------|-------------|
| `vram` (default) | GPU VRAM | Maximizes VRAM, minimizes RAM | **Generally preferred** - VRAM is separate from app memory, can use 2GB+ without affecting app performance |
| `ram` | CPU RAM | Maximizes RAM, minimizes VRAM | Use when VRAM is constrained - but note that high RAM usage (1GB+) can cause app slowdowns |

**Recommendation:** Use `vram` mode unless you have a specific reason to minimize GPU memory usage. VRAM is allocated separately from your app's heap and doesn't contribute to memory pressure warnings.

### Non-Streaming (Default)

All frames are loaded into memory before playback begins. Best for short-to-medium animations. Works with both **texture arrays** and **atlas** packing modes, and supports both **Native ASTC** and **UASTC** encoding.

```jsx
<KTX2PlayerView
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
<KTX2PlayerView
  source={{ uri: 'https://example.com/animation.ktx2' }}
  streamingStrategy="ram"
  autoPlay
  loop
/>
```

### Streaming Mode

For very long animations or memory-constrained scenarios, streaming mode decodes frames on-demand during playback using a ring buffer. Only a small window of frames is kept in memory at any time, providing **constant and predictable memory usage** regardless of animation length.

**Streaming requirements:**
- File must use `.ktx2.zst` extension (externally Zstd-compressed KTX2)
- Must use **texture array** packing (not atlas)
- Must use **Native ASTC** encoding (not UASTC)
- The KTX2 inside is uncompressed; Zstd compression is applied to the whole container externally

**Enable streaming by using `.ktx2.zst` file extension:**

```jsx
<KTX2PlayerView
  source={{ uri: 'https://example.com/animation.ktx2.zst' }}
  streamingStrategy="vram"
  streamingRingBufferSize={20}
  streamingCacheSize={60}
  streamingPrefetchAhead={15}
  autoPlay
  loop
/>
```

#### Streaming Props

| Prop | Default | Description |
|------|---------|-------------|
| `streamingStrategy` | `"vram"` | `"vram"` = GPU ring buffer (minimizes RAM), `"ram"` = CPU cache + GPU ring buffer (minimizes VRAM) |
| `streamingRingBufferSize` | `20` | Number of GPU texture slots in ring buffer |
| `streamingCacheSize` | `60` | (RAM mode only) Number of decoded frames to keep in CPU memory |
| `streamingPrefetchAhead` | `15` | How many frames to decode ahead of playback position |

#### VRAM Streaming

Frames are decoded directly into a GPU texture ring buffer. Minimal CPU RAM usage - only the ring buffer exists in VRAM.

```
[GPU Ring Buffer: 20 slots]
Slot 0: Frame 100  ← Currently displaying
Slot 1: Frame 101
Slot 2: Frame 102
...
Slot 19: Frame 119 ← Prefetched ahead
```

#### RAM Streaming

Frames are first decoded to a CPU RAM cache, then uploaded to a smaller GPU ring buffer on demand. Minimizes VRAM usage while providing a larger decode-ahead buffer.

```
[CPU Cache: 60 frames] → [GPU Ring Buffer: 20 slots]
Frames 80-139 cached    Frames 100-119 in GPU
```

#### Why Streaming?

Streaming enables playback of arbitrarily long animations with **fixed memory footprint**:
- A 10,000 frame animation uses the same memory as a 100 frame animation
- Memory usage is fully configurable via ring buffer and cache size props
- Ideal for long-form content, digital signage, or memory-constrained devices

### Encoding for Streaming

```bash
node encode-basis.js \
  --input ./frames \
  --output ./animation.ktx2 \
  --width 400 \
  --height 400 \
  --fps 30 \
  --no-mipmap \
  --astc \
  --astc-quality exhaustive \
  --astc-block 4x4 \
  --astc-perceptual \
  --zstd 22 \
  --max-texture-size 8192 \
  --mode array \
  --stream
```

The `--stream` flag produces an uncompressed ASTC texture array inside the KTX2 container. When combined with `--zstd N` (where N > 0), the encoder internally applies Zstd compression to the entire container and outputs a `.ktx2.zst` file suitable for streaming. This all happens in a single encoding command.

---

## Uncompressed ASTC Streaming

For maximum playback performance and massive concurrency, uncompressed ASTC streaming eliminates all runtime Zstd decompression overhead. The KTX2 file contains raw ASTC texture data that can be uploaded directly to the GPU.

### When to Use

- **50-100+ simultaneous players** on a single device
- **Battery-critical applications** (kiosks, digital signage)
- **Guaranteed smooth playback** regardless of device CPU
- **Storage is not a constraint** (local assets, fast network)

### Performance Characteristics

| Metric | Zstd Compressed (.ktx2.zst) | Uncompressed (.ktx2) |
|--------|-----------------|---------------|
| File size | ~5 MB | ~29 MB (6x larger) |
| CPU during playback | Zstd decode + upload | Upload only |
| Concurrent players | 10-20 typical | 50-100+ tested |
| Battery impact | Moderate | Minimal |
| Memory bandwidth | Lower | Higher |

### Encoding Uncompressed ASTC

```bash
node encode-basis.js \
  --input ./frames \
  --output ./animation_uncompressed.ktx2 \
  --width 400 \
  --height 400 \
  --fps 30 \
  --no-mipmap \
  --astc \
  --astc-quality exhaustive \
  --astc-block 4x4 \
  --astc-perceptual \
  --zstd 0 \
  --max-texture-size 8192 \
  --mode array \
  --stream
```

The only difference from compressed streaming is `--zstd 0` instead of `--zstd 22`. This produces an uncompressed `.ktx2` file (not `.ktx2.zst`) that uses memory-mapped I/O for zero-copy frame access.

### Usage

```jsx
// Uncompressed streaming uses .ktx2 extension (not .ktx2.zst)
// The player auto-detects uncompressed format from file header
<KTX2PlayerView
  source={{ uri: 'https://example.com/animation_uncompressed.ktx2' }}
  streamingStrategy="vram"
  streamingRingBufferSize={20}
  autoPlay
  loop
/>
```

### How It Works

1. **Memory-mapped file access** - The KTX2 file is mmap'd, allowing the OS to page frames in/out efficiently
2. **Direct GPU upload** - ASTC blocks are copied directly to GPU texture memory (no Zstd decompression needed)
3. **Ring buffer playback** - Only `streamingRingBufferSize` frames are in GPU memory at once
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
| `source` | `{ uri: string }` | Required | Animation source URL or file path |
| `style` | `ViewStyle` | — | Container style |
| `autoPlay` | `boolean` | `false` | Start playback automatically when loaded |
| `loop` | `boolean` | `false` | Loop playback continuously |
| `paused` | `boolean` | `false` | Pause/resume playback |
| `cacheKey` | `string` | — | Custom cache key for remote files |
| `parts` | `Array<{ uri: string }>` | — | Explicit part URIs for multi-part animations |
| `streamingStrategy` | `"vram"` \| `"ram"` | `"vram"` | Memory strategy for playback |
| `streamingRingBufferSize` | `number` | `20` | GPU texture slots for streaming |
| `streamingCacheSize` | `number` | `60` | CPU cache size for RAM streaming |
| `streamingPrefetchAhead` | `number` | `15` | Frames to decode ahead |
| `onStateChange` | `(event) => void` | — | Called when player state changes |
| `onError` | `(event) => void` | — | Called on playback errors |
| `onLoop` | `(event) => void` | — | Called when animation loops |

### State Change Events

```jsx
<KTX2PlayerView
  onStateChange={({ nativeEvent }) => {
    // nativeEvent.state: "loading" | "ready" | "playing" | "paused" | "stopped"
    console.log('State:', nativeEvent.state);
  }}
/>
```

### Imperative Methods

```jsx
const playerRef = useRef(null);

<KTX2PlayerView ref={playerRef} ... />

// Control playback
playerRef.current?.play();
playerRef.current?.pause();
playerRef.current?.stop();
playerRef.current?.seekToFrame(30);
```

---

## License

MIT