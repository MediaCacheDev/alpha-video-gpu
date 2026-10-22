#include "range_source.h"

#include "RangeReader.h"
#include "Store.h"

#include <cstddef>
#include <cstring>
#include <exception>
#include <memory>
#include <string>

#ifndef RANGE_SOURCE_VERSION
#define RANGE_SOURCE_VERSION "1.0.0"
#endif

#if defined(_WIN32)
#define RANGE_SOURCE_EXPORT __declspec(dllexport)
#else
#define RANGE_SOURCE_EXPORT __attribute__((visibility("default")))
#endif

struct RangeSourceStore {
    std::shared_ptr<rangesource::Store> store;
};

struct RangeSourceReader {
    std::shared_ptr<rangesource::RangeReader> reader;
};

namespace {

using rangesource::SourceInfo;

void copyOut(const std::string& from, char* to, size_t toSize) {
    if (!to || toSize == 0) return;
    const size_t n = from.size() < toSize - 1 ? from.size() : toSize - 1;
    std::memcpy(to, from.data(), n);
    to[n] = '\0';
}

// Up to the first NUL, or the whole array if there's none
template <size_t N>
std::string copyIn(const char (&from)[N]) {
    return std::string(from, strnlen(from, N));
}

class CUpstream : public rangesource::Upstream {
public:
    explicit CUpstream(const RangeSourceUpstream& c) : c_(c) {}
    ~CUpstream() override {
        if (c_.release) c_.release(c_.context);
    }

    bool open(int64_t position, SourceInfo& info, std::string& error) override {
        RangeSourceInfo c{};
        c.size = -1;
        char message[512] = "";
        if (c_.open(c_.context, position, &c, message, sizeof(message)) != 0) {
            error = copyIn(message);
            if (error.empty()) error = "the source couldn't be opened";
            return false;
        }
        info.size = c.size;
        info.etag = copyIn(c.etag);
        info.lastModified = copyIn(c.lastModified);
        return true;
    }

    int64_t read(uint8_t* out, int64_t size, std::string& error) override {
        char message[512] = "";
        const int64_t n = c_.read(c_.context, out, size, message, sizeof(message));
        if (n < 0) {
            error = copyIn(message);
            if (error.empty()) error = "the source couldn't be read";
        }
        return n;
    }

    void close() override { c_.close(c_.context); }

