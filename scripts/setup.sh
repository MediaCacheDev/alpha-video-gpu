#!/bin/bash
# Setup script for react-native-ktx2player
# Downloads Basis Universal transcoder source files

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "🔧 Setting up react-native-ktx2player..."
echo ""

# Basis Universal version to use
BASIS_VERSION="1.16.4"
BASIS_URL="https://github.com/BinomialLLC/basis_universal/archive/refs/tags/${BASIS_VERSION}.tar.gz"

# Create vendor directories
VENDOR_DIR="$PROJECT_DIR/vendor"
mkdir -p "$VENDOR_DIR"

# Download and extract Basis Universal
if [ ! -d "$VENDOR_DIR/basis_universal" ]; then
    echo "📥 Downloading Basis Universal v${BASIS_VERSION}..."
    curl -L "$BASIS_URL" -o "$VENDOR_DIR/basis_universal.tar.gz"
    
    echo "📦 Extracting..."
    cd "$VENDOR_DIR"
    tar -xzf basis_universal.tar.gz
    mv "basis_universal-${BASIS_VERSION}" basis_universal
    rm basis_universal.tar.gz
    
    echo "✅ Basis Universal downloaded"
else
    echo "✅ Basis Universal already exists"
fi

# Copy files for iOS
IOS_VENDOR="$PROJECT_DIR/ios/vendor"
mkdir -p "$IOS_VENDOR"

if [ ! -d "$IOS_VENDOR/basis_universal" ]; then
    echo "� Copying files to iOS..."
    cp -R "$VENDOR_DIR/basis_universal" "$IOS_VENDOR/"
fi

# Copy files for Android
ANDROID_CPP="$PROJECT_DIR/android/src/main/cpp"
mkdir -p "$ANDROID_CPP"

if [ ! -d "$ANDROID_CPP/basis_universal" ]; then
    echo "� Copying files to Android..."
    cp -R "$VENDOR_DIR/basis_universal" "$ANDROID_CPP/"
fi

echo ""
echo "✅ Setup complete!"
echo ""
echo "📁 Basis Universal installed at: $VENDOR_DIR/basis_universal"
echo ""
echo "Next steps:"
echo "  iOS:     cd ios && pod install"
echo "  Android: Build should work automatically with gradle"
echo ""
