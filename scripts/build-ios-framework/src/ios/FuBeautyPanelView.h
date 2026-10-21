#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

@protocol FuBeautyPanelViewDelegate <NSObject>
- (void)beautyPanelDidChangeHeight:(CGFloat)heightPts;
- (void)beautyPanelSelectTab:(NSString *)tabId expanded:(BOOL)expanded;
- (void)beautyPanelSelectEffect:(NSString *)key;
- (void)beautyPanelSliderChanged:(NSString *)key value:(double)value;
/** 恢复默认：原生面板已改完 UI，这里只负责 setParam（key→sdkValue） */
- (void)beautyPanelApplyRecoverDefaults:(NSDictionary<NSString *, NSNumber *> *)sdkParams
                                    tab:(NSString *)tabId;
- (void)beautyPanelSelectFilter:(NSString *)filterId filterKey:(NSString *)filterKey filterName:(NSString *)filterName;
- (void)beautyPanelWhiteningMode:(NSString *)mode; // global | skin
/** 恢复默认完成后的提示（纯原生 tip；可选顺带同步 JS 内存） */
- (void)beautyPanelDidRecoverDefaults:(NSString *)tabId;
- (void)beautyPanelCompareStart;
- (void)beautyPanelCompareEnd;
- (void)beautyPanelSave;
/** 媒体美颜页左上角返回（对齐选择页 back 箭头） */
- (void)beautyPanelBack;
@end

/**
 * 对齐 FULiveDemo FUBeautyComponent：底栏 Tab + 功能区 + 滑杆 + 对比；
 * 展开 0.2s translate；品牌色 #5EC7FE。
 */
@interface FuBeautyPanelView : UIView
@property (nonatomic, weak, nullable) id<FuBeautyPanelViewDelegate> delegate;
/** camera | image | video；media 模式显示保存钮、隐藏拍摄跟随由宿主负责 */
@property (nonatomic, copy) NSString *mode;
- (void)applyConfig:(NSDictionary *)config;
- (void)updateValues:(NSDictionary *)values;
- (void)setSelectedFilterId:(NSString *)filterId;
- (void)setSelectedEffectKey:(NSString *)key;
- (void)setWhiteningMode:(NSString *)mode;
/**
 * 将当前 tab（美肤/美型）参数恢复为配置默认值并刷新 UI。
 * @return SDK 写参字典（key→sdkValue），由宿主一次性落盘
 */
- (NSDictionary<NSString *, NSNumber *> *)recoverTabDefaults:(NSString *)tabId;
/** 切滤镜时读取该滤镜已存强度（SDK 0~1，对齐 Demo filterMap）。 */
- (double)peekFilterSdkIntensity:(NSString *)filterIdOrKey;
- (CGFloat)currentPanelHeight;
- (BOOL)hitInteractiveAtPoint:(CGPoint)point;
@end

NS_ASSUME_NONNULL_END
