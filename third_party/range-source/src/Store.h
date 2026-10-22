#pragma once

#include "Upstream.h"

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

namespace rangesource {

class Store;
struct StoreEntry;

/**
 * One key in a store, open for reading and writing its blocks. The store doesn't evict a key
 * while it's open. Thread-safe.
 */
class StoredFile {
public:
    ~StoredFile();
    StoredFile(const StoredFile&) = delete;
    StoredFile& operator=(const StoredFile&) = delete;

    SourceInfo info() const;
    int64_t blockSize() const;
    bool has(int64_t index) const;
    /** Whether every block is held; never while the size isn't known. */
    bool complete() const;
    /** Reads block `index`, `length` bytes, into `out`. False if it's gone missing. */
    bool read(int64_t index, uint8_t* out, int64_t length);
    void write(int64_t index, const uint8_t* data, int64_t length);
    /** Records the size, once a source that didn't give one reaches its end. */
    void setSize(int64_t size);

private:
    friend class Store;
    StoredFile(std::shared_ptr<Store> store, std::shared_ptr<StoreEntry> entry);

    const std::shared_ptr<Store> store_;
    const std::shared_ptr<StoreEntry> entry_;
};

/**
 * Keeps sources' bytes between plays, in fixed-size blocks, under a byte cap shared by every key
 * in it; the least recently used keys go first. On disk, each key is a sparse data file and a
 * small meta file (the source's size and version, and which blocks are held) in one folder.
 * Stores are best-effort: a block that has gone missing is fetched again.
 */
class Store : public std::enable_shared_from_this<Store> {
public:
    /**
     * The store in folder `dir`, created if needed. One per folder in the process: later calls
     * return the same store, with their `maxBytes`.
     */
    static std::shared_ptr<Store> disk(const std::string& dir, int64_t maxBytes);
    /** A store in memory, for the process's lifetime. One per name, like disk(). */
    static std::shared_ptr<Store> memory(const std::string& name, int64_t maxBytes);

    ~Store();

    /** What's held under `key`, open, or nullptr. */
    std::unique_ptr<StoredFile> find(const std::string& key);
    /**
     * Opens `key` for this version of the source. A copy of the same version is kept, with the
     * block size it was written with; a different one is dropped. Returns nullptr if the files
     * can't be created.
     */
    std::unique_ptr<StoredFile> open(const std::string& key, const SourceInfo& info, int64_t blockSize);
    void remove(const std::string& key);
    void clear();

    int64_t bytesUsed() const;
    void setMaxBytes(int64_t maxBytes);

private:
    friend class StoredFile;
    Store(std::string dir, int64_t maxBytes);

    void loadIndex();
    std::shared_ptr<StoreEntry> lookupLocked(const std::string& key);
    void dropLocked(const std::shared_ptr<StoreEntry>& entry);
    void setSizeLocked(StoreEntry& entry, int64_t size);
    void touchLocked(StoreEntry& entry);
    void saveLocked(StoreEntry& entry);
    void evictLocked();
    void closeFile(StoreEntry& entry);

    // Empty for a memory store
    const std::string dir_;
    mutable std::mutex mutex_;
    int64_t maxBytes_;
    int64_t used_ = 0;
    std::unordered_map<std::string, std::shared_ptr<StoreEntry>> entries_;
};

} // namespace rangesource
