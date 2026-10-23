# alpha-video-gpu-web

<p align="center">
  <img src="https://raw.githubusercontent.com/MediaCacheDev/alpha-video-gpu/main/docs/alpha-video-gpu.avif" alt="Transparent ASTC videos playing over a checkerboard in the demo app on Android" width="360">
  <br>
  <a href="https://github.com/MediaCacheDev/alpha-video-test">Compare transparent formats in the demo app</a>
</p>

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

A seek shows the frame, clamped to the clip, and playback carries on from it or stays paused; seeks before the source loads are ignored. While streaming, a seek outside the buffered frames starts the zstd decoder at the file's nearest seek point (the encoder writes one every 8 MB of ASTC by default), or at the start of a file written as one stream. `stop()` pauses on the first frame. `setPaused(paused)` is the `paused` prop: it holds playback, and unpausing plays again when `autoPlay` is on.

`AlphaVideoGpuCore` is the player class itself, for supplying your own decoders.

## Sources and storage

`setSource(source, { cacheKey, headers })` takes:

- **A URL:** `https://`, relative, or `asset://path` (served from the site root). It is read with HTTP range requests, so streaming starts after the first few hundred kilobytes, and a seek fetches only from its seek point on. `headers` (for example `Authorization`) go with every request. A server without range support works too, read progressively from the start.
- **A `Blob` or `File`,** read with `slice`.
- **An `ArrayBuffer` or `Uint8Array`.**
- **A byte source** of your own: `{ size, read(offset, length, signal) }`, returning a `Promise<Uint8Array>`. It's for apps that fetch, decrypt or cache in their own code. See [range-source-web](https://github.com/MediaCacheDev/range-source/tree/main/web#sources), which the player depends on, for the full interface; `httpSource`, `blobSource` and `memorySource` are re-exported here.

What a URL's bytes are kept in between plays is the `storage` option, read when the player is created:

- `'auto'` (the default for URLs) is OPFS, falling back to IndexedDB where OPFS isn't available.
- `'opfs'`, `'indexeddb'`, `'cache'` (the Cache API) or `'memory'` (this page only) pick one. Where it isn't available, the player falls back to IndexedDB, then memory.
- `'none'` stores nothing. Clips up to 16 MB still stay in memory, so loops don't fetch again.
- A `ByteStore` of your own (from `createStore`, or written against range-source-web's interface).

Storage is partial: it keeps the ranges played so far, and a clip held whole plays again without the network. `maxStorageBytes` (default 512 MB) caps what this player keeps across every clip, evicting the least recently played. Stored clips are named by `cacheKey`, else the URL, so a presigned URL that changes on each visit can keep the same copy. A copy is dropped when the server's `ETag`, `Last-Modified` or length no longer matches it. A clip held whole isn't checked again; give a changed file a new `cacheKey` or URL.

Blobs, byte sources and bytes in memory default to `'none'`, as the data is local already, or the source does its own caching. With `storage` and a `cacheKey` they are stored too.

A Service Worker controlling the page sees the player's range requests as plain `fetch`es, so it can answer them from its own cache.

Cross-origin URLs need CORS. To check versions, the server must expose `ETag` (or `Last-Modified`) and `Content-Range` with `Access-Control-Expose-Headers`; without them, copies aren't checked.

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

Props: `src` (any source `setSource` takes), `cacheKey`, `headers`, `storage`, `maxStorageBytes`, `width`, `height`, `className`, `style`, `autoPlay`, `loop`, `playbackSpeed`, `paused`, `stream`, `streamingPrefetchAhead`, `memoryStrategy`, `verbose`, and the callbacks `onLoad`, `onStateChange`, `onFrameUpdate`, `onFinished`, `onError`. A ref has `play()`, `pause()`, `stop()`, `seekToFrame(frame)` and `seekToTime(seconds)`. A new `src` loads again, as does a new `cacheKey` for a URL. New `headers` are used from the next load, and `storage` and `maxStorageBytes` are read when the component mounts. React 16.8 or later is an optional peer dependency.

## Without GPU ASTC

When the browser has no `WEBGL_compressed_texture_astc`, each frame is decoded to RGBA on the main thread when it's uploaded, with the decoder the Android and iOS players use, compiled to WebAssembly. It loads as a separate 42 KB chunk, only in those browsers. Bundlers (webpack, Vite, esbuild) take the ESM build even for a `require`, as the `module` export condition comes first, so React Native for Web gets the chunk too; only Node's CommonJS build inlines it. What changes:

- **Speed.** About 1.2 ms per 400×400 frame on an Apple M4, so roughly 16 ms at 1080p; slower CPUs take proportionally longer.
- **Memory.** Textures are RGBA8, 4 bytes a pixel against 1 for `astc4x4` (and less for larger blocks), so a VRAM streaming ring of `streamingPrefetchAhead` + 2 frames takes that much more GPU memory. Preloaded (`stream={false}`) playback keeps the clip as ASTC in memory and decodes only the shown frame, whatever `memoryStrategy` says, as every frame in RGBA could need gigabytes.

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

Other parameters exercise sources and storage:

- `?storage=<kind>` stores what's played (default `none`). The page clears that store first.
- `?replay` plays each case a second time. With a persistent store, it checks that the clip was stored whole and played again without a request.
- `?input=blob|bytes|buffer` loads each file as a `Blob`, a minimal `{ size, read }` byte source, or an `ArrayBuffer` instead of a URL.

To test range requests, serve the folder with range-source-web's server: `npm run serve -- <folder> --port 8766` in `range-source/web`. Add `--no-range` for a server without range support. Python's `http.server` doesn't support ranges either.

## License

MIT. The bundled zstd decoder is MIT (zstddec) and BSD-3-Clause (zstd), and the ASTC decoder Apache-2.0; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
