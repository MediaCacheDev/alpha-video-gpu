#include "Store.h"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace avsource {

struct StoreEntry {
    std::string key;
    // The data file is base + ".data", the meta file base + ".meta"; empty in a memory store
    std::string base;
    SourceInfo info;
    int64_t blockSize = 0;
    std::vector<bool> held;
    int64_t heldCount = 0;
    int64_t bytes = 0;
    int64_t lastUsed = 0;
    int fd = -1;
    int openCount = 0;
    bool dropped = false;
    // A memory store's blocks
    std::unordered_map<int64_t, std::vector<uint8_t>> blocks;
};

namespace {

constexpr char kMagic[4] = {'A', 'V', 'S', '1'};

std::mutex registryMutex;
std::unordered_map<std::string, std::shared_ptr<Store>> registry;

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::system_clock::now().time_since_epoch()).count();
}

std::string hashName(const std::string& key) {
    uint64_t hash = 14695981039346656037ull;
    for (unsigned char c : key) {
        hash ^= c;
        hash *= 1099511628211ull;
    }
    char name[17];
    snprintf(name, sizeof(name), "%016llx", static_cast<unsigned long long>(hash));
    return name;
}

int64_t blockCountOf(int64_t size, int64_t blockSize) {
    return size < 0 ? -1 : (size + blockSize - 1) / blockSize;
}

int64_t blockLengthOf(const StoreEntry& entry, int64_t index) {
    if (entry.info.size < 0) return entry.blockSize;
    return std::min(entry.blockSize, entry.info.size - index * entry.blockSize);
}

void put64(std::string& out, int64_t value) {
    for (int i = 0; i < 8; i++) out.push_back(static_cast<char>((static_cast<uint64_t>(value) >> (8 * i)) & 0xff));
}

void putString(std::string& out, const std::string& value) {
    put64(out, static_cast<int64_t>(value.size()));
    out += value;
}

bool get64(const std::string& in, size_t& pos, int64_t& value) {
    if (pos > in.size() || in.size() - pos < 8) return false;
    uint64_t v = 0;
    for (int i = 0; i < 8; i++) v |= static_cast<uint64_t>(static_cast<unsigned char>(in[pos + i])) << (8 * i);
    pos += 8;
    value = static_cast<int64_t>(v);
    return true;
}

bool getString(const std::string& in, size_t& pos, std::string& value) {
    int64_t length;
    if (!get64(in, pos, length) || length < 0 || static_cast<uint64_t>(length) > in.size() - pos) return false;
    value.assign(in, pos, static_cast<size_t>(length));
    pos += static_cast<size_t>(length);
    return true;
}

int openFile(const std::string& path, int flags) {
    int fd;
    do {
        fd = ::open(path.c_str(), flags | O_CLOEXEC, 0600);
    } while (fd < 0 && errno == EINTR);
    return fd;
}

bool writeAll(int fd, const char* data, size_t size) {
    while (size > 0) {
        ssize_t n = ::write(fd, data, size);
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        data += n;
        size -= static_cast<size_t>(n);
    }
    return true;
}

bool writeFileAtomically(const std::string& path, const std::string& data) {
    const std::string temp = path + ".tmp";
    int fd = openFile(temp, O_WRONLY | O_CREAT | O_TRUNC);
    if (fd < 0) return false;
    bool ok = writeAll(fd, data.data(), data.size());
    ok = ::close(fd) == 0 && ok;
    if (!ok || ::rename(temp.c_str(), path.c_str()) != 0) {
        ::unlink(temp.c_str());
        return false;
    }
    return true;
}

bool readFile(const std::string& path, std::string& data) {
    int fd = openFile(path, O_RDONLY);
    if (fd < 0) return false;
    data.clear();
    char buffer[16384];
    for (;;) {
        ssize_t n = ::read(fd, buffer, sizeof(buffer));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) {
            ::close(fd);
            return n == 0;
        }
        data.append(buffer, static_cast<size_t>(n));
    }
}

