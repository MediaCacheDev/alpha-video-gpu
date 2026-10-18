#pragma once

#include <jni.h>
#include <memory>

#include "RangeReader.h"

/** The reader behind an AlphaVideoGpuReader reference, for a holder to keep its own copy. */
std::shared_ptr<avsource::RangeReader> readerFromReference(jlong reference);
