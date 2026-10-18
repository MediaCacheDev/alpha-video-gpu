/**
 * Host tests for zstd_seek.h, on the shared fixtures. From the repository root:
 *
 *   clang -O1 -Ithird_party -Iios/Sources/AlphaVideoGPU ios/Tests/zstd_seek_test.c third_party/zstd/zstd.c -o /tmp/zstd_seek_test && /tmp/zstd_seek_test
 */
#include <stdio.h>
#include "zstd_seek.h"

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static void put_u32(uint8_t *p, uint32_t v) {
  p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}

/** Frame bytes (contents unread) and then the table, for frames {compressed, decompressed}. */
static size_t level_with(uint8_t *out, const uint32_t frames[][2], size_t count, bool checksums) {
  size_t entrySize = checksums ? 12 : 8, at = 0;
  for (size_t i = 0; i < count; i++) at += frames[i][0];
  memset(out, 0, at);
  uint32_t tableSize = (uint32_t)(count * entrySize + 9);
  put_u32(out + at, 0x184D2A5E); put_u32(out + at + 4, tableSize); at += 8;
  for (size_t i = 0; i < count; i++, at += entrySize) {
    put_u32(out + at, frames[i][0]); put_u32(out + at + 4, frames[i][1]);
    if (checksums) put_u32(out + at + 8, 0);
  }
  put_u32(out + at, (uint32_t)count); out[at + 4] = checksums ? 0x80 : 0; put_u32(out + at + 5, 0x8F92EAB1);
  return at + 9;
}

static void test_table(void) {
  static const uint32_t frames[3][2] = {{100, 4000}, {50, 4000}, {70, 2000}};
  uint8_t level[512];
  AVGZstdSeekTable table;
  for (int checksums = 0; checksums <= 1; checksums++) {
    size_t length = level_with(level, frames, 3, checksums);
    CHECK(avg_zstd_seek_table_parse(level, length, 10000, &table), "parse (checksums=%d)", checksums);
    CHECK(table.count == 3 && table.compressedOffsets[1] == 100 && table.compressedOffsets[2] == 150, "compressed offsets");
    CHECK(table.decompressedOffsets[1] == 4000 && table.decompressedOffsets[2] == 8000, "decompressed offsets");
    AVGZstdSeekPoint p = avg_zstd_seek_point_before(&table, 3999);
    CHECK(p.compressedOffset == 0 && p.decompressedOffset == 0, "point before 3999");
    p = avg_zstd_seek_point_before(&table, 4000);
    CHECK(p.compressedOffset == 100 && p.decompressedOffset == 4000, "point at 4000");
    p = avg_zstd_seek_point_before(&table, 9999);
    CHECK(p.compressedOffset == 150, "point before 9999");
    avg_zstd_seek_table_free(&table);
    CHECK(!avg_zstd_seek_table_parse(level, length, 10001, &table), "wrong decompressed length");
    CHECK(!avg_zstd_seek_table_parse(level + 1, length - 1, 10000, &table), "frames don't add up");
  }
  size_t length = level_with(level, frames, 3, false);
  level[length - 5] = 0x04;
  CHECK(!avg_zstd_seek_table_parse(level, length, 10000, &table), "reserved bits");
  length = level_with(level, frames, 0, false);
  CHECK(!avg_zstd_seek_table_parse(level, length, 0, &table), "no frames");
  CHECK(!avg_zstd_seek_table_parse(level, 8, 0, &table), "too short");

  // From the level's last bytes only, as read over the network
  length = level_with(level, frames, 3, true);
  uint64_t tail = avg_zstd_seek_table_tail_size(level, length);
  CHECK(tail == 8 + 3 * 12 + 9, "tail size %llu", (unsigned long long)tail);
  CHECK(avg_zstd_seek_table_parse_tail(level + length - tail, (size_t)tail, length, 10000, &table) &&
        table.count == 3 && table.compressedOffsets[2] == 150, "parse the exact tail");
  avg_zstd_seek_table_free(&table);
  CHECK(avg_zstd_seek_table_parse_tail(level + length - tail - 7, (size_t)tail + 7, length, 10000, &table) &&
        table.count == 3, "parse a longer tail");
  avg_zstd_seek_table_free(&table);
  CHECK(!avg_zstd_seek_table_parse_tail(level + length - tail + 1, (size_t)tail - 1, length, 10000, &table),
        "a tail one byte short");
  CHECK(avg_zstd_seek_table_tail_size(level, length - 1) == 0, "no footer");
  AVGZstdSeekPoint p = avg_zstd_seek_point_before(NULL, 12345);
  CHECK(p.compressedOffset == 0 && p.decompressedOffset == 0, "no table");
}

