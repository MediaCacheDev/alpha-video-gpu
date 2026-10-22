#import "AlphaVideoGPU.h"
#import "AlphaVideoGpuSource.h"
#import <TargetConditionals.h>
#import <sys/mman.h>
#import <sys/stat.h>
#import <fcntl.h>
#import <unistd.h>

// Third-party sources compiled into this file, with their own warnings left alone
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Weverything"

#define ZSTD_STATIC_LINKING_ONLY
#include "zstd/zstd.h"
#include "zstd/zstd.c"

// Software ASTC decoder, for the simulator
#include "astc_decomp.cpp"

#pragma clang diagnostic pop

#include "zstd_seek.h"

// How long a display tick spends catching up to a seek; the rest goes on over the next ticks
static const CFTimeInterval kSeekSkipSliceSeconds = 0.008;

// The store's cap when maxStorageBytes isn't set
static const int64_t kAVGDefaultMaxStorageBytes = 512LL * 1024 * 1024;
// Bytes of a level read from a reader at a time
static const size_t kAVGReaderChunkBytes = 256 * 1024;
// Bytes read first from a reader for the header and metadata, which most files fit in
static const int64_t kAVGHeaderGuessBytes = 16 * 1024;

static_assert(AVGSourceReaderPending == AVG_ZSTD_READ_PENDING, "a pending read means the same to both");

#define AVG_VLOG(fmt, ...) do { if (self.verbose) NSLog(@"AlphaVideoGpuView: " fmt, ##__VA_ARGS__); } while (0)

#pragma mark - Source resolution

static BOOL AVGIsRemote(NSString *uri) {
  return [uri hasPrefix:@"https://"] || [uri hasPrefix:@"http://"];
}

/** The file an asset:// (in the app bundle), file:// or absolute-path source names. */
static NSString *AVGLocalPath(NSString *uri, NSString **error) {
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

/** A level's bytes through a reader, for AVGZstdStream's read function. */
typedef struct {
  void *reader;  // the view's AVGSourceReader, which it holds while the stream reads
  uint64_t levelOffset;
} AVGLevelInput;

static int64_t AVGReadLevel(void *context, uint64_t offset, uint8_t *buffer, size_t length) {
  AVGLevelInput *input = (AVGLevelInput *)context;
  return [(__bridge AVGSourceReader *)input->reader tryReadAt:(int64_t)(input->levelOffset + offset)
                                                         into:buffer
                                                       length:length];
}

@interface AlphaVideoGpuView () <MTKViewDelegate>

@property (nonatomic, strong) id<MTLCommandQueue> commandQueue;
@property (nonatomic, strong) id<MTLBuffer> vertexBuffer;

@property (nonatomic, assign) NSInteger currentFrame;
@property (nonatomic, assign) NSInteger totalFrames;
@property (nonatomic, assign) CGFloat frameRate;
@property (nonatomic, assign) BOOL isPlaying;
@property (nonatomic, assign) CFTimeInterval frameInterval;

@property (nonatomic, assign) NSInteger frameWidth;
@property (nonatomic, assign) NSInteger frameHeight;

@property (nonatomic, strong) CADisplayLink *displayLink;
@property (nonatomic, assign) CFTimeInterval lastFrameTime;

@end

/** Frame rate from KTXanimData (per-frame duration / timescale) in a key/value data block, or 0. */
static double AVGAnimFrameRate(const uint8_t *kvd, NSUInteger length) {
  NSUInteger pos = 0;
  while (pos + 4 <= length) {
    uint32_t entryLength = avg_read_u32le(kvd + pos);
    NSUInteger entryStart = pos + 4;
    if (entryLength == 0 || entryStart + entryLength > length) break;
    NSUInteger keyEnd = entryStart;
    while (keyEnd < entryStart + entryLength && kvd[keyEnd] != 0) keyEnd++;
    static const char kAnimKey[] = "KTXanimData";
    if (keyEnd - entryStart == sizeof(kAnimKey) - 1 && memcmp(kvd + entryStart, kAnimKey, keyEnd - entryStart) == 0 &&
        entryStart + entryLength >= keyEnd + 1 + 12) {
      uint32_t duration = avg_read_u32le(kvd + keyEnd + 1);
      uint32_t timescale = avg_read_u32le(kvd + keyEnd + 5);
      return duration > 0 && timescale > 0 ? (double)timescale / duration : 0;
    }
    pos = entryStart + entryLength + (4 - entryLength % 4) % 4;
  }
  return 0;
}

/**
 * Frame size, frame count and vkFormat from the KTX2 header, and the frame rate from KTXanimData
 * (30 without it). Both are standard KTX 2.0; texenc writes no other metadata.
 */
static NSDictionary *AVGKTX2Metadata(const uint8_t *header, const uint8_t *kvd, NSUInteger kvdLength) {
  double frameRate = kvd ? AVGAnimFrameRate(kvd, kvdLength) : 0;
  return @{
    @"vkFormat": @(avg_read_u32le(header + 12)),
    @"frameWidth": @(avg_read_u32le(header + 20)),
    @"frameHeight": @(avg_read_u32le(header + 24)),
    @"frameCount": @(avg_read_u32le(header + 32)),
    @"frameRate": @(frameRate > 0 ? frameRate : 30.0),
  };
}

/** ASTC (VkFormat 157-184, either colour space) in an array texture: the only files that play. */
static BOOL AVGIsASTCArray(NSDictionary *metadata) {
  uint32_t vkFormat = [metadata[@"vkFormat"] unsignedIntValue];
  return vkFormat >= 157 && vkFormat <= 184 && [metadata[@"frameCount"] unsignedIntValue] >= 1;
}

NSString *const AlphaVideoGpuErrorNoSourceModule = @"NO_SOURCE_MODULE";

@implementation AlphaVideoGpuView {
  MTLPixelFormat _metalPixelFormat;
  BOOL _useCompressedFormat;
  id<MTLSamplerState> _samplerState;
  NSString *_sourceUri;
  // The local file the current source resolved to (a bundle file or a path)
  NSString *_localPath;
  // What a URL or data source is read through, while it plays
  AVGSourceReader *_reader;
  // The headers and data source the current source was opened with
  NSDictionary<NSString *, NSString *> *_playingHeaders;
  NSString *_playingDataSource;
  // A whole file read from _reader, which _mmapData points into in place of a mapping
  NSData *_heldFileData;
  // The zstd stream's input from _reader, and the buffer it reads into
  AVGLevelInput _levelInput;
  NSMutableData *_streamChunk;
  // Streaming stopped on a read or decompression error
  BOOL _streamFailed;
  // Bumped for every new source, so a slow download for an old one is dropped
  NSUInteger _sourceGeneration;
  // The cache key the loaded/loading source belongs to. Compared against self.cacheKey
  // to tell a re-signed URL for the same object apart from a new asset.
  NSString *_loadedCacheKey;
  BOOL _isSourceLoaded;
  BOOL _isSourceLoading;
  BOOL _isDestroyed;
  
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
  // Reads _compressedData through _dstream
  AVGZstdStream _zstdStream;
  // Empty when the level is one zstd stream
  AVGZstdSeekTable _seekTable;
  // Decoded bytes still to drop before _decoderPosition: a seek catching up
  uint64_t _pendingSkipBytes;
  NSMutableData *_frameAccumulator;
  uint32_t _streamBytesPerLayer;
  uint64_t _streamLevel0Offset;
  
  // Uncompressed streaming (mmap)
  int _mmapFd;
  void *_mmapData;
  size_t _mmapSize;
  BOOL _isUncompressedStreaming;
  
  // Decoded RGBA cache for simulator ASTC decompression (background thread)
  NSMutableDictionary<NSNumber *, NSData *> *_decodedRgbaCache;
  NSInteger _uncompressedDecoderPosition;
  dispatch_queue_t _astcDecodeQueue;
  BOOL _uncompressedDecodeQueued;  // main thread only
  
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
    self.memoryStrategy = @"vram";     // vram, ram
    
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
    memset(&_zstdStream, 0, sizeof(_zstdStream));
    memset(&_seekTable, 0, sizeof(_seekTable));
    _pendingSkipBytes = 0;
    _frameAccumulator = nil;
    _streamBytesPerLayer = 0;
    _streamLevel0Offset = 0;
    _mmapFd = -1;
    _mmapData = NULL;
    _mmapSize = 0;
    _isUncompressedStreaming = NO;
    _decodedRgbaCache = nil;
    _uncompressedDecoderPosition = 0;
    _astcDecodeQueue = dispatch_queue_create("dev.mediacache.alphavideo.gpu.astcdecode", DISPATCH_QUEUE_SERIAL);
    _astcBlockWidth = 4;
    _astcBlockHeight = 4;
    
    // Detect and cache optimal texture format
    [self detectAndCacheTextureFormat];
    
    // Default values
    self.autoPlay = YES;
    self.stream = YES;
    self.storage = nil;
    
    [self setupMetal];
    [self setupVertexData];
  }
  return self;
}

