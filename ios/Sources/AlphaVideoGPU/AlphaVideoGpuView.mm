#import "AlphaVideoGPU.h"
#import <TargetConditionals.h>
#import <sys/mman.h>
#import <sys/stat.h>
#import <fcntl.h>
#import <unistd.h>
#import <CommonCrypto/CommonDigest.h>

// Third-party sources compiled into this file, with their own warnings left alone
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Weverything"

// Basis Universal transcoder - must define this before including
#define BASISU_NO_ITERATOR_DEBUG_LEVEL

// Include the single-file transcoder implementation
#include "transcoder/basisu_transcoder.cpp"

// Zstandard decompression from basis_universal
#define ZSTD_STATIC_LINKING_ONLY
#include "zstd/zstd.h"
#include "zstd/zstd.c"

// Proper ASTC decoder for simulator fallback
#include "astc_decomp.cpp"

#pragma clang diagnostic pop

#define AVG_VLOG(fmt, ...) do { if (self.verbose) NSLog(@"AlphaVideoGpuView: " fmt, ##__VA_ARGS__); } while (0)

#pragma mark - Source resolution

static NSString *AVGSHA256Hex(NSString *text) {
  NSData *data = [text dataUsingEncoding:NSUTF8StringEncoding];
  unsigned char digest[CC_SHA256_DIGEST_LENGTH];
  CC_SHA256(data.bytes, (CC_LONG)data.length, digest);
  NSMutableString *hex = [NSMutableString stringWithCapacity:CC_SHA256_DIGEST_LENGTH * 2];
  for (int i = 0; i < CC_SHA256_DIGEST_LENGTH; i++) {
    [hex appendFormat:@"%02x", digest[i]];
  }
  return hex;
}

static BOOL AVGFileHasData(NSString *path) {
  NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil];
  return attributes != nil && attributes.fileSize > 0;
}

static BOOL AVGIsRemote(NSString *uri) {
  return [uri hasPrefix:@"https://"] || [uri hasPrefix:@"http://"];
}

/** The URI of fileName in the same folder as uri (for multi-part files), or nil when it has no folder. */
static NSString *AVGSiblingURI(NSString *uri, NSString *fileName) {
  if (AVGIsRemote(uri)) {
    return [NSURL URLWithString:fileName relativeToURL:[NSURL URLWithString:uri]].absoluteString;
  }
  if ([uri hasPrefix:@"asset://"] || [uri hasPrefix:@"file://"] || [uri hasPrefix:@"/"]) {
    NSRange slash = [uri rangeOfString:@"/" options:NSBackwardsSearch];
    return [[uri substringToIndex:slash.location + 1] stringByAppendingString:fileName];
  }
  return nil;
}

/** Downloads urlString into Caches unless it's already there. Blocks; call off the main thread. */
static NSString *AVGDownload(NSString *urlString, NSString *cacheKey, NSString **error) {
  NSURL *url = [NSURL URLWithString:urlString];
  if (!url) {
    *error = [NSString stringWithFormat:@"Invalid URL: %@", urlString];
    return nil;
  }

  NSString *cacheDir = [NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES).firstObject
                        stringByStandardizingPath];
  NSString *cachePath;
  if (cacheKey.length > 0) {
    cachePath = [[cacheDir stringByAppendingPathComponent:cacheKey] stringByStandardizingPath];
    if (![cachePath hasPrefix:[cacheDir stringByAppendingString:@"/"]]) {
      *error = [NSString stringWithFormat:@"cacheKey must stay inside the cache directory: %@", cacheKey];
      return nil;
    }
  } else {
    cachePath = [[cacheDir stringByAppendingPathComponent:@"alpha-video-gpu"]
                 stringByAppendingPathComponent:AVGSHA256Hex(urlString)];
  }
  if (AVGFileHasData(cachePath)) {
    return cachePath;
  }

  __block NSString *failure = nil;
  dispatch_semaphore_t done = dispatch_semaphore_create(0);
  NSURLSessionDownloadTask *task = [[NSURLSession sharedSession] downloadTaskWithURL:url
      completionHandler:^(NSURL *location, NSURLResponse *response, NSError *downloadError) {
    NSInteger status = [response isKindOfClass:[NSHTTPURLResponse class]]
        ? ((NSHTTPURLResponse *)response).statusCode : 200;
    if (downloadError || !location) {
      failure = [NSString stringWithFormat:@"Download failed: %@", downloadError.localizedDescription ?: @"unknown error"];
    } else if (status != 200) {
      failure = [NSString stringWithFormat:@"Download failed: HTTP %ld for %@", (long)status, urlString];
    } else {
      // The temporary file is deleted when this handler returns, so move it now. Another view
      // may have finished the same download first, in which case its file is used.
      NSFileManager *fileManager = [NSFileManager defaultManager];
      [fileManager createDirectoryAtPath:[cachePath stringByDeletingLastPathComponent]
             withIntermediateDirectories:YES attributes:nil error:nil];
      NSError *moveError = nil;
      if (![fileManager moveItemAtPath:location.path toPath:cachePath error:&moveError] && !AVGFileHasData(cachePath)) {
        failure = [NSString stringWithFormat:@"Cannot write cache file: %@", moveError.localizedDescription];
      }
    }
    dispatch_semaphore_signal(done);
  }];
  [task resume];
  dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);

  if (failure) {
    *error = failure;
    return nil;
  }
  return cachePath;
}

/**
 * Turns a source URI into a local file path. https:// and http:// are downloaded into Caches
 * (named by cacheKey, or a hash of the URL), asset:// maps into the app bundle, and file:// and
 * absolute paths pass through. Blocks; call off the main thread.
 */
static NSString *AVGResolveSource(NSString *uri, NSString *cacheKey, NSString **error) {
  if (AVGIsRemote(uri)) {
    return AVGDownload(uri, cacheKey, error);
  }

  NSString *path = nil;
  if ([uri hasPrefix:@"asset://"]) {
    NSString *relative = [uri substringFromIndex:@"asset://".length];
    relative = [relative stringByRemovingPercentEncoding] ?: relative;
    while ([relative hasPrefix:@"/"]) {
      relative = [relative substringFromIndex:1];
    }
    if (relative.length > 0) {
      path = [[NSBundle mainBundle].resourcePath stringByAppendingPathComponent:relative];
    }
  } else if ([uri hasPrefix:@"file://"]) {
    NSURL *url = [NSURL URLWithString:uri];
    path = url.isFileURL ? url.path : [uri substringFromIndex:@"file://".length];
  } else if ([uri hasPrefix:@"/"]) {
    path = uri;
  }

  if (!path) {
    *error = [NSString stringWithFormat:@"Unsupported source '%@'. Use https://, file://, an absolute path or asset://", uri];
    return nil;
  }
  if (![[NSFileManager defaultManager] fileExistsAtPath:path]) {
    *error = [NSString stringWithFormat:@"File not found: %@", uri];
    return nil;
  }
  return path;
}

static BOOL AVGFileStartsWithZstdFrame(NSString *path) {
  NSFileHandle *handle = [NSFileHandle fileHandleForReadingAtPath:path];
  NSData *head = [handle readDataOfLength:4];
  [handle closeFile];
  const uint8_t *b = (const uint8_t *)head.bytes;
  return head.length == 4 && b[0] == 0x28 && b[1] == 0xB5 && b[2] == 0x2F && b[3] == 0xFD;
}

@interface AlphaVideoGpuView () <MTKViewDelegate>

@property (nonatomic, strong) id<MTLCommandQueue> commandQueue;
@property (nonatomic, strong) id<MTLRenderPipelineState> pipelineState;
@property (nonatomic, strong) id<MTLTexture> currentTexture;
@property (nonatomic, strong) NSArray<id<MTLTexture>> *partTextures;
@property (nonatomic, strong) id<MTLBuffer> vertexBuffer;
@property (nonatomic, strong) id<MTLBuffer> uniformBuffer;

@property (nonatomic, assign) NSInteger currentFrame;
@property (nonatomic, assign) NSInteger totalFrames;
@property (nonatomic, assign) CGFloat frameRate;
@property (nonatomic, assign) BOOL isPlaying;
@property (nonatomic, assign) CFTimeInterval frameInterval;

// Grid layout
@property (nonatomic, assign) NSInteger framesPerRow;
@property (nonatomic, assign) NSInteger framesPerPart;
@property (nonatomic, assign) NSInteger partCount;
@property (nonatomic, assign) NSInteger frameWidth;
@property (nonatomic, assign) NSInteger frameHeight;

@property (nonatomic, strong) CADisplayLink *displayLink;
@property (nonatomic, assign) CFTimeInterval lastFrameTime;

@end

@implementation AlphaVideoGpuView {
  basist::transcoder_texture_format _targetFormat;
  MTLPixelFormat _metalPixelFormat;
  BOOL _useCompressedFormat;
  std::vector<std::vector<uint8_t>> _transcodedData;
  id<MTLSamplerState> _samplerState;
  BOOL _basisInitialized;
  NSString *_sourceUri;
  // The local file the current source resolved to (a download, a bundle file or a path)
  NSString *_localPath;
  // Bumped for every new source, so a slow download for an old one is dropped
  NSUInteger _sourceGeneration;
  // The cache key the loaded/loading source belongs to. Compared against self.cacheKey
  // to tell a re-signed URL for the same object apart from a new asset.
  NSString *_loadedCacheKey;
  NSArray *_partsMetadata;
  NSArray<NSNumber *> *_partWidths;
  NSArray<NSNumber *> *_partHeights;
  BOOL _isSourceLoaded;
  BOOL _isSourceLoading;
  BOOL _isDestroyed;
  
  BOOL _isNativeASTC;
  NSDictionary *_cachedMetadata;
  
  // GLB container support
  BOOL _isGLBContainer;
  NSArray<NSData *> *_glbKtx2Parts;
  
  // Texture array support (for packingMode: 'array')
  BOOL _useTextureArray;
  id<MTLTexture> _textureArray;
  NSInteger _textureArrayLayerCount;
  id<MTLRenderPipelineState> _arrayPipelineState;
  id<MTLBuffer> _arrayUniformBuffer;
  
  // Streaming ring buffer support (for efficient memory usage)
  BOOL _streamingRingBufferMode;
  NSInteger _streamingRingBufferSize;
  NSMutableArray<NSNumber *> *_ringSlots;  // Tracks which virtual frame is in each GPU slot
  NSMutableDictionary<NSNumber *, NSData *> *_frameCache;  // CPU frame cache
  NSInteger _decoderPosition;              // Current decoder frame position
  NSInteger _decoderLoopIteration;         // Which loop iteration the decoder is on
  NSInteger _playbackLoopIteration;        // Which loop iteration playback is on
  BOOL _useVramStrategy;                   // True for VRAM-heavy mode

  // The shown frame changed (a load or a seek) and is drawn once decoded, playing or not
  BOOL _frameOwed;
  // A clip that doesn't loop has reached its last frame; playing again starts it over
  BOOL _ended;
  
  // Streaming decompression state
  ZSTD_DStream *_dstream;
  NSData *_compressedData;
  size_t _compressedDataPos;
  NSMutableData *_frameAccumulator;
  uint32_t _streamBytesPerLayer;
  uint64_t _streamLevel0Offset;
  BOOL _streamHeaderSkipped;
  
  // Uncompressed streaming (mmap)
  int _mmapFd;
  void *_mmapData;
  size_t _mmapSize;
  BOOL _isUncompressedStreaming;
  
  // Decoded RGBA cache for simulator ASTC decompression (background thread)
  NSMutableDictionary<NSNumber *, NSData *> *_decodedRgbaCache;
  NSInteger _uncompressedDecoderPosition;
  dispatch_queue_t _astcDecodeQueue;
  
  // ASTC block dimensions (derived from vkFormat, default 4x4)
  uint32_t _astcBlockWidth;
  uint32_t _astcBlockHeight;
  
  // Staging buffers for async blit uploads (double-buffered)
  id<MTLBuffer> _stagingBuffers[2];
  NSInteger _currentStagingBuffer;
  id<MTLCommandQueue> _blitCommandQueue;
}

- (instancetype)initWithFrame:(CGRect)frame
{
  AVG_VLOG(@"initWithFrame %@", NSStringFromCGRect(frame));
  
  self = [super initWithFrame:frame];
  if (self) {
    self.device = MTLCreateSystemDefaultDevice();
    AVG_VLOG(@"Metal device=%@", self.device.name);
    self.commandQueue = [self.device newCommandQueue];
    self.delegate = self;
    
    // Configure MTKView to render on demand only (we control timing via CADisplayLink)
    self.paused = YES;
    self.enableSetNeedsDisplay = YES;
    
    // CRITICAL: Set up transparency
    self.opaque = NO;
    self.backgroundColor = [UIColor clearColor];
    self.layer.opaque = NO;
    self.clearColor = MTLClearColorMake(0, 0, 0, 0);
    
    // Initialize with default values
    self.currentFrame = 0;
    self.totalFrames = 0;
    self.frameRate = 30.0;
    self.isPlaying = NO;
    self.loop = YES;
    self.playbackSpeed = 1.0;
    self.frameInterval = 1.0 / self.frameRate;
    self.enableFrameEvents = NO;  // Disabled by default for performance
    _isSourceLoaded = NO;
    _isSourceLoading = NO;
    _isDestroyed = NO;
    
    // Streaming configuration defaults (match Android)
    self.streamingRingSize = 16;       // GPU ring buffer slots
    self.streamingCacheSize = 60;      // CPU frame cache size
    self.streamingPrefetchAhead = 30;  // Frames to decode ahead
    self.memoryStrategy = @"auto";     // auto, vram, ram
    
    // Initialize streaming state
    _streamingRingBufferMode = NO;
    _streamingRingBufferSize = 0;
    _ringSlots = nil;
    _frameCache = nil;
    _decoderPosition = 0;
    _decoderLoopIteration = 0;
    _playbackLoopIteration = 0;
    _useVramStrategy = NO;
    _dstream = NULL;
    _compressedData = nil;
    _compressedDataPos = 0;
    _frameAccumulator = nil;
    _streamBytesPerLayer = 0;
    _streamLevel0Offset = 0;
    _streamHeaderSkipped = NO;
    _mmapFd = -1;
    _mmapData = NULL;
    _mmapSize = 0;
    _isUncompressedStreaming = NO;
    _decodedRgbaCache = nil;
    _uncompressedDecoderPosition = 0;
    _astcDecodeQueue = dispatch_queue_create("dev.mediacache.alphavideo.gpu.astcdecode", DISPATCH_QUEUE_SERIAL);
    _astcBlockWidth = 4;
    _astcBlockHeight = 4;
    
    // Initialize Basis transcoder (only once)
    if (!_basisInitialized) {
      basist::basisu_transcoder_init();
      _basisInitialized = YES;
    }
    
    // Detect and cache optimal texture format
    [self detectAndCacheTextureFormat];
    
    // Default values
    self.autoPlay = YES;
    
    [self setupMetal];
    [self setupVertexData];
  }
  return self;
}

- (void)detectAndCacheTextureFormat
{
  // Check if running on simulator
#if TARGET_OS_SIMULATOR
  AVG_VLOG(@"Running on simulator - using RGBA32 fallback");
  _targetFormat = basist::transcoder_texture_format::cTFRGBA32;
  _metalPixelFormat = MTLPixelFormatRGBA8Unorm;
  _useCompressedFormat = NO;
#else
  // Check for ASTC support (all real iOS devices with Metal support ASTC)
  if (@available(iOS 8.0, *)) {
    // Check device capabilities - ASTC is supported on A8 and later
    if ([self.device supportsFamily:MTLGPUFamilyApple2]) {
      AVG_VLOG(@"Using ASTC 4x4 format");
      _targetFormat = basist::transcoder_texture_format::cTFASTC_4x4_RGBA;
      _metalPixelFormat = MTLPixelFormatASTC_4x4_sRGB;
      _useCompressedFormat = YES;
      return;
    }
  }
  
  // Fallback to RGBA32 for older devices
  AVG_VLOG(@"Using RGBA32 fallback format");
  _targetFormat = basist::transcoder_texture_format::cTFRGBA32;
  _metalPixelFormat = MTLPixelFormatRGBA8Unorm;
  _useCompressedFormat = NO;
#endif
}

/**
 * Map a KTX2 vkFormat to the corresponding MTLPixelFormat and ASTC block dimensions.
 * Supports all 14 ASTC block sizes (UNORM/SRGB pairs, VkFormat 157–184).
 * Updates _metalPixelFormat, _astcBlockWidth, _astcBlockHeight.
 * Returns YES if the vkFormat is a recognized ASTC format.
 */
- (BOOL)configureASTCFormatFromVkFormat:(uint32_t)vkFormat
{
  // All ASTC VkFormats come in UNORM/SRGB pairs.
  // We use the sRGB MTLPixelFormat for both (matches visual output).
  struct ASTCFormatInfo {
    uint32_t vkFormatUnorm;
    uint32_t vkFormatSrgb;
    MTLPixelFormat pixelFormat;
    uint32_t blockWidth;
    uint32_t blockHeight;
  };
  
  static const ASTCFormatInfo formats[] = {
    { 157, 158, MTLPixelFormatASTC_4x4_sRGB,   4,  4 },
    { 159, 160, MTLPixelFormatASTC_5x4_sRGB,   5,  4 },
    { 161, 162, MTLPixelFormatASTC_5x5_sRGB,   5,  5 },
    { 163, 164, MTLPixelFormatASTC_6x5_sRGB,   6,  5 },
    { 165, 166, MTLPixelFormatASTC_6x6_sRGB,   6,  6 },
    { 167, 168, MTLPixelFormatASTC_8x5_sRGB,   8,  5 },
    { 169, 170, MTLPixelFormatASTC_8x6_sRGB,   8,  6 },
    { 171, 172, MTLPixelFormatASTC_8x8_sRGB,   8,  8 },
    { 173, 174, MTLPixelFormatASTC_10x5_sRGB,  10, 5 },
    { 175, 176, MTLPixelFormatASTC_10x6_sRGB,  10, 6 },
    { 177, 178, MTLPixelFormatASTC_10x8_sRGB,  10, 8 },
    { 179, 180, MTLPixelFormatASTC_10x10_sRGB, 10, 10 },
    { 181, 182, MTLPixelFormatASTC_12x10_sRGB, 12, 10 },
    { 183, 184, MTLPixelFormatASTC_12x12_sRGB, 12, 12 },
  };
  
  for (const auto &fmt : formats) {
    if (vkFormat == fmt.vkFormatUnorm || vkFormat == fmt.vkFormatSrgb) {
      // Always set block dimensions (needed for software decoding on simulator)
      _astcBlockWidth = fmt.blockWidth;
      _astcBlockHeight = fmt.blockHeight;
      
      // Only update pixel format if using compressed format (real device)
      // On simulator, _useCompressedFormat is NO and _metalPixelFormat is RGBA8Unorm
#if !TARGET_OS_SIMULATOR
      _metalPixelFormat = fmt.pixelFormat;
#endif
      AVG_VLOG(@"ASTC format: %ux%u (VkFormat=%u, MTLPixelFormat=%lu, useCompressed=%d)",
            fmt.blockWidth, fmt.blockHeight, vkFormat, (unsigned long)_metalPixelFormat, _useCompressedFormat);
      return YES;
    }
  }
  
  AVG_VLOG(@"Unknown ASTC VkFormat: %u, falling back to 4x4", vkFormat);
#if !TARGET_OS_SIMULATOR
  _metalPixelFormat = MTLPixelFormatASTC_4x4_sRGB;
#endif
  _astcBlockWidth = 4;
  _astcBlockHeight = 4;
  return NO;
}

