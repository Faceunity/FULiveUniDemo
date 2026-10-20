#import "NamaModule.h"
#import "DCUniDefine.h"
#import "authpack.h"
#import "FURenderKitCameraView.h"
#import <FURenderKit/FURenderKit.h>
#import <FURenderKit/FUAIKit.h>
#import <FURenderKit/FUGLDisplayView.h>
#import <FURenderKit/FUImageHelper.h>
#import <FURenderKit/FUBeauty.h>
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
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <OpenGLES/EAGL.h>
#import <Photos/Photos.h>
#import <MobileCoreServices/MobileCoreServices.h>
#import <PhotosUI/PhotosUI.h>
#import <stdbool.h>

#define FU_LOG(fmt, ...) do { } while (0)
/** PreviewChrome 拍摄/对比按钮 tag，overlay hitTest 放行 */
static const NSInteger kFuChromeInteractiveTag = 88219901;

int FuBeautyCameraHandle = 0;
int FuBeautyMediaHandle = 0;
int FuBeautyItemHandle = 0;

static int FuBeautyHandleForPipeline(BOOL media) {
    return media ? FuBeautyMediaHandle : FuBeautyCameraHandle;
}

/** 设备性能分级缓存（对齐 FULiveDemo：Low…4 Excellent；皮肤美白仅 Excellent） */
static int FuDevicePerformanceLevelCached(void) {
    static int sLevel = 0;
    static BOOL sHasValue = NO;
    if (sHasValue) {
        return sLevel;
    }
    int level = 4;
    @try {
        FUDevicePerformanceLevel perf = [FURenderKit devicePerformanceLevel];
        switch (perf) {
            case FUDevicePerformanceLevelExcellent:
                level = 4;
                break;
            case FUDevicePerformanceLevelVeryHigh:
                level = 3;
                break;
            case FUDevicePerformanceLevelHigh:
                level = 2;
                break;
            default:
                level = 1;
                break;
        }
    } @catch (__unused NSException *e) {
        level = 4;
    }
    sLevel = level;
    sHasValue = YES;
    return sLevel;
}

