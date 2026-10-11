//
//  AlphaVideoGpuViewManager.m
//  alpha-video-gpu
//

#import <React/RCTViewManager.h>

@interface AlphaVideoGpuViewManager : RCTViewManager
@end

@implementation AlphaVideoGpuViewManager

RCT_EXPORT_MODULE(AlphaVideoGpuView)

+ (BOOL)requiresMainQueueSetup
{
  return YES;
}

- (UIView *)view
{
  // Import and instantiate the view from AlphaVideoGpu.mm
  Class viewClass = NSClassFromString(@"AlphaVideoGpuView");
  if (viewClass) {
    return [[viewClass alloc] init];
  }
  return nil;
}

RCT_EXPORT_VIEW_PROPERTY(autoPlay, BOOL)
RCT_EXPORT_VIEW_PROPERTY(loop, BOOL)
RCT_EXPORT_VIEW_PROPERTY(playbackSpeed, CGFloat)
RCT_EXPORT_VIEW_PROPERTY(enableFrameEvents, BOOL)
RCT_EXPORT_VIEW_PROPERTY(gpuPlayback, BOOL)
RCT_EXPORT_VIEW_PROPERTY(reactPaused, BOOL)
RCT_EXPORT_VIEW_PROPERTY(cacheKey, NSString)
RCT_EXPORT_VIEW_PROPERTY(streamingRingSize, NSInteger)
RCT_EXPORT_VIEW_PROPERTY(streamingCacheSize, NSInteger)
RCT_EXPORT_VIEW_PROPERTY(streamingPrefetchAhead, NSInteger)
RCT_EXPORT_VIEW_PROPERTY(memoryStrategy, NSString)
RCT_EXPORT_VIEW_PROPERTY(stream, BOOL)
RCT_EXPORT_VIEW_PROPERTY(verbose, BOOL)
RCT_EXPORT_VIEW_PROPERTY(source, NSDictionary)
RCT_EXPORT_VIEW_PROPERTY(parts, NSArray)
RCT_EXPORT_VIEW_PROPERTY(onLoad, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onFrameUpdate, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onStateChange, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onFinished, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onError, RCTDirectEventBlock)

@end
