#import "KTX2Player.h"
#import <React/RCTEventDispatcher.h>
#import <TargetConditionals.h>

// Basis Universal transcoder - must define this before including
#define BASISU_NO_ITERATOR_DEBUG_LEVEL

// Include the single-file transcoder implementation
#include "vendor/basis_universal/transcoder/basisu_transcoder.cpp"

// Zstandard decompression from basis_universal
#define ZSTD_STATIC_LINKING_ONLY
#include "vendor/basis_universal/zstd/zstd.h"
#include "vendor/basis_universal/zstd/zstd.c"

// Proper ASTC decoder for simulator fallback
#include "astc_decomp.cpp"

// View manager is in separate KTX2PlayerViewManager.m file

@implementation KTX2PlayerView {
  basist::transcoder_texture_format _targetFormat;
  MTLPixelFormat _metalPixelFormat;
  BOOL _useCompressedFormat;
  std::vector<std::vector<uint8_t>> _transcodedData;
  id<MTLSamplerState> _samplerState;
  BOOL _basisInitialized;
  NSString *_basePath;
  NSString *_sourceUri;
  NSArray *_partsMetadata;
  NSArray<NSNumber *> *_partWidths;
  NSArray<NSNumber *> *_partHeights;
  BOOL _isSourceLoaded;
  BOOL _isDestroyed;
  
  BOOL _isNativeASTC;
  NSDictionary *_cachedMetadata;
}

