package com.faceunity.nama;

import com.faceunity.wrapper.faceunity;

import java.util.HashMap;
import java.util.Map;

/**
 * 对齐 iOS {@code FuReconfirmSpecialBeautySwitches}：每帧 fuRender 前重 latch 特殊算法开关。
 */
final class FuSpecialBeautySync {

    private static final Map<String, Double> SPECIAL_CACHE = new HashMap<>();

    private FuSpecialBeautySync() {
    }

    static void cacheValue(int beautyHandle, String key, double value) {
        if (beautyHandle <= 0 || key == null || key.isEmpty()) {
            return;
        }
        synchronized (SPECIAL_CACHE) {
            SPECIAL_CACHE.put(cacheKey(beautyHandle, key), value);
        }
    }

    static boolean hasCachedValue(int beautyHandle, String key) {
        if (beautyHandle <= 0 || key == null || key.isEmpty()) {
            return false;
        }
        synchronized (SPECIAL_CACHE) {
            return SPECIAL_CACHE.containsKey(cacheKey(beautyHandle, key));
        }
    }

    /** 祛斑痘或面部丰盈任一开启时需 NV21 BufferMatrix(4) 校正 Y 轴 */
    static boolean isDelspotOrPlumpActive(int beautyHandle) {
        if (beautyHandle <= 0) {
            return false;
        }
        return effectiveValue(beautyHandle, "delspot_level") > 0.001
                || effectiveValue(beautyHandle, "facial_plump") > 0.001;
    }

    /** 对齐 iOS FuEffectiveSpecialValue：get 被 SDK 帧间清零时用 cache */
    static double effectiveValue(int beautyHandle, String key) {
        if (beautyHandle <= 0 || key == null || key.isEmpty()) {
            return 0.0;
        }
        double live = 0.0;
        try {
            live = faceunity.fuItemGetParam(beautyHandle, key);
        } catch (Throwable ignored) {
        }
        Double cached;
        synchronized (SPECIAL_CACHE) {
            cached = SPECIAL_CACHE.get(cacheKey(beautyHandle, key));
        }
        double c = cached != null ? cached : 0.0;
        if (cached != null) {
            return c;
        }
        return live;
    }

    /** 对齐 iOS FuReconfirmSpecialBeautySwitches；Android 不写 use_facial_plump；body_blur 由 updateBeautyBlurEffectAfterRender 处理 */
    static void reconfirmBeforeRender(int beautyHandle) {
        if (beautyHandle <= 0 || faceunity.fuIsLibraryInit() == 0) {
            return;
        }
        try {
            double plump = effectiveValue(beautyHandle, "facial_plump");
            trySet(beautyHandle, "disable_delspot", 0.0);
            faceunity.fuItemSetParam(beautyHandle, "facial_plump", plump);

            double delspot = effectiveValue(beautyHandle, "delspot_level");
            if (delspot > 0.001) {
                trySet(beautyHandle, "disable_delspot", 0.0);
                faceunity.fuItemSetParam(beautyHandle, "delspot_level", delspot);
            }

            double pupil = effectiveValue(beautyHandle, "intensity_eye_pupil");
            if (pupil > 0.001) {
                trySet(beautyHandle, "disable_delspot", 0.0);
                faceunity.fuItemSetParam(beautyHandle, "intensity_eye_pupil", pupil);
            }

            double skinseg = effectiveValue(beautyHandle, "enable_skinseg");
            if (skinseg > 0.001) {
                faceunity.fuItemSetParam(beautyHandle, "enable_skinseg", skinseg);
            }

            double eyeBright = effectiveValue(beautyHandle, "eye_bright");
            if (eyeBright > 0.001) {
                trySet(beautyHandle, "eye_bright", eyeBright);
                trySet(beautyHandle, "eye_bright_v2", eyeBright);
            }
        } catch (Throwable ignored) {
        }
    }

    static String dumpDiag(int beautyHandle) {
        if (beautyHandle <= 0) {
            return "specialBeauty handle=0";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("specialBeauty handle=").append(beautyHandle);
        String[] keys = {
                "enable_skinseg", "delspot_level", "facial_plump",
                "intensity_eye_pupil", "body_blur_level", "disable_delspot", "color_level"
        };
        for (String key : keys) {
            double eff = effectiveValue(beautyHandle, key);
            boolean cached = hasCachedValue(beautyHandle, key);
            sb.append(' ').append(key).append('=').append(eff);
            if (cached) {
                sb.append("(cached)");
            }
        }
        return sb.toString();
    }

    private static String cacheKey(int handle, String key) {
        return handle + ":" + key;
    }

    private static void trySet(int handle, String key, double value) {
        try {
            faceunity.fuItemSetParam(handle, key, value);
        } catch (Throwable ignored) {
        }
    }
}
