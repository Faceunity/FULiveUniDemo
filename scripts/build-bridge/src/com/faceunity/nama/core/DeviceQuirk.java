package com.faceunity.nama.core;

import android.os.Build;
import android.util.Log;

import java.util.Locale;

/**
 * 机型特例：vivo X9 / NEX 等。注意国内机 Build.MODEL 常是 PD1616，不含 "X9"。
 */
public final class DeviceQuirk {

    private static final String TAG = "FU-DeviceQuirk";

    private DeviceQuirk() {
    }

    private static final boolean VIVO;
    private static final boolean VIVO_X9_OR_NEX;
    private static final boolean HUAWEI;
    private static final boolean LEGACY_VIDEO_ENCODE;
    private static final boolean AGGRESSIVE_SURFACE_HIDE;

    static {
        String manu = lower(Build.MANUFACTURER);
        String brand = lower(Build.BRAND);
        String model = upper(Build.MODEL);
        String device = upper(Build.DEVICE);
        String product = upper(Build.PRODUCT);
        String finger = upper(Build.FINGERPRINT);
        String display = upper(Build.DISPLAY);
        String hardware = upper(Build.HARDWARE);
        // 拼一块方便匹配内部代号 / 市场名
        String blob = (model + " " + device + " " + product + " " + finger + " " + display + " " + hardware)
                .replace('-', ' ')
                .replace('_', ' ');

        VIVO = manu.contains("vivo")
                || brand.contains("vivo")
                || blob.contains("VIVO");
        HUAWEI = manu.contains("huawei")
                || brand.contains("huawei")
                || brand.contains("honor")
                || manu.contains("honor")
                || blob.contains("HUAWEI")
                || blob.contains("HONOR");

        // 市场名（整词，避免 X90 / NEXUS 误伤）
        boolean nameHit = containsToken(blob, "X9")
                || containsToken(blob, "X9S")
                || containsToken(blob, "X9I")
                || containsToken(blob, "X9PLUS")
                || containsToken(blob, "NEX")
                || containsToken(blob, "NEXS")
                || containsToken(blob, "NEXA");
        // 内部型号（Build.MODEL 常见值）
        boolean codeHit = containsToken(blob, "PD1616")   // X9 / X9i
                || containsToken(blob, "PD1616B") // X9s
                || containsToken(blob, "PD1619")  // X9Plus
                || containsToken(blob, "PD1635")  // X9s Plus
                || containsToken(blob, "PD1805")  // NEX S
                || containsToken(blob, "PD1806")  // NEX A
                || containsToken(blob, "PD1821")  // NEX 双屏
                || containsToken(blob, "PD1924")  // NEX 3
                || containsToken(blob, "PD1950"); // NEX 3S

        VIVO_X9_OR_NEX = VIVO && (nameHit || codeHit);
        LEGACY_VIDEO_ENCODE = Build.VERSION.SDK_INT < 29 || VIVO_X9_OR_NEX || HUAWEI;
        // 老 vivo / 华为：SurfaceView 不跟 alpha，切页必须卸顶 + 缩到 1px
        AGGRESSIVE_SURFACE_HIDE = VIVO_X9_OR_NEX || HUAWEI || Build.VERSION.SDK_INT < 24;

        Log.e(TAG, "vivo=" + VIVO
                + " huawei=" + HUAWEI
                + " x9OrNex=" + VIVO_X9_OR_NEX
                + " legacyEncode=" + LEGACY_VIDEO_ENCODE
                + " surfaceHide=" + AGGRESSIVE_SURFACE_HIDE
                + " model=" + Build.MODEL
                + " device=" + Build.DEVICE
                + " product=" + Build.PRODUCT);
    }

    private static String lower(String s) {
        return s != null ? s.toLowerCase(Locale.US) : "";
    }

    private static String upper(String s) {
        return s != null ? s.toUpperCase(Locale.US) : "";
    }

    /** 整词匹配，避免 PD1616 误伤 PD16160 */
    private static boolean containsToken(String blob, String token) {
        int i = blob.indexOf(token);
        while (i >= 0) {
            char before = i == 0 ? ' ' : blob.charAt(i - 1);
            int end = i + token.length();
            char after = end >= blob.length() ? ' ' : blob.charAt(end);
            if (!Character.isLetterOrDigit(before) && !Character.isLetterOrDigit(after)) {
                return true;
            }
            i = blob.indexOf(token, i + 1);
        }
        return false;
    }

    public static boolean isVivo() {
        return VIVO;
    }

    public static boolean isHuaweiFamily() {
        return HUAWEI;
    }

    /** vivo X9 / NEX 机型翻转已废弃：改固定 BufferMatrix=4 */
    @Deprecated
    public static boolean needsBeautyShapeXFlip() {
        return false;
    }

    /** 统一 NV21→NV12 再喂编码器；直喂 NV21 会导致华为/vivo 人脸发蓝 */
    public static boolean encoderFeedsNv21Direct() {
        return false;
    }

    public static boolean isVivoX9OrNex() {
        return VIVO_X9_OR_NEX;
    }

    /**
     * 仅 vivo X9/NEX：AAC 硬编/封装常无法解析，录制走纯视频轨。
     * 华为等仍录 AAC（否则 P20 Pro 等会无声）。
     */
    public static boolean wantsVideoOnlyRecord() {
        return VIVO_X9_OR_NEX;
    }

    /** 老机硬编不稳：横帧 + orientationHint（音轨是否写入见 wantsVideoOnlyRecord） */
    public static boolean needsLegacyVideoEncode() {
        return LEGACY_VIDEO_ENCODE;
    }

    /** 切页时 SurfaceView 必须强隐藏（alpha/平移无效） */
    public static boolean needsAggressiveSurfaceHide() {
        return AGGRESSIVE_SURFACE_HIDE;
    }

    /** 华为 Mali：GPU 硬解+读回易黑屏，走 CPU 解码 + 相机 GL 美颜 */
    public static boolean prefersCpuVideoPreview() {
        return HUAWEI;
    }
}
