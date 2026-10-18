// alpha-video-source's range reader (third_party/alpha-video-source), in a namespace of its own:
// an app can link another copy, at another version, from the WebM player (react-native-alpha-video),
// whose pod also makes its headers public under the same names.
#pragma once

#define avsource alpha_video_gpu_avsource
#include "alpha-video-source/src/RangeReader.h"
