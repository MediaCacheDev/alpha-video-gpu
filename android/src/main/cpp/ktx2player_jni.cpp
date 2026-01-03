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

#ifndef GL_COMPRESSED_RGBA_ASTC_4x4_KHR
#define GL_COMPRESSED_RGBA_ASTC_4x4_KHR 0x93B0
#endif

// Initialize transcoder once
static bool g_transcoder_initialized = false;

// Decode entire ASTC 4x4 texture to RGBA using proper decoder
static bool decode_astc_to_rgba(const uint8_t* astc_data, size_t astc_size,
                                 int width, int height,
                                 std::vector<uint8_t>& rgba_output) {
    const int block_width = 4;
    const int block_height = 4;
    int blocks_x = (width + block_width - 1) / block_width;
    int blocks_y = (height + block_height - 1) / block_height;
    size_t expected_size = blocks_x * blocks_y * 16; // 16 bytes per block
    
    if (astc_size < expected_size) {
        LOGE("ASTC data too small: %zu < %zu", astc_size, expected_size);
        return false;
    }
    
    rgba_output.resize(width * height * 4);
    memset(rgba_output.data(), 0, rgba_output.size());
    
    // Temporary buffer for one decoded block (4x4 RGBA)
    uint8_t block_pixels[block_width * block_height * 4];
    
    for (int by = 0; by < blocks_y; by++) {
        for (int bx = 0; bx < blocks_x; bx++) {
            const uint8_t* block = astc_data + (by * blocks_x + bx) * 16;
            
            // Use proper ASTC decoder (sRGB = false for linear decoding)
            bool success = basisu::astc::decompress(block_pixels, block, false, block_width, block_height);
            if (!success) {
                LOGW("Failed to decode ASTC block at (%d, %d)", bx, by);
                // Fill with magenta to indicate error
                for (int i = 0; i < 16; i++) {
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
    
    LOGI("Decoded ASTC %dx%d (%d x %d blocks) to RGBA", width, height, blocks_x, blocks_y);
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
Java_com_ktx2player_KTX2PlayerModule_nativeInit(JNIEnv *env, jclass clazz) {
    init_transcoder();
}

JNIEXPORT jintArray JNICALL
Java_com_ktx2player_KTX2PlayerModule_nativeTranscodeKTX2(JNIEnv *env, jclass clazz,
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
Java_com_ktx2player_KTX2PlayerModule_nativeTranscodeKTX2NoUpload(JNIEnv *env, jclass clazz,
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
Java_com_ktx2player_KTX2PlayerModule_nativeDeleteTexture(JNIEnv *env, jclass clazz,
                                                          jint texture_id) {
    GLuint id = (GLuint)texture_id;
    glDeleteTextures(1, &id);
}

JNIEXPORT jbyteArray JNICALL
Java_com_ktx2player_KTX2PlayerModule_nativeDecompressZstd(JNIEnv *env, jclass clazz,
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

// Decode ASTC to RGBA and create texture (for emulator fallback)
JNIEXPORT jintArray JNICALL
Java_com_ktx2player_KTX2PlayerModule_nativeDecodeASTCToRGBA(JNIEnv *env, jclass clazz,
                                                             jbyteArray astc_data,
                                                             jint width, jint height) {
    LOGW("ASTC software decode fallback - this is for emulator testing only!");
    
    jbyte* input_bytes = env->GetByteArrayElements(astc_data, nullptr);
    if (!input_bytes) {
        LOGE("Failed to get ASTC byte array");
        return nullptr;
    }
    
    jsize astc_size = env->GetArrayLength(astc_data);
    const uint8_t* astc_ptr = reinterpret_cast<const uint8_t*>(input_bytes);
    
    // Decode ASTC to RGBA
    std::vector<uint8_t> rgba_data;
    bool success = decode_astc_to_rgba(astc_ptr, astc_size, width, height, rgba_data);
    
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

} // extern "C"