/**
 * Calculate bytes per layer for the current ASTC block size.
 * All ASTC blocks are 16 bytes (128 bits) regardless of block dimensions.
 */
- (uint32_t)astcBytesPerLayerForWidth:(uint32_t)width height:(uint32_t)height
{
  uint32_t blocksX = (width + _astcBlockWidth - 1) / _astcBlockWidth;
  uint32_t blocksY = (height + _astcBlockHeight - 1) / _astcBlockHeight;
  return blocksX * blocksY * 16;
}

- (void)setSource:(NSString *)source
{
  AVG_VLOG(@"setSource %@", source);
  NSString *uri = source.length > 0 ? source : nil;

  if (!uri) {
    _source = nil;
    _sourceUri = nil;
    _localPath = nil;
    _sourceGeneration++;
    [self cleanup];
    return;
  }
  
  // Check if source changed
  if ([_sourceUri isEqualToString:uri] && (_isSourceLoaded || _isSourceLoading)) {
    AVG_VLOG(@"Source unchanged and already loaded/loading, skipping");
    return;
  }
  
  // A presigned URL is re-signed every time the app lists its media, so the URL changes
  // while still addressing the same object. When the cache key matches what is already
  // on screen this is a credential refresh, not a new asset, and reloading would
  // interrupt a loop that is supposed to run until unmount.
  if (self.cacheKey.length > 0 && [self.cacheKey isEqualToString:_loadedCacheKey]
      && (_isSourceLoaded || _isSourceLoading)) {
    AVG_VLOG(@"Same cacheKey (%@), ignoring URL change", self.cacheKey);
    _source = [uri copy];
    _sourceUri = [uri copy];
    return;
  }
  
  _source = [uri copy];
  _sourceUri = [uri copy];
  _loadedCacheKey = [self.cacheKey copy];
  _isSourceLoaded = NO;
  _isSourceLoading = YES;
  _isDestroyed = NO;
  self.currentFrame = 0;
  _ended = NO;
  _frameOwed = NO;
  
  _localPath = nil;
  [self notifyStateChange:@"loading"];
  [self resolveAndLoadSource];
}

/** Resolves _sourceUri to a local file off the main thread, then loads it. */
- (void)resolveAndLoadSource
{
  NSUInteger generation = ++_sourceGeneration;
  NSString *uri = _sourceUri;
  NSString *cacheKey = self.cacheKey;
  AVG_VLOG(@"Resolving source %@", uri);

  dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
    NSString *error = nil;
    NSString *path = AVGResolveSource(uri, cacheKey, &error);
    dispatch_async(dispatch_get_main_queue(), ^{
      if (generation != self->_sourceGeneration) return;
      if (self->_isDestroyed) {
        self->_isSourceLoading = NO;
        return;
      }
      if (!path) {
        [self notifyError:error];
        return;
      }
      self->_localPath = path;
      [self loadKTX2FromFile:path];
    });
  });
}

- (void)notifyStateChange:(NSString *)state
{
  if (self.onStateChange) self.onStateChange(state);
}

- (void)notifyLoad
{
  // Every load path reports here, once it can draw; show the first frame even before playing
  _frameOwed = YES;
  [self updateDisplayLink];
  if (self.onLoad) self.onLoad(self.frameWidth, self.frameHeight, self.totalFrames, self.frameRate);
}

- (void)notifyFrameUpdate
{
  if (self.onFrameUpdate) self.onFrameUpdate(self.currentFrame, self.totalFrames);
}

- (void)notifyFinished
{
  if (self.onFinished) self.onFinished();
}

- (void)notifyError:(NSString *)error
{
  _isSourceLoading = NO;
  NSLog(@"AlphaVideoGpuView: Error: %@", error);
  if (self.onError) self.onError(error ?: @"Unknown error");
}

- (void)setupMetal
{
  // Create vertex shader
  NSString *vertexShaderSource = @"#include <metal_stdlib>\n"
                                  "using namespace metal;\n"
                                  "struct VertexOut {\n"
                                  "  float4 position [[position]];\n"
                                  "  float2 texCoord;\n"
                                  "};\n"
                                  "vertex VertexOut vertex_main(uint vertexID [[vertex_id]]) {\n"
                                  "  VertexOut out;\n"
                                  "  const float2 positions[6] = {\n"
                                  "    float2(-1.0, -1.0), float2(1.0, -1.0), float2(-1.0, 1.0),\n"
                                  "    float2(-1.0, 1.0), float2(1.0, -1.0), float2(1.0, 1.0)\n"
                                  "  };\n"
                                  "  const float2 texCoords[6] = {\n"
                                  "    float2(0.0, 1.0), float2(1.0, 1.0), float2(0.0, 0.0),\n"
                                  "    float2(0.0, 0.0), float2(1.0, 1.0), float2(1.0, 0.0)\n"
                                  "  };\n"
                                  "  out.position = float4(positions[vertexID], 0.0, 1.0);\n"
                                  "  out.texCoord = texCoords[vertexID];\n"
                                  "  return out;\n"
                                  "}";
  
  // Create fragment shader (must include VertexOut struct since shaders compile separately)
  NSString *fragmentShaderSource = @"#include <metal_stdlib>\n"
                                    "using namespace metal;\n"
                                    "struct VertexOut {\n"
                                    "  float4 position [[position]];\n"
                                    "  float2 texCoord;\n"
                                    "};\n"
                                    "struct Uniforms {\n"
                                    "  float4 frameUV;\n"
                                    "};\n"
                                    "fragment float4 fragment_main(VertexOut in [[stage_in]],\n"
                                    "                             texture2d<float> tex [[texture(0)]],\n"
                                    "                             sampler s [[sampler(0)]],\n"
                                    "                             constant Uniforms &uniforms [[buffer(0)]]) {\n"
                                    "  float2 uv = mix(uniforms.frameUV.xy, uniforms.frameUV.zw, in.texCoord);\n"
                                    "  return tex.sample(s, uv);\n"
                                    "}";
  
  AVG_VLOG(@"Compiling Metal shaders...");
  
  NSError *error = nil;
  id<MTLLibrary> vertexLibrary = [self.device newLibraryWithSource:vertexShaderSource options:nil error:&error];
  if (error) {
    NSLog(@"AlphaVideoGpuView: Vertex shader compilation error: %@", error);
  }
  id<MTLFunction> vertexFunction = [vertexLibrary newFunctionWithName:@"vertex_main"];
  if (!vertexFunction) {
    NSLog(@"AlphaVideoGpuView: Failed to get vertex_main function!");
  }
  
  error = nil;
  id<MTLLibrary> fragmentLibrary = [self.device newLibraryWithSource:fragmentShaderSource options:nil error:&error];
  if (error) {
    NSLog(@"AlphaVideoGpuView: Fragment shader compilation error: %@", error);
  }
  id<MTLFunction> fragmentFunction = [fragmentLibrary newFunctionWithName:@"fragment_main"];
  if (!fragmentFunction) {
    NSLog(@"AlphaVideoGpuView: Failed to get fragment_main function!");
  }
  
  AVG_VLOG(@"Shader compilation complete. vertex=%p, fragment=%p", vertexFunction, fragmentFunction);
  
  // Create pipeline
  MTLRenderPipelineDescriptor *pipelineDescriptor = [[MTLRenderPipelineDescriptor alloc] init];
  pipelineDescriptor.vertexFunction = vertexFunction;
  pipelineDescriptor.fragmentFunction = fragmentFunction;
  pipelineDescriptor.colorAttachments[0].pixelFormat = self.colorPixelFormat;
  // Use premultiplied alpha blending (RGB is pre-multiplied by alpha in encoder)
  pipelineDescriptor.colorAttachments[0].blendingEnabled = YES;
  pipelineDescriptor.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
  pipelineDescriptor.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
  pipelineDescriptor.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorOne;
  pipelineDescriptor.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
  pipelineDescriptor.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
  pipelineDescriptor.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
  
  error = nil;
  self.pipelineState = [self.device newRenderPipelineStateWithDescriptor:pipelineDescriptor error:&error];
  
  if (error) {
    NSLog(@"AlphaVideoGpuView: Failed to create pipeline state: %@", error);
  } else {
    AVG_VLOG(@"Pipeline state created successfully: %p", self.pipelineState);
  }
  
  // Create texture array shader for packingMode: 'array'
  NSString *arrayFragmentShaderSource = @"#include <metal_stdlib>\n"
                                         "using namespace metal;\n"
                                         "struct VertexOut {\n"
                                         "  float4 position [[position]];\n"
                                         "  float2 texCoord;\n"
                                         "};\n"
                                         "struct ArrayUniforms {\n"
                                         "  float layer;\n"
                                         "};\n"
                                         "fragment float4 fragment_array(VertexOut in [[stage_in]],\n"
                                         "                               texture2d_array<float> tex [[texture(0)]],\n"
                                         "                               sampler s [[sampler(0)]],\n"
                                         "                               constant ArrayUniforms &uniforms [[buffer(0)]]) {\n"
                                         "  return tex.sample(s, in.texCoord, uint(uniforms.layer));\n"
                                         "}";
  
  error = nil;
  id<MTLLibrary> arrayFragmentLibrary = [self.device newLibraryWithSource:arrayFragmentShaderSource options:nil error:&error];
  if (error) {
    NSLog(@"AlphaVideoGpuView: Array fragment shader compilation error: %@", error);
  }
  id<MTLFunction> arrayFragmentFunction = [arrayFragmentLibrary newFunctionWithName:@"fragment_array"];
  
  if (arrayFragmentFunction) {
    MTLRenderPipelineDescriptor *arrayPipelineDescriptor = [[MTLRenderPipelineDescriptor alloc] init];
    arrayPipelineDescriptor.vertexFunction = vertexFunction;
    arrayPipelineDescriptor.fragmentFunction = arrayFragmentFunction;
    arrayPipelineDescriptor.colorAttachments[0].pixelFormat = self.colorPixelFormat;
    arrayPipelineDescriptor.colorAttachments[0].blendingEnabled = YES;
    arrayPipelineDescriptor.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
    arrayPipelineDescriptor.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
    arrayPipelineDescriptor.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorOne;
    arrayPipelineDescriptor.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
    arrayPipelineDescriptor.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
    arrayPipelineDescriptor.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
    
    error = nil;
    _arrayPipelineState = [self.device newRenderPipelineStateWithDescriptor:arrayPipelineDescriptor error:&error];
    if (error) {
      NSLog(@"AlphaVideoGpuView: Failed to create array pipeline state: %@", error);
    } else {
      AVG_VLOG(@"Array pipeline state created successfully");
    }
  }
  
  // Create uniform buffer for texture array (just layer index)
  _arrayUniformBuffer = [self.device newBufferWithLength:16 options:MTLResourceStorageModeShared];
}

- (void)setupVertexData
{
  // Create uniform buffer for the frame UV rectangle
  // Uniforms struct: float4 frameUV (16 bytes) + 9 floats (36 bytes) + 1 int (4 bytes) = 56 bytes, aligned to 64
  self.uniformBuffer = [self.device newBufferWithLength:16 options:MTLResourceStorageModeShared];
  
  // Create sampler state
  MTLSamplerDescriptor *samplerDescriptor = [[MTLSamplerDescriptor alloc] init];
  samplerDescriptor.minFilter = MTLSamplerMinMagFilterLinear;
  samplerDescriptor.magFilter = MTLSamplerMinMagFilterLinear;
  samplerDescriptor.sAddressMode = MTLSamplerAddressModeClampToEdge;
  samplerDescriptor.tAddressMode = MTLSamplerAddressModeClampToEdge;
  _samplerState = [self.device newSamplerStateWithDescriptor:samplerDescriptor];
}

/**
 * Extract BasisPlayerMetadata from KTX2 key-value data section.
 * Returns nil if not found.
 */
- (NSDictionary *)extractKTX2Metadata:(NSData *)ktx2Data
{
  if (ktx2Data.length < 80) return nil;
  
  const uint8_t *bytes = (const uint8_t *)[ktx2Data bytes];
  
  // Check KTX2 magic: «KTX 20»
  if (bytes[0] != 0xAB || bytes[1] != 0x4B || bytes[2] != 0x54 || bytes[3] != 0x58) {
    return nil;
  }
  
  // Read KVD offset and length from header (bytes 56-63, little-endian)
  uint32_t kvdByteOffset = *(uint32_t *)(bytes + 56);
  uint32_t kvdByteLength = *(uint32_t *)(bytes + 60);
  
  if (kvdByteLength == 0 || kvdByteOffset + kvdByteLength > ktx2Data.length) {
    return nil;
  }
  
  // Parse key-value entries
  uint32_t pos = kvdByteOffset;
  uint32_t kvdEnd = kvdByteOffset + kvdByteLength;
  
  while (pos < kvdEnd) {
    if (pos + 4 > ktx2Data.length) break;
    
    uint32_t entryLength = *(uint32_t *)(bytes + pos);
    pos += 4;
    
    if (entryLength == 0 || pos + entryLength > ktx2Data.length) break;
    
    // Find key (NUL-terminated string)
    uint32_t keyEnd = pos;
    while (keyEnd < pos + entryLength && bytes[keyEnd] != 0) {
      keyEnd++;
    }
    
    NSString *key = [[NSString alloc] initWithBytes:(bytes + pos) length:(keyEnd - pos) encoding:NSUTF8StringEncoding];
    
    if ([key isEqualToString:@"BasisPlayerMetadata"]) {
      // Value starts after key's NUL terminator
      uint32_t valueStart = keyEnd + 1;
      uint32_t valueEnd = pos + entryLength;
      
      // Find actual value end (may have NUL terminator)
      while (valueEnd > valueStart && bytes[valueEnd - 1] == 0) {
        valueEnd--;
      }
      
      NSData *valueData = [NSData dataWithBytes:(bytes + valueStart) length:(valueEnd - valueStart)];
      NSError *jsonError;
      NSDictionary *metadata = [NSJSONSerialization JSONObjectWithData:valueData options:0 error:&jsonError];
      if (!jsonError) {
        AVG_VLOG(@"Extracted embedded metadata from KTX2");
        return metadata;
      }
    }
    
    // Move to next entry (with padding to 4-byte alignment)
    pos += entryLength;
    pos += (4 - (entryLength % 4)) % 4;
  }
  
  return nil;
}

/**
 * Check if data is a GLB container (magic: "glTF" = 0x46546C67)
 */
- (BOOL)isGLBData:(NSData *)data
{
  if (data.length < 12) return NO;
  const uint8_t *bytes = (const uint8_t *)[data bytes];
  return bytes[0] == 0x67 && bytes[1] == 0x6C && bytes[2] == 0x54 && bytes[3] == 0x46;
}

/**
 * Parse GLB container and extract metadata and KTX2 parts.
 * Returns metadata dictionary, populates _glbKtx2Parts array.
 */
- (NSDictionary *)parseGLBContainer:(NSData *)data
{
  if (![self isGLBData:data]) return nil;
  
  const uint8_t *bytes = (const uint8_t *)[data bytes];
  
  // Read header (little-endian)
  uint32_t version = *(uint32_t *)(bytes + 4);
  
  if (version != 2) {
    NSLog(@"AlphaVideoGpuView: Unsupported GLB version: %u", version);
    return nil;
  }
  
  // Read JSON chunk (starts at byte 12)
  uint32_t offset = 12;
  uint32_t jsonChunkLength = *(uint32_t *)(bytes + offset);
  offset += 4;
  uint32_t jsonChunkType = *(uint32_t *)(bytes + offset);
  offset += 4;
  
  if (jsonChunkType != 0x4E4F534A) { // "JSON"
    AVG_VLOG(@"Expected JSON chunk, got: 0x%X", jsonChunkType);
    return nil;
  }
  
  // Parse JSON metadata
  NSData *jsonData = [NSData dataWithBytes:(bytes + offset) length:jsonChunkLength];
  NSError *jsonError = nil;
  NSDictionary *metadata = [NSJSONSerialization JSONObjectWithData:jsonData options:0 error:&jsonError];
  if (jsonError || !metadata) {
    NSLog(@"AlphaVideoGpuView: Failed to parse GLB JSON: %@", jsonError);
    return nil;
  }
  offset += jsonChunkLength;
  
  // Read BIN chunk: length (not needed), then type
  offset += 4;
  uint32_t binChunkType = *(uint32_t *)(bytes + offset);
  offset += 4;
  
  if (binChunkType != 0x004E4942) { // "BIN\0"
    AVG_VLOG(@"Expected BIN chunk, got: 0x%X", binChunkType);
    return nil;
  }
  
  uint32_t binDataStart = offset;
  
  // Extract KTX2 parts from BIN chunk using ktx2Parts offsets
  NSArray *ktx2PartsInfo = metadata[@"ktx2Parts"];
  if (!ktx2PartsInfo || ktx2PartsInfo.count == 0) {
    AVG_VLOG(@"No ktx2Parts found in GLB metadata");
    return nil;
  }
  
  NSMutableArray<NSData *> *parts = [NSMutableArray array];
  for (NSDictionary *partInfo in ktx2PartsInfo) {
    uint32_t byteOffset = [partInfo[@"byteOffset"] unsignedIntValue];
    uint32_t byteLength = [partInfo[@"byteLength"] unsignedIntValue];
    
    NSData *partData = [NSData dataWithBytes:(bytes + binDataStart + byteOffset) length:byteLength];
    [parts addObject:partData];
  }
  
  _glbKtx2Parts = [parts copy];
  _isGLBContainer = YES;
  
  AVG_VLOG(@"Parsed GLB container: %lu KTX2 parts", (unsigned long)_glbKtx2Parts.count);
  
  return metadata;
}

