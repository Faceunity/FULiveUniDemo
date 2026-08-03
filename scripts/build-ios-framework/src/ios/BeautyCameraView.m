#import "BeautyCameraView.h"
#import "CNamaSDK.h"
#import "FuBeautyHandle.h"

#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <OpenGLES/ES3/gl.h>
#import <OpenGLES/ES3/glext.h>
#import <CoreVideo/CoreVideo.h>
#import <Photos/Photos.h>
#import <limits.h>

#define FU_LOG(fmt, ...) do { } while (0)

static NSString *gLastError = @"";
static int gFrameCount = 0;
static int gRenderOkCount = 0;
static int gLastRenderRet = 0;
static int gLastEglW = 0;
static int gLastEglH = 0;
static int gLastLayoutW = 0;
static int gLastLayoutH = 0;
static int gLastFrameW = 0;
static int gLastFrameH = 0;
static int gLastFuOutW = 0;
static int gLastFuOutH = 0;
static int gLastFuTexId = 0;
static int gLastOutTexId = 0;
static int gLastPreviewTexId = 0;
static int gLastFuSysErr = 0;
static int gLastGlError = 0;
static int gLastBeautyHandle = 0;
static BOOL gLastPreviewStarted = NO;
static float gLastFps = 0.f;
static int gLastRenderTimeMs = 0;
static NSTimeInterval gDisplayFpsStart = 0;
static int gDisplayFpsCount = 0;

static void FuTickDisplayFps(void) {
    NSTimeInterval now = CFAbsoluteTimeGetCurrent();
    gDisplayFpsCount += 1;
    if (gDisplayFpsStart <= 0) {
        gDisplayFpsStart = now;
        return;
    }
    NSTimeInterval elapsed = now - gDisplayFpsStart;
    if (elapsed >= 1.0) {
        gLastFps = (float)(gDisplayFpsCount / elapsed);
        gDisplayFpsCount = 0;
        gDisplayFpsStart = now;
    }
}
static BOOL gBeautyEnabled = YES;
static int gTargetPreviewW = 1280;
static int gTargetPreviewH = 720;
static int gLastTracking = -1;
static NSString *gLastDrawPath = @"";
/** 全局 sharegroup：多次进出美颜页复用，避免FU context 与新 GLKView 失联导致二次进入无美颜*/
static EAGLContext *gSharedGLContext = nil;
static BOOL gFuGLContextReady = NO;
static NSMutableArray<NSDictionary *> *gPendingBeautyParams = nil;
static NSObject *gGLLock = nil;

static NSObject *FUGLLock(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        gGLLock = [[NSObject alloc] init];
    });
    return gGLLock;
}

static void releasePixelBuffer(void *info, const void *data, size_t size) {
    (void)info;
    (void)size;
    if (data) {
        free((void *)data);
    }
}

@class FuExposureRailView;

@interface BeautyCameraView () <GLKViewDelegate, AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate>
@property (nonatomic, strong) AVCaptureSession *session;
@property (nonatomic, strong) AVCaptureVideoDataOutput *videoOutput;
@property (nonatomic, strong) AVCaptureAudioDataOutput *audioOutput;
@property (nonatomic, strong) AVCaptureDeviceInput *audioDeviceInput;
@property (nonatomic, strong) dispatch_queue_t captureQueue;
@property (nonatomic, assign) CVOpenGLESTextureCacheRef textureCache;
@property (nonatomic, assign) GLuint renderProgram;
@property (nonatomic, assign) GLuint outputTexture;
@property (nonatomic, assign) GLuint positionAttr;
@property (nonatomic, assign) GLuint texCoordAttr;
@property (nonatomic, assign) GLuint textureUniform;
@property (nonatomic, assign) GLuint mirrorUniform;
@property (nonatomic, assign) GLuint mirrorYUniform;
@property (nonatomic, assign) int layoutWidth;
@property (nonatomic, assign) int layoutHeight;
@property (nonatomic, assign) int eglWidth;
@property (nonatomic, assign) int eglHeight;
@property (nonatomic, assign) int frameId;
@property (nonatomic, assign) int cachedFuOutW;
@property (nonatomic, assign) int cachedFuOutH;
@property (nonatomic, assign) BOOL glReady;
@property (nonatomic, assign) BOOL cameraConfigured;
@property (nonatomic, assign) BOOL previewStarted;
@property (nonatomic, assign) BOOL stopped;
@property (nonatomic, assign) BOOL destroying;
/** soft-hide / pause 后为 YES：didMoveToWindow 禁止再unhide，否则转场露出冻帧*/
@property (nonatomic, assign) BOOL softHidden;
@property (nonatomic, assign) BOOL useFrontCamera;
@property (nonatomic, assign) BOOL capturePending;
@property (nonatomic, assign) BOOL frameReady;
@property (nonatomic, assign) BOOL displayScheduled;
@property (nonatomic, assign) int frameWidth;
@property (nonatomic, assign) int frameHeight;
@property (nonatomic, copy) void (^captureFinished)(NSString *path, NSError *error);
@property (nonatomic, copy) void (^releaseFinishedCallback)(void);
@property (nonatomic, strong) NSObject *frameLock;
@property (nonatomic, assign) BOOL recordingVideo;
@property (nonatomic, strong) AVAssetWriter *assetWriter;
@property (nonatomic, strong) AVAssetWriterInput *writerInput;
@property (nonatomic, strong) AVAssetWriterInput *audioWriterInput;
@property (nonatomic, strong) AVAssetWriterInputPixelBufferAdaptor *pixelAdaptor;
@property (nonatomic, assign) BOOL recordSessionStarted;
@property (nonatomic, assign) int recordWidth;
@property (nonatomic, assign) int recordHeight;
@property (nonatomic, assign) int recordFrameIndex;
@property (nonatomic, assign) CFAbsoluteTime recordStartTime;
@property (nonatomic, assign) CMTime recordAudioBasePts;
@property (nonatomic, assign) BOOL recordAudioBaseValid;
@property (nonatomic, strong) NSMutableArray<NSValue *> *pendingAudioSamples;
@property (nonatomic, copy) NSString *recordTempPath;
@property (nonatomic, copy) void (^recordFinished)(NSString *path, NSError *error);
@property (nonatomic, assign) BOOL recordAutoStopping;
@property (nonatomic, strong) UIView *focusChrome;
@property (nonatomic, strong) UIView *crosshairView;
@property (nonatomic, strong) FuExposureRailView *exposureRail;
@property (nonatomic, strong) NSTimer *focusChromeHideTimer;
/** 用户拖过曝光条后为 YES；对焦时勿冲掉 EV（对齐 Android exposureLockedByUser） */
@property (nonatomic, assign) BOOL exposureLockedByUser;
@property (nonatomic, assign) CGFloat lastExposureNormalized;
@property (nonatomic, assign) CFAbsoluteTime lastFaceExposureApplyTime;
@property (nonatomic, assign) CGPoint lastFaceExposurePoi;
@end

/** 全屏 chrome 只把触摸交给曝光轨，十字/空白穿透*/
@interface FuFocusChromeView : UIView
@property (nonatomic, weak) UIView *exposureRail;
@end
@implementation FuFocusChromeView
- (UIView *)hitTest:(CGPoint)point withEvent:(UIEvent *)event {
    if (self.hidden || !self.exposureRail || self.exposureRail.hidden) {
        return nil;
    }
    CGPoint p = [self convertPoint:point toView:self.exposureRail];
    //  扩大命中，竖条较窄
    if (CGRectContainsPoint(CGRectInset(self.exposureRail.bounds, -12, -12), p)) {
        return self.exposureRail;
    }
    return nil;
}
@end

/** Demo 风格四角对焦框（#869DFF）*/
@interface FuFocusCrossView : UIView
@end
@implementation FuFocusCrossView
- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.backgroundColor = [UIColor clearColor];
        self.userInteractionEnabled = NO;
        self.opaque = NO;
    }
    return self;
}
- (void)drawRect:(CGRect)rect {
    CGFloat w = CGRectGetWidth(self.bounds);
    CGFloat h = CGRectGetHeight(self.bounds);
    CGFloat arm = MIN(w, h) * 0.22f;
    CGFloat lw = 3.2f;
    UIBezierPath *path = [UIBezierPath bezierPath];
    path.lineWidth = lw;
    path.lineCapStyle = kCGLineCapSquare;
    // 四角
    [path moveToPoint:CGPointMake(0, arm)];
    [path addLineToPoint:CGPointMake(0, 0)];
    [path addLineToPoint:CGPointMake(arm, 0)];
    [path moveToPoint:CGPointMake(w - arm, 0)];
    [path addLineToPoint:CGPointMake(w, 0)];
    [path addLineToPoint:CGPointMake(w, arm)];
    [path moveToPoint:CGPointMake(w, h - arm)];
    [path addLineToPoint:CGPointMake(w, h)];
    [path addLineToPoint:CGPointMake(w - arm, h)];
    [path moveToPoint:CGPointMake(arm, h)];
    [path addLineToPoint:CGPointMake(0, h)];
    [path addLineToPoint:CGPointMake(0, h - arm)];
    // 中心十字
    CGFloat cx = w * 0.5f, cy = h * 0.5f, c = MIN(w, h) * 0.12f;
    [path moveToPoint:CGPointMake(cx - c, cy)];
    [path addLineToPoint:CGPointMake(cx + c, cy)];
    [path moveToPoint:CGPointMake(cx, cy - c)];
    [path addLineToPoint:CGPointMake(cx, cy + c)];
    [[UIColor colorWithRed:0x86 / 255.0 green:0x9D / 255.0 blue:1 alpha:0.98] setStroke];
    [path stroke];
}
@end

/** Demo / Android：右侧竖直曝光条（太阳月亮 + 白轨 + thumb）*/
@interface FuExposureRailView : UIView
@property (nonatomic, assign) CGFloat progress; // 0~1
@property (nonatomic, copy) void (^onChange)(CGFloat progress, BOOL finalized);
@end
@implementation FuExposureRailView
- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.backgroundColor = [UIColor clearColor];
        self.opaque = NO;
        self.userInteractionEnabled = YES;
        _progress = 0.5;
    }
    return self;
}
- (void)setProgress:(CGFloat)progress {
    _progress = MIN(1, MAX(0, progress));
    [self setNeedsDisplay];
}
- (void)drawRect:(CGRect)rect {
    CGFloat w = CGRectGetWidth(self.bounds);
    CGFloat h = CGRectGetHeight(self.bounds);
    CGFloat cx = w * 0.5f;
    CGFloat sunCy = 20;
    CGFloat moonCy = h - 20;
    CGFloat sunR = 10;
    CGFloat moonR = 6;
    CGFloat top = sunCy + sunR + 20;
    CGFloat bottom = moonCy - moonR - 20;
    UIBezierPath *track = [UIBezierPath bezierPath];
    track.lineWidth = 4;
    track.lineCapStyle = kCGLineCapRound;
    [track moveToPoint:CGPointMake(cx, top)];
    [track addLineToPoint:CGPointMake(cx, bottom)];
    [[UIColor whiteColor] setStroke];
    [track stroke];
    CGFloat midY = (top + bottom) * 0.5f;
    UIBezierPath *mid = [UIBezierPath bezierPathWithRoundedRect:CGRectMake(cx - 1, midY - 6, 2, 12) cornerRadius:1];
    [[UIColor whiteColor] setFill];
    [mid fill];
    CGFloat y = bottom - (bottom - top) * _progress;
    UIBezierPath *thumb = [UIBezierPath bezierPathWithOvalInRect:CGRectMake(cx - 7, y - 7, 14, 14)];
    [thumb fill];
    CGFloat sy = sunCy;
    UIBezierPath *sun = [UIBezierPath bezierPathWithOvalInRect:CGRectMake(cx - 5, sy - 5, 10, 10)];
    sun.lineWidth = 1.5;
    [sun stroke];
    for (int i = 0; i < 8; i++) {
        CGFloat a = (CGFloat)(i * M_PI / 4.0);
        UIBezierPath *ray = [UIBezierPath bezierPath];
        ray.lineWidth = 1.5;
        [ray moveToPoint:CGPointMake(cx + cosf(a) * 7, sy + sinf(a) * 7)];
        [ray addLineToPoint:CGPointMake(cx + cosf(a) * 10, sy + sinf(a) * 10)];
        [ray stroke];
    }
    CGFloat my = moonCy;
    CGContextRef ctx = UIGraphicsGetCurrentContext();
    [[UIColor whiteColor] setFill];
    UIBezierPath *moon = [UIBezierPath bezierPathWithOvalInRect:CGRectMake(cx - 6, my - 6, 12, 12)];
    [moon fill];
    if (ctx) {
        CGContextSetBlendMode(ctx, kCGBlendModeClear);
        UIBezierPath *cut = [UIBezierPath bezierPathWithOvalInRect:CGRectMake(cx - 1, my - 7, 10, 10)];
        [cut fill];
        CGContextSetBlendMode(ctx, kCGBlendModeNormal);
    }
    [[UIColor whiteColor] setFill];
}
- (void)updateFromTouchY:(CGFloat)y finalized:(BOOL)finalized {
    CGFloat sunCy = 20;
    CGFloat moonCy = CGRectGetHeight(self.bounds) - 20;
    CGFloat top = sunCy + 10 + 20;
    CGFloat bottom = moonCy - 6 - 20;
    CGFloat span = MAX(1, bottom - top);
    self.progress = 1.0 - MIN(1, MAX(0, (y - top) / span));
    if (self.onChange) {
        self.onChange(self.progress, finalized);
    }
}
- (void)touchesBegan:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [self updateFromTouchY:[touches.anyObject locationInView:self].y finalized:NO];
}
- (void)touchesMoved:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [self updateFromTouchY:[touches.anyObject locationInView:self].y finalized:NO];
}
- (void)touchesEnded:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [self updateFromTouchY:[touches.anyObject locationInView:self].y finalized:YES];
}
- (void)touchesCancelled:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [self updateFromTouchY:[touches.anyObject locationInView:self].y finalized:YES];
}
@end