    void cancel() override {
        if (c_.cancel) c_.cancel(c_.context);
    }

private:
    const RangeSourceUpstream c_;
};

// Whether an options struct of `structSize` bytes reaches past `offset + fieldSize`
bool reaches(uint32_t structSize, size_t offset, size_t fieldSize) {
    return structSize >= offset + fieldSize;
}

#define RANGE_SOURCE_OPTION(field)                                                                              \
    if (options && reaches(options->structSize, offsetof(RangeSourceReaderOptions, field), sizeof(options->field)) \
        && options->field >= 0)                                                                                 \
    result.field = options->field

rangesource::ReaderOptions readerOptions(const RangeSourceReaderOptions* options) {
    rangesource::ReaderOptions result;
    RANGE_SOURCE_OPTION(blockSize);
    RANGE_SOURCE_OPTION(memoryBytes);
    RANGE_SOURCE_OPTION(readAheadBytes);
    RANGE_SOURCE_OPTION(reconnectGap);
    RANGE_SOURCE_OPTION(retries);
    RANGE_SOURCE_OPTION(retryDelayMs);
    if (result.blockSize == 0) result.blockSize = rangesource::ReaderOptions().blockSize;
    return result;
}

#undef RANGE_SOURCE_OPTION

RangeSourceStore* wrapStore(std::shared_ptr<rangesource::Store> store) {
    return store ? new RangeSourceStore{std::move(store)} : nullptr;
}

RangeSourceStore* diskStore(const char* dir, int64_t maxBytes) {
    try {
        return wrapStore(rangesource::Store::disk(dir ? dir : "", maxBytes));
    } catch (const std::exception&) {
        return nullptr;
    }
}

RangeSourceStore* memoryStore(const char* name, int64_t maxBytes) {
    try {
        return wrapStore(rangesource::Store::memory(name ? name : "", maxBytes));
    } catch (const std::exception&) {
        return nullptr;
    }
}

int64_t storeBytesUsed(RangeSourceStore* store) {
    return store->store->bytesUsed();
}

void storeSetMaxBytes(RangeSourceStore* store, int64_t maxBytes) {
    store->store->setMaxBytes(maxBytes);
}

int storeHolds(RangeSourceStore* store, const char* key) {
    auto file = store->store->find(key ? key : "");
    if (!file) return RANGE_SOURCE_HOLDS_NONE;
    return file->complete() ? RANGE_SOURCE_HOLDS_WHOLE : RANGE_SOURCE_HOLDS_PART;
}

void storeRemove(RangeSourceStore* store, const char* key) {
    store->store->remove(key ? key : "");
}

void storeClear(RangeSourceStore* store) {
    store->store->clear();
}

void releaseStore(RangeSourceStore* store) {
    delete store;
}

RangeSourceReader* openReader(const RangeSourceUpstream* upstream, RangeSourceStore* store, const char* key,
                              const RangeSourceReaderOptions* options, char* error, size_t errorSize) {
    if (!upstream) {
        copyOut("no upstream", error, errorSize);
        return nullptr;
    }
    try {
        // Owns the upstream from here, so a failed open releases it too
        auto c = std::make_unique<CUpstream>(*upstream);
        std::string message;
        auto reader = rangesource::RangeReader::open(std::move(c), store ? store->store : nullptr, key ? key : "",
                                                     readerOptions(options), message);
        if (!reader) {
            copyOut(message, error, errorSize);
            return nullptr;
        }
        return new RangeSourceReader{std::move(reader)};
    } catch (const std::exception& e) {
        copyOut(e.what(), error, errorSize);
        return nullptr;
    }
}

int64_t readerSize(RangeSourceReader* reader) {
    return reader->reader->size();
}

int64_t readerRead(RangeSourceReader* reader, int64_t position, uint8_t* out, int64_t size, const volatile int32_t* abort) {
    return reader->reader->read(position, out, size, abort);
}

int64_t readerTryRead(RangeSourceReader* reader, int64_t position, uint8_t* out, int64_t size) {
    return reader->reader->tryRead(position, out, size);
}

int readerFetchAll(RangeSourceReader* reader, const volatile int32_t* abort) {
    return reader->reader->fetchAll(abort) ? 1 : 0;
}

size_t readerLastError(RangeSourceReader* reader, char* out, size_t outSize) {
    const std::string error = reader->reader->lastError();
    copyOut(error, out, outSize);
    return error.size();
}

void readerInfo(RangeSourceReader* reader, RangeSourceInfo* info) {
    const SourceInfo got = reader->reader->info();
    info->size = got.size;
    copyOut(got.etag, info->etag, sizeof(info->etag));
    copyOut(got.lastModified, info->lastModified, sizeof(info->lastModified));
}

void readerStats(RangeSourceReader* reader, RangeSourceStats* stats) {
    const rangesource::RangeReader::Stats got = reader->reader->stats();
    stats->opens = got.opens;
    stats->bytesFetched = got.bytesFetched;
}

void closeReader(RangeSourceReader* reader) {
    reader->reader->close();
}

void releaseReader(RangeSourceReader* reader) {
    delete reader;
}

const RangeSourcePlugin kPlugin = {
    RANGE_SOURCE_ABI,
    sizeof(RangeSourcePlugin),
    RANGE_SOURCE_VERSION,
    diskStore,
    memoryStore,
    storeBytesUsed,
    storeSetMaxBytes,
    storeHolds,
    storeRemove,
    storeClear,
    releaseStore,
    openReader,
    readerSize,
    readerRead,
    readerTryRead,
    readerFetchAll,
    readerLastError,
    readerInfo,
    readerStats,
    closeReader,
    releaseReader,
};

} // namespace

extern "C" RANGE_SOURCE_EXPORT const RangeSourcePlugin* range_source_plugin(void) {
    return &kPlugin;
}
