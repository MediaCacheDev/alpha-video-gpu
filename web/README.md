# alpha-video-gpu-web

Plays transparent (alpha) animations stored as KTX2 textures in a `<canvas>`, with WebGL 2 (or WebGL 1). Native ASTC files need `WEBGL_compressed_texture_astc`, which most mobile browsers have; Basis Universal UASTC files are transcoded to whatever the GPU supports, so they play everywhere. The Basis transcoder and the zstd decoder (JS and WebAssembly) are bundled inline: there are no files to host and no `<script>` tags.

It is the web half of [alpha-video-gpu](https://github.com/danielkpl2/alpha-video-gpu), whose React Native component uses it on React Native Web. Encoding, streaming and the file format are described there.

```bash
npm install alpha-video-gpu-web
```

## Plain JS/TS

```ts
import { createAlphaVideoGpuPlayer } from 'alpha-video-gpu-web';

const player = createAlphaVideoGpuPlayer(document.querySelector('canvas')!, {
  autoPlay: true,
  loop: true,
  stream: false,
  onLoad: ({ width, height, frameCount, frameRate, duration }) => {},
  onStateChange: (state) => {},
  onError: (error) => console.error(error),
});

await player.setSource('https://example.com/animation.ktx2');
player.pause();
player.resume();
player.seekToFrame(0);
player.destroy();
```

`setSource(uri, partUris?, cacheKey?)` also takes the parts of a multi-part animation. Downloads are cached in IndexedDB, under `cacheKey` when given (for URLs that change while the file doesn't). `AlphaVideoGpuCore` is the player class itself, for supplying your own decoders.

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

Props: `src`, `parts`, `cacheKey`, `width`, `height`, `className`, `style`, `autoPlay`, `loop`, `playbackSpeed`, `paused`, `stream`, `streamingRingSize`, `streamingCacheSize`, `streamingPrefetchAhead`, `memoryStrategy`, `verbose`, and the callbacks `onLoad`, `onStateChange`, `onFrameUpdate`, `onFinished`, `onError`. React 16.8 or later is an optional peer dependency.

## Building

`npm run build` inlines `libs/basis_transcoder.{js,wasm}` into `src/basis-transcoder.ts` and builds `dist/` (ESM, CommonJS and type declarations) with tsup.

## License

MIT. The bundled decoders are Apache-2.0 (Basis Universal), MIT (zstddec) and BSD-3-Clause (zstd); see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
