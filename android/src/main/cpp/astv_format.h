#pragma once
// ASTV (ASTC Texture Video) File Format
//
// Purpose-built binary format for streaming ASTC texture video with
// codec-level interframe compression. Replaces KTX2 for temporal video.
//
// Architecture:
//   [Header]  - Fixed 64 bytes
//   [Codebook] - N x 16 bytes raw ASTC blocks (uncompressed, random access)
//   [Frame Index Table] - Per-frame byte offsets for seeking
//   [Frame Data] - Sequential zstd-compressed frame chunks

#include <cstdint>
#include <cstddef>

// File magic: "ASTV" + version
static constexpr uint32_t ASTV_MAGIC = 0x56545341; // "ASTV" little-endian
static constexpr uint16_t ASTV_VERSION = 1;

// Block types for per-block encoding decisions
enum AstvBlockType : uint8_t {
    ASTV_BLOCK_SKIP  = 0,  // Unchanged from previous frame - no data
    ASTV_BLOCK_I     = 1,  // Intra block - raw codebook index
    ASTV_BLOCK_P     = 2,  // Predicted block - motion vector (dx,dy) into prev frame
    ASTV_BLOCK_INTRA = 3,  // Intra-predicted from spatial neighbours + codebook index
};

// Frame types
enum AstvFrameType : uint8_t {
    ASTV_FRAME_I = 0,  // All blocks are I-blocks (keyframe / GOP start)
    ASTV_FRAME_P = 1,  // Can contain skip, P, and I blocks
};

// File header - 64 bytes, all little-endian
struct __attribute__((packed)) AstvHeader {
    uint32_t magic;                // ASTV_MAGIC                    +4  = 4
    uint16_t version;              // ASTV_VERSION                  +2  = 6
    uint16_t flags;                // Reserved                      +2  = 8

    uint16_t width;                // Texture width in pixels       +2  = 10
    uint16_t height;               // Texture height in pixels      +2  = 12
    uint8_t  astc_block_w;         // ASTC block width              +1  = 13
    uint8_t  astc_block_h;         // ASTC block height             +1  = 14
    uint16_t blocks_x;             // ceil(width / block_w)         +2  = 16
    uint16_t blocks_y;             // ceil(height / block_h)        +2  = 18

    uint32_t frame_count;          // Total frames                  +4  = 22
    uint16_t fps_num;              // Frame rate numerator          +2  = 24
    uint16_t fps_den;              // Frame rate denominator        +2  = 26

    uint32_t codebook_entries;     // Number of codebook entries    +4  = 30
    uint32_t codebook_offset;      // Byte offset to codebook       +4  = 34
    uint32_t frame_index_offset;   // Byte offset to frame index    +4  = 38
    uint32_t frame_data_offset;    // Byte offset to frame data     +4  = 42

    uint16_t gop_length;           // Frames between I-frames       +2  = 44
    uint8_t  index_bits;           // Bits per codebook index       +1  = 45
    uint8_t  has_alpha;            // 1 if ASTC includes alpha      +1  = 46

    uint32_t codebook_compressed;  // Compressed codebook size (0=uncompressed) +4 = 50
    uint8_t  reserved[14];         // Padding to 64 bytes           +14 = 64
};

static_assert(sizeof(AstvHeader) == 64, "AstvHeader must be 64 bytes");

// Frame index table entry - 12 bytes per frame
struct __attribute__((packed)) AstvFrameIndex {
    uint32_t byte_offset;          // Offset from frame_data_offset
    uint32_t compressed_size;      // zstd compressed size of this frame's data
    uint8_t  frame_type;           // AstvFrameType
    uint8_t  flags;                // Reserved
    uint16_t padding;              // Align to 12 bytes
};

static_assert(sizeof(AstvFrameIndex) == 12, "AstvFrameIndex must be 12 bytes");

// Per-frame encoded data layout (after zstd decompression):
//
// For I-frames:
//   [blocks_x * blocks_y * index_bytes]  -- raw codebook indices for every block
//
// For P-frames:
//   [block_type_flags]  -- packed 2-bit flags per block (skip/I/P/intra)
//   [I-block data]      -- codebook indices for I-blocks
//   [P-block data]      -- (dx, dy) motion vectors for P-blocks
//
// Block type flags are packed 4 per byte: (b0 << 0) | (b1 << 2) | (b2 << 4) | (b3 << 6)

// Motion vector for P-blocks: signed byte pair
struct __attribute__((packed)) AstvMotionVector {
    int8_t dx;  // Block-level horizontal offset
    int8_t dy;  // Block-level vertical offset
};

// Convenience: compute blocks per frame
inline uint32_t astv_blocks_per_frame(const AstvHeader& h) {
    return (uint32_t)h.blocks_x * h.blocks_y;
}

// Convenience: codebook size in bytes
inline uint32_t astv_codebook_bytes(const AstvHeader& h) {
    return h.codebook_entries * 16; // Each ASTC block is 16 bytes
}

// Bytes per index: 1 (<=256), 2 (<=65536), or 4 (>65536)
inline uint32_t astv_index_bytes(const AstvHeader& h) {
    if (h.codebook_entries <= 256) return 1;
    if (h.codebook_entries <= 65536) return 2;
    return 4;
}
