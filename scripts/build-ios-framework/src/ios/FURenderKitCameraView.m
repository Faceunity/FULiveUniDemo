//
//  FURenderKitCameraView.m
//
//  FURenderKit 版相机美颜预览视图：把自建 GLKView + 旧 C SDK 的渲染链路
//  替换为官方 FURenderKit 内部相机方案。
//
//  - 类继承 UIView，内部持有官方 FUGLDisplayView 作为 subview 显示画面。
//  - 相机采集 / 美颜渲染 / 预览显示全部由 [FURenderKit shareRenderKit] 内部相机完成，
//    本类只负责编排：glDisplayView 赋值、startInternalCamera/stopInternalCamera、
//    pause 开关、切摄、拍照/录像、对焦曝光、诊断。
//  - SDK 初始化 / AI 模型加载 / 美颜 bundle 创建在 NamaModule.m 处理，
//    这里只访问 shareRenderKit 及已赋值的 beauty。
//

#import "FURenderKitCameraView.h"

#import <FURenderKit/FURenderKit.h>
#import <FURenderKit/FUInternalCameraSetting.h>
#import <FURenderKit/FUCaptureCamera.h>
#import <FURenderKit/FUGLDisplayView.h>
#import <FURenderKit/FUGLContext.h>
#import <FURenderKit/FUBeauty.h>
#import <FURenderKit/FUAIKit.h>
#import <FURenderKit/FUFilterDefineKey.h>

#import <AVFoundation/AVFoundation.h>
#import <Photos/Photos.h>
#import <mach/mach_time.h>

#define FU_LOG(fmt, ...) do { } while (0)

#pragma mark - 静态诊断状态（对齐 BeautyCameraView 的 g* 变量）

static NSString *gLastError = @"";
static BOOL gBeautyEnabled = YES;
static int gTargetPreviewW = 1280;
static int gTargetPreviewH = 720;
/** 对齐官方 FURenderViewModel.presets 下标：@"480" / @"720" / @"1080" */
static NSString *gTargetResolutionId = @"720";
/** 用户是否显式选过分辨率；进页默认 720 也为 YES */
static BOOL gTargetPreviewSet = YES;
static BOOL gLastPreviewStarted = NO;
static int gLastFrameW = 0;
static int gLastFrameH = 0;
static float gLastFps = 0.f;
static int gLastRenderTimeMs = 0;
static int gLastTracking = -1;
static int gFrameCount = 0;
static int gRenderOkCount = 0;
/** Benchmark 帧统计（对齐 Android KitBeautyCameraGLView.accumulateBenchmarkFrame） */
static const int kBenchmarkFrameCount = 10;
static int sBenchmarkFrameCnt = 0;
static long sBenchmarkLastStampNs = 0;
static long sBenchmarkRenderNs = 0;
static BOOL sBenchmarkRenderThisFrame = NO;
static long sBenchmarkFrameStartNs = 0;
/** 排队中的美颜参数（旧 C SDK 参数名 → FUBeauty 属性名的桥接映射） */
static NSMutableArray<NSDictionary *> *gPendingBeautyParams = nil;
static NSObject *gGLLock = nil;
/** 当前实例，供类方法访问相机状态（对齐原 sSharedGLContext 单例管理模式） */
static __weak FURenderKitCameraView *sCurrentInstance = nil;
static NSString *gOverlayRootClass = @"";

static NSObject *FUGLLock(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        gGLLock = [[NSObject alloc] init];
    });
    return gGLLock;
}

/** mach_absolute_time 转纳秒，等价 Android System.nanoTime()。 */
static long FUMonotonicNowNanos(void) {
    static mach_timebase_info_data_t sTimebase;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        mach_timebase_info(&sTimebase);
    });
    uint64_t now = mach_absolute_time();
    return (long)((now * sTimebase.numer) / sTimebase.denom);
}

/** 每帧渲染前调用（渲染线程）。记录渲染耗时起点与真实采集分辨率。 */
static void FUBeautyMarkBenchmarkFrameStart(FURenderInput *renderInput) {
    sBenchmarkFrameStartNs = FUMonotonicNowNanos();
    sBenchmarkRenderThisFrame = YES;
    if (renderInput) {
        CVPixelBufferRef pb = renderInput.pixelBuffer;
        if (pb) {
            size_t w = CVPixelBufferGetWidth(pb);
            size_t h = CVPixelBufferGetHeight(pb);
            if (w > 0 && h > 0) {
                gLastFrameW = (int)w;
                gLastFrameH = (int)h;
            }
        }
    }
}

/** 每帧渲染后调用（渲染线程）。每攒满 kBenchmarkFrameCount 帧统计一次 fps 与平均渲染耗时。 */
static void FUBeautyAccumulateBenchmarkFrame(void) {
    long now = FUMonotonicNowNanos();
    if (sBenchmarkRenderThisFrame) {
        sBenchmarkRenderNs += now - sBenchmarkFrameStartNs;
        sBenchmarkRenderThisFrame = NO;
    }
    if (++sBenchmarkFrameCnt < kBenchmarkFrameCount) {
        return;
    }
    if (sBenchmarkLastStampNs > 0) {
        long elapsed = now - sBenchmarkLastStampNs;
        if (elapsed > 0) {
            float fps = (float)((double)kBenchmarkFrameCount * 1000000000.0 / elapsed);
            gLastFps = MAX(0.f, fps);
        }
    }
    gLastRenderTimeMs = (int)((double)sBenchmarkRenderNs / kBenchmarkFrameCount / 1000000.0);
    sBenchmarkFrameCnt = 0;
    sBenchmarkLastStampNs = now;
    sBenchmarkRenderNs = 0;
}

/** 重建/销毁 GL 时清空 benchmark 统计。 */
static void FUBeautyResetBenchmark(void) {
    sBenchmarkFrameCnt = 0;
    sBenchmarkLastStampNs = 0;
    sBenchmarkRenderNs = 0;
    sBenchmarkRenderThisFrame = NO;
    sBenchmarkFrameStartNs = 0;
    gLastFps = 0.f;
    gLastRenderTimeMs = 0;
}