- (void)loadKTX2FromFile:(NSString *)path
{
  // Reset GLB state
  _isGLBContainer = NO;
  _glbKtx2Parts = nil;
  
  dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH, 0), ^{
    // A whole-file zstd frame (.ktx2.zst) streams; recognised by content, since a cached
    // download is named by its cache key rather than by the URL's extension
    if (AVGFileStartsWithZstdFrame(path)) {
      AVG_VLOG(@"Detected streaming mode (zstd frame): %@", path);
      [self loadStreamingKTX2FromFile:path];
      return;
    }
    
    // With stream, an uncompressed texture array is mmap-streamed rather than loaded into memory
    if ([self checkAndPlayUncompressedStreaming:path]) {
      AVG_VLOG(@"Using uncompressed streaming mode");
      return;
    }
    
    // Fall back to loading entire file
    NSData *fileData = [NSData dataWithContentsOfFile:path options:NSDataReadingMappedIfSafe error:nil];
    if (!fileData) {
      dispatch_async(dispatch_get_main_queue(), ^{
        [self notifyError:@"Failed to load file"];
      });
      return;
    }
    
    NSDictionary *metadata = nil;
    
    // Check if this is a GLB container or KTX2 file
    if ([self isGLBData:fileData]) {
      AVG_VLOG(@"Detected GLB container");
      metadata = [self parseGLBContainer:fileData];
      if (!metadata) {
        dispatch_async(dispatch_get_main_queue(), ^{
          [self notifyError:@"Failed to parse GLB container"];
        });
        return;
      }
    } else {
      // Regular KTX2 file
      metadata = [self extractKTX2Metadata:fileData];
      if (!metadata) {
        dispatch_async(dispatch_get_main_queue(), ^{
          [self notifyError:@"No metadata found in KTX2 file. Ensure the file was encoded with metadata injection."];
        });
        return;
      }
      AVG_VLOG(@"Using embedded KTX2 metadata");
    }
    
    // Continue on main thread with parsed metadata
    dispatch_async(dispatch_get_main_queue(), ^{
      [self applyMetadataAndLoadTextures:metadata sourcePath:path];
    });
  });
}

#pragma mark - Streaming Decompression

- (void)loadStreamingKTX2FromFile:(NSString *)path
{
  dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH, 0), ^{
    // Memory-mapped, so the compressed file isn't copied into RAM
    NSData *compressedData = [NSData dataWithContentsOfFile:path options:NSDataReadingMappedIfSafe error:nil];
    
    if (!compressedData) {
      dispatch_async(dispatch_get_main_queue(), ^{
        [self notifyError:@"Failed to load streaming file"];
      });
      return;
    }
    
    AVG_VLOG(@"Streaming compressed data: %lu bytes", (unsigned long)compressedData.length);
    
    // Initialize streaming decompression with ZSTD_DStream
    ZSTD_DStream *dstream = ZSTD_createDStream();
    if (!dstream) {
      dispatch_async(dispatch_get_main_queue(), ^{
        [self notifyError:@"Failed to create ZSTD_DStream"];
      });
      return;
    }
    
    size_t initResult = ZSTD_initDStream(dstream);
    if (ZSTD_isError(initResult)) {
      NSLog(@"AlphaVideoGpuView: ZSTD_initDStream failed: %s", ZSTD_getErrorName(initResult));
      ZSTD_freeDStream(dstream);
      dispatch_async(dispatch_get_main_queue(), ^{
        [self notifyError:@"Failed to init ZSTD_DStream"];
      });
      return;
    }
    
    // Setup input buffer
    ZSTD_inBuffer input = { compressedData.bytes, compressedData.length, 0 };
    
    // Decompress header first (need at least 104 bytes for KTX2 header)
    size_t outputBufferSize = ZSTD_DStreamOutSize();
    NSMutableData *headerBuffer = [NSMutableData dataWithLength:outputBufferSize];
    NSMutableData *decompressedHeader = [NSMutableData data];
    
    uint32_t width = 0, height = 0, layerCount = 0;
    uint64_t level0Offset = 0;
    BOOL headerParsed = NO;
    
    while (!headerParsed && input.pos < input.size) {
      ZSTD_outBuffer output = { headerBuffer.mutableBytes, outputBufferSize, 0 };
      size_t result = ZSTD_decompressStream(dstream, &output, &input);
      
      if (ZSTD_isError(result)) {
        NSLog(@"AlphaVideoGpuView: Streaming decompression error: %s", ZSTD_getErrorName(result));
        ZSTD_freeDStream(dstream);
        dispatch_async(dispatch_get_main_queue(), ^{
          [self notifyError:@"Streaming decompression error"];
        });
        return;
      }
      
      if (output.pos > 0) {
        [decompressedHeader appendBytes:headerBuffer.bytes length:output.pos];
      }
      
      // Try to parse header once we have enough data
      if (decompressedHeader.length >= 104 && !headerParsed) {
        const uint8_t *bytes = (const uint8_t *)decompressedHeader.bytes;
        if (bytes[0] == 0xAB && bytes[1] == 0x4B) {
          uint32_t vkFormat = *(uint32_t *)(bytes + 12);
          width = *(uint32_t *)(bytes + 20);
          height = *(uint32_t *)(bytes + 24);
          layerCount = *(uint32_t *)(bytes + 32);
          level0Offset = *(uint64_t *)(bytes + 80);
          headerParsed = YES;
          
          // Configure ASTC format from vkFormat
          dispatch_sync(dispatch_get_main_queue(), ^{
            [self configureASTCFormatFromVkFormat:vkFormat];
          });
          
          AVG_VLOG(@"Streaming texture: %ux%u, %u layers, offset=%llu, vkFormat=%u", width, height, layerCount, level0Offset, vkFormat);
        }
      }
    }
    
    if (!headerParsed || layerCount == 0) {
      ZSTD_freeDStream(dstream);
      dispatch_async(dispatch_get_main_queue(), ^{
        [self notifyError:@"Failed to parse KTX2 header from stream"];
      });
      return;
    }
    
    // Continue decompressing until we have enough for metadata
    while (decompressedHeader.length < level0Offset && input.pos < input.size) {
      ZSTD_outBuffer output = { headerBuffer.mutableBytes, outputBufferSize, 0 };
      size_t result = ZSTD_decompressStream(dstream, &output, &input);
      
      if (ZSTD_isError(result)) {
        NSLog(@"AlphaVideoGpuView: Streaming decompression error: %s", ZSTD_getErrorName(result));
        ZSTD_freeDStream(dstream);
        dispatch_async(dispatch_get_main_queue(), ^{
          [self notifyError:@"Streaming decompression error"];
        });
        return;
      }
      
      if (output.pos > 0) {
        [decompressedHeader appendBytes:headerBuffer.bytes length:output.pos];
      }
    }
    
    // Extract metadata from header
    NSDictionary *metadata = [self extractKTX2Metadata:decompressedHeader];
    if (!metadata) {
      ZSTD_freeDStream(dstream);
      dispatch_async(dispatch_get_main_queue(), ^{
        [self notifyError:@"No metadata found in streaming KTX2"];
      });
      return;
    }
    
    // Calculate bytes per layer using current ASTC block dimensions
    uint32_t bytesPerLayer = [self astcBytesPerLayerForWidth:width height:height];
    
    AVG_VLOG(@"Bytes per layer: %u (ASTC %ux%u), level0Offset: %llu", bytesPerLayer, self->_astcBlockWidth, self->_astcBlockHeight, level0Offset);
    
    // Determine memory strategy
    BOOL useVramStrategy = [self.memoryStrategy isEqualToString:@"vram"] || 
                           ([self.memoryStrategy isEqualToString:@"auto"]);
    NSInteger gpuCacheSize = useVramStrategy ? 
        MIN(self.streamingCacheSize, (NSInteger)layerCount) : 
        MIN(self.streamingRingSize, (NSInteger)layerCount);
    
    AVG_VLOG(@"Streaming params: strategy=%@, gpuCache=%ld, vramMode=%d", 
          self.memoryStrategy, (long)gpuCacheSize, useVramStrategy);
    
    // Setup on main thread
    __block id<MTLTexture> textureArray = nil;
    __block BOOL setupComplete = NO;
    
    dispatch_sync(dispatch_get_main_queue(), ^{
      // Apply metadata
      self.totalFrames = layerCount;
      self.framesPerRow = 1;
      self.framesPerPart = layerCount;
      self.partCount = 1;
      self.frameWidth = width;
      self.frameHeight = height;
      self.frameRate = [metadata[@"frameRate"] floatValue] ?: 30.0;
      self.frameInterval = 1.0 / self.frameRate;
      
      self->_useTextureArray = YES;
      self->_textureArrayLayerCount = layerCount;
      self->_streamingRingBufferMode = YES;
      self->_streamingRingBufferSize = gpuCacheSize;
      self->_useVramStrategy = useVramStrategy;
      
      // Initialize ring buffer tracking
      self->_ringSlots = [NSMutableArray arrayWithCapacity:gpuCacheSize];
      for (NSInteger i = 0; i < gpuCacheSize; i++) {
        [self->_ringSlots addObject:@(-1)];
      }
      self->_frameCache = [NSMutableDictionary dictionary];
      self->_decoderPosition = 0;
      self->_decoderLoopIteration = 0;
      self->_playbackLoopIteration = 0;
      
      // Store streaming state for on-demand decoding
      self->_compressedData = compressedData;
      self->_compressedDataPos = input.pos;
      self->_dstream = dstream;
      self->_frameAccumulator = [NSMutableData data];
      self->_streamBytesPerLayer = bytesPerLayer;
      self->_streamLevel0Offset = level0Offset;
      self->_streamHeaderSkipped = NO;
      
      // Copy any data we already have past the header
      if (decompressedHeader.length > level0Offset) {
        [self->_frameAccumulator appendBytes:((const uint8_t *)decompressedHeader.bytes + level0Offset) 
                                      length:decompressedHeader.length - level0Offset];
        self->_streamHeaderSkipped = YES;
      }
      
      // Create texture array with ring buffer size (not full layer count!)
      MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:self->_metalPixelFormat
                                                                                           width:width
                                                                                          height:height
                                                                                       mipmapped:NO];
      descriptor.textureType = MTLTextureType2DArray;
      descriptor.arrayLength = gpuCacheSize;  // Ring buffer size, not total frames!
      descriptor.usage = MTLTextureUsageShaderRead;
      
      self->_textureArray = [self.device newTextureWithDescriptor:descriptor];
      textureArray = self->_textureArray;
      
      if (!textureArray) {
        [self notifyError:@"Failed to create texture array"];
        return;
      }
      
      // Initialize staging buffers for async blit uploads (double-buffered)
      if (self->_useCompressedFormat) {
        self->_stagingBuffers[0] = [self.device newBufferWithLength:self->_streamBytesPerLayer
                                                            options:MTLResourceStorageModeShared];
        self->_stagingBuffers[1] = [self.device newBufferWithLength:self->_streamBytesPerLayer
                                                            options:MTLResourceStorageModeShared];
        self->_currentStagingBuffer = 0;
        if (!self->_blitCommandQueue) {
          self->_blitCommandQueue = [self.device newCommandQueue];
        }
        AVG_VLOG(@"Created staging buffers for async blit: %u bytes each", self->_streamBytesPerLayer);
      }
      
      AVG_VLOG(@"Created streaming texture array: %ux%u, %ld GPU slots (ring buffer)", 
            width, height, (long)gpuCacheSize);
      
      // Pre-decode initial frames so we have something to display
      // This is needed because decodeFramesAhead is only called during playback
      NSInteger initialVPos = 0;
      [self decodeFramesAhead:initialVPos];
      
      self->_isSourceLoaded = YES;
      self->_isSourceLoading = NO;
      [self notifyLoad];
      [self notifyStateChange:@"ready"];
      
      if (self.autoPlay && !self.playbackPaused) {
        [self play];
      }
      
      setupComplete = YES;
    });
    
    if (!setupComplete || !textureArray) {
      // Don't free dstream here - it's now owned by the instance for on-demand decoding
      return;
    }
    
    // Note: We no longer decode all frames upfront!
    // Frames are decoded on-demand during playback via decodeFramesAhead:
    AVG_VLOG(@"Streaming setup complete - frames will be decoded on-demand during playback");
  });
}

#pragma mark - On-Demand Frame Decoding

- (void)decodeFramesAhead:(NSInteger)currentVirtualPos
{
  if (!_dstream || !_compressedData || !_streamingRingBufferMode) {
    static int skipCount = 0;
    if (skipCount++ < 5) {
      AVG_VLOG(@"decodeFramesAhead skipping - dstream=%p, compressedData=%p, ringMode=%d",
            _dstream, _compressedData, _streamingRingBufferMode);
    }
    return;
  }
  
  // Calculate how many frames to decode ahead
  // Use relative distance to handle wraparound correctly (matching Android approach)
  NSInteger vPosModulus = self.totalFrames * 2;  // Bounded virtual position space
  NSInteger currentDecodeVPos = (_decoderLoopIteration * self.totalFrames + _decoderPosition) % vPosModulus;
  
  // Calculate frames ahead using relative distance
  NSInteger framesAhead = (currentDecodeVPos - currentVirtualPos + vPosModulus) % vPosModulus;
  NSInteger framesToDecode = self.streamingPrefetchAhead - framesAhead;
  
  if (framesToDecode <= 0) {
    static int skipLogCount = 0;
    if (skipLogCount++ < 10 || skipLogCount % 60 == 0) {
      AVG_VLOG(@"decodeFramesAhead skipping - vPos=%ld, decodeVPos=%ld, framesAhead=%ld (already ahead enough)",
            (long)currentVirtualPos, (long)currentDecodeVPos, (long)framesAhead);
    }
    return;  // Already ahead enough
  }
  
  static int decodeLogCount = 0;
  if (decodeLogCount++ < 20 || framesToDecode > 0) {
    static int logThrottle = 0;
    if (logThrottle++ % 30 == 0 || framesToDecode > 0) {
      AVG_VLOG(@"decodeFramesAhead vPos=%ld, decodeVPos=%ld, framesAhead=%ld, framesToDecode=%ld, decoderPos=%ld, decoderLoop=%ld",
            (long)currentVirtualPos, (long)currentDecodeVPos, (long)framesAhead, (long)framesToDecode,
            (long)_decoderPosition, (long)_decoderLoopIteration);
    }
  }
  
  size_t outputBufferSize = ZSTD_DStreamOutSize();
  NSMutableData *decompressBuffer = [NSMutableData dataWithLength:outputBufferSize];
  
  ZSTD_inBuffer input = { _compressedData.bytes, _compressedData.length, _compressedDataPos };
  
  while (framesToDecode > 0) {
    // Check if we need to restart decompression for looping
    if (_decoderPosition >= self.totalFrames) {
      _decoderPosition = 0;
      _decoderLoopIteration++;
      
      // Reinitialize decompression stream
      ZSTD_initDStream(_dstream);
      input.pos = 0;
      _compressedDataPos = 0;
      [_frameAccumulator setLength:0];
      _streamHeaderSkipped = NO;
      
      AVG_VLOG(@"Decoder wrap-around: loop %ld, resetting input.pos from %zu to 0", 
            (long)_decoderLoopIteration, input.pos);
      
      // After wrap-around, recalculate how many frames we need to decode
      // Use the same formula as at the start of the method
      NSInteger newDecodeVPos = (_decoderLoopIteration * self.totalFrames + _decoderPosition) % vPosModulus;
      NSInteger newFramesAhead = (newDecodeVPos - currentVirtualPos + vPosModulus) % vPosModulus;
      framesToDecode = self.streamingPrefetchAhead - newFramesAhead;
      if (framesToDecode <= 0) {
        AVG_VLOG(@"After wrap-around: already ahead enough, stopping decode");
        break;
      }
      AVG_VLOG(@"After wrap-around: newDecodeVPos=%ld, framesAhead=%ld, framesToDecode=%ld, inputSize=%zu",
            (long)newDecodeVPos, (long)newFramesAhead, (long)framesToDecode, input.size);
      // Don't continue - fall through to decompress the header and first frames
      // The continue was causing us to skip decompression after wrap-around
    }
    
    // Skip header bytes if needed
    if (![self skipStreamHeader:&input buffer:decompressBuffer]) {
      break;  // Not enough data yet
    }
    
    // Decompress until we have a full frame
    int decompressLoopCount = 0;
    while (_frameAccumulator.length < _streamBytesPerLayer && input.pos < input.size) {
      ZSTD_outBuffer output = { decompressBuffer.mutableBytes, outputBufferSize, 0 };
      size_t result = ZSTD_decompressStream(_dstream, &output, &input);
      if (ZSTD_isError(result)) {
        NSLog(@"AlphaVideoGpuView: Decompression error: %s", ZSTD_getErrorName(result));
        break;
      }
      if (output.pos > 0) {
        [_frameAccumulator appendBytes:decompressBuffer.bytes length:output.pos];
      }
      decompressLoopCount++;
      if (result == 0) {
        AVG_VLOG(@"ZSTD stream ended at decoderPos=%ld, inputPos=%zu/%zu, accumLen=%lu",
              (long)_decoderPosition, input.pos, input.size, (unsigned long)_frameAccumulator.length);
        break;  // Stream ended
      }
    }
    
    // Log if we exited without a full frame
    if (_frameAccumulator.length < _streamBytesPerLayer && decompressLoopCount == 0) {
      static int noProgressCount = 0;
      if (noProgressCount++ < 10) {
        AVG_VLOG(@"No decompression progress - accumLen=%lu, bytesPerLayer=%u, inputPos=%zu/%zu, decoderPos=%ld",
              (unsigned long)_frameAccumulator.length, _streamBytesPerLayer, input.pos, input.size, (long)_decoderPosition);
      }
    }
    
    // Process complete frame
    if (_frameAccumulator.length >= _streamBytesPerLayer) {
      NSInteger virtualPos = (_decoderLoopIteration * self.totalFrames + _decoderPosition) % vPosModulus;
      NSInteger gpuSlot = _decoderPosition % _streamingRingBufferSize;
      
      // Check if we can upload to this GPU slot
      NSInteger slotCurrentVPos = [_ringSlots[gpuSlot] integerValue];
      
      // Calculate if slot is "old" (behind playback) using relative distance
      // A slot is old if it's behind the current playback position
      // We also allow overwriting if the slot is from a different loop iteration
      // (i.e., slot has vPos from loop 0 but we're decoding for loop 1)
      NSInteger slotAge = (currentVirtualPos - slotCurrentVPos + vPosModulus) % vPosModulus;
      BOOL slotIsOld = slotAge > 0 && slotAge < vPosModulus / 2;
      
      // Also allow overwriting if the slot's frame is from a previous loop
      // This handles the wrap-around case where decoder is ahead of playback
      NSInteger slotActualFrame = slotCurrentVPos % self.totalFrames;
      NSInteger decoderActualFrame = _decoderPosition;
      BOOL slotFromPreviousLoop = (slotCurrentVPos != -1) && 
                                   (slotActualFrame == decoderActualFrame) && 
                                   (slotCurrentVPos != virtualPos);
      
      BOOL canOverwrite = (slotCurrentVPos == -1) || (slotCurrentVPos == virtualPos) || slotIsOld || slotFromPreviousLoop;
      
      // In RAM mode, ALWAYS cache the frame first (CPU cache is primary storage)
      // In VRAM mode, only cache if we can't upload to GPU
      if (!_useVramStrategy) {
        // RAM mode: Always cache to CPU
        NSData *frameData = [NSData dataWithBytes:_frameAccumulator.bytes length:_streamBytesPerLayer];
        _frameCache[@(virtualPos)] = frameData;
      }
      
      if (canOverwrite && slotCurrentVPos != virtualPos) {
        // Upload frame to GPU
        static int uploadLogCount = 0;
        if (uploadLogCount++ < 30) {
          AVG_VLOG(@"Uploading frame %ld to GPU slot %ld (vPos=%ld, loop=%ld)", 
                (long)_decoderPosition, (long)gpuSlot, (long)virtualPos, (long)_decoderLoopIteration);
        }
        [self uploadFrameToGPU:_frameAccumulator.bytes slot:gpuSlot];
        _ringSlots[gpuSlot] = @(virtualPos);
      } else if (!canOverwrite && _useVramStrategy) {
        // VRAM mode: Can't upload, cache temporarily
        static int blockLogCount = 0;
        if (blockLogCount++ < 10) {
          NSLog(@"AlphaVideoGpuView: Can't upload frame %ld to slot %ld - caching (slotVPos=%ld, virtualPos=%ld, slotAge=%ld)",
                (long)_decoderPosition, (long)gpuSlot, (long)slotCurrentVPos, (long)virtualPos, (long)slotAge);
        }
        NSData *frameData = [NSData dataWithBytes:_frameAccumulator.bytes length:_streamBytesPerLayer];
        _frameCache[@(virtualPos)] = frameData;
      }
      
      _decoderPosition++;
      framesToDecode--;
      
      // Remove processed frame data (in-place shift, no allocation)
      if (_frameAccumulator.length > _streamBytesPerLayer) {
        [_frameAccumulator replaceBytesInRange:NSMakeRange(0, _streamBytesPerLayer) withBytes:NULL length:0];
      } else {
        [_frameAccumulator setLength:0];
      }
    } else {
      break;  // No more data available
    }
  }
  
  _compressedDataPos = input.pos;
}

