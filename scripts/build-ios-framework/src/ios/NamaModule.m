#import "NamaModule.h"
#import "CNamaSDK.h"
#import "DCUniDefine.h"
#import "authpack.h"
#import "BeautyCameraView.h"
#import "BeautyVideoView.h"
#import "PreviewChromeView.h"
#import "FuBeautyPanelView.h"

// 运行时取类，避免 slim 漏链 PreviewChromeView.o 时留下未定义 _OBJC_CLASS_$_ 导致基座加载即闪退
static Class FuPreviewChromeViewClass(void) {
    static Class cls;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        cls = NSClassFromString(@"PreviewChromeView");
    });
    return cls;
}
static Class FuBeautyPanelViewClass(void) {
    static Class cls;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        cls = NSClassFromString(@"FuBeautyPanelView");
    });
    return cls;
}
#import "VideoBeautyExporter.h"

#import <string.h>
#import <sys/sysctl.h>
#import "FuBeautyHandle.h"
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <OpenGLES/EAGL.h>
#import <Photos/Photos.h>
#import <MobileCoreServices/MobileCoreServices.h>
#import <stdbool.h>

#define FU_LOG(fmt, ...) do { } while (0)
/** PreviewChrome 拍摄/对比按钮 tag，overlay hitTest 放行 */
static const NSInteger kFuChromeInteractiveTag = 88219901;

/** 对齐 Android FuExportProgressHud：圆环 + 百分比 */
@interface FuExportProgressRingView : UIView
@property (nonatomic, assign) CGFloat progress;
@end
@implementation FuExportProgressRingView
- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.backgroundColor = [UIColor clearColor];
        self.opaque = NO;
    }
    return self;
}
- (void)setProgress:(CGFloat)progress {
    _progress = MAX(0.f, MIN(1.f, progress));
    [self setNeedsDisplay];
}
- (void)drawRect:(CGRect)rect {
    CGFloat lineW = 4.f;
    CGRect oval = CGRectInset(rect, 10.f, 10.f);
    CGPoint c = CGPointMake(CGRectGetMidX(oval), CGRectGetMidY(oval));
    CGFloat r = CGRectGetWidth(oval) * 0.5f;
    UIBezierPath *bg = [UIBezierPath bezierPathWithArcCenter:c radius:r startAngle:0 endAngle:(CGFloat)(2.0 * M_PI) clockwise:YES];
    bg.lineWidth = lineW;
    [[UIColor colorWithWhite:1 alpha:0.27] setStroke];
    [bg stroke];
    if (_progress <= 0.0001f) {
        return;
    }
    UIBezierPath *arc = [UIBezierPath bezierPathWithArcCenter:c
                                                      radius:r
                                                  startAngle:(CGFloat)(-M_PI_2)
                                                    endAngle:(CGFloat)(-M_PI_2 + 2.0 * M_PI * _progress)
                                                   clockwise:YES];
    arc.lineWidth = lineW;
    arc.lineCapStyle = kCGLineCapRound;
    [[UIColor colorWithRed:0.369 green:0.780 blue:0.996 alpha:1] setStroke];
    [arc stroke];
}
@end

int FuBeautyCameraHandle = 0;
int FuBeautyMediaHandle = 0;
int FuBeautyItemHandle = 0;
static BOOL sAdvancedBeautyGlReady = NO;
static BOOL sAdvancedBeautySetUseApplied = NO;

/** 关闭 SDK 文件/控制台日志（DEBUG 写盘会拖慢预览） */
static void FuDisableSdkLogging(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        fuSetLogLevel(FU_LOG_LEVEL_OFF);
    });
}

static int FuItemSetParamdLogged(int handle, const char *name, double value) {
    return fuItemSetParamd(handle, name, value);
}

static int FuItemSetParamsLogged(int handle, const char *name, const char *value) {
    return fuItemSetParams(handle, name, value);
}

int FuDevicePerformanceLevelCached(void) {
    static int sLevel = -1;
    if (sLevel > 0) {
        return sLevel;
    }
    int level = 0;
    if (fuIsLibraryInit()) {
        @try {
            int sdk = fuGetDeviceLevel();
            // SDK：1~4 有效；-99 表示无推荐
            if (sdk >= 1 && sdk <= 4) {
                level = sdk;
            }
        } @catch (__unused NSException *e) {
        }
    }
    sLevel = MAX(1, MIN(4, level > 0 ? level : 1));
    return sLevel;
}

void FuBeautySetPipelineHandle(BOOL media, int handle) {
    if (media) {
        FuBeautyMediaHandle = handle;
    } else {
        FuBeautyCameraHandle = handle;
        FuBeautyItemHandle = handle;
    }
}

void FuBeautyClearCamera(void) {
    FuBeautyCameraHandle = 0;
    FuBeautyItemHandle = 0;
}

void FuBeautyClearMedia(void) {
    FuBeautyMediaHandle = 0;
}

void FuBeautyClearAll(void) {
    FuBeautyCameraHandle = 0;
    FuBeautyMediaHandle = 0;
    FuBeautyItemHandle = 0;
}

void FuSetAdvancedBeautyGlReady(BOOL ready) {
    sAdvancedBeautyGlReady = ready ? YES : NO;
    if (!ready) {
        sAdvancedBeautySetUseApplied = NO;
    }
}

/**
 * 公开侧运行时参数。 * 切勿调用 FUAI_FaceProcessorSetUse*：未公开 API，多处时机都会空指针闪退。 * 切勿在此强制 enable_skinseg=0：会冲掉前端「仅皮肤 / 全局」分段。 */
static int sLastBlurType = -1;
static int sLastBlurMask = -1;
/** 曝光条进度；极端 EV 时强制精细磨皮关 mask，避免均匀磨皮噪点密密麻麻闪 */
static double sExposureNormForBlur = 0.5;

void FuResetBeautyBlurCache(void) {
    sLastBlurType = -1;
    sLastBlurMask = -1;
}

void FuSetExposureNormalizedForBlur(double normalized01) {
    double n = normalized01;
    if (n < 0.0) {
        n = 0.0;
    } else if (n > 1.0) {
        n = 1.0;
    }
    BOOL extremeBefore = (sExposureNormForBlur < 0.45 || sExposureNormForBlur > 0.58);
    BOOL extremeAfter = (n < 0.45 || n > 0.58);
    sExposureNormForBlur = n;
    if (extremeBefore != extremeAfter) {
        FuResetBeautyBlurCache();
    }
}

/** 官方文档：blur_use_mask 仅在 blur_type==2（精细磨皮）时生效；type=3 时开 mask 会出灰蒙。
 * 对齐 Android：按机型写一次默认；全身磨皮开启时强制关 mask，关闭后恢复机型默认。 */
void FuUpdateBeautyBlurEffect(int beautyHandle) {
    if (beautyHandle <= 0 || fuIsLibraryInit() == 0) {
        return;
    }
    int level = FuDevicePerformanceLevelCached();
    int wantType;
    int wantMask;
    if (level >= 3) {
        // 高端：均匀磨皮 EquallySkinFine；mask 必须关
        wantType = 3;
        wantMask = 0;
    } else {
        // 中低端：官方推荐精细磨皮 + 人脸 mask
        wantType = 2;
        wantMask = 1;
    }
    // 全身磨皮与人脸 mask 叠加会灰蒙；开启时关 mask，关掉后应恢复机型默认
    double bodyBlur = fuItemGetParamd(beautyHandle, "body_blur_level");
    if (bodyBlur > 0.001) {
        wantMask = 0;
    }
    // 对齐 Android applyBlurForExposure：极端曝光下均匀磨皮会出密密麻麻闪点
    if (sExposureNormForBlur < 0.45 || sExposureNormForBlur > 0.58) {
        wantType = 2;
        wantMask = 0;
    }
    if (wantType != sLastBlurType || wantMask != sLastBlurMask) {
        FuItemSetParamdLogged(beautyHandle, "heavy_blur", 0.0);
        FuItemSetParamdLogged(beautyHandle, "blur_type", (double)wantType);
        FuItemSetParamdLogged(beautyHandle, "blur_use_mask", (double)wantMask);
        sLastBlurType = wantType;
        sLastBlurMask = wantMask;
    }
}

/** 特效写参前轻量确认公开开关（对齐 Android ensureAdvancedBeautySwitches）；不重置磨皮缓存 */
static volatile BOOL sBeautyChangeFramesHoldZero = NO;

void FuSetBeautyChangeFramesHoldZero(BOOL hold) {
    sBeautyChangeFramesHoldZero = hold ? YES : NO;
}

BOOL FuBeautyChangeFramesHoldZero(void) {
    return sBeautyChangeFramesHoldZero;
}

double FuBeautyChangeFramesValue(void) {
    return sBeautyChangeFramesHoldZero ? 0.0 : 12.0;
}

void FuEnsureAdvancedBeautySwitches(int beautyHandle) {
    if (beautyHandle <= 0) {
        return;
    }
    FuItemSetParamdLogged(beautyHandle, "disable_delspot", 0.0);
    FuItemSetParamdLogged(beautyHandle, "use_facial_plump", 1.0);
    FuItemSetParamdLogged(beautyHandle, "heavy_blur", 0.0);
    FuItemSetParamdLogged(beautyHandle, "skin_detect", 0.0);
    FuItemSetParamdLogged(beautyHandle, "face_shape", 4.0);
    FuItemSetParamdLogged(beautyHandle, "face_shape_level", 1.0);
    FuItemSetParamdLogged(beautyHandle, "change_frames", FuBeautyChangeFramesValue());
    FuItemSetParamdLogged(beautyHandle, "enable_warp_anti_alias", 1.0);
    FuItemSetParamdLogged(beautyHandle, "warp_anti_alias", 1.0);
}

static NSMutableDictionary<NSString *, NSNumber *> *sSpecialBeautyCache;

static NSString *FuSpecialCacheKey(int beautyHandle, const char *key) {
    return [NSString stringWithFormat:@"%d:%s", beautyHandle, key ?: ""];
}

void FuCacheSpecialBeautyValue(int beautyHandle, const char *key, double value) {
    if (beautyHandle <= 0 || !key) {
        return;
    }
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        sSpecialBeautyCache = [NSMutableDictionary dictionary];
    });
    @synchronized (sSpecialBeautyCache) {
        sSpecialBeautyCache[FuSpecialCacheKey(beautyHandle, key)] = @(value);
    }
}

double FuCachedSpecialBeautyValue(int beautyHandle, const char *key) {
    if (beautyHandle <= 0 || !key || !sSpecialBeautyCache) {
        return 0.0;
    }
    @synchronized (sSpecialBeautyCache) {
        NSNumber *n = sSpecialBeautyCache[FuSpecialCacheKey(beautyHandle, key)];
        return n ? n.doubleValue : 0.0;
    }
}

static BOOL FuHasCachedSpecialBeautyValue(int beautyHandle, const char *key) {
    if (beautyHandle <= 0 || !key || !sSpecialBeautyCache) {
        return NO;
    }
    @synchronized (sSpecialBeautyCache) {
        return sSpecialBeautyCache[FuSpecialCacheKey(beautyHandle, key)] != nil;
    }
}

static double FuEffectiveSpecialValue(int beautyHandle, const char *key) {
    // 用户滑杆写入 cache 后优先信 cache，避免 SDK 帧间把 facial_plump 等清回 0
    if (FuHasCachedSpecialBeautyValue(beautyHandle, key)) {
        return FuCachedSpecialBeautyValue(beautyHandle, key);
    }
    return fuItemGetParamd(beautyHandle, key);
}

BOOL FuIsSpecialBeautyParamName(const char *key) {
    if (!key) {
        return NO;
    }
    return strcmp(key, "body_blur_level") == 0 ||
        strcmp(key, "delspot_level") == 0 ||
        strcmp(key, "facial_plump") == 0 ||
        strcmp(key, "intensity_eye_pupil") == 0 ||
        strcmp(key, "enable_skinseg") == 0;
}

/** 批量 flush 时用：不再重复 FuEnsureAdvancedBeautySwitches */
void FuApplyBeautyParamDirectOnGl(int beautyHandle, const char *key, double value) {
    if (beautyHandle <= 0 || !key) {
        return;
    }
    if (strcmp(key, "enable_skinseg") == 0) {
        fuItemSetParamd(beautyHandle, "enable_skinseg", value);
        return;
    }
    fuItemSetParamd(beautyHandle, key, value);
    if (strcmp(key, "eye_bright") == 0) {
        fuItemSetParamd(beautyHandle, "eye_bright_v2", value);
    }
    if (strcmp(key, "facial_plump") == 0 || strcmp(key, "delspot_level") == 0) {
        fuItemSetParamd(beautyHandle, "disable_delspot", 0.0);
        if (strcmp(key, "facial_plump") == 0) {
            fuItemSetParamd(beautyHandle, "use_facial_plump", 1.0);
            fuItemSetParamd(beautyHandle, "facial_plump", value);
        } else {
            fuItemSetParamd(beautyHandle, "delspot_level", value);
        }
    } else if (FuIsSpecialBeautyParamName(key)) {
        fuItemSetParamd(beautyHandle, "disable_delspot", 0.0);
    }
    if (strcmp(key, "body_blur_level") == 0) {
        FuResetBeautyBlurCache();
        FuUpdateBeautyBlurEffect(beautyHandle);
    }
    FuCacheSpecialBeautyValue(beautyHandle, key, value);
}

/** 对齐 Android BeautyParamApplier.applySpecialAlgoParam */
void FuApplySpecialBeautyParamOnGl(int beautyHandle, const char *key, double value) {
    if (beautyHandle <= 0 || !key) {
        return;
    }
    FuEnsureAdvancedBeautySwitches(beautyHandle);
    FuApplyBeautyParamDirectOnGl(beautyHandle, key, value);
}

/** 滑杆拖动时 ValueChanged 可能节流，SDK 也可能帧间清 use_facial_plump；每帧用 cache+get 重 latch */
void FuReconfirmSpecialBeautySwitches(int beautyHandle) {
    if (beautyHandle <= 0) {
        return;
    }
    double plump = FuEffectiveSpecialValue(beautyHandle, "facial_plump");
    // 始终开启丰盈开关，仅强度为 0 时无视觉效果（对齐高端机可常开 use_facial_plump）
    fuItemSetParamd(beautyHandle, "disable_delspot", 0.0);
    fuItemSetParamd(beautyHandle, "use_facial_plump", 1.0);
    fuItemSetParamd(beautyHandle, "facial_plump", plump);
    double delspot = FuEffectiveSpecialValue(beautyHandle, "delspot_level");
    if (delspot > 0.001) {
        fuItemSetParamd(beautyHandle, "disable_delspot", 0.0);
        fuItemSetParamd(beautyHandle, "delspot_level", delspot);
    }
    double pupil = FuEffectiveSpecialValue(beautyHandle, "intensity_eye_pupil");
    if (pupil > 0.001) {
        fuItemSetParamd(beautyHandle, "disable_delspot", 0.0);
        fuItemSetParamd(beautyHandle, "intensity_eye_pupil", pupil);
    }
    double skinseg = FuEffectiveSpecialValue(beautyHandle, "enable_skinseg");
    if (skinseg > 0.001) {
        fuItemSetParamd(beautyHandle, "enable_skinseg", skinseg);
    }
    double eyeBright = FuEffectiveSpecialValue(beautyHandle, "eye_bright");
    if (eyeBright > 0.001) {
        fuItemSetParamd(beautyHandle, "eye_bright", eyeBright);
        fuItemSetParamd(beautyHandle, "eye_bright_v2", eyeBright);
    }
}

void FuEnableAdvancedBeautyRuntime(int beautyHandle) {
    if (beautyHandle <= 0) {
        return;
    }
    FuEnsureAdvancedBeautySwitches(beautyHandle);
    FuResetBeautyBlurCache();
    FuUpdateBeautyBlurEffect(beautyHandle);
}

/** 首帧 fuRender 成功后再开未公开 SetUse*（须在持有Nama GL current 的渲染线程调用） */
void FuTryApplyAdvancedBeautySetUseAfterRender(int beautyHandle) {
    (void)beautyHandle;
    // 未公开 SetUse* 已禁用（会闪退）；仅保留公开道具参数边沿触发
    if (!sAdvancedBeautyGlReady || !fuIsAIModelLoaded(FUAITYPE_FACEPROCESSOR)) {
        return;
    }
    if (!sAdvancedBeautySetUseApplied) {
        int h = beautyHandle > 0 ? beautyHandle : FuBeautyCameraHandle;
        if (h > 0) {
            FuItemSetParamdLogged(h, "disable_delspot", 0.0);
            FuItemSetParamdLogged(h, "use_facial_plump", 1.0);
        }
        sAdvancedBeautySetUseApplied = YES;
    }
}

/** 仅命中曝光滑杆，其余触摸穿透到下层业务窗WebView（对齐Android PopupWindow）*/
@interface FuPassThroughHost : UIView
@end
@implementation FuPassThroughHost
- (UIView *)hitTest:(CGPoint)point withEvent:(UIEvent *)event {
    UIView *hit = [super hitTest:point withEvent:event];
    if (!hit || hit == self) {
        return nil;
    }
    UIView *v = hit;
    while (v && v != self) {
        if ([v isKindOfClass:[UISlider class]]) {
            return hit;
        }
        if (v.tag == kFuChromeInteractiveTag) {
            return hit;
        }
        // 导出进度遮罩 / 播放钮等
        if (v.tag == 88219902) {
            return hit;
        }
        if ([v isKindOfClass:[FuBeautyPanelView class]]) {
            return hit;
        }
        if ([v isKindOfClass:[UIButton class]] && v.userInteractionEnabled) {
            return hit;
        }
        v = v.superview;
    }
    return nil;
}
@end

/**
 * 对齐 Android ZOrderOnTop：预览窗只合成画面。 * hitTest 仅放行UISlider（曝光条）；其余返回 nil，触摸落到业务WKWebView。 * 根因：普通UIWindow + rootVC.view 会吞掉PassThroughHost 返回的nil。 */
@interface FuOverlayWindow : UIWindow
@end
@implementation FuOverlayWindow
- (UIView *)hitTest:(CGPoint)point withEvent:(UIEvent *)event {
    UIView *hit = [super hitTest:point withEvent:event];
    if (!hit || hit == self || hit == self.rootViewController.view) {
        return nil;
    }
    UIView *v = hit;
    while (v && v != self) {
        if ([v isKindOfClass:[UISlider class]]) {
            return hit;
        }
        if (v.tag == kFuChromeInteractiveTag) {
            return hit;
        }
        if (v.tag == 88219902) {
            return hit;
        }
        if ([v isKindOfClass:[FuBeautyPanelView class]]) {
            return hit;
        }
        if ([v isKindOfClass:[UIButton class]] && v.userInteractionEnabled) {
            return hit;
        }
        v = v.superview;
    }
    return nil;
}
@end

