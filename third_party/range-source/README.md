# range-source (native)

`include/` is `native/include` from [range-source](https://github.com/MediaCacheDev/range-source), unmodified, at commit `5c5dd0929ddbfbb51730b41d9dfd1a468d35e58e`. MIT, by the same author as this repository.

- `include/range_source.h` is the add-on's C interface, a table of functions, with a C++ wrapper over it. The player finds the add-on at runtime and reads URLs and data sources through it.
- `include/range_source_download.h` is the basic input, for when the add-on isn't there: a URL downloaded whole into a temporary file, read as it arrives.

Each player puts the C++ wrappers in a namespace of its own (`RANGE_SOURCE_CLIENT_NAMESPACE`), so another player's copy in the same app can't clash.

To update, run `scripts/vendor_range_source.sh` against a newer checkout. Its tests live in that repository (`native/test`).