bool preadAll(int fd, uint8_t* out, int64_t size, int64_t offset) {
    while (size > 0) {
        ssize_t n = ::pread(fd, out, static_cast<size_t>(size), offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        out += n;
        size -= n;
        offset += n;
    }
    return true;
}

bool pwriteAll(int fd, const uint8_t* data, int64_t size, int64_t offset) {
    while (size > 0) {
        ssize_t n = ::pwrite(fd, data, static_cast<size_t>(size), offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        data += n;
        size -= n;
        offset += n;
    }
    return true;
}

bool makeDirs(const std::string& dir) {
    if (dir.empty()) return false;
    struct stat st;
    if (::stat(dir.c_str(), &st) == 0) return S_ISDIR(st.st_mode);
    const size_t slash = dir.find_last_of('/');
    if (slash != std::string::npos && slash > 0 && !makeDirs(dir.substr(0, slash))) return false;
    return ::mkdir(dir.c_str(), 0700) == 0 || errno == EEXIST;
}

bool endsWith(const std::string& s, const char* suffix) {
    const size_t n = std::strlen(suffix);
    return s.size() >= n && s.compare(s.size() - n, n, suffix) == 0;
}

std::string encodeMeta(const StoreEntry& entry) {
    std::string out(kMagic, sizeof(kMagic));
    put64(out, entry.blockSize);
    put64(out, entry.info.size);
    put64(out, entry.lastUsed);
    putString(out, entry.key);
    putString(out, entry.info.etag);
    putString(out, entry.info.lastModified);
    put64(out, static_cast<int64_t>(entry.held.size()));
    uint8_t byte = 0;
    for (size_t i = 0; i < entry.held.size(); i++) {
        if (entry.held[i]) byte |= static_cast<uint8_t>(1u << (i % 8));
        if (i % 8 == 7 || i + 1 == entry.held.size()) {
            out.push_back(static_cast<char>(byte));
            byte = 0;
        }
    }
    return out;
}

bool decodeMeta(const std::string& in, StoreEntry& entry) {
    if (in.size() < sizeof(kMagic) || in.compare(0, sizeof(kMagic), kMagic, sizeof(kMagic)) != 0) return false;
    size_t pos = sizeof(kMagic);
    int64_t count;
    if (!get64(in, pos, entry.blockSize) || !get64(in, pos, entry.info.size) || !get64(in, pos, entry.lastUsed) ||
        !getString(in, pos, entry.key) || !getString(in, pos, entry.info.etag) ||
        !getString(in, pos, entry.info.lastModified) || !get64(in, pos, count)) {
        return false;
    }
    if (entry.blockSize <= 0 || count < 0 || static_cast<uint64_t>(count + 7) / 8 != in.size() - pos) return false;
    const int64_t expected = blockCountOf(entry.info.size, entry.blockSize);
    if (expected >= 0 && count > expected) return false;
    entry.held.assign(static_cast<size_t>(count), false);
    for (int64_t i = 0; i < count; i++) {
        if (static_cast<unsigned char>(in[pos + i / 8]) & (1u << (i % 8))) {
            entry.held[static_cast<size_t>(i)] = true;
            entry.heldCount++;
            entry.bytes += blockLengthOf(entry, i);
        }
    }
    return true;
}

} // namespace

bool sameVersion(const SourceInfo& a, const SourceInfo& b) {
    if (a.size >= 0 && b.size >= 0 && a.size != b.size) return false;
    if (!a.etag.empty() && !b.etag.empty()) return a.etag == b.etag;
    if (!a.lastModified.empty() && !b.lastModified.empty()) return a.lastModified == b.lastModified;
    return true;
}

//==============================================================================
// StoredFile
//==============================================================================

StoredFile::StoredFile(std::shared_ptr<Store> store, std::shared_ptr<StoreEntry> entry)
    : store_(std::move(store)), entry_(std::move(entry)) {}

StoredFile::~StoredFile() {
    std::lock_guard<std::mutex> lock(store_->mutex_);
    if (--entry_->openCount == 0) {
        store_->closeFile(*entry_);
        store_->evictLocked();
    }
}

SourceInfo StoredFile::info() const {
    std::lock_guard<std::mutex> lock(store_->mutex_);
    return entry_->info;
}

int64_t StoredFile::blockSize() const {
    return entry_->blockSize;
}

bool StoredFile::has(int64_t index) const {
    std::lock_guard<std::mutex> lock(store_->mutex_);
    return !entry_->dropped && index >= 0 && index < static_cast<int64_t>(entry_->held.size()) &&
           entry_->held[static_cast<size_t>(index)];
}

bool StoredFile::complete() const {
    std::lock_guard<std::mutex> lock(store_->mutex_);
    return !entry_->dropped && entry_->info.size >= 0 &&
           entry_->heldCount == blockCountOf(entry_->info.size, entry_->blockSize);
}

bool StoredFile::read(int64_t index, uint8_t* out, int64_t length) {
    std::lock_guard<std::mutex> lock(store_->mutex_);
    StoreEntry& entry = *entry_;
    if (entry.dropped || index < 0 || index >= static_cast<int64_t>(entry.held.size()) ||
        !entry.held[static_cast<size_t>(index)] || length != blockLengthOf(entry, index)) {
        return false;
    }
    bool ok;
    if (store_->dir_.empty()) {
        auto it = entry.blocks.find(index);
        ok = it != entry.blocks.end() && static_cast<int64_t>(it->second.size()) == length;
        if (ok) std::memcpy(out, it->second.data(), static_cast<size_t>(length));
    } else {
        if (entry.fd < 0) entry.fd = openFile(entry.base + ".data", O_RDWR | O_CREAT);
        ok = entry.fd >= 0 && preadAll(entry.fd, out, length, index * entry.blockSize);
    }
    if (!ok) {
        // Gone from disk: forget it, so it's fetched again
        entry.held[static_cast<size_t>(index)] = false;
        entry.heldCount--;
        entry.bytes -= length;
        store_->used_ -= length;
        store_->saveLocked(entry);
    }
    return ok;
}

void StoredFile::write(int64_t index, const uint8_t* data, int64_t length) {
    std::lock_guard<std::mutex> lock(store_->mutex_);
    StoreEntry& entry = *entry_;
    if (entry.dropped || index < 0) return;
    const int64_t count = blockCountOf(entry.info.size, entry.blockSize);
    if ((count >= 0 && index >= count) || length != blockLengthOf(entry, index)) return;
    if (index < static_cast<int64_t>(entry.held.size()) && entry.held[static_cast<size_t>(index)]) return;

    if (store_->dir_.empty()) {
        entry.blocks[index].assign(data, data + length);
    } else {
        if (entry.fd < 0) entry.fd = openFile(entry.base + ".data", O_RDWR | O_CREAT);
        if (entry.fd < 0 || !pwriteAll(entry.fd, data, length, index * entry.blockSize)) return;
    }
    if (index >= static_cast<int64_t>(entry.held.size())) entry.held.resize(static_cast<size_t>(index) + 1, false);
    entry.held[static_cast<size_t>(index)] = true;
    entry.heldCount++;
    entry.bytes += length;
    store_->used_ += length;
    store_->saveLocked(entry);
    store_->evictLocked();
}

void StoredFile::setSize(int64_t size) {
    std::lock_guard<std::mutex> lock(store_->mutex_);
    store_->setSizeLocked(*entry_, size);
}

//==============================================================================
// Store
//==============================================================================

std::shared_ptr<Store> Store::disk(const std::string& dir, int64_t maxBytes) {
    std::lock_guard<std::mutex> lock(registryMutex);
    std::shared_ptr<Store>& store = registry["disk:" + dir];
    if (store) {
        store->setMaxBytes(maxBytes);
        return store;
    }
    if (!makeDirs(dir)) return nullptr;
    store.reset(new Store(dir, maxBytes));
    store->loadIndex();
    return store;
}

std::shared_ptr<Store> Store::memory(const std::string& name, int64_t maxBytes) {
    std::lock_guard<std::mutex> lock(registryMutex);
    std::shared_ptr<Store>& store = registry["memory:" + name];
    if (store) {
        store->setMaxBytes(maxBytes);
        return store;
    }
    store.reset(new Store("", maxBytes));
    return store;
}

Store::Store(std::string dir, int64_t maxBytes) : dir_(std::move(dir)), maxBytes_(maxBytes) {}

Store::~Store() {
    for (auto& item : entries_) closeFile(*item.second);
}

void Store::loadIndex() {
    std::lock_guard<std::mutex> lock(mutex_);
    DIR* dir = ::opendir(dir_.c_str());
    if (!dir) return;
    std::vector<std::string> names;
    while (dirent* item = ::readdir(dir)) names.emplace_back(item->d_name);
    ::closedir(dir);

    for (const std::string& name : names) {
        const std::string path = dir_ + "/" + name;
        if (endsWith(name, ".tmp")) {
            ::unlink(path.c_str());
            continue;
        }
        if (!endsWith(name, ".meta")) continue;
        auto entry = std::make_shared<StoreEntry>();
        entry->base = path.substr(0, path.size() - 5);
        std::string meta;
        struct stat st;
        const bool dataPresent = ::stat((entry->base + ".data").c_str(), &st) == 0;
        if (!readFile(path, meta) || !decodeMeta(meta, *entry) || (!dataPresent && entry->heldCount > 0) ||
            entry->base != dir_ + "/" + hashName(entry->key) || entries_.count(entry->key)) {
            ::unlink(path.c_str());
            ::unlink((entry->base + ".data").c_str());
            continue;
        }
        used_ += entry->bytes;
        entries_[entry->key] = std::move(entry);
    }
    // Data files whose meta file is gone
    for (const std::string& name : names) {
        if (!endsWith(name, ".data")) continue;
        const std::string base = dir_ + "/" + name.substr(0, name.size() - 5);
        struct stat st;
        if (::stat((base + ".meta").c_str(), &st) != 0) ::unlink((base + ".data").c_str());
    }
    evictLocked();
}

std::unique_ptr<StoredFile> Store::find(const std::string& key) {
    std::lock_guard<std::mutex> lock(mutex_);
    std::shared_ptr<StoreEntry> entry = lookupLocked(key);
    if (!entry) return nullptr;
    entry->openCount++;    touchLocked(*entry);
    return std::unique_ptr<StoredFile>(new StoredFile(shared_from_this(), entry));
}

std::unique_ptr<StoredFile> Store::open(const std::string& key, const SourceInfo& info, int64_t blockSize) {
    std::lock_guard<std::mutex> lock(mutex_);
    std::shared_ptr<StoreEntry> entry = lookupLocked(key);
    if (entry && sameVersion(entry->info, info)) {
        if (entry->info.etag.empty()) entry->info.etag = info.etag;
        if (entry->info.lastModified.empty()) entry->info.lastModified = info.lastModified;
        setSizeLocked(*entry, info.size);
        entry->openCount++;
        touchLocked(*entry);
        return std::unique_ptr<StoredFile>(new StoredFile(shared_from_this(), entry));
    }
    if (entry) dropLocked(entry);

    entry = std::make_shared<StoreEntry>();
    entry->key = key;
    entry->info = info;
    entry->blockSize = blockSize;
    if (!dir_.empty()) {
        entry->base = dir_ + "/" + hashName(key);
        // Another key with the same hash gives way
        for (auto it = entries_.begin(); it != entries_.end(); ++it) {
            if (it->second->base == entry->base) {
                dropLocked(it->second);
                break;
            }
        }
        entry->fd = openFile(entry->base + ".data", O_RDWR | O_CREAT | O_TRUNC);
        if (entry->fd < 0) return nullptr;
    }
    entry->openCount = 1;
    entries_[key] = entry;
    touchLocked(*entry);
    return std::unique_ptr<StoredFile>(new StoredFile(shared_from_this(), entry));
}

void Store::remove(const std::string& key) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (std::shared_ptr<StoreEntry> entry = lookupLocked(key)) dropLocked(entry);
}