/** C SDK 下划线 key → FUBeauty 驼峰属性名的桥接映射 */
static NSString *FUBeautyKeyForSDKKey(NSString *sdkKey) {
    static NSDictionary *map = nil;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        map = @{
            // Skin
            @"skin_detect": @"skinDetect",
            @"nonskin_blur_scale": @"nonskinBlurScale",
            @"blur_level": @"blurLevel",
            @"blur_type": @"blurType",
            @"heavy_blur": @"heavyBlur",
            @"blur_use_mask": @"blurUseMask",
            @"enable_skinseg": @"enableSkinSegmentation",
            @"anti_acne_spot": @"antiAcneSpot",
            @"delspot_level": @"antiAcneSpot",
            @"color_level": @"colorLevel",
            @"red_level": @"redLevel",
            @"clarity": @"clarity",
            @"sharpen": @"sharpen",
            @"face_threed": @"faceThreed",
            @"eye_bright": @"eyeBright",
            @"tooth_whiten": @"toothWhiten",
            @"remove_pouch_strength": @"removePouchStrength",
            @"remove_nasolabial_folds_strength": @"removeNasolabialFoldsStrength",
            // Shape
            @"enable_warp_anti_alias": @"enableWarpAntiAlias",
            @"face_shape": @"faceShape",
            @"change_frames": @"changeFrames",
            @"face_shape_level": @"faceShapeLevel",
            @"cheek_v": @"cheekV",
            @"cheek_thinning": @"cheekThinning",
            @"cheek_long": @"cheekLong",
            @"cheek_circle": @"cheekCircle",
            @"cheek_narrow": @"cheekNarrow",
            @"cheek_small": @"cheekSmall",
            @"cheek_short": @"cheekShort",
            @"intensity_cheekbones": @"intensityCheekbones",
            @"intensity_lower_jaw": @"intensityLowerJaw",
            @"eye_enlarging": @"eyeEnlarging",
            @"intensity_chin": @"intensityChin",
            @"intensity_forehead": @"intensityForehead",
            @"intensity_nose": @"intensityNose",
            @"intensity_mouth": @"intensityMouth",
            @"intensity_lip_thick": @"intensityLipThick",
            @"intensity_eye_height": @"intensityEyeHeight",
            @"intensity_canthus": @"intensityCanthus",
            @"intensity_eye_lid": @"intensityEyeLid",
            @"intensity_eye_space": @"intensityEyeSpace",
            @"intensity_eye_rotate": @"intensityEyeRotate",
            @"intensity_long_nose": @"intensityLongNose",
            @"intensity_philtrum": @"intensityPhiltrum",
            @"intensity_smile": @"intensitySmile",
            @"intensity_eye_circle": @"intensityEyeCircle",
            @"intensity_brow_height": @"intensityBrowHeight",
            @"intensity_brow_space": @"intensityBrowSpace",
            @"intensity_brow_thick": @"intensityBrowThick",
            // Filter
            @"filter_name": @"filterName",
            @"filter_level": @"filterLevel",
        };
    });
    NSString *hit = map[sdkKey];
    if (hit.length > 0) {
        return hit;
    }
    // JS/Android 侧用 _modeX 后缀区分同一属性的不同 mode：去掉后缀映射到基础属性
    // （例：color_level_mode2 → colorLevel；eye_enlarging_mode3 → eyeEnlarging）
    NSString *base = sdkKey;
    NSRegularExpression *re = [NSRegularExpression regularExpressionWithPattern:@"_(mode\\d+)$" options:0 error:nil];
    if (re) {
        NSString *stripped = [re stringByReplacingMatchesInString:sdkKey options:0 range:NSMakeRange(0, sdkKey.length) withTemplate:@""];
        if (stripped.length > 0 && stripped.length < sdkKey.length) {
            base = stripped;
            hit = map[base];
            if (hit.length > 0) {
                return hit;
            }
        }
    }
    // is_beauty_on：FUBeauty 用 enable 开关
    if ([sdkKey isEqualToString:@"is_beauty_on"]) {
        return @"enable";
    }
    return @"";
}

/** 无任何落地路径的旧 SDK key：彻底忽略，不写参 */
static BOOL FUIsUnsupportedSDKKey(NSString *sdkKey) {
    // disable_delspot / use_facial_plump 是 Android 旧开关，iOS 无对应；其余 C 参数走 setParam:forName:
    if ([sdkKey isEqualToString:@"disable_delspot"] ||
        [sdkKey isEqualToString:@"use_facial_plump"]) {
        return YES;
    }
    return NO;
}

/** 走底层 C 参数（FUItem setParam:forName:）而非 OC 属性的 key（对齐 FULiveDemo） */
static BOOL FUIsNativeCParamKey(NSString *sdkKey) {
    if ([sdkKey isEqualToString:@"body_blur_level"] ||
        [sdkKey isEqualToString:@"facial_plump"] ||
        [sdkKey isEqualToString:@"intensity_eye_pupil"]) {
        return YES;
    }
    return NO;
}

/** 把 0/1 数值转成 BOOL 属性值；其余按原始数值 */
static NSNumber *FUBoolOrDoubleForSDKKey(NSString *sdkKey, double value) {
    if ([sdkKey isEqualToString:@"skin_detect"] ||
        [sdkKey isEqualToString:@"nonskin_blur_scale"] ||
        [sdkKey isEqualToString:@"blur_use_mask"] ||
        [sdkKey isEqualToString:@"enable_skinseg"] ||
        [sdkKey isEqualToString:@"enable_warp_anti_alias"] ||
        [sdkKey isEqualToString:@"change_frames"]) {
        return @((value != 0));
    }
    return @(value);
}

@interface FURenderKitCameraView () <FURenderKitDelegate>
@property (nonatomic, strong) FUGLDisplayView *glView;
@property (nonatomic, assign) int layoutWidth;
@property (nonatomic, assign) int layoutHeight;
@property (nonatomic, assign) BOOL previewStarted;
@property (nonatomic, assign) CFAbsoluteTime previewStartTime;
@property (nonatomic, assign) BOOL firstRenderLogged;
@property (nonatomic, assign) BOOL stopped;
@property (nonatomic, assign) BOOL destroying;
/** soft-hide / pause 后为 YES：延迟 bringOverlay 等不得再 unhide，否则露出冻帧 */
@property (nonatomic, assign) BOOL softHidden;
@property (nonatomic, assign) BOOL useFrontCamera;
/** 长按录制最长时长（秒）；录像结束保存到相册 */
@property (nonatomic, assign) CFAbsoluteTime recordStartTime;
@property (nonatomic, assign) BOOL recordingVideo;
@property (nonatomic, assign) BOOL recordAutoStopping;
@property (nonatomic, copy) NSString *recordTempPath;
@property (nonatomic, copy) void (^recordFinished)(NSString *path, NSError *error);
@end

@implementation FURenderKitCameraView

#pragma mark - 静态：全局开关 / 目标分辨率

+ (void)setBeautyEnabledGlobal:(BOOL)enabled {
    gBeautyEnabled = enabled;
    FURenderKitCameraView *inst = sCurrentInstance;
    if (inst) {
        [inst applyBeautyEnabled];
    }
}

/** 对齐官方 presetTitles：480x640 / 720x1280 / 1080x1920（竖屏展示）；采集 preset 见 presets 数组。 */
+ (NSString *)resolutionIdForWidth:(int)width height:(int)height {
    int shortSide = MIN(width, height);
    if (shortSide <= 480) {
        return @"480";
    }
    if (shortSide <= 720) {
        return @"720";
    }
    return @"1080";
}

