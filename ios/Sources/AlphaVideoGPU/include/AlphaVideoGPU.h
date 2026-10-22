/**
 * AlphaVideoGPU.h
 * Transparent animations for iOS from ASTC KTX2 texture arrays, drawn with Metal. The simulator
 * decodes the ASTC in software.
 */

#import <Metal/Metal.h>
#import <MetalKit/MetalKit.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/** What a data source knows about a file, given at the start of each read. */
@interface AlphaVideoGpuDataInfo : NSObject

/** The whole file's size in bytes (not what's left after the offset), or -1 if not known. */
@property (nonatomic, readonly) int64_t size;
/** An ETag, or any string that changes when the content does. */
@property (nonatomic, copy, readonly, nullable) NSString *etag;
/** Last-Modified, for sources without an ETag. */
@property (nonatomic, copy, readonly, nullable) NSString *lastModified;

- (instancetype)initWithSize:(int64_t)size
                        etag:(nullable NSString *)etag
                lastModified:(nullable NSString *)lastModified NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

@end

/** A read in progress. */
@protocol AlphaVideoGpuCancellable <NSObject>
/** Stops the read; the source may still call its handlers, which are then ignored. */
- (void)cancel;
@end

/**
 * Where an animation's bytes come from, for an app that fetches, caches or decrypts them itself:
 * the role of AVAssetResourceLoaderDelegate, without the AVFoundation types. The player asks for
 * byte ranges as it plays, a window of a few megabytes at a time, and a new one at a seek.
 *
 * Shared by every player it's registered for, so a read must not depend on the one before.
 */
@protocol AlphaVideoGpuDataSource <NSObject>

/**
 * Reads `length` bytes of `url` from `offset`, fewer at the end of the file. Call `response`
 * once, then `data` with the bytes in order (any number of times), then `completion` once: nil
 * when done, an error otherwise. A read at or past the end calls `response`, then `completion`
 * with nil. The handlers may be called on any thread, and soon after this returns or much later.
 *
 * @param headers the source's `headers`, for sources that make HTTP requests; may be empty
 */
- (id<AlphaVideoGpuCancellable>)readURL:(NSURL *)url
                                 offset:(int64_t)offset
                                 length:(int64_t)length
                                headers:(NSDictionary<NSString *, NSString *> *)headers
                               response:(void (^)(AlphaVideoGpuDataInfo *info))response
                                   data:(void (^)(NSData *data))data
                             completion:(void (^)(NSError *_Nullable error))completion
    NS_SWIFT_NAME(read(url:offset:length:headers:response:data:completion:));

@end

/**
 * Data sources by name, for React Native: register one here, then pass
 * `source={{ uri, dataSource: 'name' }}` to AlphaVideoGpuView. Thread-safe.
 */
@interface AlphaVideoGpuDataSources : NSObject

/** Registers `dataSource` under `name`, replacing any registered before. */
+ (void)registerDataSource:(id<AlphaVideoGpuDataSource>)dataSource name:(NSString *)name
    NS_SWIFT_NAME(register(_:name:));
+ (void)unregisterName:(NSString *)name NS_SWIFT_NAME(unregister(name:));
+ (nullable id<AlphaVideoGpuDataSource>)dataSourceNamed:(NSString *)name NS_SWIFT_NAME(dataSource(named:));

@end

/**
 * onError's code for a data source without the range-source add-on, which reads them. Without it
 * a URL is downloaded whole for each play, and storage, maxStorageBytes and cacheKey are ignored.
 */
extern NSString *const AlphaVideoGpuErrorNoSourceModule;

/**
 * Plays a KTX2 animation with alpha. A new `source` loads on the next main-queue turn, so set the
 * other properties in the same turn and they apply to it.
 *
 * It is an MTKView that draws on demand, so leave the inherited `paused`, `delegate` and
 * `enableSetNeedsDisplay` alone; use `playbackPaused` to pause. Use it from the main thread;
 * the blocks are called there too.
 */
@interface AlphaVideoGpuView : MTKView

/**
 * What to play, or nil: https:// or http:// (read by range as it plays, and kept as storage
 * says), file:// or an absolute path, asset://path inside the app bundle, or with dataSource any
 * URL that data source reads.
 */
@property (nonatomic, copy, nullable) NSString *source;
/**
 * What the store keeps the source under, instead of its URL, for URLs that change while the file
 * doesn't (presigned URLs). A source with the same cacheKey as the one playing keeps playing.
 */