static BeautyCameraView *sOverlayCameraView = nil;
static UIView *sOverlayCameraHost = nil;
static BOOL sCameraHostedByComponent = NO;
static BeautyVideoView *sOverlayVideoView = nil;
static UIView *sOverlayVideoHost = nil;
static UIButton *sOverlayVideoPlayBtn = nil;
static UIWindow *sExportProgressWindow = nil;
static UIView *sExportProgressHud = nil;
static UILabel *sExportProgressPercentLabel = nil;
static UILabel *sExportProgressTipLabel = nil;
static FuExportProgressRingView *sExportProgressRing = nil;
static UIButton *sExportProgressCancelBtn = nil;
static BOOL sExportCancelled = NO;
/** 恢复默认批量写参时抑制逐条 slider 事件（对齐 Android，避免 JS 风暴） */
static BOOL sSuppressBeautyPanelSliderEvents = NO;
static dispatch_queue_t gProcessImageQueue;
static BOOL gProcessImageRunning = NO;
static BOOL gProcessImageRunAgain = NO;
static NSDictionary *gProcessImagePendingOpts = nil;
static UniModuleKeepAliveCallback gProcessImagePendingCallback = nil;

static dispatch_queue_t FuProcessImageQueue(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        gProcessImageQueue = dispatch_queue_create("com.faceunity.processImage", DISPATCH_QUEUE_SERIAL);
    });
    return gProcessImageQueue;
}
static CGFloat sLastBeautyPanelHeight = 0;
static BOOL sChromeInsetDidInitial = NO;
static UIWindow *sOverlayWindow = nil;
static UIWindow *sConfirmWindow = nil;
static __weak UIWindow *sConfirmPrevKeyWindow = nil;
static PreviewChromeView *sPreviewChromeView = nil;
static FuBeautyPanelView *sBeautyPanelView = nil;
static UIButton *sMediaBackBtn = nil;
static __weak NamaModule *sNamaModuleWeak = nil;
static void (^sPendingConfirmFinish)(BOOL) = nil;
static NSString *sLastVideoPath = nil;
static int sOverlayBringGen = 0;
static int sLastCssX = -1;
static int sLastCssY = -1;
static int sLastCssW = -1;
static int sLastCssH = -1;
/** 稳定取景框：拒绝 convertRect 偶发把整层往上甩 */
static CGRect sLastStableOverlayBox;
static BOOL sHasStableOverlayBox = NO;

@implementation NamaModule (HostedCamera)

+ (void)attachHostedCameraView:(BeautyCameraView *)view {
    if (!view) {
        return;
    }
    if (sOverlayCameraHost) {
        [sOverlayCameraHost removeFromSuperview];
        sOverlayCameraHost = nil;
    }
    sOverlayCameraView = view;
    sOverlayCameraHost = nil;
    sCameraHostedByComponent = YES;
    FU_LOG("attachHostedCameraView");
}

+ (void)detachHostedCameraView:(BeautyCameraView *)view {
    if (!view) {
        return;
    }
    if (sOverlayCameraView == view) {
        sOverlayCameraView = nil;
        sCameraHostedByComponent = NO;
        FU_LOG("detachHostedCameraView");
    }
}

@end

static void FuEnableFaceAlgorithmModules(void) {
    fuSetMachineType(FUAIMACHINE_HIGH);
    fuSetDynamicQualityControl(false);
    fuSetFaceAlgorithmConfig(FUAIFACE_ENABLE_ALL);
    fuSetARMeshV2(true);
    FU_LOG("FuEnableFaceAlgorithmModules config=ENABLE_ALL armeshV2=1");
}

@implementation NamaModule {
    int _beautyHandle;
    int _frameId;
    UniModuleKeepAliveCallback _pickMediaCallback;
}

//  让NamaModule 能收 UIImagePicker 回调（对齐FULiveDemo 系统相册白底+取消）
- (void)imagePickerController:(UIImagePickerController *)picker
didFinishPickingMediaWithInfo:(NSDictionary<UIImagePickerControllerInfoKey,id> *)info {
    [picker dismissViewControllerAnimated:YES completion:nil];
    UniModuleKeepAliveCallback cb = _pickMediaCallback;
    _pickMediaCallback = nil;
    if (!cb) {
        return;
    }
    NSString *mediaType = info[UIImagePickerControllerMediaType];
    if ([mediaType isEqualToString:(NSString *)kUTTypeImage]) {
        UIImage *image = info[UIImagePickerControllerOriginalImage];
        if (![image isKindOfClass:[UIImage class]]) {
            cb(@{ @"code": @(-1), @"message": @"未选择图片" }, NO);
            return;
        }
        NSString *path = [NSTemporaryDirectory() stringByAppendingPathComponent:
                          [NSString stringWithFormat:@"nama_pick_%@.jpg", @((long long)(NSDate.date.timeIntervalSince1970 * 1000))]];
        NSData *data = UIImageJPEGRepresentation(image, 0.95);
        if (!data || ![data writeToFile:path atomically:YES]) {
            cb(@{ @"code": @(-1), @"message": @"保存图片失败" }, NO);
            return;
        }
        cb(@{ @"code": @(0), @"data": @{ @"path": path, @"type": @"image" } }, NO);
        return;
    }
    // 视频：优先MediaURL，再兜底拷贝
    NSURL *mediaURL = info[UIImagePickerControllerMediaURL];
    if ([mediaURL isKindOfClass:[NSURL class]] && mediaURL.isFileURL) {
        NSString *ext = mediaURL.pathExtension.length > 0 ? mediaURL.pathExtension : @"mp4";
        NSString *dest = [NSTemporaryDirectory() stringByAppendingPathComponent:
                          [NSString stringWithFormat:@"nama_pick_%@.%@", @((long long)(NSDate.date.timeIntervalSince1970 * 1000)), ext]];
        NSError *err = nil;
        [[NSFileManager defaultManager] removeItemAtPath:dest error:nil];
        if ([[NSFileManager defaultManager] copyItemAtURL:mediaURL toURL:[NSURL fileURLWithPath:dest] error:&err]) {
            cb(@{ @"code": @(0), @"data": @{ @"path": dest, @"type": @"video" } }, NO);
            return;
        }
        //  拷贝失败则直接用原路径
        cb(@{ @"code": @(0), @"data": @{ @"path": mediaURL.path ?: @"", @"type": @"video" } }, NO);
        return;
    }
    cb(@{ @"code": @(-1), @"message": @"未选择视频" }, NO);
}

- (void)imagePickerControllerDidCancel:(UIImagePickerController *)picker {
    [picker dismissViewControllerAnimated:YES completion:nil];
    UniModuleKeepAliveCallback cb = _pickMediaCallback;
    _pickMediaCallback = nil;
    if (cb) {
        cb(@{ @"code": @(-1), @"message": @"用户取消选择" }, NO);
    }
}

/**
 * 对齐 FULiveDemo FUMediaPickerViewController： * UIImagePickerController + SavedPhotosAlbum →系统白底相册，左上角「取消」。 */
UNI_EXPORT_METHOD(@selector(pickMediaFromAlbum:callback:))
- (void)pickMediaFromAlbum:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    if (callback) {
        // keepAlive：等用户选完再二次回调（与showConfirm 同模式）
        callback(@{ @"code": @(0), @"data": @{ @"pending": @(1) } }, YES);
    }
    NSString *type = [options[@"type"] isKindOfClass:[NSString class]] ? options[@"type"] : @"image";
    BOOL wantVideo = [type caseInsensitiveCompare:@"video"] == NSOrderedSame;
    dispatch_async(dispatch_get_main_queue(), ^{
        void (^presentPicker)(void) = ^{
            if (self->_pickMediaCallback) {
                // 上一次未结束
                if (callback) {
                    callback(@{ @"code": @(-1), @"message": @"相册占用中，请返回后重试" }, NO);
                }
                return;
            }
            self->_pickMediaCallback = [callback copy];
            UIImagePickerController *picker = [[UIImagePickerController alloc] init];
            picker.delegate = (id<UINavigationControllerDelegate, UIImagePickerControllerDelegate>)self;
            picker.sourceType = UIImagePickerControllerSourceTypeSavedPhotosAlbum;
            picker.allowsEditing = NO;
            picker.mediaTypes = wantVideo
                ? @[ (NSString *)kUTTypeMovie ]
                : @[ (NSString *)kUTTypeImage ];
            picker.modalPresentationStyle = UIModalPresentationFullScreen;
            UIViewController *host = [self fuTopViewController];
            if (!host) {
                self->_pickMediaCallback = nil;
                if (callback) {
                    callback(@{ @"code": @(-1), @"message": @"无法打开相册" }, NO);
                }
                return;
            }
            [host presentViewController:picker animated:YES completion:nil];
        };

        PHAuthorizationStatus status = [PHPhotoLibrary authorizationStatus];
        if (@available(iOS 14, *)) {
            status = [PHPhotoLibrary authorizationStatusForAccessLevel:PHAccessLevelReadWrite];
        }
        BOOL ok = (status == PHAuthorizationStatusAuthorized);
        if (@available(iOS 14, *)) {
            ok = ok || (status == PHAuthorizationStatusLimited);
        }
        if (ok) {
            presentPicker();
            return;
        }
        if (status == PHAuthorizationStatusDenied || status == PHAuthorizationStatusRestricted) {
            if (callback) {
                callback(@{ @"code": @(-1), @"message": @"需要相册权限才能选择媒体" }, NO);
            }
            return;
        }
        [PHPhotoLibrary requestAuthorization:^(PHAuthorizationStatus auth) {
            dispatch_async(dispatch_get_main_queue(), ^{
                BOOL granted = (auth == PHAuthorizationStatusAuthorized);
                if (@available(iOS 14, *)) {
                    granted = granted || (auth == PHAuthorizationStatusLimited);
                }
                if (!granted) {
                    if (callback) {
                        callback(@{ @"code": @(-1), @"message": @"需要相册权限才能选择媒体" }, NO);
                    }
                    return;
                }
                presentPicker();
            });
        }];
    });
}

#pragma mark - PreviewChrome (对齐 Android Popup)

- (void)firePreviewChromeEvent:(NSString *)action extra:(NSDictionary *)extra {
    NSMutableDictionary *payload = [@{ @"action": action ?: @"" } mutableCopy];
    if (extra.count > 0) {
        [payload addEntriesFromDictionary:extra];
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        [self.uniInstance fireGlobalEvent:@"namaPreviewChrome" params:payload];
    });
}

- (void)previewChromeCaptureTouchDown {
    [self firePreviewChromeEvent:@"captureDown" extra:nil];
}
- (void)previewChromeCaptureLongPress {
    [self firePreviewChromeEvent:@"captureLongPress" extra:nil];
}
- (void)previewChromeCaptureTouchUp:(BOOL)wasLongPress {
    [self firePreviewChromeEvent:@"captureUp" extra:@{ @"longPress": @(wasLongPress) }];
}
- (void)previewChromeCompareStart {
    [self firePreviewChromeEvent:@"compareStart" extra:nil];
}
- (void)previewChromeCompareEnd {
    [self firePreviewChromeEvent:@"compareEnd" extra:nil];
}
- (void)previewChromeHome {
    [self firePreviewChromeEvent:@"home" extra:nil];
}
- (void)previewChromeSwitchCamera {
    [self firePreviewChromeEvent:@"switchCamera" extra:nil];
}
- (void)previewChromeToggleDualInput:(BOOL)dual {
    [self firePreviewChromeEvent:@"dualInput" extra:@{ @"dual": @(dual) }];
}
- (void)previewChromeSelectResolution:(NSString *)resolutionId {
    [self firePreviewChromeEvent:@"resolution" extra:@{ @"id": resolutionId ?: @"" }];
}
- (void)previewChromeImportMedia {
    [self firePreviewChromeEvent:@"importMedia" extra:nil];
}
- (UIViewController *)fuTopViewController {
    UIWindow *window = [self resolveBusinessWindow];
    if (!window) {
        if (@available(iOS 13.0, *)) {
            for (UIScene *scene in UIApplication.sharedApplication.connectedScenes) {
                if (scene.activationState != UISceneActivationStateForegroundActive) {
                    continue;
                }
                if (![scene isKindOfClass:[UIWindowScene class]]) {
                    continue;
                }
                for (UIWindow *w in ((UIWindowScene *)scene).windows) {
                    if (w == sOverlayWindow || w == sConfirmWindow || w == sExportProgressWindow) {
                        continue;
                    }
                    if (w.isKeyWindow) {
                        window = w;
                        break;
                    }
                    if (!window && !w.hidden) {
                        window = w;
                    }
                }
                if (window) {
                    break;
                }
            }
        }
    }
    if (!window) {
        window = UIApplication.sharedApplication.keyWindow;
        if (window == sOverlayWindow || window == sConfirmWindow) {
            window = nil;
        }
    }
    UIViewController *vc = window.rootViewController;
    while (vc.presentedViewController) {
        vc = vc.presentedViewController;
    }
    return vc;
}

- (void)previewChromeDebugVisibleChanged:(BOOL)visible {
    [self firePreviewChromeEvent:@"debugVisible" extra:@{ @"visible": @(visible) }];
}

- (void)dismissPreviewChrome {
    if (sPreviewChromeView) {
        [sPreviewChromeView removeFromSuperview];
        sPreviewChromeView = nil;
    }
}

- (void)dismissBeautyPanel {
    if (sBeautyPanelView) {
        [sBeautyPanelView removeFromSuperview];
        sBeautyPanelView = nil;
    }
}

- (void)syncPreviewChromeLayoutX:(int)x y:(int)y width:(int)width height:(int)height {
    if (!sPreviewChromeView || width <= 0 || height <= 0) {
        return;
    }
    UIView *parent = sPreviewChromeView.superview ?: [self resolveOverlayParentView];
    if (!parent) {
        return;
    }
    CGRect box = [self previewBoxFrameOnDecor:x y:y width:width height:height];
    sPreviewChromeView.frame = box;
    sPreviewChromeView.layer.zPosition = 30000.f;
    sPreviewChromeView.hidden = NO;
    [parent bringSubviewToFront:sPreviewChromeView];
    if (sLastBeautyPanelHeight > 0) {
        [sPreviewChromeView setBottomChromeInset:sLastBeautyPanelHeight animated:NO];
        [sPreviewChromeView setCompareButtonHidden:YES];
    }
}

UNI_EXPORT_METHOD(@selector(showPreviewChrome:callback:))
- (void)showPreviewChrome:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        sNamaModuleWeak = self;
        if (!sOverlayCameraView) {
            callback(@{ @"code": @(-1), @"message": @"camera not ready" }, NO);
            return;
        }
        int x = options[@"x"] ? [options[@"x"] intValue] : sLastCssX;
        int y = options[@"y"] ? [options[@"y"] intValue] : sLastCssY;
        int width = options[@"width"] ? [options[@"width"] intValue] : sLastCssW;
        int height = options[@"height"] ? [options[@"height"] intValue] : sLastCssH;
        if (width <= 0 || height <= 0) {
            callback(@{ @"code": @(-1), @"message": @"preview size 无效" }, NO);
            return;
        }
        UIView *parent = sOverlayCameraHost ?: [self resolveOverlayParentView];
        if (!parent) {
            callback(@{ @"code": @(-1), @"message": @"overlay parent null" }, NO);
            return;
        }
        if (!sPreviewChromeView) {
            Class chromeCls = FuPreviewChromeViewClass();
            if (!chromeCls) {
                // PreviewChromeView.m 未链进framework：勿硬崩，前端可回退 Vue 顶栏
                FU_LOG("showPreviewChrome skipped: PreviewChromeView class missing");
                callback(@{ @"code": @(-1), @"message": @"PreviewChromeView missing" }, NO);
                return;
            }
            sPreviewChromeView = [[chromeCls alloc] initWithFrame:CGRectZero];
            sPreviewChromeView.delegate = (id<PreviewChromeViewDelegate>)self;
            [parent addSubview:sPreviewChromeView];
        } else if (sPreviewChromeView.superview != parent) {
            [sPreviewChromeView removeFromSuperview];
            [parent addSubview:sPreviewChromeView];
        }
        NSString *resId = options[@"resolutionId"];
        if ([resId isKindOfClass:[NSString class]] && resId.length > 0) {
            [sPreviewChromeView setSelectedResolutionId:resId];
        }
        if (options[@"dualInput"] != nil) {
            [sPreviewChromeView setDualInputState:[options[@"dualInput"] boolValue]];
        }
        [self syncPreviewChromeLayoutX:x y:y width:width height:height];
        FU_LOG("showPreviewChrome css:%dx%d@%d,%d", width, height, x, y);
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(updatePreviewChromeStats:callback:))
- (void)updatePreviewChromeStats:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (sPreviewChromeView && [options isKindOfClass:[NSDictionary class]]) {
            id resObj = options[@"resolution"];
            NSString *res = [resObj isKindOfClass:[NSString class]]
                ? (NSString *)resObj
                : [NSString stringWithFormat:@"%@", resObj ?: @"-"];
            int fps = [options[@"fps"] intValue];
            int rt = [options[@"renderTime"] intValue];
            [sPreviewChromeView updateStatsWithResolution:res fps:fps renderTimeMs:rt];
        }
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(setPreviewChromeRecording:callback:))
- (void)setPreviewChromeRecording:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        BOOL on = [options[@"recording"] boolValue];
        [sPreviewChromeView setRecording:on];
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(hidePreviewChrome:))
- (void)hidePreviewChrome:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        [self dismissPreviewChrome];
        if (callback) {
            callback(@{ @"code": @(0), @"data": @(0) }, NO);
        }
    });
}

- (void)fireBeautyPanelEvent:(NSString *)action extra:(NSDictionary *)extra {
    NSMutableDictionary *payload = [@{ @"action": action ?: @"" } mutableCopy];
    if (extra.count > 0) {
        [payload addEntriesFromDictionary:extra];
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        [self.uniInstance fireGlobalEvent:@"namaBeautyPanel" params:payload];
    });
}

- (void)applyBeautyPanelBottomInset:(CGFloat)panelHeight animated:(BOOL)animated {
    // 面板高度整段抬升拍摄钮；PreviewChrome 再留 panelGap，避免被 Tab 盖住
    sLastBeautyPanelHeight = MAX(0, panelHeight);
    CGFloat inset = sLastBeautyPanelHeight;
    if (sPreviewChromeView) {
        [sPreviewChromeView setBottomChromeInset:inset animated:animated];
        [sPreviewChromeView setCompareButtonHidden:inset > 0.5];
    }
}

- (void)applyBeautyPanelBottomInset:(CGFloat)panelHeight {
    BOOL animated = sChromeInsetDidInitial;
    sChromeInsetDidInitial = YES;
    [self applyBeautyPanelBottomInset:panelHeight animated:animated];
}

- (UIView *)resolveBeautyPanelParentForMode:(NSString *)mode {
    BOOL media = [mode isEqualToString:@"image"] || [mode isEqualToString:@"video"];
    // 媒体页：勿挂到 soft-hide 的相机 host（hidden）上，否则整面板看不见
    if (sOverlayVideoHost && (!sOverlayVideoHost.hidden || media)) {
        return sOverlayVideoHost;
    }
    if (media) {
        return [self resolveOverlayParentView];
    }
    if (sOverlayCameraHost && !sOverlayCameraHost.hidden) {
        return sOverlayCameraHost;
    }
    return sOverlayCameraHost ?: sOverlayVideoHost ?: [self resolveOverlayParentView];
}

- (void)syncBeautyPanelLayout {
    if (!sBeautyPanelView) {
        return;
    }
    UIView *parent = sBeautyPanelView.superview;
    if (!parent) {
        return;
    }
    sBeautyPanelView.frame = parent.bounds;
    sBeautyPanelView.layer.zPosition = 30100.f;
    [parent bringSubviewToFront:sBeautyPanelView];
    if (sMediaBackBtn && sMediaBackBtn.superview == parent) {
        [self layoutMediaBackButton];
        [parent bringSubviewToFront:sMediaBackBtn];
    }
    if (sPreviewChromeView) {
        [parent bringSubviewToFront:sPreviewChromeView];
    }
}