+ (void)setTargetPreviewResolutionId:(NSString *)resolutionId {
    if (![resolutionId isKindOfClass:[NSString class]] || resolutionId.length == 0) {
        resolutionId = @"720";
    }
    gTargetResolutionId = [resolutionId copy];
    gTargetPreviewSet = YES;
    if ([resolutionId isEqualToString:@"480"]) {
        gTargetPreviewW = 640;
        gTargetPreviewH = 480;
    } else if ([resolutionId isEqualToString:@"1080"]) {
        gTargetPreviewW = 1920;
        gTargetPreviewH = 1080;
    } else {
        gTargetPreviewW = 1280;
        gTargetPreviewH = 720;
    }
    FU_LOG("setTargetPreviewResolutionId %@ -> %dx%d", resolutionId, gTargetPreviewW, gTargetPreviewH);
}

+ (void)setTargetPreviewSize:(int)width height:(int)height {
    if (width > 0 && height > 0) {
        [FURenderKitCameraView setTargetPreviewResolutionId:
            [FURenderKitCameraView resolutionIdForWidth:width height:height]];
    }
}

+ (void)resetTargetPreviewSizeToDefault {
    [FURenderKitCameraView setTargetPreviewResolutionId:@"720"];
    FU_LOG("resetTargetPreviewSizeToDefault 720");
}

/** 对齐官方 FURenderViewModel.presets 数组，按下标 id 映射 preset。 */
+ (AVCaptureSessionPreset)presetForResolutionId:(NSString *)resolutionId {
    if ([resolutionId isEqualToString:@"480"]) {
        return AVCaptureSessionPreset640x480;
    }
    if ([resolutionId isEqualToString:@"1080"]) {
        return AVCaptureSessionPreset1920x1080;
    }
    return AVCaptureSessionPreset1280x720;
}

+ (AVCaptureSessionPreset)presetForTargetPreviewSize {
    if (!gTargetPreviewSet) {
        return AVCaptureSessionPreset1280x720;
    }
    return [FURenderKitCameraView presetForResolutionId:gTargetResolutionId];
}

/** 对齐官方 FURenderViewModel.switchCapturePreset */
+ (BOOL)switchCapturePreset:(AVCaptureSessionPreset)preset unsupportedHandler:(void (^)(void))handler {
    FURenderKit *kit = [FURenderKit shareRenderKit];
    if (!kit) {
        if (handler) {
            dispatch_async(dispatch_get_main_queue(), handler);
        }
        return NO;
    }
    FUCaptureCamera *camera = kit.captureCamera;
    if (!camera) {
        kit.internalCameraSetting.sessionPreset = preset;
        [FURenderKitCameraView appendDiagLine:[NSString stringWithFormat:
            @"switchCapturePreset deferred preset=%@ (camera nil)", preset]];
        return NO;
    }
    if ([camera changeSessionPreset:preset]) {
        kit.internalCameraSetting.sessionPreset = preset;
        [FURenderKitCameraView appendDiagLine:[NSString stringWithFormat:
            @"switchCapturePreset OK preset=%@", preset]];
        return YES;
    }
    [FURenderKitCameraView appendDiagLine:[NSString stringWithFormat:
        @"switchCapturePreset unsupported preset=%@", preset]];
    if (handler) {
        dispatch_async(dispatch_get_main_queue(), handler);
    }
    return NO;
}

/** 立即对运行中的相机会话应用目标分辨率（对齐官方 demo changeSessionPreset）。返回 YES 表示已直接生效。 */
+ (BOOL)applyTargetPreviewSizeToCamera {
    AVCaptureSessionPreset preset = [FURenderKitCameraView presetForTargetPreviewSize];
    [FURenderKitCameraView appendDiagLine:[NSString stringWithFormat:
        @"applyTargetPreviewSizeToCamera id=%@ target=%dx%d set=%d preset=%@",
        gTargetResolutionId, gTargetPreviewW, gTargetPreviewH, gTargetPreviewSet, preset]];
    return [FURenderKitCameraView switchCapturePreset:preset unsupportedHandler:nil];
}

/** 对齐官方 FURenderViewController.fuPopupMenuSegment：后台 changeSessionPreset */
+ (void)applyPreviewResolutionId:(NSString *)resolutionId
              unsupportedHandler:(void (^)(void))handler
                      completion:(void (^)(BOOL applied))completion {
    if (![resolutionId isKindOfClass:[NSString class]] || resolutionId.length == 0) {
        if (completion) {
            dispatch_async(dispatch_get_main_queue(), ^{
                completion(NO);
            });
        }
        return;
    }
    [FURenderKitCameraView setTargetPreviewResolutionId:resolutionId];
    AVCaptureSessionPreset preset = [FURenderKitCameraView presetForResolutionId:resolutionId];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        BOOL applied = [FURenderKitCameraView switchCapturePreset:preset unsupportedHandler:handler];
        dispatch_async(dispatch_get_main_queue(), ^{
            if (completion) {
                completion(applied);
            }
        });
    });
}

+ (void)applyPreviewResolutionWidth:(int)width
                             height:(int)height
                 unsupportedHandler:(void (^)(void))handler
                         completion:(void (^)(BOOL applied))completion {
    if (width <= 0 || height <= 0) {
        if (completion) {
            dispatch_async(dispatch_get_main_queue(), ^{
                completion(NO);
            });
        }
        return;
    }
    NSString *resolutionId = [FURenderKitCameraView resolutionIdForWidth:width height:height];
    [FURenderKitCameraView applyPreviewResolutionId:resolutionId
                                 unsupportedHandler:handler
                                         completion:completion];
}

#pragma mark - 静态：GL 生命周期（离屏渲染 / 全局销毁）

+ (void)shutdownSharedGLContext {
    @synchronized (FUGLLock()) {
        [FURenderKitCameraView stopInternalCameraIfNeeded];
        [FURenderKit destroy];
    }
}

+ (BOOL)ensureSharedGLContextReady:(NSError **)error {
    // FURenderKit 内部统一管理 GL 上下文，无需要求调用方提供 EAGLContext。
    // 此处仅校验 SDK 已初始化（NamaModule loadBundle 已完成 setup）。
    if ([FURenderKit shareRenderKit] == nil) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama"
                                         code:-1
                                     userInfo:@{NSLocalizedDescriptionKey: @"FURenderKit 未初始化"}];
        }
        return NO;
    }
    return YES;
}

+ (EAGLContext *)createContextInSharedGroup {
    @synchronized (FUGLLock()) {
        // FURenderKit 内部相机独占其 EAGLContext，无需暴露共享 context。
        // 返回 FURenderKit 当前 GL context（可空），供旧调用点兜底。
        return [FUGLContext shareGLContext].currentGLContext;
    }
}

+ (void)performWithSharedGLLock:(void (^)(void))block {
    if (!block) {
        return;
    }
    // FURenderKit 渲染在其内部 GL 队列，不需要自建互斥；仍同步返回保证调用方顺序。
    @synchronized (FUGLLock()) {
        block();
    }
}

