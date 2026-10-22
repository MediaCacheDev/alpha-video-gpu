cmake_minimum_required(VERSION 3.18.1)

project("alphavideogpu")

# range-source needs C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# zstd and range-source, shared with iOS (third_party/ at the repository root): range-source's C
# interface and basic input (include), and until the add-on is its own package its reader (src)
set(THIRD_PARTY_DIR ${CMAKE_SOURCE_DIR}/../../../../third_party)
set(RANGE_SOURCE ${THIRD_PARTY_DIR}/range-source)

add_library(zstd STATIC
    ${THIRD_PARTY_DIR}/zstd/zstd.c
)

# Add native library
add_library(alphavideogpu SHARED
    alphavideogpu_jni.cpp
    astc_decomp.cpp
    source_jni.cpp
    ${RANGE_SOURCE}/src/RangeReader.cpp
    ${RANGE_SOURCE}/src/Store.cpp
    ${RANGE_SOURCE}/src/range_source.cpp
)

target_include_directories(alphavideogpu PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${THIRD_PARTY_DIR}
    ${RANGE_SOURCE}/include
    ${RANGE_SOURCE}/src
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
