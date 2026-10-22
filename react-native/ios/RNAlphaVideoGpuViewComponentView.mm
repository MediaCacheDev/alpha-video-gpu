//
//  RNAlphaVideoGpuViewComponentView.mm
//  Fabric component view for AlphaVideoGpuView (new architecture)
//

#ifdef RCT_NEW_ARCH_ENABLED

#import <React/RCTConversions.h>
#import <React/RCTViewComponentView.h>

#import <react/renderer/components/RNAlphaVideoGpuSpec/ComponentDescriptors.h>
#import <react/renderer/components/RNAlphaVideoGpuSpec/EventEmitters.h>
#import <react/renderer/components/RNAlphaVideoGpuSpec/Props.h>
#import <react/renderer/components/RNAlphaVideoGpuSpec/RCTComponentViewHelpers.h>

#import "AlphaVideoGPU.h"

using namespace facebook::react;

@interface RNAlphaVideoGpuViewComponentView : RCTViewComponentView <RCTAlphaVideoGpuViewViewProtocol>
@end

@implementation RNAlphaVideoGpuViewComponentView {
  AlphaVideoGpuView *_player;
}

+ (ComponentDescriptorProvider)componentDescriptorProvider
{
  return concreteComponentDescriptorProvider<AlphaVideoGpuViewComponentDescriptor>();
}

// A player holds its frames and GPU resources, so each mount gets a new one.
+ (BOOL)shouldBeRecycled
{
  return NO;
}

- (instancetype)initWithFrame:(CGRect)frame
{
  if (self = [super initWithFrame:frame]) {
    static const auto defaultProps = std::make_shared<const AlphaVideoGpuViewProps>();
    _props = defaultProps;
    [self createPlayer];
  }
  return self;
}

- (void)createPlayer
{
  _player = [[AlphaVideoGpuView alloc] initWithFrame:self.bounds];
  [self forwardEvents];
  self.contentView = _player;
}

// Older React Native versions ignore shouldBeRecycled and recycle every component view.
- (void)prepareForRecycle
{
  [super prepareForRecycle];
  [_player cleanup];
  [self createPlayer];
}

- (void)invalidate
{
  [_player cleanup];
}

- (void)updateProps:(const Props::Shared &)props oldProps:(const Props::Shared &)oldProps
{
  const auto &newProps = *std::static_pointer_cast<const AlphaVideoGpuViewProps>(props);

  _player.autoPlay = newProps.autoPlay;
  _player.loop = newProps.loop;
  _player.playbackSpeed = newProps.playbackSpeed;
  _player.playbackPaused = newProps.paused;
  _player.enableFrameEvents = newProps.enableFrameEvents;
  _player.stream = newProps.stream;
  _player.streamingRingSize = newProps.streamingRingSize;
  _player.streamingCacheSize = newProps.streamingCacheSize;
  _player.streamingPrefetchAhead = newProps.streamingPrefetchAhead;
  _player.memoryStrategy = RCTNSStringFromString(toString(newProps.memoryStrategy));
  _player.verbose = newProps.verbose;
  _player.storage = RCTNSStringFromString(toString(newProps.storage));
  _player.maxStorageBytes = (int64_t)newProps.maxStorageBytes;

  // Setting source starts the load, which reads every other prop, the cache key included.
  NSString *cacheKey = RCTNSStringFromStringNilIfEmpty(newProps.cacheKey)
      ?: RCTNSStringFromStringNilIfEmpty(newProps.source.cacheKey);
  _player.cacheKey = cacheKey;
  NSMutableDictionary<NSString *, NSString *> *headers = [NSMutableDictionary dictionary];
  for (size_t i = 0; i + 1 < newProps.source.headers.size(); i += 2) {
    headers[RCTNSStringFromString(newProps.source.headers[i])] = RCTNSStringFromString(newProps.source.headers[i + 1]);
  }
  _player.headers = headers;
  _player.dataSource = RCTNSStringFromStringNilIfEmpty(newProps.source.dataSource);
  _player.source = RCTNSStringFromStringNilIfEmpty(newProps.source.uri);

  [super updateProps:props oldProps:oldProps];
}

- (std::shared_ptr<const AlphaVideoGpuViewEventEmitter>)playerEventEmitter
{
  return std::static_pointer_cast<const AlphaVideoGpuViewEventEmitter>(_eventEmitter);
}

- (void)forwardEvents
{
  __weak RNAlphaVideoGpuViewComponentView *weakSelf = self;
  _player.onLoad = ^(NSInteger width, NSInteger height, NSInteger frameCount, CGFloat frameRate) {
    auto emitter = [weakSelf playerEventEmitter];
    if (!emitter) return;
    AlphaVideoGpuViewEventEmitter::OnLoad event;
    event.width = (int)width;
    event.height = (int)height;
    event.frameCount = (int)frameCount;
    event.frameRate = frameRate;
    event.duration = frameCount > 0 && frameRate > 0 ? frameCount / frameRate : 0;
    emitter->onLoad(event);
  };
  _player.onFrameUpdate = ^(NSInteger frame, NSInteger totalFrames) {
    auto emitter = [weakSelf playerEventEmitter];
    if (emitter) emitter->onFrameUpdate({.frame = (int)frame, .totalFrames = (int)totalFrames});
  };
  _player.onStateChange = ^(NSString *state) {
    auto emitter = [weakSelf playerEventEmitter];
    if (emitter) emitter->onStateChange({.state = RCTStringFromNSString(state)});
  };
  _player.onFinished = ^{
    auto emitter = [weakSelf playerEventEmitter];
    if (emitter) emitter->onFinished({});
  };
  _player.onError = ^(NSString *error, NSString *code) {
    auto emitter = [weakSelf playerEventEmitter];
    if (emitter) emitter->onError({.error = RCTStringFromNSString(error), .code = RCTStringFromNSString(code ?: @"")});
  };
}

#pragma mark - Commands

- (void)handleCommand:(const NSString *)commandName args:(const NSArray *)args
{
  RCTAlphaVideoGpuViewHandleCommand(self, commandName, args);
}

- (void)play
{
  [_player play];
}

- (void)pause
{
  [_player pause];
}

- (void)stop
{
  [_player stop];
}

- (void)seekToFrame:(NSInteger)frame
{
  [_player seekToFrame:frame];
}

- (void)seekToTime:(double)seconds
{
  [_player seekToTime:seconds];
}

@end

// Older React Native versions look the class up by this name instead of codegenConfig.ios.componentProvider.
extern "C" Class<RCTComponentViewProtocol> AlphaVideoGpuViewCls(void)
{
  return RNAlphaVideoGpuViewComponentView.class;
}

#endif