- (instancetype)initWithFrame:(CGRect)frame
{
  NSLog(@"KTX2PlayerView: initWithFrame %@", NSStringFromCGRect(frame));
  
  self = [super initWithFrame:frame];
  if (self) {
    self.device = MTLCreateSystemDefaultDevice();
    NSLog(@"KTX2PlayerView: Metal device=%@", self.device.name);
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
    _isDestroyed = NO;
    
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
  NSLog(@"KTX2PlayerView: Running on simulator - using RGBA32 fallback");
  _targetFormat = basist::transcoder_texture_format::cTFRGBA32;
  _metalPixelFormat = MTLPixelFormatRGBA8Unorm;
  _useCompressedFormat = NO;
  return;
#endif
  
  // Check for ASTC support (all real iOS devices with Metal support ASTC)
  if (@available(iOS 8.0, *)) {
    // Check device capabilities - ASTC is supported on A8 and later
    if ([self.device supportsFamily:MTLGPUFamilyApple2]) {
      NSLog(@"KTX2PlayerView: Using ASTC 4x4 format");
      _targetFormat = basist::transcoder_texture_format::cTFASTC_4x4_RGBA;
      _metalPixelFormat = MTLPixelFormatASTC_4x4_sRGB;
      _useCompressedFormat = YES;
      return;
    }
  }
  
  // Fallback to RGBA32 for older devices
  NSLog(@"KTX2PlayerView: Using RGBA32 fallback format");
  _targetFormat = basist::transcoder_texture_format::cTFRGBA32;
  _metalPixelFormat = MTLPixelFormatRGBA8Unorm;
  _useCompressedFormat = NO;
}

- (void)setSource:(NSDictionary *)source
{
  NSLog(@"KTX2PlayerView: setSource called with: %@", source);
  
  if (!source) {
    NSLog(@"KTX2PlayerView: setSource - source is nil, ignoring");
    return;
  }
  
  NSString *uri = source[@"uri"];
  
  NSLog(@"KTX2PlayerView: setSource - uri=%@", uri);
  
  if (!uri || uri.length == 0) {
    NSLog(@"KTX2PlayerView: Invalid source - missing uri");
    return;
  }
  
  // Check if source changed
  if ([_sourceUri isEqualToString:uri] && _isSourceLoaded) {
    NSLog(@"KTX2PlayerView: Source unchanged and already loaded, skipping");
    return;
  }
  
  _source = [source copy];
  _sourceUri = [uri copy];
  _isSourceLoaded = NO;
  _isDestroyed = NO;
  
  NSLog(@"KTX2PlayerView: Loading source...");
  [self notifyStateChange:@"loading"];
  [self loadKTX2WithSource:source];
}

- (void)notifyStateChange:(NSString *)state
{
  if (self.onStateChange) {
    self.onStateChange(@{@"state": state});
  }
}

- (void)notifyFrameUpdate
{
  if (self.onFrameUpdate) {
    self.onFrameUpdate(@{
      @"frame": @(self.currentFrame),
      @"totalFrames": @(self.totalFrames)
    });
  }
}

- (void)notifyFinished
{
  if (self.onFinished) {
    self.onFinished(@{});
  }
}

- (void)notifyError:(NSString *)error
{
  if (self.onError) {
    self.onError(@{@"error": error ?: @"Unknown error"});
  }
}

#pragma mark - Caching

- (NSData *)loadDataFromURLWithCaching:(NSString *)urlString partIndex:(NSInteger)partIndex
{
  NSFileManager *fileManager = [NSFileManager defaultManager];
  NSURL *cachesDir = [[fileManager URLsForDirectory:NSCachesDirectory inDomains:NSUserDomainMask] firstObject];
  
  NSURL *cacheFileURL;
  
  if (self.cacheKey && self.cacheKey.length > 0) {
    // Use cacheKey as path from caches directory
    // For multi-part, append part index to cacheKey
    NSString *cacheKeyPath = self.cacheKey;
    if (self.partCount > 1) {
      NSString *ext = [cacheKeyPath pathExtension];
      NSString *basePath = [cacheKeyPath stringByDeletingPathExtension];
      cacheKeyPath = [NSString stringWithFormat:@"%@_%ld.%@", basePath, (long)partIndex, ext];
    }
    
    cacheFileURL = [cachesDir URLByAppendingPathComponent:cacheKeyPath];
    
    // Create intermediate directories
    NSURL *parentDir = [cacheFileURL URLByDeletingLastPathComponent];
    NSError *dirError = nil;
    [fileManager createDirectoryAtURL:parentDir withIntermediateDirectories:YES attributes:nil error:&dirError];
    if (dirError) {
      NSLog(@"KTX2PlayerView: Failed to create cache directory: %@", dirError);
    }
    
    NSLog(@"KTX2PlayerView: Using cacheKey: %@ -> %@", cacheKeyPath, cacheFileURL.path);
  } else {
    // Use hash-based caching in ktx2-cache subdirectory
    NSURL *ktx2CacheDir = [cachesDir URLByAppendingPathComponent:@"ktx2-cache"];
    [fileManager createDirectoryAtURL:ktx2CacheDir withIntermediateDirectories:YES attributes:nil error:nil];
    
    // Create hash from URL
    const char *urlCString = [urlString UTF8String];
    unsigned long hash = 5381;
    int c;
    while ((c = *urlCString++)) {
      hash = ((hash << 5) + hash) + c;
    }
    
    NSString *cacheFileName = [NSString stringWithFormat:@"%016lx.ktx2", hash];
    cacheFileURL = [ktx2CacheDir URLByAppendingPathComponent:cacheFileName];
    
    NSLog(@"KTX2PlayerView: Using hash-based cache: %@", cacheFileURL.path);
  }
  
  // Check if cached file exists
  if ([fileManager fileExistsAtPath:cacheFileURL.path]) {
    NSLog(@"KTX2PlayerView: Using cached file: %@", cacheFileURL.path);
    return [NSData dataWithContentsOfURL:cacheFileURL];
  }
  
  // Download from URL
  NSURL *url = [NSURL URLWithString:urlString];
  NSError *downloadError = nil;
  NSData *downloadedData = [NSData dataWithContentsOfURL:url options:0 error:&downloadError];
  
  if (downloadError || !downloadedData) {
    NSLog(@"KTX2PlayerView: Failed to download: %@, error: %@", urlString, downloadError);
    return nil;
  }
  
  // Save to cache
  NSError *writeError = nil;
  [downloadedData writeToURL:cacheFileURL options:NSDataWritingAtomic error:&writeError];
  if (writeError) {
    NSLog(@"KTX2PlayerView: Failed to write cache file: %@", writeError);
  } else {
    NSLog(@"KTX2PlayerView: Cached file to: %@", cacheFileURL.path);
  }
  
  return downloadedData;
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
  
  // Create fragment shader with grid support (must include VertexOut struct since shaders compile separately)
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
  
  NSLog(@"KTX2PlayerView: Compiling Metal shaders...");
  
  NSError *error = nil;
  id<MTLLibrary> vertexLibrary = [self.device newLibraryWithSource:vertexShaderSource options:nil error:&error];
  if (error) {
    NSLog(@"KTX2PlayerView: Vertex shader compilation error: %@", error);
  }
  id<MTLFunction> vertexFunction = [vertexLibrary newFunctionWithName:@"vertex_main"];
  if (!vertexFunction) {
    NSLog(@"KTX2PlayerView: Failed to get vertex_main function!");
  }
  
  error = nil;
  id<MTLLibrary> fragmentLibrary = [self.device newLibraryWithSource:fragmentShaderSource options:nil error:&error];
  if (error) {
    NSLog(@"KTX2PlayerView: Fragment shader compilation error: %@", error);
  }
  id<MTLFunction> fragmentFunction = [fragmentLibrary newFunctionWithName:@"fragment_main"];
  if (!fragmentFunction) {
    NSLog(@"KTX2PlayerView: Failed to get fragment_main function!");
  }
  
  NSLog(@"KTX2PlayerView: Shader compilation complete. vertex=%p, fragment=%p", vertexFunction, fragmentFunction);
  
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
    NSLog(@"KTX2PlayerView: Failed to create pipeline state: %@", error);
  } else {
    NSLog(@"KTX2PlayerView: Pipeline state created successfully: %p", self.pipelineState);
  }
}