static uint8_t *read_file(const char *path, size_t *size) {
  FILE *f = fopen(path, "rb");
  if (!f) return NULL;
  fseek(f, 0, SEEK_END);
  *size = (size_t)ftell(f);
  fseek(f, 0, SEEK_SET);
  uint8_t *data = (uint8_t *)malloc(*size);
  if (fread(data, 1, *size, f) != *size) { free(data); data = NULL; }
  fclose(f);
  return data;
}

static uint64_t u64le(const uint8_t *p) {
  return (uint64_t)avg_read_u32le(p) | ((uint64_t)avg_read_u32le(p + 4) << 32);
}

/**
 * The level over a slow network: every other call is pending, the others copy at most 777 bytes,
 * and from failAt on reads fail.
 */
typedef struct {
  const uint8_t *level;
  uint64_t length;
  uint64_t failAt;
  unsigned calls;
} Network;

static int64_t network_read(void *context, uint64_t offset, uint8_t *buffer, size_t length) {
  Network *net = (Network *)context;
  if (net->calls++ % 2 == 0) return AVG_ZSTD_READ_PENDING;
  if (offset >= net->failAt) return -1;
  if (offset >= net->length) return 0;
  uint64_t n = net->length - offset;
  if (n > length) n = length;
  if (n > 777) n = 777;
  memcpy(buffer, net->level + offset, (size_t)n);
  return (int64_t)n;
}

/** Reads until want bytes are out or the stream ends, calling again while input is pending. */
static size_t read_all(AVGZstdStream *stream, uint8_t *out, size_t want, const char **error) {
  size_t got = 0;
  for (int calls = 0; got < want && calls < 10000000; calls++) {
    size_t n = avg_zstd_stream_read(stream, out + got, want - got, error);
    got += n;
    if (n == 0 && !stream->inputPending) break;
  }
  return got;
}

/** The player's seek, as in moveStreamTo: carry on or restart, then skip to target. */
typedef struct {
  AVGZstdStream stream;
  const AVGZstdSeekTable *table;
  uint64_t decoded;  // bytes read from the stream, counted from the level's start
  int restarts;
} Reader;

static void reader_seek(Reader *r, uint64_t target, uint8_t *scratch, size_t scratchSize) {
  AVGZstdSeekPoint point = avg_zstd_seek_point_before(r->table, target);
  if (r->decoded > target || !avg_zstd_carry_on(r->decoded, point)) {
    avg_zstd_stream_restart(&r->stream, point.compressedOffset);
    r->decoded = point.decompressedOffset;
    r->restarts++;
  }
  while (r->decoded < target) {
    size_t want = (size_t)(target - r->decoded < scratchSize ? target - r->decoded : scratchSize);
    size_t got = read_all(&r->stream, scratch, want, NULL);
    if (got == 0) break;
    r->decoded += got;
  }
}

