package com.faceunity.nama.core;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import com.faceunity.wrapper.faceunity;

/**
 * 媒体/静图管线 FU 输入矩阵与算法子模块，对齐 FULiveDemo / iOS NamaModule。
 */
public final class MediaFuSetup {

    private static final String TAG = "FaceUnity-MediaFu";
    /** 对齐 FULiveDemo FUDevicePerformanceLevel */
    public static final int PERF_LOW_1 = -1;
    public static final int PERF_LOW = 1;
    public static final int PERF_HIGH = 2;
    public static final int PERF_VERY_HIGH = 3;
    public static final int PERF_EXCELLENT = 4;

    private static volatile boolean sPerfLevelResolved = false;
    private static volatile boolean sResolvedWithoutRam = false;
    private static volatile int sCachedPerfLevel = PERF_LOW;
    private static Context sAppContext;

    private MediaFuSetup() {
    }

    public static void setAppContext(Context context) {
        if (context == null) {
            return;
        }
        sAppContext = context.getApplicationContext();
        if (sPerfLevelResolved && sResolvedWithoutRam) {
            sPerfLevelResolved = false;
            Log.i(TAG, "re-resolve devicePerformanceLevel after app context");
        }
    }

    /**
     * 对齐 FULiveDemo / iOS FuDevicePerformanceLevelFromHardware：按 RAM 粗分 -1~4。
     */
    public static int getDevicePerformanceLevel() {
        if (sPerfLevelResolved) {
            return sCachedPerfLevel;
        }
        if (sAppContext == null) {
            return PERF_LOW;
        }
        int level = detectPerformanceLevelFromHardware();
        sResolvedWithoutRam = totalRamGb() <= 0;
        sCachedPerfLevel = clampPerfLevel(level);
        sPerfLevelResolved = true;
        Log.i(TAG, "devicePerformanceLevel=" + sCachedPerfLevel
                + " ramGb=" + Math.round(totalRamGb() * 100.0) / 100.0
                + " cores=" + Runtime.getRuntime().availableProcessors());
        return sCachedPerfLevel;
    }

    public static int clampPerfLevel(int level) {
        if (level <= PERF_LOW_1) {
            return PERF_LOW_1;
        }
        if (level >= PERF_EXCELLENT) {
            return PERF_EXCELLENT;
        }
        if (level <= PERF_LOW) {
            return PERF_LOW;
        }
        if (level == PERF_HIGH || level == PERF_VERY_HIGH) {
            return level;
        }
        return PERF_HIGH;
    }

    /**
     * 按 ActivityManager.totalMem（GB）分档。
     * 校准参考：vivo X9≈3.5GB→1；vivo NEX≈6GB→2；小米11 Ultra 8GB≈7.0GB→3；12GB+→4。
     */
    private static int detectPerformanceLevelFromHardware() {
        double memGb = totalRamGb();
        if (memGb > 0) {
            if (memGb < 2.0) {
                return PERF_LOW_1;
            }
            if (memGb < 4.0) {
                return PERF_LOW;
            }
            if (memGb < 7.0) {
                return PERF_HIGH;
            }
            if (memGb < 11.5) {
                return PERF_VERY_HIGH;
            }
            return PERF_EXCELLENT;
        }
        return PERF_LOW;
    }

    public static double getTotalRamGbForDiag() {
        return totalRamGb();
    }

