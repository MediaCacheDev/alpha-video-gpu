cmake_minimum_required(VERSION 3.18.1)

project("alphavideogpu")

# Set C++ standard
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# zstd, shared with iOS (third_party/ at the repository root)
set(THIRD_PARTY_DIR ${CMAKE_SOURCE_DIR}/../../../../third_party)

add_library(zstd STATIC
    ${THIRD_PARTY_DIR}/zstd/zstd.c
)

# Add native library
add_library(alphavideogpu SHARED
    alphavideogpu_jni.cpp
    astc_decomp.cpp
)

target_include_directories(alphavideogpu PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${THIRD_PARTY_DIR}
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