static void test_fixture(const char *name, size_t expectedSeekPoints, bool overNetwork) {
  char path[512];
  snprintf(path, sizeof(path), "tests/fixtures/%s", name);
  size_t fileSize = 0;
  uint8_t *file = read_file(path, &fileSize);
  CHECK(file != NULL, "read %s (run from the repository root)", path);
  if (!file) return;
  uint32_t layers = avg_read_u32le(file + 32);
  uint64_t offset = u64le(file + 80), length = u64le(file + 88), whole = u64le(file + 96);
  size_t bytesPerLayer = (size_t)(whole / layers);
  const uint8_t *level = file + offset;

  AVGZstdSeekTable table;
  bool hasTable = avg_zstd_seek_table_parse(level, (size_t)length, whole, &table);
  CHECK(hasTable == (expectedSeekPoints > 0) && table.count == expectedSeekPoints,
        "%s: %zu seek points, expected %zu", name, table.count, expectedSeekPoints);

  if (hasTable) {
    // As the player reads it from a URL: a guess at the tail, then exactly what the footer asks for
    size_t guess = (size_t)(length < AVG_ZSTD_SEEK_TAIL_GUESS ? length : AVG_ZSTD_SEEK_TAIL_GUESS);
    uint64_t tail = avg_zstd_seek_table_tail_size(level + length - guess, guess);
    AVGZstdSeekTable fromTail;
    CHECK(tail > 0 && tail <= guess &&
          avg_zstd_seek_table_parse_tail(level + length - tail, (size_t)tail, length, whole, &fromTail) &&
          fromTail.count == table.count &&
          memcmp(fromTail.compressedOffsets, table.compressedOffsets, table.count * sizeof(uint64_t)) == 0,
          "%s: the table from the tail", name);
    avg_zstd_seek_table_free(&fromTail);
  }

  uint8_t *expected = (uint8_t *)malloc((size_t)whole);
  CHECK(ZSTD_decompress(expected, (size_t)whole, level, (size_t)length) == whole, "%s: ZSTD_decompress", name);

  // In order, in reads of 1 KB to a few layers, across frame ends, then the end
  Reader r = {{ZSTD_createDStream(), level, (size_t)length, 0, false}, hasTable ? &table : NULL, 0, 0};
  Network net = {level, length, UINT64_MAX, 0};
  uint8_t chunk[4096];
  if (overNetwork) {
    r.stream.data = NULL;
    r.stream.read = network_read;
    r.stream.readContext = &net;
    r.stream.chunk = chunk;
    r.stream.chunkCapacity = sizeof(chunk);
  }
  avg_zstd_stream_restart(&r.stream, 0);
  uint8_t *out = (uint8_t *)malloc((size_t)whole);
  size_t got = 0, step = 1024;
  for (size_t n; (n = read_all(&r.stream, out + got, step < whole - got ? step : (size_t)(whole - got), NULL)) > 0; step = step * 3 + 1) {
    got += n;
    if (got == whole) break;
  }
  CHECK(got == whole && memcmp(out, expected, (size_t)whole) == 0, "%s: sequential read, %zu of %llu bytes", name, got, (unsigned long long)whole);
  CHECK(read_all(&r.stream, out, 1, NULL) == 0 && avg_zstd_stream_ended(&r.stream), "%s: ends after the data", name);

  // Seeks forward and back, each followed by reading the target layer
  avg_zstd_stream_restart(&r.stream, 0);
  r.decoded = 0;
  r.restarts = 0;
  static const uint32_t targets[] = {0, 1, 29, 30, 31, 37, 59, 44, 10, 11, 3, 58, 0};
  uint8_t *scratch = (uint8_t *)malloc(64 * 1024);
  for (size_t i = 0; i < sizeof(targets) / sizeof(targets[0]); i++) {
    uint32_t layer = targets[i] < layers ? targets[i] : layers - 1;
    reader_seek(&r, (uint64_t)layer * bytesPerLayer, scratch, 64 * 1024);
    size_t n = read_all(&r.stream, out, bytesPerLayer, NULL);
    r.decoded += n;
    CHECK(n == bytesPerLayer && memcmp(out, expected + (size_t)layer * bytesPerLayer, bytesPerLayer) == 0,
          "%s: layer %u after seeking", name, layer);
  }
  // The 4 backward seeks restart; with seek points, so do the jumps past one (to 29, 37, 59, 58)
  CHECK(r.restarts == (hasTable ? 8 : 4), "%s: %d restarts", name, r.restarts);

  if (overNetwork) {
    // A read failing partway ends the stream with an error, after the bytes before it
    net.failAt = length / 2;
    avg_zstd_stream_restart(&r.stream, 0);
    r.stream.chunkLength = 0;
    const char *error = NULL;
    got = read_all(&r.stream, out, (size_t)whole, &error);
    CHECK(error != NULL && strcmp(error, "reading failed") == 0 && got < whole && avg_zstd_stream_ended(&r.stream),
          "%s: a failed read ends the stream (%s, %zu bytes)", name, error ? error : "no error", got);
  }

  ZSTD_freeDStream(r.stream.dstream);
  avg_zstd_seek_table_free(&table);
  free(scratch);
  free(out);
  free(expected);
  free(file);
}

int main(void) {
  test_table();
  for (int overNetwork = 0; overNetwork <= 1; overNetwork++) {
    test_fixture("card_383x217/astc4x4.ktx2", 0, overNetwork);
    test_fixture("card_383x217/astc4x4_stream.ktx2", 0, overNetwork);
    test_fixture("card_383x217/astc4x4_seek.ktx2", 20, overNetwork);
    test_fixture("card_256x256/astc4x4.ktx2", 0, overNetwork);
    test_fixture("card_256x256/astc4x4_seek.ktx2", 15, overNetwork);
    test_fixture("card_256x256/astc8x8.ktx2", 0, overNetwork);
  }
  printf(failures ? "%d checks failed\n" : "all checks pass\n", failures);
  return failures ? 1 : 0;
}
