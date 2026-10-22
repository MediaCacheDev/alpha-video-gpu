// range-source (third_party/range-source): its C interface, the basic input, and until the add-on is
// its own package its reader, renamed here: an app can link another copy, at another version, from
// the WebM player (react-native-alpha-video). The two structs are C++ types in range_source.cpp,
// with inline destructors that would otherwise share a name.
#pragma once

#define rangesource alpha_video_gpu_rangesource
#define range_source_plugin alpha_video_gpu_range_source_plugin
#define RangeSourceStore AlphaVideoGpuRangeSourceStore
#define RangeSourceReader AlphaVideoGpuRangeSourceReader

#define RANGE_SOURCE_CLIENT_NAMESPACE avg_rs
#include "range-source/include/range_source.h"
#include "range-source/include/range_source_download.h"
