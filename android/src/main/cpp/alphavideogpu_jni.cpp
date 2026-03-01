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

#include "basisu_transcoder.h"

// Zstandard decompression - use the version bundled with Basis Universal
#define ZSTD_STATIC_LINKING_ONLY
#include "basis_universal/zstd/zstd.h"

// Proper ASTC decoder for emulator fallback (from richgel999/astc_dec)
#include "astc_decomp.h"

#define LOG_TAG "KTX2Player"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
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

// Initialize transcoder once
static bool g_transcoder_initialized = false;

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

// Map texture array ID to its PBO context
static std::map<GLuint, PBOContext> g_pbo_contexts;
static std::mutex g_pbo_mutex;

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

static void init_transcoder() {
    if (!g_transcoder_initialized) {
        basist::basisu_transcoder_init();
        g_transcoder_initialized = true;
        LOGI("Basis Universal transcoder initialized");
    }
}

// Determine best texture format for device
static basist::transcoder_texture_format get_best_format() {
    // First verify we have GLES3 context
    const char* version = (const char*)glGetString(GL_VERSION);
    if (version) {
        LOGI("GL_VERSION: %s", version);
        if (strstr(version, "OpenGL ES 3") == nullptr) {
            LOGE("ERROR: Expected GLES3 but got: %s", version);
            LOGE("ETC2/ASTC compression requires GLES3 context!");
        }
    }
    
    const char* renderer = (const char*)glGetString(GL_RENDERER);
    if (renderer) {
        LOGI("GL_RENDERER: %s", renderer);
    }
    
    // Check for ASTC support (preferred for quality)
    const char* extensions = (const char*)glGetString(GL_EXTENSIONS);
    if (extensions) {
        LOGI("Available GL extensions: %s", extensions);
        
        // Check max texture size
        GLint max_texture_size = 0;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &max_texture_size);
        LOGI("Max texture size: %d", max_texture_size);
        
        if (strstr(extensions, "GL_KHR_texture_compression_astc_ldr")) {
            LOGI("Using ASTC 4x4 format");
            return basist::transcoder_texture_format::cTFASTC_4x4_RGBA;
        }
        if (strstr(extensions, "GL_OES_compressed_ETC2_RGBA8_texture") ||
            strstr(extensions, "ETC2")) {
            LOGI("Using ETC2 RGBA format");
            return basist::transcoder_texture_format::cTFETC2_RGBA;
        }
    } else {
        LOGE("Failed to get GL extensions - no current GL context!");
    }
    // Fallback to RGBA32
    LOGW("Using RGBA32 fallback format - no compressed texture support detected");
    return basist::transcoder_texture_format::cTFRGBA32;
}

// Get GL format for transcoder format
static GLenum get_gl_format(basist::transcoder_texture_format format) {
    switch (format) {
        case basist::transcoder_texture_format::cTFASTC_4x4_RGBA:
            return GL_COMPRESSED_RGBA_ASTC_4x4_KHR;
        case basist::transcoder_texture_format::cTFETC2_RGBA:
            return GL_COMPRESSED_RGBA8_ETC2_EAC;
        default:
            return GL_RGBA;
    }
}

