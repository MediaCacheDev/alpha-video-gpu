#!/bin/bash
# Setup script for react-native-ktx2player
# Downloads Basis Universal transcoder source files (only what's needed for runtime)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "🔧 Setting up react-native-ktx2player..."
echo ""

# Basis Universal version to use
BASIS_VERSION="1.16.4"
BASIS_URL="https://github.com/BinomialLLC/basis_universal/archive/refs/tags/${BASIS_VERSION}.tar.gz"

# Temporary download location
TMP_DIR="$PROJECT_DIR/.tmp_basis"
rm -rf "$TMP_DIR"
mkdir -p "$TMP_DIR"

echo "📥 Downloading Basis Universal v${BASIS_VERSION}..."
curl -L "$BASIS_URL" -o "$TMP_DIR/basis_universal.tar.gz"

echo "📦 Extracting..."
cd "$TMP_DIR"
tar -xzf basis_universal.tar.gz
BASIS_SRC="$TMP_DIR/basis_universal-${BASIS_VERSION}"

# Function to copy only required files
copy_basis_files() {
    local dest="$1"
    mkdir -p "$dest/basis_universal/transcoder"
    mkdir -p "$dest/basis_universal/zstd"
    
    # Copy only transcoder files (required for runtime)
    cp "$BASIS_SRC/transcoder/"*.h "$dest/basis_universal/transcoder/"
    cp "$BASIS_SRC/transcoder/"*.cpp "$dest/basis_universal/transcoder/"
    cp "$BASIS_SRC/transcoder/"*.inc "$dest/basis_universal/transcoder/" 2>/dev/null || true
    
    # Copy zstd decompression (required for Zstandard-compressed KTX2)
    cp "$BASIS_SRC/zstd/"*.h "$dest/basis_universal/zstd/"
    cp "$BASIS_SRC/zstd/"*.c "$dest/basis_universal/zstd/"
    cp "$BASIS_SRC/zstd/LICENSE" "$dest/basis_universal/zstd/" 2>/dev/null || true
    
    echo "  ✅ Copied to $dest"
}

# Copy files for iOS
IOS_VENDOR="$PROJECT_DIR/ios/vendor"
if [ ! -d "$IOS_VENDOR/basis_universal/transcoder" ]; then
    echo "📱 Setting up iOS..."
    rm -rf "$IOS_VENDOR/basis_universal"
    copy_basis_files "$IOS_VENDOR"
else
    echo "✅ iOS already set up"
fi

# Copy files for Android
ANDROID_CPP="$PROJECT_DIR/android/src/main/cpp"
if [ ! -d "$ANDROID_CPP/basis_universal/transcoder" ]; then
    echo "🤖 Setting up Android..."
    rm -rf "$ANDROID_CPP/basis_universal"
    copy_basis_files "$ANDROID_CPP"
else
    echo "✅ Android already set up"
fi

# Cleanup
rm -rf "$TMP_DIR"

echo ""
echo "✅ Setup complete!"
echo ""
echo "Next steps:"
echo "  iOS:     cd ios && pod install"
echo "  Android: Build should work automatically with gradle"
echo ""
