#include <jni.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>
#include <GLES2/gl2ext.h>
#include <EGL/egl.h>
#include <memory>
#include <vector>
#include <string>
#include <cstring>
#include <map>
#include <mutex>
#include <atomic>

#define ZSTD_STATIC_LINKING_ONLY
#include "zstd/zstd.h"

// Software ASTC decoder, for GPUs without ASTC (emulators)
#include "astc_decomp.h"

#define LOG_TAG "AlphaVideoGpu"
// Info traces are compiled in only when ALPHA_VIDEO_GPU_DEBUG_LOGS is defined.
#ifdef ALPHA_VIDEO_GPU_DEBUG_LOGS
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) ((void)0)
#endif
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// GL_COMPRESSED_RGBA_ASTC constants for all 14 block sizes
#ifndef GL_COMPRESSED_RGBA_ASTC_4x4_KHR
#define GL_COMPRESSED_RGBA_ASTC_4x4_KHR   0x93B0
#endif
#ifndef GL_COMPRESSED_RGBA_ASTC_5x4_KHR
#define GL_COMPRESSED_RGBA_ASTC_5x4_KHR   0x93B1
#endif
#ifndef GL_COMPRESSED_RGBA_ASTC_5x5_KHR
#define GL_COMPRESSED_RGBA_ASTC_5x5_KHR   0x93B2
#endif
#ifndef GL_COMPRESSED_RGBA_ASTC_6x5_KHR
#define GL_COMPRESSED_RGBA_ASTC_6x5_KHR   0x93B3
#endif
#ifndef GL_COMPRESSED_RGBA_ASTC_6x6_KHR
#define GL_COMPRESSED_RGBA_ASTC_6x6_KHR   0x93B4
#endif
#ifndef GL_COMPRESSED_RGBA_ASTC_8x5_KHR
#define GL_COMPRESSED_RGBA_ASTC_8x5_KHR   0x93B5
#endif
#ifndef GL_COMPRESSED_RGBA_ASTC_8x6_KHR
#define GL_COMPRESSED_RGBA_ASTC_8x6_KHR   0x93B6
#endif
#ifndef GL_COMPRESSED_RGBA_ASTC_8x8_KHR
#define GL_COMPRESSED_RGBA_ASTC_8x8_KHR   0x93B7
#endif
#ifndef GL_COMPRESSED_RGBA_ASTC_10x5_KHR
#define GL_COMPRESSED_RGBA_ASTC_10x5_KHR  0x93B8
#endif
#ifndef GL_COMPRESSED_RGBA_ASTC_10x6_KHR
#define GL_COMPRESSED_RGBA_ASTC_10x6_KHR  0x93B9
#endif
#ifndef GL_COMPRESSED_RGBA_ASTC_10x8_KHR
#define GL_COMPRESSED_RGBA_ASTC_10x8_KHR  0x93BA
#endif
#ifndef GL_COMPRESSED_RGBA_ASTC_10x10_KHR
#define GL_COMPRESSED_RGBA_ASTC_10x10_KHR 0x93BB
#endif
#ifndef GL_COMPRESSED_RGBA_ASTC_12x10_KHR
#define GL_COMPRESSED_RGBA_ASTC_12x10_KHR 0x93BC
#endif
#ifndef GL_COMPRESSED_RGBA_ASTC_12x12_KHR
#define GL_COMPRESSED_RGBA_ASTC_12x12_KHR 0x93BD
#endif

// ASTC format info: maps VkFormat to GL format and block dimensions
struct ASTCFormatInfo {
    uint32_t vk_format_unorm;
    uint32_t vk_format_srgb;
    GLenum   gl_format;
    int      block_width;
    int      block_height;
};

static const ASTCFormatInfo g_astc_formats[] = {
    { 157, 158, GL_COMPRESSED_RGBA_ASTC_4x4_KHR,   4,  4 },
    { 159, 160, GL_COMPRESSED_RGBA_ASTC_5x4_KHR,   5,  4 },
    { 161, 162, GL_COMPRESSED_RGBA_ASTC_5x5_KHR,   5,  5 },
    { 163, 164, GL_COMPRESSED_RGBA_ASTC_6x5_KHR,   6,  5 },
    { 165, 166, GL_COMPRESSED_RGBA_ASTC_6x6_KHR,   6,  6 },
    { 167, 168, GL_COMPRESSED_RGBA_ASTC_8x5_KHR,   8,  5 },
    { 169, 170, GL_COMPRESSED_RGBA_ASTC_8x6_KHR,   8,  6 },
    { 171, 172, GL_COMPRESSED_RGBA_ASTC_8x8_KHR,   8,  8 },
    { 173, 174, GL_COMPRESSED_RGBA_ASTC_10x5_KHR,  10, 5 },
    { 175, 176, GL_COMPRESSED_RGBA_ASTC_10x6_KHR,  10, 6 },
    { 177, 178, GL_COMPRESSED_RGBA_ASTC_10x8_KHR,  10, 8 },
    { 179, 180, GL_COMPRESSED_RGBA_ASTC_10x10_KHR, 10, 10 },
    { 181, 182, GL_COMPRESSED_RGBA_ASTC_12x10_KHR, 12, 10 },
    { 183, 184, GL_COMPRESSED_RGBA_ASTC_12x12_KHR, 12, 12 },
};

