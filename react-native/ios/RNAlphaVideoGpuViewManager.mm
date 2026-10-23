//
//  RNAlphaVideoGpuViewManager.mm
//  React Native view manager for AlphaVideoGpuView (old architecture; the new one uses
//  RNAlphaVideoGpuViewComponentView.mm)
//

#ifndef RCT_NEW_ARCH_ENABLED

#import <React/RCTUIManager.h>
#import <React/RCTViewManager.h>
#import "AlphaVideoGPU.h"

#pragma mark - RNAlphaVideoGpuView

/** AlphaVideoGpuView with the React Native props and events. */
@interface RNAlphaVideoGpuView : AlphaVideoGpuView

// `source` prop: { uri, cacheKey, headers?: [name, value, ...], dataSource? }
@property (nonatomic, copy) NSDictionary *reactSource;

@property (nonatomic, copy) RCTDirectEventBlock reactOnLoad;
@property (nonatomic, copy) RCTDirectEventBlock reactOnFrameUpdate;
@property (nonatomic, copy) RCTDirectEventBlock reactOnStateChange;
@property (nonatomic, copy) RCTDirectEventBlock reactOnFinished;
@property (nonatomic, copy) RCTDirectEventBlock reactOnError;

@end

@implementation RNAlphaVideoGpuView

- (instancetype)initWithFrame:(CGRect)frame
{
  self = [super initWithFrame:frame];
  if (self) {
    [self forwardEvents];
  }
  return self;
}

static NSString *RNAVGString(id value)
{
  return [value isKindOfClass:[NSString class]] && [value length] > 0 ? value : nil;
}

- (void)setReactSource:(NSDictionary *)reactSource
{
  _reactSource = [reactSource copy];
  // Adopt a cacheKey inside `source` before loading: React gives no ordering guarantee against
  // the separate `cacheKey` prop, and the load reads the key to name what it stores.
  NSString *cacheKey = RNAVGString(reactSource[@"cacheKey"]);
  if (cacheKey) {
    self.cacheKey = cacheKey;
  }
  NSArray *flat = [reactSource[@"headers"] isKindOfClass:[NSArray class]] ? reactSource[@"headers"] : @[];
  NSMutableDictionary<NSString *, NSString *> *headers = [NSMutableDictionary dictionary];
  for (NSUInteger i = 0; i + 1 < flat.count; i += 2) {
    if ([flat[i] isKindOfClass:[NSString class]] && [flat[i + 1] isKindOfClass:[NSString class]]) {
      headers[flat[i]] = flat[i + 1];
    }
  }
  self.headers = headers;
  self.dataSource = RNAVGString(reactSource[@"dataSource"]);
  self.source = RNAVGString(reactSource[@"uri"]);
}

- (void)forwardEvents
{
  __weak RNAlphaVideoGpuView *weakSelf = self;
  self.onLoad = ^(NSInteger width, NSInteger height, NSInteger frameCount, CGFloat frameRate) {
    RCTDirectEventBlock block = weakSelf.reactOnLoad;
    if (!block) return;
    block(@{
      @"width": @(width),
      @"height": @(height),
      @"frameCount": @(frameCount),
      @"frameRate": @(frameRate),
      @"duration": @(frameCount > 0 && frameRate > 0 ? frameCount / frameRate : 0)
    });
  };
  self.onFrameUpdate = ^(NSInteger frame, NSInteger totalFrames) {
    RCTDirectEventBlock block = weakSelf.reactOnFrameUpdate;
    if (block) block(@{@"frame": @(frame), @"totalFrames": @(totalFrames)});
  };
  self.onStateChange = ^(NSString *state) {
    RCTDirectEventBlock block = weakSelf.reactOnStateChange;
    if (block) block(@{@"state": state});
  };
  self.onFinished = ^{
    RCTDirectEventBlock block = weakSelf.reactOnFinished;
    if (block) block(@{});
  };
  self.onError = ^(NSString *error, NSString *code) {
    RCTDirectEventBlock block = weakSelf.reactOnError;
    if (block) block(code ? @{@"error": error, @"code": code} : @{@"error": error});
  };
}

