// ASTV Decoder — Playback-side implementation
//
// Per-block decode cost:
//   SKIP: 0 ops
//   I:    1 array read (codebook index)
//   P:    1 offset read + 1 array read (motion vector resolve)

#include "astv_decoder.h"

#ifdef ASTV_USE_BUNDLED_ZSTD
#include "basis_universal/zstd/zstd.h"
#else
#include <zstd.h>
#endif
#include <cstdlib>
#include <algorithm>

namespace astv {

Decoder::Decoder()
    : zstd_ctx_(nullptr), current_frame_(0), loaded_(false) {
    memset(&header_, 0, sizeof(header_));
}

Decoder::~Decoder() {
    if (zstd_ctx_) {
        ZSTD_freeDCtx(zstd_ctx_);
    }
}

bool Decoder::load(const std::string& path) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) {
        fprintf(stderr, "ASTV: cannot open %s\n", path.c_str());
        return false;
    }

    fseek(f, 0, SEEK_END);
    size_t sz = ftell(f);
    fseek(f, 0, SEEK_SET);

    file_data_.resize(sz);
    if (fread(file_data_.data(), 1, sz, f) != sz) {
        fprintf(stderr, "ASTV: read error\n");
        fclose(f);
        return false;
    }
    fclose(f);

    return load_from_memory(file_data_.data(), file_data_.size());
}

bool Decoder::load_from_memory(const uint8_t* data, size_t size) {
    if (size < sizeof(AstvHeader)) {
        fprintf(stderr, "ASTV: file too small\n");
        return false;
    }

    memcpy(&header_, data, sizeof(AstvHeader));

    if (header_.magic != ASTV_MAGIC) {
        fprintf(stderr, "ASTV: invalid magic (got 0x%08X, expected 0x%08X)\n",
                header_.magic, ASTV_MAGIC);
        return false;
    }
    if (header_.version != ASTV_VERSION) {
        fprintf(stderr, "ASTV: unsupported version %u\n", header_.version);
        return false;
    }

    // Load codebook (possibly zstd-compressed)
    uint32_t cb_raw_size = header_.codebook_entries * 16;
    if (header_.codebook_compressed > 0) {
        // Compressed codebook
        if (header_.codebook_offset + header_.codebook_compressed > size) {
            fprintf(stderr, "ASTV: compressed codebook extends past EOF\n");
            return false;
        }
        codebook_.resize(cb_raw_size);
        size_t result = ZSTD_decompress(codebook_.data(), cb_raw_size,
                                         data + header_.codebook_offset,
                                         header_.codebook_compressed);
        if (ZSTD_isError(result) || result != cb_raw_size) {
            fprintf(stderr, "ASTV: codebook decompression failed: %s\n",
                    ZSTD_getErrorName(result));
            return false;
        }
    } else {
        // Uncompressed codebook (backward compat with v1 files)
        if (header_.codebook_offset + cb_raw_size > size) {
            fprintf(stderr, "ASTV: codebook extends past EOF\n");
            return false;
        }
        codebook_.assign(data + header_.codebook_offset,
                         data + header_.codebook_offset + cb_raw_size);
    }

    // Load frame index table
    uint32_t fi_size = header_.frame_count * sizeof(AstvFrameIndex);
    if (header_.frame_index_offset + fi_size > size) {
        fprintf(stderr, "ASTV: frame index extends past EOF\n");
        return false;
    }
    frame_index_.resize(header_.frame_count);
    memcpy(frame_index_.data(),
           data + header_.frame_index_offset,
           fi_size);

    // Keep file data for frame access
    if (file_data_.empty()) {
        file_data_.assign(data, data + size);
    }

    // Allocate index maps
    uint32_t block_count = (uint32_t)header_.blocks_x * header_.blocks_y;
    current_map_.resize(block_count, 0);
    prev_map_.resize(block_count, 0);

    // Allocate decompression buffer: worst case is P-frame with all blocks
    // type_flags + all_I_indices + all_P_mvs
    uint32_t iw = astv_index_bytes(header_);
    size_t max_frame_size = (block_count + 3) / 4    // type flags
                          + block_count * iw          // I-block indices (worst case: all I)
                          + block_count * 2;          // P-block MVs (worst case: all P)
    decompress_buf_.resize(max_frame_size);

    // Init zstd
    zstd_ctx_ = ZSTD_createDCtx();

    current_frame_ = 0;
    loaded_ = true;

    fprintf(stderr, "ASTV: loaded %ux%u, %u frames, %u codebook entries\n",
            header_.width, header_.height, header_.frame_count,
            header_.codebook_entries);

    return true;
}