static bool is_compressed_format(basist::transcoder_texture_format format) {
    return format != basist::transcoder_texture_format::cTFRGBA32;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeInit(JNIEnv *env, jclass clazz) {
    init_transcoder();
}

JNIEXPORT jintArray JNICALL
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeTranscodeKTX2(JNIEnv *env, jclass clazz,
                                                          jbyteArray ktx2_data,
                                                          jint gl_texture_format) {
    init_transcoder();
    
    jsize data_length = env->GetArrayLength(ktx2_data);
    jbyte* data = env->GetByteArrayElements(ktx2_data, nullptr);
    
    if (!data) {
        LOGE("Failed to get KTX2 data");
        return nullptr;
    }
    
    // Create KTX2 transcoder
    basist::ktx2_transcoder transcoder;
    
    if (!transcoder.init((const uint8_t*)data, data_length)) {
        LOGE("Failed to init KTX2 transcoder");
        env->ReleaseByteArrayElements(ktx2_data, data, JNI_ABORT);
        return nullptr;
    }
    LOGI("KTX2 transcoder initialized successfully");
    
    if (!transcoder.start_transcoding()) {
        LOGE("Failed to start transcoding");
        env->ReleaseByteArrayElements(ktx2_data, data, JNI_ABORT);
        return nullptr;
    }
    LOGI("KTX2 transcoding started successfully");
    
    uint32_t width = transcoder.get_width();
    uint32_t height = transcoder.get_height();
    
    // Log KTX2 file format info for debugging
    bool has_alpha = transcoder.get_has_alpha();
    bool is_uastc = transcoder.is_uastc();
    LOGI("KTX2 info: dimensions=%ux%u, has_alpha=%s, is_uastc=%s", 
         width, height, has_alpha ? "YES" : "NO", is_uastc ? "YES" : "NO");
    
    // Check texture size limits
    GLint max_texture_size = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &max_texture_size);
    if (width > (uint32_t)max_texture_size || height > (uint32_t)max_texture_size) {
        LOGE("Texture size %dx%d exceeds device limit %d", width, height, max_texture_size);
        env->ReleaseByteArrayElements(ktx2_data, data, JNI_ABORT);
        return nullptr;
    }
    
    // Check if RGBA32 would use too much memory (> 64MB)
    uint32_t rgba32_size = width * height * 4;
    if (rgba32_size > 64 * 1024 * 1024) {
        LOGW("Large texture detected: %dx%d (%.1f MB uncompressed)", width, height, rgba32_size / (1024.0f * 1024.0f));
    }
    
    // Use the passed texture format instead of calling get_best_format()
    GLenum gl_format = (GLenum)gl_texture_format;
    bool compressed = (gl_format != GL_RGBA);
    
    // Determine Basis Universal format based on GL format
    basist::transcoder_texture_format target_format;
    if (gl_format == GL_COMPRESSED_RGBA_ASTC_4x4_KHR) {
        target_format = basist::transcoder_texture_format::cTFASTC_4x4_RGBA;
    } else if (gl_format == GL_COMPRESSED_RGBA8_ETC2_EAC) {
        target_format = basist::transcoder_texture_format::cTFETC2_RGBA;
    } else if (gl_format == GL_ETC1_RGB8_OES) {
        target_format = basist::transcoder_texture_format::cTFETC1_RGB;
        LOGI("Using ETC1 RGB format (no alpha)");
    } else {
        target_format = basist::transcoder_texture_format::cTFRGBA32;
    }
    
    // Calculate output size and block counts
    uint32_t blocks_x = (width + 3) / 4;
    uint32_t blocks_y = (height + 3) / 4;
    uint32_t total_blocks = blocks_x * blocks_y;
    uint32_t output_size_bytes;
    
    // Log dimension alignment info
    bool width_aligned = (width % 4) == 0;
    bool height_aligned = (height % 4) == 0;
    LOGI("Texture dimensions: %ux%u, block-aligned: width=%s height=%s", 
         width, height, width_aligned ? "YES" : "NO", height_aligned ? "YES" : "NO");
    LOGI("Block dimensions: %ux%u blocks = %u total blocks", blocks_x, blocks_y, total_blocks);
    
    if (compressed) {
        output_size_bytes = total_blocks * 16; // 16 bytes per block for ASTC 4x4 / ETC2
    } else {
        output_size_bytes = width * height * 4; // RGBA
    }
    
    LOGI("Allocating output buffer: %u bytes (%.1f MB)", 
         output_size_bytes, output_size_bytes / (1024.0f * 1024.0f));
    
    std::vector<uint8_t> output_data;
    try {
        output_data.resize(output_size_bytes);
    } catch (const std::bad_alloc& e) {
        LOGE("Failed to allocate output buffer: %u bytes - out of memory", output_size_bytes);
        env->ReleaseByteArrayElements(ktx2_data, data, JNI_ABORT);
        return nullptr;
    }
    LOGI("Output buffer allocated successfully");
    
    // Transcode - Basis Universal API expects:
    // - output_blocks_buf_size_in_blocks_or_pixels: total output buffer size in BYTES for compressed, pixels for RGBA
    // - output_row_pitch_in_blocks_or_pixels: 0 = use default (tightly packed)
    LOGI("Attempting to transcode to format: %d (compressed=%s)", target_format, compressed ? "true" : "false");
    
    // Use simpler transcode call - let Basis handle the details
    if (!transcoder.transcode_image_level(0, 0, 0, output_data.data(), 
                                           compressed ? total_blocks : (width * height),
                                           target_format)) {
        LOGE("Failed to transcode image - format %d may not be supported for this KTX2", target_format);
        env->ReleaseByteArrayElements(ktx2_data, data, JNI_ABORT);
        return nullptr;
    }
    LOGI("Image transcoded successfully");
    
    env->ReleaseByteArrayElements(ktx2_data, data, JNI_ABORT);
    
    // Check max texture size
    GLint maxTexSize = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTexSize);
    LOGI("Max texture size: %d, requested: %dx%d", maxTexSize, width, height);
    
    if (width > maxTexSize || height > maxTexSize) {
        LOGE("Texture size %dx%d exceeds max texture size %d", width, height, maxTexSize);
        return nullptr;
    }
    
    // Clear any pending GL errors
    while (glGetError() != GL_NO_ERROR) {}
    
    // Create OpenGL texture
    GLuint texture_id;
    glGenTextures(1, &texture_id);
    LOGI("Generated texture ID: %d", texture_id);
    
    glBindTexture(GL_TEXTURE_2D, texture_id);
    GLenum bindError = glGetError();
    if (bindError != GL_NO_ERROR) {
        LOGE("glBindTexture error: 0x%x", bindError);
    }
    
    if (compressed) {
        LOGI("Uploading compressed texture: format=0x%x, size=%zu bytes", gl_format, output_size_bytes);
        glCompressedTexImage2D(GL_TEXTURE_2D, 0, gl_format, width, height, 0,
                               output_size_bytes, output_data.data());
    } else {
        LOGI("Uploading RGBA texture: %dx%d, size=%zu bytes", width, height, output_size_bytes);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, output_data.data());
    }
    
    GLenum texError = glGetError();
    if (texError != GL_NO_ERROR) {
        LOGE("glTexImage2D error: 0x%x", texError);
        glDeleteTextures(1, &texture_id);
        return nullptr;
    }
    
    // Explicitly disable mipmaps - critical for ASTC on real devices
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
    
    // Check for GL errors
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        LOGE("OpenGL error after tex params: 0x%x", error);
    }
    
    LOGI("Created texture ID: %d (%dx%d)", texture_id, width, height);
    
    // Return [textureId, width, height]
    jintArray result = env->NewIntArray(3);
    jint values[3] = { (jint)texture_id, (jint)width, (jint)height };
    env->SetIntArrayRegion(result, 0, 3, values);
    
    return result;
}