@implementation BeautyCameraView {
    GLfloat _quadVertices[8];
    CVPixelBufferRef _pendingPixelBuffer;
}

+ (void)setBeautyEnabledGlobal:(BOOL)enabled {
    gBeautyEnabled = enabled;
}

+ (void)shutdownSharedGLContext {
    @synchronized (FUGLLock()) {
        if (gFuGLContextReady) {
            if (gSharedGLContext) {
                [EAGLContext setCurrentContext:gSharedGLContext];
            }
            if (fuIsLibraryInit()) {
                fuOnDeviceLostSafe();
            }
            fuDestroyGLContext();
            gFuGLContextReady = NO;
            FU_LOG("shutdownSharedGLContext");
        }
    }
}

/**
 * 静图/视频离屏渲染用。 * 必须先setCurrent，再 fuInitGLContext；init 失败时回退裸EAGLContext， * 避免冷启动导入直接报「fuInitGLContext 失败」。 */
+ (BOOL)ensureSharedGLContextReadyOnCurrentThread:(NSError **)error {
    if (!gSharedGLContext) {
        gSharedGLContext = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
        if (!gSharedGLContext) {
            if (error) {
                *error = [NSError errorWithDomain:@"FaceUnityNama"
                                             code:-1
                                         userInfo:@{NSLocalizedDescriptionKey: @"创建共享 GL 上下文失败" }];
            }
            return NO;
        }
        FU_LOG("ensureSharedGLContextReady created gSharedGLContext");
    }

    if (![EAGLContext setCurrentContext:gSharedGLContext]) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnityNama"
                                         code:-1
                                     userInfo:@{NSLocalizedDescriptionKey: @"setCurrentContext 失败"}];
        }
        return NO;
    }

    if (!gFuGLContextReady) {
        //  只允许挂到全局 sharegroup；禁止fuInitGLContext(NULL)，否则会与相机GLKView 失联导致白屏/崩
        void *fuCtx = fuInitGLContext((__bridge void *)gSharedGLContext);
        if (fuCtx) {
            gFuGLContextReady = YES;
            FU_LOG("ensureSharedGLContextReady fuInitGLContext ok");
        } else {
            // 回退：仅当前 EAGLContext 做buffer 渲染，不标记 FU managed ready
            FU_LOG("fuInitGLContext(shared) failed, buffer-only fallback (do not mark ready)");
        }
    }

    if (gFuGLContextReady) {
        if (!fuMakeGLContextCurrent()) {
            [EAGLContext setCurrentContext:gSharedGLContext];
            FU_LOG("fuMakeGLContextCurrent failed, use share root");
        }
    }
    return YES;
}

+ (BOOL)ensureSharedGLContextReady:(NSError **)error {
    __block BOOL ok = NO;
    __block NSError *localErr = nil;
    void (^work)(void) = ^{
        @synchronized (FUGLLock()) {
            ok = [self ensureSharedGLContextReadyOnCurrentThread:&localErr];
        }
    };
    // FU GL 初始化放主线程更稳；渲染线程再fuMakeGLContextCurrent / setCurrent
    if ([NSThread isMainThread]) {
        work();
    } else {
        dispatch_sync(dispatch_get_main_queue(), work);
        // 回到调用线程，重新把 context 绑到当前线程
        @synchronized (FUGLLock()) {
            if (ok) {
                if (gFuGLContextReady) {
                    if (!fuMakeGLContextCurrent()) {
                        [EAGLContext setCurrentContext:gSharedGLContext];
                    }
                } else if (gSharedGLContext) {
                    [EAGLContext setCurrentContext:gSharedGLContext];
                }
            }
        }
    }
    if (!ok && error) {
        *error = localErr;
    }
    return ok;
}

+ (void)performWithSharedGLLock:(void (^)(void))block {
    if (!block) {
        return;
    }
    @synchronized (FUGLLock()) {
        EAGLContext *prev = [EAGLContext currentContext];
        // special 写参（丰盈/全身磨皮等）须在 Nama GL current 上，否则 get=set 但渲染无效
        if (gSharedGLContext) {
            [EAGLContext setCurrentContext:gSharedGLContext];
            if (gFuGLContextReady) {
                fuMakeGLContextCurrent();
            }
        }
        @try {
            block();
        } @finally {
            if (prev) {
                [EAGLContext setCurrentContext:prev];
            } else if (gSharedGLContext) {
                [EAGLContext setCurrentContext:nil];
            }
        }
    }
}

+ (void)enqueueBeautyParam:(int)handle name:(NSString *)name value:(double)value {
    if (handle <= 0 || name.length == 0) {
        return;
    }
    @synchronized (FUGLLock()) {
        if (!gPendingBeautyParams) {
            gPendingBeautyParams = [NSMutableArray array];
        }
        // 拖动滑杆时合并同名参数，避免队列爆炸拖死渲染线程
        for (NSInteger i = (NSInteger)gPendingBeautyParams.count - 1; i >= 0; i--) {
            NSDictionary *old = gPendingBeautyParams[(NSUInteger)i];
            if ([old[@"h"] intValue] == handle &&
                [old[@"n"] isEqualToString:name] &&
                [old[@"t"] isEqualToString:@"d"]) {
                [gPendingBeautyParams removeObjectAtIndex:(NSUInteger)i];
                break;
            }
        }
        [gPendingBeautyParams addObject:@{
            @"h": @(handle),
            @"n": [name copy],
            @"v": @(value),
            @"t": @"d",
        }];
    }
    if (FuIsSpecialBeautyParamName(name.UTF8String)) {
        FuCacheSpecialBeautyValue(handle, name.UTF8String, value);
    }
}

+ (void)enqueueBeautyParamString:(int)handle name:(NSString *)name value:(NSString *)value {
    if (handle <= 0 || name.length == 0 || !value) {
        return;
    }
    @synchronized (FUGLLock()) {
        if (!gPendingBeautyParams) {
            gPendingBeautyParams = [NSMutableArray array];
        }
        [gPendingBeautyParams addObject:@{
            @"h": @(handle),
            @"n": [name copy],
            @"s": [value copy],
            @"t": @"s",
        }];
    }
}

/** 须在相机/视频渲染线程、Nama GL current 后调用；内部统一走 shared GL 锁，避免与 processImage 并发 */
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
    [self performWithSharedGLLock:^{
        for (NSDictionary *p in batch) {
            int h = [p[@"h"] intValue];
            NSString *n = p[@"n"];
            if (h <= 0 || n.length == 0) {
                continue;
            }
            if ([p[@"t"] isEqualToString:@"s"]) {
                fuItemSetParams(h, n.UTF8String, [p[@"s"] UTF8String]);
            } else {
                const char *key = n.UTF8String;
                double v = [p[@"v"] doubleValue];
                if (FuIsSpecialBeautyParamName(key)) {
                    // 对齐 Android：仅 special 走 direct 写参，勿 batch 级 ensure（会反复 change_frames=12 导致丰盈间歇失效）
                    FuApplyBeautyParamDirectOnGl(h, key, v);
                } else {
                    fuItemSetParamd(h, key, v);
                }
            }
        }
    }];
}

+ (EAGLContext *)createContextInSharedGroup {
    @synchronized (FUGLLock()) {
        if (!gSharedGLContext) {
            gSharedGLContext = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
        }
        if (!gSharedGLContext) {
            return nil;
        }
        EAGLContext *ctx = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3
                                                 sharegroup:gSharedGLContext.sharegroup];
        return ctx ?: gSharedGLContext;
    }
}

+ (void)setTargetPreviewSize:(int)width height:(int)height {
    if (width > 0 && height > 0) {
        gTargetPreviewW = width;
        gTargetPreviewH = height;
        FU_LOG("setTargetPreviewSize %dx%d", width, height);
    }
}

+ (void)resetTargetPreviewSizeToDefault {
    gTargetPreviewW = 1280;
    gTargetPreviewH = 720;
    FU_LOG("resetTargetPreviewSizeToDefault 1280x720");
}

+ (void)setOverlayRootClass:(NSString *)rootClass {
    (void)rootClass;
}

+ (void)resetSessionDiag {
    gFrameCount = 0;
    gRenderOkCount = 0;
    gLastRenderRet = 0;
    gLastOutTexId = 0;
    gLastFuTexId = 0;
    gLastPreviewTexId = 0;
    gLastFuSysErr = 0;
    gLastGlError = 0;
    gLastError = @"";
    gLastFps = 0.f;
    gDisplayFpsStart = 0;
    gDisplayFpsCount = 0;
    gLastRenderTimeMs = 0;
    gLastTracking = -1;
    gLastDrawPath = @"";
}

+ (NSString *)lastError {
    return gLastError ?: @"";
}

+ (NSString *)previewDiag {
    return [NSString stringWithFormat:
            @"frameCount=%d renderOk=%d egl=%dx%d cameraFrame=%dx%d tracking=%d previewStarted=%@ fuSysErr=%d fuTex=%d outTex=%d previewTex=%d drawPath=%@ glErr=%d",
            gFrameCount,
            gRenderOkCount,
            gLastEglW,
            gLastEglH,
            gLastFrameW,
            gLastFrameH,
            gLastTracking,
            gLastPreviewStarted ? @"true" : @"false",
            gLastFuSysErr,
            gLastFuTexId,
            gLastOutTexId,
            gLastPreviewTexId,
            gLastDrawPath ?: @"",
            gLastGlError];
}

+ (NSDictionary *)previewStats {
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
        @"frameWidth": @(gLastFrameW),
        @"frameHeight": @(gLastFrameH),
        @"frameCount": @(gFrameCount),
        @"renderOk": @(gRenderOkCount),
        @"previewStarted": @(gLastPreviewStarted),
        @"label": label,
    };
}

- (instancetype)initWithFrame:(CGRect)frame {
    if (!gSharedGLContext) {
        gSharedGLContext = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
    }
    // 每次新建 View 都挂到同一 sharegroup，FU 共享 context 可跨页面复用
    EAGLContext *context = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3
                                                 sharegroup:gSharedGLContext.sharegroup];
    if (!context) {
        context = gSharedGLContext;
    }
    self = [super initWithFrame:frame context:context];
    if (self) {
        _frameLock = [[NSObject alloc] init];
        //  原生 overlay 盖在 WKWebView 之上，必须不透明；透明时清屏黑纹理会“看起来像黑块消失”
        self.backgroundColor = [UIColor blackColor];
        self.opaque = YES;
        self.layer.opaque = YES;
        self.layer.backgroundColor = [UIColor blackColor].CGColor;
        self.layer.zPosition = 1000.f;
        self.contentScaleFactor = [UIScreen mainScreen].scale;
        self.drawableColorFormat = GLKViewDrawableColorFormatRGBA8888;
        self.drawableDepthFormat = GLKViewDrawableDepthFormatNone;
        self.drawableStencilFormat = GLKViewDrawableStencilFormatNone;
        self.drawableMultisample = GLKViewDrawableMultisampleNone;
        // 对齐 Android RENDERMODE_WHEN_DIRTY
        self.enableSetNeedsDisplay = YES;
        self.delegate = self;
        _captureQueue = dispatch_queue_create("com.faceunity.beauty.camera", DISPATCH_QUEUE_SERIAL);
        _layoutWidth = (int)(CGRectGetWidth(frame) * self.contentScaleFactor);
        _layoutHeight = (int)(CGRectGetHeight(frame) * self.contentScaleFactor);
        _useFrontCamera = YES;
        _exposureLockedByUser = NO;
        _lastExposureNormalized = 0.5;
        _lastFaceExposurePoi = CGPointMake(-1, -1);
        gLastLayoutW = _layoutWidth;
        gLastLayoutH = _layoutHeight;
        [self rebuildVertexBufferWithCenterCrop];
    }
    return self;
}

- (void)dealloc {
    [self stopPreview];
    [self teardownGL];
    @synchronized (_frameLock) {
        if (_pendingPixelBuffer) {
            CVPixelBufferRelease(_pendingPixelBuffer);
            _pendingPixelBuffer = NULL;
        }
    }
}

- (BOOL)isPreviewStarted {
    return _previewStarted;
}

- (void)setBeautyEnabled:(BOOL)enabled {
    gBeautyEnabled = enabled;
    if (FuBeautyCameraHandle > 0 && fuIsLibraryInit() != 0) {
        fuItemSetParamd(FuBeautyCameraHandle, "is_beauty_on", enabled ? 1.0 : 0.0);
    }
}

- (void)bindLayoutSize:(int)width height:(int)height {
    if (width <= 32 || height <= 32) {
        return;
    }
    _layoutWidth = width;
    _layoutHeight = height;
    gLastLayoutW = width;
    gLastLayoutH = height;
    [self rebuildVertexBufferWithCenterCrop];
    dispatch_async(dispatch_get_main_queue(), ^{
        [self display];
    });
}

- (void)layoutSubviews {
    [super layoutSubviews];
    _eglWidth = (int)(CGRectGetWidth(self.bounds) * self.contentScaleFactor);
    _eglHeight = (int)(CGRectGetHeight(self.bounds) * self.contentScaleFactor);
    if (_eglWidth > 32 && _eglHeight > 32) {
        gLastEglW = _eglWidth;
        gLastEglH = _eglHeight;
        [self rebuildVertexBufferWithCenterCrop];
    }
    if (!_glReady) {
        [self setupGLIfNeeded];
    }
    [self ensurePreviewStartedIfReady];
}

