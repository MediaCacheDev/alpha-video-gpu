# alpha-video-source (native)

`src/` is `native/src` from [alpha-video-source](https://github.com/MediaCacheDev/alpha-video-source), unmodified, at commit `2c7300f0b04ddb6364409d87f4c99c869a47a182`. MIT, by the same author as this repository.

It reads a file by range from an upstream (the platform's HTTP fetcher or an app's data source), through blocks in memory and an optional store on disk or in memory. The player reads URLs and app data sources through it on Android and iOS. On Android its sources compile with the player's native code, with `src/` on the include path. On iOS `ios/Sources/AlphaVideoGPU/alpha_video_source.{h,cpp}` include them by their path under `third_party` and rename the `avsource` namespace, so a copy from another package in the same app can't clash.

To update, run `scripts/vendor_alpha_video_source.sh` against a newer checkout. Its tests live in that repository (`native/test`).