// Transcode KTX2 without GPU upload - returns byte array with transcoded data + dimensions
// Result format: first 8 bytes are width (4) + height (4), rest is texture data
JNIEXPORT jbyteArray JNICALL
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeTranscodeKTX2NoUpload(JNIEnv *env, jclass clazz,
                                                                   jbyteArray ktx2_data,
                                                                   jint gl_texture_format) {
    init_transcoder();
    
    jsize data_length = env->GetArrayLength(ktx2_data);
    jbyte* data = env->GetByteArrayElements(ktx2_data, nullptr);
    
    if (!data) {
        LOGE("Failed to get KTX2 data");
        return nullptr;
    }
    
    basist::ktx2_transcoder transcoder;
    
    if (!transcoder.init((const uint8_t*)data, data_length)) {
        LOGE("Failed to init KTX2 transcoder");
        env->ReleaseByteArrayElements(ktx2_data, data, JNI_ABORT);
        return nullptr;
    }
    
    if (!transcoder.start_transcoding()) {
        LOGE("Failed to start transcoding");
        env->ReleaseByteArrayElements(ktx2_data, data, JNI_ABORT);
        return nullptr;
    }
    
    uint32_t width = transcoder.get_width();
    uint32_t height = transcoder.get_height();
    
    GLenum gl_format = (GLenum)gl_texture_format;
    bool compressed = (gl_format != GL_RGBA);
    
    basist::transcoder_texture_format target_format;
    if (gl_format == GL_COMPRESSED_RGBA_ASTC_4x4_KHR) {
        target_format = basist::transcoder_texture_format::cTFASTC_4x4_RGBA;
    } else if (gl_format == GL_COMPRESSED_RGBA8_ETC2_EAC) {
        target_format = basist::transcoder_texture_format::cTFETC2_RGBA;
    } else if (gl_format == GL_ETC1_RGB8_OES) {
        target_format = basist::transcoder_texture_format::cTFETC1_RGB;
    } else {
        target_format = basist::transcoder_texture_format::cTFRGBA32;
    }
    
    uint32_t blocks_x = (width + 3) / 4;
    uint32_t blocks_y = (height + 3) / 4;
    uint32_t total_blocks = blocks_x * blocks_y;
    uint32_t output_size_bytes;
    
    if (compressed) {
        output_size_bytes = total_blocks * 16;
    } else {
        output_size_bytes = width * height * 4;
    }
    
    std::vector<uint8_t> output_data;
    try {
        output_data.resize(output_size_bytes);
    } catch (const std::bad_alloc& e) {
        LOGE("Failed to allocate output buffer: %u bytes", output_size_bytes);
        env->ReleaseByteArrayElements(ktx2_data, data, JNI_ABORT);
        return nullptr;
    }
    
    if (!transcoder.transcode_image_level(0, 0, 0, output_data.data(), 
                                           compressed ? total_blocks : (width * height),
                                           target_format)) {
        LOGE("Failed to transcode image");
        env->ReleaseByteArrayElements(ktx2_data, data, JNI_ABORT);
        return nullptr;
    }
    
    env->ReleaseByteArrayElements(ktx2_data, data, JNI_ABORT);
    
    // Return byte array: [width(4 bytes), height(4 bytes), isCompressed(1 byte), texture data...]
    uint32_t result_size = 9 + output_size_bytes;
    jbyteArray result = env->NewByteArray(result_size);
    if (!result) {
        LOGE("Failed to allocate result array");
        return nullptr;
    }
    
    // Write header
    uint8_t header[9];
    header[0] = (width >> 0) & 0xFF;
    header[1] = (width >> 8) & 0xFF;
    header[2] = (width >> 16) & 0xFF;
    header[3] = (width >> 24) & 0xFF;
    header[4] = (height >> 0) & 0xFF;
    header[5] = (height >> 8) & 0xFF;
    header[6] = (height >> 16) & 0xFF;
    header[7] = (height >> 24) & 0xFF;
    header[8] = compressed ? 1 : 0;
    
    env->SetByteArrayRegion(result, 0, 9, (jbyte*)header);
    env->SetByteArrayRegion(result, 9, output_size_bytes, (jbyte*)output_data.data());
    
    LOGI("Transcoded KTX2 (no upload): %dx%d, %u bytes, compressed=%d", width, height, output_size_bytes, compressed);
    
    return result;
}