+ (void)stopInternalCameraIfNeeded {
    FURenderKit *kit = [FURenderKit shareRenderKit];
    if (kit) {
        kit.pause = YES;
        if (kit.glDisplayView) {
            kit.glDisplayView = nil;
        }
        [kit stopInternalCamera];
    }
}

#pragma mark - 静态：美颜参数排队

+ (void)enqueueBeautyParam:(int)handle name:(NSString *)name value:(double)value {
    (void)handle; // 旧 handle 参数在 FURenderKit 方案下由 shareRenderKit.beauty 取代
    if (name.length == 0) {
        return;
    }
    @synchronized (FUGLLock()) {
        if (!gPendingBeautyParams) {
            gPendingBeautyParams = [NSMutableArray array];
        }
        // 拖动滑杆时合并同名参数，避免队列爆炸拖死渲染线程
        for (NSInteger i = (NSInteger)gPendingBeautyParams.count - 1; i >= 0; i--) {
            NSDictionary *old = gPendingBeautyParams[(NSUInteger)i];
            if ([old[@"n"] isEqualToString:name] && [old[@"t"] isEqualToString:@"d"]) {
                [gPendingBeautyParams removeObjectAtIndex:(NSUInteger)i];
                break;
            }
        }
        [gPendingBeautyParams addObject:@{
            @"n": [name copy],
            @"v": @(value),
            @"t": @"d",
        }];
    }
}

+ (void)enqueueBeautyParamString:(int)handle name:(NSString *)name value:(NSString *)value {
    (void)handle;
    if (name.length == 0 || !value) {
        return;
    }
    @synchronized (FUGLLock()) {
        if (!gPendingBeautyParams) {
            gPendingBeautyParams = [NSMutableArray array];
        }
        [gPendingBeautyParams addObject:@{
            @"n": [name copy],
            @"s": [value copy],
            @"t": @"s",
        }];
    }
}

+ (void)flushPendingBeautyParams {
    NSArray *batch = nil;
    @synchronized (FUGLLock()) {
        if (gPendingBeautyParams.count == 0) {
            return;
        }
        batch = [gPendingBeautyParams copy];
        [gPendingBeautyParams removeAllObjects];
    }
    if (batch.count == 0) {
        return;
    }
    [self applyPendingBeautyParams:batch];
}

+ (void)applyPendingBeautyParams:(NSArray<NSDictionary *> *)batch {
    @synchronized (FUGLLock()) {
        FURenderKit *kit = [FURenderKit shareRenderKit];
        FUBeauty *beauty = kit.beauty;
        static int sFlushCount = 0;
        for (NSDictionary *p in batch) {
            NSString *name = p[@"n"];
            sFlushCount++;
            if (name.length == 0 || !beauty) {
                continue;
            }
            NSString *key = FUBeautyKeyForSDKKey(name);
            NSString *valStr = p[@"t"] ? [NSString stringWithFormat:@"str=%@", p[@"s"]] : [NSString stringWithFormat:@"v=%.3f", [p[@"v"] doubleValue]];
            if (sFlushCount <= 20) {
                [FURenderKitCameraView appendDiagLine:[NSString stringWithFormat:
                    @"flush #%d name=%@ -> key=%@ %@", sFlushCount, name ?: @"?", key ?: @"<空>", valStr]];
            }
            if (key.length == 0) {
                // 无 OC 属性映射的 key：彻底忽略（无落地路径的开关）
                if (FUIsUnsupportedSDKKey(name)) {
                    continue;
                }
                // 未映射的 key 兜底：C SDK 参数名大多与 FUBeauty 属性同名，直接试 KVC
                key = name;
            }
            // C 参数（body_blur_level/facial_plump/intensity_eye_pupil）：对齐 FULiveDemo 用 setParam:forName:
            if (FUIsNativeCParamKey(name)) {
                if ([p[@"t"] isEqualToString:@"s"]) {
                    [beauty setParam:p[@"s"] forName:name paramType:FUParamTypeString];
                } else {
                    [beauty setParam:@([p[@"v"] doubleValue]) forName:name paramType:FUParamTypeDouble];
                }
                continue;
            }
            if ([p[@"t"] isEqualToString:@"s"]) {
                NSString *strValue = p[@"s"];
                if ([key isEqualToString:@"filterName"]) {
                    FUFilter f = (FUFilter)strValue;
                    beauty.filterName = f;
                } else if ([beauty isKindOfClass:[FUBeauty class]]) {
                    @try {
                        [beauty setValue:strValue forKey:key];
                    } @catch (NSException *e) {
                        gLastError = [NSString stringWithFormat:@"beauty setValue(%@) 失败: %@", key, e.reason ?: @""];
                    }
                }
            } else {
                double v = [p[@"v"] doubleValue];
                if ([key isEqualToString:@"filterName"]) {
                    beauty.filterName = FUFilterOrigin; // 数值写 filterName 无意义，置 origin
                } else {
                    NSNumber *boxed = FUBoolOrDoubleForSDKKey(name, v);
                    @try {
                        [beauty setValue:boxed forKey:key];
                        // 关键诊断：写入后立即读回，确认属性真的变了
                        if ([key isEqualToString:@"colorLevel"] || [key isEqualToString:@"cheekThinning"] ||
                            [key isEqualToString:@"eyeEnlarging"] || [key isEqualToString:@"redLevel"]) {
                            id back = [beauty valueForKey:key];
                            [FURenderKitCameraView appendDiagLine:[NSString stringWithFormat:
                                @"WRITE %@ = %@ -> readBack=%@", key, boxed, back]];
                        }
                    } @catch (NSException *e) {
                        gLastError = [NSString stringWithFormat:@"beauty setValue(%@) 失败: %@", key, e.reason ?: @""];
                        [FURenderKitCameraView appendDiagLine:[NSString stringWithFormat:
                            @"KVC_EXC %@ -> %@", key, e.reason ?: @""]];
                    }
                }
            }
        }
    }
}

#pragma mark - 静态：诊断

