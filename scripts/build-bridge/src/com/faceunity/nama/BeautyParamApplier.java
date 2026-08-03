package com.faceunity.nama;

import android.util.Log;

import com.faceunity.wrapper.faceunity;

/**
 * 对齐 FULiveDemo / 美颜道具文档的写参与磨皮默认。
 * <p>
 * Demo（RenderKit）美白用 {@code colorLevel} → Nama key {@code color_level}；
 * 皮肤分割用 {@code enableSkinSegmentation} → {@code enable_skinseg}。
 * 切勿同时写 {@code color_level} + {@code color_level_mode2}，强度会叠成蓝白过曝。
 */
final class BeautyParamApplier {

    private static final String TAG = "FaceUnity-BeautyParam";
    private static volatile int sAppliedBlurType = -1;
    private static volatile int sAppliedBlurMask = -1;

    private BeautyParamApplier() {
    }

    /**
     * 标量写参。美白统一写 Demo 基名 {@code color_level}（勿双写 mode2）。
     */
    static int setDouble(int handle, String key, double value) {
        if (handle <= 0 || key == null || key.isEmpty()) {
            return -1;
        }
        value = FuBeautyPerfGate.clampValue(key, value);
        if (!FuBeautyPerfGate.isParamAllowed(key) && Math.abs(value) < 0.001) {
            FuSpecialBeautySync.cacheValue(handle, key, value);
        }
        String resolved = resolveKey(key);
        zeroAlternateBeautyKey(handle, resolved);
        int code = faceunity.fuItemSetParam(handle, resolved, value);
        if (code <= 0 && !resolved.equals(key) && !isDualModeBeautyKey(key)) {
            code = faceunity.fuItemSetParam(handle, key, value);
        }
        if ("color_level".equals(resolved)) {
            FuSpecialBeautySync.cacheValue(handle, "color_level", value);
        }
        // SDK EyeBrightenPassV2：基名 eye_bright + v2 双写（对齐 iOS cache + v2 latch）
        if ("eye_bright".equals(key) || "eye_bright".equals(resolved)) {
            FuSpecialBeautySync.cacheValue(handle, "eye_bright", value);
            trySet(handle, "eye_bright_v2", value);
        }
        return code;
    }

    /** loadBundle 后：道具包可能同时带 mode1/mode2 默认值，只保留当前档位对应的一套 */
    static void normalizeDualKeyBeautyParams(int handle) {
        if (handle <= 0) {
            return;
        }
        boolean highPlus = MediaFuSetup.getDevicePerformanceLevel() >= MediaFuSetup.PERF_HIGH;
        if (highPlus) {
            trySet(handle, "remove_pouch_strength", 0.0);
            trySet(handle, "remove_nasolabial_folds_strength", 0.0);
        } else {
            trySet(handle, "remove_pouch_strength_mode2", 0.0);
            trySet(handle, "remove_nasolabial_folds_strength_mode2", 0.0);
        }
    }

    private static boolean isDualModeBeautyKey(String key) {
        return "remove_pouch_strength".equals(key)
                || "remove_pouch_strength_mode2".equals(key)
                || "remove_nasolabial_folds_strength".equals(key)
                || "remove_nasolabial_folds_strength_mode2".equals(key);
    }

    /** 去黑眼圈/去法令纹 mode1 与 mode2 不可叠写（对齐 color_level 注释） */
    private static void zeroAlternateBeautyKey(int handle, String activeKey) {
        if (handle <= 0 || activeKey == null) {
            return;
        }
        switch (activeKey) {
            case "remove_pouch_strength":
                trySet(handle, "remove_pouch_strength_mode2", 0.0);
                break;
            case "remove_pouch_strength_mode2":
                trySet(handle, "remove_pouch_strength", 0.0);
                break;
            case "remove_nasolabial_folds_strength":
                trySet(handle, "remove_nasolabial_folds_strength_mode2", 0.0);
                break;
            case "remove_nasolabial_folds_strength_mode2":
                trySet(handle, "remove_nasolabial_folds_strength", 0.0);
                break;
            default:
                break;
        }
    }