// Look up ASTC format info from VkFormat. Returns nullptr if not found.
static const ASTCFormatInfo* get_astc_format_info(uint32_t vk_format) {
    for (const auto& fmt : g_astc_formats) {
        if (vk_format == fmt.vk_format_unorm || vk_format == fmt.vk_format_srgb) {
            return &fmt;
        }
    }
    return nullptr;
}

// Calculate bytes per layer for an ASTC texture (all blocks are 16 bytes)
static uint32_t astc_bytes_per_layer(int width, int height, int block_width, int block_height) {
    int blocks_x = (width + block_width - 1) / block_width;
    int blocks_y = (height + block_height - 1) / block_height;
    return blocks_x * blocks_y * 16;
}

// PBO context for async texture uploads (per texture array)
struct PBOContext {
    GLuint pbos[2];           // Double-buffered PBOs
    GLsync fences[2];         // Fence sync objects to prevent overwriting in-flight PBOs
    int current_pbo;          // Index of PBO to use next (0 or 1)
    GLsizei buffer_size;      // Size of each PBO in bytes
    bool initialized;
    
    PBOContext() : current_pbo(0), buffer_size(0), initialized(false) {
        pbos[0] = pbos[1] = 0;
        fences[0] = fences[1] = nullptr;
    }
};

// Each view has its own EGL context, and texture and buffer names are per context, so two views
// can hold the same texture ID. PBOs are keyed by the context that is current when they're made.
using PBOKey = std::pair<EGLContext, GLuint>;
static std::map<PBOKey, PBOContext> g_pbo_contexts;
static std::mutex g_pbo_mutex;

static PBOKey pboKey(GLuint texId) {
    return {eglGetCurrentContext(), texId};
}

// Decode ASTC texture to RGBA using proper decoder (supports all block sizes)
static bool decode_astc_to_rgba(const uint8_t* astc_data, size_t astc_size,
                                 int width, int height,
                                 int block_width, int block_height,
                                 std::vector<uint8_t>& rgba_output) {
    int blocks_x = (width + block_width - 1) / block_width;
    int blocks_y = (height + block_height - 1) / block_height;
    size_t expected_size = blocks_x * blocks_y * 16; // 16 bytes per block
    
    if (astc_size < expected_size) {
        LOGE("ASTC data too small: %zu < %zu", astc_size, expected_size);
        return false;
    }
    
    rgba_output.resize(width * height * 4);
    memset(rgba_output.data(), 0, rgba_output.size());
    
    // Temporary buffer for one decoded block (max 12x12)
    uint8_t block_pixels[12 * 12 * 4];
    
    for (int by = 0; by < blocks_y; by++) {
        for (int bx = 0; bx < blocks_x; bx++) {
            const uint8_t* block = astc_data + (by * blocks_x + bx) * 16;
            
            // Use proper ASTC decoder (sRGB = false for linear decoding)
            bool success = basisu::astc::decompress(block_pixels, block, false, block_width, block_height);
            if (!success) {
                LOGW("Failed to decode ASTC block at (%d, %d)", bx, by);
                // Fill with magenta to indicate error
                int pixels_in_block = block_width * block_height;
                for (int i = 0; i < pixels_in_block; i++) {
                    block_pixels[i * 4 + 0] = 255;
                    block_pixels[i * 4 + 1] = 0;
                    block_pixels[i * 4 + 2] = 255;
                    block_pixels[i * 4 + 3] = 255;
                }
            }
            
            // Copy block to output, handling edge blocks
            for (int y = 0; y < block_height && (by * block_height + y) < height; y++) {
                for (int x = 0; x < block_width && (bx * block_width + x) < width; x++) {
                    int dst_x = bx * block_width + x;
                    int dst_y = by * block_height + y;
                    uint8_t* dst = rgba_output.data() + (dst_y * width + dst_x) * 4;
                    uint8_t* src = block_pixels + (y * block_width + x) * 4;
                    dst[0] = src[0];
                    dst[1] = src[1];
                    dst[2] = src[2];
                    dst[3] = src[3];
                }
            }
        }
    }
    
    LOGI("Decoded ASTC %dx%d (%d x %d blocks, block=%dx%d) to RGBA", width, height, blocks_x, blocks_y, block_width, block_height);
    return true;
}

