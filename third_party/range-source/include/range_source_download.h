/*
 * The basic input, for a player running without the range-source add-on: a file downloaded whole,
 * in one read from the start, into a temporary file, and read as it grows.
 *
 * Header-only, so that a player carries it with range_source.h and builds it in. Needs
 * RANGE_SOURCE_CLIENT_NAMESPACE, as for range_source.h's C++ wrapper.
 */
#ifndef RANGE_SOURCE_DOWNLOAD_H
#define RANGE_SOURCE_DOWNLOAD_H

#include "range_source.h"

#ifndef RANGE_SOURCE_CLIENT_NAMESPACE
#error "Define RANGE_SOURCE_CLIENT_NAMESPACE before including range_source_download.h"
#endif

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

namespace RANGE_SOURCE_CLIENT_NAMESPACE {

struct DownloadOptions {
    /** Reopens after a failed read, each after twice the delay of the one before */
    int retries = 3;
    int64_t retryDelayMs = 500;
};

/**
 * Downloads a file into a temporary file, from one open of the upstream at 0, and reads it as it
 * arrives: a read past what has arrived waits for it. A dropped connection is reopened where it
 * stopped, a few times. The file is unlinked as soon as it's made, so nothing stays behind, even
 * after a crash; its space is freed when the reader is released.
 *
 * Nothing is kept between plays, and the upstream is never asked for anything but what follows.
 */
class DownloadReader : public Reader {
public:
    using Options = DownloadOptions;

    /**
     * Opens `upstream` at 0 and starts the download into a new file in `dir`. Blocks for the
     * open. Returns nullptr, with `error` set, if the file can't be made or the open fails.
     */
    static std::shared_ptr<DownloadReader> open(std::unique_ptr<Upstream> upstream, const std::string& dir,
                                                std::string& error, Options options = Options()) {
        if (!upstream) {
            error = "no source";
            return nullptr;
        }
        std::string path = dir + "/range-source-download-XXXXXX";
        std::vector<char> name(path.begin(), path.end());
        name.push_back('\0');
        const int fd = mkstemp(name.data());
        if (fd < 0) {
            error = "Cannot make a temporary file in " + dir;
            return nullptr;
        }
        unlink(name.data());

        auto state = std::make_shared<State>();
        state->fd = fd;
        state->options = options;
        state->upstream = std::move(upstream);
        if (!state->upstream->open(0, state->info, error)) {
            if (error.empty()) error = "the source couldn't be opened";
            return nullptr;
        }
        state->connected = true;
        std::thread([state] { state->run(); }).detach();
        return std::shared_ptr<DownloadReader>(new DownloadReader(state));
    }

    ~DownloadReader() override { close(); }
    DownloadReader(const DownloadReader&) = delete;
    DownloadReader& operator=(const DownloadReader&) = delete;

    int64_t read(int64_t pos, uint8_t* out, int64_t size, const std::atomic<int32_t>* abort = nullptr) override {
        return state_->read(pos, out, size, abort, true);
    }

    int64_t tryRead(int64_t pos, uint8_t* out, int64_t size) override {
        return state_->read(pos, out, size, nullptr, false);
    }

    bool fetchAll(const std::atomic<int32_t>* abort = nullptr) override {
        std::unique_lock<std::mutex> lock(state_->mutex);
        for (;;) {
            if (state_->closed || state_->failed || (abort && abort->load())) return false;
            if (state_->done) return true;
            state_->changed.wait_for(lock, kAbortPoll);
        }
    }

    int64_t size() const override {
        std::lock_guard<std::mutex> lock(state_->mutex);
        return state_->info.size;
    }

    std::string lastError() const override {
        std::lock_guard<std::mutex> lock(state_->mutex);
        return state_->error;
    }

    void close() override {
        {
            std::lock_guard<std::mutex> lock(state_->mutex);
            if (state_->closed) return;
            state_->closed = true;
            state_->changed.notify_all();
        }
        state_->upstream->cancel();
    }

    /** Bytes downloaded so far. */
    int64_t downloaded() const {
        std::lock_guard<std::mutex> lock(state_->mutex);
        return state_->have;
    }

private:
    // How often a waiting read checks its abort flag; nothing signals when it changes
    static constexpr std::chrono::milliseconds kAbortPoll{50};
    static constexpr int64_t kChunkBytes = 64 * 1024;

    struct State {
        std::mutex mutex;
        std::condition_variable changed;
        Options options;
        int fd = -1;
        std::unique_ptr<Upstream> upstream;
        // Guarded by mutex. info.size is learnt at the end if the source didn't give it.
        SourceInfo info;
        int64_t have = 0;
        bool done = false;
        bool failed = false;
        bool closed = false;
        std::string error;
        // The download thread's
        bool connected = false;

        ~State() {
            if (fd >= 0) ::close(fd);
        }

