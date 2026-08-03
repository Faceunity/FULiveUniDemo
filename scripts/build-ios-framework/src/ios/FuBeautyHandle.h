#ifndef FuBeautyHandle_h
#define FuBeautyHandle_h

#import <Foundation/Foundation.h>

/** 摄像头实时美颜 */
extern int FuBeautyCameraHandle;
/** 相册图片/视频美颜 */
extern int FuBeautyMediaHandle;
/** 兼容旧名：等同相机 handle */
extern int FuBeautyItemHandle;

#ifdef __cplusplus
extern "C" {
#endif

static inline int FuBeautyHandleForPipeline(BOOL media) {
    return media ? FuBeautyMediaHandle : FuBeautyCameraHandle;
}

void FuBeautySetPipelineHandle(BOOL media, int handle);
void FuBeautyClearCamera(void);
void FuBeautyClearMedia(void);
void FuBeautyClearAll(void);

/**
 * GL context 已创建可标 ready。
 * 勿再调用未公开 FUAI_FaceProcessorSetUse*（会导致进美颜页闪退）。
 */
void FuSetAdvancedBeautyGlReady(BOOL ready);
void FuEnableAdvancedBeautyRuntime(int beautyHandle);
void FuEnsureAdvancedBeautySwitches(int beautyHandle);
/** 对齐 Android BeautyParamApplier.applySpecialAlgoParam；须在 Nama GL current 调用 */
void FuApplySpecialBeautyParamOnGl(int beautyHandle, const char *key, double value);
void FuApplyBeautyParamDirectOnGl(int beautyHandle, const char *key, double value);
BOOL FuIsSpecialBeautyParamName(const char *key);
void FuCacheSpecialBeautyValue(int beautyHandle, const char *key, double value);
double FuCachedSpecialBeautyValue(int beautyHandle, const char *key);
/** 每帧 fuRender 前重确认丰盈/祛斑等开关，防止 SDK 帧间清开关导致「拖一拖没效果」 */
void FuReconfirmSpecialBeautySwitches(int beautyHandle);
void FuUpdateBeautyBlurEffect(int beautyHandle);
void FuResetBeautyBlurCache(void);
/** 切摄期间强制 change_frames=0，防止 enableAdvanced* 又写回 12 导致美型淡入 */
void FuSetBeautyChangeFramesHoldZero(BOOL hold);
BOOL FuBeautyChangeFramesHoldZero(void);
double FuBeautyChangeFramesValue(void);
/** 曝光条归一化 0~1，极端曝光时磨皮降噪（对齐 Android applyBlurForExposure） */
void FuSetExposureNormalizedForBlur(double normalized01);
void FuTryApplyAdvancedBeautySetUseAfterRender(int beautyHandle);
/** 1=Low … 4=Excellent；SDK 无推荐时用 RAM 粗分 */
int FuDevicePerformanceLevelCached(void);

#ifdef __cplusplus
}
#endif

#endif /* FuBeautyHandle_h */