- (void)ensurePreviewStartedIfReady {
    if (_destroying || _stopped || !self.window) {
        return;
    }
    if (!_glReady) {
        [self setupGLIfNeeded];
    }
    if (_glReady && !_previewStarted) {
        [self startPreview];
    }
}

- (void)didMoveToWindow {
    [super didMoveToWindow];
    // soft-hide / pause 后页面转场仍可能触发 didMoveToWindow；勿把冻帧重新露出来
    if (self.window && !_destroying && !_stopped && !_softHidden) {
        self.hidden = NO;
        [self display];
        [self ensurePreviewStartedIfReady];
    }
}

#pragma mark - 生命周期（对齐Android：
- (void)hidePreview {
    _softHidden = YES;
    self.hidden = YES;
    self.alpha = 0;
    @synchronized (_frameLock) {
        _frameReady = NO;
        if (_pendingPixelBuffer) {
            CVPixelBufferRelease(_pendingPixelBuffer);
            _pendingPixelBuffer = NULL;
        }
    }
    // 进后台停采集，避免回前台卡死且FPS/rendertime 不再更新
    [self stopPreview];
    [BeautyCameraView resetSessionDiag];
    FU_LOG("hidePreview stopped softHidden=1");
}

- (void)resumePreview {
    _softHidden = NO;
    self.alpha = 1;
    self.hidden = NO;
    _stopped = NO;
    _destroying = NO;
    [self display];
    if (!_previewStarted) {
        [self startPreview];
    }
    FU_LOG("resumePreview started=%@", _previewStarted ? @"true" : @"false");
}

- (BOOL)isSoftHidden {
    return _softHidden;
}

- (void)releaseCameraKeepAlive:(void (^)(void))onFinished {
    _previewStarted = NO;
    gLastPreviewStarted = NO;
    self.hidden = NO;
    self.releaseFinishedCallback = onFinished;
    @synchronized (_frameLock) {
        _frameReady = NO;
        if (_pendingPixelBuffer) {
            CVPixelBufferRelease(_pendingPixelBuffer);
            _pendingPixelBuffer = NULL;
        }
    }
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
    self.releaseFinishedCallback = onFinished;
    _previewStarted = NO;
    gLastPreviewStarted = NO;
    @synchronized (_frameLock) {
        _frameReady = NO;
        if (_pendingPixelBuffer) {
            CVPixelBufferRelease(_pendingPixelBuffer);
            _pendingPixelBuffer = NULL;
        }
    }
    _cachedFuOutW = 0;
    _cachedFuOutH = 0;
    [self cancelVideoRecord];
    [self stopPreview];
    [self teardownGL];
    if (onFinished) {
        dispatch_async(dispatch_get_main_queue(), onFinished);
    }
}

- (void)switchCameraFacing {
    [self switchCameraFacingWithCompletion:nil];
}

- (void)switchCameraFacingWithCompletion:(void (^)(NSError *error))completion {
    if (_destroying || _stopped) {
        if (completion) {
            completion([NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                     userInfo:@{NSLocalizedDescriptionKey: @"相机已停止"}]);
        }
        return;
    }
    if (_recordingVideo) {
        if (completion) {
            completion([NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                     userInfo:@{NSLocalizedDescriptionKey: @"录制中无法切换摄像头"}]);
        }
        return;
    }
    BOOL nextFront = !_useFrontCamera;
    AVCaptureSession *session = _session;
    if (!session || !_previewStarted) {
        // 会话未就绪：回退整段重建
        _useFrontCamera = nextFront;
        self.exposureLockedByUser = NO;
        self.lastExposureNormalized = 0.5;
        if (fuIsLibraryInit()) {
            FuSetBeautyChangeFramesHoldZero(YES);
            int bh = FuBeautyCameraHandle > 0 ? FuBeautyCameraHandle : FuBeautyItemHandle;
            if (bh > 0) {
                fuItemSetParamd(bh, "change_frames", 0.0);
            }
            fuOnCameraChange();
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1.0 * NSEC_PER_SEC)),
                           dispatch_get_main_queue(), ^{
                FuSetBeautyChangeFramesHoldZero(NO);
                if (fuIsLibraryInit() && FuBeautyCameraHandle > 0) {
                    [BeautyCameraView enqueueBeautyParam:FuBeautyCameraHandle
                                                    name:@"change_frames"
                                                   value:12.0];
                }
            });
        }
        [self restartPreview];
        if (completion) {
            dispatch_async(dispatch_get_main_queue(), ^{
                completion(nil);
            });
        }
        return;
    }

    dispatch_async(_captureQueue, ^{
        AVCaptureDevicePosition position = nextFront
            ? AVCaptureDevicePositionFront
            : AVCaptureDevicePositionBack;
        AVCaptureDevice *device = nil;
        if (@available(iOS 10.0, *)) {
            AVCaptureDeviceDiscoverySession *discovery = [AVCaptureDeviceDiscoverySession
                discoverySessionWithDeviceTypes:@[AVCaptureDeviceTypeBuiltInWideAngleCamera]
                mediaType:AVMediaTypeVideo
                position:position];
            device = discovery.devices.firstObject;
        }
        if (!device) {
            dispatch_async(dispatch_get_main_queue(), ^{
                if (completion) {
                    completion([NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                             userInfo:@{NSLocalizedDescriptionKey: @"未找到摄像头"}]);
                }
            });
            return;
        }
        NSError *inputErr = nil;
        AVCaptureDeviceInput *newInput = [AVCaptureDeviceInput deviceInputWithDevice:device error:&inputErr];
        if (!newInput || inputErr) {
            dispatch_async(dispatch_get_main_queue(), ^{
                if (completion) {
                    completion(inputErr ?: [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                                         userInfo:@{NSLocalizedDescriptionKey: @"相机输入创建失败"}]);
                }
            });
            return;
        }

        [session beginConfiguration];
        for (AVCaptureInput *old in session.inputs) {
            [session removeInput:old];
        }
        if (![session canAddInput:newInput]) {
            [session commitConfiguration];
            dispatch_async(dispatch_get_main_queue(), ^{
                // 回退：整段重建
                self.useFrontCamera = nextFront;
                [self restartPreview];
                if (completion) {
                    completion(nil);
                }
            });
            return;
        }
        [session addInput:newInput];

        AVCaptureDeviceFormat *format = [self choosePreviewFormatForDevice:device];
        if (format) {
            @try {
                [device lockForConfiguration:nil];
                device.activeFormat = format;
                for (AVFrameRateRange *range in format.videoSupportedFrameRateRanges) {
                    if (range.minFrameRate <= 30.0 + 0.01 && range.maxFrameRate >= 30.0 - 0.01) {
                        device.activeVideoMinFrameDuration = CMTimeMake(1, 30);
                        device.activeVideoMaxFrameDuration = CMTimeMake(1, 30);
                        break;
                    }
                }
                [device unlockForConfiguration];
            } @catch (NSException *exception) {
                FU_LOG("switchCamera activeFormat warn: %@", exception.reason);
            }
        }
        [self applyDefaultCaptureDeviceSettings:device];

        AVCaptureConnection *connection = [self.videoOutput connectionWithMediaType:AVMediaTypeVideo];
        if (connection.isVideoOrientationSupported) {
            connection.videoOrientation = AVCaptureVideoOrientationPortrait;
        }
        if (connection.isVideoMirroringSupported) {
            connection.videoMirrored = NO;
        }
        [session commitConfiguration];

        CMVideoDimensions chosen = format
            ? CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            : (CMVideoDimensions){ gTargetPreviewW, gTargetPreviewH };
        int fw = chosen.width > 0 ? chosen.width : gTargetPreviewW;
        int fh = chosen.height > 0 ? chosen.height : gTargetPreviewH;

        dispatch_async(dispatch_get_main_queue(), ^{
            self.useFrontCamera = nextFront;
            self.exposureLockedByUser = NO;
            self.lastExposureNormalized = 0.5;
            self.lastFaceExposurePoi = CGPointMake(-1, -1);
            if (self.exposureRail) {
                self.exposureRail.progress = 0.5;
            }
            @synchronized (self.frameLock) {
                self.frameReady = NO;
                if (self->_pendingPixelBuffer) {
                    CVPixelBufferRelease(self->_pendingPixelBuffer);
                    self->_pendingPixelBuffer = NULL;
                }
            }
            if (fuIsLibraryInit()) {
                // 切摄期间强制 hold change_frames=0，防止 enableAdvanced* 写回 12 导致美型淡入
                FuSetBeautyChangeFramesHoldZero(YES);
                int bh = FuBeautyCameraHandle > 0 ? FuBeautyCameraHandle : FuBeautyItemHandle;
                if (bh > 0) {
                    fuItemSetParamd(bh, "change_frames", 0.0);
                }
                fuOnCameraChange();
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1.0 * NSEC_PER_SEC)),
                               dispatch_get_main_queue(), ^{
                    FuSetBeautyChangeFramesHoldZero(NO);
                    if (fuIsLibraryInit() && FuBeautyCameraHandle > 0) {
                        [BeautyCameraView enqueueBeautyParam:FuBeautyCameraHandle
                                                        name:@"change_frames"
                                                       value:12.0];
                    }
                });
            }
            self.frameWidth = fw;
            self.frameHeight = fh;
            self.cachedFuOutW = 0;
            self.cachedFuOutH = 0;
            [self applyInputCameraMatrix];
            [self updateOutputResolutionIfNeeded];
            [self rebuildVertexBufferWithCenterCrop];
            FU_LOG("switchCameraFacing ok front=%d %dx%d", nextFront ? 1 : 0, fw, fh);
            if (completion) {
                completion(nil);
            }
        });
    });
}

- (void)restartPreview {
    _previewStarted = NO;
    _cameraConfigured = NO;
    gLastPreviewStarted = NO;
    @synchronized (_frameLock) {
        _frameReady = NO;
        if (_pendingPixelBuffer) {
            CVPixelBufferRelease(_pendingPixelBuffer);
            _pendingPixelBuffer = NULL;
        }
    }
    [self stopPreview];
    _stopped = NO;
    _cachedFuOutW = 0;
    _cachedFuOutH = 0;
    [self startPreview];
}

- (void)capturePhoto:(void (^)(NSString *path, NSError *error))callback {
    if (!callback) {
        return;
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        // 等下一帧真实相机缓冲再抓，避免空FBO / 黑图；勿强行 display 空帧
        self.captureFinished = callback;
        self.capturePending = YES;
    });
}

- (void)configureAudioSessionForRecording {
    AVAudioSession *session = [AVAudioSession sharedInstance];
    NSError *err = nil;
    [session setCategory:AVAudioSessionCategoryPlayAndRecord
             withOptions:(AVAudioSessionCategoryOptionDefaultToSpeaker |
                        AVAudioSessionCategoryOptionAllowBluetooth)
                   error:&err];
    if (err) {
        FU_LOG("audio session category warn: %@", err.localizedDescription);
        err = nil;
    }
    [session setMode:AVAudioSessionModeVideoRecording error:&err];
    if (err) {
        FU_LOG("audio session mode warn: %@", err.localizedDescription);
        err = nil;
    }
    [session setActive:YES error:&err];
    if (err) {
        FU_LOG("audio session active warn: %@", err.localizedDescription);
    }
}

- (void)ensureAudioCaptureAttachedForRecording {
    if (!_session) {
        return;
    }
    BOOL running = _session.isRunning;
    if (running) {
        [_session beginConfiguration];
    }
    [self attachAudioCaptureIfAuthorized];
    if (running) {
        [_session commitConfiguration];
    }
}

- (void)clearPendingAudioSamples {
    if (_pendingAudioSamples.count == 0) {
        return;
    }
    for (NSValue *boxed in _pendingAudioSamples) {
        CMSampleBufferRef sb = (CMSampleBufferRef)boxed.pointerValue;
        if (sb) {
            CFRelease(sb);
        }
    }
    [_pendingAudioSamples removeAllObjects];
}

- (BOOL)appendAudioSampleBuffer:(CMSampleBufferRef)sampleBuffer {
    if (!sampleBuffer || !_audioWriterInput || !_assetWriter || !_recordSessionStarted) {
        return NO;
    }
    if (_assetWriter.status != AVAssetWriterStatusWriting) {
        return NO;
    }
    CMTime relPts = CMTimeMakeWithSeconds(
        MAX(0.0, CFAbsoluteTimeGetCurrent() - _recordStartTime), 44100);
    CMSampleTimingInfo timing;
    timing.duration = CMSampleBufferGetDuration(sampleBuffer);
    timing.presentationTimeStamp = relPts;
    timing.decodeTimeStamp = kCMTimeInvalid;
    CMSampleBufferRef adjusted = NULL;
    OSStatus st = CMSampleBufferCreateCopyWithNewTiming(
        kCFAllocatorDefault, sampleBuffer, 1, &timing, &adjusted);
    if (st != noErr || !adjusted) {
        return NO;
    }
    BOOL ok = NO;
    if (_audioWriterInput.isReadyForMoreMediaData) {
        ok = [_audioWriterInput appendSampleBuffer:adjusted];
    }
    CFRelease(adjusted);
    return ok;
}

