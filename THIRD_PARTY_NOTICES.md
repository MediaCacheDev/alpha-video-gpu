# Third-party notices

The code in this repository is MIT-licensed (see `LICENSE`), apart from the components below.

| Component | Version | Licence | Where |
|---|---|---|---|
| [Zstandard](https://github.com/facebook/zstd) single-file library (`zstd.c`, `zstd.h`) | 1.4.9 | BSD-3-Clause | `third_party/zstd`, compiled into the iOS and Android libraries |
| ASTC decoder (`astc_decomp.cpp`): the Android Open Source Project's drawElements Quality Program decoder, as adapted in [Basis Universal](https://github.com/BinomialLLC/basis_universal) | from Basis Universal 1.16.4 | Apache-2.0 | `ios/Sources/AlphaVideoGPU/astc_decomp.{h,cpp}` and `android/src/main/cpp/astc_decomp.{h,cpp}`, compiled into the iOS and Android libraries, and the Android copy into the web package's WebAssembly |

Licence texts: `third_party/zstd/LICENSE` (Zstandard's BSD licence) and `third_party/licenses/Apache-2.0.txt`.

The web package, [`alpha-video-gpu-web`](web), bundles [zstddec](https://github.com/donmccurdy/zstddec); see [`web/THIRD_PARTY_NOTICES.md`](web/THIRD_PARTY_NOTICES.md).