JNIEXPORT void JNICALL
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeDeleteTexture(JNIEnv *env, jclass clazz,
                                                          jint texture_id) {
    GLuint id = (GLuint)texture_id;
    glDeleteTextures(1, &id);
}

JNIEXPORT jbyteArray JNICALL
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeDecompressZstd(JNIEnv *env, jclass clazz,
                                                           jbyteArray data, jint offset,
                                                           jint compressed_length, jint uncompressed_length) {
    // Get input data
    jbyte* input_bytes = env->GetByteArrayElements(data, nullptr);
    if (!input_bytes) {
        LOGE("Failed to get input byte array");
        return nullptr;
    }
    
    const uint8_t* compressed_data = reinterpret_cast<const uint8_t*>(input_bytes) + offset;
    
    // Allocate output buffer
    std::vector<uint8_t> output_data(uncompressed_length);
    
    // Use Basis Universal's built-in zstd decompression
    size_t decompressed_size = ZSTD_decompress(
        output_data.data(), uncompressed_length,
        compressed_data, compressed_length
    );
    
    env->ReleaseByteArrayElements(data, input_bytes, JNI_ABORT);
    
    if (ZSTD_isError(decompressed_size)) {
        LOGE("Zstd decompression failed: %s", ZSTD_getErrorName(decompressed_size));
        return nullptr;
    }
    
    if (decompressed_size != (size_t)uncompressed_length) {
        LOGE("Zstd decompression size mismatch: expected %d, got %zu", uncompressed_length, decompressed_size);
        return nullptr;
    }
    
    LOGI("Zstd decompressed %d -> %zu bytes", compressed_length, decompressed_size);
    
    // Create output byte array
    jbyteArray result = env->NewByteArray(uncompressed_length);
    if (!result) {
        LOGE("Failed to allocate output byte array");
        return nullptr;
    }
    
    env->SetByteArrayRegion(result, 0, uncompressed_length, reinterpret_cast<const jbyte*>(output_data.data()));
    
    return result;
}