@property (nonatomic, copy, nullable) NSString *cacheKey;
/** Sent with every request for an http(s) source, and given to a data source. */
@property (nonatomic, copy, nullable) NSDictionary<NSString *, NSString *> *headers;
/** Reads the source through the data source registered under this name (AlphaVideoGpuDataSources). */
@property (nonatomic, copy, nullable) NSString *dataSource;
/**
 * Where a URL's or data source's bytes are kept between plays: "disk" (Caches/alpha-video-gpu),
 * "memory" (for the app's lifetime) or "none". "auto" (the default) is disk for URLs and none for
 * a data source, whose caching is its own. The store is partial: it keeps what has been played,
 * and a clip held whole plays again offline.
 */
@property (nonatomic, copy, null_resettable) NSString *storage;
/** The store's cap over every clip in it, least recently played out first; 0 or less for 512 MB. */
@property (nonatomic, assign) int64_t maxStorageBytes;

@property (nonatomic, assign) BOOL autoPlay;           // Default YES
@property (nonatomic, assign) BOOL loop;               // Default YES
@property (nonatomic, assign) CGFloat playbackSpeed;   // Default 1.0
/** Pauses or resumes playback, keeping the loaded frames (for screen focus and blur). */
@property (nonatomic, assign) BOOL playbackPaused;
/** Calls onFrameUpdate for every frame shown. Default NO. */
@property (nonatomic, assign) BOOL enableFrameEvents;

/** Streams texture-array files through a ring of GPU slots instead of loading every frame. Default YES */
@property (nonatomic, assign) BOOL stream;
@property (nonatomic, assign) NSInteger streamingRingSize;      // RAM-mode streaming: GPU ring slots. Unused in VRAM mode. Default 16
@property (nonatomic, assign) NSInteger streamingCacheSize;     // Frames kept decoded: in RAM (RAM mode) or as the GPU ring (VRAM mode). Default 60
@property (nonatomic, assign) NSInteger streamingPrefetchAhead; // Frames decoded ahead. Default 30
/** "vram" (the default: frames on the GPU) or "ram" (decoded frames in RAM). Any other value means "vram". */
@property (nonatomic, copy) NSString *memoryStrategy;
/** Logs info traces. Warnings and errors always log. Default NO. */
@property (nonatomic, assign) BOOL verbose;

@property (nonatomic, readonly) NSInteger currentFrame;
@property (nonatomic, readonly) NSInteger totalFrames;
@property (nonatomic, readonly) CGFloat frameRate;
@property (nonatomic, readonly) BOOL isPlaying;
@property (nonatomic, readonly) NSInteger frameWidth;
@property (nonatomic, readonly) NSInteger frameHeight;

/** The animation is loaded: frame size in pixels, frame count and frames per second. */
@property (nonatomic, copy, nullable) void (^onLoad)(NSInteger width, NSInteger height, NSInteger frameCount, CGFloat frameRate);
/** The shown frame changed, after seekToFrame:, and every frame when enableFrameEvents is on. */
@property (nonatomic, copy, nullable) void (^onFrameUpdate)(NSInteger frame, NSInteger totalFrames);
/** "loading", "ready", "playing", "paused" or "stopped". */
@property (nonatomic, copy, nullable) void (^onStateChange)(NSString *state);
/** Playback reached the last frame with loop off. */
@property (nonatomic, copy, nullable) void (^onFinished)(void);
/** A load or playback error; `code` is set for those an app may handle, such as AlphaVideoGpuErrorNoSourceModule. */
@property (nonatomic, copy, nullable) void (^onError)(NSString *error, NSString *_Nullable code);

/** Plays from the shown frame, or from the start once a clip that doesn't loop has finished. */
- (void)play;
- (void)pause;
/** Pauses on the first frame. */
- (void)stop;
/**
 * Shows that frame (clamped to the clip) and keeps playing or stays paused. Ignored until onLoad.
 * With stream, a seek outside the buffered frames decodes the file again.
 */
- (void)seekToFrame:(NSInteger)frame;
/** Seeks to the frame showing at that time, in seconds. */
- (void)seekToTime:(NSTimeInterval)seconds;
/** Stops playback and frees the frames. Removing the view from its superview does the same. */
- (void)cleanup;

@end

NS_ASSUME_NONNULL_END
