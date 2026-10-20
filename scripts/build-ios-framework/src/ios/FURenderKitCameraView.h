#import <UIKit/UIKit.h>
#import <AVFoundation/AVFoundation.h>

/**
 * FURenderKit 版相机美颜预览视图（官方 FURenderKit 内部相机方案）。
 *
 * 与 BeautyCameraView 保持相同公开接口契约，替换自建 GLKView + 旧 C SDK 的渲染链路。
 * 类继承 UIView（不再是 GLKView），内部持有官方 FUGLDisplayView 作为 subview 显示画面，
 * 相机采集与渲染全部交给 [FURenderKit shareRenderKit] 内部相机完成。
 *
 * 本类不负责 SDK 初始化 / AI 模型加载 / 美颜 bundle 创建（由 NamaModule.m 处理），
 * 只负责相机生命周期与 FURenderKit 的 glDisplayView 编排。
 */
@interface FURenderKitCameraView : UIView

- (void)bindLayoutSize:(int)width height:(int)height;
- (void)startPreview;
/** view 稳定后延迟启动预览（避免上窗瞬间启动相机） */
- (void)startPreviewIfWindowStable;
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
/** 对齐官方 presets[index]：@"480" / @"720" / @"1080" */
+ (void)setTargetPreviewResolutionId:(NSString *)resolutionId;
/** 进相机页重置为 720，不跨页记忆 */
+ (void)resetTargetPreviewSizeToDefault;
/** 立即对运行中的相机会话应用目标分辨率；返回 YES 表示已直接生效（对齐官方 demo changeSessionPreset） */
+ (BOOL)applyTargetPreviewSizeToCamera;
/** 对齐官方 FURenderViewModel.switchCapturePreset */
+ (BOOL)switchCapturePreset:(AVCaptureSessionPreset)preset unsupportedHandler:(void (^)(void))handler;
/** 对齐官方 FURenderViewController.fuPopupMenuSegment：按分辨率 id 后台 changeSessionPreset */
+ (void)applyPreviewResolutionId:(NSString *)resolutionId
              unsupportedHandler:(void (^)(void))handler
                      completion:(void (^)(BOOL applied))completion;
/** 对齐官方：后台线程 changeSessionPreset */
+ (void)applyPreviewResolutionWidth:(int)width
                             height:(int)height
                 unsupportedHandler:(void (^)(void))handler
                         completion:(void (^)(BOOL applied))completion;
/** App 真正退出 Nama 时销毁 FU GL；普通离开关闭预览不要调用 */
+ (void)shutdownSharedGLContext;
/** 追加一行到真机沙盒 Documents/nama_diag.log（诊断用） */
+ (void)appendDiagLine:(NSString *)line;
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