void Store::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    while (!entries_.empty()) dropLocked(entries_.begin()->second);
}

int64_t Store::bytesUsed() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return used_;
}

void Store::setMaxBytes(int64_t maxBytes) {
    std::lock_guard<std::mutex> lock(mutex_);
    maxBytes_ = maxBytes;
    evictLocked();
}

std::shared_ptr<StoreEntry> Store::lookupLocked(const std::string& key) {
    auto it = entries_.find(key);
    return it == entries_.end() ? nullptr : it->second;
}

void Store::dropLocked(const std::shared_ptr<StoreEntry>& entry) {
    auto it = entries_.find(entry->key);
    if (it != entries_.end() && it->second == entry) entries_.erase(it);
    entry->dropped = true;
    used_ -= entry->bytes;
    entry->bytes = 0;
    entry->held.clear();
    entry->heldCount = 0;
    entry->blocks.clear();
    if (!dir_.empty()) {
        // A reader that has it open keeps its descriptor to the unlinked file until it closes
        ::unlink((entry->base + ".meta").c_str());
        ::unlink((entry->base + ".data").c_str());
    }
    if (entry->openCount == 0) closeFile(*entry);
}

void Store::setSizeLocked(StoreEntry& entry, int64_t size) {
    if (entry.dropped || entry.info.size >= 0 || size < 0) return;
    entry.info.size = size;
    const int64_t count = blockCountOf(size, entry.blockSize);
    while (static_cast<int64_t>(entry.held.size()) > count) {
        if (entry.held.back()) {
            entry.heldCount--;
            entry.bytes -= entry.blockSize;
            used_ -= entry.blockSize;
        }
        entry.held.pop_back();
    }
    saveLocked(entry);
}

void Store::touchLocked(StoreEntry& entry) {
    entry.lastUsed = std::max(nowMs(), entry.lastUsed + 1);
    saveLocked(entry);
}

void Store::saveLocked(StoreEntry& entry) {
    if (dir_.empty() || entry.dropped) return;
    writeFileAtomically(entry.base + ".meta", encodeMeta(entry));
}

void Store::evictLocked() {
    while (used_ > maxBytes_) {
        std::shared_ptr<StoreEntry> oldest;
        for (auto& item : entries_) {
            const std::shared_ptr<StoreEntry>& entry = item.second;            if (entry->openCount > 0 || entry->bytes == 0) continue;
            if (!oldest || entry->lastUsed < oldest->lastUsed) oldest = entry;
        }
        if (!oldest) return;
        dropLocked(oldest);
    }
}

void Store::closeFile(StoreEntry& entry) {
    if (entry.fd >= 0) {
        ::close(entry.fd);
        entry.fd = -1;
    }
}

} // namespace avsource