    static int setString(int handle, String key, String value) {
        if (handle <= 0 || key == null || value == null) {
            return -1;
        }
        return faceunity.fuItemSetParam(handle, key, value);
    }

    /**
     * Demo：enableSkinSegmentation = on/off，只写 enable_skinseg，不重配其它美颜。
     */
    static int applySkinSegmentation(int handle, double enable01) {
        if (handle <= 0) {
            return -1;
        }
        try {
            return faceunity.fuItemSetParam(handle, "enable_skinseg", enable01);
        } catch (Throwable t) {
            Log.w(TAG, "applySkinSegmentation", t);
            return -1;
        }
    }

    /**
     * 祛斑/全身磨皮/瞳孔/丰盈/皮肤分割等特殊算法写参。
     * 对齐 iOS setParam special：先 runtime 开关 → 写强度 → plump/delspot 再确认开关并回写强度。
     * 须在 {@link NamaRenderLock} 内调用（与 DualInput 串行）。
     */
    static int applySpecialAlgoParam(int handle, String key, double value) {
        if (handle <= 0 || key == null || key.isEmpty()) {
            return -1;
        }
        value = FuBeautyPerfGate.clampValue(key, value);
        if (!FuBeautyPerfGate.isParamAllowed(key)) {
            FuSpecialBeautySync.cacheValue(handle, key, value);
            if ("enable_skinseg".equals(key)) {
                return applySkinSegmentation(handle, value);
            }
            return setDouble(handle, key, value);
        }
        MediaFuSetup.ensureAdvancedBeautySwitches(handle);
        if ("enable_skinseg".equals(key)) {
            FuSpecialBeautySync.cacheValue(handle, key, value);
            return applySkinSegmentation(handle, value);
        }
        int code = setDouble(handle, key, value);
        if ("facial_plump".equals(key) || "delspot_level".equals(key)) {
            trySet(handle, "disable_delspot", 0.0);
            if ("facial_plump".equals(key)) {
                code = setDouble(handle, "facial_plump", value);
            } else {
                code = setDouble(handle, "delspot_level", value);
            }
        } else if ("body_blur_level".equals(key)
                || "intensity_eye_pupil".equals(key)
                || "enable_skinseg".equals(key)) {
            trySet(handle, "disable_delspot", 0.0);
        }
        if ("body_blur_level".equals(key)) {
            resetBlurCache();
            updateBeautyBlurEffectAfterRender(handle);
        }
        FuSpecialBeautySync.cacheValue(handle, key, value);
        return code;
    }

    /** Demo load 后默认：精细变形 + 渐变；抗锯齿尽量写，失败忽略 */
    private static volatile boolean sChangeFramesHoldZero = false;

    static void setChangeFramesHoldZero(boolean hold) {
        sChangeFramesHoldZero = hold;
    }

    static boolean isChangeFramesHoldZero() {
        return sChangeFramesHoldZero;
    }

    /** 对齐 FULiveDemo：0=人脸出现时立即生效，无美型淡入渐变 */
    static double changeFramesValue() {
        return 0.0;
    }

    static void applyDemoDefaults(int handle) {
        if (handle <= 0) {
            return;
        }
        try {
            faceunity.fuItemSetParam(handle, "face_shape", 4.0);
            faceunity.fuItemSetParam(handle, "face_shape_level", 1.0);
            faceunity.fuItemSetParam(handle, "change_frames", changeFramesValue());
            trySet(handle, "enable_warp_anti_alias", 1.0);
            trySet(handle, "warp_anti_alias", 1.0);
        } catch (Throwable t) {
            Log.w(TAG, "applyDemoDefaults", t);
        }
    }

    static void applyDeviceBlurDefaults(int handle) {
        if (handle <= 0) {
            return;
        }
        int level = MediaFuSetup.getDevicePerformanceLevel();
        // 对齐 FULiveDemo updateBeautyBlurEffect：二级以下固定精细磨皮
        int wantType = level >= MediaFuSetup.PERF_HIGH ? 3 : 2;
        int wantMask = 0;
        applyBlurIfChanged(handle, wantType, wantMask);
    }

