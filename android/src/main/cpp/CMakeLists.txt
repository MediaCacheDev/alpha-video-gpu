cmake_minimum_required(VERSION 3.18.1)

project("alphavideogpu")

# The range reader (alpha-video-source) needs C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# zstd and the range reader, shared with iOS (third_party/ at the repository root)
set(THIRD_PARTY_DIR ${CMAKE_SOURCE_DIR}/../../../../third_party)
set(SOURCE_READER_DIR ${THIRD_PARTY_DIR}/alpha-video-source/src)

add_library(zstd STATIC
    ${THIRD_PARTY_DIR}/zstd/zstd.c
)

# Add native library
add_library(alphavideogpu SHARED
    alphavideogpu_jni.cpp
    astc_decomp.cpp
    source_jni.cpp
    ${SOURCE_READER_DIR}/RangeReader.cpp
    ${SOURCE_READER_DIR}/Store.cpp
)

target_include_directories(alphavideogpu PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${THIRD_PARTY_DIR}
    ${SOURCE_READER_DIR}
)

# Google Play requires 16 KB-aligned segments for Android 15+; NDK r27 and older link 4 KB by default.
target_link_options(alphavideogpu PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(alphavideogpu
    zstd
    EGL
    GLESv3
    log
    android
)
