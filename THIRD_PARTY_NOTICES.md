# Third-party notices

The code in this repository is MIT-licensed (see `LICENSE`), apart from the components below.

| Component | Version | Licence | Where |
|---|---|---|---|
| [Basis Universal](https://github.com/BinomialLLC/basis_universal) transcoder | 1.16.4 | Apache-2.0 | `third_party/basis_universal/transcoder`, compiled into the iOS and Android libraries |
| [Zstandard](https://github.com/facebook/zstd) single-file decoder, as shipped with Basis Universal | from Basis Universal 1.16.4 | BSD-3-Clause | `third_party/basis_universal/zstd`, compiled into the iOS and Android libraries |
| ASTC decoder (`astc_decomp.cpp`): the Android Open Source Project's drawElements Quality Program decoder, as adapted in Basis Universal | from Basis Universal | Apache-2.0 | `ios/Sources/AlphaVideoGPU/astc_decomp.{h,cpp}` and `android/src/main/cpp/astc_decomp.{h,cpp}`, compiled into the iOS and Android libraries |

Licence texts: `third_party/basis_universal/LICENSE` (Apache-2.0), `third_party/basis_universal/zstd/LICENSE` (Zstandard's BSD licence), and the other licences Basis Universal ships in `third_party/basis_universal/LICENSES/`.

The web package, [`alpha-video-gpu-web`](web), bundles a WebAssembly build of the Basis Universal transcoder and [zstddec](https://github.com/donmccurdy/zstddec); see [`web/THIRD_PARTY_NOTICES.md`](web/THIRD_PARTY_NOTICES.md).