- (void)flushPendingAudioSamples {
    if (_pendingAudioSamples.count == 0 || !_audioWriterInput || !_recordSessionStarted) {
        return;
    }
    NSMutableArray<NSValue *> *remain = [NSMutableArray array];
    for (NSValue *boxed in _pendingAudioSamples) {
        CMSampleBufferRef sb = (CMSampleBufferRef)boxed.pointerValue;
        if (!sb) {
            continue;
        }
        if ([self appendAudioSampleBuffer:sb]) {
            CFRelease(sb);
        } else if (_audioWriterInput.isReadyForMoreMediaData) {
            CFRelease(sb);
        } else {
            [remain addObject:boxed];
        }
    }
    _pendingAudioSamples = remain;
}

- (BOOL)startVideoRecord:(NSError **)error {
    if (_recordingVideo) {
        return YES;
    }
    [self configureAudioSessionForRecording];
    dispatch_sync(_captureQueue, ^{
        [self ensureAudioCaptureAttachedForRecording];
    });
    // 按所选预览分辨率（相机buffer）编码，不用屏幕 drawable 尺寸
    int w = MAX(16, _frameWidth & ~1);
    int h = MAX(16, _frameHeight & ~1);
    if (w < 16 || h < 16) {
        w = MAX(16, gTargetPreviewW & ~1);
        h = MAX(16, gTargetPreviewH & ~1);
        //  target 是横屏约定(1280x720)，竖屏预览帧通常高定
        if (_layoutHeight > _layoutWidth && w > h) {
            int tmp = w;
            w = h;
            h = tmp;
        }
    }
    if (w < 16 || h < 16) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"预览未就绪，请稍后再试" }];
        }
        return NO;
    }

    NSString *path = [NSTemporaryDirectory() stringByAppendingPathComponent:
        [NSString stringWithFormat:@"fu_rec_%.0f.mp4", [[NSDate date] timeIntervalSince1970] * 1000]];
    [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
    NSURL *url = [NSURL fileURLWithPath:path];
    NSError *writerErr = nil;
    AVAssetWriter *writer = [[AVAssetWriter alloc] initWithURL:url fileType:AVFileTypeMPEG4 error:&writerErr];
    if (!writer || writerErr) {
        if (error) {
            *error = writerErr ?: [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                               userInfo:@{NSLocalizedDescriptionKey: @"创建录像失败"}];
        }
        return NO;
    }
    NSDictionary *settings = @{
        AVVideoCodecKey: AVVideoCodecTypeH264,
        AVVideoWidthKey: @(w),
        AVVideoHeightKey: @(h),
        AVVideoCompressionPropertiesKey: @{
            AVVideoAverageBitRateKey: @(MAX(2000000, w * h * 4)),
            AVVideoProfileLevelKey: AVVideoProfileLevelH264BaselineAutoLevel,
        },
    };
    AVAssetWriterInput *input = [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeVideo
                                                                   outputSettings:settings];
    input.expectsMediaDataInRealTime = YES;
    NSDictionary *srcAttrs = @{
        (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
        (id)kCVPixelBufferWidthKey: @(w),
        (id)kCVPixelBufferHeightKey: @(h),
    };
    AVAssetWriterInputPixelBufferAdaptor *adaptor =
        [AVAssetWriterInputPixelBufferAdaptor assetWriterInputPixelBufferAdaptorWithAssetWriterInput:input
                                                                         sourcePixelBufferAttributes:srcAttrs];
    if (![writer canAddInput:input]) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                    userInfo:@{NSLocalizedDescriptionKey: @"无法添加视频轨" }];
        }
        return NO;
    }
    [writer addInput:input];

    // 有麦克风输入时加 AAC 音轨
    AVAssetWriterInput *audioIn = nil;
    if (_audioDeviceInput) {
        AudioChannelLayout acl;
        bzero(&acl, sizeof(acl));
        acl.mChannelLayoutTag = kAudioChannelLayoutTag_Mono;
        NSDictionary *audioSettings = @{
            AVFormatIDKey: @(kAudioFormatMPEG4AAC),
            AVSampleRateKey: @(48000),
            AVNumberOfChannelsKey: @(1),
            AVEncoderBitRateKey: @(96000),
            AVChannelLayoutKey: [NSData dataWithBytes:&acl length:sizeof(acl)],
        };
        audioIn = [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeAudio
                                                      outputSettings:audioSettings];
        audioIn.expectsMediaDataInRealTime = YES;
        if ([writer canAddInput:audioIn]) {
            [writer addInput:audioIn];
        } else {
            audioIn = nil;
        }
    }

    if (![writer startWriting]) {
        if (error) {
            *error = writer.error ?: [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                                  userInfo:@{NSLocalizedDescriptionKey: @"开始写入失败" }];
        }
        return NO;
    }
    // 等第一帧再 startSession，音画用真实 PTS
    _assetWriter = writer;
    _writerInput = input;
    _audioWriterInput = audioIn;
    _pixelAdaptor = adaptor;
    _recordSessionStarted = NO;
    _recordWidth = w;
    _recordHeight = h;
    _recordFrameIndex = 0;
    _recordStartTime = 0;
    _recordAudioBaseValid = NO;
    _recordAudioBasePts = kCMTimeInvalid;
    _pendingAudioSamples = [NSMutableArray array];
    _recordTempPath = path;
    _recordingVideo = YES;
    _recordAutoStopping = NO;
    // 录制前再尝试挂上音频（用户可能刚授权）
    [self requestMicrophoneIfNeeded];
    FU_LOG("startVideoRecord %dx%d audio=%d -> %@",
           w, h, audioIn ? 1 : 0, path);
    return YES;
}

