# React Native KTX2 Player

A high-performance animation player for React Native, designed for smooth, transparent animations (like glowing buttons, effects, or UI highlights) with minimal CPU, RAM, and battery usage.

Instead of playing videos or swapping images every frame, this library uploads a GPU-compressed texture once and lets the GPU handle the animation entirely.

---

## Why this exists (in plain English)

If you want an animated glow, shimmer, or effect in a mobile app, the usual options are:

### ❌ Image sequences (PNG/APNG)
- Large file sizes
- Frames are decoded and uploaded repeatedly
- High RAM usage
- CPU work every frame

### ❌ Videos (WebM / MP4 with alpha)
- Smaller files, but…
- Video decoder runs constantly
- Frames are uploaded to the GPU every frame
- Higher battery drain
- Harder to control playback precisely

### ✅ KTX2 + GPU textures (this library)
- Very small files on disk
- Uploaded to the GPU once
- Animation runs fully on the GPU
- Near-zero CPU usage during playback
- Ideal for looping UI effects with transparency

> **Best mental model:**  
> This works like a tiny game engine sprite system — not a video player.

---

## What this library does

1. Takes a sequence of PNG frames
2. Packs them into a GPU-friendly sprite sheet
3. Compresses them using Basis Universal (KTX2)

At runtime:
1. Transcodes to the device's native GPU format (ASTC / ETC2)
2. Uploads once to the GPU
3. Animates by changing texture coordinates (UVs)

**No per-frame decoding. No per-frame uploads.**

---

## Key Advantages

### 🚀 Performance
- **CPU:** Near zero usage while playing
- **RAM:** No per-frame allocations
- **GPU:** Efficient sampling of compressed textures
- **Battery:** Much lower than video playback

### 📦 File Size
- Much smaller than PNG sequences
- Comparable to video, but without decoder overhead

### 🎨 Visual Quality
- Full alpha transparency
- Smooth looping animations
- Excellent quality with ASTC / UASTC

### 🔁 Perfect for UI animations
- Glows
- Highlights
- Shimmers
- Animated icons
- Kiosk / always-on displays

---

## Features

| Feature | Description |
|---------|-------------|
| **GPU Texture Compression** | Uses KTX2 with UASTC / ASTC / ETC2 for optimal size and quality |
| **Sprite Sheet Layout** | Frames are packed into a 2D grid inside GPU texture limits |
| **Automatic Splitting** | Large animations are split into multiple texture strips when needed |
| **Alpha Transparency** | Designed specifically for transparent effects |
| **Async Loading** | Loading and transcoding never block the UI thread |
| **File Caching** | Optional on-device caching for remote animations |

---

## Installation

```bash
npm install react-native-ktx2player

# iOS
cd ios && pod install
```

Basis Universal is bundled — no extra native dependencies required.

---

## Basic Usage

```jsx
import { KTX2PlayerView } from 'react-native-ktx2player';

<KTX2PlayerView
  style={{ width: 200, height: 200 }}
  source={{
    uri: 'https://example.com/animation.ktx2',
    metadata: JSON.stringify(animationMetadata),
  }}
  autoPlay
  loop
/>
```

### With caching and events

```jsx
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

---

## How this compares to images and video

| Method | CPU | RAM | GPU Uploads | File Size | Battery |
|--------|-----|-----|-------------|-----------|---------|
| PNG sequence | High | High | Every frame | Very large | High |
| APNG | Medium | High | Every frame | Large | Medium |
| Video (WebM) | Medium | Medium | Every frame | Small | Medium–High |
| **KTX2 (this)** | **Very low** | **Low** | **Once** | **Small** | **Low** |

---

## Source Format

The `source` prop includes:
- `uri` — URL or local path to the KTX2 file(s)
- `metadata` — JSON describing how frames are laid out

### Example metadata

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

You normally generate this automatically using an encoder.

---

## Creating KTX2 Animations

Convert a PNG frame sequence into KTX2:

```bash
node encode-ktx2.js \
  --input ./frames/*.png \
  --output ./animation \
  --frameWidth 400 \
  --frameHeight 400 \
  --fps 30
```

This produces:
- One or more `.ktx2` files (GPU textures)
- A `.json` metadata file

---

## Platform Support

| Platform | Renderer | GPU Format |
|----------|----------|------------|
| iOS (device) | Metal | ASTC |
| iOS (simulator) | Metal | RGBA fallback |
| Android | OpenGL ES 3.0 | ASTC or ETC2 |

> Simulators fall back to uncompressed textures. Real devices use GPU compression.

---

## Performance Notes

- Upload happens once, not per frame
- GPU memory usage is higher than file size (this is normal)
- Best used for repeating UI effects, not long videos

**Ideal when you need:**
- Smooth loops
- Transparency
- Predictable performance

---

## When not to use this

- Long, non-looping videos
- Camera footage
- Audio-driven content
- Content that must stream continuously

---

## License

MIT