/** Decompresses past the KTX2 header of a whole-file zstd stream; NO if the input ran out first. */
- (BOOL)skipStreamHeader:(ZSTD_inBuffer *)input buffer:(NSMutableData *)decompressBuffer
{
  if (_streamHeaderSkipped) return YES;
  while (_frameAccumulator.length < _streamLevel0Offset && input->pos < input->size) {
    ZSTD_outBuffer output = { decompressBuffer.mutableBytes, decompressBuffer.length, 0 };
    size_t result = ZSTD_decompressStream(_dstream, &output, input);
    if (ZSTD_isError(result)) break;
    if (output.pos > 0) {
      [_frameAccumulator appendBytes:decompressBuffer.bytes length:output.pos];
    }
  }
  if (_frameAccumulator.length < _streamLevel0Offset) return NO;
  // Remove header data, keep frame data
  [_frameAccumulator replaceBytesInRange:NSMakeRange(0, _streamLevel0Offset) withBytes:NULL length:0];
  _streamHeaderSkipped = YES;
  return YES;
}

/** Decompresses and drops the next bytes of frame data, for a seek. */
- (void)skipDecodedBytes:(uint64_t)bytes
{
  NSMutableData *decompressBuffer = [NSMutableData dataWithLength:ZSTD_DStreamOutSize()];
  ZSTD_inBuffer input = { _compressedData.bytes, _compressedData.length, _compressedDataPos };
  if ([self skipStreamHeader:&input buffer:decompressBuffer]) {
    uint64_t held = MIN((uint64_t)_frameAccumulator.length, bytes);
    [_frameAccumulator replaceBytesInRange:NSMakeRange(0, (NSUInteger)held) withBytes:NULL length:0];
    bytes -= held;
    while (bytes > 0) {
      ZSTD_outBuffer output = { decompressBuffer.mutableBytes, decompressBuffer.length, 0 };
      size_t result = ZSTD_decompressStream(_dstream, &output, &input);
      if (ZSTD_isError(result)) {
        NSLog(@"AlphaVideoGpuView: Decompression error while seeking: %s", ZSTD_getErrorName(result));
        break;
      }
      if (output.pos > bytes) {
        [_frameAccumulator appendBytes:(const uint8_t *)decompressBuffer.bytes + bytes length:output.pos - (size_t)bytes];
        bytes = 0;
      } else {
        bytes -= output.pos;
      }
      if (result == 0 || (output.pos == 0 && input.pos >= input.size)) break;
    }
  }
  _compressedDataPos = input.pos;
}

/**
 * Points the streaming decoder at target, keeping what is decoded when target is among the
 * buffered frames (from the shown one up to the decoder).
 */
- (void)moveStreamTo:(NSInteger)target
{
  if (_isUncompressedStreaming) {
    if (!_useCompressedFormat && _decodedRgbaCache) {
      // Decode blocks in flight read and advance the position, so reset it between them
      dispatch_sync(_astcDecodeQueue, ^{
        @synchronized(self->_decodedRgbaCache) {
          [self->_decodedRgbaCache removeAllObjects];
        }
        self->_uncompressedDecoderPosition = target;
        self->_decoderLoopIteration = 0;
      });
    }
  } else if (_dstream) {
    BOOL samePass = _decoderLoopIteration % 2 == _playbackLoopIteration % 2;
    if (samePass && target >= self.currentFrame && target < _decoderPosition) return;
    if (samePass && target >= _decoderPosition) {
      [self skipDecodedBytes:(uint64_t)(target - _decoderPosition) * _streamBytesPerLayer];
    } else {
      ZSTD_initDStream(_dstream);
      _compressedDataPos = 0;
      [_frameAccumulator setLength:0];
      _streamHeaderSkipped = NO;
      [self skipDecodedBytes:(uint64_t)target * _streamBytesPerLayer];
    }
    _decoderPosition = target;
    _decoderLoopIteration = 0;
    [_frameCache removeAllObjects];
  }
  _playbackLoopIteration = 0;
  for (NSUInteger i = 0; i < _ringSlots.count; i++) {
    _ringSlots[i] = @(-1);
  }
}

/** Decodes towards the shown frame; YES once it can be drawn. */
- (BOOL)prepareShownFrame
{
  if (!_streamingRingBufferMode) return YES;
  NSInteger virtualPos = (_playbackLoopIteration * self.totalFrames + self.currentFrame) % (self.totalFrames * 2);
  NSInteger gpuSlot = self.currentFrame % _streamingRingBufferSize;
  if ([_ringSlots[gpuSlot] integerValue] == virtualPos) return YES;
  if (_isUncompressedStreaming) {
    // A device uploads straight from the mapping as it draws
    if (_useCompressedFormat || !_decodedRgbaCache) return YES;
    [self decodeUncompressedFramesAhead:virtualPos];
    @synchronized(_decodedRgbaCache) {
      return _decodedRgbaCache[@(virtualPos)] != nil;
    }
  }
  [self decodeFramesAhead:virtualPos];
  return _frameCache[@(virtualPos)] != nil || [_ringSlots[gpuSlot] integerValue] == virtualPos;
}

- (void)uploadFrameToGPU:(const void *)frameData slot:(NSInteger)slot
{
  if (!_textureArray) return;
  
  MTLRegion region = MTLRegionMake2D(0, 0, self.frameWidth, self.frameHeight);
  
  if (_useCompressedFormat) {
    // Use async blit if staging buffers are available
    if (_stagingBuffers[0] && _blitCommandQueue) {
      // Copy to staging buffer
      id<MTLBuffer> staging = _stagingBuffers[_currentStagingBuffer];
      memcpy(staging.contents, frameData, _streamBytesPerLayer);
      
      // Create blit command buffer
      id<MTLCommandBuffer> cmdBuf = [_blitCommandQueue commandBuffer];
      id<MTLBlitCommandEncoder> blit = [cmdBuf blitCommandEncoder];
      
      // Async copy from buffer to texture
      [blit copyFromBuffer:staging
              sourceOffset:0
         sourceBytesPerRow:0
       sourceBytesPerImage:_streamBytesPerLayer
                sourceSize:MTLSizeMake(self.frameWidth, self.frameHeight, 1)
                 toTexture:_textureArray
          destinationSlice:slot
          destinationLevel:0
         destinationOrigin:MTLOriginMake(0, 0, 0)];
      
      [blit endEncoding];
      [cmdBuf commit];  // Async - returns immediately
      
      // Swap staging buffer for next upload
      _currentStagingBuffer = 1 - _currentStagingBuffer;
    } else {
      // Fallback to synchronous replaceRegion
      [_textureArray replaceRegion:region
                       mipmapLevel:0
                             slice:slot
                         withBytes:frameData
                       bytesPerRow:0
                     bytesPerImage:_streamBytesPerLayer];
    }
  } else {
    // Decode ASTC to RGBA for simulator
    uint32_t bw = _astcBlockWidth;
    uint32_t bh = _astcBlockHeight;
    uint32_t blocksX = ((uint32_t)self.frameWidth + bw - 1) / bw;
    uint32_t blocksY = ((uint32_t)self.frameHeight + bh - 1) / bh;
    std::vector<uint8_t> rgbaData(self.frameWidth * self.frameHeight * 4);
    
    const uint8_t *astcData = (const uint8_t *)frameData;
    for (uint32_t by = 0; by < blocksY; by++) {
      for (uint32_t bx = 0; bx < blocksX; bx++) {
        const uint8_t *block = astcData + (by * blocksX + bx) * 16;
        uint8_t blockPixels[12 * 12 * 4]; // max ASTC block size
        basisu::astc::decompress(blockPixels, block, false, bw, bh);
        
        for (uint32_t y = 0; y < bh && (by * bh + y) < self.frameHeight; y++) {
          for (uint32_t x = 0; x < bw && (bx * bw + x) < self.frameWidth; x++) {
            uint32_t dstX = bx * bw + x;
            uint32_t dstY = by * bh + y;
            uint8_t *dst = rgbaData.data() + (dstY * self.frameWidth + dstX) * 4;
            uint8_t *src = blockPixels + (y * bw + x) * 4;
            memcpy(dst, src, 4);
          }
        }
      }
    }
    
    [_textureArray replaceRegion:region
                     mipmapLevel:0
                           slice:slot
                       withBytes:rgbaData.data()
                     bytesPerRow:self.frameWidth * 4
                   bytesPerImage:self.frameWidth * self.frameHeight * 4];
  }
}

- (void)ensureFrameInGPU:(NSInteger)virtualPos
{
  if (!_streamingRingBufferMode) return;
  
  NSInteger actualFrame = virtualPos % self.totalFrames;
  NSInteger gpuSlot = actualFrame % _streamingRingBufferSize;
  
  // Check if already loaded
  NSInteger slotVPos = [_ringSlots[gpuSlot] integerValue];
  if (slotVPos == virtualPos) return;
  
  // Check CPU cache
  NSData *frameData = _frameCache[@(virtualPos)];
  if (!frameData) {
    static int missCount = 0;
    if (missCount++ < 30) {
      AVG_VLOG(@"ensureFrameInGPU - cache miss for vPos=%ld, slot=%ld has vPos=%ld, cacheSize=%lu",
            (long)virtualPos, (long)gpuSlot, (long)slotVPos, (unsigned long)_frameCache.count);
    }
    return;
  }
  
  // Upload to GPU
  static int ensureUploadCount = 0;
  if (ensureUploadCount++ < 30) {
    AVG_VLOG(@"ensureFrameInGPU - uploading vPos=%ld to slot=%ld from cache",
          (long)virtualPos, (long)gpuSlot);
  }
  [self uploadFrameToGPU:frameData.bytes slot:gpuSlot];
  _ringSlots[gpuSlot] = @(virtualPos);
  
  // In VRAM mode, remove from CPU cache after upload
  if (_useVramStrategy) {
    [_frameCache removeObjectForKey:@(virtualPos)];
  }
}

- (void)evictOldFrames:(NSInteger)currentVirtualPos
{
  if (!_frameCache) return;
  
  NSInteger vPosModulus = self.totalFrames * 2;
  NSMutableArray *toRemove = [NSMutableArray array];
  
  if (_useVramStrategy) {
    // VRAM mode: Only evict frames that have been played
    for (NSNumber *virtualPos in _frameCache.allKeys) {
      NSInteger vPos = [virtualPos integerValue];
      NSInteger age = (currentVirtualPos - vPos + vPosModulus) % vPosModulus;
      // Frame is "old" if it's behind playback (age > 0) but not wrapped around (age < half modulus)
      if (age > 0 && age < vPosModulus / 2) {
        [toRemove addObject:virtualPos];
      }
    }
  } else {
    // RAM mode: Use sliding window with relative distance
    // Keep frames within [-2, +streamingCacheSize] window
    for (NSNumber *virtualPos in _frameCache.allKeys) {
      NSInteger vPos = [virtualPos integerValue];
      NSInteger distance = (vPos - currentVirtualPos + vPosModulus) % vPosModulus;
      // distance > vPosModulus - 2 means frame is 1-2 behind (wrapped)
      // distance <= streamingCacheSize means frame is ahead
      BOOL isBehind = distance > (NSInteger)(vPosModulus - 2);
      BOOL isAhead = distance <= self.streamingCacheSize;
      if (!isBehind && !isAhead) {
        [toRemove addObject:virtualPos];
      }
    }
  }
  
  for (NSNumber *virtualPos in toRemove) {
    [_frameCache removeObjectForKey:virtualPos];
  }
}

#pragma mark - Uncompressed Streaming (mmap)