- (void)cancelVideoRecord {
    _recordingVideo = NO;
    void (^cb)(NSString *, NSError *) = _recordFinished;
    _recordFinished = nil;
    AVAssetWriter *writer = _assetWriter;
    _assetWriter = nil;
    _writerInput = nil;
    _audioWriterInput = nil;
    _pixelAdaptor = nil;
    _recordSessionStarted = NO;
    _recordAudioBaseValid = NO;
    [self clearPendingAudioSamples];
    _pendingAudioSamples = nil;
    NSString *path = _recordTempPath;
    _recordTempPath = nil;
    if (writer) {
        [writer cancelWriting];
    }
    if (path.length > 0) {
        [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
    }
    if (cb) {
        cb(nil, [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                               userInfo:@{NSLocalizedDescriptionKey: @"录制已取消" }]);
    }
}

- (void)stopVideoRecord:(void (^)(NSString *path, NSError *error))callback {
    // 允许「UI 认为在录但原生已清」时仍能收尾，避免长按松手卡在录制中
    BOOL wasRecording = _recordingVideo;
    AVAssetWriter *writer = _assetWriter;
    AVAssetWriterInput *input = _writerInput;
    AVAssetWriterInput *audioIn = _audioWriterInput;
    NSString *path = [_recordTempPath copy];
    int frames = _recordFrameIndex;
    _recordingVideo = NO;
    _recordAutoStopping = NO;
    _assetWriter = nil;
    _writerInput = nil;
    _audioWriterInput = nil;
    _pixelAdaptor = nil;
    _recordSessionStarted = NO;
    _recordAudioBaseValid = NO;
    [self clearPendingAudioSamples];
    _pendingAudioSamples = nil;
    _recordTempPath = nil;
    _recordFinished = nil;
    void (^done)(NSString *, NSError *) = [callback copy];

    if (!wasRecording && !writer) {
        // 幂等：重复 stop 当作成功，减少「未在录制」误报
        if (done) {
            done(@"", nil);
        }
        return;
    }
    if (!writer || !input) {
        if (path.length > 0) {
            [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
        }
        if (done) {
            done(nil, [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                   userInfo:@{NSLocalizedDescriptionKey: @"录像器无效" }]);
        }
        return;
    }
    if (frames <= 0) {
        [writer cancelWriting];
        if (path.length > 0) {
            [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
        }
        if (done) {
            done(nil, [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                   userInfo:@{NSLocalizedDescriptionKey: @"录制时间过短，请长按再试" }]);
        }
        return;
    }
    [input markAsFinished];
    if (audioIn) {
        [audioIn markAsFinished];
    }
    __weak typeof(self) weakSelf = self;
    [writer finishWritingWithCompletionHandler:^{
        if (writer.status != AVAssetWriterStatusCompleted || path.length == 0) {
            if (done) {
                done(nil, writer.error ?: [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                                       userInfo:@{NSLocalizedDescriptionKey: @"结束录像失败"}]);
            }
            if (path.length > 0) {
                [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
            }
            return;
        }
        __strong typeof(weakSelf) self = weakSelf;
        if (self) {
            [self saveRecordedVideoAtPath:path completion:done];
        } else if (done) {
            done(nil, [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                   userInfo:@{NSLocalizedDescriptionKey: @"相机已释放" }]);
            [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
        }
    }];
}

- (void)appendVideoFrameFromTexture:(unsigned int)texId width:(int)width height:(int)height {
    if (!_recordingVideo || !_writerInput || !_pixelAdaptor || !_assetWriter || texId == 0) {
        return;
    }
    int w = width & ~1;
    int h = height & ~1;
    if (w < 16 || h < 16) {
        return;
    }
    // 分辨率变化时跳过，避免编码器崩溃
    if (w != _recordWidth || h != _recordHeight) {
        return;
    }
    if (!_writerInput.readyForMoreMediaData) {
        return;
    }
    if (_assetWriter.status != AVAssetWriterStatusWriting) {
        return;
    }

    unsigned char *rgba = [self copyRGBAFromTexture:texId width:w height:h];
    if (!rgba) {
        return;
    }

    CVPixelBufferRef px = NULL;
    CVReturn cvRet = CVPixelBufferPoolCreatePixelBuffer(NULL, _pixelAdaptor.pixelBufferPool, &px);
    if (cvRet != kCVReturnSuccess || !px) {
        CVPixelBufferCreate(kCFAllocatorDefault, w, h, kCVPixelFormatType_32BGRA, NULL, &px);
    }
    if (!px) {
        free(rgba);
        return;
    }
    CVPixelBufferLockBaseAddress(px, 0);
    unsigned char *dst = (unsigned char *)CVPixelBufferGetBaseAddress(px);
    size_t dstStride = CVPixelBufferGetBytesPerRow(px);
    size_t srcStride = (size_t)w * 4;
    // copyRGBAFromTexture 已翻成顶左；RGBA →BGRA
    for (int row = 0; row < h; row++) {
        unsigned char *srcRow = rgba + (size_t)row * srcStride;
        unsigned char *dstRow = dst + (size_t)row * dstStride;
        for (int col = 0; col < w; col++) {
            int si = col * 4;
            int di = col * 4;
            dstRow[di + 0] = srcRow[si + 2];
            dstRow[di + 1] = srcRow[si + 1];
            dstRow[di + 2] = srcRow[si + 0];
            dstRow[di + 3] = 255;
        }
    }
    CVPixelBufferUnlockBaseAddress(px, 0);
    free(rgba);

    CFAbsoluteTime now = CFAbsoluteTimeGetCurrent();
    if (_recordStartTime <= 0) {
        _recordStartTime = now;
    }
    // 视频时间轴从 0 起；勿用相机时钟绝对 PTS（会和音轨错位）
    CMTime pts = CMTimeMakeWithSeconds(MAX(0.0, now - _recordStartTime), 1000);
    if (!_recordSessionStarted) {
        [_assetWriter startSessionAtSourceTime:kCMTimeZero];
        _recordSessionStarted = YES;
        pts = kCMTimeZero;
        [self flushPendingAudioSamples];
    }
    if (![_pixelAdaptor appendPixelBuffer:px withPresentationTime:pts]) {
        FU_LOG("appendPixelBuffer failed status=%ld", (long)_assetWriter.status);
    } else {
        [self flushPendingAudioSamples];
    }
    _recordFrameIndex += 1;
    CVPixelBufferRelease(px);

    //  最长10 秒：通知宿主收尾，避免 JS/UI 仍认为在录导致「超时/未在录制」
    if (!_recordAutoStopping && (now - _recordStartTime) >= 10.0) {
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

- (void)saveRecordedVideoAtPath:(NSString *)path completion:(void (^)(NSString *, NSError *))completion {
    if (!completion) {
        return;
    }
    [self requestPhotoSaveAuthorization:^(BOOL granted) {
        if (!granted) {
            [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
            completion(nil, [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
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
                    completion(nil, error ?: [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                                          userInfo:@{NSLocalizedDescriptionKey: @"保存视频失败"}]);
                }
            });
        }];
    }];
}

#pragma mark - GL onSurfaceCreated

- (void)setupGLIfNeeded {
    if (_glReady) {
        return;
    }
    [EAGLContext setCurrentContext:self.context];
    //  纹理 blit 路径必须与GLKView 共享 context，否则FU 生成的纹理采样为黑
    @synchronized (FUGLLock()) {
        if (!gFuGLContextReady) {
            // 用全局 sharegroup 的root context 初始化FU，跨页面稳定
            EAGLContext *shareCtx = gSharedGLContext ?: self.context;
            if (shareCtx) {
                [EAGLContext setCurrentContext:shareCtx];
            }
            void *fuCtx = fuInitGLContext((__bridge void *)shareCtx);
            if (!fuCtx && shareCtx) {
                // 禁止 fuInitGLContext(NULL)：会脱离 sharegroup，导入页污染后相机白屏闪退
                FU_LOG("fuInitGLContext(shared) failed, continue with view context");
            }
            if (fuCtx) {
                gFuGLContextReady = YES;
                FU_LOG("fuInitGLContext shared with global sharegroup");
            } else {
                FU_LOG("fuInitGLContext failed, continue with view context");
            }
            [EAGLContext setCurrentContext:self.context];
        } else {
            fuMakeGLContextCurrent();
            [EAGLContext setCurrentContext:self.context];
            FU_LOG("reuse existing fu GL context");
        }
    }
    CVReturn cacheRet = CVOpenGLESTextureCacheCreate(
        kCFAllocatorDefault,
        NULL,
        self.context,
        NULL,
        &_textureCache
    );
    if (cacheRet != kCVReturnSuccess) {
        gLastError = @"CVOpenGLESTextureCache 创建失败";
        return;
    }

    _renderProgram = [self buildProgram];
    if (_renderProgram == 0) {
        gLastError = @"GL program 创建失败";
        return;
    }
    _positionAttr = (GLuint)glGetAttribLocation(_renderProgram, "aPosition");
    _texCoordAttr = (GLuint)glGetAttribLocation(_renderProgram, "aTexCoord");
    _textureUniform = (GLuint)glGetUniformLocation(_renderProgram, "uTexture");
    _mirrorUniform = (GLuint)glGetUniformLocation(_renderProgram, "uMirror");
    _mirrorYUniform = (GLuint)glGetUniformLocation(_renderProgram, "uMirrorY");

    glGenTextures(1, &_outputTexture);
    glBindTexture(GL_TEXTURE_2D, _outputTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    glClearColor(0.f, 0.f, 0.f, 1.f);
    fuSetForceUseGL2(1);
    _cachedFuOutW = 0;
    _cachedFuOutH = 0;
    if (fuIsLibraryInit() != 0 && FuBeautyCameraHandle > 0) {
        //  勿强制enable_skinseg=0 / gBeautyEnabled：会冲掉「仅皮肤」与对比态
        if (gBeautyEnabled) {
            fuItemSetParamd(FuBeautyCameraHandle, "is_beauty_on", 1.0);
        } else {
            fuItemSetParamd(FuBeautyCameraHandle, "is_beauty_on", 0.0);
        }
        // 勿覆盖FuEnableAdvancedBeautyRuntime 的blur_type/delspot/plump
        FuSetAdvancedBeautyGlReady(YES);
        FuEnableAdvancedBeautyRuntime(FuBeautyCameraHandle);
        fuOnCameraChange();
        FU_LOG("setupGL restore beauty on=%.1f blur=%.2f skinseg=%.1f",
               fuItemGetParamd(FuBeautyCameraHandle, "is_beauty_on"),
               fuItemGetParamd(FuBeautyCameraHandle, "blur_level"),
               fuItemGetParamd(FuBeautyCameraHandle, "enable_skinseg"));
    }

    _glReady = YES;
    gLastError = @"";
    [self rebuildVertexBufferWithCenterCrop];
    FU_LOG("onSurfaceCreated beautyHandle=%d", FuBeautyCameraHandle);
    [self ensurePreviewStartedIfReady];
}

- (void)teardownGL {
    if (!_glReady) {
        return;
    }
    FuSetAdvancedBeautyGlReady(NO);
    [self hideFocusChrome];
    [EAGLContext setCurrentContext:self.context];
    //  注意：离开美颜页只销毁本 View 的GL 资源，不要fuOnDeviceLost / fuDestroyGLContext。    // 否则同一次App 会话再次进入时beauty handle 仍在，但 GPU 状态已坏→机tracking 无美颜。
    if (_outputTexture) {
        glDeleteTextures(1, &_outputTexture);
        _outputTexture = 0;
    }
    if (_renderProgram) {
        glDeleteProgram(_renderProgram);
        _renderProgram = 0;
    }
    if (_textureCache) {
        CVOpenGLESTextureCacheFlush(_textureCache, 0);
        CFRelease(_textureCache);
        _textureCache = NULL;
    }
    _glReady = NO;
}

#pragma mark - Draw helpers

- (void)bindViewFramebufferWithViewport:(int)eglW height:(int)eglH {
    [EAGLContext setCurrentContext:self.context];
    [self bindDrawable];
    glViewport(0, 0, eglW, eglH);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
}

#pragma mark - onDrawFrame（对齐Android BeautyCameraGLView.onDrawFrame）
- (void)glkView:(GLKView *)view drawInRect:(CGRect)rect {
    (void)view;
    (void)rect;
    if (!_glReady || _stopped || _destroying || _softHidden) {
        return;
    }

    int eglW = (int)self.drawableWidth;
    int eglH = (int)self.drawableHeight;
    _eglWidth = eglW;
    _eglHeight = eglH;
    gLastEglW = eglW;
    gLastEglH = eglH;
    if (eglW < 32 || eglH < 32) {
        return;
    }

    CVPixelBufferRef pixelBuffer = NULL;
    int width = 0;
    int height = 0;
    int currentFrameId = 0;
    @synchronized (_frameLock) {
        if (!_frameReady || !_pendingPixelBuffer) {
            return;
        }
        pixelBuffer = CVPixelBufferRetain(_pendingPixelBuffer);
        width = _frameWidth;
        height = _frameHeight;
        currentFrameId = _frameId;
        // 消费本帧，避免主线程积压多次 display 时反复 fuRender 同一/多帧把 UI 卡死
        _frameReady = NO;
    }
    if (!pixelBuffer) {
        return;
    }

    int bufW = (int)CVPixelBufferGetWidth(pixelBuffer);
    int bufH = (int)CVPixelBufferGetHeight(pixelBuffer);
    if (bufW > 0 && bufH > 0) {
        width = bufW;
        height = bufH;
        if (_frameWidth != bufW || _frameHeight != bufH) {
            _frameWidth = bufW;
            _frameHeight = bufH;
            [self updateOutputResolutionIfNeeded];
            [self rebuildVertexBufferWithCenterCrop];
        }
    }
    if (width <= 0 || height <= 0) {
        CVPixelBufferRelease(pixelBuffer);
        return;
    }

    gLastFrameW = width;
    gLastFrameH = height;

    if (fuIsLibraryInit() == 0) {
        CVPixelBufferRelease(pixelBuffer);
        return;
    }

    [self updateOutputResolutionIfNeeded];

    int beautyHandle = FuBeautyCameraHandle;
    gLastBeautyHandle = beautyHandle;

    int previewTexId = 0;
    int fuTexId = 0;
    int ret = 0;
    CVOpenGLESTextureRef bgraTextureRef = NULL;
    gLastDrawPath = @"";

    @try {
        // 必须可写：BGRA 原地美颜要回写；ReadOnly 时常见tracking 成功但像素未改→看起来没美颜
        CVPixelBufferLockBaseAddress(pixelBuffer, 0);

        CVReturn texRet = CVOpenGLESTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault,
            _textureCache,
            pixelBuffer,
            NULL,
            GL_TEXTURE_2D,
            GL_RGBA,
            bufW,
            bufH,
            GL_BGRA,
            GL_UNSIGNED_BYTE,
            0,
            &bgraTextureRef
        );
        previewTexId = (texRet == kCVReturnSuccess && bgraTextureRef)
            ? (int)CVOpenGLESTextureGetName(bgraTextureRef)
            : 0;
        gLastPreviewTexId = previewTexId;

        if (previewTexId <= 0) {
            gLastError = @"相机纹理创建失败";
            CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
            CVPixelBufferRelease(pixelBuffer);
            if (bgraTextureRef) {
                CFRelease(bgraTextureRef);
            }
            return;
        }

        GLenum texTarget = CVOpenGLESTextureGetTarget(bgraTextureRef);
        glBindTexture(texTarget, (GLuint)previewTexId);
        glTexParameteri(texTarget, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(texTarget, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(texTarget, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(texTarget, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        void *baseAddr = CVPixelBufferGetBaseAddress(pixelBuffer);
        int stride = (int)CVPixelBufferGetBytesPerRow(pixelBuffer);
        unsigned char *packedBGRA = NULL;

        //  对比只关 is_beauty_on，始终走同一 FU 渲染路径，避免切到相机纹理后上下颠倒
        if (beautyHandle > 0 && baseAddr && width > 0 && height > 0) {
            int items[1] = { beautyHandle };
            // BGRA CPU 输入（检脸美型与像素同一缓冲）→ RGBA 纹理输出
            //  避免 glTexImage2D 上传顶左 BGRA 造成的Y 颠倒，也避免dual-input CPU/GPU 朝向不一致
            void *fuInput = baseAddr;
            if (stride != width * 4) {
                size_t packedSize = (size_t)width * (size_t)height * 4;
                packedBGRA = (unsigned char *)malloc(packedSize);
                if (packedBGRA) {
                    for (int row = 0; row < height; row++) {
                        memcpy(packedBGRA + (size_t)row * (size_t)width * 4,
                               (unsigned char *)baseAddr + (size_t)row * (size_t)stride,
                               (size_t)width * 4);
                    }
                    fuInput = packedBGRA;
                }
            }
            unsigned int outTex = 0;
            [EAGLContext setCurrentContext:self.context];
            fuMakeGLContextCurrent();
            // 对齐 Android runOnNamaGl：特殊算法写参在 fuRender 前落地
            [BeautyCameraView flushPendingBeautyParams];
            FuReconfirmSpecialBeautySwitches(beautyHandle);
            // 切摄 hold 期间每帧强制 change_frames=0，对抗 enableAdvanced* 写回 12
            if (FuBeautyChangeFramesHoldZero() && beautyHandle > 0) {
                fuItemSetParamd(beautyHandle, "change_frames", 0.0);
            }
            // body_blur 变更后立刻按新值重算 mask，避免有人脸时 mask 与身体磨皮错位乱跳
            FuUpdateBeautyBlurEffect(beautyHandle);
            CFAbsoluteTime renderStart = CFAbsoluteTimeGetCurrent();
            // RGBA 纹理（对齐 Android GPU 路径）+ FORCE_OUTPUT_ALPHA_ONE 抑制全身磨皮半透明灰蒙
            // 勿优先 BGRA 全图 CPU 翻转：会在主线程把 UI/触摸卡死
            int renderFlags = NAMA_RENDER_FEATURE_FULL | NAMA_RENDER_OPTION_FORCE_OUTPUT_ALPHA_ONE;
            ret = fuRender(
                FU_FORMAT_RGBA_TEXTURE,
                &outTex,
                FU_FORMAT_BGRA_BUFFER,
                fuInput,
                width,
                height,
                currentFrameId,
                items,
                1,
                renderFlags,
                NULL
            );
            gLastRenderTimeMs = (int)MAX(0, (CFAbsoluteTimeGetCurrent() - renderStart) * 1000.0);
            gLastOutTexId = (int)outTex;
            if (outTex > 0 && ret > 0 && fuGetSystemError() == 0) {
                fuTexId = (int)outTex;
            } else {
                // 回退：BGRA 原地 + 上传时按行翻转，抵消 glTexImage2D 底左原点
                ret = fuRender(
                    FU_FORMAT_BGRA_BUFFER,
                    fuInput,
                    FU_FORMAT_BGRA_BUFFER,
                    fuInput,
                    width,
                    height,
                    currentFrameId,
                    items,
                    1,
                    renderFlags,
                    NULL
                );
                if (ret >= 0 && fuGetSystemError() == 0) {
                    size_t rowBytes = (size_t)width * 4;
                    size_t imgBytes = rowBytes * (size_t)height;
                    unsigned char *flippedUpload = (unsigned char *)malloc(imgBytes);
                    if (flippedUpload) {
                        for (int row = 0; row < height; row++) {
                            memcpy(flippedUpload + (size_t)row * rowBytes,
                                   (unsigned char *)fuInput + (size_t)(height - 1 - row) * rowBytes,
                                   rowBytes);
                        }
                    }
                    const void *uploadPtr = flippedUpload ? flippedUpload : fuInput;
                    glBindTexture(GL_TEXTURE_2D, _outputTexture);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                    glTexImage2D(
                        GL_TEXTURE_2D,
                        0,
                        GL_RGBA,
                        width,
                        height,
                        0,
                        GL_BGRA,
                        GL_UNSIGNED_BYTE,
                        uploadPtr
                    );
                    if (flippedUpload) {
                        free(flippedUpload);
                    }
                    fuTexId = (int)_outputTexture;
                    gLastOutTexId = fuTexId;
                    if (ret == 0) {
                        ret = 1;
                    }
                } else {
                    fuTexId = 0;
                }
            }
        }

        if (packedBGRA) {
            free(packedBGRA);
            packedBGRA = NULL;
        }

        gLastTracking = fuIsTracking();
        gLastFuSysErr = fuGetSystemError();
        gLastRenderRet = ret;
        gLastFuTexId = fuTexId;
        gLastPreviewTexId = previewTexId;
        [self updateFaceAnchoredExposureIfNeededWithFrameWidth:width height:height];
        if (beautyHandle > 0) {
            double blur = fuItemGetParamd(beautyHandle, "blur_level");
            double on = fuItemGetParamd(beautyHandle, "is_beauty_on");
            if (currentFrameId % 30 == 1) {
                FU_LOG("beautyParams on=%.1f blur=%.2f handle=%d ret=%d fuTex=%d", on, blur, beautyHandle, ret, fuTexId);
            }
        }

        CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);

        // 美颜成功优先画FU/处理后纹理；失败再回退相机原图
        unsigned int displayTexId = (unsigned int)previewTexId;
        NSString *drawPath = @"camera-blit";
        if (fuTexId > 0 && ret > 0 && gLastFuSysErr == 0) {
            displayTexId = (unsigned int)fuTexId;
            drawPath = @"fu-blit";
        }

        [EAGLContext setCurrentContext:self.context];
        [self bindViewFramebufferWithViewport:eglW height:eglH];
        glClearColor(0.f, 0.f, 0.f, 1.f);
        glClear(GL_COLOR_BUFFER_BIT);
        [self drawTexture:displayTexId];

        gLastDrawPath = drawPath;
        gLastFuTexId = (int)displayTexId;
        gLastGlError = (int)glGetError();
        gRenderOkCount += 1;
        gLastError = @"";
        FuTickDisplayFps();

        // 首帧成功后开 SetUse*
        if (ret > 0) {
            int bh = beautyHandle > 0 ? beautyHandle : FuBeautyCameraHandle;
            FuTryApplyAdvancedBeautySetUseAfterRender(bh);
        }

        if (_capturePending) {
            _capturePending = NO;
            [self saveCapturedFrameFromTexture:displayTexId width:width height:height];
        }
        if (_recordingVideo) {
            [self appendVideoFrameFromTexture:displayTexId width:width height:height];
        }
    } @catch (NSException *exception) {
        gLastError = exception.reason ?: @"渲染异常";
        FU_LOG("render exception: %@", gLastError);
        @try {
            CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
        } @catch (__unused NSException *ignored) {
        }
    }

    if (bgraTextureRef) {
        CFRelease(bgraTextureRef);
    }
    CVOpenGLESTextureCacheFlush(_textureCache, 0);
    CVPixelBufferRelease(pixelBuffer);
}

- (float)mirrorFlipX {
    // FU BufferMatrix 已校正水平朝向；显示层不再做水平镜像
    return 0.f;
}

- (float)mirrorFlipY {
    //  FU 输出纹理 / CV 纹理相对屏幕为上下颠倒，前后置统一翻Y；勿改BufferMatrix（美型已正确）
    return 1.f;
}

- (void)drawTexture:(unsigned int)textureId {
    glUseProgram(_renderProgram);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, textureId);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glUniform1i(_textureUniform, 0);
    glUniform1f(_mirrorUniform, [self mirrorFlipX]);
    glUniform1f(_mirrorYUniform, [self mirrorFlipY]);

    //  竖屏缓冲：标准顶左UV（不再做 cw90）
    static const GLfloat kTexCoords[8] = {
        0.f, 1.f,
        1.f, 1.f,
        0.f, 0.f,
        1.f, 0.f,
    };

    glEnableVertexAttribArray(_positionAttr);
    glVertexAttribPointer(_positionAttr, 2, GL_FLOAT, GL_FALSE, 0, _quadVertices);
    glEnableVertexAttribArray(_texCoordAttr);
    glVertexAttribPointer(_texCoordAttr, 2, GL_FLOAT, GL_FALSE, 0, kTexCoords);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(_positionAttr);
    glDisableVertexAttribArray(_texCoordAttr);
}

- (BOOL)displayContentSize:(int *)outW height:(int *)outH {
    int fw = _frameWidth;
    int fh = _frameHeight;
    if (fw <= 0 || fh <= 0) {
        if (_cachedFuOutW <= 0 || _cachedFuOutH <= 0) {
            return NO;
        }
        fw = _cachedFuOutW;
        fh = _cachedFuOutH;
    }
    // 竖屏缓冲直接用真实宽高，不再强制对调
    outW[0] = fw;
    outH[0] = fh;
    return outW[0] > 0 && outH[0] > 0;
}

- (void)rebuildVertexBufferWithCenterCrop {
    static const GLfloat kFullQuad[8] = {
        -1.f, -1.f,
         1.f, -1.f,
        -1.f,  1.f,
         1.f,  1.f,
    };

    int vw = _eglWidth > 0 ? _eglWidth : _layoutWidth;
    int vh = _eglHeight > 0 ? _eglHeight : _layoutHeight;
    if (vw <= 0 || vh <= 0) {
        memcpy(_quadVertices, kFullQuad, sizeof(kFullQuad));
        return;
    }

    int contentW = 0;
    int contentH = 0;
    if (![self displayContentSize:&contentW height:&contentH]) {
        memcpy(_quadVertices, kFullQuad, sizeof(kFullQuad));
        return;
    }

    float viewAspect = (float)vw / (float)vh;
    float contentAspect = (float)contentW / (float)contentH;
    float sx = 1.f;
    float sy = 1.f;
    if (contentAspect > viewAspect) {
        sx = contentAspect / viewAspect;
    } else {
        sy = viewAspect / contentAspect;
    }

    _quadVertices[0] = -sx; _quadVertices[1] = -sy;
    _quadVertices[2] =  sx; _quadVertices[3] = -sy;
    _quadVertices[4] = -sx; _quadVertices[5] =  sy;
    _quadVertices[6] =  sx; _quadVertices[7] =  sy;
}

- (void)updateOutputResolutionIfNeeded {
    int w = _frameWidth;
    int h = _frameHeight;
    if (w <= 32 || h <= 32) {
        return;
    }
    w = w & ~1;
    h = h & ~1;
    if (w <= 32 || h <= 32 || (w == _cachedFuOutW && h == _cachedFuOutH)) {
        return;
    }
    _cachedFuOutW = w;
    _cachedFuOutH = h;
    gLastFuOutW = w;
    gLastFuOutH = h;
    fuSetOutputResolution(w, h);
    [self rebuildVertexBufferWithCenterCrop];
    FU_LOG("fuSetOutputResolution %dx%d", w, h);
}

- (AVCaptureDeviceFormat *)choosePreviewFormatForDevice:(AVCaptureDevice *)device {
    // JS 传入的是横屏标称尺寸（如 1920x1080）；设备 format 维度通常也是横屏标称
    int targetW = MAX(gTargetPreviewW, gTargetPreviewH);
    int targetH = MIN(gTargetPreviewW, gTargetPreviewH);
    AVCaptureDeviceFormat *best = nil;
    int bestDiff = INT_MAX;
    for (AVCaptureDeviceFormat *format in device.formats) {
        CMVideoDimensions dim = CMVideoFormatDescriptionGetDimensions(format.formatDescription);
        int dw = MAX(dim.width, dim.height);
        int dh = MIN(dim.width, dim.height);
        FourCharCode mediaSubType = CMFormatDescriptionGetMediaSubType(format.formatDescription);
        // 仅考虑常见视频输出格式，避免选到拍照/低帧率格式导致发糊
        BOOL videoLike = (mediaSubType == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange ||
                          mediaSubType == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange ||
                          mediaSubType == kCVPixelFormatType_32BGRA);
        if (!videoLike) {
            continue;
        }
        int diff = abs(dw - targetW) + abs(dh - targetH);
        if (diff < bestDiff) {
            bestDiff = diff;
            best = format;
        }
    }
    if (best) {
        CMVideoDimensions dim = CMVideoFormatDescriptionGetDimensions(best.formatDescription);
        FU_LOG("chooseFormat target=%dx%d matched=%dx%d diff=%d",
               targetW, targetH, dim.width, dim.height, bestDiff);
    }
    return best;
}

#pragma mark - Camera

- (void)startPreview {
    if (_previewStarted || _stopped || _destroying) {
        return;
    }
    if (!_glReady) {
        [self setupGLIfNeeded];
    }
    if (!_glReady) {
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
                    [self requestMicrophoneIfNeeded];
                } else {
                    gLastError = @"相机权限被拒绝";
                }
            });
        }];
        return;
    }
    [self configureCameraIfNeeded];
    // 首启并行申请麦克风，避免第一次录像无声
    [self requestMicrophoneIfNeeded];
}

- (void)stopPreview {
    _previewStarted = NO;
    gLastPreviewStarted = NO;
    _cameraConfigured = NO;
    if (_session) {
        AVCaptureSession *session = _session;
        dispatch_async(_captureQueue, ^{
            if (session.isRunning) {
                [session stopRunning];
            }
        });
    }
    _session = nil;
    _videoOutput = nil;
    _audioOutput = nil;
    _audioDeviceInput = nil;
    _cachedFuOutW = 0;
    _cachedFuOutH = 0;
}

- (void)configureCameraIfNeeded {
    if (_cameraConfigured || !_glReady || _stopped || _destroying) {
        return;
    }
    _cameraConfigured = YES;

    dispatch_async(_captureQueue, ^{
        @try {
            if (self.stopped || self.destroying) {
                self->_cameraConfigured = NO;
                return;
            }

            self.session = [[AVCaptureSession alloc] init];
            // 用activeFormat 控制分辨率；勿写死Preset1280x720，否则切换480/1080 无效
            if ([self.session canSetSessionPreset:AVCaptureSessionPresetInputPriority]) {
                self.session.sessionPreset = AVCaptureSessionPresetInputPriority;
            } else if ([self.session canSetSessionPreset:AVCaptureSessionPresetHigh]) {
                self.session.sessionPreset = AVCaptureSessionPresetHigh;
            }

            AVCaptureDevicePosition position = self.useFrontCamera
                ? AVCaptureDevicePositionFront
                : AVCaptureDevicePositionBack;
            AVCaptureDevice *device = nil;
            if (@available(iOS 10.0, *)) {
                AVCaptureDeviceDiscoverySession *discovery = [AVCaptureDeviceDiscoverySession
                    discoverySessionWithDeviceTypes:@[AVCaptureDeviceTypeBuiltInWideAngleCamera]
                    mediaType:AVMediaTypeVideo
                    position:position];
                device = discovery.devices.firstObject;
            }
            if (!device) {
                gLastError = @"未找到摄像头";
                self->_cameraConfigured = NO;
                return;
            }

            NSError *error = nil;
            AVCaptureDeviceInput *input = [AVCaptureDeviceInput deviceInputWithDevice:device error:&error];
            if (!input || error) {
                gLastError = error.localizedDescription ?: @"相机输入创建失败";
                self->_cameraConfigured = NO;
                return;
            }
            if ([self.session canAddInput:input]) {
                [self.session addInput:input];
            }

            // 麦克风：已授权则挂音频轨，保证录像有声；未授权不阻塞相机
            [self attachAudioCaptureIfAuthorized];

            // 必须在session 有input 之后再改 activeFormat
            AVCaptureDeviceFormat *format = [self choosePreviewFormatForDevice:device];
            if (format) {
                @try {
                    [device lockForConfiguration:nil];
                    device.activeFormat = format;
                    for (AVFrameRateRange *range in format.videoSupportedFrameRateRanges) {
                        if (range.minFrameRate <= 30.0 + 0.01 && range.maxFrameRate >= 30.0 - 0.01) {
                            device.activeVideoMinFrameDuration = CMTimeMake(1, 30);
                            device.activeVideoMaxFrameDuration = CMTimeMake(1, 30);
                            break;
                        }
                    }
                    [device unlockForConfiguration];
                } @catch (NSException *exception) {
                    FU_LOG("activeFormat warn: %@", exception.reason);
                }
            }

            [self applyDefaultCaptureDeviceSettings:device];

            self.videoOutput = [[AVCaptureVideoDataOutput alloc] init];
            self.videoOutput.videoSettings = @{
                (NSString *)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA)
            };
            self.videoOutput.alwaysDiscardsLateVideoFrames = YES;
            [self.videoOutput setSampleBufferDelegate:self queue:self.captureQueue];

            if ([self.session canAddOutput:self.videoOutput]) {
                [self.session addOutput:self.videoOutput];
            }

            AVCaptureConnection *connection = [self.videoOutput connectionWithMediaType:AVMediaTypeVideo];
            //  竖屏缓冲：给人脸检测美颜正确朝向。横帧+ cw90 时tracking 会一直为 0，美颜等于没开。
            if (connection.isVideoOrientationSupported) {
                connection.videoOrientation = AVCaptureVideoOrientationPortrait;
            }
            if (connection.isVideoMirroringSupported) {
                // 镜像交给 shader / FU BufferMatrix，避免与 videoMirrored 叠加
                connection.videoMirrored = NO;
            }

            CMVideoDimensions chosen = format
                ? CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                : (CMVideoDimensions){ gTargetPreviewW, gTargetPreviewH };
            self->_frameWidth = chosen.width > 0 ? chosen.width : gTargetPreviewW;
            self->_frameHeight = chosen.height > 0 ? chosen.height : gTargetPreviewH;

            dispatch_async(dispatch_get_main_queue(), ^{
                self->_cachedFuOutW = 0;
                self->_cachedFuOutH = 0;
                [self applyInputCameraMatrix];
                [self updateOutputResolutionIfNeeded];
                [self rebuildVertexBufferWithCenterCrop];
            });

            if (self.stopped || self.destroying) {
                self->_cameraConfigured = NO;
                return;
            }

            [self.session startRunning];
            self->_previewStarted = YES;
            gLastPreviewStarted = YES;
            gLastError = @"";
            FU_LOG("cameraStarted target=%dx%d format=%dx%d",
                   gTargetPreviewW, gTargetPreviewH, self->_frameWidth, self->_frameHeight);
        } @catch (NSException *exception) {
            gLastError = exception.reason ?: @"相机配置异常";
            self->_cameraConfigured = NO;
        }
    });
}

/** 已授权麦克风时挂到 session；未授权则跳过（JS / 首启会申请） */
- (void)attachAudioCaptureIfAuthorized {
    if (!_session) {
        return;
    }
    AVAuthorizationStatus st = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeAudio];
    if (st != AVAuthorizationStatusAuthorized) {
        return;
    }
    if (_audioDeviceInput) {
        return;
    }
    NSError *err = nil;
    AVCaptureDevice *mic = [AVCaptureDevice defaultDeviceWithMediaType:AVMediaTypeAudio];
    if (!mic) {
        return;
    }
    AVCaptureDeviceInput *ain = [AVCaptureDeviceInput deviceInputWithDevice:mic error:&err];
    if (!ain || err) {
        return;
    }
    if ([_session canAddInput:ain]) {
        [_session addInput:ain];
        _audioDeviceInput = ain;
    }
    if (!_audioOutput) {
        _audioOutput = [[AVCaptureAudioDataOutput alloc] init];
        [_audioOutput setSampleBufferDelegate:self queue:_captureQueue];
    }
    if (_audioOutput && [_session canAddOutput:_audioOutput] &&
        ![_session.outputs containsObject:_audioOutput]) {
        [_session addOutput:_audioOutput];
    }
}

- (void)requestMicrophoneIfNeeded {
    AVAuthorizationStatus st = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeAudio];
    if (st != AVAuthorizationStatusNotDetermined) {
        if (st == AVAuthorizationStatusAuthorized) {
            dispatch_async(_captureQueue, ^{
                BOOL running = self.session.isRunning;
                if (running) {
                    [self.session beginConfiguration];
                }
                [self attachAudioCaptureIfAuthorized];
                if (running) {
                    [self.session commitConfiguration];
                }
            });
        }
        return;
    }
    [AVCaptureDevice requestAccessForMediaType:AVMediaTypeAudio completionHandler:^(BOOL granted) {
        if (!granted) {
            return;
        }
        dispatch_async(self.captureQueue, ^{
            BOOL running = self.session.isRunning;
            if (running) {
                [self.session beginConfiguration];
            }
            [self attachAudioCaptureIfAuthorized];
            if (running) {
                [self.session commitConfiguration];
            }
        });
    }];
}

