#pragma once

#include "Store.h"
#include "Upstream.h"

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>

namespace avsource {

struct ReaderOptions {
    /** Block size in bytes. A stored copy keeps the block size it was written with. */
    int64_t blockSize = 256 * 1024;
    /** Bytes of blocks kept in memory, least recently used out. */
    int64_t memoryBytes = 16 * 1024 * 1024;
    /** After each read, fetch this many bytes past it in the background. 0 turns read-ahead off. */
    int64_t readAheadBytes = 2 * 1024 * 1024;
    /** A fetch this far past the open read's position reads on through it rather than reconnect. */
    int64_t reconnectGap = 512 * 1024;
    /** Attempts after a failed open or read, each after twice the delay of the one before. */
    int retries = 3;
    int64_t retryDelayMs = 500;
};

/**
 * Reads a file by offset from an Upstream, through blocks in memory and an optional store, for a
 * demuxer that reads where it likes. One fetch thread keeps one read open on the upstream and
 * moves it only when a read needs bytes far from it: playing from the start is one request, and a
 * seek one more. After each read it fetches readAheadBytes past it. Thread-safe.
 *
 * A file the store holds whole is read without opening the upstream at all, so it plays offline.
 */
class RangeReader {
public:
    struct Stats {
        /** Reads opened on the upstream, the first included */
        int64_t opens = 0;
        /** Bytes read from the upstream */
        int64_t bytesFetched = 0;
    };

    /**
     * Opens a reader over `upstream`. With a `store` and a `key`, blocks are kept there, and read
     * from there before the upstream. Opens the upstream at 0 to learn the file's size and
     * version, unless the store holds the file whole. Blocks for that; returns nullptr with
     * `error` set if it fails.
     */
    static std::shared_ptr<RangeReader> open(std::unique_ptr<Upstream> upstream, std::shared_ptr<Store> store,
                                             const std::string& key, const ReaderOptions& options,
                                             std::string& error);

    /** Closes the reader. A fetch still waiting on the upstream finishes in the background. */
    ~RangeReader();
    RangeReader(const RangeReader&) = delete;
    RangeReader& operator=(const RangeReader&) = delete;

    /**
     * Reads up to `size` bytes at `pos` into `out`, waiting for them if they're being fetched.
     * Returns how many: fewer at a block's end, 0 at or past the end of the file, -1 if they can't
     * be fetched (lastError() says why), once the reader is closed, or once `abort` (may be null)
     * becomes true.
     */
    int64_t read(int64_t pos, uint8_t* out, int64_t size, const std::atomic<bool>* abort = nullptr);

    /** What tryRead returns while the bytes are on their way. */
    static constexpr int64_t kPending = -2;

    /**
     * read() without waiting, for a caller that must not block, such as a render loop. Returns
     * kPending when the bytes aren't held yet: it asks for them, so a later call finds them. A
     * later call returns -1, once, if fetching them failed; the call after that asks again.
     */
    int64_t tryRead(int64_t pos, uint8_t* out, int64_t size);

    /** Reads the whole file, so that it's held in the store. False on an error or abort. */
    bool fetchAll(const std::atomic<bool>* abort = nullptr);

    /** The file's size, or -1 while it isn't known. */
    int64_t size() const;
    SourceInfo info() const;
    std::string lastError() const;
    Stats stats() const;

    void close();

private:
    struct Core;
    explicit RangeReader(std::shared_ptr<Core> core);

    const std::shared_ptr<Core> core_;
};

} // namespace avsource
