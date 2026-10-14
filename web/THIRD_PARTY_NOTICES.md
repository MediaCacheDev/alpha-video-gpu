# Third-party notices

The code in this package is MIT-licensed (see `LICENSE`). The built files in `dist/` also contain, inlined as JavaScript and base64 WebAssembly:

| Component | Version | Licence | Licence text |
|---|---|---|---|
| [zstddec](https://github.com/donmccurdy/zstddec), `dist/zstddec-stream.esm.js`, modified | 0.2.0-alpha.4 | MIT; the WebAssembly decoder inside it is [Zstandard](https://github.com/facebook/zstd), BSD-3-Clause | `licenses/zstddec-LICENSE.txt` (both licences) |

The zstddec change: `decodeStreaming` is a native generator that frees its WebAssembly buffers in a `finally`, so a stream closed early with `return()` doesn't leak them. Without the transpiled generator, the regenerator-runtime helpers it needed were removed.

The source file is `src/zstddec-stream-bundled.js` in the [repository](https://github.com/danielkpl2/alpha-video-gpu/tree/main/web).