/** 追加诊断日志到沙盒 Documents/nama_diag.log（与 NamaModule 共用） */
+ (void)appendDiagLine:(NSString *)line {
    @try {
        NSArray *paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES);
        NSString *doc = paths.firstObject;
        if (doc.length == 0) {
            return;
        }
        NSString *file = [doc stringByAppendingPathComponent:@"nama_diag.log"];
        NSString *entry = [NSString stringWithFormat:@"%@ %@\n", [NSDate date], line];
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

+ (void)resetSessionDiag {
    gFrameCount = 0;
    gRenderOkCount = 0;
    gLastTracking = -1;
    gLastError = @"";
    FUBeautyResetBenchmark();
    @synchronized (FUGLLock()) {
        if (gPendingBeautyParams) {
            [gPendingBeautyParams removeAllObjects];
        }
    }
}

+ (NSString *)lastError {
    return gLastError ?: @"";
}

+ (NSString *)previewDiag {
    int faces = 0;
    @try {
        faces = [FUAIKit shareKit].trackedFacesCount;
        if (faces <= 0) {
            int n = [FUAIKit aiFaceProcessorNums];
            faces = n > 0 ? n : 0;
        }
    } @catch (NSException *e) {
        faces = 0;
    }
    return [NSString stringWithFormat:
            @"frameCount=%d renderOk=%d cameraFrame=%dx%d tracking=%d previewStarted=%@ faces=%d beautyOn=%@ err=%@",
            gFrameCount,
            gRenderOkCount,
            gLastFrameW,
            gLastFrameH,
            gLastTracking,
            gLastPreviewStarted ? @"true" : @"false",
            faces,
            [FURenderKit shareRenderKit].beauty.enable ? @"true" : @"false",
            gLastError ?: @""];
}

+ (NSDictionary *)previewStats {
    int faces = 0;
    @try {
        faces = [FUAIKit shareKit].trackedFacesCount;
        if (faces <= 0) {
            int n = [FUAIKit aiFaceProcessorNums];
            faces = n > 0 ? n : 0;
        }
    } @catch (NSException *e) {
        faces = 0;
    }
    int shortSide = 0;
    if (gLastFrameW > 0 && gLastFrameH > 0) {
        shortSide = MIN(gLastFrameW, gLastFrameH);
    }
    NSString *label = shortSide > 0
        ? [NSString stringWithFormat:@"%d.%.0f.%d", shortSide, gLastFps, gLastRenderTimeMs]
        : [NSString stringWithFormat:@"0.%.0f.%d", gLastFps, gLastRenderTimeMs];
    return @{
        @"fps": @(gLastFps),
        @"resolution": @(shortSide),
        @"renderTime": @(gLastRenderTimeMs),
        @"tracking": @(gLastTracking),
        @"faces": @(faces),
        @"frameWidth": @(gLastFrameW),
        @"frameHeight": @(gLastFrameH),
        @"frameCount": @(gFrameCount),
        @"renderOk": @(gRenderOkCount),
        @"previewStarted": @(gLastPreviewStarted),
        @"label": label,
        @"sdkVersion": [FURenderKit getVersion] ?: @"",
    };
}

+ (void)setOverlayRootClass:(NSString *)rootClass {
    gOverlayRootClass = rootClass ?: @"";
}

#pragma mark - 生命周期

- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.backgroundColor = [UIColor blackColor];
        self.opaque = YES;
        self.layer.opaque = YES;
        self.layer.backgroundColor = [UIColor blackColor].CGColor;
        self.layer.zPosition = 1000.f;
        self.clipsToBounds = YES;
        _layoutWidth = (int)CGRectGetWidth(frame);
        _layoutHeight = (int)CGRectGetHeight(frame);
        _useFrontCamera = YES;
        _previewStarted = NO;
        _stopped = NO;
        _destroying = NO;
        _softHidden = NO;

        FUGLDisplayView *glView = [[FUGLDisplayView alloc] initWithFrame:self.bounds];
        glView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
        glView.contentMode = FUGLDisplayViewContentModeScaleAspectFill;
        glView.orientation = FUGLDisplayViewOrientationPortrait;
        glView.backgroundColor = [UIColor blackColor];
        [self addSubview:glView];
        _glView = glView;

        static dispatch_once_t once;
        dispatch_once(&once, ^{
            gPendingBeautyParams = [NSMutableArray array];
        });
    }
    return self;
}

- (void)dealloc {
    @synchronized (FUGLLock()) {
        if (sCurrentInstance == self) {
            sCurrentInstance = nil;
        }
    }
    FURenderKit *kit = [FURenderKit shareRenderKit];
    if (kit) {
        kit.pause = YES;
        if (kit.glDisplayView == self.glView) {
            kit.glDisplayView = nil;
        }
    }
}

- (BOOL)isPreviewStarted {
    return _previewStarted;
}

- (BOOL)isSoftHidden {
    return _softHidden;
}

- (void)bindLayoutSize:(int)width height:(int)height {
    if (width <= 32 || height <= 32) {
        return;
    }
    // 仅记录预览区域像素，分辨率由 setTargetPreviewResolutionId / switchCapturePreset 负责
    _layoutWidth = width;
    _layoutHeight = height;
}

- (void)layoutSubviews {
    [super layoutSubviews];
    self.glView.frame = self.bounds;
}

- (void)didMoveToWindow {
    [super didMoveToWindow];
    // soft-hide / pause 后页面转场仍可能触发 didMoveToWindow；勿把冻帧重新露出来
    if (self.window && !_destroying && !_stopped && !_softHidden) {
        self.hidden = NO;
        // 延迟到下一 runloop 再 startPreview，避免在上窗瞬间（view 未稳定）启动相机
        // （startInternalCamera 上窗瞬间调用会阻塞主线程约 9s，对齐 demo 时序后消除）
        __weak typeof(self) wself = self;
        dispatch_async(dispatch_get_main_queue(), ^{
            __strong typeof(self) sself = wself;
            if (sself) {
                [sself ensurePreviewStartedIfReady];
            }
        });
    }
}

- (void)ensurePreviewStartedIfReady {
    if (_destroying || _stopped || !self.window) {
        return;
    }
    if (!_previewStarted) {
        [self startPreview];
    }
}

/// 诊断：相机启动统一走延迟路径（view 稳定后），避免上窗瞬间启动
- (void)startPreviewIfWindowStable {
    if (!self.window || _previewStarted) {
        return;
    }
    __weak typeof(self) wself = self;
    dispatch_async(dispatch_get_main_queue(), ^{
        __strong typeof(self) sself = wself;
        if (sself && !sself->_previewStarted) {
            [sself startPreview];
        }
    });
}

#pragma mark - 预览控制

- (void)startPreview {
    if (_previewStarted || _stopped || _destroying) {
        return;
    }
    AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
    if (status == AVAuthorizationStatusDenied || status == AVAuthorizationStatusRestricted) {
        gLastError = @"相机权限被拒绝";
        return;
    }
    if (status == AVAuthorizationStatusNotDetermined) {
        [AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo completionHandler:^(BOOL granted) {
            dispatch_async(dispatch_get_main_queue(), ^{
                if (granted) {
                    [self configureCameraIfNeeded];
                } else {
                    gLastError = @"相机权限被拒绝";
                }
            });
        }];
        return;
    }
    [self configureCameraIfNeeded];
    [self requestMicrophoneIfNeeded];
}