// Zero-copy Zstd decompression directly to a direct ByteBuffer
JNIEXPORT jboolean JNICALL
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeDecompressZstdToBuffer(JNIEnv *env, jclass clazz,
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

// Decode ASTC to RGBA and create texture (for emulator fallback)
JNIEXPORT jintArray JNICALL
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeDecodeASTCToRGBA(JNIEnv *env, jclass clazz,
                                                             jbyteArray astc_data,
                                                             jint width, jint height,
                                                             jint block_w, jint block_h) {
    LOGW("ASTC software decode fallback - this is for emulator testing only!");
    
    int bw = (block_w > 0) ? block_w : 4;
    int bh = (block_h > 0) ? block_h : 4;
    
    jbyte* input_bytes = env->GetByteArrayElements(astc_data, nullptr);
    if (!input_bytes) {
        LOGE("Failed to get ASTC byte array");
        return nullptr;
    }
    
    jsize astc_size = env->GetArrayLength(astc_data);
    const uint8_t* astc_ptr = reinterpret_cast<const uint8_t*>(input_bytes);
    
    // Decode ASTC to RGBA
    std::vector<uint8_t> rgba_data;
    bool success = decode_astc_to_rgba(astc_ptr, astc_size, width, height, bw, bh, rgba_data);
    
    env->ReleaseByteArrayElements(astc_data, input_bytes, JNI_ABORT);
    
    if (!success) {
        LOGE("Failed to decode ASTC to RGBA");
        return nullptr;
    }
    
    LOGI("Decoded ASTC %dx%d to RGBA (%zu bytes)", width, height, rgba_data.size());
    
    // Create OpenGL texture
    GLuint texture_id = 0;
    glGenTextures(1, &texture_id);
    glBindTexture(GL_TEXTURE_2D, texture_id);
    
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, rgba_data.data());
    
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        LOGE("OpenGL error after ASTC->RGBA texture upload: 0x%x", error);
        glDeleteTextures(1, &texture_id);
        return nullptr;
    }
    
    LOGI("Created RGBA texture from ASTC: ID=%d (%dx%d)", texture_id, width, height);
    
    // Return [textureId, width, height]
    jintArray result = env->NewIntArray(3);
    jint values[3] = { (jint)texture_id, (jint)width, (jint)height };
    env->SetIntArrayRegion(result, 0, 3, values);
    
    return result;
}

/**
 * Load a KTX2 texture array (one frame per layer).
 * Uses OpenGL ES 3.0 GL_TEXTURE_2D_ARRAY for efficient frame switching.
 * Returns [textureId, width, height, layerCount]
 */