// With the stream prop, plays an uncompressed KTX2 texture array by mmap streaming, reading only
// the header and metadata so the file is never loaded into memory.
- (BOOL)checkAndPlayUncompressedStreaming:(NSString *)filePath
{
  if (!self.stream) return NO;
  AVG_VLOG(@"checkAndPlayUncompressedStreaming: %@", filePath);
  
  // Open file
  int fd = open([filePath UTF8String], O_RDONLY);
  if (fd < 0) {
    NSLog(@"AlphaVideoGpuView: Failed to open file for mmap");
    return NO;
  }
  
  // Get file size
  struct stat st;
  if (fstat(fd, &st) < 0) {
    close(fd);
    return NO;
  }
  size_t fileSize = st.st_size;
  
  // Read header (first 256 bytes)
  uint8_t headerBytes[256];
  if (read(fd, headerBytes, 256) < 100) {
    close(fd);
    return NO;
  }
  
  // Check KTX2 magic
  uint8_t ktx2Magic[] = {0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A};
  if (memcmp(headerBytes, ktx2Magic, 12) != 0) {
    close(fd);
    return NO;
  }
  
  // Check supercompressionScheme at offset 44
  uint32_t supercompression = *(uint32_t *)(headerBytes + 44);
  if (supercompression != 0) {
    close(fd);
    return NO;  // Not uncompressed
  }
  
  // Read KVD offset and length (bytes 56-63)
  uint32_t kvdOffset = *(uint32_t *)(headerBytes + 56);
  uint32_t kvdLength = *(uint32_t *)(headerBytes + 60);
  
  AVG_VLOG(@"checkAndPlayUncompressedStreaming: kvdOffset=%u, kvdLength=%u", kvdOffset, kvdLength);
  
  if (kvdLength == 0 || kvdOffset == 0) {
    close(fd);
    return NO;
  }
  
  // Read metadata section
  lseek(fd, kvdOffset, SEEK_SET);
  NSMutableData *metadataBytes = [NSMutableData dataWithLength:MIN(kvdLength, 4096u)];
  read(fd, metadataBytes.mutableBytes, metadataBytes.length);
  
  // Extract metadata JSON from KVD
  NSString *metadataJson = [self extractMetadataJsonFromKvdBytes:metadataBytes];
  if (!metadataJson) {
    close(fd);
    return NO;
  }
  
  // Parse metadata
  NSError *jsonError = nil;
  NSDictionary *metadata = [NSJSONSerialization JSONObjectWithData:[metadataJson dataUsingEncoding:NSUTF8StringEncoding]
                                                           options:0 error:&jsonError];
  if (jsonError || ![metadata isKindOfClass:[NSDictionary class]]) {
    close(fd);
    return NO;
  }
  // Only texture arrays stream
  if (![metadata[@"packingMode"] isEqual:@"array"]) {
    close(fd);
    return NO;
  }
  
  AVG_VLOG(@"Detected uncompressed streaming KTX2 - using mmap");
  
  // Memory map the file
  void *mmapData = mmap(NULL, fileSize, PROT_READ, MAP_PRIVATE, fd, 0);
  if (mmapData == MAP_FAILED) {
    NSLog(@"AlphaVideoGpuView: mmap failed");
    close(fd);
    return NO;
  }
  
  // Store mmap state
  _mmapFd = fd;
  _mmapData = mmapData;
  _mmapSize = fileSize;
  _isUncompressedStreaming = YES;
  
  // Parse KTX2 header from mmap
  const uint8_t *data = (const uint8_t *)mmapData;
  uint32_t vkFormat = *(uint32_t *)(data + 12);
  uint32_t width = *(uint32_t *)(data + 20);
  uint32_t height = *(uint32_t *)(data + 24);
  uint32_t layerCount = *(uint32_t *)(data + 32);
  if (layerCount == 0) layerCount = 1;
  uint64_t level0Offset = *(uint64_t *)(data + 80);
  
  // Configure ASTC format from vkFormat
  [self configureASTCFormatFromVkFormat:vkFormat];
  uint32_t bytesPerLayer = [self astcBytesPerLayerForWidth:width height:height];
  
  AVG_VLOG(@"Uncompressed streaming: %ux%u, %u layers, %u bytes/layer (ASTC %ux%u), offset=%llu",
        width, height, layerCount, bytesPerLayer, _astcBlockWidth, _astcBlockHeight, level0Offset);
  
  // Determine GPU cache size
  BOOL useVramStrategy = [self.memoryStrategy isEqualToString:@"vram"] || 
                         [self.memoryStrategy isEqualToString:@"auto"];
  NSInteger gpuCacheSize = useVramStrategy ? 
      MIN(self.streamingCacheSize, (NSInteger)layerCount) : 
      MIN(self.streamingRingSize, (NSInteger)layerCount);
  
  // Setup on main thread
  dispatch_sync(dispatch_get_main_queue(), ^{
    self.totalFrames = layerCount;
    self.framesPerRow = 1;
    self.framesPerPart = layerCount;
    self.partCount = 1;
    self.frameWidth = width;
    self.frameHeight = height;
    self.frameRate = [metadata[@"frameRate"] floatValue] ?: 30.0;
    self.frameInterval = 1.0 / self.frameRate;
    
    self->_useTextureArray = YES;
    self->_textureArrayLayerCount = layerCount;
    self->_streamingRingBufferMode = YES;
    self->_streamingRingBufferSize = gpuCacheSize;
    self->_useVramStrategy = useVramStrategy;
    self->_streamBytesPerLayer = bytesPerLayer;
    self->_streamLevel0Offset = level0Offset;
    
    // Initialize ring buffer tracking
    self->_ringSlots = [NSMutableArray arrayWithCapacity:gpuCacheSize];
    for (NSInteger i = 0; i < gpuCacheSize; i++) {
      [self->_ringSlots addObject:@(-1)];
    }
    self->_playbackLoopIteration = 0;
    
    // Initialize decoded RGBA cache for simulator ASTC decompression
    if (!self->_useCompressedFormat) {
      self->_decodedRgbaCache = [NSMutableDictionary dictionary];
      self->_uncompressedDecoderPosition = 0;
      self->_decoderLoopIteration = 0;
    }
    
    // Create texture array with ring buffer size
    MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:self->_metalPixelFormat
                                                                                         width:width
                                                                                        height:height
                                                                                     mipmapped:NO];
    descriptor.textureType = MTLTextureType2DArray;
    descriptor.arrayLength = gpuCacheSize;
    descriptor.usage = MTLTextureUsageShaderRead;
    
    self->_textureArray = [self.device newTextureWithDescriptor:descriptor];
    
    if (!self->_textureArray) {
      [self notifyError:@"Failed to create texture array for uncompressed streaming"];
      return;
    }
    
    AVG_VLOG(@"Created uncompressed streaming texture array: %ux%u, %ld GPU slots",
          width, height, (long)gpuCacheSize);
    
    self->_isSourceLoaded = YES;
    self->_isSourceLoading = NO;
    [self notifyLoad];
    [self notifyStateChange:@"ready"];
    
    // Pre-decode initial frames on background thread to avoid freeze
    if (!self->_useCompressedFormat) {
      // Simulator: decode ASTC to RGBA on background, then upload to GPU
      dispatch_async(self->_astcDecodeQueue, ^{
        NSInteger framesToPreload = MIN(gpuCacheSize, (NSInteger)layerCount);
        AVG_VLOG(@"Pre-decoding %ld initial frames for uncompressed streaming (simulator)", (long)framesToPreload);
        
        for (NSInteger i = 0; i < framesToPreload && self->_mmapData; i++) {
          // Decode ASTC to RGBA
          const uint8_t *data = (const uint8_t *)self->_mmapData;
          uint64_t frameOffset = self->_streamLevel0Offset + (uint64_t)i * self->_streamBytesPerLayer;
          const uint8_t *frameData = data + frameOffset;
          
          uint32_t bw = self->_astcBlockWidth;
          uint32_t bh = self->_astcBlockHeight;
          uint32_t blocksX = (width + bw - 1) / bw;
          uint32_t blocksY = (height + bh - 1) / bh;
          NSMutableData *rgbaData = [NSMutableData dataWithLength:width * height * 4];
          uint8_t *rgba = (uint8_t *)rgbaData.mutableBytes;
          
          for (uint32_t by = 0; by < blocksY; by++) {
            for (uint32_t bx = 0; bx < blocksX; bx++) {
              const uint8_t *block = frameData + (by * blocksX + bx) * 16;
              uint8_t blockPixels[12 * 12 * 4]; // max ASTC block size
              basisu::astc::decompress(blockPixels, block, false, bw, bh);
              
              for (uint32_t y = 0; y < bh && (by * bh + y) < height; y++) {
                for (uint32_t x = 0; x < bw && (bx * bw + x) < width; x++) {
                  uint32_t dstX = bx * bw + x;
                  uint32_t dstY = by * bh + y;
                  uint8_t *dst = rgba + (dstY * width + dstX) * 4;
                  uint8_t *src = blockPixels + (y * bw + x) * 4;
                  memcpy(dst, src, 4);
                }
              }
            }
          }
          
          // Cache decoded frame
          @synchronized(self->_decodedRgbaCache) {
            self->_decodedRgbaCache[@(i)] = rgbaData;
          }
          self->_uncompressedDecoderPosition = i + 1;
        }
        
        // Upload to GPU on main thread (fast - just memcpy)
        dispatch_async(dispatch_get_main_queue(), ^{
          for (NSInteger i = 0; i < framesToPreload; i++) {
            NSInteger slot = i % gpuCacheSize;
            [self uploadDecodedFrameToGPU:i slot:slot];
            self->_ringSlots[slot] = @(i);
          }
          
          AVG_VLOG(@"Pre-decode complete, starting playback");
          
          if (self.autoPlay && !self.playbackPaused) {
            [self play];
          }
        });
      });
    } else {
      // Real device: direct upload (fast, no ASTC decode needed)
      dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH, 0), ^{
        NSInteger framesToPreload = MIN(gpuCacheSize, (NSInteger)layerCount);
        AVG_VLOG(@"Pre-uploading %ld initial frames for uncompressed streaming (device)", (long)framesToPreload);
        
        for (NSInteger i = 0; i < framesToPreload; i++) {
          NSInteger slot = i % gpuCacheSize;
          [self uploadUncompressedFrame:i toSlot:slot];
          self->_ringSlots[slot] = @(i);
        }
        
        AVG_VLOG(@"Pre-upload complete, starting playback");
        
        dispatch_async(dispatch_get_main_queue(), ^{
          if (self.autoPlay && !self.playbackPaused) {
            [self play];
          }
        });
      });
    }
  });
  
  return YES;
}

- (NSString *)extractMetadataJsonFromKvdBytes:(NSData *)kvdBytes
{
  if (!kvdBytes || kvdBytes.length < 4) return nil;
  
  const uint8_t *bytes = (const uint8_t *)kvdBytes.bytes;
  NSUInteger pos = 0;
  NSUInteger kvdEnd = kvdBytes.length;
  
  while (pos < kvdEnd) {
    if (pos + 4 > kvdBytes.length) break;
    
    uint32_t entryLength = *(uint32_t *)(bytes + pos);
    pos += 4;
    
    if (entryLength == 0 || pos + entryLength > kvdBytes.length) break;
    
    // Find key (NUL-terminated string)
    NSUInteger keyEnd = pos;
    while (keyEnd < pos + entryLength && bytes[keyEnd] != 0) {
      keyEnd++;
    }
    
    NSString *key = [[NSString alloc] initWithBytes:(bytes + pos) length:(keyEnd - pos) encoding:NSUTF8StringEncoding];
    
    if ([key isEqualToString:@"BasisPlayerMetadata"]) {
      NSUInteger valueStart = keyEnd + 1;
      NSUInteger valueEnd = pos + entryLength;
      
      // Find actual value end (may have NUL terminator)
      while (valueEnd > valueStart && bytes[valueEnd - 1] == 0) {
        valueEnd--;
      }
      
      NSString *metadataJson = [[NSString alloc] initWithBytes:(bytes + valueStart) 
                                                        length:(valueEnd - valueStart) 
                                                      encoding:NSUTF8StringEncoding];
      AVG_VLOG(@"extractMetadataJsonFromKvdBytes: found BasisPlayerMetadata");
      return metadataJson;
    }
    
    // Move to next entry (with padding to 4-byte alignment)
    pos += entryLength;
    pos += (4 - (entryLength % 4)) % 4;
  }
  
  return nil;
}

- (void)uploadUncompressedFrame:(NSInteger)frameIndex toSlot:(NSInteger)slot
{
  if (!_mmapData || !_textureArray) return;
  
  const uint8_t *data = (const uint8_t *)_mmapData;
  uint64_t frameOffset = _streamLevel0Offset + (uint64_t)frameIndex * _streamBytesPerLayer;
  const uint8_t *frameData = data + frameOffset;
  
  MTLRegion region = MTLRegionMake2D(0, 0, self.frameWidth, self.frameHeight);
  
  if (_useCompressedFormat) {
    [_textureArray replaceRegion:region
                     mipmapLevel:0
                           slice:slot
                       withBytes:frameData
                     bytesPerRow:0
                   bytesPerImage:_streamBytesPerLayer];
  } else {
    // Decode ASTC to RGBA for simulator
    uint32_t bw = _astcBlockWidth;
    uint32_t bh = _astcBlockHeight;
    uint32_t blocksX = ((uint32_t)self.frameWidth + bw - 1) / bw;
    uint32_t blocksY = ((uint32_t)self.frameHeight + bh - 1) / bh;
    std::vector<uint8_t> rgbaData(self.frameWidth * self.frameHeight * 4);
    
    for (uint32_t by = 0; by < blocksY; by++) {
      for (uint32_t bx = 0; bx < blocksX; bx++) {
        const uint8_t *block = frameData + (by * blocksX + bx) * 16;
        uint8_t blockPixels[12 * 12 * 4]; // max ASTC block size
        basisu::astc::decompress(blockPixels, block, false, bw, bh);
        
        for (uint32_t y = 0; y < bh && (by * bh + y) < self.frameHeight; y++) {
          for (uint32_t x = 0; x < bw && (bx * bw + x) < self.frameWidth; x++) {
            uint32_t dstX = bx * bw + x;
            uint32_t dstY = by * bh + y;
            uint8_t *dst = rgbaData.data() + (dstY * self.frameWidth + dstX) * 4;
            uint8_t *src = blockPixels + (y * bw + x) * 4;
            memcpy(dst, src, 4);
          }
        }
      }
    }
    
    [_textureArray replaceRegion:region
                     mipmapLevel:0
                           slice:slot
                       withBytes:rgbaData.data()
                     bytesPerRow:self.frameWidth * 4
                   bytesPerImage:self.frameWidth * self.frameHeight * 4];
  }
}

// Decode ASTC frames ahead on background thread for uncompressed streaming (simulator only)
- (void)decodeUncompressedFramesAhead:(NSInteger)currentVirtualPos
{
  if (!_mmapData || !_isUncompressedStreaming || _useCompressedFormat) return;
  
  // Only needed on simulator where we decode ASTC to RGBA
  NSInteger vPosModulus = self.totalFrames * 2;
  NSInteger currentDecodeVPos = (_decoderLoopIteration * self.totalFrames + _uncompressedDecoderPosition) % vPosModulus;
  
  // Calculate frames ahead
  NSInteger framesAhead = (currentDecodeVPos - currentVirtualPos + vPosModulus) % vPosModulus;
  NSInteger framesToDecode = self.streamingPrefetchAhead - framesAhead;
  
  if (framesToDecode <= 0) return;
  
  // Dispatch decode work to background queue
  dispatch_async(_astcDecodeQueue, ^{
    for (NSInteger i = 0; i < framesToDecode && self->_mmapData; i++) {
      NSInteger frameIndex = self->_uncompressedDecoderPosition;
      NSInteger virtualPos = (self->_decoderLoopIteration * self.totalFrames + frameIndex) % vPosModulus;
      
      // Skip if already decoded
      if (self->_decodedRgbaCache[@(virtualPos)]) {
        self->_uncompressedDecoderPosition++;
        if (self->_uncompressedDecoderPosition >= self.totalFrames) {
          self->_uncompressedDecoderPosition = 0;
          self->_decoderLoopIteration++;
        }
        continue;
      }
      
      // Decode ASTC to RGBA
      const uint8_t *data = (const uint8_t *)self->_mmapData;
      uint64_t frameOffset = self->_streamLevel0Offset + (uint64_t)frameIndex * self->_streamBytesPerLayer;
      const uint8_t *frameData = data + frameOffset;
      
      uint32_t bw = self->_astcBlockWidth;
      uint32_t bh = self->_astcBlockHeight;
      uint32_t blocksX = ((uint32_t)self.frameWidth + bw - 1) / bw;
      uint32_t blocksY = ((uint32_t)self.frameHeight + bh - 1) / bh;
      NSMutableData *rgbaData = [NSMutableData dataWithLength:self.frameWidth * self.frameHeight * 4];
      uint8_t *rgba = (uint8_t *)rgbaData.mutableBytes;
      
      for (uint32_t by = 0; by < blocksY; by++) {
        for (uint32_t bx = 0; bx < blocksX; bx++) {
          const uint8_t *block = frameData + (by * blocksX + bx) * 16;
          uint8_t blockPixels[12 * 12 * 4]; // max ASTC block size
          basisu::astc::decompress(blockPixels, block, false, bw, bh);
          
          for (uint32_t y = 0; y < bh && (by * bh + y) < self.frameHeight; y++) {
            for (uint32_t x = 0; x < bw && (bx * bw + x) < self.frameWidth; x++) {
              uint32_t dstX = bx * bw + x;
              uint32_t dstY = by * bh + y;
              uint8_t *dst = rgba + (dstY * self.frameWidth + dstX) * 4;
              uint8_t *src = blockPixels + (y * bw + x) * 4;
              memcpy(dst, src, 4);
            }
          }
        }
      }
      
      // Cache the decoded frame
      @synchronized(self->_decodedRgbaCache) {
        self->_decodedRgbaCache[@(virtualPos)] = rgbaData;
      }
      
      self->_uncompressedDecoderPosition++;
      if (self->_uncompressedDecoderPosition >= self.totalFrames) {
        self->_uncompressedDecoderPosition = 0;
        self->_decoderLoopIteration++;
      }
    }
  });
}

// Upload pre-decoded RGBA frame to GPU (fast, main thread safe)
- (void)uploadDecodedFrameToGPU:(NSInteger)virtualPos slot:(NSInteger)slot
{
  NSData *rgbaData;
  @synchronized(_decodedRgbaCache) {
    rgbaData = _decodedRgbaCache[@(virtualPos)];
  }
  
  if (!rgbaData || !_textureArray) return;
  
  MTLRegion region = MTLRegionMake2D(0, 0, self.frameWidth, self.frameHeight);
  [_textureArray replaceRegion:region
                   mipmapLevel:0
                         slice:slot
                     withBytes:rgbaData.bytes
                   bytesPerRow:self.frameWidth * 4
                 bytesPerImage:self.frameWidth * self.frameHeight * 4];
}

// Evict old decoded frames from cache
- (void)evictOldDecodedFrames:(NSInteger)currentVirtualPos
{
  if (!_decodedRgbaCache) return;
  
  NSInteger vPosModulus = self.totalFrames * 2;
  NSMutableArray *toRemove = [NSMutableArray array];
  
  @synchronized(_decodedRgbaCache) {
    for (NSNumber *virtualPos in _decodedRgbaCache.allKeys) {
      NSInteger vPos = [virtualPos integerValue];
      NSInteger distance = (vPos - currentVirtualPos + vPosModulus) % vPosModulus;
      BOOL isBehind = distance > (NSInteger)(vPosModulus - 2);
      BOOL isAhead = distance <= self.streamingCacheSize;
      if (!isBehind && !isAhead) {
        [toRemove addObject:virtualPos];
      }
    }
    
    for (NSNumber *virtualPos in toRemove) {
      [_decodedRgbaCache removeObjectForKey:virtualPos];
    }
  }
}

- (void)applyMetadataAndLoadTextures:(NSDictionary *)metadata sourcePath:(NSString *)sourcePath
{
  
  // Parse metadata (support both new 'part' and legacy 'strip' terminology)
  self.totalFrames = [metadata[@"frameCount"] integerValue];
  self.framesPerRow = [metadata[@"framesPerRow"] integerValue] ?: 1;
  self.framesPerPart = [metadata[@"framesPerPart"] integerValue] ?: [metadata[@"framesPerStrip"] integerValue] ?: self.totalFrames;
  self.partCount = [metadata[@"partCount"] integerValue] ?: [metadata[@"stripCount"] integerValue] ?: 1;
  self.frameWidth = [metadata[@"frameWidth"] integerValue];
  self.frameHeight = [metadata[@"frameHeight"] integerValue];
  self.frameRate = [metadata[@"frameRate"] floatValue] ?: 30.0;
  self.frameInterval = 1.0 / self.frameRate;
  _partsMetadata = metadata[@"parts"] ?: metadata[@"strips"];
  
  // Check if this is native ASTC mode
  NSString *mode = metadata[@"mode"];
  BOOL isNativeASTC = [mode isEqualToString:@"astc"];
  
  // Check for texture array packing mode
  NSString *packingMode = metadata[@"packingMode"];
  _useTextureArray = [packingMode isEqualToString:@"array"];
  _textureArrayLayerCount = [metadata[@"layerCount"] integerValue] ?: self.totalFrames;
  
  AVG_VLOG(@"Metadata: %ld frames, %ldx%ld, %.1f fps, %ld parts, mode=%@, packing=%@",
        (long)self.totalFrames, (long)self.frameWidth, (long)self.frameHeight,
        self.frameRate, (long)self.partCount, mode ?: @"uastc", packingMode ?: @"atlas");
  
  // Load textures based on packing mode
  if (_useTextureArray) {
    [self loadTextureArray:metadata sourcePath:sourcePath isNativeASTC:isNativeASTC];
  } else {
    [self loadPartTextures:metadata sourcePath:sourcePath isNativeASTC:isNativeASTC];
  }
}

/**
 * Decode ASTC compressed data to RGBA using proper decoder.
 * Uses basisu::astc::decompress from astc_decomp.cpp
 * Supports all ASTC block sizes via _astcBlockWidth/_astcBlockHeight.
 */
