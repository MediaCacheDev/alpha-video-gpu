/*
 * range-source's native reader and stores, as a C interface: a versioned table of functions.
 *
 * A player doesn't link the add-on. It gets the table at runtime (range_source_plugin(), found
 * through the add-on's Java or Objective-C class) and carries a copy of this header only, so the
 * two never pass C++ objects, and each can keep its own C++ runtime.
 *
 * The table only grows: a new function goes at the end, and structSize says which are there. A
 * table with another `abi` is incompatible.
 *
 * Defining RANGE_SOURCE_CLIENT_NAMESPACE before including this header adds a C++ wrapper over the
 * table, in that namespace. Each binary names its own, so two copies of this header never clash.
 */
#ifndef RANGE_SOURCE_H
#define RANGE_SOURCE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RANGE_SOURCE_ABI 1

/* What tryRead returns while the bytes are on their way. */
#define RANGE_SOURCE_PENDING (-2)

/* storeHolds */
#define RANGE_SOURCE_HOLDS_NONE 0
#define RANGE_SOURCE_HOLDS_PART 1
#define RANGE_SOURCE_HOLDS_WHOLE 2

typedef struct RangeSourceStore RangeSourceStore;
typedef struct RangeSourceReader RangeSourceReader;

/* What a source tells about the file when a read opens. Strings are cut to fit; empty if none. */
typedef struct RangeSourceInfo {
    /* Total size in bytes, or -1 while it isn't known (a response without a length) */
    int64_t size;
    /* ETag, or any string that changes when the content does */
    char etag[256];
    /* Last-Modified, for sources without an ETag */
    char lastModified[64];
} RangeSourceInfo;

/*
 * Where a reader's bytes come from: the player's HTTP fetcher, or an app's data source. Open a read
 * at a position, read on in order, close. One read is open at a time. open, read and close are
 * called on the reader's fetch thread only; cancel may be called from any thread.
 *
 * Errors are written to `error`, `errorSize` bytes with the terminating NUL.
 */
typedef struct RangeSourceUpstream {
    /* sizeof(RangeSourceUpstream) */
    uint32_t structSize;
    void* context;
    /*
     * Opens a read of the bytes from `position` to the end, closing any read still open, and fills
     * `info`. Opening at or past the end gives a read that ends at once. Returns 0, or -1.
     */
    int (*open)(void* context, int64_t position, RangeSourceInfo* info, char* error, size_t errorSize);
    /* Reads up to `size` bytes, waiting for at least one. Returns how many, 0 at the end, or -1. */
    int64_t (*read)(void* context, uint8_t* out, int64_t size, char* error, size_t errorSize);
    void (*close)(void* context);
    /* Makes an open or read in progress return soon, failing. May be NULL. */
    void (*cancel)(void* context);
    /* Called once, when the reader is done with the upstream, perhaps on another thread. May be NULL. */
    void (*release)(void* context);
} RangeSourceUpstream;

/* A negative field takes the add-on's default; readAheadBytes 0 turns read-ahead off. */
typedef struct RangeSourceReaderOptions {
    /* sizeof(RangeSourceReaderOptions) */
    uint32_t structSize;
    /* Block size in bytes. A stored copy keeps the block size it was written with. */
    int64_t blockSize;
    /* Bytes of blocks kept in memory, least recently used out */
    int64_t memoryBytes;
    /* After each read, fetch this many bytes past it in the background */
    int64_t readAheadBytes;
    /* A fetch this far past the open read's position reads on through it rather than reconnect */
    int64_t reconnectGap;
    /* Attempts after a failed open or read, each after twice the delay of the one before */
    int32_t retries;
    int64_t retryDelayMs;
} RangeSourceReaderOptions;

typedef struct RangeSourceStats {
    /* Reads opened on the upstream, the first included */
    int64_t opens;
    /* Bytes read from the upstream */
    int64_t bytesFetched;
} RangeSourceStats;

/*
 * Every function is thread-safe. A handle is released once, by its release function; a store's
 * handle may be released while readers opened with it are still reading.
 */