- (UIImage *)fuChromeImageNamed:(NSString *)name {
    NSString *base = [name stringByDeletingPathExtension];
    NSBundle *b = [NSBundle bundleForClass:FuBeautyPanelViewClass() ?: [self class]];
    NSString *path = [b pathForResource:base ofType:@"png" inDirectory:@"fu_chrome"];
    if (!path) {
        path = [b pathForResource:base ofType:@"png"];
    }
    return path ? [UIImage imageWithContentsOfFile:path] : nil;
}

- (void)layoutMediaBackButton {
    if (!sMediaBackBtn || !sMediaBackBtn.superview) {
        return;
    }
    UIView *parent = sMediaBackBtn.superview;
    CGFloat topSafe = 0;
    if (@available(iOS 11.0, *)) {
        topSafe = parent.safeAreaInsets.top;
        if (topSafe < 1 && parent.window) {
            topSafe = parent.window.safeAreaInsets.top;
        }
    }
    if (topSafe < 20) {
        topSafe = 44;
    }
    sMediaBackBtn.frame = CGRectMake(10, topSafe + 8, 44, 44);
    sMediaBackBtn.layer.zPosition = 50000.f;
}

- (void)ensureMediaBackButtonOnParent:(UIView *)parent {
    if (!parent) {
        return;
    }
    if (!sMediaBackBtn) {
        sMediaBackBtn = [UIButton buttonWithType:UIButtonTypeCustom];
        sMediaBackBtn.tag = kFuChromeInteractiveTag;
        sMediaBackBtn.backgroundColor = [UIColor clearColor];
        sMediaBackBtn.layer.cornerRadius = 0;
        sMediaBackBtn.clipsToBounds = NO;
        UIImage *back = [self fuChromeImageNamed:@"back.png"];
        if (back) {
            [sMediaBackBtn setImage:[back imageWithRenderingMode:UIImageRenderingModeAlwaysOriginal]
                           forState:UIControlStateNormal];
            sMediaBackBtn.imageView.contentMode = UIViewContentModeScaleAspectFit;
            sMediaBackBtn.contentEdgeInsets = UIEdgeInsetsMake(8, 8, 8, 8);
        } else {
            [sMediaBackBtn setTitle:@"‹" forState:UIControlStateNormal];
            [sMediaBackBtn setTitleColor:[UIColor whiteColor] forState:UIControlStateNormal];
            sMediaBackBtn.titleLabel.font = [UIFont systemFontOfSize:28 weight:UIFontWeightLight];
        }
        [sMediaBackBtn addTarget:self action:@selector(onMediaBackBtn:) forControlEvents:UIControlEventTouchUpInside];
    }
    if (sMediaBackBtn.superview != parent) {
        [sMediaBackBtn removeFromSuperview];
        [parent addSubview:sMediaBackBtn];
    }
    sMediaBackBtn.hidden = NO;
    [self layoutMediaBackButton];
    [parent bringSubviewToFront:sMediaBackBtn];
}

- (void)hideMediaBackButton {
    if (sMediaBackBtn) {
        sMediaBackBtn.hidden = YES;
        [sMediaBackBtn removeFromSuperview];
    }
}

- (void)onMediaBackBtn:(UIButton *)sender {
    (void)sender;
    [self fireBeautyPanelEvent:@"back" extra:nil];
}

UNI_EXPORT_METHOD(@selector(showBeautyPanel:callback:))
- (void)showBeautyPanel:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        sNamaModuleWeak = self;
        Class cls = FuBeautyPanelViewClass();
        if (!cls) {
            callback(@{ @"code": @(-1), @"message": @"FuBeautyPanelView missing" }, NO);
            return;
        }
        UIView *parent = nil;
        NSDictionary *cfg = [options isKindOfClass:[NSDictionary class]] ? options : @{};
        NSString *mode = [cfg[@"mode"] isKindOfClass:[NSString class]] ? cfg[@"mode"] : @"camera";
        parent = [self resolveBeautyPanelParentForMode:mode];
        if (!parent) {
            parent = [self resolveOverlayParentView];
        }
        if (!parent) {
            callback(@{ @"code": @(-1), @"message": @"overlay parent null" }, NO);
            return;
        }
        if (!sBeautyPanelView) {
            sBeautyPanelView = [[cls alloc] initWithFrame:parent.bounds];
            sBeautyPanelView.delegate = (id<FuBeautyPanelViewDelegate>)self;
            [parent addSubview:sBeautyPanelView];
        } else if (sBeautyPanelView.superview != parent) {
            [sBeautyPanelView removeFromSuperview];
            [parent addSubview:sBeautyPanelView];
        }
        sBeautyPanelView.hidden = NO;
        NSMutableDictionary *cfgMut = [NSMutableDictionary dictionaryWithDictionary:cfg];
        if (cfgMut[@"devicePerfLevel"] == nil) {
            cfgMut[@"devicePerfLevel"] = @(FuDevicePerformanceLevelCached());
        }
        [sBeautyPanelView applyConfig:cfgMut];
        [self syncBeautyPanelLayout];
        BOOL media = [mode isEqualToString:@"image"] || [mode isEqualToString:@"video"];
        if (media) {
            [self ensureMediaBackButtonOnParent:parent];
        } else {
            [self hideMediaBackButton];
        }
        CGFloat ht = [sBeautyPanelView currentPanelHeight];
        [self applyBeautyPanelBottomInset:ht];
        [self fireBeautyPanelEvent:@"panelHeight" extra:@{ @"height": @(ht) }];
        FU_LOG("showBeautyPanel height=%.1f mode=%@ parent=%@", ht, mode, NSStringFromClass(parent.class));
        callback(@{ @"code": @(0), @"data": @{ @"height": @(ht) } }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(hideBeautyPanel:))
- (void)hideBeautyPanel:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        [self hideMediaBackButton];
        if (sBeautyPanelView) {
            [sBeautyPanelView removeFromSuperview];
            sBeautyPanelView = nil;
        }
        if (sPreviewChromeView) {
            [sPreviewChromeView setBottomChromeInset:0 animated:YES];
            [sPreviewChromeView setCompareButtonHidden:NO];
        }
        sLastBeautyPanelHeight = 0;
        sChromeInsetDidInitial = NO;
        if (callback) {
            callback(@{ @"code": @(0), @"data": @(0) }, NO);
        }
    });
}

UNI_EXPORT_METHOD(@selector(updateBeautyPanelValues:callback:))
- (void)updateBeautyPanelValues:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (sBeautyPanelView && [options isKindOfClass:[NSDictionary class]]) {
            NSDictionary *values = options[@"values"];
            if ([values isKindOfClass:[NSDictionary class]]) {
                [sBeautyPanelView updateValues:values];
            }
            if ([options[@"filterId"] isKindOfClass:[NSString class]]) {
                [sBeautyPanelView setSelectedFilterId:options[@"filterId"]];
            }
            if ([options[@"whiteningMode"] isKindOfClass:[NSString class]]) {
                [sBeautyPanelView setWhiteningMode:options[@"whiteningMode"]];
            }
            if ([options[@"selectedKey"] isKindOfClass:[NSString class]] &&
                [options[@"selectedKey"] length] > 0) {
                [sBeautyPanelView setSelectedEffectKey:options[@"selectedKey"]];
            }
        }
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(setBeautyPanelMode:callback:))
- (void)setBeautyPanelMode:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        NSString *mode = [options[@"mode"] isKindOfClass:[NSString class]] ? options[@"mode"] : @"camera";
        if (sBeautyPanelView) {
            sBeautyPanelView.mode = mode;
            [self syncBeautyPanelLayout];
            [self applyBeautyPanelBottomInset:[sBeautyPanelView currentPanelHeight]];
        }
        callback(@{ @"code": @(0), @"data": @{ @"mode": mode } }, NO);
    });
}

#pragma mark - FuBeautyPanelViewDelegate

/** 媒体页优先用 media handle，避免相机 soft-hide 后仍写到 camera handle 导致调参/丰盈无效 */
- (int)resolvePanelBeautyHandle {
    NSString *mode = sBeautyPanelView.mode;
    BOOL media = [mode isEqualToString:@"image"] || [mode isEqualToString:@"video"];
    // 对齐 Android：视频叠层存在时也优先 media
    if (!media && (sOverlayVideoView || sOverlayVideoHost)) {
        media = YES;
    }
    if (media) {
        if (FuBeautyMediaHandle > 0) {
            return FuBeautyMediaHandle;
        }
        // media 未绑定时回退相机 handle，并同步绑定，避免恢复/调参写空
        if (FuBeautyCameraHandle > 0) {
            FuBeautySetPipelineHandle(YES, FuBeautyCameraHandle);
            return FuBeautyCameraHandle;
        }
    }
    if (FuBeautyCameraHandle > 0) {
        return FuBeautyCameraHandle;
    }
    if (FuBeautyItemHandle > 0) {
        return FuBeautyItemHandle;
    }
    return FuBeautyMediaHandle;
}

/** 对齐 Android BeautyParamApplier.resolveKey：面板 key → SDK 实际参数名 */
static NSString *FuResolveBeautyParamKey(NSString *key) {
    if (key.length == 0) {
        return key;
    }
    if ([key isEqualToString:@"color_level"] || [key isEqualToString:@"color_level_mode2"]) {
        return @"color_level";
    }
    if ([key isEqualToString:@"remove_pouch_strength"]) {
        return @"remove_pouch_strength_mode2";
    }
    if ([key isEqualToString:@"remove_nasolabial_folds_strength"]) {
        return @"remove_nasolabial_folds_strength_mode2";
    }
    if ([key isEqualToString:@"eye_enlarging"]) {
        return @"eye_enlarging_mode3";
    }
    if ([key isEqualToString:@"cheek_narrow"]) {
        return @"cheek_narrow_mode2";
    }
    if ([key isEqualToString:@"cheek_small"]) {
        return @"cheek_small_mode2";
    }
    if ([key isEqualToString:@"intensity_forehead"]) {
        return @"intensity_forehead_mode2";
    }
    if ([key isEqualToString:@"intensity_nose"]) {
        return @"intensity_nose_mode2";
    }
    if ([key isEqualToString:@"intensity_mouth"]) {
        return @"intensity_mouth_mode3";
    }
    return key;
}

- (void)beautyPanelDidChangeHeight:(CGFloat)heightPts {
    [self applyBeautyPanelBottomInset:heightPts animated:YES];
    [self fireBeautyPanelEvent:@"panelHeight" extra:@{ @"height": @(heightPts) }];
}
- (void)beautyPanelSelectTab:(NSString *)tabId expanded:(BOOL)expanded {
    [self fireBeautyPanelEvent:@"tab" extra:@{ @"tab": tabId ?: @"", @"expanded": @(expanded) }];
}
- (void)beautyPanelSelectEffect:(NSString *)key {
    [self fireBeautyPanelEvent:@"selectEffect" extra:@{ @"key": key ?: @"" }];
}
- (void)beautyPanelSliderChanged:(NSString *)key value:(double)value {
    int handle = [self resolvePanelBeautyHandle];
    if (handle > 0 && key.length > 0) {
        NSString *sdkKey = FuResolveBeautyParamKey(key);
        BOOL special = [sdkKey isEqualToString:@"body_blur_level"] ||
            [sdkKey isEqualToString:@"delspot_level"] ||
            [sdkKey isEqualToString:@"facial_plump"] ||
            [sdkKey isEqualToString:@"intensity_eye_pupil"] ||
            [sdkKey isEqualToString:@"enable_skinseg"];
        if (special) {
            // 对齐 Android applySpecialAlgoParam：单 key 入队，flush 时走 special 写参
            [BeautyCameraView enqueueBeautyParam:handle name:sdkKey value:value];
            // 面部丰盈等特殊算法：滑杆拖动时立即 GL 落地，避免仅靠队列+帧间 latch 间歇失效
            if ([sdkKey isEqualToString:@"facial_plump"] ||
                [sdkKey isEqualToString:@"delspot_level"] ||
                [sdkKey isEqualToString:@"intensity_eye_pupil"]) {
                const char *cKey = sdkKey.UTF8String;
                [BeautyCameraView performWithSharedGLLock:^{
                    FuApplyBeautyParamDirectOnGl(handle, cKey, value);
                }];
            }
            if ([sdkKey isEqualToString:@"body_blur_level"]) {
                FuResetBeautyBlurCache();
            }
        } else {
            // 与 special 一样进渲染队列，避免主线程 set 后渲染线程读到旧值
            [BeautyCameraView enqueueBeautyParam:handle name:sdkKey value:value];
        }
    }
    [self refreshPausedVideoBeautyIfNeeded];
    [self requestCameraPreviewRedrawIfActive];
    if (!sSuppressBeautyPanelSliderEvents) {
        [self fireBeautyPanelEvent:@"slider" extra:@{ @"key": key ?: @"", @"value": @(value) }];
    }
}

- (void)requestCameraPreviewRedrawIfActive {
    NSString *panelMode = sBeautyPanelView.mode;
    // 图片页勿触发相机 GL 重绘，会与后台 processImage 并发 fuRender
    if ([panelMode isEqualToString:@"image"]) {
        return;
    }
    if (sOverlayCameraView && ![sOverlayCameraView isSoftHidden]) {
        [sOverlayCameraView setNeedsDisplay];
    }
}

- (void)beautyPanelApplyRecoverDefaults:(NSDictionary<NSString *, NSNumber *> *)sdkParams
                                    tab:(NSString *)tabId {
    // 纯原生写参：与滑杆同一 enqueue 路径；不发 JS slider 事件
    NSDictionary *params = [sdkParams copy] ?: @{};
    BOOL isShape = [tabId isEqualToString:@"shape"];
    int handle = [self resolvePanelBeautyHandle];
    FU_LOG("recoverDefaults write handle=%d count=%lu tab=%@", handle, (unsigned long)params.count, tabId ?: @"");

    if (handle > 0) {
        [BeautyCameraView enqueueBeautyParam:handle name:@"change_frames" value:0.0];
        [BeautyCameraView enqueueBeautyParam:handle name:@"is_beauty_on" value:1.0];
        if (isShape) {
            [BeautyCameraView enqueueBeautyParam:handle name:@"face_shape" value:4.0];
            [BeautyCameraView enqueueBeautyParam:handle name:@"face_shape_level" value:1.0];
        }
    }

    sSuppressBeautyPanelSliderEvents = YES;
    [params enumerateKeysAndObjectsUsingBlock:^(NSString *key, NSNumber *num, BOOL *stop) {
        (void)stop;
        if (key.length == 0 || ![num isKindOfClass:[NSNumber class]]) {
            return;
        }
        [self beautyPanelSliderChanged:key value:num.doubleValue];
    }];
    if (!isShape && handle > 0) {
        [BeautyCameraView enqueueBeautyParam:handle name:@"enable_skinseg" value:0.0];
    }
    sSuppressBeautyPanelSliderEvents = NO;

    [self refreshPausedVideoBeautyIfNeeded];
    [self requestCameraPreviewRedrawIfActive];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.5 * NSEC_PER_SEC)),
                   dispatch_get_main_queue(), ^{
        if (FuBeautyChangeFramesHoldZero()) {
            return;
        }
        int h = [self resolvePanelBeautyHandle];
        if (h > 0) {
            [BeautyCameraView enqueueBeautyParam:h name:@"change_frames" value:12.0];
        }
    });
}

- (void)beautyPanelDidRecoverDefaults:(NSString *)tabId {
    [self showPreviewCenterTip:@"已恢复默认"];
    // 仅顺带同步 JS 内存状态，功能不依赖 JS
    [self fireBeautyPanelEvent:@"recover" extra:@{ @"tab": tabId ?: @"skin", @"confirmed": @(1) }];
}

- (void)beautyPanelSelectFilter:(NSString *)filterId filterKey:(NSString *)filterKey filterName:(NSString *)filterName {
    int handle = [self resolvePanelBeautyHandle];
    if (handle > 0 && filterKey.length > 0) {
        FuItemSetParamsLogged(handle, "filter_name", filterKey.UTF8String);
    }
    [self refreshPausedVideoBeautyIfNeeded];
    NSString *tip = filterName.length ? filterName : filterKey;
    if (tip.length) {
        [self showPreviewCenterTip:tip];
    }
    [self fireBeautyPanelEvent:@"filter" extra:@{
        @"id": filterId ?: @"",
        @"key": filterKey ?: @"",
        @"name": tip ?: @"",
    }];
}
- (void)beautyPanelWhiteningMode:(NSString *)mode {
    int handle = [self resolvePanelBeautyHandle];
    double v = [mode isEqualToString:@"skin"] ? 1.0 : 0.0;
    if (handle > 0) {
        // 勿在主线程 performWithSharedGLLock，恢复默认时会卡死 UI
        [BeautyCameraView enqueueBeautyParam:handle name:@"enable_skinseg" value:v];
    }
    [self refreshPausedVideoBeautyIfNeeded];
    [self fireBeautyPanelEvent:@"whiteningMode" extra:@{ @"mode": mode ?: @"global" }];
}
- (void)beautyPanelCompareStart {
    [BeautyCameraView setBeautyEnabledGlobal:NO];
    if (sOverlayCameraView) {
        [sOverlayCameraView setBeautyEnabled:NO];
    }
    if (sOverlayVideoView) {
        [sOverlayVideoView setBeautyEnabled:NO];
        [self refreshPausedVideoBeautyIfNeeded];
    }
    [self fireBeautyPanelEvent:@"compareStart" extra:nil];
}
- (void)beautyPanelCompareEnd {
    [BeautyCameraView setBeautyEnabledGlobal:YES];
    if (sOverlayCameraView) {
        [sOverlayCameraView setBeautyEnabled:YES];
    }
    if (sOverlayVideoView) {
        [sOverlayVideoView setBeautyEnabled:YES];
        [self refreshPausedVideoBeautyIfNeeded];
    }
    [self fireBeautyPanelEvent:@"compareEnd" extra:nil];
}
- (void)beautyPanelSave {
    [self fireBeautyPanelEvent:@"save" extra:nil];
}
- (void)beautyPanelBack {
    [self fireBeautyPanelEvent:@"back" extra:nil];
}

- (void)beautyPanelShowPerfLimitTip:(NSString *)message {
    if (message.length == 0) {
        return;
    }
    [self showPreviewPerfLimitTip:message];
}