- (void)setupVertexData
{
  // Create uniform buffer for frame UVs
  self.uniformBuffer = [self.device newBufferWithLength:sizeof(float) * 4 options:MTLResourceStorageModeShared];
  
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
        NSLog(@"KTX2PlayerView: Extracted embedded metadata from KTX2");
        return metadata;
      }
    }
    
    // Move to next entry (with padding to 4-byte alignment)
    pos += entryLength;
    pos += (4 - (entryLength % 4)) % 4;
  }
  
  return nil;
}

- (void)loadKTX2WithSource:(NSDictionary *)source
{
  NSString *uri = source[@"uri"];
  
  // Store base path for part file loading
  _basePath = [[uri stringByDeletingLastPathComponent] copy];
  
  // Download the KTX2 file and extract embedded metadata
  dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH, 0), ^{
    NSData *ktx2Data = nil;
    if ([uri hasPrefix:@"http://"] || [uri hasPrefix:@"https://"]) {
      ktx2Data = [self loadDataFromURLWithCaching:uri partIndex:0];
    } else if ([uri hasPrefix:@"file://"]) {
      NSURL *fileURL = [NSURL URLWithString:uri];
      ktx2Data = [NSData dataWithContentsOfURL:fileURL];
    } else {
      ktx2Data = [NSData dataWithContentsOfFile:uri];
    }
    
    if (!ktx2Data) {
      dispatch_async(dispatch_get_main_queue(), ^{
        [self notifyError:@"Failed to load KTX2 file"];
      });
      return;
    }
    
    // Extract embedded metadata from KTX2 file (required)
    NSDictionary *metadata = [self extractKTX2Metadata:ktx2Data];
    
    if (!metadata) {
      dispatch_async(dispatch_get_main_queue(), ^{
        [self notifyError:@"No metadata found in KTX2 file. Ensure the file was encoded with metadata injection."];
      });
      return;
    }
    
    NSLog(@"KTX2PlayerView: Using embedded KTX2 metadata");
    
    // Continue on main thread with parsed metadata
    dispatch_async(dispatch_get_main_queue(), ^{
      [self applyMetadataAndLoadTextures:metadata basePath:self->_basePath sourceUri:uri];
    });
  });
}

- (void)applyMetadataAndLoadTextures:(NSDictionary *)metadata basePath:(NSString *)basePath sourceUri:(NSString *)sourceUri
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
  
  NSLog(@"KTX2PlayerView: Metadata: %ld frames, %ldx%ld, %.1f fps, %ld parts, mode=%@",
        (long)self.totalFrames, (long)self.frameWidth, (long)self.frameHeight,
        self.frameRate, (long)self.partCount, mode ?: @"uastc");
  
  // Load part textures
  [self loadPartTextures:metadata basePath:basePath sourceUri:sourceUri isNativeASTC:isNativeASTC];
}

/**
 * Decode ASTC 4x4 compressed data to RGBA using proper decoder.
 * Uses basisu::astc::decompress from astc_decomp.cpp
 */
