// Software ASTC decoding for browsers without WEBGL_compressed_texture_astc. It wraps the decoder
// Android and iOS fall back to (android/src/main/cpp/astc_decomp.cpp), so the web draws the same
// pixels they do. Built into src/astc-wasm.ts by build.sh.

#include "astc_decomp.h"

#include <cstdint>
#include <cstdlib>
#include <cstring>

extern "C" {

uint8_t* astc_alloc(size_t size) { return static_cast<uint8_t*>(malloc(size)); }

void astc_free(uint8_t* ptr) { free(ptr); }

// Decodes one layer of 16-byte blocks to RGBA, width * height * 4 bytes, row-major. A block that
// fails to decode is drawn magenta, as on Android; returns how many did.
int astc_decode_layer(const uint8_t* src, int width, int height, int blockWidth, int blockHeight, uint8_t* dst) {
    const int blocksX = (width + blockWidth - 1) / blockWidth;
    const int blocksY = (height + blockHeight - 1) / blockHeight;
    uint8_t block[12 * 12 * 4];
    int failed = 0;
    for (int by = 0; by < blocksY; by++) {
        for (int bx = 0; bx < blocksX; bx++) {
            if (!basisu::astc::decompress(block, src + (by * blocksX + bx) * 16, false, blockWidth, blockHeight)) {
                for (int i = 0; i < blockWidth * blockHeight; i++) {
                    const uint8_t magenta[4] = {255, 0, 255, 255};
                    memcpy(block + i * 4, magenta, 4);
                }
                failed++;
            }
            const int x0 = bx * blockWidth, y0 = by * blockHeight;
            const int rowPixels = (x0 + blockWidth <= width ? blockWidth : width - x0);
            for (int y = 0; y < blockHeight && y0 + y < height; y++) {
                memcpy(dst + ((y0 + y) * width + x0) * 4, block + y * blockWidth * 4, rowPixels * 4);
            }
        }
    }
    return failed;
}

}  // extern "C"