- (void)showPreviewPerfLimitTip:(NSString *)message {
    if (message.length == 0) {
        return;
    }
    // 性能限制提示须盖在美颜面板(z~30100)之上；PreviewChrome 在面板下层会被挡住
    dispatch_async(dispatch_get_main_queue(), ^{
        UIView *root = sOverlayVideoHost ?: sOverlayCameraHost ?: [self resolveOverlayParentView] ?: [self resolveBusinessWindow];
        if (!root) {
            return;
        }
        static const NSInteger kPerfTipTag = 88215603;
        UIView *old = [root viewWithTag:kPerfTipTag];
        [old.layer removeAllAnimations];
        [old removeFromSuperview];

        UILabel *label = [[UILabel alloc] init];
        label.text = message;
        label.textColor = [UIColor whiteColor];
        label.font = [UIFont systemFontOfSize:14 weight:UIFontWeightMedium];
        label.textAlignment = NSTextAlignmentCenter;
        label.numberOfLines = 0;

        UIView *card = [[UIView alloc] init];
        card.tag = kPerfTipTag;
        card.backgroundColor = [[UIColor blackColor] colorWithAlphaComponent:0.72];
        card.layer.cornerRadius = 10;
        card.clipsToBounds = YES;
        card.layer.zPosition = 50001.f;
        [card addSubview:label];

        CGFloat maxW = MIN(CGRectGetWidth(root.bounds) - 48.f, 300.f);
        CGSize textSize = [label sizeThatFits:CGSizeMake(maxW - 28.f, CGFLOAT_MAX)];
        CGFloat w = MIN(maxW, MAX(160.f, textSize.width + 28.f));
        CGFloat h = MAX(40.f, textSize.height + 20.f);
        CGFloat panelH = sLastBeautyPanelHeight > 0 ? sLastBeautyPanelHeight : 80.f;
        CGFloat tipCenterY = (CGRectGetHeight(root.bounds) - panelH) * 0.42f;
        card.frame = CGRectMake((CGRectGetWidth(root.bounds) - w) * 0.5f, tipCenterY - h * 0.5f, w, h);
        label.frame = CGRectMake(14, 10, w - 28.f, h - 20.f);
        card.alpha = 0;
        [root addSubview:card];
        [root bringSubviewToFront:card];
        [UIView animateWithDuration:0.18 animations:^{
            card.alpha = 1;
        } completion:^(__unused BOOL finished) {
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.0 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
                [UIView animateWithDuration:0.22 animations:^{
                    card.alpha = 0;
                } completion:^(__unused BOOL done) {
                    [card removeFromSuperview];
                }];
            });
        }];
    });
}

#pragma mark - SDK

UNI_EXPORT_METHOD(@selector(getVersion:))
- (void)getVersion:(UniModuleKeepAliveCallback)callback {
    callback(@{ @"code": @(0), @"data": [NSString stringWithUTF8String:fuGetVersion()] }, NO);
}

UNI_EXPORT_METHOD(@selector(init:callback:))
- (void)init:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    NSData *authData = [self readAuthData:options];
    if (authData == nil || authData.length == 0) {
        callback(@{ @"code": @(-1), @"message": @"authData 不能为空" }, NO);
        return;
    }

    FuDisableSdkLogging();
    int setupCode = fuSetup(NULL, 0, NULL, (void *)authData.bytes, (int)authData.length);
    int libInit = fuIsLibraryInit();
    int systemError = fuGetSystemError();
    NSString *version = [NSString stringWithUTF8String:fuGetVersion()];

    NSMutableDictionary *diag = [@{
        @"version": version ?: @"",
        @"authSize": @(authData.length),
        @"fuSetupCode": @(setupCode),
        @"fuIsLibraryInit": @(libInit),
        @"fuGetSystemError": @(systemError),
    } mutableCopy];

    if (systemError != 0) {
        diag[@"fuGetSystemErrorString"] = [NSString stringWithUTF8String:fuGetSystemErrorString(systemError)] ?: @"";
    }
    if (setupCode == 0) {
        callback(@{ @"code": @(-1), @"message": @"fuSetup 失败 code=0", @"data": diag }, NO);
        return;
    }
    if (libInit == 0) {
        callback(@{
            @"code": @(-1),
            @"message": @"SDK 未就绪fuIsLibraryInit=0（authpack 与Bundle ID 不匹配）",
            @"data": diag
        }, NO);
        return;
    }

    callback(@{ @"code": @(0), @"data": diag }, NO);
}

UNI_EXPORT_METHOD(@selector(loadAIModel:callback:))
- (void)loadAIModel:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    @try {
        [self ensureInitialized];
        NSData *bundleData = [self readFileData:options[@"path"]];
        int aiType = [options[@"aiType"] intValue];
        if (aiType == 0) {
            aiType = FUAITYPE_FACEPROCESSOR;
        }
        // load 前开子模块：默认 algorithmConfig=-1 会全关皮肤分割祛斑/ARMeshV2/丰盈
        // release 后再设一次，避免 release 把config 打回默认 -1
        FuEnableFaceAlgorithmModules();
        if (fuIsAIModelLoaded(FUAITYPE_FACEPROCESSOR)) {
            fuReleaseAIModel(FUAITYPE_FACEPROCESSOR);
        }
        FuEnableFaceAlgorithmModules();
        int handle = fuLoadAIModelFromPackage((void *)bundleData.bytes, (int)bundleData.length, aiType);
        if (handle <= 0) {
            callback(@{ @"code": @(-1), @"message": @"loadAIModel 失败" }, NO);
            return;
        }
        // load 后再开公开侧开关（ARMesh / algorithm config）；勿调未公开 FUAI_SetUse*
        FuEnableAdvancedBeautyRuntime(0);
        int perf = FuDevicePerformanceLevelCached();
        // 多人跟踪是 SDK 算力瓶颈（非 Vue 写法问题）；低端限制 2 脸减轻 UI 卡顿
        fuSetMaxFaces(perf >= 4 ? 4 : 2);
        fuSetFaceProcessorDetectMode(1);
        fuFaceProcessorSetMinFaceRatio(0.05f);
        int faceOk = fuIsAIModelLoaded(FUAITYPE_FACEPROCESSOR);
        int m0 = fuGetModuleCode(0), m1 = fuGetModuleCode(1), m2 = fuGetModuleCode(2), m3 = fuGetModuleCode(3);
        FU_LOG("loadAIModel ok handle=%d aiBytes=%lu faceLoaded=%d ARMeshV2=1 algo=0 machine=HIGH dq=0 module=[%d,%d,%d,%d]",
               handle,
               (unsigned long)bundleData.length,
               faceOk,
               m0, m1, m2, m3);
        callback(@{
            @"code": @(0),
            @"data": @{
                @"handle": @(handle),
                @"aiBytes": @((unsigned long)bundleData.length),
                @"faceLoaded": @(faceOk),
                @"moduleCode0": @(m0),
                @"moduleCode1": @(m1),
                @"moduleCode2": @(m2),
                @"moduleCode3": @(m3),
            },
        }, NO);
    } @catch (NSException *exception) {
        callback(@{ @"code": @(-1), @"message": exception.reason ?: @"loadAIModel 异常" }, NO);
    }
}

UNI_EXPORT_METHOD(@selector(loadBundle:callback:))
- (void)loadBundle:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    @try {
        [self ensureInitialized];
        NSData *bundleData = [self readFileData:options[@"path"]];
        NSString *pipeline = options[@"pipeline"];
        BOOL media = [pipeline isKindOfClass:[NSString class]] &&
            [pipeline caseInsensitiveCompare:@"media"] == NSOrderedSame;
        int old = media ? FuBeautyMediaHandle : FuBeautyCameraHandle;
        if (old > 0) {
            fuDestroyItem(old);
        }
        int handle = fuCreateItemFromPackage((void *)bundleData.bytes, (int)bundleData.length);
        if (handle <= 0) {
            callback(@{ @"code": @(-1), @"message": @"loadBundle 失败" }, NO);
            return;
        }
        FuBeautySetPipelineHandle(media, handle);
        // 兼容旧逻辑：_beautyHandle 记「当前管线」最后一次load
        _beautyHandle = handle;
        FuEnableAdvancedBeautyRuntime(handle);
        FuItemSetParamdLogged(handle, "is_beauty_on", 1.0);
        // 强度默认必须对齐 beauty-effects.ts（全身磨皮/祛斑默认 0；勿写 3.3/0.5，否则常驻闪点）
        FuItemSetParamdLogged(handle, "skin_detect", 0.0);
        FuItemSetParamdLogged(handle, "blur_level", 3.3);
        FuItemSetParamdLogged(handle, "color_level", 0.4);
        FuItemSetParamdLogged(handle, "red_level", 0.3);
        FuItemSetParamdLogged(handle, "face_shape", 4.0);
        FuItemSetParamdLogged(handle, "face_shape_level", 1.0);
        FuItemSetParamdLogged(handle, "body_blur_level", 0.0);
        FuItemSetParamdLogged(handle, "delspot_level", 0.0);
        FuItemSetParamdLogged(handle, "facial_plump", 0.0);
        FuItemSetParamdLogged(handle, "intensity_eye_pupil", 0.5);
        FuItemSetParamdLogged(handle, "disable_delspot", 0.0);
        FuItemSetParamdLogged(handle, "use_facial_plump", 1.0);
        FuResetBeautyBlurCache();
        FuUpdateBeautyBlurEffect(handle);
        FU_LOG("loadBundle pipeline=%@ handle=%d camera=%d media=%d",
               media ? @"media" : @"camera",
               handle,
               FuBeautyCameraHandle,
               FuBeautyMediaHandle);
        if (!media) {
            [BeautyCameraView setBeautyEnabledGlobal:YES];
        }
        callback(@{
            @"code": @(0),
            @"data": @{
                @"handle": @(handle),
                @"pipeline": media ? @"media" : @"camera",
            }
        }, NO);
    } @catch (NSException *exception) {
        callback(@{ @"code": @(-1), @"message": exception.reason ?: @"loadBundle 异常" }, NO);
    }
}

UNI_EXPORT_METHOD(@selector(setParam:callback:))
- (void)setParam:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    @try {
        [self ensureInitialized];
        int handle = [options[@"handle"] intValue];
        if (handle <= 0) {
            NSString *pipeline = options[@"pipeline"];
            BOOL media = [pipeline isKindOfClass:[NSString class]] &&
                [pipeline caseInsensitiveCompare:@"media"] == NSOrderedSame;
            handle = FuBeautyHandleForPipeline(media);
        }
        if (handle <= 0) {
            handle = _beautyHandle;
        }
        if (handle <= 0) {
            callback(@{ @"code": @(-1), @"message": @"请先 loadBundle" }, NO);
            return;
        }
        NSString *key = options[@"key"];
        int ret;
        NSString *stringValue = options[@"stringValue"];
        if (stringValue.length > 0) {
            ret = FuItemSetParamsLogged(handle, key.UTF8String, stringValue.UTF8String);
            callback(@{ @"code": @(0), @"data": @{ @"ret": @(ret) } }, NO);
            return;
        }
        double value = [options[@"value"] doubleValue];
        NSString *sdkKey = FuResolveBeautyParamKey(key);
        // 依赖 AI 子模块的参数：须在Nama GL 线程写，否则可能 get=set 但渲染无效果
        BOOL special = [sdkKey isEqualToString:@"body_blur_level"] ||
            [sdkKey isEqualToString:@"delspot_level"] ||
            [sdkKey isEqualToString:@"facial_plump"] ||
            [sdkKey isEqualToString:@"intensity_eye_pupil"] ||
            [sdkKey isEqualToString:@"enable_skinseg"];
        __block int retBlock = 0;
        NSMutableDictionary *data = [@{
            @"ret": @(0),
        } mutableCopy];
        if (special) {
            [BeautyCameraView enqueueBeautyParam:handle name:sdkKey value:value];
            if ([sdkKey isEqualToString:@"body_blur_level"]) {
                FuResetBeautyBlurCache();
            }
            retBlock = 0;
        } else {
            retBlock = FuItemSetParamdLogged(handle, sdkKey.UTF8String, value);
            (void)fuItemGetParamd(handle, sdkKey.UTF8String);
        }
        data[@"ret"] = @(retBlock);
        callback(@{ @"code": @(0), @"data": data }, NO);
    } @catch (NSException *exception) {
        callback(@{ @"code": @(-1), @"message": exception.reason ?: @"setParam 异常" }, NO);
    }
}

UNI_EXPORT_METHOD(@selector(drainSdkLog:))
- (void)drainSdkLog:(UniModuleKeepAliveCallback)callback {
    if (callback) {
        callback(@{ @"code": @(0), @"data": @"" }, NO);
    }
}

/** JS 复用相机 beauty handle 时，同步原生 FuBeautyMediaHandle，避免媒体页 handle=0 黑屏/原图 */
UNI_EXPORT_METHOD(@selector(bindMediaBeautyHandle:callback:))
- (void)bindMediaBeautyHandle:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    int handle = [options[@"handle"] intValue];
    if (handle <= 0) {
        handle = FuBeautyCameraHandle;
    }
    if (handle <= 0) {
        callback(@{ @"code": @(-1), @"message": @"无可用beauty handle" }, NO);
        return;
    }
    FuBeautySetPipelineHandle(YES, handle);
    FuEnableAdvancedBeautyRuntime(handle);
    FU_LOG("bindMediaBeautyHandle handle=%d camera=%d media=%d", handle, FuBeautyCameraHandle, FuBeautyMediaHandle);
    callback(@{
        @"code": @(0),
        @"data": @{
            @"handle": @(handle),
            @"mediaHandle": @(FuBeautyMediaHandle),
            @"cameraHandle": @(FuBeautyCameraHandle),
        },
    }, NO);
}

UNI_EXPORT_METHOD(@selector(onFrame:callback:))
- (void)onFrame:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    int textureId = [options[@"textureId"] intValue];
    int width = [options[@"width"] intValue];
    int height = [options[@"height"] intValue];
    int flags = [options[@"flags"] intValue];

    if (textureId <= 0 || width <= 0 || height <= 0) {
        callback(@{ @"code": @(-1), @"message": @"textureId / width / height 无效" }, NO);
        return;
    }

    int items[1] = { _beautyHandle > 0 ? _beautyHandle : 0 };
    int itemCount = _beautyHandle > 0 ? 1 : 0;
    unsigned int inTex = (unsigned int)textureId;
    unsigned int outTex = (unsigned int)textureId;
    _frameId += 1;

    int ret = fuRender(
        FU_FORMAT_RGBA_TEXTURE,
        &outTex,
        FU_FORMAT_RGBA_TEXTURE,
        &inTex,
        width,
        height,
        _frameId,
        items,
        itemCount,
        flags,
        NULL
    );

    callback(@{
        @"code": @(0),
        @"data": @{ @"result": @(ret), @"textureId": @(outTex) }
    }, NO);
}

#pragma mark - Camera overlay (对齐 Android NamaModule)

UNI_EXPORT_METHOD(@selector(showCamera:callback:))
- (void)showCamera:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        @try {
            [self ensureInitialized];
            NSDictionary *opts = [options isKindOfClass:[NSDictionary class]] ? options : @{};

            // nvue <beauty-camera> 托管：只恢复预览
            if (sCameraHostedByComponent && sOverlayCameraView) {
                int width = [opts[@"width"] intValue];
                int height = [opts[@"height"] intValue];
                if (width > 0 && height > 0) {
                    int pxW = [self cssToPhysical:width];
                    int pxH = [self cssToPhysical:height];
                    [sOverlayCameraView bindLayoutSize:pxW height:pxH];
                } else {
                    CGSize size = sOverlayCameraView.bounds.size;
                    if (size.width > 1 && size.height > 1) {
                        [sOverlayCameraView bindLayoutSize:(int)size.width height:(int)size.height];
                    }
                }
                sOverlayCameraView.hidden = NO;
                [sOverlayCameraView resumePreview];
                callback(@{
                    @"code": @(0),
                    @"data": @{
                        @"hosted": @(YES),
                        @"cameraError": [BeautyCameraView lastError] ?: @"",
                        @"diag": [BeautyCameraView previewDiag] ?: @"",
                    }
                }, NO);
                return;
            }

            int x = [opts[@"x"] intValue];
            int y = [opts[@"y"] intValue];
            int width = [opts[@"width"] intValue];
            int height = [opts[@"height"] intValue];
            if (width <= 0 || height <= 0) {
                callback(@{ @"code": @(-1), @"message": @"width/height 无效" }, NO);
                return;
            }

            //  已有相机层时只改 frame，避免destroy →黑闪（对齐Android）
            if (sOverlayCameraView && sOverlayCameraHost) {
                int pxW = [self cssToPhysical:width];
                int pxH = [self cssToPhysical:height];
                CGRect boxFrame = [self previewBoxFrameOnDecor:x y:y width:width height:height];
                UIView *previewBox = sOverlayCameraView.superview;
                if (previewBox) {
                    previewBox.frame = boxFrame;
                    sOverlayCameraView.frame = previewBox.bounds;
                }
                [sOverlayCameraView bindLayoutSize:pxW height:pxH];
                [sOverlayCameraView setNeedsLayout];
                [sOverlayCameraView layoutIfNeeded];
                sOverlayCameraHost.hidden = NO;
                sOverlayCameraHost.alpha = 1;
                sOverlayCameraView.hidden = NO;
                if (sOverlayWindow) {
                    sOverlayWindow.hidden = NO;
                }
                BOOL resizeOnly = [opts[@"resizeOnly"] boolValue];
                if (!resizeOnly) {
                    [sOverlayCameraView resumePreview];
                }
                sLastCssX = x;
                sLastCssY = y;
                sLastCssW = width;
                sLastCssH = height;
                [self bringOverlayToFront];
                FU_LOG("showCamera resized css:%dx%d@%d,%d boxFrame=%@ resizeOnly=%@",
                       width, height, x, y, NSStringFromCGRect(boxFrame),
                       resizeOnly ? @"YES" : @"NO");
                callback(@{
                    @"code": @(0),
                    @"data": @{
                        @"x": @(x),
                        @"y": @(y),
                        @"width": @(width),
                        @"height": @(height),
                        @"reused": @(YES),
                        @"resized": @(YES),
                        @"resizeOnly": @(resizeOnly),
                        @"cameraError": [BeautyCameraView lastError] ?: @"",
                        @"diag": [BeautyCameraView previewDiag] ?: @"",
                        @"boxFrame": NSStringFromCGRect(boxFrame),
                    }
                }, NO);
                return;
            }

            UIView *overlayRoot = [self resolveOverlayRootView];
            if (!overlayRoot) {
                callback(@{ @"code": @(-1), @"message": @"overlay root null" }, NO);
                return;
            }

            __weak typeof(self) weakSelf = self;
            [self hideCameraInternal:^{
                [weakSelf mountCameraOverlay:overlayRoot options:opts callback:callback];
            }];
        } @catch (NSException *exception) {
            callback(@{ @"code": @(-1), @"message": exception.reason ?: @"showCamera 异常" }, NO);
        }
    });
}

