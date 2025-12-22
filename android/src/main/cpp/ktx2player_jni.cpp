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

#define LOG_TAG "KTX2Player"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#ifndef GL_COMPRESSED_RGBA_ASTC_4x4_KHR
#define GL_COMPRESSED_RGBA_ASTC_4x4_KHR 0x93B0
#endif

// Initialize transcoder once
static bool g_transcoder_initialized = false;

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

JNIEXPORT void JNICALL
Java_com_ktx2player_KTX2PlayerModule_nativeDeleteTexture(JNIEnv *env, jclass clazz,
                                                          jint texture_id) {
    GLuint id = (GLuint)texture_id;
    glDeleteTextures(1, &id);
}

} // extern "C"