extern "C" {

JNIEXPORT void JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeDeleteTexture(JNIEnv *env, jclass clazz,
                                                          jint texture_id) {
    GLuint id = (GLuint)texture_id;
    glDeleteTextures(1, &id);
}

// Zero-copy Zstd decompression directly to a direct ByteBuffer
JNIEXPORT jboolean JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeDecompressZstdToBuffer(JNIEnv *env, jclass clazz,
                                                                   jbyteArray data, jint offset,
                                                                   jint compressed_length,
                                                                   jobject output_buffer,
                                                                   jint uncompressed_length) {
    // Get input data
    jbyte* input_bytes = env->GetByteArrayElements(data, nullptr);
    if (!input_bytes) {
        LOGE("Failed to get input byte array");
        return JNI_FALSE;
    }
    
    // Get direct buffer address
    void* output_ptr = env->GetDirectBufferAddress(output_buffer);
    if (!output_ptr) {
        LOGE("Failed to get direct buffer address for Zstd output");
        env->ReleaseByteArrayElements(data, input_bytes, JNI_ABORT);
        return JNI_FALSE;
    }
    
    const uint8_t* compressed_data = reinterpret_cast<const uint8_t*>(input_bytes) + offset;
    
    // Decompress directly into the direct ByteBuffer (zero-copy)
    size_t decompressed_size = ZSTD_decompress(
        output_ptr, uncompressed_length,
        compressed_data, compressed_length
    );
    
    env->ReleaseByteArrayElements(data, input_bytes, JNI_ABORT);
    
    if (ZSTD_isError(decompressed_size)) {
        LOGE("Zstd decompression to buffer failed: %s", ZSTD_getErrorName(decompressed_size));
        return JNI_FALSE;
    }
    
    if (decompressed_size != (size_t)uncompressed_length) {
        LOGE("Zstd decompression size mismatch: expected %d, got %zu", uncompressed_length, decompressed_size);
        return JNI_FALSE;
    }
    
    LOGI("Zstd decompressed %d -> %zu bytes (zero-copy to direct buffer)", compressed_length, decompressed_size);
    
    return JNI_TRUE;
}

/**
 * Load a KTX2 texture array (one frame per layer).
 * Uses OpenGL ES 3.0 GL_TEXTURE_2D_ARRAY for efficient frame switching.
 * Returns [textureId, width, height, layerCount]
 */
JNIEXPORT jintArray JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeLoadTextureArray(JNIEnv *env, jclass clazz,
                                                             jbyteArray ktx2_data,
                                                             jboolean use_astc) {
    jsize data_length = env->GetArrayLength(ktx2_data);
    jbyte* data = env->GetByteArrayElements(ktx2_data, nullptr);
    
    if (!data || data_length < 104) {
        LOGE("Invalid KTX2 data for texture array");
        if (data) env->ReleaseByteArrayElements(ktx2_data, data, JNI_ABORT);
        return nullptr;
    }
    
    const uint8_t* bytes = (const uint8_t*)data;
    
    // Parse KTX2 header
    uint32_t vkFormat = *(uint32_t*)(bytes + 12);
    uint32_t width = *(uint32_t*)(bytes + 20);
    uint32_t height = *(uint32_t*)(bytes + 24);
    uint32_t layerCount = *(uint32_t*)(bytes + 32);
    uint32_t supercompressionScheme = *(uint32_t*)(bytes + 44);
    
    // Level 0 index (at byte 80)
    uint64_t level0Offset = *(uint64_t*)(bytes + 80);
    uint64_t level0Length = *(uint64_t*)(bytes + 88);
    uint64_t level0UncompressedLength = *(uint64_t*)(bytes + 96);
    
    // Look up ASTC format info from vkFormat
    const ASTCFormatInfo* astcInfo = get_astc_format_info(vkFormat);
    int bw = astcInfo ? astcInfo->block_width : 4;
    int bh = astcInfo ? astcInfo->block_height : 4;
    GLenum astcGlFormat = astcInfo ? astcInfo->gl_format : GL_COMPRESSED_RGBA_ASTC_4x4_KHR;
    
    LOGI("Texture array: %ux%u, %u layers, supercompression=%u, vkFormat=%u (ASTC %dx%d)",
         width, height, layerCount, supercompressionScheme, vkFormat, bw, bh);
    
    // Extract ASTC data
    std::vector<uint8_t> astcData;
    
    if (supercompressionScheme == 2) {
        // Zstd compressed - decompress
        const void* compressedData = bytes + level0Offset;
        size_t compressedSize = (size_t)level0Length;
        size_t decompressedSize = (size_t)level0UncompressedLength;
        
        astcData.resize(decompressedSize);
        size_t result = ZSTD_decompress(astcData.data(), decompressedSize,
                                         compressedData, compressedSize);
        
        if (ZSTD_isError(result)) {
            LOGE("Zstd decompression failed: %s", ZSTD_getErrorName(result));
            env->ReleaseByteArrayElements(ktx2_data, data, JNI_ABORT);
            return nullptr;
        }
        
        LOGI("Decompressed %llu -> %zu bytes", (unsigned long long)level0Length, result);
    } else {
        // Uncompressed
        astcData.assign(bytes + level0Offset, bytes + level0Offset + level0Length);
    }
    
    env->ReleaseByteArrayElements(ktx2_data, data, JNI_ABORT);
    
    // Calculate bytes per layer using dynamic block dimensions
    uint32_t bytesPerLayer = astc_bytes_per_layer(width, height, bw, bh);
    
    LOGI("ASTC data: %zu bytes, %u bytes/layer (block=%dx%d), %u layers",
         astcData.size(), bytesPerLayer, bw, bh, layerCount);
    
    // Clear any pending GL errors
    while (glGetError() != GL_NO_ERROR) {}
    
    // Create texture array
    GLuint textureId;
    glGenTextures(1, &textureId);
    glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
    
    GLenum bindError = glGetError();
    if (bindError != GL_NO_ERROR) {
        LOGE("glBindTexture GL_TEXTURE_2D_ARRAY error: 0x%x", bindError);
        glDeleteTextures(1, &textureId);
        return nullptr;
    }
    
    if (use_astc) {
        // Upload compressed ASTC data directly using correct GL format
        glCompressedTexImage3D(GL_TEXTURE_2D_ARRAY, 0, astcGlFormat,
                                width, height, layerCount, 0,
                                astcData.size(), astcData.data());
    } else {
        // Decode ASTC to RGBA for each layer and upload
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8,
                     width, height, layerCount, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        
        for (uint32_t layer = 0; layer < layerCount; layer++) {
            const uint8_t* layerData = astcData.data() + (layer * bytesPerLayer);
            std::vector<uint8_t> rgbaData;
            
            if (decode_astc_to_rgba(layerData, bytesPerLayer, width, height, bw, bh, rgbaData)) {
                glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0,
                                0, 0, layer,
                                width, height, 1,
                                GL_RGBA, GL_UNSIGNED_BYTE, rgbaData.data());
            }
        }
    }
    
    GLenum texError = glGetError();
    if (texError != GL_NO_ERROR) {
        LOGE("Texture array upload error: 0x%x", texError);
        glDeleteTextures(1, &textureId);
        return nullptr;
    }
    
    // Set texture parameters
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    
    LOGI("Created texture array: ID=%d (%ux%u, %u layers)", textureId, width, height, layerCount);
    
    // Return [textureId, width, height, layerCount]
    jintArray result = env->NewIntArray(4);
    jint values[4] = { (jint)textureId, (jint)width, (jint)height, (jint)layerCount };
    env->SetIntArrayRegion(result, 0, 4, values);
    
    return result;
}

