# React Native KTX2 Player

Play GPU-compressed sprite animations in React Native. Encodes PNG sequences into KTX2 files using Basis Universal, then transcodes to native GPU formats (ASTC/ETC2) at runtime for efficient playback.

## Features

- **GPU Texture Compression** - UASTC/ETC2/ASTC for small file sizes and fast GPU rendering
- **Sprite Sheet Layout** - Packs frames in 2D grid within GPU texture limits
- **Multi-Strip Support** - Large animations split across multiple files automatically
- **Alpha Transparency** - Full support for transparent animations
- **Async Loading** - Downloads don't block the UI thread
- **File Caching** - Optional `cacheKey` for persistent local storage

## Installation

```bash
npm install react-native-ktx2player

# iOS
cd ios && pod install
```

Basis Universal source is included in the package - no additional setup needed.

## Usage

```jsx
import { KTX2PlayerView } from 'react-native-ktx2player';

// Basic usage
<KTX2PlayerView
  style={{ width: 200, height: 200 }}
  source={{
    uri: 'https://example.com/animation.ktx2',
    metadata: JSON.stringify(animationMetadata),
  }}
  autoPlay
  loop
/>

// With caching and events
<KTX2PlayerView
  style={{ width: 200, height: 200 }}
  source={{
    uri: 'https://example.com/glow.ktx2',
    metadata: JSON.stringify(metadata),
  }}
  cacheKey="animations/glow.ktx2"
  autoPlay
  loop
  onStateChange={({ nativeEvent }) => console.log(nativeEvent.state)}
  onError={({ nativeEvent }) => console.error(nativeEvent.error)}
/>
```

### Source Format

The `source` prop requires:
- `uri` - URL or local path to the KTX2 file
- `metadata` - JSON string containing animation info:

```json
{
  "frameCount": 120,
  "frameWidth": 400,
  "frameHeight": 400,
  "frameRate": 30,
  "framesPerRow": 4,
  "framesPerStrip": 64,
  "stripCount": 2,
  "strips": [
    { "file": "anim_strip0.ktx2", "columns": 4, "rows": 16 },
    { "file": "anim_strip1.ktx2", "columns": 4, "rows": 14 }
  ]
}
```

## Props

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `source` | `{ uri: string, metadata: string }` | required | KTX2 file location and metadata |
| `autoPlay` | `boolean` | `true` | Start playing when loaded |
| `loop` | `boolean` | `true` | Loop animation |
| `playbackSpeed` | `number` | `1.0` | Playback speed multiplier |
| `cacheKey` | `string` | - | Cache path (e.g. `"my-app/anim.ktx2"`) |
| `enableFrameEvents` | `boolean` | `false` | Emit `onFrameUpdate` events (has overhead) |
| `onStateChange` | `function` | - | Called with `{ state: 'loading' \| 'ready' \| 'playing' \| 'paused' }` |
| `onFrameUpdate` | `function` | - | Called with `{ frame, totalFrames }` |
| `onFinished` | `function` | - | Called when non-looping animation ends |
| `onError` | `function` | - | Called with `{ error: string }` |

## Creating KTX2 Animations

Use a KTX2 encoder to convert PNG sequences:

```bash
# Example using a custom encoder script
node encode-ktx2.js \
  --input ./frames/*.png \
  --output ./animation \
  --frameWidth 400 \
  --frameHeight 400 \
  --fps 30
```

Output:
- `animation.ktx2` (or `animation_strip0.ktx2`, `animation_strip1.ktx2` for large animations)
- `animation.json` (metadata)

## Platform Details

| Platform | Renderer | GPU Format |
|----------|----------|------------|
| iOS (device) | Metal | ASTC 4x4 |
| iOS (simulator) | Metal | RGBA32 fallback |
| Android | OpenGL ES 3.0 | ASTC or ETC2 |

## Performance Tips

- Keep individual strip textures under 4096×4096 for best compatibility
- Use `enableFrameEvents={false}` unless you need per-frame callbacks
- Set `cacheKey` for remote files to avoid re-downloading
- UASTC compression gives best quality; ETC1S for smallest files

## License

MIT