- (void)detectAndCacheTextureFormat
{
  // Check if running on simulator
#if TARGET_OS_SIMULATOR
  AVG_VLOG(@"Running on simulator - decoding ASTC to RGBA in software");
  _metalPixelFormat = MTLPixelFormatRGBA8Unorm;
  _useCompressedFormat = NO;
#else
  // Check for ASTC support (all real iOS devices with Metal support ASTC)
  if (@available(iOS 8.0, *)) {
    // Check device capabilities - ASTC is supported on A8 and later
    if ([self.device supportsFamily:MTLGPUFamilyApple2]) {
      AVG_VLOG(@"The GPU decodes ASTC");
      _metalPixelFormat = MTLPixelFormatASTC_4x4_LDR;
      _useCompressedFormat = YES;
      return;
    }
  }
  
  // Fallback to RGBA32 for older devices
  AVG_VLOG(@"No GPU ASTC support - decoding ASTC to RGBA in software");
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
  // Both map to the LDR MTLPixelFormat, as on Android and web: the encoder stores premultiplied
  // sRGB-encoded bytes that must reach the drawable unchanged, and an _sRGB format would
  // linearise them on sampling with nothing converting back.
  struct ASTCFormatInfo {
    uint32_t vkFormatUnorm;
    uint32_t vkFormatSrgb;
    MTLPixelFormat pixelFormat;
    uint32_t blockWidth;
    uint32_t blockHeight;
  };
  
  static const ASTCFormatInfo formats[] = {
    { 157, 158, MTLPixelFormatASTC_4x4_LDR,   4,  4 },
    { 159, 160, MTLPixelFormatASTC_5x4_LDR,   5,  4 },
    { 161, 162, MTLPixelFormatASTC_5x5_LDR,   5,  5 },
    { 163, 164, MTLPixelFormatASTC_6x5_LDR,   6,  5 },
    { 165, 166, MTLPixelFormatASTC_6x6_LDR,   6,  6 },
    { 167, 168, MTLPixelFormatASTC_8x5_LDR,   8,  5 },
    { 169, 170, MTLPixelFormatASTC_8x6_LDR,   8,  6 },
    { 171, 172, MTLPixelFormatASTC_8x8_LDR,   8,  8 },
    { 173, 174, MTLPixelFormatASTC_10x5_LDR,  10, 5 },
    { 175, 176, MTLPixelFormatASTC_10x6_LDR,  10, 6 },
    { 177, 178, MTLPixelFormatASTC_10x8_LDR,  10, 8 },
    { 179, 180, MTLPixelFormatASTC_10x10_LDR, 10, 10 },
    { 181, 182, MTLPixelFormatASTC_12x10_LDR, 12, 10 },
    { 183, 184, MTLPixelFormatASTC_12x12_LDR, 12, 12 },
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
  _metalPixelFormat = MTLPixelFormatASTC_4x4_LDR;
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
  BOOL sameReads = [self readsAsPlaying];
  if ([_sourceUri isEqualToString:uri] && sameReads && (_isSourceLoaded || _isSourceLoading)) {
    AVG_VLOG(@"Source unchanged and already loaded/loading, skipping");
    return;
  }
  
  // A presigned URL is re-signed every time the app lists its media, so the URL changes
  // while still addressing the same object. When the cache key matches what is already
  // on screen this is a credential refresh, not a new asset, and reloading would
  // interrupt a loop that is supposed to run until unmount.
  if (self.cacheKey.length > 0 && [self.cacheKey isEqualToString:_loadedCacheKey] && sameReads
      && (_isSourceLoaded || _isSourceLoading)) {
    AVG_VLOG(@"Same cacheKey (%@), ignoring URL change", self.cacheKey);
    _source = [uri copy];
    _sourceUri = [uri copy];
    return;
  }
  
  // Frees the previous source: its decoder, mapping or reader, and frames
  [self cleanup];
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

/** Whether the headers and data source set are the ones the current source was opened with. */
- (BOOL)readsAsPlaying
{
  NSString *dataSource = self.dataSource.length > 0 ? self.dataSource : nil;
  return [self.headers ?: @{} isEqualToDictionary:_playingHeaders ?: @{}]
      && (dataSource == _playingDataSource || [dataSource isEqualToString:_playingDataSource]);
}

- (void)setStorage:(NSString *)storage
{
  _storage = [@[ @"disk", @"memory", @"none" ] containsObject:storage] ? [storage copy] : @"auto";
}

/**
 * Opens _sourceUri off the main thread, then loads it: a URL or a data source through a reader,
 * anything else as a local file. Starts on the next main-queue turn, so that properties set in
 * the same turn as source (React Native's, in any order) apply to it.
 */
- (void)resolveAndLoadSource
{
  NSUInteger generation = ++_sourceGeneration;
  dispatch_async(dispatch_get_main_queue(), ^{
    if (generation != self->_sourceGeneration) return;
    if (self->_isDestroyed) {
      self->_isSourceLoading = NO;
      return;
    }
    [self openSource:generation];
  });
}

- (void)openSource:(NSUInteger)generation
{
  NSString *uri = _sourceUri;
  _loadedCacheKey = [self.cacheKey copy];
  _playingHeaders = [self.headers copy] ?: @{};
  _playingDataSource = self.dataSource.length > 0 ? [self.dataSource copy] : nil;
  NSString *dataSourceName = _playingDataSource;
  BOOL readsRanges = dataSourceName != nil || AVGIsRemote(uri);
  if (readsRanges && !AVGRangeSourceAvailable()) {
    if (dataSourceName) {
      [self notifyError:[NSString stringWithFormat:@"The data source \"%@\" needs the range-source add-on, which the "
                                                   @"app's build leaves out", dataSourceName]
                   code:AlphaVideoGpuErrorNoSourceModule];
      return;
    }
    [self warnIfStorageWithoutAddOn];
  }
  id<AlphaVideoGpuDataSource> dataSource = nil;
  if (dataSourceName) {
    dataSource = [AlphaVideoGpuDataSources dataSourceNamed:dataSourceName];
    if (!dataSource) {
      [self notifyError:[NSString stringWithFormat:@"No data source is registered as \"%@\" (AlphaVideoGpuDataSources)",
                                                   dataSourceName]];
      return;
    }
  }
  NSDictionary<NSString *, NSString *> *headers = _playingHeaders;
  // auto: the disk for URLs; a data source's caching is its own
  NSString *storage = ![self.storage isEqualToString:@"auto"] ? self.storage : dataSource ? @"none" : @"disk";
  NSString *key = self.cacheKey.length > 0 ? self.cacheKey : uri;
  int64_t maxStorageBytes = self.maxStorageBytes > 0 ? self.maxStorageBytes : kAVGDefaultMaxStorageBytes;
  AVG_VLOG(@"Resolving source %@", uri);

  dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
    NSString *error = nil;
    NSString *path = nil;
    AVGSourceReader *reader = nil;
    if (readsRanges) {
      NSURL *url = [NSURL URLWithString:uri];
      reader = url ? [AVGSourceReader openURL:url
                                   dataSource:dataSource
                                      headers:headers
                                      storage:storage
                                          key:key
                              maxStorageBytes:maxStorageBytes
                                        error:&error]
                   : nil;
      if (!reader) error = [NSString stringWithFormat:@"Loading %@ failed: %@", uri, error ?: @"not a URL"];
    } else {
      path = AVGLocalPath(uri, &error);
    }
    dispatch_async(dispatch_get_main_queue(), ^{
      if (generation != self->_sourceGeneration) {
        [reader close];
        return;
      }
      if (self->_isDestroyed) {
        [reader close];
        self->_isSourceLoading = NO;
        return;
      }
      if (reader) {
        AVG_VLOG(@"Opened %@ (%lld bytes, storage %@)", uri, reader.size, storage);
        self->_reader = reader;
        [self loadKTX2FromReader:reader];
      } else if (path) {
        self->_localPath = path;
        [self loadKTX2FromFile:path];
      } else {
        [self notifyError:error];
      }
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
  [self notifyError:error code:nil];
}

- (void)notifyError:(NSString *)error code:(nullable NSString *)code
{
  _isSourceLoading = NO;
  NSLog(@"AlphaVideoGpuView: Error: %@", error);
  if (self.onError) self.onError(error ?: @"Unknown error", code);
}

- (void)warnIfStorageWithoutAddOn
{
  BOOL set = ![self.storage isEqualToString:@"auto"] || self.maxStorageBytes > 0 || self.cacheKey.length > 0;
  static dispatch_once_t once;
  if (set) {
    dispatch_once(&once, ^{
      NSLog(@"[AlphaVideoGPU] storage, maxStorageBytes and cacheKey need the range-source add-on, which the app's "
            @"build leaves out; URLs are downloaded whole for each play instead");
    });
  }
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
  
  // Each frame is a layer of a texture array
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
  // Create sampler state
  MTLSamplerDescriptor *samplerDescriptor = [[MTLSamplerDescriptor alloc] init];
  samplerDescriptor.minFilter = MTLSamplerMinMagFilterLinear;
  samplerDescriptor.magFilter = MTLSamplerMinMagFilterLinear;
  samplerDescriptor.sAddressMode = MTLSamplerAddressModeClampToEdge;
  samplerDescriptor.tAddressMode = MTLSamplerAddressModeClampToEdge;
  _samplerState = [self.device newSamplerStateWithDescriptor:samplerDescriptor];
}

/** Reads the frame metadata from data holding the file through its key/value data; nil if it isn't KTX2. */
- (NSDictionary *)extractKTX2Metadata:(NSData *)ktx2Data
{
  if (ktx2Data.length < 80) return nil;
  const uint8_t *bytes = (const uint8_t *)ktx2Data.bytes;
  if (bytes[0] != 0xAB || bytes[1] != 0x4B || bytes[2] != 0x54 || bytes[3] != 0x58) return nil;

  uint32_t kvdOffset = avg_read_u32le(bytes + 56);
  uint32_t kvdLength = avg_read_u32le(bytes + 60);
  BOOL kvdInRange = kvdLength > 0 && (uint64_t)kvdOffset + kvdLength <= ktx2Data.length;
  return AVGKTX2Metadata(bytes, kvdInRange ? bytes + kvdOffset : NULL, kvdInRange ? kvdLength : 0);
}

- (void)loadKTX2FromFile:(NSString *)path
{
  dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH, 0), ^{
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
    
    NSDictionary *metadata = [self extractKTX2Metadata:fileData];
    if (!metadata) {
      dispatch_async(dispatch_get_main_queue(), ^{
        [self notifyError:@"Not a KTX2 file"];
      });
      return;
    }
    
    // Continue on main thread with parsed metadata
    dispatch_async(dispatch_get_main_queue(), ^{
      [self applyMetadataAndLoadTextures:metadata sourcePath:path fileData:nil reader:nil];
    });
  });
}

/**
 * Loads from a reader: the header and metadata first, then a streamed zstd level as it plays, or
 * anything else read whole into memory.
 */
- (void)loadKTX2FromReader:(AVGSourceReader *)reader
{
  dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH, 0), ^{
    NSString *error = nil;
    NSData *fileData = [self readHeaderFrom:reader error:&error];
    NSDictionary *metadata = fileData ? [self extractKTX2Metadata:fileData] : nil;
    if (fileData && !metadata) {
      error = @"Not a KTX2 file";
    }
    uint32_t supercompression = metadata ? avg_read_u32le((const uint8_t *)fileData.bytes + 44) : 0;
    if (metadata && self.stream && supercompression == 0) {
      fileData = [reader readAll:&error];
      if (!fileData) metadata = nil;
      if (metadata && [self checkAndPlayUncompressedStreamingFromData:fileData metadata:metadata]) return;
    }
    dispatch_async(dispatch_get_main_queue(), ^{
      if (reader != self->_reader) return;
      if (!metadata) {
        [self notifyError:error];
        return;
      }
      [self applyMetadataAndLoadTextures:metadata sourcePath:nil fileData:fileData reader:reader];
    });
  });
}