- (void)mountCameraOverlay:(UIView *)overlayRoot
                   options:(NSDictionary *)options
                  callback:(UniModuleKeepAliveCallback)callback {
    (void)overlayRoot;
    int x = [options[@"x"] intValue];
    int y = [options[@"y"] intValue];
    int width = [options[@"width"] intValue];
    int height = [options[@"height"] intValue];

    int pxW = [self cssToPhysical:width];
    int pxH = [self cssToPhysical:height];

    UIView *parent = [self resolveOverlayParentView];
    if (!parent) {
        callback(@{ @"code": @(-1), @"message": @"overlay parent null" }, NO);
        return;
    }

    // 对齐 Android：透明 PassThrough host 挂在 Normal+1 专用窗；仅曝光滑杆吃触摸
    FuPassThroughHost *host = [[FuPassThroughHost alloc] initWithFrame:parent.bounds];
    host.backgroundColor = [UIColor clearColor];
    host.opaque = NO;
    host.clipsToBounds = NO;
    host.userInteractionEnabled = YES;
    host.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [self attachOverlayHostOnDecor:parent host:host];

    CGRect boxFrame = [self previewBoxFrameOnDecor:x y:y width:width height:height];
    UIView *previewBox = [[UIView alloc] initWithFrame:boxFrame];
    previewBox.backgroundColor = [UIColor clearColor];
    previewBox.opaque = NO;
    previewBox.clipsToBounds = YES;
    previewBox.userInteractionEnabled = NO;
    previewBox.layer.zPosition = 10000.f;
    [host addSubview:previewBox];

    BeautyCameraView *view = [[BeautyCameraView alloc] initWithFrame:previewBox.bounds];
    view.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    view.layer.zPosition = 10001.f;
    view.clipsToBounds = YES;
    view.userInteractionEnabled = NO;
    [previewBox addSubview:view];
    [view bindLayoutSize:pxW height:pxH];
    [BeautyCameraView resetSessionDiag];
    [view setNeedsLayout];
    [view layoutIfNeeded];
    [view display];
    [view startPreview];

    sOverlayCameraView = view;
    sOverlayCameraHost = host;
    sLastCssX = x;
    sLastCssY = y;
    sLastCssW = width;
    sLastCssH = height;

    [self bringOverlayToFront];

    FU_LOG("showCamera css:%dx%d@%d,%d physical:%dx%d boxFrame=%@ parent=%@",
           width, height, x, y, pxW, pxH,
           NSStringFromCGRect(boxFrame),
           NSStringFromClass([parent class]));

    callback(@{
        @"code": @(0),
        @"data": @{
            @"x": @(x),
            @"y": @(y),
            @"width": @(width),
            @"height": @(height),
            @"reused": @(NO),
            @"cameraError": [BeautyCameraView lastError] ?: @"",
            @"diag": [BeautyCameraView previewDiag] ?: @"",
            @"boxFrame": NSStringFromCGRect(boxFrame),
            @"rootClass": NSStringFromClass([parent class]),
            @"windowLevel": @(sOverlayWindow ? sOverlayWindow.windowLevel : 0),
        }
    }, NO);

    //  uni-app 可能稍后重构视图层级，短暂重顶几次；soft-hide / gen 过期则跳过
    int bringGen = sOverlayBringGen;
    for (NSNumber *delay in @[ @0.3, @0.8, @1.5, @2.5 ]) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(delay.doubleValue * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            if (bringGen != sOverlayBringGen) {
                return;
            }
            [self bringOverlayToFront];
        });
    }

    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(3 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        FU_LOG("previewDiag %@ view=%dx%d box=%dx%d hostHidden=%@",
               [BeautyCameraView previewDiag],
               (int)view.bounds.size.width,
               (int)view.bounds.size.height,
               width,
               height,
               sOverlayCameraHost.hidden ? @"YES" : @"NO");
    });
}

UNI_EXPORT_METHOD(@selector(pauseCameraPreview:))
- (void)pauseCameraPreview:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        sOverlayBringGen += 1;
        if (sOverlayCameraView) {
            [sOverlayCameraView hidePreview];
        }
        if (sOverlayCameraHost) {
            sOverlayCameraHost.hidden = YES;
            sOverlayCameraHost.alpha = 0;
        }
        //  导入选择等无视频场景：整窗藏，避免冻帧残留
        if (!sOverlayVideoHost && sOverlayWindow) {
            sOverlayWindow.hidden = YES;
        }
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(resumeCameraPreview:))
- (void)resumeCameraPreview:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        // 先清 softHidden，再 bringOverlayToFront（后者会跳过仍soft-hide 的视图）
        if (sOverlayCameraView) {
            [sOverlayCameraView resumePreview];
        }
        [self bringOverlayToFront];
        if (sOverlayWindow) {
            sOverlayWindow.hidden = NO;
        }
        if (sOverlayCameraHost) {
            sOverlayCameraHost.hidden = NO;
            sOverlayCameraHost.alpha = 1;
        }
        if (sOverlayCameraView) {
            sOverlayCameraView.hidden = NO;
        }
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(destroyCameraPreview:))
- (void)destroyCameraPreview:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        // 彻底拆除
        [self hideCameraInternal:^{
            callback(@{ @"code": @(0), @"data": @(0) }, NO);
        }];
    });
}

UNI_EXPORT_METHOD(@selector(hideCamera:callback:))
- (void)hideCamera:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        BOOL keepSession = YES;
        if ([options isKindOfClass:[NSDictionary class]] && options[@"keepSession"] != nil) {
            keepSession = [options[@"keepSession"] boolValue];
        }
        if (keepSession) {
            // 导入/跳转：只关显示与相机采集，不拆GL，避免进媒体页卡很久
            [self softHideCameraOverlay:^{
                callback(@{ @"code": @(0), @"data": @(0) }, NO);
            }];
            return;
        }
        [self hideCameraInternal:^{
            callback(@{ @"code": @(0), @"data": @(0) }, NO);
        }];
    });
}

UNI_EXPORT_METHOD(@selector(hideCamera:))
- (void)hideCamera:(UniModuleKeepAliveCallback)callback {
    [self hideCamera:nil callback:callback];
}

UNI_EXPORT_METHOD(@selector(resizeCameraPreview:callback:))
- (void)resizeCameraPreview:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    NSMutableDictionary *opts = [NSMutableDictionary dictionaryWithDictionary:options ?: @{}];
    opts[@"resizeOnly"] = @YES;
    [self showCamera:opts callback:callback];
}

UNI_EXPORT_METHOD(@selector(setOverlayWindowsHidden:callback:))
- (void)setOverlayWindowsHidden:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        BOOL hidden = [options[@"hidden"] boolValue];
        //  只藏相机/视频专用窗，绝不隐藏业务主窗口（系统 Alert 依赖主窗）
        if (hidden) {
            //  有视频host 时勿整窗 hidden（马上要挂视频）；纯相机/导入选择页可整窗藏，避免冻帧盖住下层页
            [self dismissPreviewChrome];
            if (sOverlayCameraView) {
                [sOverlayCameraView hidePreview];
            }
            if (sOverlayCameraHost) {
                sOverlayCameraHost.hidden = YES;
            }
            if (sOverlayVideoHost) {
                sOverlayVideoHost.hidden = YES;
            }
            if (!sOverlayVideoHost && sOverlayWindow) {
                sOverlayWindow.hidden = YES;
            }
        } else {
            if (sOverlayWindow) {
                sOverlayWindow.hidden = NO;
            }
            // 只恢复视频叠层；相机必须由resumeCameraPreview / showCamera 显式恢复
            // （否则图片美颜页会把相机冻帧盖回来）
            if (sOverlayVideoHost) {
                sOverlayVideoHost.hidden = NO;
                [self bringVideoOverlayToFront];
            }
        }
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(getDevicePerformanceLevel:))
- (void)getDevicePerformanceLevel:(UniModuleKeepAliveCallback)callback {
    int level = FuDevicePerformanceLevelCached();
    callback(@{ @"code": @(0), @"data": @{ @"level": @(level) } }, NO);
}

UNI_EXPORT_METHOD(@selector(getPreviewDiag:))
- (void)getPreviewDiag:(UniModuleKeepAliveCallback)callback {
    callback(@{
        @"code": @(0),
        @"data": @{
            @"diag": [BeautyCameraView previewDiag] ?: @"",
            @"mounted": @(sOverlayCameraView != nil),
            @"stats": [BeautyCameraView previewStats] ?: @{},
        }
    }, NO);
}

UNI_EXPORT_METHOD(@selector(getPreviewStats:))
- (void)getPreviewStats:(UniModuleKeepAliveCallback)callback {
    NSDictionary *stats = [BeautyCameraView previewStats] ?: @{};
    NSNumber *tracking = stats[@"tracking"];
    NSNumber *frameCount = stats[@"frameCount"];
    if (sPreviewChromeView && tracking) {
        // 预览稳定后（约 1s@30fps）再提示，避免进页闪一下
        BOOL show = tracking.intValue == 0 &&
            [stats[@"previewStarted"] boolValue] &&
            frameCount.intValue > 30;
        dispatch_async(dispatch_get_main_queue(), ^{
            [sPreviewChromeView setNoFaceVisible:show];
        });
    }
    callback(@{ @"code": @(0), @"data": stats }, NO);
}

UNI_EXPORT_METHOD(@selector(tapFocus:callback:))
- (void)tapFocus:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!sOverlayCameraView) {
            callback(@{ @"code": @(-1), @"message": @"相机未挂载" }, NO);
            return;
        }
        CGFloat nx = [options[@"nx"] doubleValue];
        CGFloat ny = [options[@"ny"] doubleValue];
        if (options[@"nx"] == nil || options[@"ny"] == nil) {
            CGFloat x = [options[@"x"] doubleValue];
            CGFloat y = [options[@"y"] doubleValue];
            CGFloat w = MAX(1.0, sOverlayCameraView.bounds.size.width);
            CGFloat h = MAX(1.0, sOverlayCameraView.bounds.size.height);
            nx = x / w;
            ny = y / h;
        }
        [sOverlayCameraView tapFocusAtNormalizedX:nx y:ny];
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(setExposureBias:callback:))
- (void)setExposureBias:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!sOverlayCameraView) {
            callback(@{ @"code": @(-1), @"message": @"相机未挂载" }, NO);
            return;
        }
        // 归一化到 0~1（0.5=EV0），与原生曝光条一致：
        // - value>1：按 0~100
        // - value<0：按 -1~1 bias → (v+1)/2（对齐 Android）
        // - 0~1：已是归一化进度
        CGFloat raw = options[@"exposure"] != nil
            ? [options[@"exposure"] doubleValue]
            : [options[@"value"] doubleValue];
        CGFloat normalized = 0.5;
        if (raw > 1.0) {
            normalized = raw / 100.0;
        } else if (raw < 0.0) {
            normalized = (raw + 1.0) * 0.5;
        } else {
            normalized = raw;
        }
        [sOverlayCameraView setExposureNormalized:MIN(1.0, MAX(0.0, normalized))];
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(hideFocusChrome:))
- (void)hideFocusChrome:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        [sOverlayCameraView hideFocusChrome];
        if (callback) {
            callback(@{ @"code": @(0), @"data": @(0) }, NO);
        }
    });
}

UNI_EXPORT_METHOD(@selector(setBeautyEnabled:callback:))
- (void)setBeautyEnabled:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        BOOL enabled = YES;
        if ([options isKindOfClass:[NSDictionary class]]) {
            if (options[@"enabled"] != nil) {
                enabled = [options[@"enabled"] boolValue];
            }
        }
        [BeautyCameraView setBeautyEnabledGlobal:enabled];
        if (sOverlayCameraView) {
            [sOverlayCameraView setBeautyEnabled:enabled];
        }
        if (sOverlayVideoView) {
            [sOverlayVideoView setBeautyEnabled:enabled];
            if (![sOverlayVideoView isPlaying]) {
                [sOverlayVideoView redrawBeautyFrame];
            }
        }
        callback(@{ @"code": @(0), @"data": @(enabled ? 1 : 0) }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(switchCamera:))
- (void)switchCamera:(UniModuleKeepAliveCallback)callback {
    if (callback) {
        callback(@{ @"code": @(0), @"data": @{ @"pending": @(1) } }, YES);
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!sOverlayCameraView) {
            if (callback) {
                callback(@{ @"code": @(-1), @"message": @"相机未挂载" }, NO);
            }
            return;
        }
        [sOverlayCameraView switchCameraFacingWithCompletion:^(NSError *error) {
            if (callback) {
                if (error) {
                    callback(@{ @"code": @(-1), @"message": error.localizedDescription ?: @"切换摄像头失败" }, NO);
                } else {
                    callback(@{ @"code": @(0), @"data": @(0) }, NO);
                }
            }
        }];
    });
}

UNI_EXPORT_METHOD(@selector(setPreviewResolution:callback:))
- (void)setPreviewResolution:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        int width = [options[@"width"] intValue];
        int height = [options[@"height"] intValue];
        if (width <= 0 || height <= 0) {
            callback(@{ @"code": @(-1), @"message": @"width/height 无效" }, NO);
            return;
        }
        [BeautyCameraView setTargetPreviewSize:width height:height];
        if (sOverlayCameraView) {
            [sOverlayCameraView restartPreview];
        }
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(resetPreviewResolution:))
- (void)resetPreviewResolution:(UniModuleKeepAliveCallback)callback {
    [BeautyCameraView resetTargetPreviewSizeToDefault];
    if (callback) {
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    }
}

UNI_EXPORT_METHOD(@selector(capturePhoto:))
- (void)capturePhoto:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!sOverlayCameraView) {
            callback(@{ @"code": @(-1), @"message": @"相机未挂载" }, NO);
            return;
        }
        [sOverlayCameraView capturePhoto:^(NSString *path, NSError *error) {
            if (error || path.length == 0) {
                callback(@{ @"code": @(-1), @"message": error.localizedDescription ?: @"保存相册失败" }, NO);
                return;
            }
            callback(@{ @"code": @(0), @"data": @{ @"path": path } }, NO);
        }];
    });
}

UNI_EXPORT_METHOD(@selector(startVideoRecord:))
- (void)startVideoRecord:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!sOverlayCameraView) {
            callback(@{ @"code": @(-1), @"message": @"相机未挂载" }, NO);
            return;
        }
        NSError *err = nil;
        if (![sOverlayCameraView startVideoRecord:&err]) {
            callback(@{ @"code": @(-1), @"message": err.localizedDescription ?: @"开始录制失败" }, NO);
            return;
        }
        __weak typeof(self) weakSelf = self;
        sOverlayCameraView.onAutoStopRecording = ^(NSString *path, NSError *error) {
            __strong typeof(weakSelf) self = weakSelf;
            if (sPreviewChromeView) {
                [sPreviewChromeView setRecording:NO];
            }
            [self firePreviewChromeEvent:@"recordAutoStopped" extra:@{
                @"path": path ?: @"",
                @"ok": @(error == nil && path.length > 0),
                @"message": error.localizedDescription ?: @"",
            }];
        };
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(stopVideoRecord:))
- (void)stopVideoRecord:(UniModuleKeepAliveCallback)callback {
    // 相册保存是异步：必须 keepAlive，否则 JS 侧会「stopVideoRecord 超时」
    if (callback) {
        callback(@{ @"code": @(0), @"data": @{ @"pending": @(1) } }, YES);
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!sOverlayCameraView) {
            if (callback) {
                callback(@{ @"code": @(-1), @"message": @"相机未挂载" }, NO);
            }
            return;
        }
        [sOverlayCameraView stopVideoRecord:^(NSString *path, NSError *error) {
            if (sPreviewChromeView) {
                [sPreviewChromeView setRecording:NO];
            }
            if (error) {
                if (callback) {
                    callback(@{ @"code": @(-1), @"message": error.localizedDescription ?: @"保存视频失败" }, NO);
                }
                return;
            }
            // path 为空字符串表示幂等空 stop（未在录）
            if (callback) {
                callback(@{ @"code": @(0), @"data": @{ @"path": path.length ? path : @"photos://noop" } }, NO);
            }
        }];
    });
}

UNI_EXPORT_METHOD(@selector(showToast:callback:))
- (void)showToast:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        NSDictionary *opts = [options isKindOfClass:[NSDictionary class]] ? options : @{};
        NSString *title = opts[@"title"];
        if (![title isKindOfClass:[NSString class]] || title.length == 0) {
            callback(@{ @"code": @(-1), @"message": @"title 为空" }, NO);
            return;
        }
        NSTimeInterval duration = [opts[@"duration"] doubleValue];
        if (duration < 500) {
            duration = 2000;
        }
        [self presentOverlayToast:title durationMs:duration];
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    });
}

/** 原生确认框：独立普通UIWindow（勿挂FuOverlayWindow，其 hitTest 会穿透导致点到对焦） */
UNI_EXPORT_METHOD(@selector(showConfirm:callback:))
- (void)showConfirm:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    //  Uni 延迟回调必须先keepAlive，否则点确认后JS 收不到结果（表现为恢复无效果）
    if (callback) {
        callback(@{ @"code": @(0), @"data": @{ @"pending": @(1) } }, YES);
    }
    NSDictionary *opts = [options isKindOfClass:[NSDictionary class]] ? options : @{};
    [self presentNativeConfirm:opts completion:^(BOOL confirm) {
        if (callback) {
            callback(@{
                @"code": @(0),
                @"data": @{ @"confirm": @(confirm ? 1 : 0) }
            }, NO);
        }
    }];
}

- (void)presentNativeConfirm:(NSDictionary *)opts completion:(void (^)(BOOL confirm))completion {
    dispatch_async(dispatch_get_main_queue(), ^{
        NSString *title = [opts[@"title"] isKindOfClass:[NSString class]] ? opts[@"title"] : @"提示";
        NSString *content = [opts[@"content"] isKindOfClass:[NSString class]] ? opts[@"content"] : @"";
        NSString *confirmText = [opts[@"confirmText"] isKindOfClass:[NSString class]] ? opts[@"confirmText"] : @"确定";
        NSString *cancelText = [opts[@"cancelText"] isKindOfClass:[NSString class]] ? opts[@"cancelText"] : @"取消";

        @try {
            if (sOverlayCameraView) {
                [sOverlayCameraView hideFocusChrome];
            }
            // 确认期间关掉 overlay 触摸，避免点到对焦/面板
            if (sOverlayWindow) {
                sOverlayWindow.userInteractionEnabled = NO;
            }
            if (sBeautyPanelView) {
                sBeautyPanelView.userInteractionEnabled = NO;
            }

            if (sConfirmWindow) {
                @try {
                    UIViewController *oldRoot = sConfirmWindow.rootViewController;
                    if (oldRoot.presentedViewController) {
                        [oldRoot dismissViewControllerAnimated:NO completion:nil];
                    }
                } @catch (__unused NSException *e) {
                }
                sConfirmWindow.hidden = YES;
                sConfirmWindow.rootViewController = nil;
                sConfirmWindow = nil;
            }

            // 独立普通 UIWindow（绝不用 FuOverlayWindow：其 hitTest 穿透）
            CGRect screenBounds = UIScreen.mainScreen.bounds;
            UIWindow *win = nil;
            if (@available(iOS 13.0, *)) {
                UIWindow *biz = [self resolveBusinessWindow];
                UIWindowScene *scene = biz.windowScene ?: sOverlayWindow.windowScene;
                if (scene) {
                    win = [[UIWindow alloc] initWithWindowScene:scene];
                    win.frame = screenBounds;
                }
            }
            if (!win) {
                win = [[UIWindow alloc] initWithFrame:screenBounds];
            }
            win.windowLevel = UIWindowLevelAlert + 50.f;
            win.backgroundColor = [UIColor clearColor];
            win.userInteractionEnabled = YES;
            win.hidden = NO;
            UIViewController *vc = [[UIViewController alloc] init];
            // 无半透明遮罩：仅 Alert
            vc.view.backgroundColor = [UIColor clearColor];
            vc.view.userInteractionEnabled = YES;
            win.rootViewController = vc;
            sConfirmPrevKeyWindow = UIApplication.sharedApplication.keyWindow;
            if (sConfirmPrevKeyWindow == sOverlayWindow || sConfirmPrevKeyWindow == sExportProgressWindow) {
                sConfirmPrevKeyWindow = [self resolveBusinessWindow];
            }
            sConfirmWindow = win;
            [win makeKeyAndVisible];

            void (^finish)(BOOL) = ^(BOOL confirm) {
                sPendingConfirmFinish = nil;
                // 关键：先恢复交互并回调业务（恢复默认），再拆 Alert 窗。
                // 旧逻辑先 nil rootVC 再回调，dismiss 异常时会吞掉整段恢复。
                if (sBeautyPanelView) {
                    sBeautyPanelView.userInteractionEnabled = YES;
                }
                if (sOverlayWindow) {
                    sOverlayWindow.userInteractionEnabled = YES;
                }
                if (completion) {
                    completion(confirm);
                }
                UIWindow *confirmWin = sConfirmWindow;
                sConfirmWindow = nil;
                UIWindow *prev = sConfirmPrevKeyWindow;
                sConfirmPrevKeyWindow = nil;
                dispatch_async(dispatch_get_main_queue(), ^{
                    @try {
                        if (confirmWin) {
                            UIViewController *root = confirmWin.rootViewController;
                            if (root.presentedViewController) {
                                [root dismissViewControllerAnimated:NO completion:^{
                                    confirmWin.hidden = YES;
                                    confirmWin.rootViewController = nil;
                                }];
                            } else {
                                confirmWin.hidden = YES;
                                confirmWin.rootViewController = nil;
                            }
                        }
                    } @catch (__unused NSException *e) {
                    }
                    UIWindow *biz = prev ?: [self resolveBusinessWindow];
                    if (biz && !biz.hidden && biz != sOverlayWindow) {
                        [biz makeKeyWindow];
                    }
                });
            };
            sPendingConfirmFinish = [finish copy];

            UIAlertController *alert =
                [UIAlertController alertControllerWithTitle:title
                                                    message:content
                                             preferredStyle:UIAlertControllerStyleAlert];
            [alert addAction:[UIAlertAction actionWithTitle:cancelText
                                                      style:UIAlertActionStyleCancel
                                                    handler:^(__unused UIAlertAction *action) {
                if (sPendingConfirmFinish) {
                    sPendingConfirmFinish(NO);
                }
            }]];
            [alert addAction:[UIAlertAction actionWithTitle:confirmText
                                                      style:UIAlertActionStyleDefault
                                                    handler:^(__unused UIAlertAction *action) {
                if (sPendingConfirmFinish) {
                    sPendingConfirmFinish(YES);
                }
            }]];
            [vc presentViewController:alert animated:YES completion:nil];
        } @catch (__unused NSException *e) {
            if (sBeautyPanelView) {
                sBeautyPanelView.userInteractionEnabled = YES;
            }
            if (sOverlayWindow) {
                sOverlayWindow.userInteractionEnabled = YES;
            }
            if (completion) {
                completion(NO);
            }
        }
    });
}