- (void)applyInputCameraMatrix {
    if (!fuIsLibraryInit()) {
        return;
    }
    //  Portrait BGRA：用 BufferMatrix 把CPU 帧校正到抬头正立；展示层 identity（mirrorFlip=0）    // 前置水平翻转用于自拍朝向；勿再在 GL 二次镜像。
    TRANSFORM_MATRIX bufMat = _useFrontCamera ? CCROT0_FLIPHORIZONTAL : CCROT0;
    fuSetDefaultRotationMode(FU_ROTATION_MODE_0);
    fuSetInputCameraBufferMatrix(bufMat);
    fuSetInputCameraBufferMatrixState(true);
    // 兼容旧路径（部分 SDK 内部仍读此矩阵）
    int flipX = _useFrontCamera ? 1 : 0;
    fuSetInputCameraMatrix(flipX, 0, FU_ROTATION_MODE_0);
    fuOnCameraChange();
    FU_LOG("cameraMatrix portrait front=%@ bufMat=%d fuFlipX=%d",
           _useFrontCamera ? @"true" : @"false",
           (int)bufMat,
           flipX);
}

- (void)captureOutput:(AVCaptureOutput *)output
didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
       fromConnection:(AVCaptureConnection *)connection {
    (void)connection;
    if (_stopped || _destroying || self.hidden) {
        return;
    }

    // 音频：录制中写入（必须把采集时钟 PTS 映射到相对 0，否则片长会变成「开机至今」量级，如 243 小时）
    if (output == _audioOutput) {
        if (!_recordingVideo || !_audioWriterInput || !_assetWriter) {
            return;
        }
        if (!_recordSessionStarted) {
            // 视频首帧 startSession 前暂存，避免音轨从 0 之前写入导致后半段丢失
            CMSampleBufferRef copy = NULL;
            if (CMSampleBufferCreateCopy(kCFAllocatorDefault, sampleBuffer, &copy) == noErr && copy) {
                if (!_pendingAudioSamples) {
                    _pendingAudioSamples = [NSMutableArray array];
                }
                [_pendingAudioSamples addObject:[NSValue valueWithPointer:copy]];
            }
            return;
        }
        if (_assetWriter.status != AVAssetWriterStatusWriting) {
            return;
        }
        if (![self appendAudioSampleBuffer:sampleBuffer]) {
            CMSampleBufferRef copy = NULL;
            if (CMSampleBufferCreateCopy(kCFAllocatorDefault, sampleBuffer, &copy) == noErr && copy) {
                if (!_pendingAudioSamples) {
                    _pendingAudioSamples = [NSMutableArray array];
                }
                if (_pendingAudioSamples.count < 240) {
                    [_pendingAudioSamples addObject:[NSValue valueWithPointer:copy]];
                } else {
                    CFRelease(copy);
                }
            }
        }
        return;
    }

    CVPixelBufferRef pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
    if (!pixelBuffer) {
        return;
    }

    int bufW = (int)CVPixelBufferGetWidth(pixelBuffer);
    int bufH = (int)CVPixelBufferGetHeight(pixelBuffer);

    int currentFrameId = 0;
    @synchronized (_frameLock) {
        if (_pendingPixelBuffer) {
            CVPixelBufferRelease(_pendingPixelBuffer);
        }
        _pendingPixelBuffer = CVPixelBufferRetain(pixelBuffer);
        if (bufW > 0 && bufH > 0) {
            _frameWidth = bufW;
            _frameHeight = bufH;
        }
        _frameId += 1;
        currentFrameId = _frameId;
        gFrameCount = _frameId;
        _frameReady = YES;
    }

    if (!_glReady) {
            return;
        }

    // 合并到主线程：只保留一个待执行 display；新帧覆盖 pending，避免人脸时 fuRender 变慢后主队列雪崩
    BOOL shouldSchedule = NO;
    @synchronized (_frameLock) {
        if (!_displayScheduled) {
            _displayScheduled = YES;
            shouldSchedule = YES;
        }
    }
    if (!shouldSchedule) {
        return;
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        @synchronized (self.frameLock) {
            self.displayScheduled = NO;
        }
        if (!self.stopped && !self.hidden) {
            [self display];
        }
    });
}

