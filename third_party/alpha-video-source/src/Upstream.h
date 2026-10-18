#pragma once

#include <cstdint>
#include <string>

namespace avsource {

/** What a source tells about the file when a read opens. */
struct SourceInfo {
    /** Total size in bytes, or -1 while it isn't known (a response without a length). */
    int64_t size = -1;
    /** ETag, or any string that changes when the content does; empty if none. */
    std::string etag;
    /** Last-Modified, for sources without an ETag; empty if none. */
    std::string lastModified;
};

/**
 * Whether two answers describe the same content: no known sizes that differ, and no ETag or
 * Last-Modified that differs.
 */
bool sameVersion(const SourceInfo& a, const SourceInfo& b);

/**
 * Where a reader's bytes come from: the platform's HTTP fetcher, or an app's data source (a
 * Media3 DataSource on Android, an AlphaVideoDataSource on iOS). It has a Media3 DataSource's
 * shape: open a read at a position, read on in order, close.
 *
 * One read is open at a time. open, read and close are called on the reader's fetch thread only;
 * cancel may be called from any thread.
 */
class Upstream {
public:
    virtual ~Upstream() = default;

    /**
     * Opens a read of the bytes from `position` to the end, closing any read still open. Fills
     * `info` with what the source knows. Opening at or past the end gives a read that ends at
     * once. Returns false, with `error` set, if it can't be opened.
     */
    virtual bool open(int64_t position, SourceInfo& info, std::string& error) = 0;

    /**
     * Reads up to `size` bytes, continuing from the last read; waits for at least one. Returns how
     * many it read, 0 at the end of the file, or -1 with `error` set.
     */
    virtual int64_t read(uint8_t* out, int64_t size, std::string& error) = 0;

    virtual void close() = 0;

    /**
     * Makes an open or read in progress return soon, failing. Optional: without it, a seek waits
     * for the read in progress to return, and a closed reader lets it finish in the background.
     */
    virtual void cancel() {}
};

} // namespace avsource