/**
 * Streaming decompression support using ZSTD_DStream.
 * Decompresses the level0 data of zstd-supercompressed KTX2 files as they play.
 * Per-instance state management for multiple concurrent streams.
 * 
 * Memory optimization: Uses memory-mapped files to avoid copying
 * compressed data into Java heap and C++ heap.
 */

#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

// Per-instance streaming decompression state
struct StreamingDecompressState {
    ZSTD_DStream* dstream = nullptr;
    
    // Memory-mapped file (preferred - zero copy)
    int fd = -1;
    void* mmap_ptr = nullptr;
    size_t mmap_size = 0;
    
    // Unified input pointer and size
    const uint8_t* input_data = nullptr;
    size_t input_size = 0;
    size_t input_pos = 0;
    // The last call filled the output mid-frame: zstd may hold more even with no input left
    bool output_pending = false;
    
    ~StreamingDecompressState() {
        if (dstream) {
            ZSTD_freeDStream(dstream);
            dstream = nullptr;
        }
        if (mmap_ptr && mmap_ptr != MAP_FAILED) {
            munmap(mmap_ptr, mmap_size);
            mmap_ptr = nullptr;
        }
        if (fd >= 0) {
            close(fd);
            fd = -1;
        }
    }
};

// Map of instance ID -> streaming state
static std::map<jlong, std::unique_ptr<StreamingDecompressState>> g_streaming_states;
static std::mutex g_streaming_mutex;
static std::atomic<jlong> g_next_stream_id{1};

/**
 * Maps [offset, offset + length) of fd into the state's input. The descriptor is duplicated, so
 * the caller may close its own copy. A negative length means "to the end of the file".
 */
static bool mapFdRange(StreamingDecompressState* state, int fd, int64_t offset, int64_t length) {
    state->fd = dup(fd);
    if (state->fd < 0) {
        LOGE("Failed to dup descriptor (errno=%d)", errno);
        return false;
    }

    struct stat st;
    if (fstat(state->fd, &st) < 0) {
        LOGE("Failed to stat descriptor (errno=%d)", errno);
        return false;
    }
    if (length < 0) length = st.st_size - offset;
    if (offset < 0 || length <= 0 || offset + length > st.st_size) {
        LOGE("Range [%lld, +%lld) is outside the file (%lld bytes)",
             (long long)offset, (long long)length, (long long)st.st_size);
        return false;
    }

    long page_size = sysconf(_SC_PAGE_SIZE);
    off_t aligned_offset = (offset / page_size) * page_size;
    size_t offset_adjustment = offset - aligned_offset;
    size_t mmap_length = length + offset_adjustment;

    void* ptr = mmap(nullptr, mmap_length, PROT_READ, MAP_PRIVATE, state->fd, aligned_offset);
    if (ptr == MAP_FAILED) {
        LOGE("Failed to mmap %zu bytes (errno=%d)", mmap_length, errno);
        return false;
    }
    state->mmap_ptr = ptr;
    state->mmap_size = mmap_length;
    madvise(ptr, mmap_length, MADV_SEQUENTIAL);

    state->input_data = static_cast<const uint8_t*>(ptr) + offset_adjustment;
    state->input_size = length;
    state->input_pos = 0;
    return true;
}

/** Creates the zstd stream for a state whose input is set, and registers it. Returns 0 on error. */
static jlong registerStream(std::unique_ptr<StreamingDecompressState> state) {
    state->dstream = ZSTD_createDStream();
    if (!state->dstream) {
        LOGE("Failed to create ZSTD_DStream");
        return 0;
    }
    size_t initResult = ZSTD_initDStream(state->dstream);
    if (ZSTD_isError(initResult)) {
        LOGE("Failed to init ZSTD_DStream: %s", ZSTD_getErrorName(initResult));
        return 0;
    }

    jlong streamId = g_next_stream_id.fetch_add(1);
    std::lock_guard<std::mutex> lock(g_streaming_mutex);
    g_streaming_states[streamId] = std::move(state);
    return streamId;
}

