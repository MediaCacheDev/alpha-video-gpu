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
 * Plays a KTX2 animation with alpha. Set the properties, then `source`; loading starts at once.
 *
 * It is an MTKView that draws on demand, so leave the inherited `paused`, `delegate` and
 * `enableSetNeedsDisplay` alone; use `playbackPaused` to pause. Use it from the main thread;
 * the blocks are called there too.
 */
@interface AlphaVideoGpuView : MTKView

/**
 * What to play, or nil: https:// or http:// (downloaded once into Caches, see cacheKey), file://
 * or an absolute path, or asset://path inside the app bundle.
 */
@property (nonatomic, copy, nullable) NSString *source;
/**
 * The file name for a download inside Caches, for URLs that change while the file doesn't
 * (presigned URLs). A source with the same cacheKey as the one playing keeps playing. Set it
 * before source. Default: a hash of the URL, under Caches/alpha-video-gpu.
 */
@property (nonatomic, copy, nullable) NSString *cacheKey;

@property (nonatomic, assign) BOOL autoPlay;           // Default YES
@property (nonatomic, assign) BOOL loop;               // Default YES
@property (nonatomic, assign) CGFloat playbackSpeed;   // Default 1.0
/** Pauses or resumes playback, keeping the loaded frames (for screen focus and blur). */
@property (nonatomic, assign) BOOL playbackPaused;
/** Calls onFrameUpdate for every frame shown. Default NO. */
@property (nonatomic, assign) BOOL enableFrameEvents;

/** Streams texture-array files through a ring of GPU slots instead of loading every frame. */
@property (nonatomic, assign) BOOL stream;
@property (nonatomic, assign) NSInteger streamingRingSize;      // RAM-mode streaming: GPU ring slots. Unused in VRAM mode. Default 16
@property (nonatomic, assign) NSInteger streamingCacheSize;     // Frames kept decoded: in RAM (RAM mode) or as the GPU ring (VRAM mode). Default 60
@property (nonatomic, assign) NSInteger streamingPrefetchAhead; // Frames decoded ahead. Default 30
/** "auto" (the default), "vram" (every frame on the GPU) or "ram" (decoded frames in RAM). */
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
@property (nonatomic, copy, nullable) void (^onError)(NSString *error);

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