JNIEXPORT jintArray JNICALL
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeLoadTextureArray(JNIEnv *env, jclass clazz,
                                                             jbyteArray ktx2_data,
                                                             jboolean use_astc) {
    init_transcoder();
    
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
 * This allows progressive decompression of .ktx2.zst files.
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
    
    // Fallback: heap-allocated input (for byte array init)
    std::vector<uint8_t> input_heap;
    
    // Unified input pointer and size
    const uint8_t* input_data = nullptr;
    size_t input_size = 0;
    size_t input_pos = 0;
    
    // Output buffer (reused across calls)
    std::vector<uint8_t> output;
    
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
 * Initialize streaming decompression with a file path (memory-mapped).
 * This is the preferred method - avoids copying data to Java/C++ heap.
 * Returns a unique stream ID (> 0), or 0 on error.
 */
JNIEXPORT jlong JNICALL
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeStreamingDecompressInitFromFile(JNIEnv *env, jclass clazz,
                                                                            jstring file_path) {
    const char* path = env->GetStringUTFChars(file_path, nullptr);
    if (!path) {
        LOGE("Failed to get file path string");
        return 0;
    }
    
    // Create new per-instance state
    auto state = std::make_unique<StreamingDecompressState>();
    
    // Open file
    state->fd = open(path, O_RDONLY);
    if (state->fd < 0) {
        LOGE("Failed to open file: %s (errno=%d)", path, errno);
        env->ReleaseStringUTFChars(file_path, path);
        return 0;
    }
    
    // Get file size
    struct stat st;
    if (fstat(state->fd, &st) < 0) {
        LOGE("Failed to stat file: %s", path);
        env->ReleaseStringUTFChars(file_path, path);
        return 0;
    }
    state->mmap_size = st.st_size;
    
    // Memory-map the file (read-only, private)
    state->mmap_ptr = mmap(nullptr, state->mmap_size, PROT_READ, MAP_PRIVATE, state->fd, 0);
    if (state->mmap_ptr == MAP_FAILED) {
        LOGE("Failed to mmap file: %s (errno=%d)", path, errno);
        env->ReleaseStringUTFChars(file_path, path);
        return 0;
    }
    
    // Advise kernel we'll read sequentially
    madvise(state->mmap_ptr, state->mmap_size, MADV_SEQUENTIAL);
    
    // Set up unified input pointer
    state->input_data = static_cast<const uint8_t*>(state->mmap_ptr);
    state->input_size = state->mmap_size;
    state->input_pos = 0;
    
    // Create decompression stream
    state->dstream = ZSTD_createDStream();
    if (!state->dstream) {
        LOGE("Failed to create ZSTD_DStream");
        env->ReleaseStringUTFChars(file_path, path);
        return 0;
    }
    
    size_t initResult = ZSTD_initDStream(state->dstream);
    if (ZSTD_isError(initResult)) {
        LOGE("Failed to init ZSTD_DStream: %s", ZSTD_getErrorName(initResult));
        env->ReleaseStringUTFChars(file_path, path);
        return 0;
    }
    
    // Allocate output buffer
    state->output.resize(64 * 1024);
    
    // Generate unique stream ID and store state
    jlong streamId = g_next_stream_id.fetch_add(1);
    
    LOGI("Streaming decompression initialized from mmap: stream %lld, %zu bytes from %s", 
         (long long)streamId, state->mmap_size, path);
    
    env->ReleaseStringUTFChars(file_path, path);
    
    {
        std::lock_guard<std::mutex> lock(g_streaming_mutex);
        g_streaming_states[streamId] = std::move(state);
    }
    
    return streamId;
}

/**
 * Initialize streaming decompression with compressed data (fallback).
 * Use nativeStreamingDecompressInitFromFile when possible for better memory efficiency.
 * Returns a unique stream ID (> 0), or 0 on error.
 */
JNIEXPORT jlong JNICALL
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeStreamingDecompressInit(JNIEnv *env, jclass clazz,
                                                                     jbyteArray compressed_data) {
    // Create new per-instance state
    auto state = std::make_unique<StreamingDecompressState>();
    
    // Create decompression stream
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
    
    // Copy compressed data to heap (fallback path)
    jsize data_length = env->GetArrayLength(compressed_data);
    jbyte* data = env->GetByteArrayElements(compressed_data, nullptr);
    if (!data) {
        LOGE("Failed to get compressed data");
        return 0;
    }
    
    state->input_heap.assign((uint8_t*)data, (uint8_t*)data + data_length);
    env->ReleaseByteArrayElements(compressed_data, data, JNI_ABORT);
    
    // Set up unified input pointer
    state->input_data = state->input_heap.data();
    state->input_size = state->input_heap.size();
    state->input_pos = 0;
    
    // Allocate output buffer
    state->output.resize(64 * 1024);
    
    // Generate unique stream ID and store state
    jlong streamId = g_next_stream_id.fetch_add(1);
    
    {
        std::lock_guard<std::mutex> lock(g_streaming_mutex);
        g_streaming_states[streamId] = std::move(state);
    }
    
    LOGI("Streaming decompression initialized (heap): stream %lld, %zu bytes input", (long long)streamId, data_length);
    
    return streamId;
}

/**
 * Continue streaming decompression for a specific stream.
 * Returns decompressed chunk, or null if done/error.
 * The returned array format: [status(1 byte), data...]
 * Status: 0 = more data available, 1 = done, 2 = error
 */
JNIEXPORT jbyteArray JNICALL
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeStreamingDecompressContinue(JNIEnv *env, jclass clazz,
                                                                         jlong stream_id,
                                                                         jint max_output_bytes) {
    StreamingDecompressState* state = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_streaming_mutex);
        auto it = g_streaming_states.find(stream_id);
        if (it == g_streaming_states.end()) {
            LOGE("Streaming decompression not found for stream %lld", (long long)stream_id);
            return nullptr;
        }
        state = it->second.get();
    }
    
    if (!state || !state->dstream) {
        LOGE("Streaming decompression not initialized for stream %lld", (long long)stream_id);
        return nullptr;
    }
    
    // Check if we've consumed all input
    if (state->input_pos >= state->input_size) {
        // Return done status
        jbyteArray result = env->NewByteArray(1);
        jbyte status = 1; // done
        env->SetByteArrayRegion(result, 0, 1, &status);
        return result;
    }
    
    // Ensure output buffer is large enough
    if (state->output.size() < (size_t)max_output_bytes) {
        state->output.resize(max_output_bytes);
    }
    
    // Setup input/output buffers (using unified input_data pointer)
    ZSTD_inBuffer input = {
        state->input_data + state->input_pos,
        state->input_size - state->input_pos,
        0
    };
    
    ZSTD_outBuffer output = {
        state->output.data(),
        (size_t)max_output_bytes,
        0
    };
    
    // Decompress
    size_t result = ZSTD_decompressStream(state->dstream, &output, &input);
    
    if (ZSTD_isError(result)) {
        LOGE("Streaming decompression error for stream %lld: %s", (long long)stream_id, ZSTD_getErrorName(result));
        jbyteArray errorResult = env->NewByteArray(1);
        jbyte status = 2; // error
        env->SetByteArrayRegion(errorResult, 0, 1, &status);
        return errorResult;
    }
    
    // Update input position
    state->input_pos += input.pos;
    
    // Check if done
    bool isDone = (state->input_pos >= state->input_size) && (result == 0);
    
    // Return result: [status, data...]
    jbyteArray resultArray = env->NewByteArray(1 + output.pos);
    jbyte statusByte = isDone ? 1 : 0;
    env->SetByteArrayRegion(resultArray, 0, 1, &statusByte);
    if (output.pos > 0) {
        env->SetByteArrayRegion(resultArray, 1, output.pos, (jbyte*)state->output.data());
    }
    
    return resultArray;
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
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeStreamingDecompressContinueToBuffer(JNIEnv *env, jclass clazz,
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
    
    // Check if we've consumed all input
    if (state->input_pos >= state->input_size) {
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
    
    // Check if done
    bool isDone = (state->input_pos >= state->input_size) && (result == 0);
    
    // Return: high 32 bits = status (0=more, 1=done), low 32 bits = bytes written
    jlong status = isDone ? 1 : 0;
    return (status << 32) | (jlong)output.pos;
}

/**
 * Clean up streaming decompression resources for a specific stream.
 */
JNIEXPORT void JNICALL
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeStreamingDecompressEnd(JNIEnv *env, jclass clazz,
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
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeUploadTextureArrayLayer(JNIEnv *env, jclass clazz,
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
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeCreateTextureArray(JNIEnv *env, jclass clazz,
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
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeUploadTextureArrayLayerFromBuffer(JNIEnv *env, jclass clazz,
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
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeUploadTextureArrayLayerFromBufferWithOffset(JNIEnv *env, jclass clazz,
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
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeInitPBOs(JNIEnv *env, jclass clazz,
                                                           jint texture_id,
                                                           jint buffer_size) {
    std::lock_guard<std::mutex> lock(g_pbo_mutex);
    
    GLuint texId = (GLuint)texture_id;
    
    // Check if already initialized
    if (g_pbo_contexts.find(texId) != g_pbo_contexts.end() && g_pbo_contexts[texId].initialized) {
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
    g_pbo_contexts[texId] = ctx;
    
    LOGI("Initialized PBOs for texture %u: size=%d bytes each", texId, buffer_size);
    return JNI_TRUE;
}

/**
 * Cleanup PBOs for a texture array.
 */
JNIEXPORT void JNICALL
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeCleanupPBOs(JNIEnv *env, jclass clazz,
                                                              jint texture_id) {
    std::lock_guard<std::mutex> lock(g_pbo_mutex);
    
    GLuint texId = (GLuint)texture_id;
    auto it = g_pbo_contexts.find(texId);
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
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeUploadTextureArrayLayerPBO(JNIEnv *env, jclass clazz,
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
        return Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeUploadTextureArrayLayerFromBuffer(
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
        auto it = g_pbo_contexts.find(texId);
        if (it == g_pbo_contexts.end() || !it->second.initialized) {
            LOGW("PBOs not initialized for texture %u, falling back to regular upload", texId);
            return Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeUploadTextureArrayLayerFromBuffer(
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
 * Unlike outer .zst files, only the level0 data is compressed - header is uncompressed.
 * 
 * @param file_path Path to the .ktx2 file
 * @param data_offset Offset to the compressed level0 data (from KTX2 header)
 * @param compressed_size Size of compressed data
 * @return Stream ID (> 0), or 0 on error
 */
JNIEXPORT jlong JNICALL
Java_com_alphavideo_gpu_AlphaVideoGpuModule_nativeInternalZstdStreamingInit(JNIEnv *env, jclass clazz,
                                                                       jstring file_path,
                                                                       jlong data_offset,
                                                                       jlong compressed_size) {
    const char* path = env->GetStringUTFChars(file_path, nullptr);
    if (!path) {
        LOGE("Failed to get file path string");
        return 0;
    }
    
    // Create new per-instance state
    auto state = std::make_unique<StreamingDecompressState>();
    
    // Open file
    state->fd = open(path, O_RDONLY);
    if (state->fd < 0) {
        LOGE("Failed to open file for internal zstd: %s (errno=%d)", path, errno);
        env->ReleaseStringUTFChars(file_path, path);
        return 0;
    }
    
    // Get file size for validation
    struct stat st;
    if (fstat(state->fd, &st) < 0) {
        LOGE("Failed to stat file: %s", path);
        env->ReleaseStringUTFChars(file_path, path);
        return 0;
    }
    
    // Validate offset and size
    if (data_offset + compressed_size > st.st_size) {
        LOGE("Internal zstd data extends beyond file: offset=%lld, size=%lld, fileSize=%lld",
             (long long)data_offset, (long long)compressed_size, (long long)st.st_size);
        env->ReleaseStringUTFChars(file_path, path);
        return 0;
    }
    
    // Memory-map only the compressed data portion
    // Use page-aligned offset for mmap
    long page_size = sysconf(_SC_PAGE_SIZE);
    off_t aligned_offset = (data_offset / page_size) * page_size;
    size_t offset_adjustment = data_offset - aligned_offset;
    size_t mmap_length = compressed_size + offset_adjustment;
    
    state->mmap_ptr = mmap(nullptr, mmap_length, PROT_READ, MAP_PRIVATE, state->fd, aligned_offset);
    if (state->mmap_ptr == MAP_FAILED) {
        LOGE("Failed to mmap internal zstd data: %s (errno=%d)", path, errno);
        env->ReleaseStringUTFChars(file_path, path);
        return 0;
    }
    state->mmap_size = mmap_length;
    
    // Advise kernel we'll read sequentially
    madvise(state->mmap_ptr, state->mmap_size, MADV_SEQUENTIAL);
    
    // Set up unified input pointer (adjusted for page alignment)
    state->input_data = static_cast<const uint8_t*>(state->mmap_ptr) + offset_adjustment;
    state->input_size = compressed_size;
    state->input_pos = 0;
    
    // Create decompression stream
    state->dstream = ZSTD_createDStream();
    if (!state->dstream) {
        LOGE("Failed to create ZSTD_DStream for internal zstd");
        env->ReleaseStringUTFChars(file_path, path);
        return 0;
    }
    
    size_t initResult = ZSTD_initDStream(state->dstream);
    if (ZSTD_isError(initResult)) {
        LOGE("Failed to init ZSTD_DStream for internal zstd: %s", ZSTD_getErrorName(initResult));
        env->ReleaseStringUTFChars(file_path, path);
        return 0;
    }
    
    // Allocate output buffer
    state->output.resize(64 * 1024);
    
    // Generate unique stream ID and store state
    jlong streamId = g_next_stream_id.fetch_add(1);
    
    LOGI("Internal zstd streaming initialized: stream %lld, offset=%lld, size=%lld from %s", 
         (long long)streamId, (long long)data_offset, (long long)compressed_size, path);
    
    env->ReleaseStringUTFChars(file_path, path);
    
    {
        std::lock_guard<std::mutex> lock(g_streaming_mutex);
        g_streaming_states[streamId] = std::move(state);
    }
    
    return streamId;
}

} // extern "C"