/**
 * Continue streaming decompression into a direct ByteBuffer (zero-copy).
 * This avoids allocating new byte arrays on each call.
 * 
 * @param stream_id The stream ID from init
 * @param output_buffer A direct ByteBuffer to write decompressed data into
 * @param max_output_bytes Maximum bytes to decompress
 * @return Status: -1 = error, 0 = more data, 1 = done, or positive = bytes written
 *         High 32 bits = status, low 32 bits = bytes written
 */
JNIEXPORT jlong JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeStreamingDecompressContinueToBuffer(JNIEnv *env, jclass clazz,
                                                                                 jlong stream_id,
                                                                                 jobject output_buffer,
                                                                                 jint max_output_bytes) {
    StreamingDecompressState* state = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_streaming_mutex);
        auto it = g_streaming_states.find(stream_id);
        if (it == g_streaming_states.end()) {
            LOGE("Streaming decompression not found for stream %lld", (long long)stream_id);
            return -1;  // Error
        }
        state = it->second.get();
    }
    
    if (!state || !state->dstream) {
        LOGE("Streaming decompression not initialized for stream %lld", (long long)stream_id);
        return -1;  // Error
    }
    
    // Check if we've consumed all input and zstd has nothing left to flush
    if (state->input_pos >= state->input_size && !state->output_pending) {
        return (1LL << 32);  // Done, 0 bytes written
    }
    
    // Get direct buffer address
    void* buffer_ptr = env->GetDirectBufferAddress(output_buffer);
    if (!buffer_ptr) {
        LOGE("Failed to get direct buffer address");
        return -1;  // Error
    }
    
    // Setup input/output buffers
    ZSTD_inBuffer input = {
        state->input_data + state->input_pos,
        state->input_size - state->input_pos,
        0
    };
    
    ZSTD_outBuffer output = {
        buffer_ptr,
        (size_t)max_output_bytes,
        0
    };
    
    // Decompress
    size_t result = ZSTD_decompressStream(state->dstream, &output, &input);
    
    if (ZSTD_isError(result)) {
        LOGE("Streaming decompression error for stream %lld: %s", (long long)stream_id, ZSTD_getErrorName(result));
        return -1;  // Error
    }
    
    // Update input position
    state->input_pos += input.pos;
    // 0 means the frame is done and flushed; a level can hold several frames back to back
    state->output_pending = output.pos == output.size && result != 0;
    
    // Check if done
    bool isDone = state->input_pos >= state->input_size && !state->output_pending;
    
    // Return: high 32 bits = status (0=more, 1=done), low 32 bits = bytes written
    jlong status = isDone ? 1 : 0;
    return (status << 32) | (jlong)output.pos;
}

/**
 * Starts the stream again at a zstd frame boundary of its input, such as a seek point from the
 * level's seek table (0 is the level's start). The mapping is kept.
 */
JNIEXPORT jboolean JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeStreamingRestartAt(JNIEnv *env, jclass clazz,
                                                                                jlong stream_id,
                                                                                jlong compressed_offset) {
    std::lock_guard<std::mutex> lock(g_streaming_mutex);
    auto it = g_streaming_states.find(stream_id);
    if (it == g_streaming_states.end() || !it->second->dstream) return JNI_FALSE;
    StreamingDecompressState* state = it->second.get();
    if (compressed_offset < 0 || (size_t)compressed_offset > state->input_size) {
        LOGE("Restart offset %lld is outside the stream (%zu bytes)", (long long)compressed_offset, state->input_size);
        return JNI_FALSE;
    }
    size_t reset = ZSTD_DCtx_reset(state->dstream, ZSTD_reset_session_only);
    if (ZSTD_isError(reset)) {
        LOGE("Failed to reset ZSTD_DStream: %s", ZSTD_getErrorName(reset));
        return JNI_FALSE;
    }
    state->input_pos = (size_t)compressed_offset;
    state->output_pending = false;
    return JNI_TRUE;
}

/**
 * Clean up streaming decompression resources for a specific stream.
 */
JNIEXPORT void JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeStreamingDecompressEnd(JNIEnv *env, jclass clazz,
                                                                    jlong stream_id) {
    std::lock_guard<std::mutex> lock(g_streaming_mutex);
    auto it = g_streaming_states.find(stream_id);
    if (it != g_streaming_states.end()) {
        g_streaming_states.erase(it);
        LOGI("Streaming decompression ended: stream %lld", (long long)stream_id);
    }
}

/**
 * Upload a single layer to an existing texture array.
 * Used for streaming mode where layers are uploaded progressively.
 */
