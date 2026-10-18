#include "RangeReader.h"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <list>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

namespace avsource {

namespace {

// Largest single upstream read, so that a read far away waits at most this much for the fetch
constexpr int64_t kChunkBytes = 64 * 1024;
// How often a waiting read checks its abort flag; nothing signals when it changes
constexpr auto kAbortPollInterval = std::chrono::milliseconds(50);

using Bytes = std::shared_ptr<std::vector<uint8_t>>;

} // namespace

struct RangeReader::Core {
    ReaderOptions options;
    int64_t blockSize = 0;
    std::unique_ptr<Upstream> upstream;
    std::shared_ptr<Store> store;
    std::string key;

    mutable std::mutex mutex;
    // Wakes reads waiting for a block, and the fetch thread waiting for work
    std::condition_variable changed;
    SourceInfo info;
    std::unique_ptr<StoredFile> file;
    bool closed = false;
    // The source changed while it was read; every read of a block not held fails
    bool broken = false;
    // The block a read waits for, or -1
    int64_t demand = -1;
    // Where the last read ended, for read-ahead
    int64_t readEnd = 0;
    // After a failure, read-ahead waits for the next read
    bool readAheadPaused = false;
    std::unordered_map<int64_t, std::pair<Bytes, std::list<int64_t>::iterator>> memory;
    std::list<int64_t> lru;
    int64_t memoryUsed = 0;
    // The block being fetched; reads may take its first `filled` bytes
    int64_t filling = -1;
    std::vector<uint8_t> fillBuffer;
    int64_t filled = 0;
    int64_t failedBlock = -1;
    uint64_t failures = 0;
    // The block tryRead last asked for, and the failure count then
    int64_t askedBlock = -1;
    uint64_t askedFailures = 0;
    std::string error;
    Stats stats;

    // Fetch thread only
    bool connected = false;
    int64_t connPos = 0;

    void run();

private:
    enum class Fetched { Block, End, Moved, Failed, Closed };