        void run() {
            std::vector<uint8_t> buffer(static_cast<size_t>(kChunkBytes));
            int attempts = 0;
            std::string message;
            for (;;) {
                if (isClosed()) break;
                if (!connected && !reconnect(attempts, message)) break;
                const int64_t n = upstream->read(buffer.data(), kChunkBytes, message);
                if (n > 0) {
                    if (!write(buffer.data(), n)) break;
                    attempts = 0;
                    continue;
                }
                if (n == 0) {
                    std::lock_guard<std::mutex> lock(mutex);
                    if (info.size < 0 || have >= info.size) {
                        info.size = have;
                        done = true;
                        changed.notify_all();
                        break;
                    }
                    message = "the source ended at " + std::to_string(have) + " of " + std::to_string(info.size) +
                              " bytes";
                }
                upstream->close();
                connected = false;
                if (isClosed()) break;
                if (attempts >= options.retries) {
                    fail(message);
                    break;
                }
                if (!waitRetry(++attempts)) break;
            }
            if (connected) upstream->close();
        }

        bool isClosed() {
            std::lock_guard<std::mutex> lock(mutex);
            return closed;
        }

        // Opens again where the download stopped, for the same version of the file
        bool reconnect(int& attempts, std::string& message) {
            for (;;) {
                int64_t from;
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    if (closed) return false;
                    from = have;
                }
                SourceInfo opened;
                message.clear();
                if (upstream->open(from, opened, message)) {
                    std::lock_guard<std::mutex> lock(mutex);
                    if (!sameVersion(info, opened)) {
                        upstream->close();
                        failLocked("the source changed while it was read");
                        return false;
                    }
                    connected = true;
                    return true;
                }
                if (attempts >= options.retries) {
                    fail(message.empty() ? "the source couldn't be opened" : message);
                    return false;
                }
                if (!waitRetry(++attempts)) return false;
            }
        }

        bool waitRetry(int attempt) {
            const auto delay = std::chrono::milliseconds(options.retryDelayMs << std::min(attempt - 1, 10));
            std::unique_lock<std::mutex> lock(mutex);
            changed.wait_for(lock, delay, [this] { return closed; });
            return !closed;
        }

        bool write(const uint8_t* data, int64_t n) {
            int64_t at;
            {
                std::lock_guard<std::mutex> lock(mutex);
                at = have;
            }
            for (int64_t written = 0; written < n;) {
                const ssize_t w = pwrite(fd, data + written, static_cast<size_t>(n - written), at + written);
                if (w < 0 && errno == EINTR) continue;
                if (w <= 0) {
                    fail("Cannot write the download to its temporary file (" + std::string(errno == ENOSPC ? "the disk is full" : "error " + std::to_string(errno)) + ")");
                    return false;
                }
                written += w;
            }
            std::lock_guard<std::mutex> lock(mutex);
            have += n;
            changed.notify_all();
            return true;
        }

        void fail(const std::string& message) {
            std::lock_guard<std::mutex> lock(mutex);
            failLocked(message);
        }

        void failLocked(const std::string& message) {
            if (closed) return;
            failed = true;
            error = message;
            changed.notify_all();
        }

        int64_t read(int64_t pos, uint8_t* out, int64_t size, const std::atomic<int32_t>* abort, bool wait) {
            if (pos < 0 || size < 0) return -1;
            if (size == 0) return 0;
            int64_t n;
            {
                std::unique_lock<std::mutex> lock(mutex);
                for (;;) {
                    if (closed || (abort && abort->load())) return -1;
                    if (info.size >= 0 && pos >= info.size) return 0;
                    if (pos < have) break;
                    if (done) return 0;
                    if (failed) return -1;
                    if (!wait) return kPending;
                    changed.wait_for(lock, kAbortPoll);
                }
                n = std::min(size, have - pos);
            }
            for (;;) {
                const ssize_t r = pread(fd, out, static_cast<size_t>(n), pos);
                if (r < 0 && errno == EINTR) continue;
                if (r < 0) {
                    std::lock_guard<std::mutex> lock(mutex);
                    error = "Cannot read the download's temporary file (error " + std::to_string(errno) + ")";
                    return -1;
                }
                return r;
            }
        }
    };

    static bool sameVersion(const SourceInfo& a, const SourceInfo& b) {
        if (a.size >= 0 && b.size >= 0 && a.size != b.size) return false;
        if (!a.etag.empty() && !b.etag.empty() && a.etag != b.etag) return false;
        if (!a.lastModified.empty() && !b.lastModified.empty() && a.lastModified != b.lastModified) return false;
        return true;
    }

    explicit DownloadReader(std::shared_ptr<State> state) : state_(std::move(state)) {}

    const std::shared_ptr<State> state_;
};

} // namespace RANGE_SOURCE_CLIENT_NAMESPACE

#endif /* RANGE_SOURCE_DOWNLOAD_H */
