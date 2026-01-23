#import <React/RCTBridgeModule.h>
#import <React/RCTViewManager.h>
#import <React/RCTEventDispatcher.h>
#import <Metal/Metal.h>
#import <MetalKit/MetalKit.h>

// View manager is in separate KTX2PlayerViewManager.m file

@interface KTX2PlayerView : MTKView <MTKViewDelegate>

@property (nonatomic, strong) id<MTLDevice> device;
@property (nonatomic, strong) id<MTLCommandQueue> commandQueue;
@property (nonatomic, strong) id<MTLRenderPipelineState> pipelineState;
@property (nonatomic, strong) id<MTLTexture> currentTexture;
@property (nonatomic, strong) NSArray<id<MTLTexture>> *partTextures;
@property (nonatomic, strong) id<MTLBuffer> vertexBuffer;
@property (nonatomic, strong) id<MTLBuffer> uniformBuffer;

// React Native props
@property (nonatomic, copy) NSDictionary *source;
@property (nonatomic, copy) NSArray *parts;  // For multi-part animations: array of source dictionaries
@property (nonatomic, copy) NSString *cacheKey;
@property (nonatomic, assign) BOOL autoPlay;
@property (nonatomic, assign) BOOL enableFrameEvents;
@property (nonatomic, assign) BOOL gpuPlayback;  // GPU-controlled frame timing (experimental)
@property (nonatomic, assign) BOOL reactPaused;  // Pause from React prop (for screen focus/blur)

// Streaming configuration
@property (nonatomic, assign) NSInteger streamingRingSize;      // GPU ring buffer slots (VRAM)
@property (nonatomic, assign) NSInteger streamingCacheSize;     // CPU frame cache size (RAM)
@property (nonatomic, assign) NSInteger streamingPrefetchAhead; // Frames to decode ahead of playback
@property (nonatomic, copy) NSString *memoryStrategy;           // "auto", "vram", "ram"
@property (nonatomic, assign) BOOL stream;                      // Enable streaming mode for texture arrays

// Animation properties
@property (nonatomic, assign) NSInteger currentFrame;
@property (nonatomic, assign) NSInteger totalFrames;
@property (nonatomic, assign) CGFloat frameRate;
@property (nonatomic, assign) BOOL isPlaying;
@property (nonatomic, assign) BOOL loop;
@property (nonatomic, assign) CGFloat playbackSpeed;
@property (nonatomic, assign) CFTimeInterval frameInterval;

// Grid layout properties
@property (nonatomic, assign) NSInteger framesPerRow;
@property (nonatomic, assign) NSInteger framesPerPart;
@property (nonatomic, assign) NSInteger partCount;
@property (nonatomic, assign) NSInteger frameWidth;
@property (nonatomic, assign) NSInteger frameHeight;

// Animation timer
@property (nonatomic, strong) CADisplayLink *displayLink;
@property (nonatomic, assign) CFTimeInterval lastFrameTime;

// Event callbacks
@property (nonatomic, copy) RCTDirectEventBlock onLoad;
@property (nonatomic, copy) RCTDirectEventBlock onFrameUpdate;
@property (nonatomic, copy) RCTDirectEventBlock onStateChange;
@property (nonatomic, copy) RCTDirectEventBlock onFinished;
@property (nonatomic, copy) RCTDirectEventBlock onError;

- (void)loadKTX2WithSource:(NSDictionary *)source;
- (void)play;
- (void)pause;
- (void)stop;
- (void)seekToFrame:(NSInteger)frame;
- (void)setPlaybackSpeed:(CGFloat)speed;
- (void)cleanup;

@end
