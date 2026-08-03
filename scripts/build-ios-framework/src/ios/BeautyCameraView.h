#import <GLKit/GLKit.h>

@interface BeautyCameraView : GLKView

- (void)bindLayoutSize:(int)width height:(int)height;
- (void)startPreview;
- (void)stopPreview;
- (void)hidePreview;
- (void)resumePreview;
/** soft-hide 中：延迟 bringOverlay 等不得再 unhide，否则露出冻帧 */
- (BOOL)isSoftHidden;
- (void)releaseCameraKeepAlive:(void (^)(void))onFinished;
- (void)destroyPreviewAsync:(void (^)(void))onFinished;
- (void)setBeautyEnabled:(BOOL)enabled;
- (void)switchCameraFacing;
/** 带完成回调的切换，避免竞态导致偶发失败 */
- (void)switchCameraFacingWithCompletion:(void (^ _Nullable)(NSError * _Nullable error))completion;
- (void)restartPreview;
- (BOOL)isPreviewStarted;
- (void)capturePhoto:(void (^)(NSString *path, NSError *error))callback;
/** 长按录制：写入美颜后的预览帧；stop 后保存到相册 */
- (BOOL)startVideoRecord:(NSError **)error;
- (void)stopVideoRecord:(void (^)(NSString *path, NSError *error))callback;
- (void)cancelVideoRecord;
/** 原生最长时长自动停录时回调（主线程） */
@property (nonatomic, copy, nullable) void (^onAutoStopRecording)(NSString * _Nullable path, NSError * _Nullable error);
/** 点击对焦：nx/ny 为预览视图内 0~1；十字+曝光条画在取景之上（原生层） */
- (void)tapFocusAtNormalizedX:(CGFloat)nx y:(CGFloat)ny;
/** 曝光 0~1，映射到设备 exposureTargetBias；finalizeLock=抬手锁 AE */
- (void)setExposureNormalized:(CGFloat)value;
- (void)setExposureNormalized:(CGFloat)value finalizeLock:(BOOL)finalizeLock;
- (void)hideFocusChrome;

+ (void)setBeautyEnabledGlobal:(BOOL)enabled;
+ (void)setTargetPreviewSize:(int)width height:(int)height;
/** 进相机页重置为 720，不跨页记忆 */
+ (void)resetTargetPreviewSizeToDefault;
/** App 真正退出 Nama 时销毁 FU GL；普通离开关闭预览不要调用 */
+ (void)shutdownSharedGLContext;
/**
 * 静图美颜等离屏渲染前调用：确保全局 sharegroup + fuInitGLContext 就绪，
 * 并将 FU 托管 context 设为 current。失败时 error 可读。
 */
+ (BOOL)ensureSharedGLContextReady:(NSError **)error;
/** 创建挂在全局 sharegroup 上的 EAGLContext（视频预览等复用） */
+ (EAGLContext *)createContextInSharedGroup;
/** 与相机/静图/视频共用的 GL 互斥，渲染期间必须持有 */
+ (void)performWithSharedGLLock:(void (^)(void))block;
/** 特殊算法写参：排队到相机/渲染线程在 fuRender 前落地（对齐 Android runOnNamaGl） */
+ (void)enqueueBeautyParam:(int)handle name:(NSString *)name value:(double)value;
+ (void)enqueueBeautyParamString:(int)handle name:(NSString *)name value:(NSString *)value;
+ (void)flushPendingBeautyParams;
+ (NSString *)lastError;
+ (NSString *)previewDiag;
+ (NSDictionary *)previewStats;
+ (void)setOverlayRootClass:(NSString *)rootClass;
+ (void)resetSessionDiag;

@end