#pragma mark - Shader（与 Android VERTEX_SHADER / FRAGMENT_SHADER 一致）

- (GLuint)buildProgram {
    // 竖屏缓冲：仅做可选镜像，不做 cw90
    const char *vertex =
    "attribute vec2 aPosition;\n"
    "attribute vec2 aTexCoord;\n"
    "uniform float uMirror;\n"
    "uniform float uMirrorY;\n"
    "varying vec2 vTexCoord;\n"
    "void main() {\n"
    "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
    "  float mx = uMirror > 0.5 ? (1.0 - aTexCoord.x) : aTexCoord.x;\n"
    "  float my = uMirrorY > 0.5 ? (1.0 - aTexCoord.y) : aTexCoord.y;\n"
    "  vTexCoord = vec2(mx, my);\n"
    "}\n";
    const char *fragment =
    "precision mediump float;\n"
    "varying vec2 vTexCoord;\n"
    "uniform sampler2D uTexture;\n"
    "void main() {\n"
    "  vec4 c = texture2D(uTexture, vTexCoord);\n"
    "  gl_FragColor = vec4(c.rgb, 1.0);\n"
    "}\n";

    GLuint vs = [self compileShader:GL_VERTEX_SHADER source:vertex];
    GLuint fs = [self compileShader:GL_FRAGMENT_SHADER source:fragment];
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return 0;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (!linked) {
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

- (GLuint)compileShader:(GLenum)type source:(const char *)source {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

#pragma mark - Capture

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

- (void)saveCapturedFrameFromTexture:(unsigned int)texId width:(int)width height:(int)height {
    void (^callback)(NSString *, NSError *) = self.captureFinished;
    self.captureFinished = nil;
    if (!callback) {
        return;
    }
    int w = width & ~1;
    int h = height & ~1;
    if (texId == 0 || w <= 0 || h <= 0) {
        callback(nil, [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                   userInfo:@{NSLocalizedDescriptionKey: @"capture size invalid"}]);
        return;
    }

    // 按相机帧分辨率（所选480/720/1080）导出，而非屏幕 drawable
    unsigned char *rgba = [self copyRGBAFromTexture:texId width:w height:h];
    if (!rgba) {
        callback(nil, [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                   userInfo:@{NSLocalizedDescriptionKey: @"读取像素失败"}]);
        return;
    }

    NSInteger bytesPerRow = w * 4;
    size_t bufferSize = (size_t)bytesPerRow * (size_t)h;
    int sample = rgba[(bytesPerRow * (h / 2)) + (w / 2) * 4];
    int sampleG = rgba[(bytesPerRow * (h / 2)) + (w / 2) * 4 + 1];
    int sampleB = rgba[(bytesPerRow * (h / 2)) + (w / 2) * 4 + 2];
    if (sample + sampleG + sampleB < 12) {
        FU_LOG("capture nearly black center=%d,%d,%d —abort save", sample, sampleG, sampleB);
        free(rgba);
        callback(nil, [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                   userInfo:@{NSLocalizedDescriptionKey: @"捕获帧为空" }]);
        return;
    }

    FU_LOG("capturePhoto save %dx%d (target=%dx%d)", w, h, gTargetPreviewW, gTargetPreviewH);

    CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
    CGDataProviderRef provider = CGDataProviderCreateWithData(NULL, rgba, bufferSize, releasePixelBuffer);
    CGImageRef rawImage = CGImageCreate(
        (size_t)w,
        (size_t)h,
        8,
        32,
        (size_t)bytesPerRow,
        colorSpace,
        kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big,
        provider,
        NULL,
        false,
        kCGRenderingIntentDefault
    );
    CGDataProviderRelease(provider);
    CGColorSpaceRelease(colorSpace);

    if (!rawImage) {
        free(rgba);
        callback(nil, [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                   userInfo:@{NSLocalizedDescriptionKey: @"读取像素失败"}]);
        return;
    }

    UIImage *image = [UIImage imageWithCGImage:rawImage scale:1.0 orientation:UIImageOrientationUp];
    CGImageRelease(rawImage);
    if (!image) {
        callback(nil, [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                   userInfo:@{NSLocalizedDescriptionKey: @"生成图片失败"}]);
        return;
    }

    __block void (^done)(NSString *, NSError *) = [callback copy];
    [self requestPhotoSaveAuthorization:^(BOOL granted) {
        if (!granted) {
            done(nil, [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                   userInfo:@{NSLocalizedDescriptionKey: @"相册写入权限被拒绝" }]);
            return;
        }
        [[PHPhotoLibrary sharedPhotoLibrary] performChanges:^{
            [PHAssetChangeRequest creationRequestForAssetFromImage:image];
        } completionHandler:^(BOOL success, NSError *error) {
            dispatch_async(dispatch_get_main_queue(), ^{
                if (success) {
                    done(@"photos://saved", nil);
                } else {
                    done(nil, error ?: [NSError errorWithDomain:@"FaceUnity-Nama" code:-1
                                                   userInfo:@{NSLocalizedDescriptionKey: @"保存相册失败"}]);
                }
            });
        }];
    }];
}

///  离屏 FBO 按相机帧尺寸读回美颜纹理（顶左RGBA，已翻Y，
- (unsigned char *)copyRGBAFromTexture:(unsigned int)texId width:(int)w height:(int)h {
    if (texId == 0 || w < 2 || h < 2) {
        return NULL;
    }
    GLint prevFbo = 0;
    GLint prevViewport[4] = {0, 0, 0, 0};
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
    glGetIntegerv(GL_VIEWPORT, prevViewport);

    GLuint fbo = 0;
    GLuint colorTex = 0;
    glGenFramebuffers(1, &fbo);
    glGenTextures(1, &colorTex);
    glBindTexture(GL_TEXTURE_2D, colorTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);

    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        FU_LOG("capture FBO incomplete status=0x%x", status);
        glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
        glDeleteTextures(1, &colorTex);
        glDeleteFramebuffers(1, &fbo);
        return NULL;
    }

    glViewport(0, 0, w, h);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);

    //  全幅四边形（不做预览 center-crop）
    static const GLfloat kFullQuad[8] = { -1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f };
    static const GLfloat kTexCoords[8] = { 0.f, 1.f, 1.f, 1.f, 0.f, 0.f, 1.f, 0.f };
    glUseProgram(_renderProgram);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texId);
    glUniform1i(_textureUniform, 0);
    glUniform1f(_mirrorUniform, [self mirrorFlipX]);
    glUniform1f(_mirrorYUniform, [self mirrorFlipY]);
    glEnableVertexAttribArray(_positionAttr);
    glVertexAttribPointer(_positionAttr, 2, GL_FLOAT, GL_FALSE, 0, kFullQuad);
    glEnableVertexAttribArray(_texCoordAttr);
    glVertexAttribPointer(_texCoordAttr, 2, GL_FLOAT, GL_FALSE, 0, kTexCoords);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(_positionAttr);
    glDisableVertexAttribArray(_texCoordAttr);

    size_t bytesPerRow = (size_t)w * 4;
    size_t bufferSize = bytesPerRow * (size_t)h;
    unsigned char *raw = (unsigned char *)malloc(bufferSize);
    unsigned char *out = (unsigned char *)malloc(bufferSize);
    if (!raw || !out) {
        free(raw);
        free(out);
        glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
        glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        glDeleteTextures(1, &colorTex);
        glDeleteFramebuffers(1, &fbo);
        return NULL;
    }
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, raw);
    // 底左 →顶左
    for (int row = 0; row < h; row++) {
        memcpy(out + (size_t)row * bytesPerRow,
               raw + (size_t)(h - 1 - row) * bytesPerRow,
               bytesPerRow);
    }
    free(raw);

    glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
    glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
    glDeleteTextures(1, &colorTex);
    glDeleteFramebuffers(1, &fbo);
    // 恢复预览 drawable，避免后续帧画到已删 FBO
    [self bindDrawable];
    return out;
}

#pragma mark - Focus / Exposure