- (NSData *)decodeASTCToRGBA:(NSData *)astcData width:(uint32_t)width height:(uint32_t)height
{
  const uint8_t *blocks = (const uint8_t *)[astcData bytes];
  const uint32_t bw = _astcBlockWidth;
  const uint32_t bh = _astcBlockHeight;
  uint32_t blocksX = (width + bw - 1) / bw;
  uint32_t blocksY = (height + bh - 1) / bh;
  
  NSMutableData *rgbaData = [NSMutableData dataWithLength:width * height * 4];
  uint8_t *rgba = (uint8_t *)[rgbaData mutableBytes];
  memset(rgba, 0, width * height * 4);
  
  // Temporary buffer for one decoded block (max 12x12)
  uint8_t blockPixels[12 * 12 * 4];
  
  for (uint32_t by = 0; by < blocksY; by++) {
    for (uint32_t bx = 0; bx < blocksX; bx++) {
      const uint8_t *block = blocks + (by * blocksX + bx) * 16;
      
      // Use proper ASTC decoder (sRGB = false for linear decoding)
      bool success = basisu::astc::decompress(blockPixels, block, false, bw, bh);
      if (!success) {
        // Fill with magenta to indicate decode error
        uint32_t pixelsInBlock = bw * bh;
        for (uint32_t i = 0; i < pixelsInBlock; i++) {
          blockPixels[i * 4 + 0] = 255;
          blockPixels[i * 4 + 1] = 0;
          blockPixels[i * 4 + 2] = 255;
          blockPixels[i * 4 + 3] = 255;
        }
      }
      
      // Copy block to output
      for (uint32_t y = 0; y < bh && (by * bh + y) < height; y++) {
        for (uint32_t x = 0; x < bw && (bx * bw + x) < width; x++) {
          uint32_t dstX = bx * bw + x;
          uint32_t dstY = by * bh + y;
          uint8_t *dst = rgba + (dstY * width + dstX) * 4;
          uint8_t *src = blockPixels + (y * bw + x) * 4;
          dst[0] = src[0];
          dst[1] = src[1];
          dst[2] = src[2];
          dst[3] = src[3];
        }
      }
    }
  }
  
  AVG_VLOG(@"Decoded ASTC %ux%u (%u x %u blocks, block=%ux%u) to RGBA", width, height, blocksX, blocksY, bw, bh);
  return rgbaData;
}

/**
 * Load native ASTC data directly from KTX2 file without transcoding.
 * Handles Zstandard decompression if needed.
 */
- (NSData *)loadNativeASTCFromKTX2:(NSData *)ktx2Data width:(uint32_t *)outWidth height:(uint32_t *)outHeight
{
  if (ktx2Data.length < 80) return nil;
  
  const uint8_t *bytes = (const uint8_t *)[ktx2Data bytes];
  
  // Parse KTX2 header (little-endian)
  uint32_t vkFormat = *(uint32_t *)(bytes + 12);
  uint32_t pixelWidth = *(uint32_t *)(bytes + 20);
  uint32_t pixelHeight = *(uint32_t *)(bytes + 24);
  uint32_t supercompressionScheme = *(uint32_t *)(bytes + 44);
  
  *outWidth = pixelWidth;
  *outHeight = pixelHeight;
  
  AVG_VLOG(@"Native ASTC - VkFormat=%u, %ux%u, supercompression=%u",
        vkFormat, pixelWidth, pixelHeight, supercompressionScheme);
  
  // Configure ASTC format from vkFormat (sets _metalPixelFormat, _astcBlockWidth, _astcBlockHeight)
  [self configureASTCFormatFromVkFormat:vkFormat];
  
  // Read level index (after 80-byte header)
  // Level index: array of { byteOffset: uint64, byteLength: uint64, uncompressedByteLength: uint64 }
  uint64_t level0ByteOffset = *(uint64_t *)(bytes + 80);
  uint64_t level0ByteLength = *(uint64_t *)(bytes + 88);
  uint64_t level0UncompressedLength = *(uint64_t *)(bytes + 96);
  
  AVG_VLOG(@"Level 0 - offset=%llu, length=%llu, uncompressed=%llu",
        level0ByteOffset, level0ByteLength, level0UncompressedLength);
  
  if (level0ByteOffset + level0ByteLength > ktx2Data.length) {
    NSLog(@"AlphaVideoGpuView: Invalid level data bounds");
    return nil;
  }
  
  NSData *textureData = nil;
  
  if (supercompressionScheme == 2) {
    // Zstandard compression - use basis_universal's bundled zstd
    AVG_VLOG(@"Decompressing Zstandard data...");
    
    const uint8_t *compressedData = bytes + level0ByteOffset;
    size_t compressedSize = (size_t)level0ByteLength;
    size_t uncompressedSize = (size_t)level0UncompressedLength;
    
    NSMutableData *decompressedData = [NSMutableData dataWithLength:uncompressedSize];
    
    size_t decodedSize = ZSTD_decompress(
      (uint8_t *)[decompressedData mutableBytes],
      uncompressedSize,
      compressedData,
      compressedSize
    );
    
    if (ZSTD_isError(decodedSize)) {
      NSLog(@"AlphaVideoGpuView: Zstd decompression failed: %s", ZSTD_getErrorName(decodedSize));
      return nil;
    }
    
    if (decodedSize != uncompressedSize) {
      NSLog(@"AlphaVideoGpuView: Zstd decompression size mismatch: expected %zu, got %zu", uncompressedSize, decodedSize);
      return nil;
    }
    
    textureData = decompressedData;
    AVG_VLOG(@"Decompressed %zu bytes", decodedSize);
  } else if (supercompressionScheme == 0) {
    // No supercompression
    textureData = [NSData dataWithBytes:(bytes + level0ByteOffset) length:(size_t)level0ByteLength];
  } else {
    NSLog(@"AlphaVideoGpuView: Unsupported supercompression scheme: %u", supercompressionScheme);
    return nil;
  }
  
  return textureData;
}

/**
 * Transcode UASTC KTX2 data using Basis Universal transcoder.
 * Returns transcoded texture data ready for GPU upload.
 */
- (NSData *)transcodeKTX2:(NSData *)ktx2Data width:(uint32_t *)outWidth height:(uint32_t *)outHeight
{
  basist::ktx2_transcoder transcoder;
  if (!transcoder.init((const uint8_t*)[ktx2Data bytes], (uint32_t)[ktx2Data length])) {
    NSLog(@"AlphaVideoGpuView: Failed to init KTX2 transcoder");
    return nil;
  }
  
  if (!transcoder.start_transcoding()) {
    NSLog(@"AlphaVideoGpuView: Failed to start transcoding");
    return nil;
  }
  
  uint32_t width = transcoder.get_width();
  uint32_t height = transcoder.get_height();
  *outWidth = width;
  *outHeight = height;
  
  // Calculate transcoded data size
  // Note: Basis Universal transcoding always targets ASTC 4x4
  uint32_t dataSize;
  if (_useCompressedFormat) {
    uint32_t blocksX = (width + 3) / 4;  // Always 4x4 for Basis transcoding
    uint32_t blocksY = (height + 3) / 4;
    dataSize = blocksX * blocksY * 16;
  } else {
    dataSize = width * height * 4;
  }
  
  // Allocate and transcode
  NSMutableData *transcodedData = [NSMutableData dataWithLength:dataSize];
  if (!transcoder.transcode_image_level(0, 0, 0, (uint8_t*)[transcodedData mutableBytes], dataSize, _targetFormat, 0)) {
    NSLog(@"AlphaVideoGpuView: Failed to transcode image");
    return nil;
  }
  
  AVG_VLOG(@"Transcoded UASTC %ux%u (%u bytes)", width, height, dataSize);
  return transcodedData;
}

/**
 * The URI of a part file: from the parts prop, else the file the metadata names (or
 * <name>_<index>.ktx2) in the source's folder. Nil when the source has no folder.
 */
- (NSString *)partURIAtIndex:(NSInteger)partIndex partsArray:(NSArray *)partsArray useExplicitParts:(BOOL)useExplicitParts
{
  if (useExplicitParts && partIndex < (NSInteger)self.partURIs.count) {
    return self.partURIs[partIndex];
  }
  NSString *fileName = partIndex < (NSInteger)partsArray.count ? partsArray[partIndex][@"file"] : nil;
  if (!fileName) {
    NSString *name = [[_sourceUri componentsSeparatedByString:@"?"].firstObject lastPathComponent];
    name = [name stringByReplacingOccurrencesOfString:@"_0.ktx2" withString:@""];
    name = [name stringByReplacingOccurrencesOfString:@".ktx2" withString:@""];
    fileName = [NSString stringWithFormat:@"%@_%ld.ktx2", name, (long)partIndex];
  }
  return AVGSiblingURI(_sourceUri, fileName);
}

/**
 * The cache key for a downloaded part: its own key from the parts prop, else the source's key
 * with the index before the extension (glow.ktx2 becomes glow_1.ktx2).
 */
- (NSString *)partCacheKeyAtIndex:(NSInteger)partIndex useExplicitParts:(BOOL)useExplicitParts
{
  if (useExplicitParts && partIndex < (NSInteger)self.partCacheKeys.count) {
    NSString *key = self.partCacheKeys[partIndex];
    if ([key isKindOfClass:[NSString class]] && key.length > 0) return key;
  }
  NSString *key = _loadedCacheKey;
  if (key.length == 0) return nil;
  NSString *ext = key.pathExtension;
  NSString *base = ext.length > 0 ? key.stringByDeletingPathExtension : key;
  NSString *indexed = [NSString stringWithFormat:@"%@_%ld", base, (long)partIndex];
  return ext.length > 0 ? [indexed stringByAppendingPathExtension:ext] : indexed;
}

#pragma mark - Texture Array Loading

/**
 * Load a KTX2 texture array (one frame per layer).
 * Uses Metal's MTLTextureType2DArray for efficient frame switching.
 */
- (void)loadTextureArray:(NSDictionary *)metadata sourcePath:(NSString *)filePath isNativeASTC:(BOOL)isNativeASTC
{
  _cachedMetadata = metadata;
  _isNativeASTC = isNativeASTC;
  
  // Check for streaming mode from stream prop (not metadata)
  BOOL streamMode = self.stream;
  
  dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH, 0), ^{
    // Load the KTX2 file
    NSData *ktx2Data = [NSData dataWithContentsOfFile:filePath options:NSDataReadingMappedIfSafe error:nil];
    
    if (!ktx2Data || ktx2Data.length < 104) {
      dispatch_async(dispatch_get_main_queue(), ^{
        [self notifyError:@"Failed to load texture array KTX2 file"];
      });
      return;
    }
    
    // Parse KTX2 header
    const uint8_t *bytes = (const uint8_t *)[ktx2Data bytes];
    uint32_t vkFormat = *(uint32_t *)(bytes + 12);
    uint32_t width = *(uint32_t *)(bytes + 20);
    uint32_t height = *(uint32_t *)(bytes + 24);
    uint32_t layerCount = *(uint32_t *)(bytes + 32);
    uint32_t supercompressionScheme = *(uint32_t *)(bytes + 44);
    
    // Level 0 index (at byte 80)
    uint64_t level0Offset = *(uint64_t *)(bytes + 80);
    uint64_t level0Length = *(uint64_t *)(bytes + 88);
    uint64_t level0UncompressedLength = *(uint64_t *)(bytes + 96);
    
    // Configure ASTC format from vkFormat
    dispatch_sync(dispatch_get_main_queue(), ^{
      [self configureASTCFormatFromVkFormat:vkFormat];
    });
    
    AVG_VLOG(@"Texture array: %ux%u, %u layers, supercompression=%u, streamMode=%d, vkFormat=%u",
          width, height, layerCount, supercompressionScheme, streamMode, vkFormat);
    
    // Check for internal zstd streaming mode
    if (streamMode && supercompressionScheme == 2 && filePath) {
      AVG_VLOG(@"Using internal zstd streaming mode");
      [self loadInternalZstdStreamingTextureArray:filePath
                                            width:width
                                           height:height
                                       layerCount:layerCount
                                     level0Offset:level0Offset
                                     level0Length:level0Length
                                         metadata:metadata];
      return;
    }
    
    // Extract ASTC data (non-streaming path)
    NSData *astcData = nil;
    
    if (supercompressionScheme == 2) {
      // Zstd compressed - decompress using zstd (all at once)
      const void *compressedData = bytes + level0Offset;
      size_t compressedSize = (size_t)level0Length;
      size_t decompressedSize = (size_t)level0UncompressedLength;
      
      NSMutableData *decompressedData = [NSMutableData dataWithLength:decompressedSize];
      size_t result = ZSTD_decompress([decompressedData mutableBytes], decompressedSize,
                                       compressedData, compressedSize);
      
      if (ZSTD_isError(result)) {
        NSLog(@"AlphaVideoGpuView: Zstd decompression failed: %s", ZSTD_getErrorName(result));
        dispatch_async(dispatch_get_main_queue(), ^{
          [self notifyError:@"Failed to decompress texture array"];
        });
        return;
      }
      
      astcData = decompressedData;
      AVG_VLOG(@"Decompressed %llu -> %zu bytes", level0Length, result);
    } else {
      // Uncompressed
      astcData = [NSData dataWithBytes:bytes + level0Offset length:(NSUInteger)level0Length];
    }
    
    // Calculate bytes per layer using current ASTC block dimensions
    uint32_t blocksX = (width + self->_astcBlockWidth - 1) / self->_astcBlockWidth;
    uint32_t blocksY = (height + self->_astcBlockHeight - 1) / self->_astcBlockHeight;
    uint32_t bytesPerLayer = blocksX * blocksY * 16;
    
    AVG_VLOG(@"ASTC data: %lu bytes, %u bytes/layer (ASTC %ux%u), %u layers",
          (unsigned long)astcData.length, bytesPerLayer, self->_astcBlockWidth, self->_astcBlockHeight, layerCount);
    
    // Pre-decode ASTC to RGBA on background thread for simulator (avoid main thread freeze)
    NSMutableArray<NSData *> *decodedLayers = nil;
    if (!self->_useCompressedFormat) {
      AVG_VLOG(@"Pre-decoding %u ASTC layers to RGBA on background thread (simulator)", layerCount);
      decodedLayers = [NSMutableArray arrayWithCapacity:layerCount];
      const uint8_t *astcBytes = (const uint8_t *)[astcData bytes];
      
      for (uint32_t layer = 0; layer < layerCount; layer++) {
        const uint8_t *layerData = astcBytes + (layer * bytesPerLayer);
        NSData *layerASTCData = [NSData dataWithBytesNoCopy:(void *)layerData length:bytesPerLayer freeWhenDone:NO];
        NSData *rgbaData = [self decodeASTCToRGBA:layerASTCData width:width height:height];
        if (rgbaData) {
          [decodedLayers addObject:rgbaData];
        } else {
          // Create empty placeholder to maintain layer indices
          [decodedLayers addObject:[NSData data]];
        }
      }
      AVG_VLOG(@"Pre-decode complete, uploading to GPU");
    }
    
    // Create texture array and upload on main thread (fast - just memcpy)
    dispatch_async(dispatch_get_main_queue(), ^{
      if (self->_isDestroyed) return;
      
      // Create texture array descriptor
      // For non-streaming bulk load, use Private storage for better GPU read performance
      // Create staging texture with Shared storage, then blit to Private final texture
      MTLTextureDescriptor *descriptor = [[MTLTextureDescriptor alloc] init];
      descriptor.textureType = MTLTextureType2DArray;
      descriptor.pixelFormat = self->_useCompressedFormat ? self->_metalPixelFormat : MTLPixelFormatRGBA8Unorm;
      descriptor.width = width;
      descriptor.height = height;
      descriptor.arrayLength = layerCount;
      descriptor.mipmapLevelCount = 1;
      descriptor.usage = MTLTextureUsageShaderRead;
      
      // Use Private storage for final texture (GPU-optimized layout)
      // Only use Private storage for compressed format (real device) - simulator needs Shared for CPU writes
      BOOL usePrivateStorage = self->_useCompressedFormat;
      
      id<MTLTexture> stagingTexture = nil;
      if (usePrivateStorage) {
        // Create staging texture with Shared storage for CPU writes
        descriptor.storageMode = MTLStorageModeShared;
        stagingTexture = [self.device newTextureWithDescriptor:descriptor];
        
        // Create final texture with Private storage for GPU-optimized reads
        descriptor.storageMode = MTLStorageModePrivate;
        self->_textureArray = [self.device newTextureWithDescriptor:descriptor];
      } else {
        descriptor.storageMode = MTLStorageModeShared;
        self->_textureArray = [self.device newTextureWithDescriptor:descriptor];
      }
      
      if (!self->_textureArray || (usePrivateStorage && !stagingTexture)) {
        [self notifyError:@"Failed to create texture array"];
        return;
      }
      
      // Upload each layer to staging texture (or directly to final if not using Private)
      id<MTLTexture> uploadTarget = usePrivateStorage ? stagingTexture : self->_textureArray;
      const uint8_t *astcBytes = (const uint8_t *)[astcData bytes];
      
      for (uint32_t layer = 0; layer < layerCount; layer++) {
        MTLRegion region = MTLRegionMake2D(0, 0, width, height);
        
        if (self->_useCompressedFormat) {
          // Upload compressed ASTC directly (real device)
          // bytesPerRow must be 0 for compressed textures per Apple docs
          const uint8_t *layerData = astcBytes + (layer * bytesPerLayer);
          [uploadTarget replaceRegion:region
                          mipmapLevel:0
                                slice:layer
                            withBytes:layerData
                          bytesPerRow:0
                        bytesPerImage:bytesPerLayer];
        } else {
          // Upload pre-decoded RGBA (simulator) - uses Shared storage, no staging needed
          if (layer < decodedLayers.count && decodedLayers[layer].length > 0) {
            [uploadTarget replaceRegion:region
                            mipmapLevel:0
                                  slice:layer
                              withBytes:[decodedLayers[layer] bytes]
                            bytesPerRow:width * 4
                          bytesPerImage:width * height * 4];
          }
        }
      }
      
      // Blit from staging to Private final texture if using Private storage
      if (usePrivateStorage && stagingTexture) {
        id<MTLCommandQueue> cmdQueue = self->_blitCommandQueue ?: [self.device newCommandQueue];
        id<MTLCommandBuffer> cmdBuf = [cmdQueue commandBuffer];
        id<MTLBlitCommandEncoder> blit = [cmdBuf blitCommandEncoder];
        
        // Copy all layers from staging to final
        [blit copyFromTexture:stagingTexture toTexture:self->_textureArray];
        
        [blit endEncoding];
        [cmdBuf commit];
        [cmdBuf waitUntilCompleted];  // Wait for blit to complete before releasing staging
        
        // Staging texture will be released when it goes out of scope
        AVG_VLOG(@"Blitted staging texture to Private storage");
      }
      
      self->_textureArrayLayerCount = layerCount;
      self->_isSourceLoaded = YES;
      self->_isSourceLoading = NO;
      
      AVG_VLOG(@"✓ Texture array loaded: %ux%u, %u layers (Private storage: %@)", 
            width, height, layerCount, usePrivateStorage ? @"YES" : @"NO");
      
      [self notifyLoad];
      [self notifyStateChange:@"ready"];
      
      if (self.autoPlay && !self.playbackPaused) {
        [self play];
      }
    });
  });
}