    static void applyBlurForExposure(int handle, int exposureUi0to100) {
        if (handle <= 0) {
            return;
        }
        if (exposureUi0to100 < 45) {
            applyBlurIfChanged(handle, 2, 0);
        } else {
            applyDeviceBlurDefaults(handle);
        }
    }

    static void updateBeautyBlurEffectAfterRender(int handle) {
        if (handle <= 0 || faceunity.fuIsLibraryInit() == 0) {
            return;
        }
        int level = MediaFuSetup.getDevicePerformanceLevel();
        int wantType = level >= MediaFuSetup.PERF_HIGH ? 3 : 2;
        int wantMask = 0;
        try {
            double bodyBlur = 0.0;
            if (FuBeautyPerfGate.isParamAllowed("body_blur_level")) {
                bodyBlur = FuSpecialBeautySync.effectiveValue(handle, "body_blur_level");
            }
            if (bodyBlur > 0.001) {
                wantMask = 0;
            }
        } catch (Throwable ignored) {
        }
        applyBlurIfChanged(handle, wantType, wantMask);
    }

    static void resetBlurCache() {
        sAppliedBlurType = -1;
        sAppliedBlurMask = -1;
    }

    private static void applyBlurIfChanged(int handle, int wantType, int wantMask) {
        if (wantType == sAppliedBlurType && wantMask == sAppliedBlurMask) {
            return;
        }
        try {
            faceunity.fuItemSetParam(handle, "heavy_blur", 0.0);
            faceunity.fuItemSetParam(handle, "blur_type", (double) wantType);
            faceunity.fuItemSetParam(handle, "blur_use_mask", (double) wantMask);
            sAppliedBlurType = wantType;
            sAppliedBlurMask = wantMask;
        } catch (Throwable t) {
            Log.w(TAG, "applyBlurIfChanged", t);
        }
    }

    /**
     * 美白：对齐 iOS FuResolveBeautyParamKey，统一写基名 color_level（勿双写 mode2）。
     * 其它高性能美型项仍可用 mode 后缀。
     */
    static String resolveKey(String key) {
        if (key == null) {
            return "";
        }
        int level = MediaFuSetup.getDevicePerformanceLevel();
        boolean highPlus = level >= MediaFuSetup.PERF_HIGH;
        switch (key) {
            case "color_level":
            case "color_level_mode2":
                return "color_level";
            case "remove_pouch_strength":
            case "remove_pouch_strength_mode2":
                return highPlus ? "remove_pouch_strength_mode2" : "remove_pouch_strength";
            case "remove_nasolabial_folds_strength":
            case "remove_nasolabial_folds_strength_mode2":
                return highPlus
                        ? "remove_nasolabial_folds_strength_mode2"
                        : "remove_nasolabial_folds_strength";
            case "eye_enlarging":
            case "eye_enlarging_mode3":
                return highPlus ? "eye_enlarging_mode3" : "eye_enlarging";
            case "cheek_thinning":
                return "cheek_thinning";
            case "cheek_narrow":
            case "cheek_narrow_mode2":
                return highPlus ? "cheek_narrow_mode2" : "cheek_narrow";
            case "cheek_small":
            case "cheek_small_mode2":
                return highPlus ? "cheek_small_mode2" : "cheek_small";
            case "intensity_chin":
                return "intensity_chin";
            case "intensity_forehead":
            case "intensity_forehead_mode2":
                return highPlus ? "intensity_forehead_mode2" : "intensity_forehead";
            case "intensity_nose":
            case "intensity_nose_mode2":
                return highPlus ? "intensity_nose_mode2" : "intensity_nose";
            case "intensity_mouth":
            case "intensity_mouth_mode3":
                return highPlus ? "intensity_mouth_mode3" : "intensity_mouth";
            default:
                return key;
        }
    }

    private static void trySet(int handle, String key, double value) {
        try {
            faceunity.fuItemSetParam(handle, key, value);
        } catch (Throwable ignored) {
        }
    }
}
