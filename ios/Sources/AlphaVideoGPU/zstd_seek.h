/**
 * Streaming zstd for a KTX2 level, with seeking. texenc can write the level as several zstd
 * frames followed by a seek table in zstd's seekable format (contrib/seekable_format): a
 * skippable frame listing each frame's compressed and decompressed size, ending with a 9-byte
 * footer. The decoder can start at any frame, so a seek decodes at most one frame's worth before
 * its target. A level without a table is a single stream, which decodes only from its start.
 *
 * Header-only C, so the player includes it and host tests (ios/Tests) compile it as is.
 */
#ifndef AVG_ZSTD_SEEK_H
#define AVG_ZSTD_SEEK_H

#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "zstd/zstd.h"

typedef struct {
  /** Number of frames; 0 for a level without a table. */
  size_t count;
  /** Where each frame starts in the level data, and where its output starts once decompressed. */
  uint64_t *compressedOffsets;
  uint64_t *decompressedOffsets;
} AVGZstdSeekTable;

/** A frame start: where the decoder can begin. */
typedef struct {
  uint64_t compressedOffset;
  uint64_t decompressedOffset;
} AVGZstdSeekPoint;

static inline uint32_t avg_read_u32le(const uint8_t *p) {
  return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static inline void avg_zstd_seek_table_free(AVGZstdSeekTable *table) {
  free(table->compressedOffsets);
  free(table->decompressedOffsets);
  memset(table, 0, sizeof(*table));
}

/**
 * Reads the table at the end of the level data into table (freed with avg_zstd_seek_table_free).
 * False, with an empty table, when there is none or it doesn't describe exactly the frames
 * before it and decompressedLength.
 */
static inline bool avg_zstd_seek_table_parse(const uint8_t *level, size_t length, uint64_t decompressedLength,
                                             AVGZstdSeekTable *table) {
  const uint32_t tableMagic = 0x184D2A5E, footerMagic = 0x8F92EAB1;
  const size_t footerSize = 9, skippableHeaderSize = 8;
  memset(table, 0, sizeof(*table));
  if (length < skippableHeaderSize + footerSize) return false;
  if (avg_read_u32le(level + length - 4) != footerMagic) return false;
  uint8_t descriptor = level[length - 5];
  if (descriptor & 0x7C) return false;  // reserved bits
  uint64_t count = avg_read_u32le(level + length - footerSize);
  uint64_t entrySize = (descriptor & 0x80) ? 12 : 8;  // with checksums
  uint64_t tableSize = count * entrySize + footerSize;
  if (count == 0 || tableSize + skippableHeaderSize > length) return false;
  size_t tableStart = length - skippableHeaderSize - (size_t)tableSize;
  if (avg_read_u32le(level + tableStart) != tableMagic) return false;
  if (avg_read_u32le(level + tableStart + 4) != tableSize) return false;

  uint64_t *compressedOffsets = (uint64_t *)malloc(count * sizeof(uint64_t));
  uint64_t *decompressedOffsets = (uint64_t *)malloc(count * sizeof(uint64_t));
  if (!compressedOffsets || !decompressedOffsets) {
    free(compressedOffsets);
    free(decompressedOffsets);
    return false;
  }
  uint64_t compressed = 0, decompressed = 0;
  for (size_t i = 0; i < count; i++) {
    const uint8_t *entry = level + tableStart + skippableHeaderSize + i * entrySize;
    compressedOffsets[i] = compressed;
    decompressedOffsets[i] = decompressed;
    compressed += avg_read_u32le(entry);
    decompressed += avg_read_u32le(entry + 4);
  }
  if (compressed != tableStart || decompressed != decompressedLength) {
    free(compressedOffsets);
    free(decompressedOffsets);
    return false;
  }
  table->count = (size_t)count;
  table->compressedOffsets = compressedOffsets;
  table->decompressedOffsets = decompressedOffsets;
  return true;
}

/** The last frame starting at or before that decompressed offset; the level start without a table. */
static inline AVGZstdSeekPoint avg_zstd_seek_point_before(const AVGZstdSeekTable *table, uint64_t decompressedOffset) {
  AVGZstdSeekPoint start = {0, 0};
  if (!table || table->count == 0) return start;
  size_t low = 0, high = table->count - 1;
  while (low < high) {
    size_t mid = (low + high + 1) / 2;
    if (table->decompressedOffsets[mid] <= decompressedOffset) low = mid;
    else high = mid - 1;
  }
  AVGZstdSeekPoint point = {table->compressedOffsets[low], table->decompressedOffsets[low]};
  return point;
}

/**
 * For a target at or ahead of the decoder: whether a decoder that has produced `decoded` bytes
 * reaches it sooner by carrying on than by starting at point, the target's seek point.
 */
static inline bool avg_zstd_carry_on(uint64_t decoded, AVGZstdSeekPoint point) {
  return decoded >= point.decompressedOffset;
}

/** A zstd stream over level data held in memory (a mapping of the file, in the player). */
typedef struct {
  ZSTD_DStream *dstream;
  const uint8_t *data;
  size_t size;
  size_t pos;
  // The last call filled the output mid-frame: zstd may hold more even with no input left
  bool outputPending;
} AVGZstdStream;

/** Starts decoding again at a frame start in the data (0 is the level's start). */
static inline void avg_zstd_stream_restart(AVGZstdStream *stream, uint64_t compressedOffset) {
  ZSTD_initDStream(stream->dstream);
  stream->pos = compressedOffset < stream->size ? (size_t)compressedOffset : stream->size;
  stream->outputPending = false;
}

/** True once every byte of output is out: the input is used up and nothing is left to flush. */
static inline bool avg_zstd_stream_ended(const AVGZstdStream *stream) {
  return stream->pos >= stream->size && !stream->outputPending;
}

/**
 * Decompresses up to capacity bytes into out and returns how many; carries on across frame
 * ends (several frames, and the skippable table, follow each other). Returns 0 once the stream
 * has ended, and sets *error on corrupt data.
 */
static inline size_t avg_zstd_stream_read(AVGZstdStream *stream, void *out, size_t capacity, const char **error) {
  size_t written = 0;
  while (written < capacity && !avg_zstd_stream_ended(stream)) {
    ZSTD_inBuffer input = {stream->data, stream->size, stream->pos};
    ZSTD_outBuffer output = {(uint8_t *)out + written, capacity - written, 0};
    size_t result = ZSTD_decompressStream(stream->dstream, &output, &input);
    if (ZSTD_isError(result)) {
      if (error) *error = ZSTD_getErrorName(result);
      stream->pos = stream->size;
      stream->outputPending = false;
      return written;
    }
    stream->pos = input.pos;
    written += output.pos;
    // 0 means the frame is done and flushed; calling again would start reading a next one
    stream->outputPending = output.pos == output.size && result != 0;
  }
  return written;
}

#endif