#pragma mark - Internal Zstd Streaming

/**
 * Load a KTX2 texture array with internal zstd compression using streaming decompression.
 * This is similar to loadStreamingKTX2FromFile but for internal zstd (supercompressionScheme=2)
 * where the KTX2 header is uncompressed and only level0 data is zstd compressed.
 */
- (void)loadInternalZstdStreamingTextureArray:(NSString *)filePath
                                        width:(uint32_t)width
                                       height:(uint32_t)height
                                   layerCount:(uint32_t)layerCount
                                 level0Offset:(uint64_t)level0Offset
                                 level0Length:(uint64_t)level0Length
                                     metadata:(NSDictionary *)metadata
{
  AVG_VLOG(@"Starting internal zstd streaming: %@", filePath);
  AVG_VLOG(@"Internal zstd: %ux%u, %u layers, offset=%llu, compressedSize=%llu",
        width, height, layerCount, level0Offset, level0Length);
  
  // Open file and mmap only the compressed data portion
  int fd = open([filePath UTF8String], O_RDONLY);
  if (fd < 0) {
    dispatch_async(dispatch_get_main_queue(), ^{
      [self notifyError:@"Failed to open file for internal zstd streaming"];
    });
    return;
  }
  
  // Get file size for validation
  struct stat st;
  if (fstat(fd, &st) < 0) {
    close(fd);
    dispatch_async(dispatch_get_main_queue(), ^{
      [self notifyError:@"Failed to stat file"];
    });
    return;
  }
  
  // Mmap the compressed data portion (page-aligned)
  long pageSize = sysconf(_SC_PAGE_SIZE);
  off_t alignedOffset = (level0Offset / pageSize) * pageSize;
  size_t offsetAdjustment = level0Offset - alignedOffset;
  size_t mmapLength = level0Length + offsetAdjustment;
  
  void *mmapData = mmap(NULL, mmapLength, PROT_READ, MAP_PRIVATE, fd, alignedOffset);
  if (mmapData == MAP_FAILED) {
    close(fd);
    dispatch_async(dispatch_get_main_queue(), ^{
      [self notifyError:@"Failed to mmap internal zstd data"];
    });
    return;
  }
  
  // Advise kernel for sequential access
  madvise(mmapData, mmapLength, MADV_SEQUENTIAL);
  
  // Create ZSTD_DStream for streaming decompression
  ZSTD_DStream *dstream = ZSTD_createDStream();
  if (!dstream) {
    munmap(mmapData, mmapLength);
    close(fd);
    dispatch_async(dispatch_get_main_queue(), ^{
      [self notifyError:@"Failed to create ZSTD_DStream"];
    });
    return;
  }
  
  size_t initResult = ZSTD_initDStream(dstream);
  if (ZSTD_isError(initResult)) {
    ZSTD_freeDStream(dstream);
    munmap(mmapData, mmapLength);
    close(fd);
    dispatch_async(dispatch_get_main_queue(), ^{
      [self notifyError:@"Failed to init ZSTD_DStream"];
    });
    return;
  }
  
  // Calculate bytes per layer using current ASTC block dimensions
  uint32_t bytesPerLayer = [self astcBytesPerLayerForWidth:width height:height];
  
  // Determine memory strategy
  BOOL useVramStrategy = [self.memoryStrategy isEqualToString:@"vram"] || 
                         [self.memoryStrategy isEqualToString:@"auto"];
  NSInteger gpuCacheSize = useVramStrategy ? 
      MIN(self.streamingCacheSize, (NSInteger)layerCount) : 
      MIN(self.streamingRingSize, (NSInteger)layerCount);
  
  AVG_VLOG(@"Internal zstd streaming params: strategy=%@, gpuCache=%ld, bytesPerLayer=%u (ASTC %ux%u)",
        self.memoryStrategy, (long)gpuCacheSize, bytesPerLayer, _astcBlockWidth, _astcBlockHeight);
  
  // Store streaming state
  const uint8_t *compressedDataPtr = (const uint8_t *)mmapData + offsetAdjustment;
  
  // Setup on main thread
  dispatch_sync(dispatch_get_main_queue(), ^{
    // Apply metadata
    self.totalFrames = layerCount;
    self.framesPerRow = 1;
    self.framesPerPart = layerCount;
    self.partCount = 1;
    self.frameWidth = width;
    self.frameHeight = height;
    self.frameRate = [metadata[@"frameRate"] floatValue] ?: 30.0;
    self.frameInterval = 1.0 / self.frameRate;
    
    self->_useTextureArray = YES;
    self->_textureArrayLayerCount = layerCount;
    self->_streamingRingBufferMode = YES;
    self->_streamingRingBufferSize = gpuCacheSize;
    self->_useVramStrategy = useVramStrategy;
    
    // Initialize ring buffer tracking
    self->_ringSlots = [NSMutableArray arrayWithCapacity:gpuCacheSize];
    for (NSInteger i = 0; i < gpuCacheSize; i++) {
      [self->_ringSlots addObject:@(-1)];
    }
    self->_frameCache = [NSMutableDictionary dictionary];
    self->_decoderPosition = 0;
    self->_decoderLoopIteration = 0;
    self->_playbackLoopIteration = 0;
    
    // Store streaming state - internal zstd doesn't need header skip
    self->_dstream = dstream;
    self->_compressedData = [NSData dataWithBytesNoCopy:(void *)compressedDataPtr 
                                                 length:level0Length 
                                           freeWhenDone:NO];
    self->_compressedDataPos = 0;
    self->_frameAccumulator = [NSMutableData data];
    self->_streamBytesPerLayer = bytesPerLayer;
    self->_streamLevel0Offset = 0;  // No header to skip - we start at level0 data directly
    self->_streamHeaderSkipped = YES;  // Header already skipped
    
    // Store mmap info for cleanup and loop restart
    self->_mmapFd = fd;
    self->_mmapData = mmapData;
    self->_mmapSize = mmapLength;
    
    // Create texture array with ring buffer size
    MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:self->_metalPixelFormat
                                                                                         width:width
                                                                                        height:height
                                                                                     mipmapped:NO];
    descriptor.textureType = MTLTextureType2DArray;
    descriptor.arrayLength = gpuCacheSize;
    descriptor.usage = MTLTextureUsageShaderRead;
    
    self->_textureArray = [self.device newTextureWithDescriptor:descriptor];
    
    if (!self->_textureArray) {
      [self notifyError:@"Failed to create texture array for internal zstd streaming"];
      return;
    }
    
    AVG_VLOG(@"Created internal zstd streaming texture array: %ux%u, %ld GPU slots",
          width, height, (long)gpuCacheSize);
    
    // Pre-decode initial frames
    [self decodeFramesAhead:0];
    
    self->_isSourceLoaded = YES;
    self->_isSourceLoading = NO;
    [self notifyLoad];
    [self notifyStateChange:@"ready"];
    
    if (self.autoPlay && !self.playbackPaused) {
      [self play];
    }
  });
  
  AVG_VLOG(@"Internal zstd streaming setup complete");
}

- (void)loadPartTextures:(NSDictionary *)metadata sourcePath:(NSString *)sourcePath isNativeASTC:(BOOL)isNativeASTC
{
  _cachedMetadata = metadata;
  _isNativeASTC = isNativeASTC;
  
  // Perform loading and transcoding on background thread to avoid blocking main thread
  dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH, 0), ^{
    NSMutableArray<NSDictionary *> *loadedParts = [NSMutableArray array];
    NSArray *partsArray = metadata[@"parts"] ?: metadata[@"strips"];
    
    // Check if we're loading from GLB container
    if (self->_isGLBContainer && self->_glbKtx2Parts && self->_glbKtx2Parts.count > 0) {
      AVG_VLOG(@"Loading %lu KTX2 parts from GLB container...", (unsigned long)self->_glbKtx2Parts.count);
      
      for (NSInteger i = 0; i < (NSInteger)self->_glbKtx2Parts.count; i++) {
        NSData *ktx2Data = self->_glbKtx2Parts[i];
        AVG_VLOG(@"GLB part %ld size: %lu bytes", (long)i, (unsigned long)ktx2Data.length);
        
        uint32_t width = 0, height = 0;
        NSData *textureData = nil;
        uint32_t blocksX = 0, blocksY = 0;
        
        if (isNativeASTC) {
          AVG_VLOG(@"Using native ASTC loader for GLB part %ld", (long)i);
          textureData = [self loadNativeASTCFromKTX2:ktx2Data width:&width height:&height];
          
          if (!textureData) {
            NSLog(@"AlphaVideoGpuView: Failed to load native ASTC for GLB part %ld", (long)i);
            continue;
          }
          
          blocksX = (width + self->_astcBlockWidth - 1) / self->_astcBlockWidth;
          blocksY = (height + self->_astcBlockHeight - 1) / self->_astcBlockHeight;
          
          if (!self->_useCompressedFormat) {
            AVG_VLOG(@"ASTC not supported - decoding to RGBA");
            textureData = [self decodeASTCToRGBA:textureData width:width height:height];
            if (!textureData) {
              NSLog(@"AlphaVideoGpuView: Failed to decode ASTC to RGBA for GLB part %ld", (long)i);
              continue;
            }
          }
        } else {
          basist::ktx2_transcoder transcoder;
          if (!transcoder.init((const uint8_t*)[ktx2Data bytes], (uint32_t)[ktx2Data length])) {
            NSLog(@"AlphaVideoGpuView: Failed to init KTX2 transcoder for GLB part %ld", (long)i);
            continue;
          }
          
          if (!transcoder.start_transcoding()) {
            NSLog(@"AlphaVideoGpuView: Failed to start transcoding for GLB part %ld", (long)i);
            continue;
          }
          
          width = transcoder.get_width();
          height = transcoder.get_height();
          
          uint32_t dataSize;
          if (self->_useCompressedFormat) {
            blocksX = (width + 3) / 4;  // Always 4x4 for Basis transcoding
            blocksY = (height + 3) / 4;
            dataSize = blocksX * blocksY * 16;
          } else {
            dataSize = width * height * 4;
          }
          
          NSMutableData *transcodedData = [NSMutableData dataWithLength:dataSize];
          if (!transcoder.transcode_image_level(0, 0, 0, (void *)[transcodedData mutableBytes], dataSize / (self->_useCompressedFormat ? 16 : 4), self->_targetFormat)) {
            NSLog(@"AlphaVideoGpuView: Failed to transcode GLB part %ld", (long)i);
            continue;
          }
          
          textureData = transcodedData;
        }
        
        [loadedParts addObject:@{
          @"data": textureData,
          @"width": @(width),
          @"height": @(height),
          @"blocksX": @(blocksX),
          @"blocksY": @(blocksY)
        }];
        
        AVG_VLOG(@"Loaded GLB part %ld: %ux%u", (long)i, width, height);
      }
    } else {
      // Load from separate files (original behavior)
      BOOL useExplicitParts = self.partURIs.count > 1;
      NSInteger actualPartCount = useExplicitParts ? self.partURIs.count : self.partCount;
      
      for (NSInteger i = 0; i < actualPartCount; i++) {
        // A single-file atlas is its own part 0
        NSString *partUri = actualPartCount > 1
            ? [self partURIAtIndex:i partsArray:partsArray useExplicitParts:useExplicitParts]
            : self->_sourceUri;
        NSString *partPath = nil;
        NSString *resolveError = nil;
        if (!partUri) {
          resolveError = @"no folder to find it in; pass the parts prop";
        } else if ([partUri isEqualToString:self->_sourceUri]) {
          partPath = sourcePath;
        } else {
          partPath = AVGResolveSource(partUri, [self partCacheKeyAtIndex:i useExplicitParts:useExplicitParts], &resolveError);
        }
        
        AVG_VLOG(@"Loading part %ld: %@%@", (long)i, partUri, useExplicitParts ? @" (explicit)" : @"");
        
        NSData *ktx2Data = partPath ? [NSData dataWithContentsOfFile:partPath] : nil;
        if (resolveError) {
          NSLog(@"AlphaVideoGpuView: Can't load part %ld (%@): %@", (long)i, partUri, resolveError);
        }
        
        if (!ktx2Data) {
          NSLog(@"AlphaVideoGpuView: Failed to load part file: %@", partPath);
          continue;
        }
        
        AVG_VLOG(@"Part %ld file size: %lu bytes", (long)i, (unsigned long)ktx2Data.length);
        
        uint32_t width = 0, height = 0;
        NSData *textureData = nil;
        uint32_t blocksX = 0, blocksY = 0;
        
        if (isNativeASTC) {
          // Native ASTC mode - load directly without transcoding
          AVG_VLOG(@"Using native ASTC loader for part %ld", (long)i);
          textureData = [self loadNativeASTCFromKTX2:ktx2Data width:&width height:&height];
          
          if (!textureData) {
            NSLog(@"AlphaVideoGpuView: Failed to load native ASTC for part %ld", (long)i);
            continue;
          }
          
          blocksX = (width + self->_astcBlockWidth - 1) / self->_astcBlockWidth;
          blocksY = (height + self->_astcBlockHeight - 1) / self->_astcBlockHeight;
          
          // Check if we need to decode ASTC to RGBA (simulator or unsupported device)
          if (!self->_useCompressedFormat) {
            AVG_VLOG(@"ASTC not supported - decoding to RGBA (simulator/fallback mode)");
            AVG_VLOG(@"Note: ASTC textures require a real device for optimal performance");
            textureData = [self decodeASTCToRGBA:textureData width:width height:height];
            if (!textureData) {
              NSLog(@"AlphaVideoGpuView: Failed to decode ASTC to RGBA for part %ld", (long)i);
              continue;
            }
          }
        } else {
          // UASTC mode - transcode using Basis Universal
          basist::ktx2_transcoder transcoder;
          if (!transcoder.init((const uint8_t*)[ktx2Data bytes], (uint32_t)[ktx2Data length])) {
            NSLog(@"AlphaVideoGpuView: Failed to init KTX2 transcoder for part %ld", (long)i);
            continue;
          }
          
          if (!transcoder.start_transcoding()) {
            NSLog(@"AlphaVideoGpuView: Failed to start transcoding for part %ld", (long)i);
            continue;
          }
          
          width = transcoder.get_width();
          height = transcoder.get_height();
          AVG_VLOG(@"Part %ld dimensions: %dx%d", (long)i, width, height);
          
          // Calculate transcoded data size
          uint32_t dataSize;
          
          if (self->_useCompressedFormat) {
            blocksX = (width + 3) / 4;  // Always 4x4 for Basis transcoding
            blocksY = (height + 3) / 4;
            dataSize = blocksX * blocksY * 16;
          } else {
            dataSize = width * height * 4;
          }
          
          // Allocate and transcode
          NSMutableData *transcodedData = [NSMutableData dataWithLength:dataSize];
          if (!transcoder.transcode_image_level(0, 0, 0, (uint8_t*)[transcodedData mutableBytes], dataSize, self->_targetFormat, 0)) {
            NSLog(@"AlphaVideoGpuView: Failed to transcode part %ld", (long)i);
            continue;
          }
          
          textureData = transcodedData;
        }
        
        // Store part info for main thread
        [loadedParts addObject:@{
          @"data": textureData,
          @"width": @(width),
          @"height": @(height),
          @"blocksX": @(blocksX),
          @"blocksY": @(blocksY),
          @"isNativeASTC": @(isNativeASTC)
        }];
        
        AVG_VLOG(@"%@ part %ld successfully", isNativeASTC ? @"Loaded native ASTC" : @"Transcoded", (long)i);
      }
    }
    
    // Dispatch to main thread for Metal texture creation and UI updates
    dispatch_async(dispatch_get_main_queue(), ^{
      if (self->_isDestroyed) {
        AVG_VLOG(@"View destroyed during loading, aborting");
        return;
      }
      
      NSMutableArray *textures = [NSMutableArray array];
      NSMutableArray *partWidths = [NSMutableArray array];
      NSMutableArray *partHeights = [NSMutableArray array];
      self->_transcodedData.clear();
      
      for (NSDictionary *partInfo in loadedParts) {
        NSData *data = partInfo[@"data"];
        uint32_t width = [partInfo[@"width"] unsignedIntValue];
        uint32_t height = [partInfo[@"height"] unsignedIntValue];
        uint32_t blocksX = [partInfo[@"blocksX"] unsignedIntValue];
        
        // Create Metal texture
        MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:self->_metalPixelFormat
                                                                                             width:width
                                                                                            height:height
                                                                                         mipmapped:NO];
        descriptor.usage = MTLTextureUsageShaderRead;
        id<MTLTexture> texture = [self.device newTextureWithDescriptor:descriptor];
        
        NSUInteger bytesPerRow = self->_useCompressedFormat ? (blocksX * 16) : (width * 4);
        
        [texture replaceRegion:MTLRegionMake2D(0, 0, width, height)
                   mipmapLevel:0
                     withBytes:[data bytes]
                   bytesPerRow:bytesPerRow];
        
        [textures addObject:texture];
        [partWidths addObject:@(width)];
        [partHeights addObject:@(height)];
        
        AVG_VLOG(@"Created texture %p (%dx%d)", texture, width, height);
      }
      
      self.partTextures = [textures copy];
      self->_partWidths = [partWidths copy];
      self->_partHeights = [partHeights copy];
      AVG_VLOG(@"Loaded %lu part textures total", (unsigned long)self.partTextures.count);
      
      if (self.partTextures.count == 0) {
        [self notifyError:@"No textures loaded"];
        return;
      }
      
      self->_isSourceLoaded = YES;
      self->_isSourceLoading = NO;
      self.currentTexture = self.partTextures[0];
      [self updateUniforms];
      [self notifyLoad];
      [self notifyStateChange:@"ready"];
      
      if (self.autoPlay && !self.playbackPaused && self.partTextures.count > 0) {
        [self play];
      }
    });
  });
}

- (void)play
{
  AVG_VLOG(@"play called - isPlaying=%d, totalFrames=%ld, textureCount=%lu", 
        self.isPlaying, (long)self.totalFrames, (unsigned long)self.partTextures.count);
  
  if (!self.isPlaying && self.totalFrames > 0) {
    AVG_VLOG(@"Starting playback...");
    if (_ended) {
      [self seekToFrame:0];
    }
    self.isPlaying = YES;
    self.lastFrameTime = CACurrentMediaTime();
    [self updateDisplayLink];
    
    [self notifyStateChange:@"playing"];
  }
}