- (NSData *)decodeASTCToRGBA:(NSData *)astcData width:(uint32_t)width height:(uint32_t)height
{
  const uint8_t *blocks = (const uint8_t *)[astcData bytes];
  const int blockWidth = 4;
  const int blockHeight = 4;
  uint32_t blocksX = (width + blockWidth - 1) / blockWidth;
  uint32_t blocksY = (height + blockHeight - 1) / blockHeight;
  
  NSMutableData *rgbaData = [NSMutableData dataWithLength:width * height * 4];
  uint8_t *rgba = (uint8_t *)[rgbaData mutableBytes];
  memset(rgba, 0, width * height * 4);
  
  // Temporary buffer for one decoded block
  uint8_t blockPixels[blockWidth * blockHeight * 4];
  
  for (uint32_t by = 0; by < blocksY; by++) {
    for (uint32_t bx = 0; bx < blocksX; bx++) {
      const uint8_t *block = blocks + (by * blocksX + bx) * 16;
      
      // Use proper ASTC decoder (sRGB = false for linear decoding)
      bool success = basisu::astc::decompress(blockPixels, block, false, blockWidth, blockHeight);
      if (!success) {
        // Fill with magenta to indicate decode error
        for (int i = 0; i < 16; i++) {
          blockPixels[i * 4 + 0] = 255;
          blockPixels[i * 4 + 1] = 0;
          blockPixels[i * 4 + 2] = 255;
          blockPixels[i * 4 + 3] = 255;
        }
      }
      
      // Copy block to output
      for (uint32_t y = 0; y < blockHeight && (by * blockHeight + y) < height; y++) {
        for (uint32_t x = 0; x < blockWidth && (bx * blockWidth + x) < width; x++) {
          uint32_t dstX = bx * blockWidth + x;
          uint32_t dstY = by * blockHeight + y;
          uint8_t *dst = rgba + (dstY * width + dstX) * 4;
          uint8_t *src = blockPixels + (y * blockWidth + x) * 4;
          dst[0] = src[0];
          dst[1] = src[1];
          dst[2] = src[2];
          dst[3] = src[3];
        }
      }
    }
  }
  
  NSLog(@"KTX2PlayerView: Decoded ASTC %ux%u (%u x %u blocks) to RGBA", width, height, blocksX, blocksY);
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
  
  NSLog(@"KTX2PlayerView: Native ASTC - VkFormat=%u, %ux%u, supercompression=%u",
        vkFormat, pixelWidth, pixelHeight, supercompressionScheme);
  
  // Read level index (after 80-byte header)
  // Level index: array of { byteOffset: uint64, byteLength: uint64, uncompressedByteLength: uint64 }
  uint64_t level0ByteOffset = *(uint64_t *)(bytes + 80);
  uint64_t level0ByteLength = *(uint64_t *)(bytes + 88);
  uint64_t level0UncompressedLength = *(uint64_t *)(bytes + 96);
  
  NSLog(@"KTX2PlayerView: Level 0 - offset=%llu, length=%llu, uncompressed=%llu",
        level0ByteOffset, level0ByteLength, level0UncompressedLength);
  
  if (level0ByteOffset + level0ByteLength > ktx2Data.length) {
    NSLog(@"KTX2PlayerView: Invalid level data bounds");
    return nil;
  }
  
  NSData *textureData = nil;
  
  if (supercompressionScheme == 2) {
    // Zstandard compression - use basis_universal's bundled zstd
    NSLog(@"KTX2PlayerView: Decompressing Zstandard data...");
    
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
      NSLog(@"KTX2PlayerView: Zstd decompression failed: %s", ZSTD_getErrorName(decodedSize));
      return nil;
    }
    
    if (decodedSize != uncompressedSize) {
      NSLog(@"KTX2PlayerView: Zstd decompression size mismatch: expected %zu, got %zu", uncompressedSize, decodedSize);
      return nil;
    }
    
    textureData = decompressedData;
    NSLog(@"KTX2PlayerView: Decompressed %zu bytes", decodedSize);
  } else if (supercompressionScheme == 0) {
    // No supercompression
    textureData = [NSData dataWithBytes:(bytes + level0ByteOffset) length:(size_t)level0ByteLength];
  } else {
    NSLog(@"KTX2PlayerView: Unsupported supercompression scheme: %u", supercompressionScheme);
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
    NSLog(@"KTX2PlayerView: Failed to init KTX2 transcoder");
    return nil;
  }
  
  if (!transcoder.start_transcoding()) {
    NSLog(@"KTX2PlayerView: Failed to start transcoding");
    return nil;
  }
  
  uint32_t width = transcoder.get_width();
  uint32_t height = transcoder.get_height();
  *outWidth = width;
  *outHeight = height;
  
  // Calculate transcoded data size
  uint32_t dataSize;
  if (_useCompressedFormat) {
    uint32_t blocksX = (width + 3) / 4;
    uint32_t blocksY = (height + 3) / 4;
    dataSize = blocksX * blocksY * 16;
  } else {
    dataSize = width * height * 4;
  }
  
  // Allocate and transcode
  NSMutableData *transcodedData = [NSMutableData dataWithLength:dataSize];
  if (!transcoder.transcode_image_level(0, 0, 0, (uint8_t*)[transcodedData mutableBytes], dataSize, _targetFormat, 0)) {
    NSLog(@"KTX2PlayerView: Failed to transcode image");
    return nil;
  }
  
  NSLog(@"KTX2PlayerView: Transcoded UASTC %ux%u (%u bytes)", width, height, dataSize);
  return transcodedData;
}

