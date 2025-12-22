//
//  KTX2PlayerViewManager.m
//  react-native-ktx2player
//

#import <React/RCTViewManager.h>

@interface KTX2PlayerViewManager : RCTViewManager
@end

@implementation KTX2PlayerViewManager

RCT_EXPORT_MODULE(KTX2PlayerView)

+ (BOOL)requiresMainQueueSetup
{
  return YES;
}

- (UIView *)view
{
  // Import and instantiate the view from KTX2Player.mm
  Class viewClass = NSClassFromString(@"KTX2PlayerView");
  if (viewClass) {
    return [[viewClass alloc] init];
  }
  return nil;
}

RCT_EXPORT_VIEW_PROPERTY(autoPlay, BOOL)
RCT_EXPORT_VIEW_PROPERTY(loop, BOOL)
RCT_EXPORT_VIEW_PROPERTY(playbackSpeed, CGFloat)
RCT_EXPORT_VIEW_PROPERTY(enableFrameEvents, BOOL)
RCT_EXPORT_VIEW_PROPERTY(cacheKey, NSString)
RCT_EXPORT_VIEW_PROPERTY(source, NSDictionary)
RCT_EXPORT_VIEW_PROPERTY(onFrameUpdate, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onStateChange, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onFinished, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onError, RCTDirectEventBlock)

@end