@end

#pragma mark - RNAlphaVideoGpuViewManager

@interface RNAlphaVideoGpuViewManager : RCTViewManager
@end

@implementation RNAlphaVideoGpuViewManager

RCT_EXPORT_MODULE(AlphaVideoGpuView)

+ (BOOL)requiresMainQueueSetup
{
  return YES;
}

- (UIView *)view
{
  return [[RNAlphaVideoGpuView alloc] initWithFrame:CGRectZero];
}

RCT_REMAP_VIEW_PROPERTY(source, reactSource, NSDictionary)
RCT_REMAP_VIEW_PROPERTY(paused, playbackPaused, BOOL)
RCT_EXPORT_VIEW_PROPERTY(cacheKey, NSString)
RCT_EXPORT_VIEW_PROPERTY(storage, NSString)
RCT_EXPORT_VIEW_PROPERTY(maxStorageBytes, int64_t)
RCT_EXPORT_VIEW_PROPERTY(autoPlay, BOOL)
RCT_EXPORT_VIEW_PROPERTY(loop, BOOL)
RCT_EXPORT_VIEW_PROPERTY(playbackSpeed, CGFloat)
RCT_EXPORT_VIEW_PROPERTY(enableFrameEvents, BOOL)
RCT_EXPORT_VIEW_PROPERTY(streamingPrefetchAhead, NSInteger)
RCT_EXPORT_VIEW_PROPERTY(memoryStrategy, NSString)
RCT_EXPORT_VIEW_PROPERTY(stream, BOOL)
RCT_EXPORT_VIEW_PROPERTY(verbose, BOOL)
RCT_REMAP_VIEW_PROPERTY(onLoad, reactOnLoad, RCTDirectEventBlock)
RCT_REMAP_VIEW_PROPERTY(onFrameUpdate, reactOnFrameUpdate, RCTDirectEventBlock)
RCT_REMAP_VIEW_PROPERTY(onStateChange, reactOnStateChange, RCTDirectEventBlock)
RCT_REMAP_VIEW_PROPERTY(onFinished, reactOnFinished, RCTDirectEventBlock)
RCT_REMAP_VIEW_PROPERTY(onError, reactOnError, RCTDirectEventBlock)

// The block's view registry is empty under the new architecture's interop layer, so look the
// view up by tag instead.
- (void)withView:(NSNumber *)reactTag run:(void (^)(AlphaVideoGpuView *view))body
{
  [self.bridge.uiManager addUIBlock:^(RCTUIManager *uiManager, NSDictionary<NSNumber *, UIView *> *viewRegistry) {
    UIView *view = [uiManager viewForReactTag:reactTag];
    if ([view isKindOfClass:[AlphaVideoGpuView class]]) body((AlphaVideoGpuView *)view);
  }];
}

RCT_EXPORT_METHOD(play:(nonnull NSNumber *)reactTag)
{
  [self withView:reactTag run:^(AlphaVideoGpuView *view) { [view play]; }];
}

RCT_EXPORT_METHOD(pause:(nonnull NSNumber *)reactTag)
{
  [self withView:reactTag run:^(AlphaVideoGpuView *view) { [view pause]; }];
}

RCT_EXPORT_METHOD(stop:(nonnull NSNumber *)reactTag)
{
  [self withView:reactTag run:^(AlphaVideoGpuView *view) { [view stop]; }];
}

RCT_EXPORT_METHOD(seekToFrame:(nonnull NSNumber *)reactTag frame:(nonnull NSNumber *)frame)
{
  [self withView:reactTag run:^(AlphaVideoGpuView *view) { [view seekToFrame:frame.integerValue]; }];
}

RCT_EXPORT_METHOD(seekToTime:(nonnull NSNumber *)reactTag seconds:(nonnull NSNumber *)seconds)
{
  [self withView:reactTag run:^(AlphaVideoGpuView *view) { [view seekToTime:seconds.doubleValue]; }];
}

@end

#endif