typedef struct RangeSourcePlugin {
    /* RANGE_SOURCE_ABI */
    uint32_t abi;
    /* sizeof(RangeSourcePlugin) when the add-on was built */
    uint32_t structSize;
    /* The add-on's version, such as "1.0.0" */
    const char* version;

    /*
     * Keeps sources' bytes between plays, under a byte cap shared by every key in it; the least
     * recently used keys go first. One store per folder (or name, in memory) in the process: a
     * later call gets the same store, with its `maxBytes`. NULL if the folder can't be created.
     */
    RangeSourceStore* (*diskStore)(const char* dir, int64_t maxBytes);
    RangeSourceStore* (*memoryStore)(const char* name, int64_t maxBytes);
    int64_t (*storeBytesUsed)(RangeSourceStore* store);
    void (*storeSetMaxBytes)(RangeSourceStore* store, int64_t maxBytes);
    /* RANGE_SOURCE_HOLDS_NONE, _PART or _WHOLE */
    int (*storeHolds)(RangeSourceStore* store, const char* key);
    void (*storeRemove)(RangeSourceStore* store, const char* key);
    void (*storeClear)(RangeSourceStore* store);
    void (*releaseStore)(RangeSourceStore* store);

    /*
     * Opens a reader over `upstream`, which it owns from here on, even when opening fails: it calls
     * upstream->release when done. With a `store` (may be NULL) and a `key`, blocks are kept there,
     * and read from there before the upstream. Opens the upstream at 0 to learn the file's size and
     * version, unless the store holds the file whole, and blocks for that. `options` may be NULL.
     * Returns NULL, with `error` set, if it fails.
     */
    RangeSourceReader* (*openReader)(const RangeSourceUpstream* upstream, RangeSourceStore* store, const char* key,
                                     const RangeSourceReaderOptions* options, char* error, size_t errorSize);
    /* The file's size, or -1 while it isn't known */
    int64_t (*size)(RangeSourceReader* reader);
    /*
     * Reads up to `size` bytes at `position`, waiting for them if they're being fetched. Returns
     * how many: fewer at a block's end, 0 at or past the end of the file, -1 if they can't be
     * fetched (lastError says why), once the reader is closed, or once `*abort` (may be NULL)
     * becomes nonzero. `*abort` is read atomically.
     */
    int64_t (*read)(RangeSourceReader* reader, int64_t position, uint8_t* out, int64_t size,
                    const volatile int32_t* abort);
    /*
     * read without waiting, for a caller that must not block, such as a render loop. Returns
     * RANGE_SOURCE_PENDING when the bytes aren't held yet: it asks for them, so a later call finds
     * them. A later call returns -1, once, if fetching them failed; the call after that asks again.
     */
    int64_t (*tryRead)(RangeSourceReader* reader, int64_t position, uint8_t* out, int64_t size);
    /* Reads the whole file, so that it's held in the store. Returns 1, or 0 on an error or abort. */
    int (*fetchAll)(RangeSourceReader* reader, const volatile int32_t* abort);
    /* Writes the last error into `out`, cut to fit, and returns its full length, like snprintf. */
    size_t (*lastError)(RangeSourceReader* reader, char* out, size_t outSize);
    void (*info)(RangeSourceReader* reader, RangeSourceInfo* info);
    void (*stats)(RangeSourceReader* reader, RangeSourceStats* stats);
    /* Fails reads in progress and later ones. A fetch still waiting on the upstream finishes in the background. */
    void (*closeReader)(RangeSourceReader* reader);
    /* Closes the reader if needed, and frees the handle. */
    void (*releaseReader)(RangeSourceReader* reader);
} RangeSourcePlugin;

/* The add-on's table, for the add-on's own Java and Objective-C classes to hand to players. */
const RangeSourcePlugin* range_source_plugin(void);

#ifdef __cplusplus
} /* extern "C" */
#endif

#if defined(__cplusplus) && defined(RANGE_SOURCE_CLIENT_NAMESPACE)

#include <atomic>
#include <cstring>
#include <memory>
#include <string>
#include <utility>

namespace RANGE_SOURCE_CLIENT_NAMESPACE {

struct SourceInfo {
    int64_t size = -1;
    std::string etag;
    std::string lastModified;
};

/** An upstream in C++, which RangeReader::open hands to the add-on as a RangeSourceUpstream. */
class Upstream {
public:
    virtual ~Upstream() = default;
    virtual bool open(int64_t position, SourceInfo& info, std::string& error) = 0;
    virtual int64_t read(uint8_t* out, int64_t size, std::string& error) = 0;
    virtual void close() = 0;
    virtual void cancel() {}

