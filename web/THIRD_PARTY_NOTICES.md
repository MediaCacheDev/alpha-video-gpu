# Third-party notices

The code in this package is MIT-licensed (see `LICENSE`). The built files in `dist/` also contain, inlined as JavaScript and base64 WebAssembly:

| Component | Version | Licence | Licence text |
|---|---|---|---|
| [Basis Universal](https://github.com/BinomialLLC/basis_universal) WebAssembly transcoder (`webgl/transcoder/build/basis_transcoder.{js,wasm}`) | build from December 2025 | Apache-2.0 | `licenses/Apache-2.0.txt` |
| [zstddec](https://github.com/donmccurdy/zstddec), `dist/zstddec-stream.esm.js` | 0.2.0-alpha.4 | MIT; the WebAssembly decoder inside it is [Zstandard](https://github.com/facebook/zstd), BSD-3-Clause | `licenses/zstddec-LICENSE.txt` (both licences) |
| regenerator-runtime, inside the zstddec build | | MIT | <https://github.com/babel/babel/blob/main/packages/babel-helpers/LICENSE> |

The source files are in `libs/` and `src/zstddec-stream-bundled.js` in the [repository](https://github.com/danielkpl2/alpha-video-gpu/tree/main/web); `npm run bundle-basis` inlines the Basis files.