- (void)onFuConfirmCancel:(UIButton *)sender {
    (void)sender;
    if (sPendingConfirmFinish) {
        sPendingConfirmFinish(NO);
    }
}

- (void)onFuConfirmOk:(UIButton *)sender {
    (void)sender;
    if (sPendingConfirmFinish) {
        sPendingConfirmFinish(YES);
    }
}

UNI_EXPORT_METHOD(@selector(processImage:callback:))
- (void)processImage:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    NSDictionary *opts = [options isKindOfClass:[NSDictionary class]] ? [options copy] : @{};
    dispatch_async(FuProcessImageQueue(), ^{
        if (gProcessImageRunning) {
            gProcessImageRunAgain = YES;
            gProcessImagePendingOpts = opts;
            gProcessImagePendingCallback = callback;
            return;
        }
        gProcessImageRunning = YES;
        NSDictionary *runOpts = opts;
        UniModuleKeepAliveCallback runCb = callback;
        do {
            gProcessImageRunAgain = NO;
            if (gProcessImagePendingOpts) {
                runOpts = gProcessImagePendingOpts;
                gProcessImagePendingOpts = nil;
            }
            if (gProcessImagePendingCallback) {
                runCb = gProcessImagePendingCallback;
                gProcessImagePendingCallback = nil;
            }
            [self processImageSync:runOpts callback:runCb];
        } while (gProcessImageRunAgain);
        gProcessImageRunning = NO;
    });
}

- (void)processImageSync:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    CFAbsoluteTime t0 = CFAbsoluteTimeGetCurrent();
    @try {
            [self ensureInitialized];
            if (FuBeautyMediaHandle <= 0 && FuBeautyCameraHandle > 0) {
                FuBeautySetPipelineHandle(YES, FuBeautyCameraHandle);
                FuEnableAdvancedBeautyRuntime(FuBeautyCameraHandle);
            }
            NSString *path = options[@"path"];
            if (![path isKindOfClass:[NSString class]] || path.length == 0) {
                callback(@{ @"code": @(-1), @"message": @"path 不能为空" }, NO);
                return;
            }
            int handle = FuBeautyMediaHandle > 0
                ? FuBeautyMediaHandle
                : (FuBeautyCameraHandle > 0 ? FuBeautyCameraHandle : _beautyHandle);
            if (handle <= 0) {
                callback(@{ @"code": @(-1), @"message": @"请先 loadBundle" }, NO);
                return;
            }
            FU_LOG("processImage begin handle=%d media=%d camera=%d", handle, FuBeautyMediaHandle, FuBeautyCameraHandle);

            NSString *realPath = [self normalizeLocalPath:path];
            UIImage *image = [UIImage imageWithContentsOfFile:realPath];
            if (!image) {
                callback(@{ @"code": @(-1), @"message": [NSString stringWithFormat:@"无法解码图片: %@", realPath] }, NO);
                return;
            }
            image = [self normalizedUpImage:image];
            CGFloat maxSide = 1280;
            if (options[@"maxSide"] != nil) {
                double v = [options[@"maxSide"] doubleValue];
                if (v >= 256 && v <= 4096) {
                    maxSide = (CGFloat)v;
                }
            }
            image = [self maybeScaleImage:image maxSide:maxSide];

            CGImageRef cgImage = image.CGImage;
            size_t width = CGImageGetWidth(cgImage);
            size_t height = CGImageGetHeight(cgImage);
            if (width == 0 || height == 0) {
                callback(@{ @"code": @(-1), @"message": @"图片尺寸无效" }, NO);
                return;
            }
            // 保证偶数
            width = width & ~1;
            height = height & ~1;
            size_t bytesPerRow = width * 4;
            size_t bufSize = bytesPerRow * height;
            unsigned char *bgra = (unsigned char *)calloc(1, bufSize);
            if (!bgra) {
                callback(@{ @"code": @(-1), @"message": @"内存不足" }, NO);
                return;
            }

            CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
            //  非预乘BGRA：FU 常把 alpha 写成 0，预乘路径会导出成白图
            CGContextRef ctx = CGBitmapContextCreate(
                bgra,
                width,
                height,
                8,
                bytesPerRow,
                colorSpace,
                kCGBitmapByteOrder32Little | kCGImageAlphaNoneSkipFirst
            );
            CGColorSpaceRelease(colorSpace);
            if (!ctx) {
                free(bgra);
                callback(@{ @"code": @(-1), @"message": @"创建 bitmap context 失败" }, NO);
                return;
            }
            CGContextSetRGBFillColor(ctx, 0, 0, 0, 1);
            CGContextFillRect(ctx, CGRectMake(0, 0, width, height));
            CGContextDrawImage(ctx, CGRectMake(0, 0, width, height), cgImage);
            CGContextRelease(ctx);

            __block int ret = -1;
            __block int sysErr = 0;
            NSError *glErr = nil;
            if (![BeautyCameraView ensureSharedGLContextReady:&glErr]) {
                free(bgra);
                callback(@{
                    @"code": @(-1),
                    @"message": glErr.localizedDescription ?: @"GL 上下文未就绪"
                }, NO);
                return;
            }
            [BeautyCameraView performWithSharedGLLock:^{
                fuMakeGLContextCurrent();
                [BeautyCameraView flushPendingBeautyParams];
                FuReconfirmSpecialBeautySwitches(handle);
                fuSetDefaultRotationMode(FU_ROTATION_MODE_0);
                fuSetInputCameraMatrix(0, 0, FU_ROTATION_MODE_0);
                fuSetInputCameraBufferMatrix(CCROT0);
                fuSetInputCameraBufferMatrixState(true);
                fuSetInputCameraTextureMatrixState(false);
                fuSetOutputMatrixState(false);
                fuSetOutputResolution((int)width, (int)height);
                fuSetFaceProcessorDetectMode(0);
                _frameId += 1;
                int items[1] = { handle };
                ret = fuRender(
                    FU_FORMAT_BGRA_BUFFER,
                    bgra,
                    FU_FORMAT_BGRA_BUFFER,
                    bgra,
                    (int)width,
                    (int)height,
                    _frameId,
                    items,
                    1,
                    NAMA_RENDER_FEATURE_FULL,
                    NULL
                );
                sysErr = fuGetSystemError();
                fuSetFaceProcessorDetectMode(1);
            }];
            if (ret < 0 || sysErr != 0) {
                free(bgra);
                NSString *errStr = sysErr != 0
                    ? [NSString stringWithUTF8String:fuGetSystemErrorString(sysErr)]
                    : @"";
                callback(@{
                    @"code": @(-1),
                    @"message": [NSString stringWithFormat:@"fuRender 失败 ret=%d sys=%d %@", ret, sysErr, errStr ?: @""]
                }, NO);
                return;
            }

            // 强制不透明，防止JPEG 白图
            size_t pxCount = width * height;
            for (size_t i = 0; i < pxCount; i++) {
                bgra[i * 4 + 3] = 255;
            }

            CGColorSpaceRef outSpace = CGColorSpaceCreateDeviceRGB();
            CGContextRef outCtx = CGBitmapContextCreate(
                bgra,
                width,
                height,
                8,
                bytesPerRow,
                outSpace,
                kCGBitmapByteOrder32Little | kCGImageAlphaNoneSkipFirst
            );
            CGColorSpaceRelease(outSpace);
            if (!outCtx) {
                free(bgra);
                callback(@{ @"code": @(-1), @"message": @"创建输出 context 失败" }, NO);
                return;
            }
            CGImageRef outCg = CGBitmapContextCreateImage(outCtx);
            CGContextRelease(outCtx);
            UIImage *outImage = [UIImage imageWithCGImage:outCg scale:1.0 orientation:UIImageOrientationUp];
            CGImageRelease(outCg);

            NSString *outPath = [NSTemporaryDirectory() stringByAppendingPathComponent:
                [NSString stringWithFormat:@"fu_still_%.0f.jpg", [[NSDate date] timeIntervalSince1970] * 1000]];
            NSData *jpeg = UIImageJPEGRepresentation(outImage, 0.92);
            free(bgra);
            if (!jpeg || ![jpeg writeToFile:outPath atomically:YES]) {
                callback(@{ @"code": @(-1), @"message": @"写结果图失败" }, NO);
                return;
            }
            FU_LOG("processImage ok %@ %zux%zu cost=%.0fms handle=%d",
                   outPath, width, height, (CFAbsoluteTimeGetCurrent() - t0) * 1000.0, handle);
            callback(@{ @"code": @(0), @"data": @{ @"path": outPath } }, NO);
        } @catch (NSException *exception) {
            callback(@{ @"code": @(-1), @"message": exception.reason ?: @"processImage 异常" }, NO);
        }
}

#pragma mark - Video overlay + export

UNI_EXPORT_METHOD(@selector(showVideoPreview:callback:))
- (void)showVideoPreview:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        @try {
            [self ensureInitialized];
            NSDictionary *opts = [options isKindOfClass:[NSDictionary class]] ? options : @{};
            NSString *path = opts[@"path"];
            if (![path isKindOfClass:[NSString class]] || path.length == 0) {
                callback(@{ @"code": @(-1), @"message": @"path 不能为空" }, NO);
                return;
            }
            int x = [opts[@"x"] intValue];
            int y = [opts[@"y"] intValue];
            int width = [opts[@"width"] intValue];
            int height = [opts[@"height"] intValue];
            if (width <= 0 || height <= 0) {
                callback(@{ @"code": @(-1), @"message": @"width/height 无效" }, NO);
                return;
            }

            //  同路径已挂载：只改预览框，避免面板展开时整段重载
            if (sOverlayVideoView && sOverlayVideoHost && [sLastVideoPath isEqualToString:path]) {
                int pxW = [self cssToPhysical:width];
                int pxH = [self cssToPhysical:height];
                CGRect boxFrame = [self previewBoxFrameOnDecor:x y:y width:width height:height];
                UIView *previewBox = sOverlayVideoView.superview;
                if (previewBox) {
                    previewBox.frame = boxFrame;
                    sOverlayVideoView.frame = previewBox.bounds;
                }
                [sOverlayVideoView bindLayoutSize:pxW height:pxH];
                sLastCssX = x;
                sLastCssY = y;
                sLastCssW = width;
                sLastCssH = height;
                [self bringVideoOverlayToFront];
                FU_LOG("showVideoPreview resized css:%dx%d@%d,%d", width, height, x, y);
                callback(@{
                    @"code": @(0),
                    @"data": @{
                        @"x": @(x),
                        @"y": @(y),
                        @"width": @(width),
                        @"height": @(height),
                        @"reused": @(YES),
                    }
                }, NO);
                return;
            }

            __weak typeof(self) weakSelf = self;
            [self destroyVideoPreviewInternal:^{
                //  勿拆相机 GL：只软隐藏，媒体页可立刻挂视频
                [weakSelf softHideCameraOverlay:^{
                    [weakSelf mountVideoOverlay:opts callback:callback];
                }];
            }];
        } @catch (NSException *exception) {
            callback(@{ @"code": @(-1), @"message": exception.reason ?: @"showVideoPreview 异常" }, NO);
        }
    });
}

- (void)mountVideoOverlay:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    NSString *path = options[@"path"];
    int x = [options[@"x"] intValue];
    int y = [options[@"y"] intValue];
    int width = [options[@"width"] intValue];
    int height = [options[@"height"] intValue];
    int pxW = [self cssToPhysical:width];
    int pxH = [self cssToPhysical:height];

    UIView *parent = [self resolveOverlayParentView];
    if (!parent) {
        callback(@{ @"code": @(-1), @"message": @"overlay parent null" }, NO);
        return;
    }

    //  视频预览同样挂Normal+1 透明窗（对齐 Android decor 叠层）
    FuPassThroughHost *host = [[FuPassThroughHost alloc] initWithFrame:parent.bounds];
    host.backgroundColor = [UIColor clearColor];
    host.opaque = NO;
    host.clipsToBounds = NO;
    host.userInteractionEnabled = YES;
    host.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [self attachOverlayHostOnDecor:parent host:host];

    CGRect boxFrame = [self previewBoxFrameOnDecor:x y:y width:width height:height];
    UIView *previewBox = [[UIView alloc] initWithFrame:boxFrame];
    previewBox.backgroundColor = [UIColor blackColor];
    previewBox.opaque = YES;
    previewBox.clipsToBounds = YES;
    previewBox.layer.zPosition = 10000.f;
    [host addSubview:previewBox];

    //  媒体 handle 未绑定时复用相机 handle（与 JS initNamaForMedia 对齐）
    if (FuBeautyMediaHandle <= 0 && FuBeautyCameraHandle > 0) {
        FuBeautySetPipelineHandle(YES, FuBeautyCameraHandle);
        FuEnableAdvancedBeautyRuntime(FuBeautyCameraHandle);
        FU_LOG("mountVideoOverlay reuse cameraHandle=%d as media", FuBeautyCameraHandle);
    }

    BeautyVideoView *view = [[BeautyVideoView alloc] initWithFrame:previewBox.bounds];
    view.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    view.clipsToBounds = YES;
    [previewBox addSubview:view];
    [view bindLayoutSize:pxW height:pxH];

    UIButton *playBtn = [UIButton buttonWithType:UIButtonTypeCustom];
    playBtn.tag = kFuChromeInteractiveTag;
    // 上移：避免与底部保存/下载钮叠在一起（约在取景框垂直 32% 处）
    CGFloat playSize = 85;
    CGFloat playY = MAX(24, CGRectGetHeight(previewBox.bounds) * 0.32 - playSize * 0.5);
    playBtn.frame = CGRectMake((CGRectGetWidth(previewBox.bounds) - playSize) * 0.5,
                               playY, playSize, playSize);
    playBtn.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin | UIViewAutoresizingFlexibleRightMargin |
        UIViewAutoresizingFlexibleBottomMargin;
    playBtn.backgroundColor = [UIColor clearColor];
    playBtn.layer.cornerRadius = 0;
    playBtn.clipsToBounds = NO;
    playBtn.hidden = YES;
    UIImage *playImg = nil;
    {
        NSBundle *b = [NSBundle bundleForClass:FuPreviewChromeViewClass() ?: [UIView class]];
        NSString *p = [b pathForResource:@"play" ofType:@"png" inDirectory:@"fu_chrome"];
        if (!p) {
            p = [b pathForResource:@"play" ofType:@"png"];
        }
        if (p) {
            playImg = [UIImage imageWithContentsOfFile:p];
        }
    }
    if (playImg) {
        [playBtn setBackgroundImage:[playImg imageWithRenderingMode:UIImageRenderingModeAlwaysOriginal]
                           forState:UIControlStateNormal];
        playBtn.imageView.contentMode = UIViewContentModeScaleAspectFit;
    } else {
        playBtn.backgroundColor = [UIColor colorWithWhite:1 alpha:0.92];
        playBtn.layer.cornerRadius = playSize * 0.5;
        playBtn.clipsToBounds = YES;
        [playBtn setTitle:@"▶" forState:UIControlStateNormal];
        [playBtn setTitleColor:[UIColor colorWithWhite:0.15 alpha:1] forState:UIControlStateNormal];
        playBtn.titleLabel.font = [UIFont systemFontOfSize:28 weight:UIFontWeightSemibold];
    }
    [playBtn addTarget:self action:@selector(onOverlayVideoPlay:) forControlEvents:UIControlEventTouchUpInside];
    [previewBox addSubview:playBtn];
    sOverlayVideoPlayBtn = playBtn;

    NSError *loadErr = nil;
    if (![view loadVideoPath:path error:&loadErr]) {
        [view removeFromSuperview];
        [host removeFromSuperview];
        sOverlayVideoPlayBtn = nil;
        callback(@{ @"code": @(-1), @"message": loadErr.localizedDescription ?: @"加载视频失败" }, NO);
        return;
    }

    __weak typeof(self) weakSelf = self;
    view.onFirstFrame = ^{
        if (sOverlayVideoPlayBtn) {
            sOverlayVideoPlayBtn.hidden = NO;
            [sOverlayVideoPlayBtn.superview bringSubviewToFront:sOverlayVideoPlayBtn];
        }
    };
    view.onPlaybackEnded = ^{
        if (sOverlayVideoPlayBtn) {
            sOverlayVideoPlayBtn.hidden = NO;
            [sOverlayVideoPlayBtn.superview bringSubviewToFront:sOverlayVideoPlayBtn];
        }
        [weakSelf fireVideoEvent:@"ended" extra:nil];
    };
    // 默认暂停 + 首帧；不循环播放
    [view prepareFirstFrame];

    sOverlayVideoView = view;
    sOverlayVideoHost = host;
    sLastVideoPath = [path copy];
    sLastCssX = x;
    sLastCssY = y;
    sLastCssW = width;
    sLastCssH = height;
    if (sOverlayWindow) {
        sOverlayWindow.hidden = NO;
    }
    [self bringVideoOverlayToFront];
    // 面板若已创建，挪到视频 host，避免仍挂在隐藏相机层
    if (sBeautyPanelView) {
        NSString *mode = sBeautyPanelView.mode ?: @"video";
        UIView *panelParent = [self resolveBeautyPanelParentForMode:mode];
        if (panelParent && sBeautyPanelView.superview != panelParent) {
            [sBeautyPanelView removeFromSuperview];
            [panelParent addSubview:sBeautyPanelView];
        }
        [self syncBeautyPanelLayout];
    }

    FU_LOG("showVideoPreview css:%dx%d@%d,%d box=%@ path=%@ mediaHandle=%d",
           width, height, x, y, NSStringFromCGRect(boxFrame), path, FuBeautyMediaHandle);
    callback(@{
        @"code": @(0),
        @"data": @{
            @"x": @(x),
            @"y": @(y),
            @"width": @(width),
            @"height": @(height),
        }
    }, NO);

    for (NSNumber *delay in @[ @0.3, @0.8, @1.5 ]) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(delay.doubleValue * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [self bringVideoOverlayToFront];
            if (sBeautyPanelView) {
                [self syncBeautyPanelLayout];
                UIView *parent = sBeautyPanelView.superview;
                if (parent) {
                    [self ensureMediaBackButtonOnParent:parent];
                }
            }
        });
    }
}