    /** `upstream` as a RangeSourceUpstream that owns it. */
    static RangeSourceUpstream toC(std::unique_ptr<Upstream> upstream) {
        RangeSourceUpstream c{};
        c.structSize = sizeof(RangeSourceUpstream);
        c.context = upstream.release();
        c.open = [](void* context, int64_t position, RangeSourceInfo* info, char* error, size_t errorSize) {
            SourceInfo got;
            std::string message;
            if (!static_cast<Upstream*>(context)->open(position, got, message)) {
                copy(message, error, errorSize);
                return -1;
            }
            info->size = got.size;
            copy(got.etag, info->etag, sizeof(info->etag));
            copy(got.lastModified, info->lastModified, sizeof(info->lastModified));
            return 0;
        };
        c.read = [](void* context, uint8_t* out, int64_t size, char* error, size_t errorSize) {
            std::string message;
            const int64_t n = static_cast<Upstream*>(context)->read(out, size, message);
            if (n < 0) copy(message, error, errorSize);
            return n;
        };
        c.close = [](void* context) { static_cast<Upstream*>(context)->close(); };
        c.cancel = [](void* context) { static_cast<Upstream*>(context)->cancel(); };
        c.release = [](void* context) { delete static_cast<Upstream*>(context); };
        return c;
    }

    static void copy(const std::string& from, char* to, size_t toSize) {
        if (!to || toSize == 0) return;
        const size_t n = from.size() < toSize - 1 ? from.size() : toSize - 1;
        std::memcpy(to, from.data(), n);
        to[n] = '\0';
    }
};

/** A negative field takes the add-on's default. */
struct ReaderOptions {
    int64_t blockSize = -1;
    int64_t memoryBytes = -1;
    int64_t readAheadBytes = -1;
    int64_t reconnectGap = -1;
    int retries = -1;
    int64_t retryDelayMs = -1;
};

class Store {
public:
    static std::shared_ptr<Store> disk(const RangeSourcePlugin* plugin, const std::string& dir, int64_t maxBytes) {
        return wrap(plugin, plugin->diskStore(dir.c_str(), maxBytes));
    }
    static std::shared_ptr<Store> memory(const RangeSourcePlugin* plugin, const std::string& name, int64_t maxBytes) {
        return wrap(plugin, plugin->memoryStore(name.c_str(), maxBytes));
    }

    ~Store() { plugin_->releaseStore(store_); }
    Store(const Store&) = delete;
    Store& operator=(const Store&) = delete;

    int64_t bytesUsed() const { return plugin_->storeBytesUsed(store_); }
    void setMaxBytes(int64_t maxBytes) { plugin_->storeSetMaxBytes(store_, maxBytes); }
    /** RANGE_SOURCE_HOLDS_NONE, _PART or _WHOLE */
    int holds(const std::string& key) const { return plugin_->storeHolds(store_, key.c_str()); }
    void remove(const std::string& key) { plugin_->storeRemove(store_, key.c_str()); }
    void clear() { plugin_->storeClear(store_); }
    RangeSourceStore* handle() const { return store_; }

private:
    Store(const RangeSourcePlugin* plugin, RangeSourceStore* store) : plugin_(plugin), store_(store) {}
    static std::shared_ptr<Store> wrap(const RangeSourcePlugin* plugin, RangeSourceStore* store) {
        return store ? std::shared_ptr<Store>(new Store(plugin, store)) : nullptr;
    }

    const RangeSourcePlugin* const plugin_;
    RangeSourceStore* const store_;
};

/**
 * What a player reads a file through: the add-on's RangeReader, or, without the add-on, a
 * DownloadReader (range_source_download.h). Thread-safe.
 */
class Reader {
public:
    static constexpr int64_t kPending = RANGE_SOURCE_PENDING;

    virtual ~Reader() = default;
    /**
     * Reads up to `size` bytes at `pos`, waiting for them. Returns how many, 0 at or past the end,
     * or -1 if they can't be had (lastError() says why), once closed, or once `*abort` is nonzero.
     */
    virtual int64_t read(int64_t pos, uint8_t* out, int64_t size, const std::atomic<int32_t>* abort = nullptr) = 0;
    /** read() without waiting: kPending while the bytes are on their way. */
    virtual int64_t tryRead(int64_t pos, uint8_t* out, int64_t size) = 0;
    /** Waits until the whole file is held. False on an error, once closed, or on an abort. */
    virtual bool fetchAll(const std::atomic<int32_t>* abort = nullptr) = 0;
    /** The file's size, or -1 while it isn't known. */
    virtual int64_t size() const = 0;
    virtual std::string lastError() const = 0;
    /** Fails reads in progress and later ones, and stops fetching. */
    virtual void close() = 0;
};

/** The add-on's reader, with the C++ reader's methods. */
class RangeReader : public Reader {
public:
    struct Stats {
        int64_t opens = 0;
        int64_t bytesFetched = 0;
    };

