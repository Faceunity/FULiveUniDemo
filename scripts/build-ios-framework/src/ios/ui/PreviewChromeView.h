#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

@protocol PreviewChromeViewDelegate <NSObject>
- (void)previewChromeCaptureTouchDown;
- (void)previewChromeCaptureLongPress;
- (void)previewChromeCaptureTouchUp:(BOOL)wasLongPress;
- (void)previewChromeCompareStart;
- (void)previewChromeCompareEnd;
- (void)previewChromeHome;
- (void)previewChromeSwitchCamera;
- (void)previewChromeToggleDualInput:(BOOL)dual;
- (void)previewChromeSelectResolution:(NSString *)resolutionId;
- (void)previewChromeImportMedia;
- (void)previewChromeDebugVisibleChanged:(BOOL)visible;
@end

/** 取景原生 HUD：顶栏 + 拍摄/对比 + debug（对齐 Android PreviewChromeView） */
@interface PreviewChromeView : UIView
@property (nonatomic, weak, nullable) id<PreviewChromeViewDelegate> delegate;
- (void)updateStatsWithResolution:(NSString *)resolution fps:(int)fps renderTimeMs:(int)renderTimeMs;
- (void)setRecording:(BOOL)recording;
- (void)setSelectedResolutionId:(NSString *)resolutionId;
- (void)setDualInputState:(BOOL)dual;
- (void)setDebugVisibleState:(BOOL)visible;
/** 美颜面板高度变化时上推拍摄/对比（Demo 0.15s） */
- (void)setBottomChromeInset:(CGFloat)insetPts animated:(BOOL)animated;
- (void)setCompareButtonHidden:(BOOL)hidden;
/** 取景区中央短暂弹出滤镜名 */
- (void)showFilterName:(NSString *)name;
/** 未检测到人脸：盖在取景区中央 */
- (void)setNoFaceVisible:(BOOL)visible;
/** 机型限制提示：半透明黑底，位于「未检测到人脸」上方 */
- (void)showPerfLimitTip:(NSString *)message;
- (BOOL)hitInteractiveAtPoint:(CGPoint)point;
@end

NS_ASSUME_NONNULL_END
