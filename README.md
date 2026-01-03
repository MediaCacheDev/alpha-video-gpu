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
    assetExts: [...getDefaultConfig(__dirname).resolver.assetExts, 'ktx2', 'glb'],
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