    void fetchFrom(int64_t target);
    Fetched fetchBlock(int64_t index, int& attempts);
    bool connectWithRetries(int64_t pos, int64_t index, int& attempts);
    bool connect(int64_t pos, std::string& message);
    void disconnect();
    bool retryAfter(int attempt);
    void failLocked(int64_t index, const std::string& message);

public:
    int64_t read(int64_t pos, uint8_t* out, int64_t size, const std::atomic<bool>* abort, bool wait);
    int64_t nextTargetLocked();
    bool heldLocked(int64_t index) const;
    bool pastEndLocked(int64_t index) const;
    int64_t blockLengthLocked(int64_t index) const;
    Bytes blockLocked(int64_t index);
    void rememberLocked(int64_t index, Bytes data);
};

//==============================================================================
// Blocks held
//==============================================================================

bool RangeReader::Core::heldLocked(int64_t index) const {
    return memory.count(index) || (file && file->has(index));
}

bool RangeReader::Core::pastEndLocked(int64_t index) const {
    return info.size >= 0 && index * blockSize >= info.size;
}

int64_t RangeReader::Core::blockLengthLocked(int64_t index) const {
    if (info.size < 0) return blockSize;
    return std::max<int64_t>(0, std::min(blockSize, info.size - index * blockSize));
}

Bytes RangeReader::Core::blockLocked(int64_t index) {
    auto it = memory.find(index);
    if (it != memory.end()) {
        lru.splice(lru.end(), lru, it->second.second);
        return it->second.first;
    }
    if (!file || !file->has(index)) return nullptr;
    auto data = std::make_shared<std::vector<uint8_t>>(static_cast<size_t>(blockLengthLocked(index)));
    if (!file->read(index, data->data(), static_cast<int64_t>(data->size()))) return nullptr;
    rememberLocked(index, data);
    return data;
}

void RangeReader::Core::rememberLocked(int64_t index, Bytes data) {
    auto it = memory.find(index);
    if (it != memory.end()) {
        memoryUsed -= static_cast<int64_t>(it->second.first->size());
        lru.erase(it->second.second);
        memory.erase(it);
    }
    memoryUsed += static_cast<int64_t>(data->size());
    memory.emplace(index, std::make_pair(std::move(data), lru.insert(lru.end(), index)));
    while (memoryUsed > options.memoryBytes && lru.size() > 1) {
        auto oldest = memory.find(lru.front());
        memoryUsed -= static_cast<int64_t>(oldest->second.first->size());
        memory.erase(oldest);
        lru.pop_front();
    }
}

// The block a read waits for, else the first one missing within read-ahead, else -1
int64_t RangeReader::Core::nextTargetLocked() {
    if (broken) return -1;
    if (demand >= 0) {
        if (!heldLocked(demand) && !pastEndLocked(demand)) return demand;
        demand = -1;
    }
    if (readAheadPaused || options.readAheadBytes <= 0) return -1;
    int64_t end = readEnd + options.readAheadBytes;
    if (info.size >= 0) end = std::min(end, info.size);
    for (int64_t index = readEnd / blockSize; index * blockSize < end; index++) {
        if (!heldLocked(index)) return index;
    }
    return -1;
}

//==============================================================================
// The fetch thread
//==============================================================================

void RangeReader::Core::run() {
    std::unique_lock<std::mutex> lock(mutex);
    for (;;) {
        changed.wait(lock, [this] { return closed || nextTargetLocked() >= 0; });
        if (closed) break;
        const int64_t target = nextTargetLocked();
        lock.unlock();
        fetchFrom(target);
        lock.lock();
    }
    lock.unlock();
    disconnect();
}

// Fetches `target`, and the blocks after it while they're wanted next
void RangeReader::Core::fetchFrom(int64_t target) {
    const int64_t pos = target * blockSize;
    int attempts = 0;
    if (!connected || pos < connPos || pos - connPos > options.reconnectGap) {
        if (!connectWithRetries(pos, target, attempts)) return;
    }

    for (;;) {
        const Fetched fetched = fetchBlock(connPos / blockSize, attempts);
        if (fetched != Fetched::Block) return;
        std::lock_guard<std::mutex> lock(mutex);
        const int64_t next = nextTargetLocked();
        if (next < 0 || next * blockSize < connPos || next * blockSize - connPos > options.reconnectGap) return;
    }
}

RangeReader::Core::Fetched RangeReader::Core::fetchBlock(int64_t index, int& attempts) {
    const int64_t start = index * blockSize;
    int64_t length;
    {
        std::lock_guard<std::mutex> lock(mutex);
        length = blockLengthLocked(index);
        if (length == 0) return Fetched::End;
        filling = index;
        filled = 0;
    }

    int64_t got = 0;
    bool reachedEnd = false;
    while (got < length) {
        std::string message;
        const int64_t n = upstream->read(fillBuffer.data() + got, std::min(kChunkBytes, length - got), message);
        if (n == 0 && info.size < 0) {
            reachedEnd = true;
            break;
        }
        if (n <= 0) {
            if (n == 0) {
                message = "the source ended at " + std::to_string(start + got) + " of " +
                          std::to_string(info.size) + " bytes";
            }
            disconnect();
            {
                std::lock_guard<std::mutex> lock(mutex);
                filling = -1;
                if (closed) return Fetched::Closed;
                if (attempts >= options.retries) {
                    failLocked(index, message);
                    return Fetched::Failed;
                }
            }
            if (!retryAfter(++attempts) || !connectWithRetries(start, index, attempts)) return Fetched::Failed;
            std::lock_guard<std::mutex> lock(mutex);
            filling = index;
            filled = 0;
            got = 0;
            continue;
        }

        got += n;
        connPos += n;
        bool moved;
        {
            std::lock_guard<std::mutex> lock(mutex);
            stats.bytesFetched += n;
            filled = got;
            changed.notify_all();
            if (closed) {
                filling = -1;
                return Fetched::Closed;
            }
            // A read waiting far from here moves the fetch there
            const int64_t next = nextTargetLocked();
            moved = next >= 0 && next == demand && next != index &&
                    (next < index || next * blockSize - connPos > options.reconnectGap);
            if (moved) filling = -1;
        }
        if (moved) {
            disconnect();
            return Fetched::Moved;
        }
    }
    attempts = 0;

    std::lock_guard<std::mutex> lock(mutex);
    filling = -1;
    if (reachedEnd) {
        info.size = start + got;
        if (file) file->setSize(info.size);
    }
    if (got > 0) {
        auto data = std::make_shared<std::vector<uint8_t>>(fillBuffer.begin(), fillBuffer.begin() + got);
        if (file) file->write(index, data->data(), got);
        rememberLocked(index, std::move(data));
    }
    changed.notify_all();
    return reachedEnd ? Fetched::End : Fetched::Block;
}

// Connects at `pos`, retrying; on giving up, fails the reads waiting for block `index`
bool RangeReader::Core::connectWithRetries(int64_t pos, int64_t index, int& attempts) {
    std::string message;
    while (!connect(pos, message)) {
        if (attempts >= options.retries) {
            std::lock_guard<std::mutex> lock(mutex);
            if (!closed && !broken) failLocked(index, message);
            return false;
        }
        if (!retryAfter(++attempts)) return false;
    }
    return true;
}

bool RangeReader::Core::connect(int64_t pos, std::string& message) {
    disconnect();
    SourceInfo opened;
    if (!upstream->open(pos, opened, message)) return false;
    std::lock_guard<std::mutex> lock(mutex);
    stats.opens++;
    if (!sameVersion(info, opened)) {
        // Blocks of two versions can't be mixed: drop the stored copy, and fail what isn't held
        broken = true;
        error = "the source changed while it was read";
        if (store && !key.empty()) store->remove(key);
        failures++;
        changed.notify_all();
        upstream->close();
        return false;
    }
    if (info.size < 0 && opened.size >= 0) {
        info.size = opened.size;
        if (file) file->setSize(info.size);
    }
    connected = true;
    connPos = pos;
    return true;
}

void RangeReader::Core::disconnect() {
    if (!connected) return;
    connected = false;
    upstream->close();
}

// Waits before attempt `attempt`; false if the reader closed meanwhile
bool RangeReader::Core::retryAfter(int attempt) {
    const auto delay = std::chrono::milliseconds(options.retryDelayMs << std::min(attempt - 1, 10));
    std::unique_lock<std::mutex> lock(mutex);
    changed.wait_for(lock, delay, [this] { return closed || broken; });
    return !closed && !broken;
}

void RangeReader::Core::failLocked(int64_t index, const std::string& message) {
    error = message;
    failedBlock = index;
    failures++;
    if (demand == index) demand = -1;
    readAheadPaused = true;
    changed.notify_all();
}

//==============================================================================
// RangeReader
//==============================================================================

std::shared_ptr<RangeReader> RangeReader::open(std::unique_ptr<Upstream> upstream, std::shared_ptr<Store> store,
                                               const std::string& key, const ReaderOptions& options,
                                               std::string& error) {
    if (!upstream || options.blockSize <= 0) {
        error = "no source";
        return nullptr;
    }
    auto core = std::make_shared<Core>();
    core->options = options;
    core->upstream = std::move(upstream);
    if (key.empty()) store.reset();
    core->store = store;
    core->key = key;

    std::unique_ptr<StoredFile> file = store ? store->find(key) : nullptr;
    if (file && file->complete()) {
        core->info = file->info();
        core->blockSize = file->blockSize();
    } else {
        SourceInfo info;
        if (!core->upstream->open(0, info, error)) return nullptr;
        core->stats.opens = 1;
        core->connected = true;
        core->connPos = 0;
        const int64_t blockSize = file && sameVersion(file->info(), info) ? file->blockSize() : options.blockSize;
        file.reset();
        if (store) file = store->open(key, info, blockSize);
        core->info = file ? file->info() : info;
        core->blockSize = file ? file->blockSize() : blockSize;
    }
    core->file = std::move(file);
    core->fillBuffer.resize(static_cast<size_t>(core->blockSize));
    std::thread([core] { core->run(); }).detach();
    return std::shared_ptr<RangeReader>(new RangeReader(core));
}

RangeReader::RangeReader(std::shared_ptr<Core> core) : core_(std::move(core)) {}

RangeReader::~RangeReader() {
    close();
}

int64_t RangeReader::read(int64_t pos, uint8_t* out, int64_t size, const std::atomic<bool>* abort) {
    return core_->read(pos, out, size, abort, true);
}

int64_t RangeReader::tryRead(int64_t pos, uint8_t* out, int64_t size) {
    return core_->read(pos, out, size, nullptr, false);
}

int64_t RangeReader::Core::read(int64_t pos, uint8_t* out, int64_t size, const std::atomic<bool>* abort, bool wait) {
    if (pos < 0 || size < 0) return -1;
    if (size == 0) return 0;
    const int64_t index = pos / blockSize;
    const int64_t offset = pos - index * blockSize;

    std::unique_lock<std::mutex> lock(mutex);
    const uint64_t failuresBefore = failures;
    readAheadPaused = false;
    for (;;) {
        if (closed || (abort && abort->load())) return -1;
        if (info.size >= 0 && pos >= info.size) return 0;
        if (Bytes data = blockLocked(index)) {
            const int64_t n = std::min<int64_t>(size, static_cast<int64_t>(data->size()) - offset);
            if (n <= 0) return 0;
            std::memcpy(out, data->data() + offset, static_cast<size_t>(n));
            readEnd = pos + n;
            changed.notify_all();
            return n;
        }
        if (filling == index && offset < filled) {
            const int64_t n = std::min(size, filled - offset);
            std::memcpy(out, fillBuffer.data() + offset, static_cast<size_t>(n));
            readEnd = pos + n;
            return n;
        }
        if (broken) return -1;
        if (wait) {
            if (failures != failuresBefore && failedBlock == index) return -1;
        } else if (askedBlock == index && failedBlock == index && failures != askedFailures) {
            askedFailures = failures;
            return -1;
        }
        if (demand != index) {
            demand = index;
            changed.notify_all();
        }
        if (!wait) {
            if (askedBlock != index) {
                askedBlock = index;
                askedFailures = failures;
            }
            return kPending;
        }
        changed.wait_for(lock, kAbortPollInterval);
    }
}

bool RangeReader::fetchAll(const std::atomic<bool>* abort) {
    std::vector<uint8_t> scratch(static_cast<size_t>(core_->blockSize));
    for (int64_t pos = 0;;) {
        const int64_t n = read(pos, scratch.data(), static_cast<int64_t>(scratch.size()), abort);
        if (n < 0) return false;
        if (n == 0) return true;
        pos += n;
    }
}

int64_t RangeReader::size() const {
    std::lock_guard<std::mutex> lock(core_->mutex);
    return core_->info.size;
}

SourceInfo RangeReader::info() const {
    std::lock_guard<std::mutex> lock(core_->mutex);
    return core_->info;
}

std::string RangeReader::lastError() const {
    std::lock_guard<std::mutex> lock(core_->mutex);
    return core_->error;
}

RangeReader::Stats RangeReader::stats() const {
    std::lock_guard<std::mutex> lock(core_->mutex);
    return core_->stats;
}

void RangeReader::close() {
    // Released here rather than when the fetch thread ends, which may be much later, so the
    // store can evict the file as soon as nobody reads it
    std::unique_ptr<StoredFile> file;
    {
        std::lock_guard<std::mutex> lock(core_->mutex);
        if (core_->closed) return;
        core_->closed = true;
        file = std::move(core_->file);
        core_->changed.notify_all();
    }
    core_->upstream->cancel();
}

} // namespace avsource