- (void)configureCameraIfNeeded {
    FURenderKit *kit = [FURenderKit shareRenderKit];
    if (!kit) {
        gLastError = @"FURenderKit 未初始化";
        return;
    }

    @synchronized (FUGLLock()) {
        if (sCurrentInstance && sCurrentInstance != self) {
            [FURenderKitCameraView stopInternalCameraIfNeeded];
        }
        sCurrentInstance = self;
    }

    FUGLDisplayView *glView = self.glView;
    if (!glView.superview) {
        [self addSubview:glView];
    }
    glView.hidden = NO;
    glView.frame = self.bounds;

    FUInternalCameraSetting *setting = kit.internalCameraSetting;
    setting.position = _useFrontCamera ? AVCaptureDevicePositionFront : AVCaptureDevicePositionBack;
    setting.sessionPreset = [FURenderKitCameraView presetForTargetPreviewSize];
    setting.fps = 30;
    setting.format = kCVPixelFormatType_32BGRA;
    // 对齐官方 FURenderViewModel.startCameraWithRenderView：开启音频轨，否则录像无声音
    setting.needsAudioTrack = YES;

    kit.pause = NO;

    // 渲染前 flush 排队的美颜参数（对齐 Android runOnNamaGl 写参时机）
    kit.delegate = self;

    [self applyBeautyEnabled];

    // 等 UI 在 window 上后再启动，避免采集启动后显示不出来
    CFAbsoluteTime tStart = CFAbsoluteTimeGetCurrent();
    _previewStartTime = tStart;
    _firstRenderLogged = NO;
    // 对齐 demo：先 startInternalCamera 再设 glDisplayView（stop 后重启渲染的关键顺序）
    [kit startInternalCamera];
    CFAbsoluteTime tEnd = CFAbsoluteTimeGetCurrent();
    // 内部相机渲染到本类持有的 FUGLDisplayView
    if (kit.glDisplayView != glView) {
        kit.glDisplayView = glView;
    }
    _previewStarted = YES;
    gLastPreviewStarted = YES;
    gLastError = @"";
    [FURenderKitCameraView appendDiagLine:[NSString stringWithFormat:
        @"startInternalCamera ok startCameraMs=%.0f front=%d preset=%@",
        (tEnd - tStart) * 1000, _useFrontCamera ? 1 : 0, setting.sessionPreset]];
    FU_LOG("startInternalCamera front=%d preset=%@", _useFrontCamera ? 1 : 0, setting.sessionPreset);
}

- (void)applyBeautyEnabled {
    FURenderKit *kit = [FURenderKit shareRenderKit];
    if (kit.beauty) {
        kit.beauty.enable = gBeautyEnabled;
    }
}

- (void)stopPreview {
    @synchronized (FUGLLock()) {
        if (sCurrentInstance == self) {
            sCurrentInstance = nil;
        }
    }
    _previewStarted = NO;
    gLastPreviewStarted = NO;
    FURenderKit *kit = [FURenderKit shareRenderKit];
    if (kit) {
        kit.pause = YES;
        if (kit.glDisplayView == self.glView) {
            kit.glDisplayView = nil;
        }
        [kit stopInternalCamera];
    }
    gLastFrameW = 0;
    gLastFrameH = 0;
}

- (void)hidePreview {
    _softHidden = YES;
    self.hidden = YES;
    self.alpha = 0;
    self.glView.hidden = YES;
    // 软暂停：只停渲染循环、保留采集会话，后台恢复时无需重启相机（重启会丢渲染）
    FURenderKit *kit = [FURenderKit shareRenderKit];
    if (kit) {
        kit.pause = YES;
    }
    [FURenderKitCameraView resetSessionDiag];
    FU_LOG("hidePreview soft-paused");
}

- (void)resumePreview {
    _softHidden = NO;
    self.alpha = 1;
    self.hidden = NO;
    self.glView.hidden = NO;
    _stopped = NO;
    _destroying = NO;
    // 软恢复：重启渲染循环即可，不重启相机（保留采集会话，避免渲染丢失）
    FURenderKit *kit = [FURenderKit shareRenderKit];
    if (kit) {
        kit.pause = NO;
    }
    if (!_previewStarted) {
        [self startPreview];
    }
    FU_LOG("resumePreview started=%@", _previewStarted ? @"true" : @"false");
}

- (void)restartPreview {
    @synchronized (FUGLLock()) {
        if (sCurrentInstance == self) {
            sCurrentInstance = nil;
        }
    }
    _previewStarted = NO;
    gLastPreviewStarted = NO;
    FURenderKit *kit = [FURenderKit shareRenderKit];
    if (kit) {
        kit.pause = YES;
        if (kit.glDisplayView == self.glView) {
            kit.glDisplayView = nil;
        }
        [kit stopInternalCamera];
    }
    _stopped = NO;
    if (self.window) {
        [self startPreview];
    }
}

- (void)releaseCameraKeepAlive:(void (^)(void))onFinished {
    _previewStarted = NO;
    gLastPreviewStarted = NO;
    self.hidden = NO;
    [self stopPreview];
    if (onFinished) {
        dispatch_async(dispatch_get_main_queue(), onFinished);
    }
}

- (void)destroyPreviewAsync:(void (^)(void))onFinished {
    if (_destroying) {
        if (onFinished) {
            dispatch_async(dispatch_get_main_queue(), onFinished);
        }
        return;
    }
    _destroying = YES;
    _previewStarted = NO;
    gLastPreviewStarted = NO;
    [self cancelVideoRecord];
    [self stopPreview];
    if (onFinished) {
        dispatch_async(dispatch_get_main_queue(), onFinished);
    }
}

- (void)setBeautyEnabled:(BOOL)enabled {
    gBeautyEnabled = enabled;
    [self applyBeautyEnabled];
}

#pragma mark - 摄像头切换

- (void)switchCameraFacing {
    [self switchCameraFacingWithCompletion:nil];
}

- (void)switchCameraFacingWithCompletion:(void (^)(NSError *error))completion {
    if (_destroying || _stopped) {
        if (completion) {
            completion([NSError errorWithDomain:@"FaceUnityNama" code:-1
                                     userInfo:@{NSLocalizedDescriptionKey: @"相机已停止"}]);
        }
        return;
    }
    if (_recordingVideo) {
        if (completion) {
            completion([NSError errorWithDomain:@"FaceUnityNama" code:-1
                                     userInfo:@{NSLocalizedDescriptionKey: @"录制中无法切换摄像头"}]);
        }
        return;
    }
    BOOL nextFront = !_useFrontCamera;
    FURenderKit *kit = [FURenderKit shareRenderKit];
    if (!kit) {
        if (completion) {
            completion([NSError errorWithDomain:@"FaceUnityNama" code:-1
                                     userInfo:@{NSLocalizedDescriptionKey: @"FURenderKit 未初始化"}]);
        }
        return;
    }
    // 对齐官方 FURenderViewModel.switchCameraBetweenFrontAndRear：当前 preset 在新摄像头不支持则拒绝切换
    FUCaptureCamera *camera = kit.captureCamera;
    if (camera && ![camera supportsAVCaptureSessionPreset:nextFront]) {
        if (completion) {
            completion([NSError errorWithDomain:@"FaceUnityNama" code:-1
                                     userInfo:@{NSLocalizedDescriptionKey: @"设备不支持该分辨率"}]);
        }
        return;
    }
    _useFrontCamera = nextFront;
    // FURenderKit 切摄：改 position 后重启内部相机即可
    kit.internalCameraSetting.position = nextFront ? AVCaptureDevicePositionFront : AVCaptureDevicePositionBack;
    kit.pause = YES;
    [kit stopInternalCamera];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.05 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        kit.pause = NO;
        [kit startInternalCamera];
        _previewStarted = YES;
        gLastPreviewStarted = YES;
        FU_LOG("switchCameraFacing ok front=%d", nextFront ? 1 : 0);
        if (completion) {
            completion(nil);
        }
    });
}

