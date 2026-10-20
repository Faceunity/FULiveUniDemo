package com.faceunity.nama;

import android.content.Context;
import android.opengl.GLES20;
import android.os.Build;
import android.util.Log;

import com.faceunity.core.faceunity.FUAIKit;
import com.faceunity.core.faceunity.FURenderKit;
import com.faceunity.core.faceunity.OffLineRenderHandler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 媒体/静图管线 FU 输入矩阵与算法子模块，对齐 FULiveDemo / iOS NamaModule。
 * <p>
 * 文档要点（CNamaSDK.h）：{@code fuSetFaceAlgorithmConfig} 在 load face AI 时决定子模块；
 * 按机型分层开关皮肤分割/祛斑/ARMeshV2。不调用未公开 SetUse*。
 */
final class MediaFuSetup {

    private static final String TAG = "FaceUnity-MediaFu";
    /** 对齐 FULiveDemo UIDevice+FURenderKit：Low=1 High=2 VeryHigh=3 Excellent=4 */
    static final int PERF_LOW = 1;
    static final int PERF_HIGH = 2;
    static final int PERF_VERY_HIGH = 3;
    static final int PERF_EXCELLENT = 4;

    private static final int PERF_UNKNOWN = -101;
    private static final double DEVICE_SCORE_ONE = 64.0;
    private static final double DEVICE_SCORE_TWO = 69.0;
    private static final double DEVICE_SCORE_THREE = 78.0;
    private static final double DEVICE_SCORE_FOUR = 85.0;
    private static final int FUAIFACE_ENABLE_ALL = 0;
    private static final int FUAIFACE_DISABLE_FACE_OCCU = 1 << 0;
    private static final int FUAIFACE_DISABLE_SKIN_SEG = 1 << 1;
    private static final int FUAIFACE_DISABLE_DEL_SPOT = 1 << 2;
    private static final int FUAIFACE_DISABLE_ARMESHV2 = 1 << 3;
    private static final int FUAIFACE_DISABLE_RACE = 1 << 4;
    private static final int FUAIFACE_DISABLE_LANDMARK_HP_OCCU = 1 << 5;
    private static final double CPU_PROPORTION = 0.6;
    private static final double GPU_PROPORTION = 0.4;
    private static final String DEVICE_LEVEL_PREFS = "faceunity_device_level";
    private static final String DEVICE_LEVEL_KEY = "level";

    private static volatile int sCachedPerfLevel = PERF_UNKNOWN;
    private static Context sAppContext;
    private static volatile double sCpuScore = 0.0;
    private static volatile double sGpuScore = 0.0;
    private static volatile double sDeviceScore = 0.0;
    private static volatile String sCpuName = "";
    private static volatile String sGpuName = "";

    private MediaFuSetup() {
    }

    static void setAppContext(Context context) {
        if (context != null) {
            sAppContext = context.getApplicationContext();
        }
    }

