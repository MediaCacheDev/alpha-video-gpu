#!/bin/bash
# Copies the native range reader and stores from an alpha-video-source checkout into
# third_party/alpha-video-source, and records its commit there.
#
#   scripts/vendor_alpha_video_source.sh [path to alpha-video-source]
#
# Without a path it looks for a checkout beside this repository, or one folder further up.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
if [ $# -ge 1 ]; then
    SOURCE_DIR="$1"
else
    SOURCE_DIR=""
    for candidate in "$PROJECT_DIR/../alpha-video-source" "$PROJECT_DIR/../../alpha-video-source"; do
        if [ -d "$candidate/native/src" ]; then SOURCE_DIR="$candidate"; break; fi
    done
fi
if [ -z "$SOURCE_DIR" ] || [ ! -d "$SOURCE_DIR/native/src" ]; then
    echo "No alpha-video-source checkout found${SOURCE_DIR:+ at $SOURCE_DIR}; pass its path." >&2
    exit 1
fi
SOURCE_DIR="$(cd "$SOURCE_DIR" && pwd)"
DEST="$PROJECT_DIR/third_party/alpha-video-source"

COMMIT="$(git -C "$SOURCE_DIR" rev-parse HEAD)"
if [ -n "$(git -C "$SOURCE_DIR" status --porcelain -- native/src)" ]; then
    COMMIT="$COMMIT, with uncommitted changes"
fi

rm -rf "$DEST/src"
mkdir -p "$DEST/src"
cp "$SOURCE_DIR"/native/src/*.h "$SOURCE_DIR"/native/src/*.cpp "$DEST/src/"
cp "$SOURCE_DIR/LICENSE" "$DEST/LICENSE"

cat > "$DEST/README.md" <<EOF
# alpha-video-source (native)

\`src/\` is \`native/src\` from [alpha-video-source](https://github.com/MediaCacheDev/alpha-video-source), unmodified, at commit \`$COMMIT\`. MIT, by the same author as this repository.

It reads a file by range from an upstream (the platform's HTTP fetcher or an app's data source), through blocks in memory and an optional store on disk or in memory. The player reads URLs and app data sources through it on Android and iOS. On Android its sources compile with the player's native code, with \`src/\` on the include path. On iOS \`ios/Sources/AlphaVideoGPU/alpha_video_source.{h,cpp}\` include them by their path under \`third_party\` and rename the \`avsource\` namespace, so a copy from another package in the same app can't clash.

To update, run \`scripts/vendor_alpha_video_source.sh\` against a newer checkout. Its tests live in that repository (\`native/test\`).
EOF
echo "Vendored alpha-video-source native sources at $COMMIT"