bool Decoder::decode_frame() {
    if (!loaded_ || current_frame_ >= header_.frame_count) return false;

    // Swap index maps
    std::swap(current_map_, prev_map_);

    // Get compressed frame data
    const AstvFrameIndex& fi = frame_index_[current_frame_];
    const uint8_t* cdata = file_data_.data() + header_.frame_data_offset + fi.byte_offset;
    size_t csize = fi.compressed_size;

    // Decompress with zstd
    size_t dsize = ZSTD_decompressDCtx(zstd_ctx_,
                                        decompress_buf_.data(),
                                        decompress_buf_.size(),
                                        cdata, csize);
    if (ZSTD_isError(dsize)) {
        fprintf(stderr, "ASTV: zstd error frame %u: %s\n",
                current_frame_, ZSTD_getErrorName(dsize));
        return false;
    }

    bool ok;
    if (fi.frame_type == ASTV_FRAME_I) {
        ok = decode_i_frame(decompress_buf_.data(), dsize);
    } else {
        ok = decode_p_frame(decompress_buf_.data(), dsize);
    }

    if (ok) {
        compute_dirty_regions();
        current_frame_++;
    }
    return ok;
}

bool Decoder::decode_i_frame(const uint8_t* data, size_t size) {
    uint32_t block_count = (uint32_t)header_.blocks_x * header_.blocks_y;
    uint32_t iw = astv_index_bytes(header_);

    if (size < block_count * iw) return false;

    for (uint32_t i = 0; i < block_count; i++) {
        const uint8_t* p = data + i * iw;
        uint32_t idx = p[0];
        if (iw >= 2) idx |= (uint32_t)p[1] << 8;
        if (iw >= 4) idx |= ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
        current_map_[i] = idx;
    }
    return true;
}

bool Decoder::decode_p_frame(const uint8_t* data, size_t size) {
    uint32_t block_count = (uint32_t)header_.blocks_x * header_.blocks_y;
    uint32_t bx = header_.blocks_x;
    uint32_t iw = astv_index_bytes(header_);

    // Read type flags (2 bits per block, packed 4 per byte)
    uint32_t type_bytes = (block_count + 3) / 4;
    if (size < type_bytes) return false;

    const uint8_t* type_data = data;
    const uint8_t* payload = data + type_bytes;

    uint32_t i_read = 0; // I-block index read position in payload
    uint32_t p_read = 0; // P-block MV read position in payload

    // Count I and P blocks to determine payload layout
    uint32_t i_count = 0, p_count = 0;
    for (uint32_t i = 0; i < block_count; i++) {
        uint8_t t = (type_data[i / 4] >> ((i % 4) * 2)) & 0x3;
        if (t == ASTV_BLOCK_I) i_count++;
        else if (t == ASTV_BLOCK_P) p_count++;
    }

    uint32_t i_data_size = i_count * iw;
    const uint8_t* i_payload = payload;
    const uint8_t* p_payload = payload + i_data_size;

    // Decode each block
    for (uint32_t bi = 0; bi < block_count; bi++) {
        uint8_t block_type = (type_data[bi / 4] >> ((bi % 4) * 2)) & 0x3;

        switch (block_type) {
        case ASTV_BLOCK_SKIP:
            current_map_[bi] = prev_map_[bi];
            break;

        case ASTV_BLOCK_I: {
            const uint8_t* p = i_payload + i_read;
            uint32_t idx = p[0];
            if (iw >= 2) idx |= (uint32_t)p[1] << 8;
            if (iw >= 4) idx |= ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
            i_read += iw;
            current_map_[bi] = idx;
            break;
        }

        case ASTV_BLOCK_P: {
            int8_t dx = (int8_t)p_payload[p_read++];
            int8_t dy = (int8_t)p_payload[p_read++];

            uint32_t bx_pos = bi % bx;
            uint32_t by_pos = bi / bx;
            int ref_x = (int)bx_pos + dx;
            int ref_y = (int)by_pos + dy;

            // Bounds-safe: clamp to valid range
            if (ref_x < 0) ref_x = 0;
            if (ref_y < 0) ref_y = 0;
            if (ref_x >= (int)header_.blocks_x) ref_x = header_.blocks_x - 1;
            if (ref_y >= (int)header_.blocks_y) ref_y = header_.blocks_y - 1;

            current_map_[bi] = prev_map_[ref_y * bx + ref_x];
            break;
        }

        default:
            current_map_[bi] = prev_map_[bi]; // Treat unknown as skip
            break;
        }
    }

    return true;
}

