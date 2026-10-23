/**
 * AlphaVideoGPU.h
 * Transparent animations for iOS from ASTC KTX2 texture arrays, drawn with Metal. The simulator
 * decodes the ASTC in software.
 */

#import <Metal/Metal.h>
#import <MetalKit/MetalKit.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * onError's code for a data source without the range-source add-on, which reads them (the
 * RangeSource SwiftPM product, the alpha-video-gpu pod's Source subspec, or for React Native
 * react-native-range-source). Without it a URL is downloaded whole for each play, and storage,
 * maxStorageBytes and cacheKey are ignored.
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
 * What to play, or nil: https:// or http:// (with range-source read by range as it plays, and
 * kept as storage says; without it downloaded whole), file:// or an absolute path, asset://path
 * inside the app bundle, or with dataSource any URL that data source reads.
 */
@property (nonatomic, copy, nullable) NSString *source;
/**
 * What the store keeps the source under, instead of its URL, for URLs that change while the file
 * doesn't (presigned URLs). A source with the same cacheKey as the one playing keeps playing.
 */
@property (nonatomic, copy, nullable) NSString *cacheKey;
/** Sent with every request for an http(s) source, and given to a data source. */
@property (nonatomic, copy, nullable) NSDictionary<NSString *, NSString *> *headers;
/**
 * Reads the source through the data source registered under this name with range-source's
 * RangeDataSources. Needs the range-source add-on.
 */
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
@property (nonatomic, assign) NSInteger streamingPrefetchAhead; // Frames decoded ahead of the shown one, all streaming keeps: the GPU ring (vram) or RAM (ram). 1 to 100, default 8
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