#pragma mark - 拍照 / 录像

/** 480p 常为 640x480 横帧；补竖屏方向元数据（非缩放，对齐相册竖屏展示）。 */
static UIImage *FUPortraitOrientationForVGAIfNeeded(UIImage *image) {
    if (!image || ![gTargetResolutionId isEqualToString:@"480"]) {
        return image;
    }
    if (image.size.width <= image.size.height) {
        return image;
    }
    if (image.imageOrientation != UIImageOrientationUp
        && image.imageOrientation != UIImageOrientationUpMirrored) {
        return image;
    }
    UIImageOrientation orient = image.imageOrientation == UIImageOrientationUpMirrored
        ? UIImageOrientationLeftMirrored
        : UIImageOrientationRight;
    return [UIImage imageWithCGImage:image.CGImage scale:image.scale orientation:orient];
}

- (void)capturePhoto:(void (^)(NSString *path, NSError *error))callback {
    if (!callback) {
        return;
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!_previewStarted || [FURenderKit shareRenderKit].pause) {
            callback(nil, [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                         userInfo:@{NSLocalizedDescriptionKey: @"预览未就绪" }]);
            return;
        }
        UIImage *image = [FURenderKit captureImage];
        if (!image) {
            callback(nil, [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                         userInfo:@{NSLocalizedDescriptionKey: @"捕获帧为空" }]);
            return;
        }
        image = FUPortraitOrientationForVGAIfNeeded(image);
        [self saveImageToPhotoLibrary:image completion:callback];
    });
}

- (void)saveImageToPhotoLibrary:(UIImage *)image completion:(void (^)(NSString *, NSError *))completion {
    [self requestPhotoSaveAuthorization:^(BOOL granted) {
        if (!granted) {
            completion(nil, [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                         userInfo:@{NSLocalizedDescriptionKey: @"相册写入权限被拒绝" }]);
            return;
        }
        [[PHPhotoLibrary sharedPhotoLibrary] performChanges:^{
            [PHAssetChangeRequest creationRequestForAssetFromImage:image];
        } completionHandler:^(BOOL success, NSError *error) {
            dispatch_async(dispatch_get_main_queue(), ^{
                if (success) {
                    completion(@"photos://saved", nil);
                } else {
                    completion(nil, error ?: [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                                           userInfo:@{NSLocalizedDescriptionKey: @"保存相册失败"}]);
                }
            });
        }];
    }];
}

- (void)requestPhotoSaveAuthorization:(void (^)(BOOL granted))completion {
    if (!completion) {
        return;
    }
    if (@available(iOS 14, *)) {
        [PHPhotoLibrary requestAuthorizationForAccessLevel:PHAccessLevelAddOnly handler:^(PHAuthorizationStatus status) {
            dispatch_async(dispatch_get_main_queue(), ^{
                completion(status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited);
            });
        }];
        return;
    }
    [PHPhotoLibrary requestAuthorization:^(PHAuthorizationStatus status) {
        dispatch_async(dispatch_get_main_queue(), ^{
            completion(status == PHAuthorizationStatusAuthorized);
        });
    }];
}

- (BOOL)startVideoRecord:(NSError **)error {
    if (_recordingVideo) {
        return YES;
    }
    if (!_previewStarted) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"预览未就绪，请稍后再试" }];
        }
        return NO;
    }
    NSString *path = [NSTemporaryDirectory() stringByAppendingPathComponent:
        [NSString stringWithFormat:@"fu_rec_%.0f.mp4", [[NSDate date] timeIntervalSince1970] * 1000]];
    [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
    _recordTempPath = path;
    _recordingVideo = YES;
    _recordAutoStopping = NO;
    _recordStartTime = CFAbsoluteTimeGetCurrent();
    [FURenderKit startRecordVideoWithFilePath:path];
    [self requestMicrophoneIfNeeded];
    FU_LOG("startVideoRecord -> %@", path);
    return YES;
}