    /**
     * 对齐 Kotlin Demo 的 FuDeviceUtils：CPU 评分 60% + GPU 评分 40%，
     * 再按 64/69/78/85 分档。结果会缓存在本地，避免每次进页面重复创建离屏 GL。
     */
    static int getDevicePerformanceLevel() {
        if (sCachedPerfLevel != PERF_UNKNOWN) {
            return sCachedPerfLevel;
        }
        Context context = sAppContext;
        if (context != null) {
            try {
                int cached = context.getSharedPreferences(
                        DEVICE_LEVEL_PREFS, Context.MODE_PRIVATE
                ).getInt(DEVICE_LEVEL_KEY, PERF_UNKNOWN);
                if (cached != PERF_UNKNOWN) {
                    sCachedPerfLevel = cached;
                    Log.i(TAG, "devicePerformanceLevel cached=" + cached);
                    return cached;
                }
            } catch (Throwable t) {
                Log.w(TAG, "read device level cache", t);
            }
        }

        int level = -1;
        try {
            int special = judgeDeviceLevelInDeviceName();
            if (special != Integer.MIN_VALUE) {
                level = special;
            } else {
                sDeviceScore = getCpuScore() * CPU_PROPORTION + getGpuScore() * GPU_PROPORTION;
                if (sDeviceScore > DEVICE_SCORE_ONE) {
                    level = PERF_LOW;
                }
                if (sDeviceScore > DEVICE_SCORE_TWO) {
                    level = PERF_HIGH;
                }
                if (sDeviceScore > DEVICE_SCORE_THREE) {
                    level = PERF_VERY_HIGH;
                }
                if (sDeviceScore > DEVICE_SCORE_FOUR) {
                    level = PERF_EXCELLENT;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getDevicePerformanceLevel", t);
        }
        sCachedPerfLevel = level;
        if (context != null) {
            try {
                context.getSharedPreferences(
                        DEVICE_LEVEL_PREFS, Context.MODE_PRIVATE
                ).edit().putInt(DEVICE_LEVEL_KEY, level).apply();
            } catch (Throwable t) {
                Log.w(TAG, "write device level cache", t);
            }
        }
        Log.i(TAG, "devicePerformanceLevel=" + level
                + " score=" + sDeviceScore
                + " cpu=" + sCpuScore + "/" + sCpuName
                + " gpu=" + sGpuScore + "/" + sGpuName);
        return level;
    }

    static double getDeviceScore() {
        getDevicePerformanceLevel();
        return sDeviceScore;
    }

    private static double getCpuScore() {
        String cpuName = getCpuName();
        sCpuName = cpuName;
        ArrayList<Long> frequencies = getCpuFrequencies();
        Collections.sort(frequencies);
        Collections.reverse(frequencies);
        int cpuCores = Runtime.getRuntime().availableProcessors();
        if (cpuCores <= 4 || frequencies.isEmpty()) {
            sCpuScore = 65.0;
            return sCpuScore;
        }
        double maxFre = frequencies.get(0) / 1000.0 / 1000.0;
        if (cpuName.contains("qcom") || cpuName.contains("Qualcomm")) {
            sCpuScore = scoreQualcommCpu(maxFre);
        } else if (cpuName.contains("MT")) {
            sCpuScore = scoreMtkCpu(cpuName, maxFre);
        } else {
            sCpuScore = scoreDefaultCpu(maxFre);
        }
        return sCpuScore;
    }

    private static double getGpuScore() {
        final CountDownLatch done = new CountDownLatch(1);
        final double[] score = {65.0};
        final String[] renderer = {""};
        final String[] vendor = {""};
        OffLineRenderHandler handler = OffLineRenderHandler.getInstance();
        handler.onResume();
        handler.queueEvent(() -> {
            try {
                renderer[0] = safeGlString(GLES20.GL_RENDERER);
                vendor[0] = safeGlString(GLES20.GL_VENDOR);
                sGpuName = renderer[0];
                score[0] = scoreGpu(renderer[0], vendor[0]);
            } catch (Throwable t) {
                Log.w(TAG, "getGpuScore", t);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            handler.onPause();
        }
        sGpuScore = score[0];
        Log.i(TAG, "gpuScore renderer=" + renderer[0]
                + " vendor=" + vendor[0] + " score=" + sGpuScore);
        return sGpuScore;
    }

    private static double scoreGpu(String renderer, String vendor) {
        if ("Qualcomm".equals(vendor)) {
            return scoreQualcommGpu(renderer);
        }
        if ("ARM".equals(vendor)) {
            return scoreMtkGpu(renderer);
        }
        if (renderer.startsWith("PowerVR Rogue GE")) {
            return 55.0;
        }
        return 65.0;
    }

    private static double scoreDefaultCpu(double maxFre) {
        if (maxFre < 2.2) {
            return 65.0;
        }
        if (maxFre <= 2.4) {
            return interpolate(maxFre, 2.4, 70.0, 5.0, 0.2);
        }
        if (maxFre <= 2.8) {
            return interpolate(maxFre, 2.8, 75.0, 5.0, 0.4);
        }
        if (maxFre < 3.2) {
            return interpolate(maxFre, 3.2, 85.0, 10.0, 0.4);
        }
        return 90.0 + (maxFre - 3.2) / 0.2;
    }

    private static double scoreQualcommCpu(double maxFre) {
        if (maxFre < 1.9) {
            return 65.0;
        }
        if (maxFre <= 2.2) {
            return interpolate(maxFre, 2.2, 70.0, 5.0, 0.3);
        }
        if (maxFre <= 2.7) {
            return interpolate(maxFre, 2.7, 75.0, 5.0, 0.5);
        }
        if (maxFre <= 2.9) {
            return interpolate(maxFre, 2.9, 80.0, 5.0, 0.2);
        }
        if (maxFre <= 3.2) {
            return interpolate(maxFre, 3.2, 90.0, 10.0, 0.3);
        }
        return 95.0 + (maxFre - 3.3) / 0.1;
    }

    private static double scoreMtkCpu(String cpuName, double maxFre) {
        if (cpuName.length() > 7) {
            try {
                long version = Long.parseLong(cpuName.substring(2, 6));
                if (version < 6797) {
                    return 65.0;
                }
                if (version <= 6833) {
                    return interpolate(version, 6833, 70.0, 5.0, 36.0);
                }
                if (version <= 6895) {
                    return interpolate(version, 6895, 75.0, 5.0, 60.0);
                }
                if (version <= 6983) {
                    return interpolate(version, 6983, 80.0, 5.0, 88.0);
                }
                if (version < 6985) {
                    return interpolate(version, 6985, 85.0, 5.0, 2.0);
                }
                return 90.0 + (version - 6985) / 3.0;
            } catch (Throwable ignored) {
            }
        }
        if (maxFre < 2.2) {
            return 65.0;
        }
        if (maxFre <= 2.9) {
            return interpolate(maxFre, 2.9, 75.0, 10.0, 0.7);
        }
        if (maxFre < 3.3) {
            return interpolate(maxFre, 3.3, 85.0, 10.0, 0.4);
        }
        return 90.0 + (maxFre - 3.3) / 0.2;
    }

    private static double scoreQualcommGpu(String renderer) {
        if (renderer == null || !renderer.startsWith("Adreno")) {
            return 65.0;
        }
        String tail = renderer.substring(renderer.lastIndexOf(' ') + 1);
        int version = leadingInt(tail);
        if (version < 506) {
            return 65.0;
        }
        if (version <= 610) {
            return interpolate(version, 610, 70.0, 5.0, 104.0);
        }
        if (version <= 630) {
            return interpolate(version, 630, 75.0, 5.0, 20.0);
        }
        if (version <= 730) {
            return interpolate(version, 730, 85.0, 10.0, 100.0);
        }
        if (version < 740) {
            return interpolate(version, 740, 95.0, 10.0, 20.0);
        }
        return 95.0 + (version - 740) / 20.0;
    }

    private static double scoreMtkGpu(String renderer) {
        if (renderer == null || renderer.isEmpty()) {
            return 65.0;
        }
        if (renderer.startsWith("Mali-T830") || renderer.startsWith("Mali-G51")) {
            return 55.0;
        }
        if (renderer.startsWith("Mali")) {
            String first = renderer.split(" ")[0];
            int dash = first.indexOf('-');
            if (dash >= 0 && dash + 2 <= first.length()) {
                String version = first.substring(dash + 1);
                char family = version.charAt(0);
                int number = leadingInt(version.substring(1));
                if (family == 'T') {
                    return 65.0;
                }
                if (family == 'G') {
                    if (number >= 700 && number < 800) {
                        return 85.0;
                    }
                    if (number >= 600 && number < 700) {
                        return 80.0;
                    }
                    if (number < 50) {
                        return 65.0;
                    }
                    if (number < 60) {
                        return 66.0 + number - 50;
                    }
                    if (number < 70) {
                        return 67.0 + number - 60;
                    }
                    if (number < 80) {
                        return 68.0 + (number - 70) * 1.5;
                    }
                }
            }
        }
        if (renderer.startsWith("PowerVR")) {
            return 70.0;
        }
        if (renderer.startsWith("Immortalis")) {
            return 85.0;
        }
        return 65.0;
    }

    private static double interpolate(double value, double base, double baseScore,
                                      double intervalScore, double intervalValue) {
        return baseScore - (base - value) * intervalScore / intervalValue;
    }

    private static int leadingInt(String value) {
        if (value == null) {
            return 0;
        }
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(0, end));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String safeGlString(int name) {
        String value = GLES20.glGetString(name);
        return value != null ? value : "";
    }

    private static ArrayList<Long> getCpuFrequencies() {
        ArrayList<Long> result = new ArrayList<>();
        File cpuRoot = new File("/sys/devices/system/cpu");
        File[] files = cpuRoot.listFiles();
        if (files == null) {
            return result;
        }
        for (File file : files) {
            if (file == null || !file.getName().startsWith("cpu")) {
                continue;
            }
            File freq = new File(file, "cpufreq/cpuinfo_max_freq");
            if (!freq.exists()) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(freq))) {
                String raw = reader.readLine();
                if (raw != null && !raw.trim().isEmpty()) {
                    result.add(Long.parseLong(raw.trim()));
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static String getCpuName() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            String last = "";
            while ((line = reader.readLine()) != null) {
                last = line;
            }
            if (last.contains("Hardware")) {
                String[] parts = last.split(":\\s+", 2);
                if (parts.length == 2) {
                    return parts[1];
                }
            }
        } catch (Throwable ignored) {
        }
        return Build.HARDWARE != null ? Build.HARDWARE : "";
    }

    private static int judgeDeviceLevelInDeviceName() {
        String model = Build.MODEL != null ? Build.MODEL : "";
        if ("PRO 7 Plus".equals(model)) {
            return PERF_LOW;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * 对齐 Kotlin Demo UI 门槛：
     * body_blur >= 4
     * delspot / facial_plump >= 3
     * enable_skinseg >= 4
     * pupil 无门槛
     */
    static boolean isBeautyFeatureSupported(String key) {
        if (key == null || key.isEmpty()) {
            return true;
        }
        int level = getDevicePerformanceLevel();
        if ("body_blur_level".equals(key)) {
            return level >= PERF_EXCELLENT;
        }
        if ("delspot_level".equals(key) || "facial_plump".equals(key)) {
            return level >= PERF_VERY_HIGH;
        }
        if ("enable_skinseg".equals(key)) {
            return level >= PERF_EXCELLENT;
        }
        return true;
    }

    /**
     * 对齐 Demo 默认值：
     * body_blur=0 delspot=0 facial_plump=0 pupil=0.5 skinseg=0
     */
    static double getDemoDefaultValue(String key) {
        if ("body_blur_level".equals(key)) {
            return 0.0;
        }
        if ("delspot_level".equals(key)) {
            return 0.0;
        }
        if ("facial_plump".equals(key)) {
            return 0.0;
        }
        if ("intensity_eye_pupil".equals(key)) {
            return 0.5;
        }
        if ("enable_skinseg".equals(key)) {
            return 0.0;
        }
        return 0.0;
    }

    /** 必须在加载 face processor 前调用，配置与 Kotlin Demo 完全一致。 */
    static void enableFaceAlgorithmModules() {
        int level = getDevicePerformanceLevel();
        try {
            if (FuKitBridge.isActive()) {
                FUAIKit aiKit = FUAIKit.getInstance();
                invokeIfExists(aiKit, "fuSetFaceAlgorithmConfig",
                        new Class[]{int.class}, faceAlgorithmConfig(level));
                invokeIfExists(aiKit, "faceProcessorSetFaceLandmarkQuality",
                        new Class[]{int.class}, level >= PERF_HIGH ? 2 : 1);
                invokeIfExists(aiKit, "fuFaceProcessorSetFaceLandmarkHpOccu",
                        new Class[]{int.class}, 0);
                invokeIfExists(FURenderKit.getInstance(), "setDynamicQualityControl",
                        new Class[]{boolean.class}, level <= PERF_LOW);
            }
            Log.i(TAG, "enableFaceAlgorithmModules level=" + level
                    + " config=" + faceAlgorithmConfig(level));
        } catch (Throwable t) {
            Log.e(TAG, "enableFaceAlgorithmModules", t);
        }
    }

    private static int faceAlgorithmConfig(int level) {
        if (level <= PERF_LOW) {
            return FUAIFACE_DISABLE_FACE_OCCU
                    | FUAIFACE_DISABLE_SKIN_SEG
                    | FUAIFACE_DISABLE_DEL_SPOT
                    | FUAIFACE_DISABLE_ARMESHV2
                    | FUAIFACE_DISABLE_RACE
                    | FUAIFACE_DISABLE_LANDMARK_HP_OCCU;
        }
        if (level == PERF_HIGH) {
            return FUAIFACE_DISABLE_SKIN_SEG
                    | FUAIFACE_DISABLE_DEL_SPOT
                    | FUAIFACE_DISABLE_ARMESHV2
                    | FUAIFACE_DISABLE_RACE;
        }
        if (level == PERF_VERY_HIGH) {
            return FUAIFACE_DISABLE_SKIN_SEG;
        }
        return FUAIFACE_ENABLE_ALL;
    }

    private static void invokeIfExists(Object target, String method, Class<?>[] types, Object... args) {
        try {
            target.getClass().getMethod(method, types).invoke(target, args);
            Log.i(TAG, "invoke " + method + " ok");
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "invoke " + method + " skipped: method missing");
        } catch (Throwable t) {
            Log.w(TAG, "invoke " + method, t);
        }
    }

    private static String sSdkLogPath;
    private static long sSdkLogOffset;

    static void setSdkLogPath(String path) {
        sSdkLogPath = path;
        sSdkLogOffset = 0;
    }

    static String getSdkLogPath() {
        return sSdkLogPath != null ? sSdkLogPath : "";
    }

    static String drainSdkFileLog() {
        if (sSdkLogPath == null || sSdkLogPath.isEmpty()) {
            return "";
        }
        java.io.RandomAccessFile raf = null;
        try {
            java.io.File f = new java.io.File(sSdkLogPath);
            if (!f.exists()) {
                return "";
            }
            long len = f.length();
            if (len <= sSdkLogOffset) {
                return "";
            }
            raf = new java.io.RandomAccessFile(f, "r");
            raf.seek(sSdkLogOffset);
            int n = (int) Math.min(len - sSdkLogOffset, 512 * 1024);
            byte[] buf = new byte[n];
            int read = raf.read(buf);
            sSdkLogOffset = len;
            if (read <= 0) {
                return "";
            }
            String text = new String(buf, 0, read, java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder kept = new StringBuilder();
            for (String line : text.split("\n")) {
                String lower = line.toLowerCase();
                if (lower.contains("item set param")
                        || lower.contains("setparam")
                        || lower.contains("license")
                        || lower.contains("not open")
                        || lower.contains("skinseg")
                        || lower.contains("delspot")
                        || lower.contains("armesh")
                        || lower.contains("facial_plump")
                        || lower.contains("body_blur")
                        || lower.contains("algorithmconfig")
                        || lower.contains("disable_delspot")
                        || lower.contains("not support")
                        || lower.contains("module code")
                        || lower.contains("beauty")) {
                    if (kept.length() > 0) {
                        kept.append('\n');
                    }
                    kept.append(line.trim());
                }
            }
            return kept.toString();
        } catch (Throwable t) {
            Log.w(TAG, "drainSdkFileLog", t);
            return "";
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

}