- (NSString *)getPartPath:(NSInteger)partIndex partsArray:(NSArray *)partsArray basePath:(NSString *)basePath sourceUri:(NSString *)sourceUri useExplicitParts:(BOOL)useExplicitParts
{
  if (useExplicitParts && partIndex < self.parts.count) {
    NSDictionary *partDict = self.parts[partIndex];
    return partDict[@"uri"];
  } else if (self.partCount > 1 && partsArray && partIndex < partsArray.count) {
    NSString *partFilename = partsArray[partIndex][@"file"];
    if (partFilename) {
      return [basePath stringByAppendingPathComponent:partFilename];
    } else {
      NSString *baseName = [[sourceUri lastPathComponent] stringByReplacingOccurrencesOfString:@"_0.ktx2" withString:@""];
      baseName = [baseName stringByReplacingOccurrencesOfString:@".ktx2" withString:@""];
      return [basePath stringByAppendingPathComponent:[NSString stringWithFormat:@"%@_%ld.ktx2", baseName, (long)partIndex]];
    }
  } else {
    return sourceUri;
  }
}

- (void)loadPartTextures:(NSDictionary *)metadata basePath:(NSString *)basePath sourceUri:(NSString *)sourceUri isNativeASTC:(BOOL)isNativeASTC
{
  _cachedMetadata = metadata;
  _isNativeASTC = isNativeASTC;
  
  // Perform loading and transcoding on background thread to avoid blocking main thread
  dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH, 0), ^{
    NSMutableArray<NSDictionary *> *loadedParts = [NSMutableArray array];
    NSArray *partsArray = metadata[@"parts"] ?: metadata[@"strips"];
    
    // Check if we have explicit parts from React Native props
    BOOL useExplicitParts = self.parts && self.parts.count > 1;
    NSInteger actualPartCount = useExplicitParts ? self.parts.count : self.partCount;
    
    // Load all parts
    for (NSInteger i = 0; i < actualPartCount; i++) {
      NSString *partPath;
      
      if (useExplicitParts && i < self.parts.count) {
        // Use explicit URI from parts prop
        NSDictionary *partDict = self.parts[i];
        partPath = partDict[@"uri"];
      } else if (self.partCount > 1 && partsArray && i < partsArray.count) {
        NSString *partFilename = partsArray[i][@"file"];
        if (partFilename) {
          partPath = [basePath stringByAppendingPathComponent:partFilename];
        } else {
          NSString *baseName = [[sourceUri lastPathComponent] stringByReplacingOccurrencesOfString:@"_0.ktx2" withString:@""];
          baseName = [baseName stringByReplacingOccurrencesOfString:@".ktx2" withString:@""];
          partPath = [basePath stringByAppendingPathComponent:[NSString stringWithFormat:@"%@_%ld.ktx2", baseName, (long)i]];
        }
      } else {
        partPath = sourceUri;
      }
      
      NSLog(@"KTX2PlayerView: Loading part %ld: %@%@", (long)i, partPath, useExplicitParts ? @" (explicit)" : @"");
      
      // Load data from URL or file path (safe on background thread)
      NSData *ktx2Data = nil;
      if ([partPath hasPrefix:@"http://"] || [partPath hasPrefix:@"https://"]) {
        // HTTP URL - use caching
        ktx2Data = [self loadDataFromURLWithCaching:partPath partIndex:i];
      } else if ([partPath hasPrefix:@"file://"]) {
        NSURL *fileURL = [NSURL URLWithString:partPath];
        ktx2Data = [NSData dataWithContentsOfURL:fileURL];
      } else {
        ktx2Data = [NSData dataWithContentsOfFile:partPath];
      }
      
      if (!ktx2Data) {
        NSLog(@"KTX2PlayerView: Failed to load part file: %@", partPath);
        continue;
      }
      
      NSLog(@"KTX2PlayerView: Part %ld file size: %lu bytes", (long)i, (unsigned long)ktx2Data.length);
      
      uint32_t width = 0, height = 0;
      NSData *textureData = nil;
      uint32_t blocksX = 0, blocksY = 0;
      
      if (isNativeASTC) {
        // Native ASTC mode - load directly without transcoding
        NSLog(@"KTX2PlayerView: Using native ASTC loader for part %ld", (long)i);
        textureData = [self loadNativeASTCFromKTX2:ktx2Data width:&width height:&height];
        
        if (!textureData) {
          NSLog(@"KTX2PlayerView: Failed to load native ASTC for part %ld", (long)i);
          continue;
        }
        
        blocksX = (width + 3) / 4;
        blocksY = (height + 3) / 4;
        
        // Check if we need to decode ASTC to RGBA (simulator or unsupported device)
        if (!self->_useCompressedFormat) {
          NSLog(@"KTX2PlayerView: ASTC not supported - decoding to RGBA (simulator/fallback mode)");
          NSLog(@"KTX2PlayerView: Note: ASTC textures require a real device for optimal performance");
          textureData = [self decodeASTCToRGBA:textureData width:width height:height];
          if (!textureData) {
            NSLog(@"KTX2PlayerView: Failed to decode ASTC to RGBA for part %ld", (long)i);
            continue;
          }
        }
      } else {
        // UASTC mode - transcode using Basis Universal
        basist::ktx2_transcoder transcoder;
        if (!transcoder.init((const uint8_t*)[ktx2Data bytes], (uint32_t)[ktx2Data length])) {
          NSLog(@"KTX2PlayerView: Failed to init KTX2 transcoder for part %ld", (long)i);
          continue;
        }
        
        if (!transcoder.start_transcoding()) {
          NSLog(@"KTX2PlayerView: Failed to start transcoding for part %ld", (long)i);
          continue;
        }
        
        width = transcoder.get_width();
        height = transcoder.get_height();
        NSLog(@"KTX2PlayerView: Part %ld dimensions: %dx%d", (long)i, width, height);
        
        // Calculate transcoded data size
        uint32_t dataSize;
        
        if (self->_useCompressedFormat) {
          blocksX = (width + 3) / 4;
          blocksY = (height + 3) / 4;
          dataSize = blocksX * blocksY * 16;
        } else {
          dataSize = width * height * 4;
        }
        
        // Allocate and transcode
        NSMutableData *transcodedData = [NSMutableData dataWithLength:dataSize];
        if (!transcoder.transcode_image_level(0, 0, 0, (uint8_t*)[transcodedData mutableBytes], dataSize, self->_targetFormat, 0)) {
          NSLog(@"KTX2PlayerView: Failed to transcode part %ld", (long)i);
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
      
      NSLog(@"KTX2PlayerView: %@ part %ld successfully", isNativeASTC ? @"Loaded native ASTC" : @"Transcoded", (long)i);
    }
    
    // Dispatch to main thread for Metal texture creation and UI updates
    dispatch_async(dispatch_get_main_queue(), ^{
      if (self->_isDestroyed) {
        NSLog(@"KTX2PlayerView: View destroyed during loading, aborting");
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
        
        NSLog(@"KTX2PlayerView: Created texture %p (%dx%d)", texture, width, height);
      }
      
      self.partTextures = [textures copy];
      self->_partWidths = [partWidths copy];
      self->_partHeights = [partHeights copy];
      NSLog(@"KTX2PlayerView: Loaded %lu part textures total", (unsigned long)self.partTextures.count);
      
      if (self.partTextures.count == 0) {
        [self notifyError:@"No textures loaded"];
        return;
      }
      
      self->_isSourceLoaded = YES;
      self.currentTexture = self.partTextures[0];
      [self updateUniforms];
      [self notifyStateChange:@"ready"];
      
      if (self.autoPlay && self.partTextures.count > 0) {
        [self play];
      }
    });
  });
}