/** The header through the key/value data, where the metadata is. */
- (NSData *)readHeaderFrom:(AVGSourceReader *)reader error:(NSString **)error
{
  int64_t size = reader.size;
  if (size < 0) {
    *error = @"The file's size isn't known (the server sent no Content-Length or Content-Range)";
    return nil;
  }
  if (size < 104) {
    *error = [NSString stringWithFormat:@"Not a KTX2 file (%lld bytes)", size];
    return nil;
  }
  int64_t first = MIN(size, kAVGHeaderGuessBytes);
  NSData *header = [reader readAt:0 length:first error:error];
  if (!header) return nil;
  const uint8_t *bytes = (const uint8_t *)header.bytes;
  int64_t kvdEnd = (int64_t)avg_read_u32le(bytes + 56) + avg_read_u32le(bytes + 60);
  if (kvdEnd > first && kvdEnd <= size) header = [reader readAt:0 length:kvdEnd error:error];
  return header;
}

/**
 * The seek table from a level's last bytes, or an empty one when it has none. NO with error set
 * when they can't be read.
 */
- (BOOL)readSeekTable:(AVGZstdSeekTable *)table
                 from:(AVGSourceReader *)reader
          levelOffset:(uint64_t)levelOffset
          levelLength:(uint64_t)levelLength
   decompressedLength:(uint64_t)decompressedLength
                error:(NSString **)error
{
  memset(table, 0, sizeof(*table));
  uint64_t guess = MIN(levelLength, (uint64_t)AVG_ZSTD_SEEK_TAIL_GUESS);
  NSData *tail = [reader readAt:(int64_t)(levelOffset + levelLength - guess) length:(int64_t)guess error:error];
  if (!tail) return NO;
  uint64_t needed = avg_zstd_seek_table_tail_size((const uint8_t *)tail.bytes, tail.length);
  if (needed > guess && needed <= levelLength) {
    tail = [reader readAt:(int64_t)(levelOffset + levelLength - needed) length:(int64_t)needed error:error];
    if (!tail) return NO;
  }
  avg_zstd_seek_table_parse_tail((const uint8_t *)tail.bytes, tail.length, levelLength, decompressedLength, table);
  return YES;
}

