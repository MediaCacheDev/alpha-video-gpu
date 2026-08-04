#pragma once
// ASTV Decoder — Playback-side codec
//
// Designed for O(1) per-block decode cost:
//   SKIP = nothing
//   I    = one codebook array lookup (16 bytes)
//   P    = one offset array lookup + one codebook lookup
//
// NO pixel arithmetic. NO DCT. NO filters. NO color conversion.
// Just index lookups and memcpy.
//
// Per-player memory: ~160KB (codebook + 2 index maps + zstd context)
//
// Thread-safe: each Decoder instance is independent.

#include "astv_format.h"
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>
#include <string>
#include <memory>

// Forward declare zstd types
struct ZSTD_DCtx_s;
typedef ZSTD_DCtx_s ZSTD_DCtx;

namespace astv {

// Dirty tile tracking for partial GPU uploads
struct DirtyRegion {
    uint16_t x, y;       // Block-level position
    uint16_t w, h;       // Block-level dimensions
};

class Decoder {
public:
    Decoder();
    ~Decoder();

    // Load ASTV file. Returns false on error.
    bool load(const std::string& path);
    bool load_from_memory(const uint8_t* data, size_t size);

    // Decode next frame. Populates current index map and dirty tile list.
    // Returns false if no more frames (or error).
    bool decode_frame();

    // Seek to a specific frame (jumps to nearest I-frame and decodes forward)
    bool seek_to_frame(uint32_t frame);

    // Reset to beginning
    void reset();

    // Access decoded state
    const AstvHeader& header() const { return header_; }
    uint32_t current_frame() const { return current_frame_; }
    uint32_t frame_count() const { return header_.frame_count; }

    // Get the current frame as a complete ASTC texture buffer.
    // Gathers ASTC bytes from codebook indexed by current index map.
    // Output size: blocks_x * blocks_y * 16 bytes.
    // This is what gets uploaded to GPU via glCompressedTexImage2D.
    void gather_astc_texture(uint8_t* out) const;

    // Get only the dirty tiles since last frame.
    // More efficient: only upload changed regions via glCompressedTexSubImage2D.
    const std::vector<DirtyRegion>& dirty_regions() const { return dirty_regions_; }

    // Gather ASTC bytes for a specific dirty region into a contiguous buffer.
    // Returns the byte size written.
    uint32_t gather_dirty_region(const DirtyRegion& region, uint8_t* out) const;

    // Direct access to codebook and index maps (for advanced integration)
    const uint8_t* codebook_data() const { return codebook_.data(); }
    uint32_t codebook_entries() const { return header_.codebook_entries; }
    const uint32_t* current_index_map() const { return current_map_.data(); }

private:
    AstvHeader header_;
    std::vector<uint8_t> file_data_;        // Entire file in memory (for now)
    std::vector<uint8_t> codebook_;          // N * 16 bytes of ASTC blocks
    std::vector<AstvFrameIndex> frame_index_;
    std::vector<uint32_t> current_map_;      // Current frame index map
    std::vector<uint32_t> prev_map_;         // Previous frame index map
    std::vector<DirtyRegion> dirty_regions_; // Changed tiles this frame
    std::vector<uint8_t> decompress_buf_;    // Temp buffer for zstd decompression

    ZSTD_DCtx* zstd_ctx_;
    uint32_t current_frame_;
    bool loaded_;

    // Internal decode helpers
    bool decode_i_frame(const uint8_t* data, size_t size);
    bool decode_p_frame(const uint8_t* data, size_t size);
    void compute_dirty_regions();
};

} // namespace astv