- (void)play
{
  NSLog(@"KTX2PlayerView: play called - isPlaying=%d, totalFrames=%ld, textureCount=%lu", 
        self.isPlaying, (long)self.totalFrames, (unsigned long)self.partTextures.count);
  
  if (!self.isPlaying && self.totalFrames > 0) {
    NSLog(@"KTX2PlayerView: Starting playback...");
    self.isPlaying = YES;
    self.lastFrameTime = CACurrentMediaTime();
    
    self.displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(updateFrame)];
    self.displayLink.preferredFramesPerSecond = (NSInteger)self.frameRate;
    [self.displayLink addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];
    
    [self notifyStateChange:@"playing"];
  }
}

- (void)pause
{
  if (self.isPlaying) {
    self.isPlaying = NO;
    if (self.displayLink) {
      [self.displayLink invalidate];
      self.displayLink = nil;
    }
    [self notifyStateChange:@"paused"];
  }
}

- (void)stop
{
  [self pause];
  self.currentFrame = 0;
  [self updateUniforms];
  [self setNeedsDisplay];
  [self notifyStateChange:@"stopped"];
}

- (void)seekToFrame:(NSInteger)frame
{
  self.currentFrame = MAX(0, MIN(frame, self.totalFrames - 1));
  [self updateUniforms];
  [self setNeedsDisplay];
}