JNIEXPORT jboolean JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeUploadTextureArrayLayer(JNIEnv *env, jclass clazz,
                                                                     jint texture_id,
                                                                     jint layer,
                                                                     jint width, jint height,
                                                                     jbyteArray astc_data,
                                                                     jboolean use_astc,
                                                                     jint gl_format,
                                                                     jint block_w, jint block_h) {
    int bw = (block_w > 0) ? block_w : 4;
    int bh = (block_h > 0) ? block_h : 4;
    GLenum astcGlFormat = (gl_format > 0) ? (GLenum)gl_format : GL_COMPRESSED_RGBA_ASTC_4x4_KHR;
    
    jsize data_length = env->GetArrayLength(astc_data);
    jbyte* data = env->GetByteArrayElements(astc_data, nullptr);
    if (!data) {
        LOGE("Failed to get layer data");
        return JNI_FALSE;
    }
    
    glBindTexture(GL_TEXTURE_2D_ARRAY, (GLuint)texture_id);
    
    GLenum bindError = glGetError();
    if (bindError != GL_NO_ERROR) {
        LOGE("glBindTexture error: 0x%x", bindError);
        env->ReleaseByteArrayElements(astc_data, data, JNI_ABORT);
        return JNI_FALSE;
    }
    
    if (use_astc) {
        // Upload compressed ASTC data using correct GL format
        glCompressedTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0,
                                   0, 0, layer,
                                   width, height, 1,
                                   astcGlFormat,
                                   data_length, data);
    } else {
        // Decode ASTC to RGBA and upload
        std::vector<uint8_t> rgbaData;
        if (!decode_astc_to_rgba((const uint8_t*)data, data_length, width, height, bw, bh, rgbaData)) {
            LOGE("Failed to decode ASTC for layer %d", layer);
            env->ReleaseByteArrayElements(astc_data, data, JNI_ABORT);
            return JNI_FALSE;
        }
        
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0,
                        0, 0, layer,
                        width, height, 1,
                        GL_RGBA, GL_UNSIGNED_BYTE, rgbaData.data());
    }
    
    env->ReleaseByteArrayElements(astc_data, data, JNI_ABORT);
    
    GLenum texError = glGetError();
    if (texError != GL_NO_ERROR) {
        LOGE("Texture layer upload error: 0x%x", texError);
        return JNI_FALSE;
    }
    
    return JNI_TRUE;
}

/**
 * Create an empty texture array for streaming mode.
 * Returns texture ID, or 0 on error.
 */
JNIEXPORT jint JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeCreateTextureArray(JNIEnv *env, jclass clazz,
                                                                jint width, jint height,
                                                                jint layer_count,
                                                                jboolean use_astc,
                                                                jint gl_format) {
    GLenum astcGlFormat = (gl_format > 0) ? (GLenum)gl_format : GL_COMPRESSED_RGBA_ASTC_4x4_KHR;
    
    // Clear any pending GL errors
    while (glGetError() != GL_NO_ERROR) {}
    
    GLuint textureId;
    glGenTextures(1, &textureId);
    glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
    
    GLenum bindError = glGetError();
    if (bindError != GL_NO_ERROR) {
        LOGE("glBindTexture GL_TEXTURE_2D_ARRAY error: 0x%x", bindError);
        glDeleteTextures(1, &textureId);
        return 0;
    }
    
    if (use_astc) {
        // Use glTexStorage3D for immutable storage with correct ASTC format
        glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, astcGlFormat,
                       width, height, layer_count);
    } else {
        // Allocate RGBA texture storage
        glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_RGBA8,
                       width, height, layer_count);
    }
    
    GLenum texError = glGetError();
    if (texError != GL_NO_ERROR) {
        LOGE("Texture array allocation error: 0x%x", texError);
        glDeleteTextures(1, &textureId);
        return 0;
    }
    
    // Set texture parameters
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    
    LOGI("Created empty texture array: ID=%d (%dx%d, %d layers, astc=%d)",
         textureId, width, height, layer_count, use_astc);
    
    return (jint)textureId;
}

/**
 * Upload a single layer to an existing texture array from a direct ByteBuffer.
 * Zero-copy version for VRAM streaming mode - avoids Java byte[] allocation.
 */
JNIEXPORT jboolean JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeUploadTextureArrayLayerFromBuffer(JNIEnv *env, jclass clazz,
                                                                               jint texture_id,
                                                                               jint layer,
                                                                               jint width, jint height,
                                                                               jobject buffer,
                                                                               jint data_length,
                                                                               jboolean use_astc,
                                                                               jint gl_format,
                                                                               jint block_w, jint block_h) {
    int bw = (block_w > 0) ? block_w : 4;
    int bh = (block_h > 0) ? block_h : 4;
    GLenum astcGlFormat = (gl_format > 0) ? (GLenum)gl_format : GL_COMPRESSED_RGBA_ASTC_4x4_KHR;
    
    void* data = env->GetDirectBufferAddress(buffer);
    if (!data) {
        LOGE("Failed to get direct buffer address for layer upload");
        return JNI_FALSE;
    }
    
    glBindTexture(GL_TEXTURE_2D_ARRAY, (GLuint)texture_id);
    
    GLenum bindError = glGetError();
    if (bindError != GL_NO_ERROR) {
        LOGE("glBindTexture error: 0x%x", bindError);
        return JNI_FALSE;
    }
    
    if (use_astc) {
        // Upload compressed ASTC data directly from buffer
        glCompressedTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0,
                                   0, 0, layer,
                                   width, height, 1,
                                   astcGlFormat,
                                   data_length, data);
    } else {
        // Decode ASTC to RGBA and upload
        std::vector<uint8_t> rgbaData;
        if (!decode_astc_to_rgba((const uint8_t*)data, data_length, width, height, bw, bh, rgbaData)) {
            LOGE("Failed to decode ASTC for layer %d", layer);
            return JNI_FALSE;
        }
        
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0,
                        0, 0, layer,
                        width, height, 1,
                        GL_RGBA, GL_UNSIGNED_BYTE, rgbaData.data());
    }
    
    GLenum texError = glGetError();
    if (texError != GL_NO_ERROR) {
        LOGE("Texture layer upload from buffer error: 0x%x", texError);
        return JNI_FALSE;
    }
    
    return JNI_TRUE;
}

