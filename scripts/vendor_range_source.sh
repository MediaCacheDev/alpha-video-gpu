#!/bin/bash
# Copies range-source's native headers (and, until the add-on is its own package, its reader and
# stores) from a range-source checkout into third_party/range-source, and records its commit there.
#
#   scripts/vendor_range_source.sh [path to range-source]
#
# Without a path it looks for a checkout beside this repository, or one folder further up.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
if [ $# -ge 1 ]; then
    SOURCE_DIR="$1"
else
    SOURCE_DIR=""
    for candidate in "$PROJECT_DIR/../range-source" "$PROJECT_DIR/../../range-source"; do
        if [ -d "$candidate/native/include" ]; then SOURCE_DIR="$candidate"; break; fi
    done
fi
if [ -z "$SOURCE_DIR" ] || [ ! -d "$SOURCE_DIR/native/include" ]; then
    echo "No range-source checkout found${SOURCE_DIR:+ at $SOURCE_DIR}; pass its path." >&2
    exit 1
fi
SOURCE_DIR="$(cd "$SOURCE_DIR" && pwd)"
DEST="$PROJECT_DIR/third_party/range-source"

COMMIT="$(git -C "$SOURCE_DIR" rev-parse HEAD)"
if [ -n "$(git -C "$SOURCE_DIR" status --porcelain -- native/include native/src)" ]; then
    COMMIT="$COMMIT, with uncommitted changes"
fi

rm -rf "$DEST/include" "$DEST/src"
mkdir -p "$DEST/include" "$DEST/src"
cp "$SOURCE_DIR"/native/include/*.h "$DEST/include/"
cp "$SOURCE_DIR"/native/src/*.h "$SOURCE_DIR"/native/src/*.cpp "$DEST/src/"
cp "$SOURCE_DIR/LICENSE" "$DEST/LICENSE"

cat > "$DEST/README.md" <<EOF
# range-source (native)

\`include/\` and \`src/\` are \`native/include\` and \`native/src\` from [range-source](https://github.com/MediaCacheDev/range-source), unmodified, at commit \`$COMMIT\`. MIT, by the same author as this repository.

- \`include/range_source.h\` is the add-on's C interface, a table of functions, with a C++ wrapper over it. The player reads URLs and data sources through it.
- \`include/range_source_download.h\` is the basic input, for when the add-on isn't there: a URL downloaded whole into a temporary file, read as it arrives.
- \`src/\` is the add-on's reader and stores, compiled in until the add-on is its own package.

On Android they compile with the player's native code. On iOS \`ios/Sources/AlphaVideoGPU/alpha_video_gpu_range_source.{h,cpp}\` include them by their path under \`third_party\` and rename their namespace and C symbols, so a copy from another package in the same app can't clash.

To update, run \`scripts/vendor_range_source.sh\` against a newer checkout. Its tests live in that repository (\`native/test\`).
EOF
echo "Vendored range-source native sources at $COMMIT"
