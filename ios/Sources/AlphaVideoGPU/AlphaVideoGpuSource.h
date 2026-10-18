/**
 * AlphaVideoGpuSource.h
 * The library's range reader for URLs and data sources; not part of the public API.
 */

#import "AlphaVideoGPU.h"

NS_ASSUME_NONNULL_BEGIN

/** What tryReadAt:into:length: returns while the bytes are on their way. */
static const int64_t AVGSourceReaderPending = -2;

/**
 * A range reader over a URL or a data source: blocks in memory and in an optional store, fetched
 * on the reader's own thread. Thread-safe.
 */
@interface AVGSourceReader : NSObject

/**
 * Opens the source at 0 to learn its size and version, unless the store holds it whole. Blocks
 * on the network, so not on the main queue.
 *
 * @param dataSource nil for the built-in URLSession fetcher (http and https only)
 * @param storage "disk" (Caches/alpha-video-gpu), "memory" or "none"
 * @param key what the store keeps the file under
 */
+ (nullable instancetype)openURL:(NSURL *)url
                      dataSource:(nullable id<AlphaVideoGpuDataSource>)dataSource
                         headers:(NSDictionary<NSString *, NSString *> *)headers
                         storage:(NSString *)storage
                             key:(NSString *)key
                 maxStorageBytes:(int64_t)maxStorageBytes
                           error:(NSString *_Nullable *_Nullable)error;

/** The file's size in bytes, or -1 while it isn't known. */
@property (nonatomic, readonly) int64_t size;
/** Why the last read failed. */
@property (nonatomic, readonly) NSString *lastError;

/** Reads exactly `length` bytes at `position`, waiting for them. Nil with `error` set if it can't, or once closed. */
- (nullable NSData *)readAt:(int64_t)position length:(int64_t)length error:(NSString *_Nullable *_Nullable)error;
/** The whole file, waiting for it. */
- (nullable NSData *)readAll:(NSString *_Nullable *_Nullable)error;
/**
 * Copies up to `length` bytes at `position` without waiting: how many, 0 at the end of the file,
 * AVGSourceReaderPending while they're on their way (they're asked for), or -1 once if fetching
 * them failed (lastError says why).
 */
- (int64_t)tryReadAt:(int64_t)position into:(uint8_t *)buffer length:(size_t)length;
/** Stops the reader: reads waiting on it, and later ones, fail. */
- (void)close;

@end

NS_ASSUME_NONNULL_END