/**
 * Upload a single layer to an existing texture array from a direct ByteBuffer with offset.
 * Zero-copy version for non-stream RAM mode - reads from contiguous buffer at specified offset.
 */
JNIEXPORT jboolean JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeUploadTextureArrayLayerFromBufferWithOffset(JNIEnv *env, jclass clazz,
                                                                                        jint texture_id,
                                                                                        jint layer,
                                                                                        jint width, jint height,
                                                                                        jobject buffer,
                                                                                        jint offset,
                                                                                        jint data_length,
                                                                                        jboolean use_astc,
                                                                                        jint gl_format,
                                                                                        jint block_w, jint block_h) {
    int bw = (block_w > 0) ? block_w : 4;
    int bh = (block_h > 0) ? block_h : 4;
    GLenum astcGlFormat = (gl_format > 0) ? (GLenum)gl_format : GL_COMPRESSED_RGBA_ASTC_4x4_KHR;
    
    void* buffer_base = env->GetDirectBufferAddress(buffer);
    if (!buffer_base) {
        LOGE("Failed to get direct buffer address for layer upload with offset");
        return JNI_FALSE;
    }
    
    // Calculate data pointer with offset
    const uint8_t* data = reinterpret_cast<const uint8_t*>(buffer_base) + offset;
    
    glBindTexture(GL_TEXTURE_2D_ARRAY, (GLuint)texture_id);
    
    GLenum bindError = glGetError();
    if (bindError != GL_NO_ERROR) {
        LOGE("glBindTexture error: 0x%x", bindError);
        return JNI_FALSE;
    }
    
    if (use_astc) {
        // Upload compressed ASTC data directly from buffer at offset
        glCompressedTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0,
                                   0, 0, layer,
                                   width, height, 1,
                                   astcGlFormat,
                                   data_length, data);
    } else {
        // Decode ASTC to RGBA and upload
        std::vector<uint8_t> rgbaData;
        if (!decode_astc_to_rgba(data, data_length, width, height, bw, bh, rgbaData)) {
            LOGE("Failed to decode ASTC for layer %d (offset=%d)", layer, offset);
            return JNI_FALSE;
        }
        
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0,
                        0, 0, layer,
                        width, height, 1,
                        GL_RGBA, GL_UNSIGNED_BYTE, rgbaData.data());
    }
    
    GLenum texError = glGetError();
    if (texError != GL_NO_ERROR) {
        LOGE("Texture layer upload from buffer with offset error: 0x%x", texError);
        return JNI_FALSE;
    }
    
    return JNI_TRUE;
}

/**
 * Initialize PBOs for a texture array (call once per texture array).
 * Creates double-buffered PBOs for async uploads.
 */
JNIEXPORT jboolean JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeInitPBOs(JNIEnv *env, jclass clazz,
                                                           jint texture_id,
                                                           jint buffer_size) {
    std::lock_guard<std::mutex> lock(g_pbo_mutex);
    
    GLuint texId = (GLuint)texture_id;
    PBOKey key = pboKey(texId);
    
    // Check if already initialized
    auto existing = g_pbo_contexts.find(key);
    if (existing != g_pbo_contexts.end() && existing->second.initialized) {
        LOGI("PBOs already initialized for texture %u", texId);
        return JNI_TRUE;
    }
    
    PBOContext ctx;
    ctx.buffer_size = buffer_size;
    
    // Generate two PBOs for double-buffering
    glGenBuffers(2, ctx.pbos);
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("glGenBuffers failed: 0x%x", err);
        return JNI_FALSE;
    }
    
    // Allocate storage for both PBOs
    for (int i = 0; i < 2; i++) {
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, ctx.pbos[i]);
        glBufferData(GL_PIXEL_UNPACK_BUFFER, buffer_size, nullptr, GL_STREAM_DRAW);
        err = glGetError();
        if (err != GL_NO_ERROR) {
            LOGE("glBufferData for PBO %d failed: 0x%x", i, err);
            glDeleteBuffers(2, ctx.pbos);
            return JNI_FALSE;
        }
        ctx.fences[i] = nullptr;
    }
    
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    
    ctx.current_pbo = 0;
    ctx.initialized = true;
    g_pbo_contexts[key] = ctx;
    
    LOGI("Initialized PBOs for texture %u: size=%d bytes each", texId, buffer_size);
    return JNI_TRUE;
}

/**
 * Cleanup PBOs for a texture array.
 */
JNIEXPORT void JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeCleanupPBOs(JNIEnv *env, jclass clazz,
                                                              jint texture_id) {
    std::lock_guard<std::mutex> lock(g_pbo_mutex);
    
    GLuint texId = (GLuint)texture_id;
    auto it = g_pbo_contexts.find(pboKey(texId));
    if (it == g_pbo_contexts.end()) {
        return;
    }
    
    PBOContext& ctx = it->second;
    
    // Wait for any pending fences
    for (int i = 0; i < 2; i++) {
        if (ctx.fences[i]) {
            glClientWaitSync(ctx.fences[i], GL_SYNC_FLUSH_COMMANDS_BIT, 1000000000); // 1 second timeout
            glDeleteSync(ctx.fences[i]);
        }
    }
    
    glDeleteBuffers(2, ctx.pbos);
    g_pbo_contexts.erase(it);
    
    LOGI("Cleaned up PBOs for texture %u", texId);
}