void Decoder::compute_dirty_regions() {
    dirty_regions_.clear();

    uint32_t bx = header_.blocks_x;
    uint32_t by = header_.blocks_y;

    // Simple approach: one DirtyRegion per changed block.
    // A more advanced approach merges nearby dirty blocks into larger rectangles.
    for (uint32_t y = 0; y < by; y++) {
        for (uint32_t x = 0; x < bx; x++) {
            uint32_t bi = y * bx + x;
            if (current_map_[bi] != prev_map_[bi]) {
                dirty_regions_.push_back({(uint16_t)x, (uint16_t)y, 1, 1});
            }
        }
    }

    // Merge horizontally adjacent dirty regions for fewer GPU upload calls
    if (dirty_regions_.size() > 1) {
        std::vector<DirtyRegion> merged;
        merged.push_back(dirty_regions_[0]);

        for (size_t i = 1; i < dirty_regions_.size(); i++) {
            DirtyRegion& last = merged.back();
            const DirtyRegion& cur = dirty_regions_[i];

            // Merge if same row and adjacent
            if (cur.y == last.y && cur.x == last.x + last.w) {
                last.w++;
            } else {
                merged.push_back(cur);
            }
        }
        dirty_regions_ = std::move(merged);
    }
}

void Decoder::gather_astc_texture(uint8_t* out) const {
    uint32_t block_count = (uint32_t)header_.blocks_x * header_.blocks_y;
    for (uint32_t i = 0; i < block_count; i++) {
        uint32_t idx = current_map_[i];
        memcpy(out + i * 16, codebook_.data() + idx * 16, 16);
    }
}

uint32_t Decoder::gather_dirty_region(const DirtyRegion& region, uint8_t* out) const {
    uint32_t bx = header_.blocks_x;
    uint32_t bytes = 0;

    for (uint16_t dy = 0; dy < region.h; dy++) {
        for (uint16_t dx = 0; dx < region.w; dx++) {
            uint32_t bi = (region.y + dy) * bx + (region.x + dx);
            uint32_t idx = current_map_[bi];
            memcpy(out + bytes, codebook_.data() + idx * 16, 16);
            bytes += 16;
        }
    }
    return bytes;
}

bool Decoder::seek_to_frame(uint32_t target) {
    if (!loaded_ || target >= header_.frame_count) return false;

    // Find nearest I-frame at or before target
    uint32_t start = 0;
    for (uint32_t i = target; ; i--) {
        if (frame_index_[i].frame_type == ASTV_FRAME_I) {
            start = i;
            break;
        }
        if (i == 0) break;
    }

    // Reset to that I-frame
    current_frame_ = start;
    memset(prev_map_.data(), 0, prev_map_.size() * sizeof(uint32_t));

    // Decode forward to target
    while (current_frame_ <= target) {
        if (!decode_frame()) return false;
    }

    return true;
}

void Decoder::reset() {
    current_frame_ = 0;
    memset(current_map_.data(), 0, current_map_.size() * sizeof(uint32_t));
    memset(prev_map_.data(), 0, prev_map_.size() * sizeof(uint32_t));
    dirty_regions_.clear();
}

} // namespace astv