UNI_EXPORT_METHOD(@selector(pauseVideoPreview:))
- (void)pauseVideoPreview:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (sOverlayVideoView) {
            [sOverlayVideoView pause];
        }
        if (sOverlayVideoPlayBtn) {
            sOverlayVideoPlayBtn.hidden = NO;
            [sOverlayVideoPlayBtn.superview bringSubviewToFront:sOverlayVideoPlayBtn];
        }
        [self fireVideoEvent:@"paused" extra:nil];
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(resumeVideoPreview:))
- (void)resumeVideoPreview:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        [self bringVideoOverlayToFront];
        if (sOverlayVideoHost) {
            sOverlayVideoHost.hidden = NO;
        }
        if (sOverlayVideoView) {
            [sOverlayVideoView play];
        }
        if (sOverlayVideoPlayBtn) {
            sOverlayVideoPlayBtn.hidden = YES;
        }
        [self fireVideoEvent:@"playing" extra:nil];
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
    });
}

UNI_EXPORT_METHOD(@selector(destroyVideoPreview:))
- (void)destroyVideoPreview:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        [self destroyVideoPreviewInternal:^{
            callback(@{ @"code": @(0), @"data": @(0) }, NO);
        }];
    });
}

UNI_EXPORT_METHOD(@selector(processVideo:callback:))
- (void)processVideo:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    @try {
        [self ensureInitialized];
        NSString *path = options[@"path"];
        if (![path isKindOfClass:[NSString class]] || path.length == 0) {
            callback(@{ @"code": @(-1), @"message": @"path 不能为空" }, NO);
            return;
        }
        if (FuBeautyMediaHandle <= 0 && FuBeautyCameraHandle > 0) {
            FuBeautySetPipelineHandle(YES, FuBeautyCameraHandle);
            FuEnableAdvancedBeautyRuntime(FuBeautyCameraHandle);
        }
        int handle = FuBeautyMediaHandle > 0
            ? FuBeautyMediaHandle
            : (FuBeautyCameraHandle > 0 ? FuBeautyCameraHandle : _beautyHandle);
        if (handle <= 0) {
            callback(@{ @"code": @(-1), @"message": @"请先 loadBundle" }, NO);
            return;
        }
        if (callback) {
            callback(@{ @"code": @(0), @"data": @{ @"pending": @(1) } }, YES);
        }
        // 导出前暂停预览，避免争用 GL（processVideo 常在主线程，禁止 dispatch_sync main）
        [self showExportProgressHudRatio:0.f];
        sExportCancelled = NO;
        void (^pausePreviewForExport)(void) = ^{
            if (sOverlayVideoView) {
                [sOverlayVideoView pause];
            }
            if (sOverlayVideoPlayBtn) {
                sOverlayVideoPlayBtn.hidden = YES;
            }
            if (sBeautyPanelView) {
                sBeautyPanelView.userInteractionEnabled = YES;
            }
        };
        if ([NSThread isMainThread]) {
            pausePreviewForExport();
        } else {
            dispatch_sync(dispatch_get_main_queue(), pausePreviewForExport);
        }
        // 导出前 flush 排队美颜参，避免调参后保存仍是旧效果/空写
        [BeautyCameraView performWithSharedGLLock:^{
            [BeautyCameraView flushPendingBeautyParams];
        }];
        NSString *realPath = [self normalizeLocalPath:path];
        __weak typeof(self) weakSelf = self;
        [VideoBeautyExporter exportVideoAtPath:realPath
                                  beautyHandle:handle
                                maxDurationSec:60
                                       maxSide:1280
                                      progress:^(float p) {
            [weakSelf showExportProgressHudRatio:p];
        }
                                    completion:^(NSString *outPath, NSError *error) {
            [weakSelf hideExportProgressHud];
            dispatch_async(dispatch_get_main_queue(), ^{
                if (sOverlayVideoPlayBtn) {
                    sOverlayVideoPlayBtn.hidden = NO;
                }
                if (sBeautyPanelView) {
                    sBeautyPanelView.userInteractionEnabled = YES;
                }
            });
            if (sExportCancelled || [error.domain isEqualToString:@"FaceUnityNama"] && error.code == -2) {
                callback(@{ @"code": @(-2), @"message": @"导出已取消" }, NO);
                return;
            }
            if (error || outPath.length == 0) {
                callback(@{
                    @"code": @(-1),
                    @"message": error.localizedDescription ?: @"视频导出失败"
                }, NO);
                return;
            }
            callback(@{ @"code": @(0), @"data": @{ @"path": outPath } }, NO);
        }];
    } @catch (NSException *exception) {
        [self hideExportProgressHud];
        callback(@{ @"code": @(-1), @"message": exception.reason ?: @"processVideo 异常" }, NO);
    }
}

- (void)bringVideoOverlayToFront {
    if (!sOverlayVideoHost) {
        return;
    }
    [self syncOverlayWindowLevel];
    [self attachOverlayHostOnDecor:nil host:sOverlayVideoHost];
    sOverlayVideoHost.hidden = NO;
    if (sOverlayWindow) {
        sOverlayWindow.hidden = NO;
    }
}

- (void)destroyVideoPreviewInternal:(void (^)(void))onComplete {
    [self hideMediaBackButton];
    if (!sOverlayVideoView) {
        if (sOverlayVideoHost) {
            [sOverlayVideoHost removeFromSuperview];
            sOverlayVideoHost = nil;
        }
        sOverlayVideoPlayBtn = nil;
        sLastVideoPath = nil;
        if (onComplete) {
            onComplete();
        }
        return;
    }
    BeautyVideoView *view = sOverlayVideoView;
    UIView *host = sOverlayVideoHost;
    sOverlayVideoView = nil;
    sOverlayVideoHost = nil;
    sOverlayVideoPlayBtn = nil;
    sLastVideoPath = nil;
    [view stopAndRelease];
    [view removeFromSuperview];
    [host removeFromSuperview];
    if (onComplete) {
        onComplete();
    }
}

- (NSString *)normalizeLocalPath:(NSString *)path {
    if ([path hasPrefix:@"file://"]) {
        NSURL *url = [NSURL URLWithString:path];
        if (url.path.length > 0) {
            return url.path;
        }
        return [path substringFromIndex:7];
    }
    return path;
}

- (UIImage *)normalizedUpImage:(UIImage *)image {
    if (image.imageOrientation == UIImageOrientationUp && image.scale <= 1.01) {
        return image;
    }
    CGImageRef cg = image.CGImage;
    if (!cg) {
        return image;
    }
    CGFloat w = (CGFloat)CGImageGetWidth(cg);
    CGFloat h = (CGFloat)CGImageGetHeight(cg);
    if (w < 1 || h < 1) {
        return image;
    }
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(w, h), YES, 1.0);
    [image drawInRect:CGRectMake(0, 0, w, h)];
    UIImage *normalized = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();
    return normalized ?: image;
}

- (UIImage *)maybeScaleImage:(UIImage *)image maxSide:(CGFloat)maxSide {
    CGImageRef cg = image.CGImage;
    if (!cg) {
        return image;
    }
    CGFloat w = (CGFloat)CGImageGetWidth(cg);
    CGFloat h = (CGFloat)CGImageGetHeight(cg);
    CGFloat side = MAX(w, h);
    if (side <= maxSide) {
        return image;
    }
    CGFloat scale = maxSide / side;
    CGSize size = CGSizeMake(floor(w * scale), floor(h * scale));
    UIGraphicsBeginImageContextWithOptions(size, YES, 1.0);
    [image drawInRect:CGRectMake(0, 0, size.width, size.height)];
    UIImage *scaled = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();
    return scaled ?: image;
}

UNI_EXPORT_METHOD(@selector(destroy:))
- (void)destroy:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        [self hideCameraInternal:^{
            [BeautyCameraView shutdownSharedGLContext];
            fuDestroyAllItems();
            fuDestroyLibData();
            _beautyHandle = 0;
            FuBeautyClearAll();
            _frameId = 0;
            callback(@{ @"code": @(0), @"data": @(0) }, NO);
        }];
    });
}

#pragma mark - helpers

- (NSDictionary *)buildShowCameraData:(int)x y:(int)y width:(int)width height:(int)height reused:(BOOL)reused {
    return @{
        @"x": @(x),
        @"y": @(y),
        @"width": @(width),
        @"height": @(height),
        @"reused": @(reused),
        @"cameraError": [BeautyCameraView lastError] ?: @"",
        @"diag": [BeautyCameraView previewDiag] ?: @"",
        @"boxFrame": NSStringFromCGRect(CGRectMake(x, y, width, height)),
    };
}

- (int)cssToPhysical:(int)css {
    // 对齐 Android cssToPhysical(css * density)
    return (int)(css * UIScreen.mainScreen.scale + 0.5f);
}

- (UIView *)findWebViewIn:(UIView *)view {
    if (!view) {
        return nil;
    }
    if ([view isKindOfClass:NSClassFromString(@"WKWebView")]) {
        return view;
    }
    NSString *cls = NSStringFromClass([view class]);
    if ([cls containsString:@"WebView"] || [cls containsString:@"webview"]) {
        return view;
    }
    for (UIView *sub in view.subviews) {
        UIView *found = [self findWebViewIn:sub];
        if (found) {
            return found;
        }
    }
    return nil;
}

- (CGRect)previewBoxFrameOnDecor:(int)x y:(int)y width:(int)width height:(int)height {
    //  Vue 传入 WebView 内容坐标 →转到「相机专用透明窗」坐标系（对齐Android decor 叠层）
    CGRect cssBox = CGRectMake((CGFloat)x, (CGFloat)y, (CGFloat)width, (CGFloat)height);
    UIWindow *overlayWindow = [self ensureOverlayWindow];
    UIWindow *appWindow = [self resolveBusinessWindow];
    UIView *webView = appWindow ? [self findWebViewIn:appWindow] : nil;
    CGRect inOverlay = CGRectZero;
    BOOL converted = NO;
    if (webView && overlayWindow) {
        CGRect inWindow = [webView convertRect:cssBox toView:nil];
        inOverlay = [overlayWindow convertRect:inWindow fromWindow:nil];
        converted = YES;
        FU_LOG("previewBox css=%@ web=%@ -> overlay=%@",
               NSStringFromCGRect(cssBox),
               NSStringFromClass([webView class]),
               NSStringFromCGRect(inOverlay));
    } else {
        // 勿用 density 换算：JS 已是 pt；再 *scale 会偶发把框甩飞偏左
        inOverlay = cssBox;
    }

    // 全宽取景偶发 convertRect 得到异常负x / 偏移：强制贴左铺满（相机页常态）
    CGFloat ow = overlayWindow ? CGRectGetWidth(overlayWindow.bounds) : UIScreen.mainScreen.bounds.size.width;
    CGFloat oh = overlayWindow ? CGRectGetHeight(overlayWindow.bounds) : UIScreen.mainScreen.bounds.size.height;
    if (ow > 1.f && cssBox.size.width >= ow * 0.85f) {
        if (fabs(inOverlay.origin.x) > 0.5f || inOverlay.size.width < ow * 0.9f || !converted) {
            FU_LOG("previewBox clamp full-bleed x=%.1f -> 0 (converted=%d)", inOverlay.origin.x, converted ? 1 : 0);
            inOverlay.origin.x = 0;
            inOverlay.size.width = ow;
        }
    } else if (inOverlay.origin.x < -0.5f) {
        inOverlay.origin.x = 0;
    }
    if (inOverlay.origin.y < 0) {
        FU_LOG("previewBox clamp y=%.1f -> 0", inOverlay.origin.y);
        inOverlay.origin.y = 0;
    }
    if (oh > 1.f && inOverlay.size.height > oh) {
        inOverlay.size.height = oh;
    }
    // 运行中偶发 convertRect 把 y 突然变小（上移进状态栏）或变大（顶部黑边挤 UI）→ 拒绝，沿用上次稳定框
    // 注意：勿强制 y=0，正常态 convertRect 的 y（状态栏偏移）是头部 icon 的正确位置
    if (sHasStableOverlayBox &&
        fabs(inOverlay.size.width - sLastStableOverlayBox.size.width) < 4.f &&
        fabs(inOverlay.size.height - sLastStableOverlayBox.size.height) < 80.f) {
        CGFloat dy = inOverlay.origin.y - sLastStableOverlayBox.origin.y;
        if (dy < -12.f || dy > 12.f) {
            FU_LOG("previewBox reject y jump %.1f -> %.1f (keep %.1f)",
                   sLastStableOverlayBox.origin.y, inOverlay.origin.y, sLastStableOverlayBox.origin.y);
            inOverlay.origin.y = sLastStableOverlayBox.origin.y;
            inOverlay.origin.x = sLastStableOverlayBox.origin.x;
        }
    }
    if (inOverlay.size.width > 1.f && inOverlay.size.height > 1.f) {
        sLastStableOverlayBox = inOverlay;
        sHasStableOverlayBox = YES;
    }
    return inOverlay;
}

/** 业务主窗口（跳过相机专用 overlay 窗，避免把自身当业务窗） */
- (UIWindow *)resolveBusinessWindow {
    UIWindow *window = nil;
    if (@available(iOS 13.0, *)) {
        for (UIScene *scene in UIApplication.sharedApplication.connectedScenes) {
            if (scene.activationState != UISceneActivationStateForegroundActive) {
                continue;
            }
            if (![scene isKindOfClass:[UIWindowScene class]]) {
                continue;
            }
            for (UIWindow *candidate in ((UIWindowScene *)scene).windows) {
                if (candidate == sOverlayWindow) {
                    continue;
                }
                //  不再按StatusBar+50 跳过：UniApp 业务窗可能被抬高，跳过会导致叠层仍压在下面
                if (candidate.isKeyWindow) {
                    window = candidate;
                    break;
                }
                if (!window && !candidate.hidden) {
                    window = candidate;
                }
            }
            if (window) {
                break;
            }
        }
    }
    if (!window) {
        window = UIApplication.sharedApplication.keyWindow;
        if (window == sOverlayWindow) {
            window = nil;
        }
    }
    return window;
}

- (UIWindow *)resolveOverlayWindow {
    return [self ensureOverlayWindow];
}

/**
 * 对齐 Android：相机叠在业务窗之上的独立层（类似setZOrderOnTop 的Surface）。 * 窗level 必须高于真实业务窗，否则黑底 WebView 会把预览挡住；又须低于Alert。 * WKWebView 兄弟层方案会把不透明 GLKView 盖住整页 Web UI →全黑。 */
- (UIView *)resolveOverlayParentView {
    UIWindow *win = [self ensureOverlayWindow];
    return win.rootViewController.view ?: win;
}

/** 业务窗实际level +1，避免Normal+1 仍被 UniApp 抬高层盖住*/
- (CGFloat)preferredOverlayWindowLevel {
    UIWindow *biz = [self resolveBusinessWindow];
    CGFloat bizLevel = biz ? biz.windowLevel : UIWindowLevelNormal;
    CGFloat level = bizLevel + 1.f;
    if (level < UIWindowLevelNormal + 1.f) {
        level = UIWindowLevelNormal + 1.f;
    }
    CGFloat maxLevel = UIWindowLevelAlert - 1.f;
    if (level > maxLevel) {
        level = maxLevel;
    }
    return level;
}

- (void)syncOverlayWindowLevel {
    if (!sOverlayWindow) {
        return;
    }
    CGFloat want = [self preferredOverlayWindowLevel];
    if (fabs(sOverlayWindow.windowLevel - want) > 0.01f) {
        FU_LOG("sync overlay windowLevel %.1f -> %.1f", (double)sOverlayWindow.windowLevel, (double)want);
        sOverlayWindow.windowLevel = want;
    }
}

- (void)dismissDedicatedOverlayWindowIfNeeded {
    // 只拆 Alert 及以上异常窗；动态biz+1 可能高于 StatusBar，属正常
    if (sOverlayWindow && sOverlayWindow.windowLevel >= UIWindowLevelAlert) {
        FU_LOG("dismiss invalid overlay window level=%.0f", (double)sOverlayWindow.windowLevel);
        sOverlayWindow.hidden = YES;
        sOverlayWindow.rootViewController = nil;
        sOverlayWindow = nil;
    }
}

- (UIWindow *)ensureOverlayWindow {
    [self dismissDedicatedOverlayWindowIfNeeded];
    if (sOverlayWindow) {
        if (sOverlayWindow.windowLevel < UIWindowLevelNormal + 0.5f ||
            sOverlayWindow.windowLevel >= UIWindowLevelAlert) {
            sOverlayWindow.hidden = YES;
            sOverlayWindow.rootViewController = nil;
            sOverlayWindow = nil;
        } else {
            [self syncOverlayWindowLevel];
        }
    }
    if (!sOverlayWindow) {
        UIWindow *biz = [self resolveBusinessWindow];
        FuOverlayWindow *win = nil;
        if (@available(iOS 13.0, *)) {
            UIWindowScene *scene = biz.windowScene;
            if (scene) {
                win = [[FuOverlayWindow alloc] initWithWindowScene:scene];
            }
        }
        if (!win) {
            win = [[FuOverlayWindow alloc] initWithFrame:UIScreen.mainScreen.bounds];
        }
        // 高于业务窗、低于Alert；hitTest 穿透；绝不 makeKey
        win.windowLevel = [self preferredOverlayWindowLevel];
        win.backgroundColor = [UIColor clearColor];
        win.opaque = NO;
        win.userInteractionEnabled = YES;
        UIViewController *vc = [[UIViewController alloc] init];
        vc.view.backgroundColor = [UIColor clearColor];
        vc.view.opaque = NO;
        vc.view.userInteractionEnabled = YES;
        win.rootViewController = vc;
        sOverlayWindow = win;
        FU_LOG("create FuOverlayWindow level=%.1f bizLevel=%.1f (above web)",
               (double)sOverlayWindow.windowLevel,
               biz ? (double)biz.windowLevel : -1.0);
    }
    if (sOverlayWindow.hidden) {
        sOverlayWindow.hidden = NO;
    }
    return sOverlayWindow;
}

- (UIView *)resolveOverlayRootView {
    return [self resolveOverlayParentView];
}

- (UIView *)resolveOverlayDecorView {
    return [self resolveOverlayParentView];
}

- (UIView *)resolveOverlayRoot {
    return [self resolveOverlayParentView];
}

