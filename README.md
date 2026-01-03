# React Native KTX2 Player

A GPU-accelerated texture animation player for React Native (iOS, Android, Web). Plays pre-encoded KTX2 sprite sheet animations with hardware texture compression support.

## Overview

This library renders animations from KTX2 texture files containing sprite sheets. Frames are packed into GPU-compressed textures and uploaded once, then animated by updating UV coordinates on the GPU.

**Key characteristics:**
- Single texture upload per animation part (no per-frame decoding)
- Hardware texture compression (ASTC on iOS/Android, transcoded formats on web)
- Software RGBA fallback for emulators and unsupported devices
- Metadata embedded in KTX2 files or provided separately

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

Include a `.ktx2` file in your React Native project and use `require()` with `Image.resolveAssetSource()`:

```jsx
import { KTX2PlayerView } from 'react-native-ktx2player';
import { Image } from 'react-native';

// Import the asset (returns an asset ID number)
const ktx2Asset = require('./assets/animation.ktx2');

// Resolve to platform-specific URI
const resolvedAsset = Image.resolveAssetSource(ktx2Asset);

<KTX2PlayerView
  style={{ width: 400, height: 400 }}
  source={{ uri: resolvedAsset.uri }}
  autoPlay
  loop
/>
```

**Why `Image.resolveAssetSource()` is required:**
- `require()` returns an asset ID number, not a path
- `resolveAssetSource()` converts it to a platform-specific URI:
  - **Debug (Metro):** `http://localhost:8081/assets/...`
  - **iOS Release:** `file:///path/to/bundle/...`
  - **Android Release:** Resource name resolved from APK assets

**Metro config:** Ensure `.ktx2` is in your asset extensions:

```js
// metro.config.js
const { getDefaultConfig } = require('@react-native/metro-config');
const config = getDefaultConfig(__dirname);
config.resolver.assetExts.push('ktx2');
module.exports = config;
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

For multi-part animations (large frame counts split across multiple textures), metadata includes part information:

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

## Platform Details

| Platform | Renderer | Native Format | Fallback |
|----------|----------|---------------|----------|
| iOS Device | Metal | ASTC 4x4 | — |
| iOS Simulator | Metal | — | RGBA (software decode) |
| Android Device | OpenGL ES 3.0 | ASTC or ETC2 | RGBA (software decode) |
| Android Emulator | OpenGL ES 3.0 | — | RGBA (software decode) |
| Web | WebGL | ASTC/BC7/ETC2 | Error (no software fallback) |

---

## Web Support

Works with React Native Web. Requires the Basis Universal transcoder for UASTC files:

```bash
cp -r node_modules/react-native-ktx2player/web/libs public/
```

```html
<script src="/libs/basis_transcoder.js"></script>
```

Native ASTC files on web require browser ASTC extension support (`WEBGL_compressed_texture_astc`). Most mobile browsers support this; desktop browser support varies.

---

## Performance Characteristics

- **CPU:** Near-zero during playback (UV updates only)
- **Memory:** GPU texture memory = uncompressed size (ASTC decompresses on GPU)
- **Battery:** Significantly lower than video playback
- **Load time:** One-time transcode (UASTC) or direct upload (ASTC)

Best suited for:
- Looping UI effects (glows, shimmers, highlights)
- Transparent overlays
- Kiosk/always-on displays
- Any animation where consistent frame timing matters

Not ideal for:
- Long-form video content
- Streaming media
- Audio-synced playback

---

## License

MIT