/** 对齐 Android MediaFuSetup.isBeautyFeatureSupported / Demo canUseFunction */
static BOOL FuBeautyParamAllowedOnDevice(NSString *key) {
    if (key.length == 0) {
        return YES;
    }
    int level = FuDevicePerformanceLevelCached();
    if ([key isEqualToString:@"body_blur_level"]) {
        return level >= 4;
    }
    if ([key isEqualToString:@"delspot_level"] || [key isEqualToString:@"facial_plump"]) {
        return level >= 3;
    }
    if ([key isEqualToString:@"enable_skinseg"]) {
        return level >= 4;
    }
    return YES;
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

static FURenderKitCameraView *sOverlayCameraView = nil;
static UIView *sOverlayCameraHost = nil;
static BOOL sCameraHostedByComponent = NO;
static BeautyVideoView *sOverlayVideoView = nil;
static UIView *sOverlayVideoHost = nil;
static UIButton *sOverlayVideoPlayBtn = nil;
static UIWindow *sExportProgressWindow = nil;
static UIView *sExportProgressHud = nil;
static UILabel *sExportProgressLabel = nil;
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
/** 取消 mountVideoOverlay 延迟 bringVideoOverlayToFront；返回 import 时递增 */
static int sVideoOverlayBringGen = 0;
/** 用户已离开视频页：禁止 bringVideoOverlayToFront / ensureOverlayWindow 再露出叠层 */
static BOOL sVideoOverlaySuppressed = NO;
static int sLastCssX = -1;
static int sLastCssY = -1;
static int sLastCssW = -1;
static int sLastCssH = -1;
/** 稳定取景框：拒绝 convertRect 偶发把整层往上甩 */
static CGRect sLastStableOverlayBox;
static BOOL sHasStableOverlayBox = NO;

@implementation NamaModule (HostedCamera)

+ (void)attachHostedCameraView:(FURenderKitCameraView *)view {
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

+ (void)detachHostedCameraView:(FURenderKitCameraView *)view {
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

@implementation NamaModule {
    int _beautyHandle;
    int _frameId;
    UniModuleKeepAliveCallback _pickMediaCallback;
}

//  让NamaModule 能收 UIImagePicker/PHPicker 回调（对齐FULiveDemo 系统相册白底+取消）
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

- (void)picker:(PHPickerViewController *)picker didFinishPicking:(NSArray<PHPickerResult *> *)results {
    [picker dismissViewControllerAnimated:YES completion:nil];
    UniModuleKeepAliveCallback cb = _pickMediaCallback;
    _pickMediaCallback = nil;
    if (!cb) {
        return;
    }
    PHPickerResult *result = results.firstObject;
    if (![result isKindOfClass:[PHPickerResult class]]) {
        cb(@{ @"code": @(-1), @"message": @"用户取消选择" }, NO);
        return;
    }
    NSItemProvider *provider = result.itemProvider;
    if (!provider) {
        cb(@{ @"code": @(-1), @"message": @"未选择媒体" }, NO);
        return;
    }
    if ([provider hasItemConformingToTypeIdentifier:(NSString *)kUTTypeImage]) {
        if ([provider canLoadObjectOfClass:[UIImage class]]) {
            // 图片：异步取 UIImage，回主线程 JPEG 存临时目录
            __weak typeof(self) weakSelf = self;
            [provider loadObjectOfClass:[UIImage class] completionHandler:^(id<NSItemProviderReading> object, NSError *error) {
                __strong typeof(weakSelf) self = weakSelf;
                if (!self || !cb) {
                    return;
                }
                if (error || !object) {
                    dispatch_async(dispatch_get_main_queue(), ^{
                        cb(@{ @"code": @(-1), @"message": error ? (error.localizedDescription ?: @"加载图片失败") : @"未选择图片" }, NO);
                    });
                    return;
                }
                UIImage *image = (UIImage *)object;
                dispatch_async(dispatch_get_main_queue(), ^{
                    NSString *path = [NSTemporaryDirectory() stringByAppendingPathComponent:
                                      [NSString stringWithFormat:@"nama_pick_%@.jpg", @((long long)(NSDate.date.timeIntervalSince1970 * 1000))]];
                    NSData *data = UIImageJPEGRepresentation(image, 0.95);
                    if (!data || ![data writeToFile:path atomically:YES]) {
                        cb(@{ @"code": @(-1), @"message": @"保存图片失败" }, NO);
                        return;
                    }
                    cb(@{ @"code": @(0), @"data": @{ @"path": path, @"type": @"image" } }, NO);
                });
            }];
        } else {
            // 兜底：按 kUTTypeImage 读文件（iCloud 资源可能走此处）
            [provider loadItemForTypeIdentifier:(NSString *)kUTTypeImage options:nil completionHandler:^(id<NSSecureCoding> item, NSError *error) {
                dispatch_async(dispatch_get_main_queue(), ^{
                    if (error || !item) {
                        cb(@{ @"code": @(-1), @"message": error ? (error.localizedDescription ?: @"加载图片失败") : @"未选择图片" }, NO);
                        return;
                    }
                    if ([(id)item isKindOfClass:[UIImage class]]) {
                        NSString *path = [NSTemporaryDirectory() stringByAppendingPathComponent:
                                          [NSString stringWithFormat:@"nama_pick_%@.jpg", @((long long)(NSDate.date.timeIntervalSince1970 * 1000))]];
                        NSData *data = UIImageJPEGRepresentation((UIImage *)item, 0.95);
                        if (!data || ![data writeToFile:path atomically:YES]) {
                            cb(@{ @"code": @(-1), @"message": @"保存图片失败" }, NO);
                            return;
                        }
                        cb(@{ @"code": @(0), @"data": @{ @"path": path, @"type": @"image" } }, NO);
                        return;
                    }
                    NSURL *imageURL = [(id)item isKindOfClass:[NSURL class]] ? (NSURL *)item : nil;
                    if (!imageURL.isFileURL) {
                        imageURL = nil;
                    }
                    NSString *src = imageURL.path;
                    if (src.length == 0) {
                        cb(@{ @"code": @(-1), @"message": @"未选择图片" }, NO);
                        return;
                    }
                    UIImage *image = [UIImage imageWithContentsOfFile:src];
                    if (!image) {
                        cb(@{ @"code": @(-1), @"message": @"无法解码图片" }, NO);
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
                });
            }];
        }
        return;
    }
    if ([provider hasItemConformingToTypeIdentifier:(NSString *)kUTTypeMovie]) {
        // 视频：loadFileRepresentation 等文件完全 materialize 后再回调；并在 completion 作用域内
        // 同步拷贝。loadItemForTypeIdentifier 对 iCloud/待转码资源返回的 URL 可能仍是 0 字节占位，
        // 异步主线程拷贝会得到空文件 → AVURLAsset 无视频轨 → 预览黑屏且无错误提示。
        [provider loadFileRepresentationForTypeIdentifier:(NSString *)kUTTypeMovie completionHandler:^(NSURL *fileURL, NSError *error) {
            BOOL urlOk = [fileURL isKindOfClass:[NSURL class]] && fileURL.isFileURL;
            if (error || !urlOk) {
                dispatch_async(dispatch_get_main_queue(), ^{
                    cb(@{ @"code": @(-1), @"message": error ? (error.localizedDescription ?: @"加载视频失败") : @"未选择视频" }, NO);
                });
                return;
            }
            NSString *ext = fileURL.pathExtension.length > 0 ? fileURL.pathExtension : @"mp4";
            NSString *dest = [NSTemporaryDirectory() stringByAppendingPathComponent:
                              [NSString stringWithFormat:@"nama_pick_%@.%@", @((long long)(NSDate.date.timeIntervalSince1970 * 1000)), ext]];
            NSError *copyErr = nil;
            [[NSFileManager defaultManager] removeItemAtPath:dest error:nil];
            BOOL copied = [[NSFileManager defaultManager] copyItemAtURL:fileURL toURL:[NSURL fileURLWithPath:dest] error:&copyErr];
            NSDictionary *attrs = copied ? [[NSFileManager defaultManager] attributesOfItemAtPath:dest error:nil] : nil;
            long long size = [(NSNumber *)attrs[NSFileSize] longLongValue];
            dispatch_async(dispatch_get_main_queue(), ^{
                if (!copied || size <= 0) {
                    cb(@{ @"code": @(-1), @"message": @"视频读取失败（文件为空），请重试" }, NO);
                    return;
                }
                cb(@{ @"code": @(0), @"data": @{ @"path": dest, @"type": @"video" } }, NO);
            });
        }];
        return;
    }
    cb(@{ @"code": @(-1), @"message": @"未选择媒体" }, NO);
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
 * 对齐 FULiveDemo FUMediaPickerViewController：
 * - iOS 14+ 用 PHPickerViewController（异步加载、打开更快、无需相册权限）
 * - iOS 13 及以下回退 UIImagePickerController + SavedPhotosAlbum →系统白底相册，左上角「取消」
 */
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

            if (@available(iOS 14, *)) {
                // PHPicker：异步加载、打开更快，iOS 14+ 无需相册权限
                PHPickerConfiguration *config = [[PHPickerConfiguration alloc] init];
                config.selectionLimit = 1;
                config.preferredAssetRepresentationMode = PHPickerConfigurationAssetRepresentationModeCompatible;
                if (wantVideo) {
                    config.filter = [PHPickerFilter videosFilter];
                } else {
                    config.filter = [PHPickerFilter imagesFilter];
                }
                PHPickerViewController *picker = [[PHPickerViewController alloc] initWithConfiguration:config];
                picker.delegate = (id<PHPickerViewControllerDelegate>)self;
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
                return;
            }

            // iOS 13 及以下回退 UIImagePickerController
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

        // PHPicker 在 iOS 14+ 无需相册权限；权限检查仅为兼容 iOS 13 及以下
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
    __weak typeof(self) weakSelf = self;
    dispatch_async(dispatch_get_main_queue(), ^{
        BOOL started = NO;
        NSString *errMsg = nil;
        if (sOverlayCameraView) {
            NSError *err = nil;
            started = [sOverlayCameraView startVideoRecord:&err];
            if (!started) {
                errMsg = err.localizedDescription ?: @"开始录制失败";
                [weakSelf presentOverlayToast:errMsg durationMs:1500];
            } else {
                if (sPreviewChromeView) {
                    [sPreviewChromeView syncRecordProgressToNow];
                }
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
            }
        } else {
            errMsg = @"相机未挂载";
        }
        [weakSelf firePreviewChromeEvent:@"captureLongPress" extra:@{
            @"ok": @(started),
            @"message": errMsg ?: @"",
        }];
    });
}
- (void)previewChromeCaptureTouchUp:(BOOL)wasLongPress {
    if (wasLongPress && sOverlayCameraView) {
        __weak typeof(self) weakSelf = self;
        [sOverlayCameraView stopVideoRecord:^(NSString *path, NSError *error) {
            dispatch_async(dispatch_get_main_queue(), ^{
                if (sPreviewChromeView) {
                    [sPreviewChromeView setRecording:NO];
                }
                __strong typeof(weakSelf) self = weakSelf;
                if (!self) {
                    return;
                }
                [self firePreviewChromeEvent:@"recordStopped" extra:@{
                    @"path": path ?: @"",
                    @"ok": @(error == nil && path.length > 0),
                    @"message": error.localizedDescription ?: @"",
                }];
            });
        }];
    }
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
    FU_LOG("previewChromeSelectResolution id=%@", resolutionId ?: @"");
    if ([resolutionId isKindOfClass:[NSString class]] && resolutionId.length > 0) {
        __weak typeof(self) weakSelf = self;
        [FURenderKitCameraView applyPreviewResolutionId:resolutionId
                                   unsupportedHandler:^{
            [weakSelf presentOverlayToast:@"设备不支持该分辨率" durationMs:1500];
        }
                                           completion:nil];
    }
    [self firePreviewChromeEvent:@"resolution" extra:@{ @"id": resolutionId ?: @"" }];
}

- (NSDictionary *)previewResolutionDimensionsForId:(NSString *)resolutionId {
    if (![resolutionId isKindOfClass:[NSString class]] || resolutionId.length == 0) {
        return nil;
    }
    if ([resolutionId isEqualToString:@"480"]) {
        return @{ @"width": @(640), @"height": @(480) };
    }
    if ([resolutionId isEqualToString:@"720"]) {
        return @{ @"width": @(1280), @"height": @(720) };
    }
    if ([resolutionId isEqualToString:@"1080"]) {
        return @{ @"width": @(1920), @"height": @(1080) };
    }
    return nil;
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

- (void)applyBeautyPanelBottomInset:(CGFloat)panelHeight {
    // 面板高度整段抬升拍摄钮；PreviewChrome 再留 panelGap，避免被 Tab 盖住
    sLastBeautyPanelHeight = MAX(0, panelHeight);
    CGFloat inset = sLastBeautyPanelHeight;
    if (sPreviewChromeView) {
        [sPreviewChromeView setBottomChromeInset:inset animated:YES];
        [sPreviewChromeView setCompareButtonHidden:YES];
    }
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
        [sBeautyPanelView applyConfig:cfg];
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
    [self applyBeautyPanelBottomInset:heightPts];
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
        if (!FuBeautyParamAllowedOnDevice(sdkKey)) {
            return;
        }
        BOOL special = [sdkKey isEqualToString:@"body_blur_level"] ||
            [sdkKey isEqualToString:@"delspot_level"] ||
            [sdkKey isEqualToString:@"facial_plump"] ||
            [sdkKey isEqualToString:@"intensity_eye_pupil"] ||
            [sdkKey isEqualToString:@"enable_skinseg"];
        if (special) {
            // 对齐 Android applySpecialAlgoParam：单 key 入队，flush 时走 special 写参
            [FURenderKitCameraView enqueueBeautyParam:handle name:sdkKey value:value];
            if ([sdkKey isEqualToString:@"body_blur_level"]) {
                FuResetBeautyBlurCache();
            }
        } else {
            // 与 special 一样进渲染队列，避免主线程 set 后渲染线程读到旧值
            [FURenderKitCameraView enqueueBeautyParam:handle name:sdkKey value:value];
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
    // 图片页勿触发相机 GL 重绘，会与后台 processImage 并发 renderWithInput
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
        [FURenderKitCameraView enqueueBeautyParam:handle name:@"change_frames" value:0.0];
        [FURenderKitCameraView enqueueBeautyParam:handle name:@"is_beauty_on" value:1.0];
        if (isShape) {
            [FURenderKitCameraView enqueueBeautyParam:handle name:@"face_shape" value:4.0];
            [FURenderKitCameraView enqueueBeautyParam:handle name:@"face_shape_level" value:1.0];
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
        [FURenderKitCameraView enqueueBeautyParam:handle name:@"enable_skinseg" value:0.0];
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
            [FURenderKitCameraView enqueueBeautyParam:h name:@"change_frames" value:12.0];
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
    double level = 0.4;
    if (sBeautyPanelView) {
        level = [sBeautyPanelView peekFilterSdkIntensity:filterKey.length ? filterKey : filterId];
    }
    if ([filterKey isEqualToString:@"origin"] || [filterId isEqualToString:@"origin"]) {
        level = 0;
    }
    if (handle > 0 && filterKey.length > 0) {
        // 滤镜名走渲染队列 KVC 映射到 FUBeauty.filterName
        [FURenderKitCameraView enqueueBeautyParamString:handle name:@"filter_name" value:filterKey];
        [FURenderKitCameraView enqueueBeautyParam:handle name:@"filter_level" value:level];
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
    if (v > 0.5 && !FuBeautyParamAllowedOnDevice(@"enable_skinseg")) {
        return;
    }
    if (handle > 0) {
        // 勿在主线程 performWithSharedGLLock，恢复默认时会卡死 UI
        [FURenderKitCameraView enqueueBeautyParam:handle name:@"enable_skinseg" value:v];
    }
    [self refreshPausedVideoBeautyIfNeeded];
    [self fireBeautyPanelEvent:@"whiteningMode" extra:@{ @"mode": mode ?: @"global" }];
}
- (void)beautyPanelCompareStart {
    [FURenderKitCameraView setBeautyEnabledGlobal:NO];
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
    [FURenderKitCameraView setBeautyEnabledGlobal:YES];
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

#pragma mark - SDK

UNI_EXPORT_METHOD(@selector(getVersion:))
- (void)getVersion:(UniModuleKeepAliveCallback)callback {
    callback(@{ @"code": @(0), @"data": [FURenderKit getVersion] ?: @"" }, NO);
}

UNI_EXPORT_METHOD(@selector(init:callback:))
- (void)init:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    static BOOL sSetupSucceeded = NO;
    NSData *authData = [self readAuthData:options];
    if (authData == nil || authData.length == 0) {
        callback(@{ @"code": @(-1), @"message": @"authData 不能为空" }, NO);
        return;
    }

    @try {
        // FURenderKit 是全局单例，setup 只能成功一次；重复调用会返回 NO。
        // 首次成功后记录标记，后续 init（如鉴权验证重复点击）直接返回成功。
        if (sSetupSucceeded) {
            callback(@{ @"code": @(0), @"data": @{ @"reused": @(1), @"version": [FURenderKit getVersion] ?: @"" } }, NO);
            return;
        }
        FUSetupConfig *config = [[FUSetupConfig alloc] init];
        config.authPack = FUAuthPackMake((char *)authData.bytes, (int)authData.length);
        // controller_cpp.bundle 已随插件内置，setup 阶段必须提供
        NSString *controllerPath = [self furKitBundlePathForResource:@"controller_cpp" ofType:@"bundle"];
        if (controllerPath.length > 0) {
            config.controllerPath = controllerPath;
        }
        [FURenderKit setLogLevel:FU_LOG_LEVEL_ERROR];
        CFAbsoluteTime t0 = CFAbsoluteTimeGetCurrent();
        BOOL ok = [FURenderKit setupWithSetupConfig:config];
        CFAbsoluteTime t1 = CFAbsoluteTimeGetCurrent();
        // 对齐官方 FURenderKitManager：设置缓存目录，提升模型/资源加载速度
        if (ok) {
            NSArray *paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES);
            NSString *docPath = paths.count > 0 ? paths[0] : nil;
            if (docPath.length > 0) {
                [FURenderKit setCacheDirectory:docPath];
            }
        }
        NSString *version = [FURenderKit getVersion];
        int sysErr = [FURenderKit getSystemError];
        NSString *sysErrStr = [FURenderKit getSystemErrorString];

        NSMutableDictionary *diag = [@{
            @"version": version ?: @"",
            @"authSize": @(authData.length),
            @"controllerPath": controllerPath ?: @"",
            @"fuSetupCode": @(ok ? 1 : 0),
            @"fuIsLibraryInit": @(ok ? 1 : 0),
            @"furKitSetup": @(ok ? 1 : 0),
            @"sysError": @(sysErr),
            @"sysErrorString": sysErrStr ?: @"",
        } mutableCopy];

        // 文件日志：写沙盒 Documents/nama_diag.log，便于离线打包导出诊断
        [self appendDiagFile:[NSString stringWithFormat:
            @"init ok=%d version=%@ authSize=%lu controllerPath=%@ sysError=%d %@ setupMs=%.0f",
            ok ? 1 : 0, version ?: @"?", (unsigned long)authData.length,
            controllerPath ?: @"<空>", sysErr, sysErrStr ?: @"", (t1 - t0) * 1000]];

        if (!ok) {
            callback(@{
                @"code": @(-1),
                @"message": [NSString stringWithFormat:@"FURenderKit setup 失败 (sysError=%d %@)", sysErr, sysErrStr ?: @""],
                @"data": diag
            }, NO);
            return;
        }
        sSetupSucceeded = YES;
        callback(@{ @"code": @(0), @"data": diag }, NO);
    } @catch (NSException *exception) {
        callback(@{ @"code": @(-1), @"message": exception.reason ?: @"init 异常" }, NO);
    }
}

UNI_EXPORT_METHOD(@selector(loadAIModel:callback:))
- (void)loadAIModel:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    @try {
        [self ensureInitialized];
        // iOS 改用插件内置 bundle，不再依赖 JS 传 path（OSS 下载）
        NSString *aiPath = [self furKitBundlePathForResource:@"ai_face_processor" ofType:@"bundle"];
        if (aiPath.length == 0) {
            callback(@{ @"code": @(-1), @"message": @"内置 ai_face_processor.bundle 缺失" }, NO);
            return;
        }
        FUDevicePerformanceLevel perf = [FURenderKit devicePerformanceLevel];
        // 按机型性能分级开算法子模块（对齐官方 FURenderKitManager.loadFaceAIModel）
        FUFaceAlgorithmConfig config = FUFaceAlgorithmConfigEnableAll;
        if (perf < FUDevicePerformanceLevelHigh) {
            config = FUFaceAlgorithmConfigDisableAll;
        } else if (perf < FUDevicePerformanceLevelVeryHigh) {
            config = FUFaceAlgorithmConfigDisableSkinSegAndDelSpot |
                FUFaceAlgorithmConfigDisableARMeshV2 |
                FUFaceAlgorithmConfigDisableRACE;
        } else if (perf < FUDevicePerformanceLevelExcellent) {
            config = FUFaceAlgorithmConfigDisableSkinSeg;
        }
        [FUAIKit setFaceAlgorithmConfig:config];
        CFAbsoluteTime t0 = CFAbsoluteTimeGetCurrent();
        [FUAIKit loadAIModeWithAIType:FUAITYPE_FACEPROCESSOR dataPath:aiPath];
        CFAbsoluteTime t1 = CFAbsoluteTimeGetCurrent();
        // 对齐官方 FURenderKitManager.setDevicePerformanceDetails：点位质量/小脸检测/最大脸数
        FUAIKit *ai = [FUAIKit shareKit];
        ai.faceProcessorFaceLandmarkQuality = perf >= FUDevicePerformanceLevelHigh
            ? FUFaceProcessorFaceLandmarkQualityHigh : FUFaceProcessorFaceLandmarkQualityMedium;
        ai.faceProcessorSetFaceLandmarkHpOccu = NO;
        ai.faceProcessorDetectSmallFace = perf >= FUDevicePerformanceLevelHigh;
        ai.maxTrackFaces = perf >= FUDevicePerformanceLevelHigh ? 4 : 2;
        BOOL faceLoaded = [FUAIKit shareKit].trackedFacesCount >= 0;
        NSString *version = [FURenderKit getVersion];
        [self appendDiagFile:[NSString stringWithFormat:
            @"loadAIModel faceLoaded=%d loadMs=%.0f aiPath=%@",
            faceLoaded ? 1 : 0, (t1 - t0) * 1000, aiPath ?: @""]];
        callback(@{
            @"code": @(0),
            @"data": @{
                @"handle": @(1),
                @"faceLoaded": @(faceLoaded),
                @"aiPath": aiPath ?: @"",
                @"perfLevel": @(perf),
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
        NSString *pipeline = options[@"pipeline"];
        BOOL media = [pipeline isKindOfClass:[NSString class]] &&
            [pipeline caseInsensitiveCompare:@"media"] == NSOrderedSame;
        // iOS 只用一套 FUBeauty（挂在 FURenderKit 单例），camera/media 共用同一对象。
        // Android Kit 用独立 handle；iOS 这里统一返回非零 handle 即可满足 JS 的 parseNativeHandle。
        NSString *beautyPath = [self furKitBundlePathForResource:@"face_beautification" ofType:@"bundle"];
        if (beautyPath.length == 0) {
            callback(@{ @"code": @(-1), @"message": @"内置 face_beautification.bundle 缺失" }, NO);
            return;
        }
        CFAbsoluteTime t0 = CFAbsoluteTimeGetCurrent();
        FUBeauty *beauty = [[FUBeauty alloc] initWithPath:beautyPath name:@"FUBeauty"];
        CFAbsoluteTime t1 = CFAbsoluteTimeGetCurrent();
        if (!beauty) {
            callback(@{ @"code": @(-1), @"message": @"FUBeauty 创建失败" }, NO);
            return;
        }
        [self appendDiagFile:[NSString stringWithFormat:
            @"loadBundle createMs=%.0f pipeline=%@", (t1 - t0) * 1000, media ? @"media" : @"camera"]];
        // 对齐官方 FUStyleListViewModel：美型/美肤参数需先设置 mode 才生效（否则写属性无效）
        FUDevicePerformanceLevel perfLevel = [FURenderKit devicePerformanceLevel];
        if (perfLevel >= FUDevicePerformanceLevelHigh) {
            [beauty addPropertyMode:FUBeautyPropertyMode2 forKey:FUModeKeyRemovePouchStrength];
            [beauty addPropertyMode:FUBeautyPropertyMode2 forKey:FUModeKeyRemoveNasolabialFoldsStrength];
            [beauty addPropertyMode:FUBeautyPropertyMode3 forKey:FUModeKeyEyeEnlarging];
            [beauty addPropertyMode:FUBeautyPropertyMode3 forKey:FUModeKeyIntensityMouth];
        }
        // 美白推荐用 Mode2（官方文档 v8.2.0+），JS 侧传 color_level_mode2
        [beauty addPropertyMode:FUBeautyPropertyMode2 forKey:FUModeKeyColorLevel];
        [FURenderKit shareRenderKit].beauty = beauty;
        // 强度默认对齐 beauty-effects.ts（勿写 3.3/0.5，否则常驻闪点）
        beauty.skinDetect = 0;
        beauty.blurLevel = 3.3;
        beauty.colorLevel = 0.4;
        beauty.redLevel = 0.3;
        beauty.faceShape = 4;
        beauty.faceShapeLevel = 1.0;
        beauty.blurType = 3;
        beauty.blurUseMask = YES;
        beauty.enableWarpAntiAlias = YES;
        beauty.enable = YES;
        // 非零 handle 占位，满足 JS parseNativeHandle > 0 判断
        int handle = 1;
        FuBeautyCameraHandle = handle;
        if (media) {
            FuBeautyMediaHandle = handle;
        } else {
            FuBeautyItemHandle = handle;
        }
        _beautyHandle = handle;
        if (!media) {
            [FURenderKitCameraView setBeautyEnabledGlobal:YES];
        }
        FU_LOG("loadBundle pipeline=%@ beauty created handle=%d", media ? @"media" : @"camera", handle);
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
        NSString *stringValue = options[@"stringValue"];
        NSString *resolvedKey = FuResolveBeautyParamKey(key);
        if (!FuBeautyParamAllowedOnDevice(resolvedKey)) {
            callback(@{ @"code": @(0), @"data": @{ @"ret": @(-1) } }, NO);
            return;
        }
        [self appendDiagFile:[NSString stringWithFormat:
            @"setParam key=%@ %@ handle=%d", key ?: @"?", stringValue.length > 0 ? [NSString stringWithFormat:@"str=%@", stringValue] : [NSString stringWithFormat:@"v=%.3f", [options[@"value"] doubleValue]], handle]];
        NSMutableDictionary *data = [@{ @"ret": @(0) } mutableCopy];
        if (stringValue.length > 0) {
            [FURenderKitCameraView enqueueBeautyParamString:handle name:key value:stringValue];
        } else {
            double value = [options[@"value"] doubleValue];
            // 统一走渲染队列 + KVC 映射到 FUBeauty 属性（对齐 Android runOnNamaGl 写参）
            [FURenderKitCameraView enqueueBeautyParam:handle name:key value:value];
        }
        data[@"ret"] = @(0);
        callback(@{ @"code": @(0), @"data": data }, NO);
    } @catch (NSException *exception) {
        callback(@{ @"code": @(-1), @"message": exception.reason ?: @"setParam 异常" }, NO);
    }
}

UNI_EXPORT_METHOD(@selector(bulkSetParams:callback:))
- (void)bulkSetParams:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    @try {
        [self ensureInitialized];
        NSArray *items = options[@"items"];
        if (![items isKindOfClass:[NSArray class]] || items.count == 0) {
            callback(@{ @"code": @(0), @"data": @{ @"applied": @(0) } }, NO);
            return;
        }
        int handle = [options[@"handle"] intValue];
        if (handle <= 0) {
            handle = _beautyHandle;
        }
        for (NSDictionary *item in items) {
            if (![item isKindOfClass:[NSDictionary class]]) {
                continue;
            }
            NSString *key = item[@"key"];
            if (key.length == 0) {
                continue;
            }
            NSString *stringValue = item[@"stringValue"];
            if (stringValue.length > 0) {
                [FURenderKitCameraView enqueueBeautyParamString:handle name:key value:stringValue];
            } else {
                [FURenderKitCameraView enqueueBeautyParam:handle name:key value:[item[@"value"] doubleValue]];
            }
        }
        // 只入队不主动 flush：写参统一由渲染线程每帧应用（对齐单条 setParam），
        // 避免在桥接线程直接改 FUBeauty 属性与 GL 线程竞争。
        callback(@{ @"code": @(0), @"data": @{ @"applied": @(items.count) } }, NO);
    } @catch (NSException *exception) {
        callback(@{ @"code": @(-1), @"message": exception.reason ?: @"bulkSetParams 异常" }, NO);
    }
}

UNI_EXPORT_METHOD(@selector(drainSdkLog:))
- (void)drainSdkLog:(UniModuleKeepAliveCallback)callback {
    if (callback) {
        callback(@{ @"code": @(0), @"data": @"" }, NO);
    }
}

/** 前端阶段标记：记录 JS 关键步骤时间戳到 diag，定位初始化慢点 */
UNI_EXPORT_METHOD(@selector(mark:callback:))
- (void)mark:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    NSString *step = [options isKindOfClass:[NSDictionary class]] ? options[@"step"] : nil;
    [self appendDiagFile:[NSString stringWithFormat:@"MARK %@", step ?: @"?"]];
    if (callback) {
        callback(@{ @"code": @(0), @"data": @(0) }, NO);
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

    if (textureId <= 0 || width <= 0 || height <= 0) {
        callback(@{ @"code": @(-1), @"message": @"textureId / width / height 无效" }, NO);
        return;
    }

    // JS 层未调用本方法；官方 FURenderKit 已接管相机/图片/视频渲染，旧的 fuRender 纹理外链已废弃。
    // 保留签名，直接返回「不支持」，避免残留旧 C API 造成未定义符号。
    callback(@{ @"code": @(-1), @"message": @"onFrame 已废弃，请使用官方 FURenderKit 渲染链路" }, NO);
}

#pragma mark - Camera overlay (对齐 Android NamaModule)

UNI_EXPORT_METHOD(@selector(showCamera:callback:))
- (void)showCamera:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [self appendDiagFile:[NSString stringWithFormat:
        @"showCamera entered x=%@ y=%@ w=%@ h=%@", options[@"x"], options[@"y"], options[@"width"], options[@"height"]]];
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
                        @"cameraError": [FURenderKitCameraView lastError] ?: @"",
                        @"diag": [FURenderKitCameraView previewDiag] ?: @"",
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
                        @"cameraError": [FURenderKitCameraView lastError] ?: @"",
                        @"diag": [FURenderKitCameraView previewDiag] ?: @"",
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

    FURenderKitCameraView *view = [[FURenderKitCameraView alloc] initWithFrame:previewBox.bounds];
    view.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    view.layer.zPosition = 10001.f;
    view.clipsToBounds = YES;
    view.userInteractionEnabled = NO;
    [previewBox addSubview:view];
    [view bindLayoutSize:pxW height:pxH];
    [FURenderKitCameraView resetSessionDiag];
    [view setNeedsLayout];
    [view layoutIfNeeded];
    // 相机由 didMoveToWindow 延迟到 view 稳定后启动（避免上窗瞬间 startInternalCamera 阻塞主线程约 9s）
    [view startPreviewIfWindowStable];

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
            @"cameraError": [FURenderKitCameraView lastError] ?: @"",
            @"diag": [FURenderKitCameraView previewDiag] ?: @"",
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
               [FURenderKitCameraView previewDiag],
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

- (void)setVideoOverlaySuppressed:(BOOL)suppressed {
    if (suppressed) {
        sVideoOverlayBringGen += 1;
        sVideoOverlaySuppressed = YES;
        if (sOverlayVideoHost) {
            sOverlayVideoHost.hidden = YES;
        }
        if (sOverlayWindow) {
            sOverlayWindow.hidden = YES;
        }
        [self hideMediaBackButton];
    } else {
        sVideoOverlaySuppressed = NO;
    }
}

- (void)showVideoOverlayIfNeeded {
    sVideoOverlaySuppressed = NO;
    if (sOverlayWindow) {
        sOverlayWindow.hidden = NO;
    }
    if (sOverlayVideoHost) {
        sOverlayVideoHost.hidden = NO;
    }
    // previewBox 在换源 reload 时会先 hidden，等 onFirstFrame 再显示，避免闪旧帧
}

UNI_EXPORT_METHOD(@selector(setOverlayWindowsHidden:callback:))
- (void)setOverlayWindowsHidden:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    dispatch_async(dispatch_get_main_queue(), ^{
        BOOL hidden = [options[@"hidden"] boolValue];
        //  只藏相机/视频专用窗，绝不隐藏业务主窗口（系统 Alert 依赖主窗）
        if (hidden) {
            [self dismissPreviewChrome];
            if (sOverlayCameraView) {
                [sOverlayCameraView hidePreview];
            }
            if (sOverlayCameraHost) {
                sOverlayCameraHost.hidden = YES;
            }
            if (sOverlayVideoHost) {
                [self setVideoOverlaySuppressed:YES];
            } else if (sOverlayWindow) {
                sOverlayWindow.hidden = YES;
            }
        } else {
            [self setVideoOverlaySuppressed:NO];
            if (sOverlayWindow) {
                sOverlayWindow.hidden = NO;
            }
            // 只恢复视频叠层；相机必须由resumeCameraPreview / showCamera 显式恢复
            if (sOverlayVideoHost) {
                [self showVideoOverlayIfNeeded];
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
            @"diag": [FURenderKitCameraView previewDiag] ?: @"",
            @"mounted": @(sOverlayCameraView != nil),
            @"stats": [FURenderKitCameraView previewStats] ?: @{},
        }
    }, NO);
}

UNI_EXPORT_METHOD(@selector(getPreviewStats:))
- (void)getPreviewStats:(UniModuleKeepAliveCallback)callback {
    NSDictionary *stats = [FURenderKitCameraView previewStats] ?: @{};
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
        [FURenderKitCameraView setBeautyEnabledGlobal:enabled];
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
                    NSString *msg = error.localizedDescription ?: @"切换摄像头失败";
                    if ([msg isEqualToString:@"设备不支持该分辨率"]) {
                        [self presentOverlayToast:msg durationMs:1500];
                    }
                    callback(@{ @"code": @(-1), @"message": msg }, NO);
                } else {
                    callback(@{ @"code": @(0), @"data": @(0) }, NO);
                }
            }
        }];
    });
}

UNI_EXPORT_METHOD(@selector(setPreviewResolution:callback:))
- (void)setPreviewResolution:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    int width = [options[@"width"] intValue];
    int height = [options[@"height"] intValue];
    FU_LOG("setPreviewResolution width=%d height=%d", width, height);
    if (width <= 0 || height <= 0) {
        if (callback) {
            callback(@{ @"code": @(-1), @"message": @"width/height 无效" }, NO);
        }
        return;
    }
    __weak typeof(self) weakSelf = self;
    [FURenderKitCameraView applyPreviewResolutionWidth:width
                                                height:height
                                    unsupportedHandler:^{
        [weakSelf presentOverlayToast:@"设备不支持该分辨率" durationMs:1500];
    }
                                            completion:^(__unused BOOL applied) {
        if (callback) {
            callback(@{ @"code": @(0), @"data": @(0) }, NO);
        }
    }];
}

UNI_EXPORT_METHOD(@selector(resetPreviewResolution:))
- (void)resetPreviewResolution:(UniModuleKeepAliveCallback)callback {
    [FURenderKitCameraView resetTargetPreviewSizeToDefault];
    if (sOverlayCameraView && [sOverlayCameraView isPreviewStarted]) {
        [FURenderKitCameraView applyTargetPreviewSizeToCamera];
    }
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
            // 非预乘BGRA：FU 常把 alpha 写成 0，预乘路径会导出成白图
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

            NSError *glErr = nil;
            if (![FURenderKitCameraView ensureSharedGLContextReady:&glErr]) {
                free(bgra);
                callback(@{
                    @"code": @(-1),
                    @"message": glErr.localizedDescription ?: @"GL 上下文未就绪"
                }, NO);
                return;
            }
            // 官方 FURenderKit 渲染：renderWithInput 会自动应用已挂载的 [FURenderKit shareRenderKit].beauty，
            // 无需再传 item handle。onlyOutputTexture=NO 时结果读回输入 imageBuffer。
            __block BOOL renderOK = NO;
            [FURenderKitCameraView performWithSharedGLLock:^{
                [FURenderKitCameraView flushPendingBeautyParams];
                FURenderInput *input = [[FURenderInput alloc] init];
                input.imageBuffer = FUImageBufferMakeBGRA(bgra, width, height, bytesPerRow);
                input.renderConfig.imageOrientation = FUImageOrientationUP;
                input.renderConfig.autoDisplay = NO;
                input.renderConfig.onlyOutputTexture = NO;
                FURenderOutput *output = [[FURenderKit shareRenderKit] renderWithInput:input];
                renderOK = (output != nil);
            }];
            if (!renderOK) {
                free(bgra);
                NSString *errStr = [FURenderKit getSystemErrorString] ?: @"";
                int sysErr = [FURenderKit getSystemError];
                callback(@{
                    @"code": @(-1),
                    @"message": [NSString stringWithFormat:@"renderWithInput 失败 sys=%d %@", sysErr, errStr ?: @""]
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

            NSString *normPath = [self normalizeLocalPath:path];

            //  同路径已挂载：只改预览框，避免面板展开时整段重载
            if (sOverlayVideoView && sOverlayVideoHost && [sLastVideoPath isEqualToString:normPath]) {
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

            //  换源但 overlay 仍在（返回 import 再选视频）：原位 loadVideoPath，避免 destroy→remount
            if (sOverlayVideoView && sOverlayVideoHost) {
                [self reloadVideoOverlayInPlace:normPath options:opts callback:callback];
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

- (void)wireOverlayVideoCallbacks:(BeautyVideoView *)view {
    __weak typeof(self) weakSelf = self;
    view.onFirstFrame = ^{
        if (view.superview) {
            view.superview.hidden = NO;
        }
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
}

- (void)reloadVideoOverlayInPlace:(NSString *)normPath
                          options:(NSDictionary *)options
                         callback:(UniModuleKeepAliveCallback)callback {
    int x = [options[@"x"] intValue];
    int y = [options[@"y"] intValue];
    int width = [options[@"width"] intValue];
    int height = [options[@"height"] intValue];
    int pxW = [self cssToPhysical:width];
    int pxH = [self cssToPhysical:height];
    CGRect boxFrame = [self previewBoxFrameOnDecor:x y:y width:width height:height];
    UIView *previewBox = sOverlayVideoView.superview;
    if (previewBox) {
        previewBox.hidden = YES;
        previewBox.frame = boxFrame;
        sOverlayVideoView.frame = previewBox.bounds;
    }
    [sOverlayVideoView bindLayoutSize:pxW height:pxH];
    [self wireOverlayVideoCallbacks:sOverlayVideoView];
    if (sOverlayVideoPlayBtn) {
        sOverlayVideoPlayBtn.hidden = YES;
    }
    NSError *loadErr = nil;
    if (![sOverlayVideoView loadVideoPath:normPath error:&loadErr]) {
        callback(@{ @"code": @(-1), @"message": loadErr.localizedDescription ?: @"加载视频失败" }, NO);
        return;
    }
    [sOverlayVideoView prepareFirstFrame];
    sLastVideoPath = [normPath copy];
    sLastCssX = x;
    sLastCssY = y;
    sLastCssW = width;
    sLastCssH = height;
    sVideoOverlayBringGen += 1;
    if (!sVideoOverlaySuppressed) {
        [self showVideoOverlayIfNeeded];
        [self bringVideoOverlayToFront];
    }
    if (sBeautyPanelView) {
        NSString *mode = sBeautyPanelView.mode ?: @"video";
        UIView *panelParent = [self resolveBeautyPanelParentForMode:mode];
        if (panelParent && sBeautyPanelView.superview != panelParent) {
            [sBeautyPanelView removeFromSuperview];
            [panelParent addSubview:sBeautyPanelView];
        }
        [self syncBeautyPanelLayout];
    }
    FU_LOG("showVideoPreview reloaded in place path=%@", normPath);
    callback(@{
        @"code": @(0),
        @"data": @{
            @"x": @(x),
            @"y": @(y),
            @"width": @(width),
            @"height": @(height),
            @"reloaded": @(YES),
        }
    }, NO);
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
        FU_LOG("mountVideoOverlay reuse cameraHandle=%d as media", FuBeautyCameraHandle);
    }

    BeautyVideoView *view = [[BeautyVideoView alloc] initWithFrame:previewBox.bounds];
    view.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    view.clipsToBounds = YES;
    [previewBox addSubview:view];
    [view bindLayoutSize:pxW height:pxH];

    UIButton *playBtn = [UIButton buttonWithType:UIButtonTypeCustom];
    playBtn.tag = kFuChromeInteractiveTag;
    // 垂直水平都居中：标准视频播放器的中央播放钮
    CGFloat playSize = 85;
    CGFloat playY = (CGRectGetHeight(previewBox.bounds) - playSize) * 0.5;
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

    [self wireOverlayVideoCallbacks:view];
    // 默认暂停 + 首帧；不循环播放
    [view prepareFirstFrame];

    sOverlayVideoView = view;
    sOverlayVideoHost = host;
    sLastVideoPath = [[self normalizeLocalPath:path] copy];
    sLastCssX = x;
    sLastCssY = y;
    sLastCssW = width;
    sLastCssH = height;
    sVideoOverlayBringGen += 1;
    NSInteger bringGen = sVideoOverlayBringGen;
    if (!sVideoOverlaySuppressed) {
        [self showVideoOverlayIfNeeded];
        if (sOverlayWindow) {
            sOverlayWindow.hidden = NO;
        }
        [self bringVideoOverlayToFront];
    }
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
            if (bringGen != sVideoOverlayBringGen || sVideoOverlaySuppressed) {
                return;
            }
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
        // 导出前暂停预览，避免争用 GL / 双 FUVideoReader 读同一文件（对齐官方 stopReading → startProcessing）
        [self showExportProgressHud:@"导出中 0%"];
        NSString *realPath = [self normalizeLocalPath:path];
        __weak typeof(self) weakSelf = self;
        BeautyVideoView *previewView = sOverlayVideoView;
        void (^startExport)(void) = ^{
            [FURenderKitCameraView performWithSharedGLLock:^{
                [FURenderKitCameraView flushPendingBeautyParams];
            }];
            [VideoBeautyExporter exportVideoAtPath:realPath
                                      beautyHandle:handle
                                    maxDurationSec:60
                                           maxSide:1280
                                          progress:^(float p) {
                int pct = (int)lroundf(MAX(0.f, MIN(1.f, p)) * 100.f);
                [weakSelf showExportProgressHud:[NSString stringWithFormat:@"导出中 %d%%", pct]];
            }
                                        completion:^(NSString *outPath, NSError *error) {
                [weakSelf hideExportProgressHud];
                dispatch_async(dispatch_get_main_queue(), ^{
                    // 导出前 pausePreviewForExport 隐藏了视频宿主容器，这里必须放回
                    // （对齐 resumeVideoPreview 的恢复路径），否则导出结束页面黑屏
                    if (sOverlayVideoHost) {
                        sOverlayVideoHost.hidden = NO;
                    }
                    if (sOverlayVideoView) {
                        [sOverlayVideoView resumeAfterExport];
                    }
                    if (sOverlayVideoPlayBtn) {
                        sOverlayVideoPlayBtn.hidden = NO;
                    }
                    if (sBeautyPanelView) {
                        sBeautyPanelView.userInteractionEnabled = YES;
                    }
                });
                if (error || outPath.length == 0) {
                    callback(@{
                        @"code": @(-1),
                        @"message": error.localizedDescription ?: @"视频导出失败"
                    }, NO);
                    return;
                }
                callback(@{ @"code": @(0), @"data": @{ @"path": outPath } }, NO);
            }];
        };
        void (^pausePreviewForExport)(void) = ^{
            // 对齐官方 FULiveDemo：导出时保留末帧在 GL 视图，不隐藏宿主容器，
            // 由 HUD 半透明黑罩压暗画面，而不是黑屏
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
        if (previewView) {
            [previewView suspendForExportWithCompletion:^{
                startExport();
            }];
        } else {
            startExport();
        }
    } @catch (NSException *exception) {
        [self hideExportProgressHud];
        callback(@{ @"code": @(-1), @"message": exception.reason ?: @"processVideo 异常" }, NO);
    }
}

- (void)bringVideoOverlayToFront {
    if (sVideoOverlaySuppressed || !sOverlayVideoHost) {
        return;
    }
    [self syncOverlayWindowLevel];
    [self attachOverlayHostOnDecor:nil host:sOverlayVideoHost];
    sOverlayVideoHost.hidden = NO;
    if (sOverlayWindow && !sVideoOverlaySuppressed) {
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
    // 先隐藏再销毁，避免销毁过程中闪现最后一帧
    host.hidden = YES;
    [view pause];
    sVideoOverlayBringGen += 1;
    sVideoOverlaySuppressed = YES;
    [view stopAndReleaseWhenIdle:^{
        [view removeFromSuperview];
        [host removeFromSuperview];
        if (onComplete) {
            onComplete();
        }
    }];
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
            [FURenderKitCameraView shutdownSharedGLContext];
            [FURenderKit destroy];
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
        @"cameraError": [FURenderKitCameraView lastError] ?: @"",
        @"diag": [FURenderKitCameraView previewDiag] ?: @"",
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
    if (sOverlayWindow.hidden && !sVideoOverlaySuppressed) {
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
    if (sOverlayWindow && !sVideoOverlaySuppressed) {
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
    if (sOverlayWindow && !sVideoOverlaySuppressed) {
        sOverlayWindow.hidden = NO;
    }
    if (sOverlayCameraView) {
        sOverlayCameraView.hidden = NO;
        [sOverlayCameraHost setNeedsLayout];
        [sOverlayCameraView setNeedsDisplay];
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

- (void)showExportProgressHud:(NSString *)text {
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
            // UIWindow 默认 opaque=YES：不透明 window 即使 clearColor 也会盖住下层
            // overlay window，末帧透不出来导致黑屏；必须显式置 NO 才能透出压暗末帧
            win.opaque = NO;
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
            sExportProgressHud.backgroundColor = [[UIColor blackColor] colorWithAlphaComponent:0.45];
            sExportProgressHud.userInteractionEnabled = YES;
            UIView *card = [[UIView alloc] initWithFrame:CGRectMake(0, 0, 168, 96)];
            card.tag = 11;
            card.backgroundColor = [[UIColor colorWithWhite:0.12 alpha:1] colorWithAlphaComponent:0.96];
            card.layer.cornerRadius = 12;
            card.center = CGPointMake(CGRectGetMidX(sExportProgressHud.bounds), CGRectGetMidY(sExportProgressHud.bounds));
            card.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin | UIViewAutoresizingFlexibleRightMargin |
                UIViewAutoresizingFlexibleTopMargin | UIViewAutoresizingFlexibleBottomMargin;
            [sExportProgressHud addSubview:card];
            UIActivityIndicatorView *spin = nil;
            if (@available(iOS 13.0, *)) {
                spin = [[UIActivityIndicatorView alloc] initWithActivityIndicatorStyle:UIActivityIndicatorViewStyleLarge];
            } else {
                spin = [[UIActivityIndicatorView alloc] initWithActivityIndicatorStyle:UIActivityIndicatorViewStyleWhiteLarge];
            }
            spin.color = [UIColor whiteColor];
            spin.center = CGPointMake(84, 36);
            [spin startAnimating];
            [card addSubview:spin];
            sExportProgressLabel = [[UILabel alloc] initWithFrame:CGRectMake(8, 62, 152, 24)];
            sExportProgressLabel.textColor = [UIColor whiteColor];
            sExportProgressLabel.font = [UIFont systemFontOfSize:13 weight:UIFontWeightMedium];
            sExportProgressLabel.textAlignment = NSTextAlignmentCenter;
            [card addSubview:sExportProgressLabel];
            [root addSubview:sExportProgressHud];
        }
        sExportProgressHud.frame = root.bounds;
        sExportProgressLabel.text = text.length ? text : @"导出中";
        sExportProgressWindow.hidden = NO;
        [sExportProgressWindow makeKeyAndVisible];
    });
}

- (void)hideExportProgressHud {
    dispatch_async(dispatch_get_main_queue(), ^{
        [sExportProgressHud removeFromSuperview];
        sExportProgressHud = nil;
        sExportProgressLabel = nil;
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
    if (sPreviewChromeView && !sPreviewChromeView.hidden) {
        [sPreviewChromeView showFilterName:title];
        return;
    }
    // 媒体页无 PreviewChrome：在取景区中部弹出（对齐相机 tip 高度，避开底栏）
    UIView *root = sOverlayVideoHost ?: [self resolveOverlayParentView] ?: [self resolveBusinessWindow];
    if (!root) {
        return;
    }
    static const NSInteger kTipTag = 88215602;
    UIView *old = [root viewWithTag:kTipTag];
    [old removeFromSuperview];

    UILabel *label = [[UILabel alloc] init];
    label.tag = kTipTag;
    label.text = title;
    label.textColor = [UIColor whiteColor];
    label.font = [UIFont systemFontOfSize:18 weight:UIFontWeightSemibold];
    label.textAlignment = NSTextAlignmentCenter;
    label.backgroundColor = [UIColor clearColor];
    label.layer.shadowColor = [UIColor blackColor].CGColor;
    label.layer.shadowOpacity = 0.55;
    label.layer.shadowRadius = 4;
    label.layer.shadowOffset = CGSizeMake(0, 1);
    label.layer.zPosition = 50000.f; // 高于美颜面板 30100，避免滤镜名被挡住
    label.numberOfLines = 1;
    CGFloat maxW = MIN(CGRectGetWidth(root.bounds) - 48.f, 280.f);
    CGSize size = [label sizeThatFits:CGSizeMake(maxW, 40.f)];
    CGFloat w = MIN(maxW, MAX(96, size.width + 8));
    CGFloat h = MAX(28, size.height);
    CGFloat panelH = sLastBeautyPanelHeight > 0 ? sLastBeautyPanelHeight : 80;
    CGFloat tipCenterY = (CGRectGetHeight(root.bounds) - panelH) * 0.42;
    label.frame = CGRectMake((CGRectGetWidth(root.bounds) - w) * 0.5f, tipCenterY - h * 0.5f, w, h);
    label.alpha = 0;
    [root addSubview:label];
    [root bringSubviewToFront:label];
    [UIView animateWithDuration:0.18 animations:^{
        label.alpha = 1;
    } completion:^(__unused BOOL finished) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1.2 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [UIView animateWithDuration:0.22 animations:^{
                label.alpha = 0;
            } completion:^(__unused BOOL done) {
                [label removeFromSuperview];
            }];
        });
    }];
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
        FURenderKitCameraView *view = sOverlayCameraView;
        // 立刻回调，避免导入跳转卡在黑屏等teardown
        [view destroyPreviewAsync:nil];
        [self resetOverlayLayoutCache];
        if (onComplete) {
            onComplete();
        }
        return;
    }

    FURenderKitCameraView *view = sOverlayCameraView;
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
    if ([FURenderKit shareRenderKit] == nil) {
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

/** 追加诊断日志到沙盒 Documents/nama_diag.log（离线打包便于导出诊断） */
- (void)appendDiagFile:(NSString *)line {
    @try {
        NSArray *paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES);
        NSString *doc = paths.firstObject;
        if (doc.length == 0) {
            return;
        }
        NSString *file = [doc stringByAppendingPathComponent:@"nama_diag.log"];
        NSDate *now = [NSDate date];
        NSString *entry = [NSString stringWithFormat:@"%@ %@\n", now, line];
        NSFileHandle *fh = [NSFileHandle fileHandleForWritingAtPath:file];
        if (fh) {
            @try {
                [fh seekToEndOfFile];
                [fh writeData:[entry dataUsingEncoding:NSUTF8StringEncoding]];
            } @finally {
                [fh closeFile];
            }
        } else {
            [entry writeToFile:file atomically:YES encoding:NSUTF8StringEncoding error:nil];
        }
    } @catch (NSException *e) {
        // 忽略日志写入异常
    }
}

/**
 * 从随插件内置的 FURenderKit.framework/FURenderKit.bundle 里找资源。
 * 返回绝对路径；找不到返回 nil。
 */
- (NSString *)furKitBundlePathForResource:(NSString *)name ofType:(NSString *)ext {
    NSBundle *mainB = [NSBundle mainBundle];
    // 优先：mainBundle 根的 FURenderKit.bundle（CocoaPods resources 会把 bundle 平铺到 mainBundle）
    NSString *mainDir = [mainB.bundlePath stringByAppendingPathComponent:@"FURenderKit.bundle"];
    NSString *mainPath = [[mainDir stringByAppendingPathComponent:name] stringByAppendingPathExtension:ext];
    if ([[NSFileManager defaultManager] fileExistsAtPath:mainPath]) {
        return mainPath;
    }
    // 其次：mainBundle 根直接平铺
    NSString *rootPath = [mainB pathForResource:name ofType:ext];
    if (rootPath) {
        return rootPath;
    }
    // 兜底：framework 内 FURenderKit.bundle
    NSBundle *kitBundle = [NSBundle bundleForClass:[FURenderKit class]];
    if (kitBundle) {
        NSString *bundleDir = [kitBundle.bundlePath stringByAppendingPathComponent:@"FURenderKit.bundle"];
        NSString *direct = [[bundleDir stringByAppendingPathComponent:name] stringByAppendingPathExtension:ext];
        if ([[NSFileManager defaultManager] fileExistsAtPath:direct]) {
            return direct;
        }
        NSString *bundlePath = [kitBundle pathForResource:@"FURenderKit" ofType:@"bundle"];
        if (bundlePath) {
            NSBundle *res = [NSBundle bundleWithPath:bundlePath];
            NSString *p = [res pathForResource:name ofType:ext];
            if (p) {
                return p;
            }
        }
    }
    return nil;
}

@end