- (void)attachOverlayHostOnDecor:(UIView *)decor host:(UIView *)host {
    (void)decor;
    UIView *parent = [self resolveOverlayParentView];
    if (!parent || !host) {
        return;
    }
    if (host.superview && host.superview != parent) {
        [host removeFromSuperview];
    }
    host.frame = parent.bounds;
    host.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    //  FuPassThroughHost：滑杆可点，其余穿透到下层业务窗
    host.userInteractionEnabled = YES;
    if (host.superview != parent) {
        [parent addSubview:host];
    } else {
        [parent bringSubviewToFront:host];
    }
    if (sOverlayWindow) {
        sOverlayWindow.hidden = NO;
    }
    FU_LOG("attachOverlay parent=%@ hostFrame=%@ winLevel=%.1f",
           NSStringFromClass([parent class]),
           NSStringFromCGRect(host.frame),
           sOverlayWindow ? (double)sOverlayWindow.windowLevel : -1.0);
}

- (void)attachOverlayHost:(UIView *)overlayRoot host:(UIView *)host {
    [self attachOverlayHostOnDecor:overlayRoot host:host];
}

- (void)bringOverlayToFront {
    if (!sOverlayCameraHost) {
        return;
    }
    // showCamera 后0.3~2.5s 会延迟重顶；若用户已 soft-hide 离页，绝不能再unhide，否则初次跳转露冻帧
    if (sOverlayCameraView && [sOverlayCameraView isSoftHidden]) {
        return;
    }
    [self syncOverlayWindowLevel];
    [self attachOverlayHostOnDecor:nil host:sOverlayCameraHost];
    sOverlayCameraHost.hidden = NO;
    sOverlayCameraHost.alpha = 1;
    sOverlayCameraHost.userInteractionEnabled = YES;
    if (sOverlayWindow) {
        sOverlayWindow.hidden = NO;
    }
    if (sOverlayCameraView) {
        sOverlayCameraView.hidden = NO;
        [sOverlayCameraHost setNeedsLayout];
        [sOverlayCameraView display];
    }
    if (sPreviewChromeView && sLastCssW > 0 && sLastCssH > 0) {
        [self syncPreviewChromeLayoutX:sLastCssX y:sLastCssY width:sLastCssW height:sLastCssH];
    }
}

- (void)presentOverlayToast:(NSString *)title durationMs:(NSTimeInterval)durationMs {
    UIView *root = [self resolveOverlayParentView] ?: [self resolveBusinessWindow];
    if (!root) {
        return;
    }
    // toast 挂在业务窗/ WebView 兄弟层上
    static const NSInteger kToastTag = 88215601;
    UIView *old = [root viewWithTag:kToastTag];
    [old removeFromSuperview];

    UILabel *label = [[UILabel alloc] init];
    label.tag = kToastTag;
    label.text = title;
    label.textColor = [UIColor whiteColor];
    label.font = [UIFont systemFontOfSize:15 weight:UIFontWeightMedium];
    label.textAlignment = NSTextAlignmentCenter;
    label.numberOfLines = 0;
    label.backgroundColor = [[UIColor blackColor] colorWithAlphaComponent:0.78];
    label.layer.cornerRadius = 10.f;
    label.clipsToBounds = YES;
    CGFloat maxW = MIN(CGRectGetWidth(root.bounds) - 48.f, 280.f);
    CGSize size = [label sizeThatFits:CGSizeMake(maxW, 120.f)];
    CGFloat padX = 18.f;
    CGFloat padY = 12.f;
    CGFloat w = MIN(maxW, size.width + padX * 2);
    CGFloat h = size.height + padY * 2;
    label.frame = CGRectMake((CGRectGetWidth(root.bounds) - w) * 0.5f,
                             CGRectGetHeight(root.bounds) * 0.72f,
                             w,
                             h);
    label.alpha = 0;
    [root addSubview:label];
    [root bringSubviewToFront:label];
    [UIView animateWithDuration:0.18 animations:^{
        label.alpha = 1;
    } completion:^(__unused BOOL finished) {
        NSTimeInterval hold = MAX(0.6, durationMs / 1000.0);
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(hold * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [UIView animateWithDuration:0.22 animations:^{
                label.alpha = 0;
            } completion:^(__unused BOOL done) {
                [label removeFromSuperview];
            }];
        });
    }];
}

- (void)fireVideoEvent:(NSString *)action extra:(NSDictionary *)extra {
    NSMutableDictionary *payload = [@{ @"action": action ?: @"" } mutableCopy];
    if ([extra isKindOfClass:[NSDictionary class]]) {
        [payload addEntriesFromDictionary:extra];
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        [self.uniInstance fireGlobalEvent:@"namaVideo" params:payload];
    });
}

- (void)onOverlayVideoPlay:(UIButton *)sender {
    (void)sender;
    if (!sOverlayVideoView) {
        return;
    }
    if (sOverlayVideoPlayBtn) {
        sOverlayVideoPlayBtn.hidden = YES;
    }
    [sOverlayVideoView play];
    [self fireVideoEvent:@"playing" extra:nil];
}

- (void)refreshPausedVideoBeautyIfNeeded {
    if ([VideoBeautyExporter isExportActive]) {
        return;
    }
    if (!sOverlayVideoView || [sOverlayVideoView isPlaying]) {
        return;
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        [sOverlayVideoView redrawBeautyFrame];
    });
}

- (void)showExportProgressHudRatio:(float)ratio {
    int pct = (int)lroundf(MAX(0.f, MIN(1.f, ratio)) * 100.f);
    dispatch_async(dispatch_get_main_queue(), ^{
        CGRect screenBounds = UIScreen.mainScreen.bounds;
        if (!sExportProgressWindow) {
            UIWindow *win = nil;
            if (@available(iOS 13.0, *)) {
                UIWindow *biz = [self resolveBusinessWindow];
                if (biz.windowScene) {
                    win = [[UIWindow alloc] initWithWindowScene:biz.windowScene];
                    win.frame = screenBounds;
                }
            }
            if (!win) {
                win = [[UIWindow alloc] initWithFrame:screenBounds];
            }
            win.windowLevel = UIWindowLevelAlert + 2.f;
            win.backgroundColor = [UIColor clearColor];
            UIViewController *vc = [[UIViewController alloc] init];
            vc.view.backgroundColor = [UIColor clearColor];
            win.rootViewController = vc;
            sExportProgressWindow = win;
        }
        UIView *root = sExportProgressWindow.rootViewController.view;
        if (!sExportProgressHud) {
            sExportProgressHud = [[UIView alloc] initWithFrame:root.bounds];
            sExportProgressHud.tag = 88219902;
            sExportProgressHud.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
            sExportProgressHud.backgroundColor = [[UIColor blackColor] colorWithAlphaComponent:0.6];
            sExportProgressHud.userInteractionEnabled = YES;

            FuExportProgressRingView *ring = [[FuExportProgressRingView alloc] initWithFrame:CGRectMake(0, 0, 120, 120)];
            ring.tag = 21;
            ring.center = CGPointMake(CGRectGetMidX(sExportProgressHud.bounds), CGRectGetMidY(sExportProgressHud.bounds) - 24);
            ring.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin | UIViewAutoresizingFlexibleRightMargin |
                UIViewAutoresizingFlexibleTopMargin | UIViewAutoresizingFlexibleBottomMargin;
            [sExportProgressHud addSubview:ring];
            sExportProgressRing = ring;

            UILabel *pctLab = [[UILabel alloc] initWithFrame:CGRectMake(0, 0, 120, 120)];
            pctLab.tag = 22;
            pctLab.center = ring.center;
            pctLab.autoresizingMask = ring.autoresizingMask;
            pctLab.textColor = [UIColor whiteColor];
            pctLab.font = [UIFont systemFontOfSize:18 weight:UIFontWeightSemibold];
            pctLab.textAlignment = NSTextAlignmentCenter;
            [sExportProgressHud addSubview:pctLab];
            sExportProgressPercentLabel = pctLab;

            UILabel *tip = [[UILabel alloc] initWithFrame:CGRectMake(0, 0, 200, 22)];
            tip.tag = 23;
            tip.center = CGPointMake(CGRectGetMidX(sExportProgressHud.bounds), CGRectGetMaxY(ring.frame) + 20);
            tip.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin | UIViewAutoresizingFlexibleRightMargin |
                UIViewAutoresizingFlexibleTopMargin | UIViewAutoresizingFlexibleBottomMargin;
            tip.textColor = [UIColor whiteColor];
            tip.font = [UIFont systemFontOfSize:14 weight:UIFontWeightMedium];
            tip.textAlignment = NSTextAlignmentCenter;
            tip.text = @"导出中";
            [sExportProgressHud addSubview:tip];
            sExportProgressTipLabel = tip;

            UIButton *cancel = [UIButton buttonWithType:UIButtonTypeCustom];
            cancel.tag = 24;
            cancel.frame = CGRectMake(0, 0, 120, 40);
            cancel.center = CGPointMake(CGRectGetMidX(sExportProgressHud.bounds), CGRectGetMaxY(tip.frame) + 28);
            cancel.autoresizingMask = tip.autoresizingMask;
            [cancel setTitle:@"取消" forState:UIControlStateNormal];
            [cancel setTitleColor:[[UIColor whiteColor] colorWithAlphaComponent:0.9] forState:UIControlStateNormal];
            cancel.titleLabel.font = [UIFont systemFontOfSize:15 weight:UIFontWeightMedium];
            cancel.layer.cornerRadius = 8;
            cancel.layer.borderWidth = 1;
            cancel.layer.borderColor = [[UIColor whiteColor] colorWithAlphaComponent:0.35].CGColor;
            [cancel addTarget:sNamaModuleWeak action:@selector(onExportCancelTapped) forControlEvents:UIControlEventTouchUpInside];
            [sExportProgressHud addSubview:cancel];
            sExportProgressCancelBtn = cancel;

            [root addSubview:sExportProgressHud];
        }
        sExportProgressHud.frame = root.bounds;
        sExportProgressRing.progress = ratio;
        sExportProgressPercentLabel.text = [NSString stringWithFormat:@"%d%%", pct];
        sExportProgressTipLabel.text = @"导出中";
        sExportProgressWindow.hidden = NO;
        [sExportProgressWindow makeKeyAndVisible];
    });
}

- (void)showExportProgressHud:(NSString *)text {
    float ratio = 0.f;
    if (text.length) {
        NSScanner *scanner = [NSScanner scannerWithString:text];
        int pct = 0;
        if ([scanner scanString:@"导出中" intoString:NULL] && [scanner scanInt:&pct]) {
            ratio = (float)pct / 100.f;
        }
    }
    [self showExportProgressHudRatio:ratio];
}

- (void)onExportCancelTapped {
    sExportCancelled = YES;
    [VideoBeautyExporter cancelActiveExport];
}

- (void)hideExportProgressHud {
    dispatch_async(dispatch_get_main_queue(), ^{
        [sExportProgressHud removeFromSuperview];
        sExportProgressHud = nil;
        sExportProgressPercentLabel = nil;
        sExportProgressTipLabel = nil;
        sExportProgressRing = nil;
        sExportProgressCancelBtn = nil;
        sExportCancelled = NO;
        if (sExportProgressWindow) {
            sExportProgressWindow.hidden = YES;
            sExportProgressWindow.rootViewController = nil;
            sExportProgressWindow = nil;
        }
        UIWindow *biz = [self resolveBusinessWindow];
        if (biz && !biz.hidden) {
            [biz makeKeyWindow];
        }
        if (sBeautyPanelView) {
            sBeautyPanelView.userInteractionEnabled = YES;
        }
        if (sOverlayWindow) {
            sOverlayWindow.userInteractionEnabled = YES;
        }
    });
}

- (void)showPreviewCenterTip:(NSString *)title {
    if (title.length == 0) {
        return;
    }
    // 滤镜名必须盖在原生美颜面板(z~30100)之上；PreviewChrome 在面板下层，走此处 overlay
    dispatch_async(dispatch_get_main_queue(), ^{
        UIView *root = sOverlayVideoHost ?: sOverlayCameraHost ?: [self resolveOverlayParentView] ?: [self resolveBusinessWindow];
        if (!root) {
            return;
        }
        static const NSInteger kTipTag = 88215602;
        UIView *old = [root viewWithTag:kTipTag];
        [old.layer removeAllAnimations];
        [old removeFromSuperview];

        UILabel *label = [[UILabel alloc] init];
        label.tag = kTipTag;
        label.text = title;
        label.textColor = [UIColor whiteColor];
        label.font = [UIFont systemFontOfSize:32 weight:UIFontWeightSemibold];
        label.textAlignment = NSTextAlignmentCenter;
        label.backgroundColor = [UIColor clearColor];
        label.layer.shadowColor = [UIColor blackColor].CGColor;
        label.layer.shadowOpacity = 0.55;
        label.layer.shadowRadius = 4;
        label.layer.shadowOffset = CGSizeMake(0, 1);
        label.layer.zPosition = 50000.f;
        label.numberOfLines = 1;
        CGFloat maxW = MIN(CGRectGetWidth(root.bounds) - 48.f, 320.f);
        CGSize size = [label sizeThatFits:CGSizeMake(maxW, 60.f)];
        CGFloat w = MIN(maxW, MAX(120.f, size.width + 28.f));
        CGFloat h = MAX(40.f, size.height + 12.f);
        CGFloat panelH = sLastBeautyPanelHeight > 0 ? sLastBeautyPanelHeight : 80.f;
        CGFloat tipCenterY = (CGRectGetHeight(root.bounds) - panelH) * 0.42f;
        label.frame = CGRectMake((CGRectGetWidth(root.bounds) - w) * 0.5f, tipCenterY - h * 0.5f, w, h);
        label.alpha = 0;
        [root addSubview:label];
        [root bringSubviewToFront:label];
        [UIView animateWithDuration:0.18 animations:^{
            label.alpha = 1;
        } completion:^(__unused BOOL finished) {
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1.0 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
                [UIView animateWithDuration:0.22 animations:^{
                    label.alpha = 0;
                } completion:^(__unused BOOL done) {
                    [label removeFromSuperview];
                }];
            });
        }];
    });
}

- (void)releaseCameraKeepAliveInternal:(void (^)(void))onComplete {
    if (!sOverlayCameraView) {
        if (onComplete) {
            onComplete();
        }
        return;
    }
    [sOverlayCameraView releaseCameraKeepAlive:^{
        if (sOverlayCameraHost) {
            sOverlayCameraHost.hidden = YES;
        }
        if (onComplete) {
            onComplete();
        }
    }];
}

- (void)softHideCameraOverlay:(void (^)(void))onComplete {
    sOverlayBringGen += 1;
    [self dismissPreviewChrome];
    // 保留 sLastStableOverlayBox：回来时用稳定框拒绝错误 y 跳变（顶部黑边），勿清掉
    if (sOverlayCameraView) {
        [sOverlayCameraView hidePreview];
    }
    if (sOverlayCameraHost) {
        sOverlayCameraHost.hidden = YES;
        sOverlayCameraHost.alpha = 0;
    }
    //  无视频时整窗藏掉，避免导入选择页仍透出相机冻帧；有视频 host 则只藏相机
    if (!sOverlayVideoHost && sOverlayWindow) {
        sOverlayWindow.hidden = YES;
    }
    if (onComplete) {
        onComplete();
    }
}

- (void)hideCameraInternal:(void (^)(void))onComplete {
    [self dismissPreviewChrome];
    if (!sOverlayCameraView) {
        sCameraHostedByComponent = NO;
        [self resetOverlayLayoutCache];
        if (onComplete) {
            onComplete();
        }
        return;
    }

    // 组件托管：只销毁预览，不removeFromSuperview
    if (sCameraHostedByComponent && !sOverlayCameraHost) {
        BeautyCameraView *view = sOverlayCameraView;
        // 立刻回调，避免导入跳转卡在黑屏等teardown
        [view destroyPreviewAsync:nil];
        [self resetOverlayLayoutCache];
        if (onComplete) {
            onComplete();
        }
        return;
    }

    BeautyCameraView *view = sOverlayCameraView;
    UIView *host = sOverlayCameraHost;
    sOverlayCameraView = nil;
    sOverlayCameraHost = nil;
    sCameraHostedByComponent = NO;

    //  先从层级摘掉，立刻露出下层页面
    [view removeFromSuperview];
    [host removeFromSuperview];
    if (!sOverlayVideoHost && sOverlayWindow) {
        sOverlayWindow.hidden = YES;
    }
    [self resetOverlayLayoutCache];
    if (onComplete) {
        onComplete();
    }
    //  GL 后台拆，不阻塞跳转
    [view destroyPreviewAsync:nil];
}

- (void)resetOverlayLayoutCache {
    sLastCssX = -1;
    sLastCssY = -1;
    sLastCssW = -1;
    sLastCssH = -1;
    sHasStableOverlayBox = NO;
    sLastStableOverlayBox = CGRectZero;
}

- (void)ensureInitialized {
    if (fuIsLibraryInit() == 0) {
        @throw [NSException exceptionWithName:@"IllegalState" reason:@"请先 init" userInfo:nil];
    }
}

- (UIViewController *)resolveHostViewController {
    if (self.uniInstance) {
        UIViewController *vc = self.uniInstance.viewController;
        if (vc && vc.view) {
            return vc;
        }
        @try {
            id root = [self.uniInstance valueForKey:@"rootViewController"];
            if ([root isKindOfClass:[UIViewController class]]) {
                vc = (UIViewController *)root;
                if (vc.view) {
                    return vc;
                }
            }
        } @catch (__unused NSException *exception) {
        }
    }

    UIWindow *window = nil;
    if (@available(iOS 13.0, *)) {
        for (UIScene *scene in UIApplication.sharedApplication.connectedScenes) {
            if (scene.activationState != UISceneActivationStateForegroundActive) {
                continue;
            }
            if (![scene isKindOfClass:[UIWindowScene class]]) {
                continue;
            }
            for (UIWindow *candidate in ((UIWindowScene *)scene).windows) {
                if (candidate.isKeyWindow) {
                    window = candidate;
                    break;
                }
            }
            if (window) {
                break;
            }
        }
    }
    if (!window) {
        window = UIApplication.sharedApplication.keyWindow;
    }
    UIViewController *vc = window.rootViewController;
    while (vc.presentedViewController) {
        vc = vc.presentedViewController;
    }
    return vc;
}

- (NSData *)readAuthData:(NSDictionary *)options {
    if (![options isKindOfClass:[NSDictionary class]]) {
        options = @{};
    }
    NSString *authBase64 = options[@"authBase64"];
    if (authBase64.length > 0) {
        return [[NSData alloc] initWithBase64EncodedString:authBase64 options:0];
    }
    NSString *authPath = options[@"authPath"];
    if (authPath.length > 0) {
        return [self readFileData:authPath];
    }
    return [NSData dataWithBytes:g_auth_package length:sizeof(g_auth_package)];
}

- (NSData *)readFileData:(NSString *)path {
    if (path.length == 0) {
        @throw [NSException exceptionWithName:@"InvalidPath" reason:@"path 不能为空" userInfo:nil];
    }
    NSString *realPath = path;
    if ([path hasPrefix:@"file://"]) {
        realPath = [path substringFromIndex:7];
    }
    NSData *data = [NSData dataWithContentsOfFile:realPath];
    if (data == nil) {
        @throw [NSException exceptionWithName:@"FileNotFound" reason:[NSString stringWithFormat:@"文件不存在 %@", realPath] userInfo:nil];
    }
    return data;
}

@end