- (void)setPlaybackSpeed:(CGFloat)speed
{
  _playbackSpeed = speed;
  _frameInterval = 1.0 / (_frameRate * speed);
}

- (void)updateFrame
{
  if (!self.isPlaying) return;
  
  CFTimeInterval currentTime = CACurrentMediaTime();
  CFTimeInterval elapsed = currentTime - self.lastFrameTime;
  
  if (elapsed >= self.frameInterval / self.playbackSpeed) {
    self.lastFrameTime = currentTime - fmod(elapsed, self.frameInterval / self.playbackSpeed);
    
    self.currentFrame++;
    
    if (self.currentFrame >= self.totalFrames) {
      if (self.loop) {
        self.currentFrame = 0;
      } else {
        self.isPlaying = NO;
        if (self.displayLink) {
          [self.displayLink invalidate];
          self.displayLink = nil;
        }
        [self notifyFinished];
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
  
  if (!self.currentTexture || !self.pipelineState) {
    if (drawCount < 5) {
      NSLog(@"KTX2PlayerView: drawInMTKView - skipping (texture=%p, pipeline=%p)", self.currentTexture, self.pipelineState);
    }
    drawCount++;
    return;
  }
  
  if (drawCount < 5) {
    NSLog(@"KTX2PlayerView: drawInMTKView - rendering frame %ld", (long)self.currentFrame);
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
  NSLog(@"KTX2PlayerView: cleanup");
  _isDestroyed = YES;
  _isSourceLoaded = NO;
  
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
}

- (void)willMoveToSuperview:(UIView *)newSuperview
{
  [super willMoveToSuperview:newSuperview];
  
  if (newSuperview == nil) {
    // View is being removed - cleanup
    NSLog(@"KTX2PlayerView: willMoveToSuperview nil - cleaning up");
    [self cleanup];
  } else {
    // View is being added - restart if we have a source
    NSLog(@"KTX2PlayerView: willMoveToSuperview - view added");
    _isDestroyed = NO;
    
    // If we have a source but it's not loaded, reload it
    if (_source && !_isSourceLoaded) {
      NSLog(@"KTX2PlayerView: Restarting playback after view re-added");
      [self loadKTX2WithSource:_source];
    }
  }
}

- (void)didMoveToWindow
{
  [super didMoveToWindow];
  
  if (self.window == nil) {
    // View removed from window - pause but don't cleanup yet
    NSLog(@"KTX2PlayerView: didMoveToWindow nil - pausing");
    [self pause];
  } else if (_source && _isSourceLoaded && self.autoPlay && !self.isPlaying) {
    // View added to window and we have loaded source - resume
    NSLog(@"KTX2PlayerView: didMoveToWindow - resuming playback");
    [self play];
  }
}

- (void)dealloc
{
  NSLog(@"KTX2PlayerView: dealloc");
  [self cleanup];
}

@end