/**
 * Upload texture layer using PBO for async DMA.
 * The glCompressedTexSubImage3D call returns immediately while GPU DMAs in background.
 */
JNIEXPORT jboolean JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeUploadTextureArrayLayerPBO(JNIEnv *env, jclass clazz,
                                                                              jint texture_id,
                                                                              jint layer,
                                                                              jint width, jint height,
                                                                              jobject buffer,
                                                                              jint data_length,
                                                                              jboolean use_astc,
                                                                              jint gl_format,
                                                                              jint block_w, jint block_h) {
    // PBO upload only supports ASTC (no CPU decode path)
    if (!use_astc) {
        LOGW("PBO upload only supports ASTC, falling back to regular upload");
        return Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeUploadTextureArrayLayerFromBuffer(
            env, clazz, texture_id, layer, width, height, buffer, data_length, use_astc, gl_format, block_w, block_h);
    }
    
    GLuint texId = (GLuint)texture_id;
    GLenum astcGlFormat = (gl_format > 0) ? (GLenum)gl_format : GL_COMPRESSED_RGBA_ASTC_4x4_KHR;
    
    void* srcData = env->GetDirectBufferAddress(buffer);
    if (!srcData) {
        LOGE("Failed to get direct buffer address for PBO upload");
        return JNI_FALSE;
    }
    
    PBOContext* ctx = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_pbo_mutex);
        auto it = g_pbo_contexts.find(pboKey(texId));
        if (it == g_pbo_contexts.end() || !it->second.initialized) {
            LOGW("PBOs not initialized for texture %u, falling back to regular upload", texId);
            return Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeUploadTextureArrayLayerFromBuffer(
                env, clazz, texture_id, layer, width, height, buffer, data_length, use_astc, gl_format, block_w, block_h);
        }
        ctx = &it->second;
    }
    
    if (data_length > ctx->buffer_size) {
        LOGE("Data length %d exceeds PBO size %d", data_length, ctx->buffer_size);
        return JNI_FALSE;
    }
    
    int pboIndex = ctx->current_pbo;
    GLuint pbo = ctx->pbos[pboIndex];
    
    // Wait for previous upload using this PBO to complete
    if (ctx->fences[pboIndex]) {
        GLenum waitResult = glClientWaitSync(ctx->fences[pboIndex], GL_SYNC_FLUSH_COMMANDS_BIT, 0);
        if (waitResult == GL_TIMEOUT_EXPIRED) {
            // PBO still in use, wait with timeout
            waitResult = glClientWaitSync(ctx->fences[pboIndex], GL_SYNC_FLUSH_COMMANDS_BIT, 10000000); // 10ms
            if (waitResult == GL_TIMEOUT_EXPIRED) {
                LOGW("PBO fence timeout, upload may stall");
            }
        }
        glDeleteSync(ctx->fences[pboIndex]);
        ctx->fences[pboIndex] = nullptr;
    }
    
    // Bind PBO and map it
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo);
    
    // Use glMapBufferRange for potentially better performance
    void* pboPtr = glMapBufferRange(GL_PIXEL_UNPACK_BUFFER, 0, data_length,
                                     GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT);
    if (!pboPtr) {
        LOGE("glMapBufferRange failed");
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        return JNI_FALSE;
    }
    
    // Copy data into PBO
    memcpy(pboPtr, srcData, data_length);
    
    glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
    
    // Upload from PBO (async - returns immediately)
    glBindTexture(GL_TEXTURE_2D_ARRAY, texId);
    glCompressedTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0,
                               0, 0, layer,
                               width, height, 1,
                               astcGlFormat,
                               data_length, (void*)0);  // Offset 0 in bound PBO
    
    // Create fence for this upload
    ctx->fences[pboIndex] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    
    // Unbind PBO
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    
    // Swap to other PBO for next upload
    ctx->current_pbo = 1 - pboIndex;
    
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("PBO upload error: 0x%x", err);
        return JNI_FALSE;
    }
    
    return JNI_TRUE;
}

/**
 * Initialize streaming decompression for internal KTX2 zstd (supercompressionScheme=2).
 * Only the level0 data is compressed; the header is not.
 *
 * @param fd Descriptor of the file holding the KTX2 (it may start at an offset, e.g. an APK asset)
 * @param data_offset Offset of the compressed level0 data within the descriptor
 * @param compressed_size Size of compressed data
 * @return Stream ID (> 0), or 0 on error
 */
JNIEXPORT jlong JNICALL
Java_dev_mediacache_alphavideo_gpu_AlphaVideoGpuNative_nativeInternalZstdStreamingInit(JNIEnv *env, jclass clazz,
                                                                       jint fd,
                                                                       jlong data_offset,
                                                                       jlong compressed_size) {
    auto state = std::make_unique<StreamingDecompressState>();
    if (!mapFdRange(state.get(), fd, data_offset, compressed_size)) return 0;
    jlong streamId = registerStream(std::move(state));
    LOGI("Internal zstd streaming initialized: stream %lld, offset=%lld, size=%lld",
         (long long)streamId, (long long)data_offset, (long long)compressed_size);
    return streamId;
}

} // extern "C"