    private static double totalRamGb() {
        if (sAppContext == null) {
            return 0;
        }
        try {
            ActivityManager am = (ActivityManager) sAppContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return 0;
            }
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            return info.totalMem / (1024.0 * 1024.0 * 1024.0);
        } catch (Throwable t) {
            Log.w(TAG, "totalRamGb", t);
            return 0;
        }
    }

    private static int faceAlgorithmDisableAll() {
        return faceunity.FUAI_FACE_ALGORITHMCONFIG_DISABLE_FACE_OCCU
                | faceunity.FUAI_FACE_ALGORITHMCONFIG_DISABLE_SKIN_SEG
                | faceunity.FUAI_FACE_ALGORITHMCONFIG_DISABLE_DEL_SPOT
                | faceunity.FUAI_FACE_ALGORITHMCONFIG_DISABLE_ARMESHV2
                | faceunity.FUAI_FACE_ALGORITHMCONFIG_DISABLE_RACE
                | faceunity.FUAI_FACE_ALGORITHMCONFIG_DISABLE_LANDMARK_HP_OCCU;
    }

    /**
     * 对齐 FULiveDemo FURenderKitManager.loadFaceAIModel：按机型开关 AI 子模块。
     * 必须在 {@code fuLoadAIModelFromPackage} 之前调用。
     */
    public static void enableFaceAlgorithmModules() {
        try {
            int level = getDevicePerformanceLevel();
            int config = faceunity.FUAI_FACE_ALGORITHMCONFIG_ENABLE_ALL;
            boolean arMeshV2 = true;
            if (level < PERF_HIGH) {
                config = faceAlgorithmDisableAll();
                arMeshV2 = false;
            } else if (level < PERF_VERY_HIGH) {
                config = faceunity.FUAI_FACE_ALGORITHMCONFIG_DISABLE_SKIN_SEG
                        | faceunity.FUAI_FACE_ALGORITHMCONFIG_DISABLE_DEL_SPOT
                        | faceunity.FUAI_FACE_ALGORITHMCONFIG_DISABLE_ARMESHV2
                        | faceunity.FUAI_FACE_ALGORITHMCONFIG_DISABLE_RACE;
                arMeshV2 = false;
            } else if (level < PERF_EXCELLENT) {
                config = faceunity.FUAI_FACE_ALGORITHMCONFIG_DISABLE_SKIN_SEG;
            }
            faceunity.fuSetMachineType(1); // FUAIMACHINE_HIGH
            faceunity.fuSetDynamicQualityControl(level <= PERF_LOW);
            faceunity.fuSetFaceAlgorithmConfig(config);
            faceunity.fuSetARMeshV2(arMeshV2);
            Log.i(TAG, "enableFaceAlgorithmModules level=" + level
                    + " config=" + config + " armeshV2=" + arMeshV2
                    + " dynamicQ=" + (level <= PERF_LOW));
        } catch (Throwable t) {
            Log.e(TAG, "enableFaceAlgorithmModules", t);
        }
    }

    public static void ensureSpecialBeautyAlgorithmsEnabled() {
        try {
            int level = getDevicePerformanceLevel();
            int config = faceunity.FUAI_FACE_ALGORITHMCONFIG_ENABLE_ALL;
            if (level < PERF_HIGH) {
                config = faceAlgorithmDisableAll();
            } else if (level < PERF_EXCELLENT) {
                config = faceunity.FUAI_FACE_ALGORITHMCONFIG_DISABLE_RACE;
            }
            faceunity.fuSetFaceAlgorithmConfig(config);
            Log.i(TAG, "ensureSpecialBeautyAlgorithmsEnabled level=" + level + " config=" + config);
        } catch (Throwable t) {
            Log.w(TAG, "ensureSpecialBeautyAlgorithmsEnabled", t);
        }
    }

    /** loadBundle / Surface 重建时写全量默认（含磨皮档）。 */
    public static void enableAdvancedBeautyRuntime(int beautyHandle) {
        if (beautyHandle <= 0) {
            return;
        }
        try {
            ensureAdvancedBeautySwitches(beautyHandle);
            BeautyParamApplier.resetBlurCache();
            BeautyParamApplier.applyDeviceBlurDefaults(beautyHandle);
            BeautyParamApplier.normalizeDualKeyBeautyParams(beautyHandle);
            FuBeautyPerfGate.enforceOnHandle(beautyHandle);
        } catch (Throwable ignored) {
        }
    }

    public static void ensureAdvancedBeautySwitches(int beautyHandle) {
        if (beautyHandle <= 0) {
            return;
        }
        try {
            int level = getDevicePerformanceLevel();
            faceunity.fuItemSetParam(beautyHandle, "heavy_blur", 0.0);
            faceunity.fuItemSetParam(beautyHandle, "skin_detect", 0.0);
            double delspot = FuSpecialBeautySync.effectiveValue(beautyHandle, "delspot_level");
            double plump = FuSpecialBeautySync.effectiveValue(beautyHandle, "facial_plump");
            double bodyBlur = FuSpecialBeautySync.effectiveValue(beautyHandle, "body_blur_level");
            boolean specialOn = delspot > 0.001 || plump > 0.001 || bodyBlur > 0.001;
            if (level >= PERF_VERY_HIGH || specialOn) {
                faceunity.fuItemSetParam(beautyHandle, "disable_delspot", 0.0);
            } else {
                faceunity.fuItemSetParam(beautyHandle, "disable_delspot", 1.0);
            }
            if (specialOn) {
                ensureSpecialBeautyAlgorithmsEnabled();
            }
            BeautyParamApplier.applyDemoDefaults(beautyHandle);
        } catch (Throwable ignored) {
        }
    }
    /** CNamaSDK CCROT0_FLIPVERTICAL；Android 等价 iOS fuSetInputCameraBufferMatrix(4) */
    static final int CCROT0_FLIPVERTICAL = 4;

    public static void applyIdentityBufferMatrix() {
        try {
            faceunity.fuSetDefaultRotationMode(faceunity.FU_ROTATION_MODE_0);
            faceunity.fuSetInputCameraMatrix(0, 0, faceunity.FU_ROTATION_MODE_0);
            faceunity.fuSetInputBufferMatrix(0);
            faceunity.fuSetInputTextureMatrix(0);
            faceunity.fuSetOutputMatrix(0);
            faceunity.fuSetInputCameraBufferMatrixState(1);
            faceunity.fuSetInputCameraTextureMatrixState(0);
            faceunity.fuSetOutputMatrixState(0);
        } catch (Throwable t) {
            Log.e(TAG, "applyIdentityBufferMatrix", t);
        }
    }

    public static void applyStillLikeBufferMatrix() {
        try {
            faceunity.fuSetDefaultRotationMode(faceunity.FU_ROTATION_MODE_0);
            faceunity.fuSetInputCameraMatrix(0, 0, faceunity.FU_ROTATION_MODE_0);
            faceunity.fuSetInputBufferMatrix(CCROT0_FLIPVERTICAL);
            faceunity.fuSetInputTextureMatrix(0);
            faceunity.fuSetOutputMatrix(0);
            faceunity.fuSetInputCameraBufferMatrixState(1);
            faceunity.fuSetInputCameraTextureMatrixState(0);
            faceunity.fuSetOutputMatrixState(0);
        } catch (Throwable t) {
            Log.e(TAG, "applyStillLikeBufferMatrix", t);
            applyIdentityBufferMatrix();
        }
    }

    public static void applyCameraDualInputMatrices(int flipX, int rotateMode, int bufferMatrix) {
        try {
            faceunity.fuSetDefaultRotationMode(rotateMode);
            faceunity.fuSetInputCameraMatrix(flipX, 0, rotateMode);
            applyCameraBufferMatrix(bufferMatrix);
            faceunity.fuSetInputTextureMatrix(0);
            faceunity.fuSetOutputMatrix(0);
            faceunity.fuSetInputCameraTextureMatrixState(0);
            faceunity.fuSetOutputMatrixState(0);
        } catch (Throwable t) {
            Log.e(TAG, "applyCameraDualInputMatrices", t);
        }
    }

    /**
     * 对齐 iOS：BufferMatrixState 始终开启，矩阵固定 CCROT0_FLIPVERTICAL(4)。
     * 勿再随丰盈/祛斑在 0/4 间切换（关丰盈后美型会左右跑偏）。
     */
    public static void applyCameraBufferMatrix(int bufferMatrix) {
        try {
            faceunity.fuSetInputBufferMatrix(CCROT0_FLIPVERTICAL);
            faceunity.fuSetInputCameraBufferMatrixState(1);
        } catch (Throwable t) {
            Log.e(TAG, "applyCameraBufferMatrix", t);
        }
    }

    public static void ensureBeautyOn(int handle) {
        if (handle <= 0) {
            return;
        }
        try {
            faceunity.fuItemSetParam(handle, "is_beauty_on", 1.0);
        } catch (Throwable ignored) {
        }
    }

    public static void deviceLostOnCurrentGl() {
        try {
            if (faceunity.fuIsLibraryInit() != 0) {
                faceunity.fuOnDeviceLostSafe();
                Log.i(TAG, "fuOnDeviceLostSafe ok");
            }
        } catch (Throwable t) {
            Log.e(TAG, "deviceLostOnCurrentGl", t);
        }
    }
}