/** Ends streaming on a read or decompression error, with onError. */
- (void)failStream:(const char *)error
{
  if (_streamFailed) return;
  _streamFailed = YES;
  NSString *why = @(error);
  if (_reader && strcmp(error, "reading failed") == 0) why = _reader.lastError;
  _frameOwed = NO;
  [self pause];
  [self updateDisplayLink];
  [self notifyError:[NSString stringWithFormat:@"Reading %@ failed: %@", _sourceUri, why]];
}

#pragma mark - On-Demand Frame Decoding

- (void)decodeFramesAhead:(NSInteger)currentVirtualPos
{
  if (!_dstream || (!_zstdStream.data && !_zstdStream.read) || !_streamingRingBufferMode || _streamFailed) {
    static int skipCount = 0;
    if (skipCount++ < 5) {
      AVG_VLOG(@"decodeFramesAhead skipping - dstream=%p, ringMode=%d, failed=%d",
            _dstream, _streamingRingBufferMode, _streamFailed);
    }
    return;
  }
  
  // A seek still catching up: nothing past it can be decoded yet
  if (![self skipPendingBytesUntil:CACurrentMediaTime() + kSeekSkipSliceSeconds]) return;
  
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
  
  while (framesToDecode > 0) {
    // Check if we need to restart decompression for looping
    if (_decoderPosition >= self.totalFrames) {
      _decoderPosition = 0;
      _decoderLoopIteration++;
      
      avg_zstd_stream_restart(&_zstdStream, 0);
      [_frameAccumulator setLength:0];
      
      AVG_VLOG(@"Decoder wrap-around: loop %ld, decoding from the start", (long)_decoderLoopIteration);
      
      // After wrap-around, recalculate how many frames we need to decode
      // Use the same formula as at the start of the method
      NSInteger newDecodeVPos = (_decoderLoopIteration * self.totalFrames + _decoderPosition) % vPosModulus;
      NSInteger newFramesAhead = (newDecodeVPos - currentVirtualPos + vPosModulus) % vPosModulus;
      framesToDecode = self.streamingPrefetchAhead - newFramesAhead;
      if (framesToDecode <= 0) {
        AVG_VLOG(@"After wrap-around: already ahead enough, stopping decode");
        break;
      }
      AVG_VLOG(@"After wrap-around: newDecodeVPos=%ld, framesAhead=%ld, framesToDecode=%ld",
            (long)newDecodeVPos, (long)newFramesAhead, (long)framesToDecode);
      // Don't continue - fall through to decompress the header and first frames
      // The continue was causing us to skip decompression after wrap-around
    }
    
    // Decompress the rest of the frame straight into the accumulator, across zstd frame ends
    NSUInteger held = _frameAccumulator.length;
    if (held < _streamBytesPerLayer) {
      const char *error = NULL;
      [_frameAccumulator setLength:_streamBytesPerLayer];
      size_t got = avg_zstd_stream_read(&_zstdStream, (uint8_t *)_frameAccumulator.mutableBytes + held,
                                        _streamBytesPerLayer - held, &error);
      [_frameAccumulator setLength:held + got];
      if (error) {
        [self failStream:error];
        return;
      }
      // Short of a frame while input is on its way: the next display tick carries on
      if (_frameAccumulator.length < _streamBytesPerLayer && !_zstdStream.inputPending) {
        AVG_VLOG(@"ZSTD stream ended at decoderPos=%ld, accumLen=%lu",
              (long)_decoderPosition, (unsigned long)_frameAccumulator.length);
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
}

/**
 * Decompresses and drops the bytes a seek still has to get past, until the deadline
 * (CACurrentMediaTime()); YES once none are left.
 */
- (BOOL)skipPendingBytesUntil:(CFTimeInterval)deadline
{
  if (_pendingSkipBytes == 0) return YES;
  uint64_t held = MIN((uint64_t)_frameAccumulator.length, _pendingSkipBytes);
  [_frameAccumulator replaceBytesInRange:NSMakeRange(0, (NSUInteger)held) withBytes:NULL length:0];
  _pendingSkipBytes -= held;

  NSMutableData *scratch = [NSMutableData dataWithLength:ZSTD_DStreamOutSize()];
  while (_pendingSkipBytes > 0) {
    const char *error = NULL;
    size_t want = (size_t)MIN((uint64_t)scratch.length, _pendingSkipBytes);
    size_t got = avg_zstd_stream_read(&_zstdStream, scratch.mutableBytes, want, &error);
    if (error) {
      [self failStream:error];
      return NO;
    }
    if (got == 0) {
      if (_zstdStream.inputPending) return NO;
      _pendingSkipBytes = 0;  // past the end of the data
      break;
    }
    _pendingSkipBytes -= got;
    if (_pendingSkipBytes > 0 && CACurrentMediaTime() >= deadline) return NO;
  }
  return YES;
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
    // Carry on decoding when that is no further from the target than the nearest seek point
    // (the level start, without a seek table). The skip itself happens over the next display
    // ticks, in decodeFramesAhead.
    uint64_t bytesPerLayer = _streamBytesPerLayer;
    uint64_t targetBytes = (uint64_t)target * bytesPerLayer;
    AVGZstdSeekPoint point = avg_zstd_seek_point_before(&_seekTable, targetBytes);
    uint64_t decoded = (uint64_t)_decoderPosition * bytesPerLayer + _frameAccumulator.length - _pendingSkipBytes;
    if (samePass && target >= _decoderPosition && avg_zstd_carry_on(decoded, point)) {
      _pendingSkipBytes += (uint64_t)(target - _decoderPosition) * bytesPerLayer;
    } else {
      avg_zstd_stream_restart(&_zstdStream, point.compressedOffset);
      [_frameAccumulator setLength:0];
      _pendingSkipBytes = targetBytes - point.decompressedOffset;
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
  
  // Read the key/value data for KTXanimData
  uint32_t kvdOffset = *(uint32_t *)(headerBytes + 56);
  uint32_t kvdLength = *(uint32_t *)(headerBytes + 60);
  NSMutableData *kvdBytes = [NSMutableData dataWithLength:MIN(kvdLength, 4096u)];
  if (kvdBytes.length > 0) {
    ssize_t kvdRead = pread(fd, kvdBytes.mutableBytes, kvdBytes.length, kvdOffset);
    kvdBytes.length = kvdRead > 0 ? (NSUInteger)kvdRead : 0;
  }
  NSDictionary *metadata = AVGKTX2Metadata(headerBytes, (const uint8_t *)kvdBytes.bytes, kvdBytes.length);
  // Only ASTC texture arrays stream
  if (!AVGIsASTCArray(metadata)) {
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
  return [self startUncompressedStreamingWithMetadata:metadata];
}

/** checkAndPlayUncompressedStreaming: for a whole file read from a reader, held in memory. */
- (BOOL)checkAndPlayUncompressedStreamingFromData:(NSData *)fileData metadata:(NSDictionary *)metadata
{
  if (!self.stream || fileData.length < 104) return NO;
  if (avg_read_u32le((const uint8_t *)fileData.bytes + 44) != 0) return NO;
  if (!AVGIsASTCArray(metadata)) return NO;
  _heldFileData = fileData;
  _mmapData = (void *)fileData.bytes;
  _mmapSize = fileData.length;
  _mmapFd = -1;
  return [self startUncompressedStreamingWithMetadata:metadata];
}

/** Plays the uncompressed texture array at _mmapData: a mapping, or a file held in memory. */
- (BOOL)startUncompressedStreamingWithMetadata:(NSDictionary *)metadata
{
  _isUncompressedStreaming = YES;
  
  // Parse KTX2 header from mmap
  const uint8_t *data = (const uint8_t *)_mmapData;
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
  BOOL useVramStrategy = ![self.memoryStrategy isEqualToString:@"ram"];
  NSInteger gpuCacheSize = useVramStrategy ? 
      MIN(self.streamingCacheSize, (NSInteger)layerCount) : 
      MIN(self.streamingRingSize, (NSInteger)layerCount);
  
  // Setup on main thread
  dispatch_sync(dispatch_get_main_queue(), ^{
    self.totalFrames = layerCount;
    self.frameWidth = width;
    self.frameHeight = height;
    self.frameRate = [metadata[@"frameRate"] doubleValue];
    self.frameInterval = 1.0 / self.frameRate;
    
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
        if (self->_uncompressedDecoderPosition >= self.totalFrames) {
          self->_uncompressedDecoderPosition = 0;
          self->_decoderLoopIteration++;
        }
        
        // Upload to GPU on main thread (fast - just memcpy)
        dispatch_async(dispatch_get_main_queue(), ^{
          for (NSInteger i = 0; i < framesToPreload; i++) {
            NSInteger slot = i % gpuCacheSize;
            // A seek since the decode has emptied the cache
            if ([self uploadDecodedFrameToGPU:i slot:slot]) {
              self->_ringSlots[slot] = @(i);
            }
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
  if (_uncompressedDecodeQueued) return;
  _uncompressedDecodeQueued = YES;
  
  // Only needed on simulator where we decode ASTC to RGBA
  NSInteger vPosModulus = self.totalFrames * 2;
  // Decoding past the cache would have the frames evicted before they are shown
  NSInteger aheadLimit = MIN(self.streamingPrefetchAhead, self.streamingCacheSize);
  
  // The position belongs to the decode queue, so the batch is sized there
  dispatch_async(_astcDecodeQueue, ^{
    NSInteger currentDecodeVPos = (self->_decoderLoopIteration * self.totalFrames + self->_uncompressedDecoderPosition) % vPosModulus;
    NSInteger framesAhead = (currentDecodeVPos - currentVirtualPos + vPosModulus) % vPosModulus;
    NSInteger framesToDecode = aheadLimit - framesAhead;
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
    dispatch_async(dispatch_get_main_queue(), ^{
      self->_uncompressedDecodeQueued = NO;
    });
  });
}

// Upload pre-decoded RGBA frame to GPU (fast, main thread safe); NO when it isn't decoded
- (BOOL)uploadDecodedFrameToGPU:(NSInteger)virtualPos slot:(NSInteger)slot
{
  NSData *rgbaData;
  @synchronized(_decodedRgbaCache) {
    rgbaData = _decodedRgbaCache[@(virtualPos)];
  }
  
  if (!rgbaData || !_textureArray) return NO;
  
  MTLRegion region = MTLRegionMake2D(0, 0, self.frameWidth, self.frameHeight);
  [_textureArray replaceRegion:region
                   mipmapLevel:0
                         slice:slot
                     withBytes:rgbaData.bytes
                   bytesPerRow:self.frameWidth * 4
                 bytesPerImage:self.frameWidth * self.frameHeight * 4];
  return YES;
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

/**
 * With a reader, fileData holds the file from its start, through at least the header; otherwise
 * it is nil and the file is at sourcePath.
 */
- (void)applyMetadataAndLoadTextures:(NSDictionary *)metadata
                          sourcePath:(NSString *)sourcePath
                            fileData:(NSData *)fileData
                              reader:(AVGSourceReader *)reader
{
  if (!AVGIsASTCArray(metadata)) {
    [self notifyError:[NSString stringWithFormat:@"Only ASTC texture arrays play (this file has vkFormat %@ and %@ layers); encode it with texenc",
                       metadata[@"vkFormat"], metadata[@"frameCount"]]];
    return;
  }
  
  self.totalFrames = [metadata[@"frameCount"] integerValue];
  self.frameWidth = [metadata[@"frameWidth"] integerValue];
  self.frameHeight = [metadata[@"frameHeight"] integerValue];
  self.frameRate = [metadata[@"frameRate"] doubleValue];
  self.frameInterval = 1.0 / self.frameRate;
  _textureArrayLayerCount = self.totalFrames;
  
  AVG_VLOG(@"Metadata: %ld frames, %ldx%ld, %.3f fps",
        (long)self.totalFrames, (long)self.frameWidth, (long)self.frameHeight, self.frameRate);
  
  [self loadTextureArray:metadata sourcePath:sourcePath fileData:fileData reader:reader];
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

#pragma mark - Texture Array Loading

/**
 * Load a KTX2 texture array (one frame per layer).
 * Uses Metal's MTLTextureType2DArray for efficient frame switching.
 */
- (void)loadTextureArray:(NSDictionary *)metadata
              sourcePath:(NSString *)filePath
                fileData:(NSData *)fileData
                  reader:(AVGSourceReader *)reader
{
  // Check for streaming mode from stream prop (not metadata)
  BOOL streamMode = self.stream;
  
  dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH, 0), ^{
    // Load the KTX2 file
    NSData *ktx2Data = fileData ?: [NSData dataWithContentsOfFile:filePath options:NSDataReadingMappedIfSafe error:nil];
    
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
    if (streamMode && supercompressionScheme == 2 && reader) {
      AVG_VLOG(@"Using internal zstd streaming mode, from a reader");
      [self loadInternalZstdStreamingFromReader:reader
                                          width:width
                                         height:height
                                     layerCount:layerCount
                                   level0Offset:level0Offset
                                   level0Length:level0Length
                                       metadata:metadata];
      return;
    }
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

    // Not streamed: a reader's file is read whole, past the header
    if (reader && (int64_t)ktx2Data.length < reader.size) {
      NSString *error = nil;
      ktx2Data = [reader readAll:&error];
      if (!ktx2Data) {
        dispatch_async(dispatch_get_main_queue(), ^{
          if (reader == self->_reader) [self notifyError:error];
        });
        return;
      }
      bytes = (const uint8_t *)[ktx2Data bytes];
    }
    if (level0Offset + level0Length > ktx2Data.length) {
      dispatch_async(dispatch_get_main_queue(), ^{
        [self notifyError:@"The KTX2 file is shorter than its level index says"];
      });
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
 * The KTX2 header is uncompressed and only the level0 data is zstd compressed
 * (supercompressionScheme=2), so the layers are decompressed as they play.
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
  
  const uint8_t *compressedDataPtr = (const uint8_t *)mmapData + offsetAdjustment;
  uint32_t bytesPerLayer = [self astcBytesPerLayerForWidth:width height:height];
  [self startInternalZstdStreaming:dstream
                             width:width
                            height:height
                        layerCount:layerCount
                          metadata:metadata
                             adopt:^BOOL {
    self->_compressedData = [NSData dataWithBytesNoCopy:(void *)compressedDataPtr
                                                 length:level0Length
                                           freeWhenDone:NO];
    self->_zstdStream = (AVGZstdStream){dstream, compressedDataPtr, (size_t)level0Length, 0, false};
    avg_zstd_seek_table_free(&self->_seekTable);
    if (avg_zstd_seek_table_parse(compressedDataPtr, (size_t)level0Length,
                                  (uint64_t)bytesPerLayer * layerCount, &self->_seekTable)) {
      AVG_VLOG(@"Seek table: %zu seek points", self->_seekTable.count);
    } else {
      AVG_VLOG(@"No seek table: seeks decode from the start");
    }

    // Store mmap info for cleanup and loop restart
    self->_mmapFd = fd;
    self->_mmapData = mmapData;
    self->_mmapSize = mmapLength;
    return YES;
  }];
}

/** loadInternalZstdStreamingTextureArray: for a level read through a reader as it plays. */
- (void)loadInternalZstdStreamingFromReader:(AVGSourceReader *)reader
                                      width:(uint32_t)width
                                     height:(uint32_t)height
                                 layerCount:(uint32_t)layerCount
                               level0Offset:(uint64_t)level0Offset
                               level0Length:(uint64_t)level0Length
                                   metadata:(NSDictionary *)metadata
{
  uint32_t bytesPerLayer = [self astcBytesPerLayerForWidth:width height:height];
  NSString *error = nil;
  AVGZstdSeekTable table;
  if (![self readSeekTable:&table
                      from:reader
               levelOffset:level0Offset
               levelLength:level0Length
        decompressedLength:(uint64_t)bytesPerLayer * layerCount
                     error:&error]) {
    dispatch_async(dispatch_get_main_queue(), ^{
      if (reader == self->_reader) [self notifyError:error];
    });
    return;
  }
  AVG_VLOG(@"%@", table.count > 0 ? [NSString stringWithFormat:@"Seek table: %zu seek points", table.count]
                                  : @"No seek table: seeks decode from the start");

  ZSTD_DStream *dstream = ZSTD_createDStream();
  if (!dstream || ZSTD_isError(ZSTD_initDStream(dstream))) {
    ZSTD_freeDStream(dstream);
    avg_zstd_seek_table_free(&table);
    dispatch_async(dispatch_get_main_queue(), ^{
      [self notifyError:@"Failed to create ZSTD_DStream"];
    });
    return;
  }
  NSMutableData *chunk = [NSMutableData dataWithLength:kAVGReaderChunkBytes];
  __block AVGZstdSeekTable seekTable = table;
  [self startInternalZstdStreaming:dstream
                             width:width
                            height:height
                        layerCount:layerCount
                          metadata:metadata
                             adopt:^BOOL {
    if (reader != self->_reader) {
      ZSTD_freeDStream(dstream);
      avg_zstd_seek_table_free(&seekTable);
      return NO;
    }
    self->_streamChunk = chunk;
    self->_levelInput.reader = (__bridge void *)reader;
    self->_levelInput.levelOffset = level0Offset;
    AVGZstdStream stream;
    memset(&stream, 0, sizeof(stream));
    stream.dstream = dstream;
    stream.size = (size_t)level0Length;
    stream.read = AVGReadLevel;
    stream.readContext = &self->_levelInput;
    stream.chunk = (uint8_t *)chunk.mutableBytes;
    stream.chunkCapacity = chunk.length;
    self->_zstdStream = stream;
    avg_zstd_seek_table_free(&self->_seekTable);
    self->_seekTable = seekTable;
    return YES;
  }];
}

/**
 * The setup both zstd streams share, on the main queue: adopt installs the stream's input
 * (NO to drop it, for a source no longer showing), then the ring and the first frames.
 */
- (void)startInternalZstdStreaming:(ZSTD_DStream *)dstream
                             width:(uint32_t)width
                            height:(uint32_t)height
                        layerCount:(uint32_t)layerCount
                          metadata:(NSDictionary *)metadata
                             adopt:(BOOL (^)(void))adopt
{
  // Calculate bytes per layer using current ASTC block dimensions
  uint32_t bytesPerLayer = [self astcBytesPerLayerForWidth:width height:height];
  
  // Determine memory strategy
  BOOL useVramStrategy = ![self.memoryStrategy isEqualToString:@"ram"];
  NSInteger gpuCacheSize = useVramStrategy ? 
      MIN(self.streamingCacheSize, (NSInteger)layerCount) : 
      MIN(self.streamingRingSize, (NSInteger)layerCount);
  
  AVG_VLOG(@"Internal zstd streaming params: strategy=%@, gpuCache=%ld, bytesPerLayer=%u (ASTC %ux%u)",
        self.memoryStrategy, (long)gpuCacheSize, bytesPerLayer, _astcBlockWidth, _astcBlockHeight);
  
  // Setup on main thread
  dispatch_sync(dispatch_get_main_queue(), ^{
    if (!adopt()) return;

    // Apply metadata
    self.totalFrames = layerCount;
    self.frameWidth = width;
    self.frameHeight = height;
    self.frameRate = [metadata[@"frameRate"] doubleValue];
    self.frameInterval = 1.0 / self.frameRate;
    
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
    
    self->_dstream = dstream;
    self->_pendingSkipBytes = 0;
    self->_frameAccumulator = [NSMutableData data];
    self->_streamBytesPerLayer = bytesPerLayer;
    self->_streamLevel0Offset = 0;  // the stream's offsets start at the level0 data
    
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

- (void)play
{
  AVG_VLOG(@"play called - isPlaying=%d, totalFrames=%ld", self.isPlaying, (long)self.totalFrames);
  
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
  [self setNeedsDisplay];
}

/** Runs the display link while playing, or until an owed frame is drawn. */
- (void)updateDisplayLink
{
  BOOL needed = self.isPlaying || _frameOwed;
  if (needed && !self.displayLink) {
    self.displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(updateFrame)];
    self.displayLink.preferredFramesPerSecond = (NSInteger)ceil(self.frameRate);
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
    [self setNeedsDisplay];
  }
}

- (void)drawInMTKView:(MTKView *)view
{
  static int drawCount = 0;
  
  if (_textureArray && _arrayPipelineState) {
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
  }
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
  
  _textureArray = nil;
  
  // Cleanup streaming state
  if (_dstream) {
    ZSTD_freeDStream(_dstream);
    _dstream = NULL;
  }
  _compressedData = nil;
  memset(&_zstdStream, 0, sizeof(_zstdStream));
  avg_zstd_seek_table_free(&_seekTable);
  _streamChunk = nil;
  memset(&_levelInput, 0, sizeof(_levelInput));
  _streamFailed = NO;
  // Reads still waiting on it, for a load in flight, fail; the load then drops them
  [_reader close];
  _reader = nil;
  _pendingSkipBytes = 0;
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
  NSData *heldFileData = _heldFileData;
  _heldFileData = nil;
  if (heldFileData) {
    // Not a mapping: released after the block in flight
    dispatch_async(_astcDecodeQueue, ^{
      (void)heldFileData;
    });
  } else if ((mmapData && mmapData != MAP_FAILED) || mmapFd >= 0) {
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