- (void)cancelVideoRecord {
    _recordingVideo = NO;
    void (^cb)(NSString *, NSError *) = _recordFinished;
    _recordFinished = nil;
    NSString *path = _recordTempPath;
    _recordTempPath = nil;
    if (path.length > 0) {
        [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
    }
    if (cb) {
        cb(nil, [NSError errorWithDomain:@"FaceUnityNama" code:-1
                               userInfo:@{NSLocalizedDescriptionKey: @"录制已取消" }]);
    }
}

- (void)stopVideoRecord:(void (^)(NSString *path, NSError *error))callback {
    void (^done)(NSString *, NSError *) = [callback copy];
    if (!_recordingVideo) {
        // 幂等：重复 stop 当作成功，减少「未在录制」误报
        if (done) {
            done(@"", nil);
        }
        return;
    }
    _recordingVideo = NO;
    _recordAutoStopping = NO;
    NSString *path = _recordTempPath ?: @"";
    _recordTempPath = nil;
    __weak typeof(self) weakSelf = self;
    [FURenderKit stopRecordVideoComplention:^(NSString *filePath) {
        NSString *outPath = filePath ?: path;
        __strong typeof(weakSelf) self = weakSelf;
        if (self) {
            [self saveRecordedVideoAtPath:outPath completion:done];
        } else if (done) {
            done(nil, [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                   userInfo:@{NSLocalizedDescriptionKey: @"相机已释放" }]);
        }
    }];
}

- (void)saveRecordedVideoAtPath:(NSString *)path completion:(void (^)(NSString *, NSError *))completion {
    if (!completion) {
        return;
    }
    [self requestPhotoSaveAuthorization:^(BOOL granted) {
        if (!granted) {
            [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
            completion(nil, [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                         userInfo:@{NSLocalizedDescriptionKey: @"相册写入权限被拒绝" }]);
            return;
        }
        NSURL *url = [NSURL fileURLWithPath:path];
        [[PHPhotoLibrary sharedPhotoLibrary] performChanges:^{
            [PHAssetChangeRequest creationRequestForAssetFromVideoAtFileURL:url];
        } completionHandler:^(BOOL success, NSError *error) {
            dispatch_async(dispatch_get_main_queue(), ^{
                [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
                if (success) {
                    completion(@"photos://saved", nil);
                } else {
                    completion(nil, error ?: [NSError errorWithDomain:@"FaceUnityNama" code:-1
                                                          userInfo:@{NSLocalizedDescriptionKey: @"保存视频失败"}]);
                }
            });
        }];
    }];
}

#pragma mark - 麦克风（官方内部相机录像需要音频时，走 internalCameraSetting.needsAudioTrack）

- (void)requestMicrophoneIfNeeded {
    if (_recordingVideo) {
        return;
    }
    AVAuthorizationStatus st = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeAudio];
    if (st == AVAuthorizationStatusNotDetermined) {
        [AVCaptureDevice requestAccessForMediaType:AVMediaTypeAudio completionHandler:^(BOOL granted) {
            if (!granted) {
                return;
            }
            dispatch_async(dispatch_get_main_queue(), ^{
                // 录音需在相机已启动后开启音频轨
                if (_previewStarted) {
                    [FURenderKit shareRenderKit].internalCameraSetting.needsAudioTrack = YES;
                    // 重启内部相机以挂上音频轨（官方内部相机在 start 时读取该开关）
                    FURenderKit *kit = [FURenderKit shareRenderKit];
                    kit.pause = YES;
                    [kit stopInternalCamera];
                    kit.pause = NO;
                    [kit startInternalCamera];
                }
            });
        }];
    }
}

#pragma mark - 对焦 / 曝光

- (void)tapFocusAtNormalizedX:(CGFloat)nx y:(CGFloat)ny {
    nx = MIN(1.0, MAX(0.0, nx));
    ny = MIN(1.0, MAX(0.0, ny));
    FUCaptureCamera *camera = [FURenderKit shareRenderKit].captureCamera;
    if (!camera) {
        return;
    }
    // 预览视图内 0~1 坐标 → 设备点坐标（对齐 FULiveDemo setCameraFocusPoint）
    CGPoint devicePoint = CGPointMake(ny, _useFrontCamera ? nx : (1.0 - nx));
    devicePoint.x = MIN(1.0, MAX(0.0, devicePoint.x));
    devicePoint.y = MIN(1.0, MAX(0.0, devicePoint.y));
    [camera focusWithMode:AVCaptureFocusModeAutoFocus
            exposeWithMode:AVCaptureExposureModeAutoExpose
             atDevicePoint:devicePoint
    monitorSubjectAreaChange:YES];
    FU_LOG("tapFocus nx=%.2f ny=%.2f poi=(%.2f,%.2f)", nx, ny, devicePoint.x, devicePoint.y);
}

- (void)setExposureNormalized:(CGFloat)value {
    [self setExposureNormalized:value finalizeLock:YES];
}

- (void)setExposureNormalized:(CGFloat)value finalizeLock:(BOOL)finalizeLock {
    (void)finalizeLock;
    value = MIN(1.0, MAX(0.0, value));
    FUCaptureCamera *camera = [FURenderKit shareRenderKit].captureCamera;
    if (!camera) {
        return;
    }
    // 对齐 Android：补偿步进通常约 ±2EV，勿用满 iOS 可达 ±8 的 bias（会过曝/过暗）
    float bias = 0.f;
    if (value >= 0.5f) {
        bias = (float)((value - 0.5) / 0.5) * 2.0f;
    } else {
        bias = (float)((0.5 - value) / 0.5) * -2.0f;
    }
    [camera setExposureValue:bias];
    FU_LOG("setExposureNormalized value=%.2f bias=%.2f", value, bias);
}

- (void)hideFocusChrome {
    // 对焦十字 / 曝光条 UI 在 PreviewChromeView（原生层），不在相机视图内，空实现
}

#pragma mark - FURenderKitDelegate

- (void)renderKitWillRenderFromRenderInput:(FURenderInput *)renderInput {
    // 每帧渲染前落地排队的美颜参数（对齐 Android runOnNamaGl 写参时机）
    [FURenderKitCameraView flushPendingBeautyParams];
    // Benchmark：记录渲染耗时起点与真实采集分辨率（对齐 Android KitBeautyCameraGLView）
    FUBeautyMarkBenchmarkFrameStart(renderInput);
    // 诊断：渲染前读取当前 beauty 参数（每 ~30 帧记一次，确认渲染读到的是最新值）
    static int sWillCount = 0;
    sWillCount++;
    if (sWillCount % 30 == 1) {
        FUBeauty *b = [FURenderKit shareRenderKit].beauty;
        int faces = 0;
        @try {
            faces = [FUAIKit shareKit].trackedFacesCount;
            if (faces <= 0) {
                int n = [FUAIKit aiFaceProcessorNums];
                faces = n > 0 ? n : 0;
            }
        } @catch (NSException *e) {
            faces = -1;
        }
        [FURenderKitCameraView appendDiagLine:[NSString stringWithFormat:
            @"WILLRENDER #%d beauty=%@ enable=%d blur=%.1f color=%.1f cheek=%.2f eye=%.2f faces=%d shape=%d shapeLv=%.1f",
            sWillCount,
            b ? @"YES" : @"NO",
            b.enable ? 1 : 0,
            b.blurLevel,
            b.colorLevel,
            b.cheekThinning,
            b.eyeEnlarging,
            faces,
            b.faceShape,
            b.faceShapeLevel]];
    }
}

- (void)renderKitDidRenderToOutput:(FURenderOutput *)renderOutput {
    // Benchmark：累加渲染耗时并统计 fps / renderTime（对齐 Android KitBeautyCameraGLView）
    FUBeautyAccumulateBenchmarkFrame();
    // 对齐 BeautyCameraView / 官方 demo：最长 10 秒自动停录（从 FURenderKit 真正开始录算起）
    if (_recordingVideo && !_recordAutoStopping) {
        CFAbsoluteTime now = CFAbsoluteTimeGetCurrent();
        if ((now - _recordStartTime) >= 10.0) {
            _recordAutoStopping = YES;
            dispatch_async(dispatch_get_main_queue(), ^{
                void (^autoCb)(NSString *, NSError *) = self.onAutoStopRecording;
                [self stopVideoRecord:^(NSString *path, NSError *error) {
                    FU_LOG("autoStopVideoRecord path=%@ err=%@", path, error.localizedDescription);
                    if (autoCb) {
                        autoCb(path, error);
                    }
                }];
            });
        }
    }
    // 诊断：首帧渲染时间戳（画面真正出现的时刻），每次 startPreview 记录一次
    if (!_firstRenderLogged) {
        _firstRenderLogged = YES;
        [FURenderKitCameraView appendDiagLine:[NSString stringWithFormat:
            @"FIRST_RENDER elapsedAfterStartPreviewMs=%.0f",
            (CFAbsoluteTimeGetCurrent() - _previewStartTime) * 1000]];
    }
}

- (BOOL)renderKitShouldDoRender {
    return YES;
}

@end
