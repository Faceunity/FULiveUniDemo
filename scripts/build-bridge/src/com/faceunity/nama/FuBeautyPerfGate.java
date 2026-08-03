package com.faceunity.nama;

import android.util.Log;

import com.faceunity.wrapper.faceunity;

import java.util.HashMap;
import java.util.Map;

/**
 * 对齐 FULiveDemo beauty_skin/shape.json + 设备分级文档：UI 与 setParam 双重门禁。
 * performanceLevel &lt; 0 表示无限制；否则 deviceLevel 必须 &gt;= performanceLevel。
 */
final class FuBeautyPerfGate {

    private static final String TAG = "FaceUnity-PerfGate";
    private static final Map<String, Integer> MIN_LEVEL = new HashMap<>();

    static {
        // 美肤
        put("blur_level", -1);
        put("body_blur_level", 4);
        put("delspot_level", 3);
        put("facial_plump", 3);
        put("color_level", 1);
        put("color_level_mode2", 1);
        put("red_level", 1);
        put("clarity", 1);
        put("sharpen", 1);
        put("face_threed", 1);
        put("eye_bright", 1);
        put("tooth_whiten", 1);
        put("remove_pouch_strength", 1);
        put("remove_pouch_strength_mode2", 1);
        put("remove_nasolabial_folds_strength", 1);
        put("remove_nasolabial_folds_strength_mode2", 1);
        put("enable_skinseg", 4);
        // 美型
        put("cheek_thinning", -1);
        put("cheek_v", 1);
        put("cheek_narrow", 1);
        put("cheek_narrow_mode2", 1);
        put("cheek_short", 1);
        put("cheek_small", 1);
        put("cheek_small_mode2", 1);
        put("intensity_cheekbones", 1);
        put("intensity_lower_jaw", 1);
        put("eye_enlarging", -1);
        put("eye_enlarging_mode3", -1);
        put("intensity_eye_circle", 1);
        put("intensity_eye_pupil", -1);
        put("intensity_chin", 1);
        put("intensity_forehead", 1);
        put("intensity_forehead_mode2", 1);
        put("intensity_nose", -1);
        put("intensity_nose_mode2", -1);
        put("intensity_mouth", 1);
        put("intensity_mouth_mode3", 1);
        put("intensity_lip_thick", 2);
        put("intensity_eye_height", 2);
        put("intensity_canthus", 1);
        put("intensity_eye_lid", 2);
        put("intensity_eye_space", 1);
        put("intensity_eye_rotate", 1);
        put("intensity_long_nose", 1);
        put("intensity_philtrum", 1);
        put("intensity_smile", 1);
        put("intensity_brow_height", 2);
        put("intensity_brow_space", 2);
        put("intensity_brow_thick", 2);
    }

    private FuBeautyPerfGate() {
    }

    private static void put(String key, int level) {
        MIN_LEVEL.put(key, level);
    }

    static int requiredLevel(String key) {
        if (key == null || key.isEmpty()) {
            return -1;
        }
        Integer need = MIN_LEVEL.get(key);
        return need != null ? need : -1;
    }

    static boolean isParamAllowed(String key) {
        int need = requiredLevel(key);
        if (need < 0) {
            return true;
        }
        return MediaFuSetup.getDevicePerformanceLevel() >= need;
    }

    /** 不允许时强制写 0（或中性值），避免高配算法在低配机跑满负载 */
    static double clampValue(String key, double value) {
        if (isParamAllowed(key)) {
            return value;
        }
        if (Math.abs(value) > 0.001) {
            Log.i(TAG, "block key=" + key + " value=" + value
                    + " deviceLevel=" + MediaFuSetup.getDevicePerformanceLevel()
                    + " need=" + requiredLevel(key));
        }
        if ("intensity_eye_pupil".equals(key)) {
            return 0.5;
        }
        if (isBidirectionalKey(key)) {
            return 0.5;
        }
        return 0.0;
    }

    private static boolean isBidirectionalKey(String key) {
        return "intensity_chin".equals(key)
                || "intensity_forehead".equals(key)
                || "intensity_forehead_mode2".equals(key)
                || "intensity_mouth".equals(key)
                || "intensity_mouth_mode3".equals(key)
                || "intensity_lip_thick".equals(key)
                || "intensity_eye_height".equals(key)
                || "intensity_eye_space".equals(key)
                || "intensity_eye_rotate".equals(key)
                || "intensity_long_nose".equals(key)
                || "intensity_philtrum".equals(key)
                || "intensity_brow_height".equals(key)
                || "intensity_brow_space".equals(key)
                || "intensity_brow_thick".equals(key);
    }

    /** loadBundle / 切档后：清掉当前档位不允许的参数 */
    static void enforceOnHandle(int handle) {
        if (handle <= 0) {
            return;
        }
        for (Map.Entry<String, Integer> e : MIN_LEVEL.entrySet()) {
            String key = e.getKey();
            if (isParamAllowed(key)) {
                continue;
            }
            double zero = clampValue(key, 1.0);
            try {
                String resolved = BeautyParamApplier.resolveKey(key);
                faceunity.fuItemSetParam(handle, resolved, zero);
                if (!resolved.equals(key)) {
                    faceunity.fuItemSetParam(handle, key, zero);
                }
                FuSpecialBeautySync.cacheValue(handle, key, zero);
            } catch (Throwable t) {
                Log.w(TAG, "enforceOnHandle " + key, t);
            }
        }
        applyRuntimeSwitchesForLevel(handle);
    }

    /** 算法 + 道具开关：低配机关闭高负载子模块 */
    static void applyRuntimeSwitchesForLevel(int handle) {
        if (handle <= 0) {
            return;
        }
        int level = MediaFuSetup.getDevicePerformanceLevel();
        try {
            if (level < MediaFuSetup.PERF_VERY_HIGH) {
                faceunity.fuItemSetParam(handle, "delspot_level", 0.0);
                faceunity.fuItemSetParam(handle, "facial_plump", 0.0);
                faceunity.fuItemSetParam(handle, "disable_delspot", 1.0);
            } else {
                faceunity.fuItemSetParam(handle, "disable_delspot", 0.0);
            }
            if (level < MediaFuSetup.PERF_EXCELLENT) {
                faceunity.fuItemSetParam(handle, "body_blur_level", 0.0);
                faceunity.fuItemSetParam(handle, "enable_skinseg", 0.0);
                FuSpecialBeautySync.cacheValue(handle, "body_blur_level", 0.0);
                FuSpecialBeautySync.cacheValue(handle, "enable_skinseg", 0.0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyRuntimeSwitchesForLevel", t);
        }
        BeautyParamApplier.resetBlurCache();
        BeautyParamApplier.applyDeviceBlurDefaults(handle);
    }
}
