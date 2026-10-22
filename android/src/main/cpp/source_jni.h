#pragma once

#include <jni.h>
#include <memory>

// URLs and data sources are read through the range-source add-on, by its C table, or without it
// through the basic input: a download into a temporary file. Both are an avg_rs::Reader.
#define RANGE_SOURCE_CLIENT_NAMESPACE avg_rs
#include "range_source.h"
#include "range_source_download.h"

/** The reader behind an AlphaVideoGpuReader reference, for a holder to keep its own copy. */
std::shared_ptr<avg_rs::Reader> readerFromReference(jlong reference);