    static std::shared_ptr<RangeReader> open(const RangeSourcePlugin* plugin, std::unique_ptr<Upstream> upstream,
                                             const std::shared_ptr<Store>& store, const std::string& key,
                                             const ReaderOptions& options, std::string& error) {
        return open(plugin, Upstream::toC(std::move(upstream)), store, key, options, error);
    }

    /** Over an upstream already in C, such as the add-on's for an app's data source; owns it from here on. */
    static std::shared_ptr<RangeReader> open(const RangeSourcePlugin* plugin, const RangeSourceUpstream& c,
                                             const std::shared_ptr<Store>& store, const std::string& key,
                                             const ReaderOptions& options, std::string& error) {
        RangeSourceReaderOptions o{};
        o.structSize = sizeof(o);
        o.blockSize = options.blockSize;
        o.memoryBytes = options.memoryBytes;
        o.readAheadBytes = options.readAheadBytes;
        o.reconnectGap = options.reconnectGap;
        o.retries = options.retries;
        o.retryDelayMs = options.retryDelayMs;
        char message[512] = "";
        RangeSourceReader* reader =
            plugin->openReader(&c, store ? store->handle() : nullptr, key.c_str(), &o, message, sizeof(message));
        if (!reader) {
            error = message;
            return nullptr;
        }
        return std::shared_ptr<RangeReader>(new RangeReader(plugin, reader));
    }

    ~RangeReader() override { plugin_->releaseReader(reader_); }
    RangeReader(const RangeReader&) = delete;
    RangeReader& operator=(const RangeReader&) = delete;

    int64_t read(int64_t pos, uint8_t* out, int64_t size, const std::atomic<int32_t>* abort = nullptr) override {
        return plugin_->read(reader_, pos, out, size, cFlag(abort));
    }
    int64_t tryRead(int64_t pos, uint8_t* out, int64_t size) override {
        return plugin_->tryRead(reader_, pos, out, size);
    }
    bool fetchAll(const std::atomic<int32_t>* abort = nullptr) override {
        return plugin_->fetchAll(reader_, cFlag(abort)) != 0;
    }
    int64_t size() const override { return plugin_->size(reader_); }

    SourceInfo info() const {
        RangeSourceInfo c{};
        plugin_->info(reader_, &c);
        SourceInfo info;
        info.size = c.size;
        info.etag = c.etag;
        info.lastModified = c.lastModified;
        return info;
    }

    std::string lastError() const override {
        char message[512];
        const size_t n = plugin_->lastError(reader_, message, sizeof(message));
        if (n < sizeof(message)) return std::string(message, n);
        std::string whole(n + 1, '\0');
        plugin_->lastError(reader_, &whole[0], whole.size());
        whole.resize(n);
        return whole;
    }

    Stats stats() const {
        RangeSourceStats c{};
        plugin_->stats(reader_, &c);
        Stats stats;
        stats.opens = c.opens;
        stats.bytesFetched = c.bytesFetched;
        return stats;
    }

    void close() override { plugin_->closeReader(reader_); }

private:
    RangeReader(const RangeSourcePlugin* plugin, RangeSourceReader* reader) : plugin_(plugin), reader_(reader) {}

    // std::atomic<int32_t> is an int32_t on every compiler the players build with
    static const volatile int32_t* cFlag(const std::atomic<int32_t>* abort) {
        static_assert(sizeof(std::atomic<int32_t>) == sizeof(int32_t) && std::atomic<int32_t>::is_always_lock_free,
                      "std::atomic<int32_t> must be a plain int32_t");
        return reinterpret_cast<const volatile int32_t*>(abort);
    }

    const RangeSourcePlugin* const plugin_;
    RangeSourceReader* const reader_;
};

} // namespace RANGE_SOURCE_CLIENT_NAMESPACE

#endif /* __cplusplus && RANGE_SOURCE_CLIENT_NAMESPACE */

#endif /* RANGE_SOURCE_H */