- (void)applyDefaultCaptureDeviceSettings:(AVCaptureDevice *)device {
    if (!device) {
        return;
    }
    NSError *err = nil;
    if (![device lockForConfiguration:&err]) {
        return;
    }
    if ([device isFocusModeSupported:AVCaptureFocusModeContinuousAutoFocus]) {
        device.focusMode = AVCaptureFocusModeContinuousAutoFocus;
    }
    if ([device isExposureModeSupported:AVCaptureExposureModeContinuousAutoExposure]) {
        device.exposureMode = AVCaptureExposureModeContinuousAutoExposure;
    }
    if ([device respondsToSelector:@selector(setSubjectAreaChangeMonitoringEnabled:)]) {
        // 禁止系统因场景剧变自动改曝光/对焦（Pan 到亮区时人脸突然过曝）
        device.subjectAreaChangeMonitoringEnabled = NO;
    }
    if ([device isFocusPointOfInterestSupported]) {
        device.focusPointOfInterest = CGPointMake(0.5, 0.5);
    }
    if ([device isExposurePointOfInterestSupported]) {
        device.exposurePointOfInterest = CGPointMake(0.5, 0.5);
    }
    [device unlockForConfiguration];
}

/** 有人脸时把测光锚定在人脸，避免 AF/全局 AE 被画面亮区带跑 */
- (void)updateFaceAnchoredExposureIfNeededWithFrameWidth:(int)width height:(int)height {
    if (self.exposureLockedByUser || width <= 0 || height <= 0) {
        return;
    }
    if (fuIsLibraryInit() == 0 || fuIsTracking() <= 0) {
        return;
    }

    float rect[4] = {0};
    if (fuGetFaceInfoRotated(0, "face_rect", rect, 4) <= 0) {
        return;
    }
    float cx = (rect[0] + rect[2]) * 0.5f;
    float cy = (rect[1] + rect[3]) * 0.5f;
    CGFloat nx = (CGFloat)cx / (CGFloat)width;
    CGFloat ny = (CGFloat)cy / (CGFloat)height;
    nx = MIN(1.0, MAX(0.0, nx));
    ny = MIN(1.0, MAX(0.0, ny));
    CGPoint poi = CGPointMake(ny, self.useFrontCamera ? nx : (1.0 - nx));
    poi.x = MIN(1.0, MAX(0.0, poi.x));
    poi.y = MIN(1.0, MAX(0.0, poi.y));

    CFAbsoluteTime now = CFAbsoluteTimeGetCurrent();
    CGFloat dx = poi.x - self.lastFaceExposurePoi.x;
    CGFloat dy = poi.y - self.lastFaceExposurePoi.y;
    if (self.lastFaceExposurePoi.x >= 0 &&
        now - self.lastFaceExposureApplyTime < 0.35 &&
        (dx * dx + dy * dy) < 0.0025) {
        return;
    }

    __weak typeof(self) weakSelf = self;
    dispatch_async(self.captureQueue, ^{
        __strong typeof(weakSelf) self = weakSelf;
        if (!self || self.exposureLockedByUser) {
            return;
        }
        AVCaptureDevice *device = [self currentVideoDevice];
        if (!device) {
            return;
        }
        NSError *lockErr = nil;
        if (![device lockForConfiguration:&lockErr]) {
            return;
        }
        if ([device isExposurePointOfInterestSupported]) {
            device.exposurePointOfInterest = poi;
        }
        if ([device isExposureModeSupported:AVCaptureExposureModeContinuousAutoExposure]) {
            device.exposureMode = AVCaptureExposureModeContinuousAutoExposure;
        }
        if ([device respondsToSelector:@selector(setSubjectAreaChangeMonitoringEnabled:)]) {
            device.subjectAreaChangeMonitoringEnabled = NO;
        }
        [device unlockForConfiguration];
        self.lastFaceExposureApplyTime = now;
        self.lastFaceExposurePoi = poi;
    });
}

- (AVCaptureDevice *)currentVideoDevice {
    for (AVCaptureInput *input in self.session.inputs) {
        if (![input isKindOfClass:[AVCaptureDeviceInput class]]) {
            continue;
        }
        AVCaptureDevice *device = ((AVCaptureDeviceInput *)input).device;
        if (device && [device hasMediaType:AVMediaTypeVideo]) {
            return device;
        }
    }
    return nil;
}

- (void)ensureFocusChrome {
    if (_focusChrome) {
        return;
    }
    // 优先挂到 PassThrough host（专用透明窗），才能盖住GL 并穿透非滑杆触摸
    UIView *parent = self.superview; // previewBox
    if (parent.superview) {
        parent = parent.superview; // host
    }
    if (!parent) {
        parent = self;
    }
    FuFocusChromeView *chrome = [[FuFocusChromeView alloc] initWithFrame:parent.bounds];
    chrome.userInteractionEnabled = YES;
    chrome.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    chrome.backgroundColor = [UIColor clearColor];
    chrome.hidden = YES;

    FuFocusCrossView *cross = [[FuFocusCrossView alloc] initWithFrame:CGRectMake(0, 0, 70, 70)];
    [chrome addSubview:cross];

    FuExposureRailView *rail = [[FuExposureRailView alloc] initWithFrame:CGRectMake(0, 0, 44, 220)];
    rail.progress = 0.5;
    rail.tag = 88219901; //  与PreviewChrome / PassThrough hitTest 放行 tag 一致
    __weak typeof(self) weakSelf = self;
    rail.onChange = ^(CGFloat progress, BOOL finalized) {
        [weakSelf setExposureNormalized:progress finalizeLock:finalized];
        [weakSelf scheduleFocusChromeHide];
    };
    [chrome addSubview:rail];
    chrome.exposureRail = rail;

    _focusChrome = chrome;
    _crosshairView = cross;
    _exposureRail = rail;
    // previewBox/GLKView 用了 zPosition 10000+，chrome 必须更高才能盖住取景
    chrome.layer.zPosition = 20000.f;
    [parent addSubview:chrome];
    [parent bringSubviewToFront:chrome];
}

- (void)scheduleFocusChromeHide {
    [self.focusChromeHideTimer invalidate];
    __weak typeof(self) weakSelf = self;
    self.focusChromeHideTimer = [NSTimer scheduledTimerWithTimeInterval:1.3 repeats:NO block:^(__unused NSTimer *timer) {
        [weakSelf hideFocusChrome];
    }];
}

- (void)tapFocusAtNormalizedX:(CGFloat)nx y:(CGFloat)ny {
    nx = MIN(1.0, MAX(0.0, nx));
    ny = MIN(1.0, MAX(0.0, ny));
    [self ensureFocusChrome];
    // 预览框可能已 resize
    if (_focusChrome.superview) {
        _focusChrome.frame = _focusChrome.superview.bounds;
    }
    _focusChrome.hidden = NO;
    [_focusChrome.superview bringSubviewToFront:_focusChrome];

    //  十字：相机视图归一化坐标→chrome 父视图（PassThrough host）坐标
    CGPoint inCamera = CGPointMake(nx * self.bounds.size.width, ny * self.bounds.size.height);
    UIView *chromeParent = _focusChrome.superview ?: self;
    CGPoint inChrome = [self convertPoint:inCamera toView:chromeParent];
    _crosshairView.center = inChrome;
    _crosshairView.transform = CGAffineTransformMakeScale(1.25, 1.25);
    [UIView animateWithDuration:0.28 animations:^{
        self.crosshairView.transform = CGAffineTransformIdentity;
    }];

    //  对齐 Android / Demo：曝光条相对取景框右侧偏上固定，不跟十字走
    CGRect camInChrome = [self convertRect:self.bounds toView:chromeParent];
    CGFloat railW = 44.f, railH = 220.f;
    CGFloat railX = CGRectGetMaxX(camInChrome) - 12.f - railW;
    CGFloat railY = CGRectGetMinY(camInChrome) + MAX(72.f, CGRectGetHeight(camInChrome) * 0.5f - 60.f - railH * 0.5f);
    _exposureRail.frame = CGRectMake(railX, railY, railW, railH);
    _exposureRail.hidden = NO;

    AVCaptureDevice *device = [self currentVideoDevice];
    if (device) {
        // 对齐 FULiveDemo：focusPointOfInterest 是「横屏home 在右」坐标系，        // 竖屏预览点(nx,ny) →(ny, front ? nx : 1-nx)
        CGPoint poi = CGPointMake(ny, self.useFrontCamera ? nx : (1.0 - nx));
        poi.x = MIN(1.0, MAX(0.0, poi.x));
        poi.y = MIN(1.0, MAX(0.0, poi.y));

        NSError *err = nil;
        if ([device lockForConfiguration:&err]) {
            if ([device isFocusPointOfInterestSupported]) {
                device.focusPointOfInterest = poi;
            }
            //  必须用AutoFocus 单次合焦；勿立刻改回 Continuous，否则看起来「点了但不变清晰」
            if ([device isFocusModeSupported:AVCaptureFocusModeAutoFocus]) {
                device.focusMode = AVCaptureFocusModeAutoFocus;
            } else if ([device isFocusModeSupported:AVCaptureFocusModeContinuousAutoFocus]) {
                device.focusMode = AVCaptureFocusModeContinuousAutoFocus;
            }

            // 对焦与曝光解耦：测光由人脸锚定/曝光条控制，避免 AF 把 EV 带到亮区
            if ([device respondsToSelector:@selector(setSubjectAreaChangeMonitoringEnabled:)]) {
                device.subjectAreaChangeMonitoringEnabled = NO;
            }

            [device unlockForConfiguration];
            FU_LOG("tapFocus view=(%.2f,%.2f) poi=(%.2f,%.2f) front=%d focusPOI=%d",
                   nx, ny, poi.x, poi.y, (int)self.useFrontCamera,
                   (int)[device isFocusPointOfInterestSupported]);

            //  合焦完成后再回连续对焦（对齐安卓 1.8s）；若用户锁了 EV 则重放 bias
            __weak typeof(self) weakSelf = self;
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1.5 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
                __strong typeof(weakSelf) self = weakSelf;
                if (!self) {
                    return;
                }
                AVCaptureDevice *dev = [self currentVideoDevice];
                if (!dev) {
                    return;
                }
                NSError *e2 = nil;
                if ([dev lockForConfiguration:&e2]) {
                    if ([dev isFocusModeSupported:AVCaptureFocusModeContinuousAutoFocus]) {
                        dev.focusMode = AVCaptureFocusModeContinuousAutoFocus;
                    }
                    [dev unlockForConfiguration];
                }
                if (self.exposureLockedByUser) {
                    [self setExposureNormalized:self.lastExposureNormalized finalizeLock:YES];
                }
            });
        } else {
            FU_LOG("tapFocus lock failed %@", err.localizedDescription);
        }
    }

    [self scheduleFocusChromeHide];
}

- (void)setExposureNormalized:(CGFloat)value {
    [self setExposureNormalized:value finalizeLock:YES];
}

- (void)setExposureNormalized:(CGFloat)value finalizeLock:(BOOL)finalizeLock {
    value = MIN(1.0, MAX(0.0, value));
    self.lastExposureNormalized = value;
    FuSetExposureNormalizedForBlur(value);
    if (_exposureRail && fabs(_exposureRail.progress - value) > 0.001) {
        _exposureRail.progress = value;
    }
    AVCaptureDevice *device = [self currentVideoDevice];
    if (!device) {
        return;
    }
    // 拖动节流：避免每像素 lockForConfiguration
    if (!finalizeLock) {
        static CFAbsoluteTime sLastDragApply = 0;
        CFAbsoluteTime now = CFAbsoluteTimeGetCurrent();
        if (now - sLastDragApply < 0.05) {
            return;
        }
        sLastDragApply = now;
    }
    float minB = device.minExposureTargetBias;
    float maxB = device.maxExposureTargetBias;
    // 对齐 Android：补偿步进通常约 ±2EV，勿用满 iOS 可达 ±8 的 bias（会过曝/过暗）
    float maxUse = MIN(maxB, 2.0f);
    float minUse = MAX(minB, -2.0f);
    float bias = 0.f;
    if (value >= 0.5f) {
        bias = (float)((value - 0.5) / 0.5) * maxUse;
    } else {
        bias = (float)((0.5 - value) / 0.5) * minUse;
    }
    bias = MIN(maxB, MAX(minB, bias));

    NSError *err = nil;
    if (![device lockForConfiguration:&err]) {
        return;
    }
    // 只改 bias，勿来回切 AE Locked（用户明确不是锁 AE 的问题）
    if (device.exposureMode == AVCaptureExposureModeLocked &&
        [device isExposureModeSupported:AVCaptureExposureModeContinuousAutoExposure]) {
        device.exposureMode = AVCaptureExposureModeContinuousAutoExposure;
    } else if ([device isExposureModeSupported:AVCaptureExposureModeContinuousAutoExposure] &&
               device.exposureMode != AVCaptureExposureModeContinuousAutoExposure &&
               device.exposureMode != AVCaptureExposureModeAutoExpose) {
        device.exposureMode = AVCaptureExposureModeContinuousAutoExposure;
    }
    [device setExposureTargetBias:bias completionHandler:nil];
    if (finalizeLock) {
        if (fabs(value - 0.5) > 0.02) {
            self.exposureLockedByUser = YES;
        } else {
            self.exposureLockedByUser = NO;
            [device setExposureTargetBias:0 completionHandler:nil];
        }
    } else if (fabs(value - 0.5) > 0.02) {
        self.exposureLockedByUser = YES;
    }
    [device unlockForConfiguration];
}

- (void)hideFocusChrome {
    [self.focusChromeHideTimer invalidate];
    self.focusChromeHideTimer = nil;
    _focusChrome.hidden = YES;
}

@end
