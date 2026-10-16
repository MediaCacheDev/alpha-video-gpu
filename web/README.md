# alpha-video-gpu-web

Plays transparent (alpha) animations stored as ASTC texture arrays in KTX2 files, in a `<canvas>` with WebGL 2. Where the browser has `WEBGL_compressed_texture_astc` the GPU samples the ASTC directly; elsewhere the frames are decoded to RGBA in WebAssembly, as the Android and iOS players do without ASTC. The zstd decoder (JS and WebAssembly) is bundled inline: there are no files to host and no `<script>` tags.

It is the web half of [alpha-video-gpu](https://github.com/MediaCacheDev/alpha-video-gpu), whose React Native component uses it on React Native Web. Encoding, streaming and the file format are described there.

```bash
npm install alpha-video-gpu-web
```

## Plain JS/TS

```ts
import { createAlphaVideoGpuPlayer } from 'alpha-video-gpu-web';

const player = createAlphaVideoGpuPlayer(document.querySelector('canvas')!, {
  autoPlay: true,
  loop: true,
  stream: true,
  onLoad: ({ width, height, frameCount, frameRate, duration }) => {},
  onStateChange: (state) => {},
  onError: (error) => console.error(error),
});

await player.setSource('https://example.com/animation.ktx2');
player.pause();
player.resume();
player.seekToFrame(0);
player.seekToTime(1.5);
player.destroy();
```

A seek shows the frame, clamped to the clip, and playback carries on from it or stays paused; seeks before the source loads are ignored. `stop()` pauses on the first frame. `setPaused(paused)` is the `paused` prop: it holds playback, and unpausing plays again when `autoPlay` is on.

`setSource(uri, cacheKey?)` caches downloads in IndexedDB, under `cacheKey` when given (for URLs that change while the file doesn't). `AlphaVideoGpuCore` is the player class itself, for supplying your own decoders.

## React

```tsx
import { AlphaVideoGpuPlayer } from 'alpha-video-gpu-web/react';

<AlphaVideoGpuPlayer
  src="https://example.com/animation.ktx2"
  width={300}
  height={300}
  autoPlay
  loop
  onError={(error) => console.error(error)}
/>
```

Props: `src`, `cacheKey`, `width`, `height`, `className`, `style`, `autoPlay`, `loop`, `playbackSpeed`, `paused`, `stream`, `streamingRingSize`, `streamingCacheSize`, `streamingPrefetchAhead`, `memoryStrategy`, `verbose`, and the callbacks `onLoad`, `onStateChange`, `onFrameUpdate`, `onFinished`, `onError`. A ref has `play()`, `pause()`, `stop()`, `seekToFrame(frame)` and `seekToTime(seconds)`. React 16.8 or later is an optional peer dependency.

## Without GPU ASTC

When the browser has no `WEBGL_compressed_texture_astc`, each frame is decoded to RGBA on the main thread when it's uploaded, with the decoder the Android and iOS players use, compiled to WebAssembly. It loads as a separate 42 KB chunk, only in those browsers. Bundlers (webpack, Vite, esbuild) take the ESM build even for a `require`, as the `module` export condition comes first, so React Native for Web gets the chunk too; only Node's CommonJS build inlines it. What changes:

- **Speed.** About 1.2 ms per 400×400 frame on an Apple M4, so roughly 16 ms at 1080p; slower CPUs take proportionally longer.
- **Memory.** Textures are RGBA8, 4 bytes a pixel against 1 for `astc4x4` (and less for larger blocks), so a VRAM streaming ring of `streamingCacheSize` frames takes that much more GPU memory. Preloaded (`stream={false}`) playback keeps the clip as ASTC in memory and decodes only the shown frame, whatever `memoryStrategy` says, as every frame in RGBA could need gigabytes.

`verbose` logs which path a player took.

## Errors

`onError` gets an `Error`. The ones a page may want to handle are an `AlphaVideoGpuError` with a `code`:

- `'ASTC_UNSUPPORTED'`: the browser lacks `WEBGL_compressed_texture_astc`, and the software decoder couldn't load either, as when a Content-Security-Policy doesn't allow WebAssembly (`'wasm-unsafe-eval'`). Show something else in its place.
- `'WEBGL_UNSUPPORTED'`: no WebGL 2 context could be created.

```ts
import type { AlphaVideoGpuError } from 'alpha-video-gpu-web';

onError: (error) => {
  if ((error as AlphaVideoGpuError).code === 'ASTC_UNSUPPORTED') {
    showPosterImage();
  }
},
```

## Building

`npm run build` builds `dist/` (ESM, CommonJS and type declarations) with tsup. `npm run build:wasm` rebuilds the ASTC decoder's WebAssembly into `src/astc-wasm.ts`, which is committed; it needs Emscripten (`emcc`).

## Testing

`npm test` runs the unit tests, which include decoding every frame of the test-card fixtures in `../tests/fixtures` with the software decoder and reading back each frame's index from its pixels.

`test/browser/index.html` plays the test cards in a real browser, in every streaming and memory mode, and checks the frames from the canvas after seeks and in playback. Build `dist/`, serve a folder that holds this repository over HTTP, and open the page; `?astc=off` hides `WEBGL_compressed_texture_astc` to test the software decoder, `?cards=<path>` points at other cards (the demo app's include uncompressed files), and `?only=<regex>` picks cases. Results show on the page and in `window.results`.

## License

MIT. The bundled zstd decoder is MIT (zstddec) and BSD-3-Clause (zstd), and the ASTC decoder Apache-2.0; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