- (void)pause
{
  if (self.isPlaying) {
    self.isPlaying = NO;
    [self updateDisplayLink];
    [self notifyStateChange:@"paused"];
  }
}

- (void)stop
{
  [self pause];
  [self seekToFrame:0];
  [self notifyStateChange:@"stopped"];
}

- (void)seekToFrame:(NSInteger)frame
{
  if (!_isSourceLoaded || self.totalFrames <= 0) return;
  NSInteger target = MAX(0, MIN(frame, self.totalFrames - 1));
  _ended = NO;
  if (_streamingRingBufferMode) {
    [self moveStreamTo:target];
  }
  self.currentFrame = target;
  self.lastFrameTime = CACurrentMediaTime();
  _frameOwed = YES;
  [self drawOwedFrame];
  [self updateDisplayLink];
  [self notifyFrameUpdate];
}

- (void)seekToTime:(NSTimeInterval)seconds
{
  double frame = floor(MAX(seconds, 0) * self.frameRate + 1e-6);
  [self seekToFrame:(NSInteger)MIN(frame, (double)INT32_MAX)];
}

/** Draws the frame a load or a seek owes, once it is decoded. */
- (void)drawOwedFrame
{
  if (!_frameOwed || ![self prepareShownFrame]) return;
  _frameOwed = NO;
  [self updateUniforms];
  [self setNeedsDisplay];
}

/** Runs the display link while playing, or until an owed frame is drawn. */
- (void)updateDisplayLink
{
  BOOL needed = self.isPlaying || _frameOwed;
  if (needed && !self.displayLink) {
    self.displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(updateFrame)];
    self.displayLink.preferredFramesPerSecond = (NSInteger)self.frameRate;
    [self.displayLink addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];
  } else if (!needed && self.displayLink) {
    [self.displayLink invalidate];
    self.displayLink = nil;
  }
}

// Same ranges as Android; a size of 0 would leave the ring with no slots.
- (void)setStreamingRingSize:(NSInteger)size
{
  _streamingRingSize = MAX(4, MIN(64, size));
}

- (void)setStreamingCacheSize:(NSInteger)size
{
  _streamingCacheSize = MAX(10, MIN(200, size));
}

- (void)setStreamingPrefetchAhead:(NSInteger)frames
{
  _streamingPrefetchAhead = MAX(5, MIN(100, frames));
}

- (void)setPlaybackSpeed:(CGFloat)speed
{
  _playbackSpeed = speed;
  _frameInterval = 1.0 / (_frameRate * speed);
}

- (void)setPlaybackPaused:(BOOL)newPaused
{
  if (_playbackPaused != newPaused) {
    _playbackPaused = newPaused;
    AVG_VLOG(@"setPlaybackPaused: %d", newPaused);
    
    if (newPaused) {
      // Stop playback
      [self pause];
    } else {
      // Resume playback if we were playing before and source is loaded
      if (_isSourceLoaded && self.autoPlay && !self.isPlaying) {
        [self play];
      }
    }
  }
}

- (void)updateFrame
{
  if (_frameOwed) {
    [self drawOwedFrame];
    if (!_frameOwed) self.lastFrameTime = CACurrentMediaTime();
    [self updateDisplayLink];
    return;
  }
  if (!self.isPlaying) {
    [self updateDisplayLink];
    return;
  }
  
  CFTimeInterval currentTime = CACurrentMediaTime();
  
  CFTimeInterval elapsed = currentTime - self.lastFrameTime;
  
  if (elapsed >= self.frameInterval / self.playbackSpeed) {
    self.lastFrameTime = currentTime - fmod(elapsed, self.frameInterval / self.playbackSpeed);
    
    // For streaming ring buffer mode, handle on-demand decoding/upload
    if (_streamingRingBufferMode) {
      NSInteger vPosModulus = self.totalFrames * 2;
      NSInteger virtualPos = (_playbackLoopIteration * self.totalFrames + self.currentFrame) % vPosModulus;
      NSInteger gpuSlot = self.currentFrame % _streamingRingBufferSize;
      
      if (_isUncompressedStreaming) {
        // Uncompressed streaming
        if (!_useCompressedFormat && _decodedRgbaCache) {
          // Simulator: use background-decoded RGBA cache
          [self decodeUncompressedFramesAhead:virtualPos];
          [self evictOldDecodedFrames:virtualPos];
          
          NSInteger slotVPos = [_ringSlots[gpuSlot] integerValue];
          if (slotVPos != virtualPos) {
            NSData *rgbaData;
            @synchronized(_decodedRgbaCache) {
              rgbaData = _decodedRgbaCache[@(virtualPos)];
            }
            if (rgbaData) {
              [self uploadDecodedFrameToGPU:virtualPos slot:gpuSlot];
              _ringSlots[gpuSlot] = @(virtualPos);
            } else {
              // Frame not decoded yet - skip this update
              return;
            }
          }
        } else {
          // Real device: upload directly from mmap (fast, native ASTC)
          NSInteger slotVPos = [_ringSlots[gpuSlot] integerValue];
          if (slotVPos != virtualPos) {
            [self uploadUncompressedFrame:self.currentFrame toSlot:gpuSlot];
            _ringSlots[gpuSlot] = @(virtualPos);
          }
        }
      } else {
        // Zstd streaming: decode frames ahead
        [self decodeFramesAhead:virtualPos];
        
        // Evict old frames from CPU cache
        [self evictOldFrames:virtualPos];
        
        // Ensure current frame is in GPU
        [self ensureFrameInGPU:virtualPos];
        
        // Check if frame is ready
        NSInteger slotVPos = [_ringSlots[gpuSlot] integerValue];
        
        if (slotVPos != virtualPos && !_frameCache[@(virtualPos)]) {
          // Frame not ready yet - skip this update
          static int notReadyCount = 0;
          if (notReadyCount++ < 20) {
            AVG_VLOG(@"Frame not ready - frame=%ld, vPos=%ld, slot=%ld has vPos=%ld, cacheSize=%lu, decoderPos=%ld",
                  (long)self.currentFrame, (long)virtualPos, (long)gpuSlot, (long)slotVPos, 
                  (unsigned long)_frameCache.count, (long)_decoderPosition);
          }
          return;
        }
      }
    }
    
    self.currentFrame++;
    
    if (self.currentFrame >= self.totalFrames) {
      if (self.loop) {
        self.currentFrame = 0;
        if (_streamingRingBufferMode) {
          _playbackLoopIteration++;
          NSInteger nextVPos = (_playbackLoopIteration * self.totalFrames) % (self.totalFrames * 2);
          AVG_VLOG(@"Playback loop wrap-around: iteration %ld, nextVPos=%ld, decoderLoop=%ld, decoderPos=%ld, cacheSize=%lu",
                (long)_playbackLoopIteration, (long)nextVPos, (long)_decoderLoopIteration, (long)_decoderPosition, (unsigned long)_frameCache.count);
          // Log first few slot states
          for (int i = 0; self.verbose && i < MIN(4, _streamingRingBufferSize); i++) {
            AVG_VLOG(@"  slot[%d] = vPos %ld", i, (long)[_ringSlots[i] integerValue]);
          }
          // Log if next frame is in cache
          AVG_VLOG(@"  cache has nextVPos=%ld: %@", (long)nextVPos, _frameCache[@(nextVPos)] ? @"YES" : @"NO");
          
          // CRITICAL: Immediately upload frame 0 of new loop to GPU before next render
          // This prevents showing stale frame from previous loop
          [self ensureFrameInGPU:nextVPos];
        }
      } else {
        // The last frame stays on screen
        self.currentFrame = self.totalFrames - 1;
        _ended = YES;
        self.isPlaying = NO;
        [self updateDisplayLink];
        [self notifyFinished];
        [self notifyStateChange:@"stopped"];
        return;
      }
    }
    
    if (self.enableFrameEvents) {
      [self notifyFrameUpdate];
    }
    [self updateUniforms];
    [self setNeedsDisplay];
  }
}

- (void)updateUniforms
{
  // Calculate frame UV based on 2D grid layout
  NSInteger partIndex = self.framesPerPart > 0 ? self.currentFrame / self.framesPerPart : 0;
  NSInteger frameToRender = self.currentFrame;
  
  partIndex = MIN(partIndex, (NSInteger)self.partTextures.count - 1);
  NSInteger frameInPart = self.framesPerPart > 0 ? frameToRender % self.framesPerPart : frameToRender;
  
  NSInteger col = self.framesPerRow > 0 ? frameInPart % self.framesPerRow : 0;
  NSInteger row = self.framesPerRow > 0 ? frameInPart / self.framesPerRow : 0;
  
  // Get part dimensions from metadata or actual texture
  NSInteger partWidth, partHeight;
  
  if (_partsMetadata && partIndex < [_partsMetadata count]) {
    NSDictionary *partInfo = _partsMetadata[partIndex];
    NSInteger cols = [partInfo[@"columns"] integerValue] ?: self.framesPerRow;
    NSInteger rows = [partInfo[@"rows"] integerValue] ?: ((self.framesPerPart + self.framesPerRow - 1) / self.framesPerRow);
    partWidth = cols * self.frameWidth;
    partHeight = rows * self.frameHeight;
  } else if (partIndex < _partWidths.count) {
    // Use actual texture dimensions
    partWidth = [_partWidths[partIndex] integerValue];
    partHeight = [_partHeights[partIndex] integerValue];
  } else {
    // Fallback calculation
    partWidth = self.framesPerRow * self.frameWidth;
    if (self.partCount > 1) {
      NSInteger framesInThisPart = MIN(self.framesPerPart, self.totalFrames - partIndex * self.framesPerPart);
      partHeight = ((framesInThisPart + self.framesPerRow - 1) / self.framesPerRow) * self.frameHeight;
    } else {
      partHeight = ((self.totalFrames + self.framesPerRow - 1) / self.framesPerRow) * self.frameHeight;
    }
  }
  
  // Calculate UV coordinates
  float minU = (col * self.frameWidth) / (float)partWidth;
  float maxU = ((col + 1) * self.frameWidth) / (float)partWidth;
  float minV = (row * self.frameHeight) / (float)partHeight;
  float maxV = ((row + 1) * self.frameHeight) / (float)partHeight;
  
  // Uniform buffer layout (must match the Metal shader's Uniforms): float4 frameUV
  float *uniforms = (float *)[self.uniformBuffer contents];
  uniforms[0] = minU;
  uniforms[1] = minV;
  uniforms[2] = maxU;
  uniforms[3] = maxV;
  
  // Update current texture based on part index
  if (partIndex < (NSInteger)self.partTextures.count) {
    self.currentTexture = self.partTextures[partIndex];
  }
}

- (void)drawInMTKView:(MTKView *)view
{
  static int drawCount = 0;
  
  // Check for texture array mode
  if (_useTextureArray && _textureArray && _arrayPipelineState) {
    if (drawCount < 5) {
      AVG_VLOG(@"drawInMTKView - rendering texture array frame %ld", (long)self.currentFrame);
      drawCount++;
    }
    
    // Update layer uniform - use ring buffer slot for streaming mode
    float layer;
    if (_streamingRingBufferMode) {
      NSInteger gpuSlot = self.currentFrame % _streamingRingBufferSize;
      
      // CRITICAL: Ensure the GPU slot has the correct frame before rendering
      // This handles the loop boundary case where the slot has stale data
      NSInteger vPosModulus = self.totalFrames * 2;
      NSInteger virtualPos = (_playbackLoopIteration * self.totalFrames + self.currentFrame) % vPosModulus;
      NSInteger slotVPos = [_ringSlots[gpuSlot] integerValue];
      
      if (slotVPos != virtualPos) {
        // Slot has wrong frame - upload correct frame
        if (_isUncompressedStreaming) {
          if (!_useCompressedFormat && _decodedRgbaCache) {
            // Simulator: upload from decoded RGBA cache
            NSData *rgbaData;
            @synchronized(_decodedRgbaCache) {
              rgbaData = _decodedRgbaCache[@(virtualPos)];
            }
            if (rgbaData) {
              [self uploadDecodedFrameToGPU:virtualPos slot:gpuSlot];
              _ringSlots[gpuSlot] = @(virtualPos);
            }
          } else {
            // Real device: upload directly from mmap
            [self uploadUncompressedFrame:self.currentFrame toSlot:gpuSlot];
            _ringSlots[gpuSlot] = @(virtualPos);
          }
        } else {
          // Zstd: upload from cache
          NSData *frameData = _frameCache[@(virtualPos)];
          if (frameData) {
            [self uploadFrameToGPU:frameData.bytes slot:gpuSlot];
            _ringSlots[gpuSlot] = @(virtualPos);
          }
        }
      }
      
      layer = (float)gpuSlot;
    } else {
      layer = (float)self.currentFrame;
    }
    memcpy([_arrayUniformBuffer contents], &layer, sizeof(float));
    
    id<MTLCommandBuffer> commandBuffer = [self.commandQueue commandBuffer];
    MTLRenderPassDescriptor *renderPassDescriptor = view.currentRenderPassDescriptor;
    
    if (renderPassDescriptor) {
      id<MTLRenderCommandEncoder> renderEncoder = [commandBuffer renderCommandEncoderWithDescriptor:renderPassDescriptor];
      
      [renderEncoder setRenderPipelineState:_arrayPipelineState];
      [renderEncoder setFragmentTexture:_textureArray atIndex:0];
      [renderEncoder setFragmentSamplerState:_samplerState atIndex:0];
      [renderEncoder setFragmentBuffer:_arrayUniformBuffer offset:0 atIndex:0];
      
      [renderEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];
      [renderEncoder endEncoding];
      
      [commandBuffer presentDrawable:view.currentDrawable];
    }
    
    [commandBuffer commit];
    return;
  }
  
  // Standard atlas mode rendering
  if (!self.currentTexture || !self.pipelineState) {
    if (drawCount < 5) {
      AVG_VLOG(@"drawInMTKView - skipping (texture=%p, pipeline=%p)", self.currentTexture, self.pipelineState);
    }
    drawCount++;
    return;
  }
  
  if (drawCount < 5) {
    AVG_VLOG(@"drawInMTKView - rendering frame %ld", (long)self.currentFrame);
    drawCount++;
  }
  
  id<MTLCommandBuffer> commandBuffer = [self.commandQueue commandBuffer];
  
  MTLRenderPassDescriptor *renderPassDescriptor = view.currentRenderPassDescriptor;
  if (renderPassDescriptor) {
    id<MTLRenderCommandEncoder> renderEncoder = [commandBuffer renderCommandEncoderWithDescriptor:renderPassDescriptor];
    
    [renderEncoder setRenderPipelineState:self.pipelineState];
    [renderEncoder setFragmentTexture:self.currentTexture atIndex:0];
    [renderEncoder setFragmentSamplerState:_samplerState atIndex:0];
    [renderEncoder setFragmentBuffer:self.uniformBuffer offset:0 atIndex:0];
    
    [renderEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];
    [renderEncoder endEncoding];
    
    [commandBuffer presentDrawable:view.currentDrawable];
  }
  
  [commandBuffer commit];
}

- (void)mtkView:(MTKView *)view drawableSizeWillChange:(CGSize)size
{
  // Handle view size changes if needed
}

#pragma mark - Lifecycle

- (void)cleanup
{
  AVG_VLOG(@"cleanup");
  _isDestroyed = YES;
  _isSourceLoaded = NO;
  _isSourceLoading = NO;
  _frameOwed = NO;
  _ended = NO;
  
  // Stop playback
  self.isPlaying = NO;
  if (self.displayLink) {
    [self.displayLink invalidate];
    self.displayLink = nil;
  }
  
  // Release textures
  self.partTextures = nil;
  self.currentTexture = nil;
  _transcodedData.clear();
  
  // Cleanup streaming state
  if (_dstream) {
    ZSTD_freeDStream(_dstream);
    _dstream = NULL;
  }
  _compressedData = nil;
  _frameAccumulator = nil;
  _ringSlots = nil;
  _frameCache = nil;
  _streamingRingBufferMode = NO;
  
  // Cleanup mmap. The simulator's ASTC decode blocks read the mapping on _astcDecodeQueue and
  // stop once _mmapData is NULL, so unmap after the block in flight, on the same serial queue.
  void *mmapData = _mmapData;
  size_t mmapSize = _mmapSize;
  int mmapFd = _mmapFd;
  _mmapData = NULL;
  _mmapFd = -1;
  _mmapSize = 0;
  if ((mmapData && mmapData != MAP_FAILED) || mmapFd >= 0) {
    dispatch_async(_astcDecodeQueue, ^{
      if (mmapData && mmapData != MAP_FAILED) munmap(mmapData, mmapSize);
      if (mmapFd >= 0) close(mmapFd);
    });
  }
  _isUncompressedStreaming = NO;
  
  // Cleanup staging buffers for async blit
  _stagingBuffers[0] = nil;
  _stagingBuffers[1] = nil;
  _blitCommandQueue = nil;
}

- (void)willMoveToSuperview:(UIView *)newSuperview
{
  [super willMoveToSuperview:newSuperview];
  
  if (newSuperview == nil) {
    // View is being removed - cleanup
    AVG_VLOG(@"willMoveToSuperview nil - cleaning up");
    [self cleanup];
  } else {
    // View is being added - restart if we have a source
    AVG_VLOG(@"willMoveToSuperview - view added");
    _isDestroyed = NO;
    
    // If we have a source but it's not loaded or loading, reload it
    if (_source && !_isSourceLoaded && !_isSourceLoading) {
      AVG_VLOG(@"Restarting playback after view re-added");
      _isSourceLoading = YES;
      if (_localPath) {
        [self loadKTX2FromFile:_localPath];
      } else {
        [self resolveAndLoadSource];
      }
    }
  }
}

- (void)didMoveToWindow
{
  [super didMoveToWindow];
  
  if (self.window == nil) {
    // View removed from window - pause but don't cleanup yet
    AVG_VLOG(@"didMoveToWindow nil - pausing");
    [self pause];
  } else if (_source && _isSourceLoaded && self.autoPlay && !self.playbackPaused && !self.isPlaying) {
    // View added to window and we have loaded source - resume
    AVG_VLOG(@"didMoveToWindow - resuming playback");
    [self play];
  }
}

- (void)dealloc
{
  AVG_VLOG(@"dealloc");
  [self cleanup];
}

@end
