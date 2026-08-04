cmake_minimum_required(VERSION 3.18.1)

project("alphavideogpu")

# Set C++ standard
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add Basis Universal transcoder
set(BASIS_UNIVERSAL_DIR ${CMAKE_SOURCE_DIR}/basis_universal)

# Compile Basis transcoder as a static library
add_library(basis_transcoder STATIC
    ${BASIS_UNIVERSAL_DIR}/transcoder/basisu_transcoder.cpp
    ${BASIS_UNIVERSAL_DIR}/zstd/zstd.c
)

target_include_directories(basis_transcoder PUBLIC
    ${BASIS_UNIVERSAL_DIR}/transcoder
)

target_compile_definitions(basis_transcoder PRIVATE
    BASISU_NO_ITERATOR_DEBUG_LEVEL=1
)

# Add native library
add_library(alphavideogpu SHARED
    alphavideogpu_jni.cpp
    astc_decomp.cpp
    astv_decoder.cpp
)

target_include_directories(alphavideogpu PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${BASIS_UNIVERSAL_DIR}/transcoder
)

target_compile_definitions(alphavideogpu PRIVATE
    BASISU_NO_ITERATOR_DEBUG_LEVEL=1
    ASTV_USE_BUNDLED_ZSTD=1
)

target_link_libraries(alphavideogpu
    basis_transcoder
    EGL
    GLESv3
    log
    android
)
