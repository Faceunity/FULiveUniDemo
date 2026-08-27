package com.faceunity.nama.core;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import com.alibaba.fastjson.JSONObject;
import com.faceunity.nama.NamaModule;
import com.faceunity.wrapper.faceunity;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 前置摄像头 + FaceUnity 预览（GLSurfaceView + setZOrderOnTop 穿透 WebView）。
 * <p>
 * 相机对齐 FULiveDemoDroid：双输入 {@code fuRenderDualInput}（NV21+OES，FULL 含高级美颜，
 * 失败回退 {@code fuDualInputToTexture}）；单输入 {@code fuRenderToTexture}（OES）。
 */
public class BeautyCameraGLView extends GLSurfaceView implements GLSurfaceView.Renderer {

    private static final String TAG = "FaceUnity-Camera";

    /** fuRenderDualInput 第 6 参：OES 外部纹理 */
    private static final int FLAG_EXTERNAL_OES = faceunity.FU_ADM_FLAG_EXTERNAL_OES_TEXTURE;
    /** RGBA readback（fuRenderToRgbaImage） */
    private static final int FLAG_RGBA_READBACK = faceunity.FU_ADM_FLAG_ENABLE_READBACK;
    /** CNamaSDK FU_FORMAT_NV21_BUFFER */
    private static final int FORMAT_NV21_BUFFER = 2;

    private static final float[] TEX_COORDS = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
    };

    private static final float[] FULL_QUAD = {
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
    };

    private final Object frameLock = new Object();
    private final AtomicBoolean previewStarted = new AtomicBoolean(false);
    /** 同一 View 上 Surface/EGL 重建次数。>1 时旧 beauty item 已失效，须在本 GL 重建 */
    private int surfaceCreateCount = 0;
    private static volatile String lastError = "";
    private static volatile int frameCount = 0;
    private static volatile int renderOkCount = 0;
    private static volatile int lastRenderRet = 0;
    private static volatile int lastEglW = 0;
    private static volatile int lastEglH = 0;
    private static volatile int lastLayoutW = 0;
    private static volatile int lastLayoutH = 0;
    private static volatile int lastTracking = -1;
    private static volatile boolean lastPreviewStarted = false;
    private static volatile int lastBeautyHandle = 0;
    private static volatile int lastPreviewTexId = 0;
    private static volatile int lastFuTexId = 0;
    private static volatile int lastFuSysErr = 0;
    private static volatile int lastGlError = 0;
    private static volatile int lastFrameW = 0;
    private static volatile int lastFrameH = 0;
    private static volatile int lastFuOutW = 0;
    private static volatile int lastFuOutH = 0;
    private static volatile int lastFps = 0;
    /** 单帧美颜渲染耗时（毫秒），对齐 FU 调试 HUD 的 rendertime */
    private static volatile int lastRenderTimeMs = 0;
    private static volatile boolean beautyEnabled = true;
    /** Camera1 选横帧（如 1280×720）；显示转正只在 GL，不动 FU 输出尺寸 */
    private static volatile int targetPreviewW = 1280;
    private static volatile int targetPreviewH = 720;
    /** 切摄 / 改分辨率 / 单双输入切换：丢前 N 帧，避免旧纹理闪一帧（对齐 FULiveDemoDroid mSkippedFrames） */
    private static final int SWITCH_SKIP_FRAMES = 6;
    private volatile int skipFrames = 0;
    /** 应用进后台 / onHide：停相机，保留 EGL（勿 GONE 毁 Surface） */
    private final AtomicBoolean previewPaused = new AtomicBoolean(false);
    /** 多任务/后台：用上一帧美颜纹理冻帧，禁止清黑 */
    private final AtomicBoolean freezeFrame = new AtomicBoolean(false);
    /**
     * 强隐藏：切页时不绘冻帧（老 vivo SurfaceView 不跟 alpha，冻帧仍会盖住下一页）。
     * 与 freeze 搭配：停采 + 不往 Surface 画内容。
     */
    private final AtomicBoolean coverHidden = new AtomicBoolean(false);
    private volatile int frozenTexId = 0;
    private static final Object PENDING_BEAUTY_LOCK = new Object();
    private static final java.util.ArrayList<PendingBeautyParam> PENDING_BEAUTY_PARAMS =
            new java.util.ArrayList<>();

    private static final class PendingBeautyParam {
        final int handle;
        final String key;
        final double value;
        final boolean special;

        PendingBeautyParam(int handle, String key, double value, boolean special) {
            this.handle = handle;
            this.key = key;
            this.value = value;
            this.special = special;
        }
    }
    /** GPU 视频硬解预览（MediaCodec → OES → fuRenderToTexture） */
    private VideoGpuPreviewHelper videoGpuPreview;

    /** 视频美颜首帧：fuOnCameraChange + StillLike，对齐静图点位 */
    private final AtomicBoolean videoStillMatrixPending = new AtomicBoolean(false);
    /** 导入视频期间：相机 GL 专供 processVideoRgbaFrame，禁止 freeze 卡死 GL 队列 */
    private final AtomicBoolean videoBeautyHosting = new AtomicBoolean(false);
    /** 视频首帧 warmup 剩余次数（对齐 ImageBeautyProcessor 8 帧） */
    private final AtomicInteger videoWarmupLeft = new AtomicInteger(0);
    private volatile int lastExposureUi = 50;
    /** 用户拖过曝光后锁定 AE，避免测光/对焦把亮度拉闪 */
    private final AtomicBoolean exposureLockedByUser = new AtomicBoolean(false);
    /** 合并拖动中的曝光请求，避免每帧 setParameters 闪退 */
    private volatile int pendingExposureUi = 50;
    private volatile boolean pendingExposureFinalizeLock = true;
    private volatile int lastAppliedEvTarget = Integer.MIN_VALUE;
    private volatile int lastBlurExposureBucket = -1;
    private final Runnable applyPendingExposureRunnable = () ->
            applyExposureOnCameraThread(pendingExposureUi, pendingExposureFinalizeLock);

    public static void setTargetPreviewSize(int width, int height) {
        if (width > 0 && height > 0) {
            targetPreviewW = Math.max(width, height);
            targetPreviewH = Math.min(width, height);
        }
    }

    /** 进页默认 720，不跨会话记忆上次分辨率 */
    public static void resetTargetPreviewSizeToDefault() {
        targetPreviewW = 1280;
        targetPreviewH = 720;
    }

    public static int getTargetPreviewWidth() {
        return targetPreviewW;
    }

    public static int getTargetPreviewHeight() {
        return targetPreviewH;
    }

    public static JSONObject getPreviewStatsJson() {
        int shortSide = 0;
        if (lastFrameW > 0 && lastFrameH > 0) {
            shortSide = Math.min(lastFrameW, lastFrameH);
        }
        JSONObject stats = new JSONObject();
        stats.put("fps", lastFps);
        stats.put("resolution", shortSide);
        stats.put("renderTime", lastRenderTimeMs);
        stats.put("tracking", lastTracking);
        stats.put("frameWidth", lastFrameW);
        stats.put("frameHeight", lastFrameH);
        String label = shortSide > 0
                ? (shortSide + "." + lastFps + "." + lastRenderTimeMs)
                : ("0." + lastFps + "." + lastRenderTimeMs);
        stats.put("label", label);
        return stats;
    }

    public static void setBeautyEnabledGlobal(boolean enabled) {
        beautyEnabled = enabled;
    }

    /** 对齐 iOS enqueueBeautyParam：合并同名参数，在 onDrawFrame fuRender 前 flush */
    public static void enqueueBeautyParam(int handle, String key, double value, boolean special) {
        if (handle <= 0 || key == null || key.isEmpty()) {
            return;
        }
        synchronized (PENDING_BEAUTY_LOCK) {
            for (int i = PENDING_BEAUTY_PARAMS.size() - 1; i >= 0; i--) {
                PendingBeautyParam p = PENDING_BEAUTY_PARAMS.get(i);
                if (p.handle == handle && p.key.equals(key) && p.special == special) {
                    PENDING_BEAUTY_PARAMS.remove(i);
                    break;
                }
            }
            PENDING_BEAUTY_PARAMS.add(new PendingBeautyParam(handle, key, value, special));
        }
    }

    private static void flushPendingBeautyParams() {
        java.util.ArrayList<PendingBeautyParam> batch;
        synchronized (PENDING_BEAUTY_LOCK) {
            if (PENDING_BEAUTY_PARAMS.isEmpty()) {
                return;
            }
            batch = new java.util.ArrayList<>(PENDING_BEAUTY_PARAMS);
            PENDING_BEAUTY_PARAMS.clear();
        }
        for (PendingBeautyParam p : batch) {
            try {
                if (p.special) {
                    BeautyParamApplier.applySpecialAlgoParam(p.handle, p.key, p.value);
                } else {
                    BeautyParamApplier.setDouble(p.handle, p.key, p.value);
                }
            } catch (Throwable t) {
                Log.w(TAG, "flushPendingBeautyParam " + p.key, t);
            }
        }
    }

    private void holdDrawLastFrame() {
        int tex = lastFuTexId > 0 ? lastFuTexId : frozenTexId;
        if (tex > 0 && program > 0) {
            GLES20.glViewport(0, 0, eglWidth, eglHeight);
            drawPreviewTexture(tex, 0f);
        }
    }

    private void beginPreviewTransition() {
        skipFrames = SWITCH_SKIP_FRAMES;
        previewStarted.set(false);
        frozenTexId = 0;
        lastFuTexId = 0;
        cachedFuOutW = 0;
        cachedFuOutH = 0;
        synchronized (frameLock) {
            frameReady = false;
            nv21Buffer = null;
            nv21RenderBuffer = null;
        }
        // 画一帧黑屏，不切 CONTINUOUSLY（避免快速闪黑框）；新相机帧到达后 requestRender 自然驱动
        requestRender();
    }

    private void drawSwitchingBlackFrame() {
        if (eglWidth < 32 || eglHeight < 32) {
            return;
        }
        GLES20.glViewport(0, 0, eglWidth, eglHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }

    public static String getLastError() {
        return lastError != null ? lastError : "";
    }

    public static String getPreviewDiag() {
        return "frameCount=" + frameCount
                + " renderOk=" + renderOkCount
                + " lastRenderRet=" + lastRenderRet
                + " egl=" + lastEglW + "x" + lastEglH
                + " layout=" + lastLayoutW + "x" + lastLayoutH
                + " cameraFrame=" + lastFrameW + "x" + lastFrameH
                + " fuOut=" + lastFuOutW + "x" + lastFuOutH
                + " tracking=" + lastTracking
                + " beautyHandle=" + lastBeautyHandle
                + " previewTex=" + lastPreviewTexId
                + " fuTex=" + lastFuTexId
                + " fuSysErr=" + lastFuSysErr
                + " glErr=" + lastGlError
                + " previewStarted=" + lastPreviewStarted
                + " cameraError=" + getLastError();
    }

    private static void setLastError(String message) {
        lastError = message != null ? message : "";
    }

    private Camera camera;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private SurfaceTexture previewSurfaceTexture;
    private int previewTextureId;
    private byte[] nv21Buffer;
    /** GL 线程专用，避免与相机回调争用同一块 NV21（会导致闪烁） */
    private byte[] nv21RenderBuffer;
    private int frameWidth;
    private int frameHeight;
    private int frameId;
    private boolean frameReady;
    /** 兼容前端开关；渲染统一走 fuRenderTexture FULL，不再分 ItemsEx2 双/单输入 API */
    private final AtomicBoolean dualInputEnabled = new AtomicBoolean(true);
    private final AtomicBoolean oesFrameAvailable = new AtomicBoolean(false);

    private int layoutWidth;
    private int layoutHeight;
    private int eglWidth;
    private int eglHeight;
    private int cachedFuOutW;
    private int cachedFuOutH;
    /** FU 检测朝向（度），仅用于检脸，不改变输出纹理尺寸 */
    private int fuInputDegrees = 90;
    private int fuFlipX = 0;
    private int fuRotateMode = faceunity.FU_ROTATION_MODE_90;
    /** 避免每帧重复写 SDK：0 或 CCROT0_FLIPVERTICAL(4) */
    private int lastCameraBufferMatrix = -1;

    private int program;
    private int aPosition;
    private int aTexCoord;
    private int uTexture;
    private int uMirror;
    private int uMirrorY;
    private int uRotate;
    private int uBlur;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texBuffer;
    private Runnable releaseFinishedCallback;
    private Runnable destroyTimeoutToken;
    private static final Object CAMERA_LOCK = new Object();
    private final AtomicBoolean destroying = new AtomicBoolean(false);
    private final AtomicBoolean capturePending = new AtomicBoolean(false);
    private volatile boolean useFrontCamera = true;
    private long fpsTickStart = 0;
    private int fpsTickCount = 0;
    private CaptureCallback captureCallback;
    private final BeautyVideoRecorder videoRecorder = new BeautyVideoRecorder();
    /** 录像 A/V 对齐：音视频 PTS 均相对此墙钟起点 */
    private long recordStartNs = 0L;
    private long lastRecordOfferNs = 0L;
    private static final long LEGACY_RECORD_MIN_INTERVAL_NS = 66_000_000L;
    /**
     * 老机（API&lt;24，如 vivo X9）硬编常不支持竖屏分辨率（720x1280）→ 文件「无法解析」。
     * true：按传感器横帧编码 + orientationHint；false：竖屏像素直接编码。
     */
    private boolean recordEncodeLandscape = false;
    /** 录像用：预览同路径 FBO 读回后的 NV21 */
    private byte[] recordNv21Scratch;
    /** 录像用：FBO RGBA 读回缓冲 */
    private byte[] recordRgbaScratch;
    /** 录像用：复用 glReadPixels DirectBuffer */
    private ByteBuffer recordRgbaDirect;
    /** 拍照/录像共用离屏 FBO（竖屏，套用预览旋转/镜像） */
    private int captureFbo;
    private int captureColorTex;
    private int captureFboW;
    private int captureFboH;

    public interface CaptureCallback {
        void onSuccess(String path);

        void onError(String message);
    }

    public void setBeautyEnabled(boolean enabled) {
        beautyEnabled = enabled;
        int handle = FuBeautyHandle.cameraHandle;
        if (handle > 0 && faceunity.fuIsLibraryInit() != 0) {
            try {
                faceunity.fuItemSetParam(handle, "is_beauty_on", enabled ? 1.0 : 0.0);
            } catch (Throwable t) {
                Log.w(TAG, "setBeautyEnabled is_beauty_on", t);
            }
        }
    }

    /** 单/双输入切换：丢 3 帧避免切换瞬间闪旧纹理 */
    public void setDualInputEnabled(boolean dual) {
        boolean changed = dualInputEnabled.getAndSet(dual) != dual;
        Log.i(TAG, "setDualInputEnabled=" + dual + " changed=" + changed);
        if (changed) {
            skipFrames = SWITCH_SKIP_FRAMES;
            final int handle = FuBeautyHandle.cameraHandle;
            queueEvent(() -> {
                if (handle > 0 && faceunity.fuIsLibraryInit() != 0) {
                    MediaFuSetup.ensureSpecialBeautyAlgorithmsEnabled();
                    MediaFuSetup.enableAdvancedBeautyRuntime(handle);
                    try {
                        faceunity.fuItemSetParam(handle, "disable_delspot", 0.0);
                    } catch (Throwable ignored) {
                    }
                    FuSpecialBeautySync.reconfirmBeforeRender(handle);
                }
            });
            requestRender();
        }
    }

    public int getLastExposureUi() {
        return lastExposureUi;
    }

    public boolean isExposureLockedByUser() {
        return exposureLockedByUser.get();
    }

    /** 相机已 release / 即将 reopen：勿让 lastApplied 与真实 EV 脱节导致误判跳过写参 */
    private void invalidateExposureApplyCache() {
        lastAppliedEvTarget = Integer.MIN_VALUE;
    }

    public boolean isDualInputEnabled() {
        return dualInputEnabled.get();
    }

    /**
     * 点击对焦：localX/Y 为预览框内 CSS 坐标，previewW/H 为预览框 CSS 尺寸。
     * Camera.Area 坐标系相对传感器，需按 displayOrientation 旋转，不能直接用屏幕归一化。
     */
    public void tapToFocus(float localX, float localY, float previewW, float previewH) {
        if (previewW <= 0 || previewH <= 0) {
            return;
        }
        float nx = Math.max(0f, Math.min(1f, localX / previewW));
        float ny = Math.max(0f, Math.min(1f, localY / previewH));
        // 前置镜像预览：触摸点水平翻转后再映射传感器
        if (useFrontCamera) {
            nx = 1f - nx;
        }
        final int displayOrientation = getDisplayRotation();
        final int[] sensorXY = mapViewNormToSensorFocus(nx, ny, displayOrientation);
        final int focusX = sensorXY[0];
        final int focusY = sensorXY[1];
        Log.i(TAG, "tapToFocus view=(" + nx + "," + ny + ") sensor=(" + focusX + "," + focusY
                + ") orient=" + displayOrientation + " front=" + useFrontCamera);
        ensureCameraThread();
        cameraHandler.post(() -> {
            Camera cam = camera;
            if (cam == null) {
                return;
            }
            try {
                cam.cancelAutoFocus();
                Camera.Parameters params = cam.getParameters();
                List<String> modes = params.getSupportedFocusModes();
                if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
                if (params.getMaxNumFocusAreas() > 0) {
                    int half = 150;
                    Rect area = new Rect(
                            clampFocus(focusX - half),
                            clampFocus(focusY - half),
                            clampFocus(focusX + half),
                            clampFocus(focusY + half)
                    );
                    List<Camera.Area> areas = new java.util.ArrayList<>();
                    areas.add(new Camera.Area(area, 1000));
                    params.setFocusAreas(areas);
                    // 用户已锁 EV 时不要改测光区，否则暗部会闪噪点
                    if (!exposureLockedByUser.get() && params.getMaxNumMeteringAreas() > 0) {
                        params.setMeteringAreas(areas);
                    }
                }
                cam.setParameters(params);
                cam.autoFocus((success, c) -> {
                    Log.i(TAG, "autoFocus done success=" + success);
                    // 用户已锁 EV 时不要反复冲曝光；仅在中性 EV 时跟随测光
                    if (!exposureLockedByUser.get()) {
                        applyExposureOnCameraThread(lastExposureUi, true);
                    }
                    cameraHandler.postDelayed(() -> {
                        try {
                            if (c == null || camera != c) {
                                return;
                            }
                            Camera.Parameters p = c.getParameters();
                            List<String> m = p.getSupportedFocusModes();
                            if (m != null && m.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                                c.setParameters(p);
                            }
                            if (exposureLockedByUser.get()) {
                                applyExposureOnCameraThread(lastExposureUi, true);
                            }
                        } catch (Exception ignored) {
                        }
                    }, 1800);
                });
            } catch (Exception e) {
                Log.w(TAG, "tapToFocus", e);
            }
        });
    }

    /**
     * 预览归一化点 → Camera.Area 传感器坐标（-1000..1000）。
     * displayOrientation 与 setDisplayOrientation 一致（竖屏通常 90）。
     */
    private static int[] mapViewNormToSensorFocus(float nx, float ny, int displayOrientation) {
        float x = nx;
        float y = ny;
        switch (displayOrientation) {
            case 90:
                // 竖屏：视图 x→传感器 -y，视图 y→传感器 x
                return new int[]{
                        clampFocus((int) (y * 2000f - 1000f)),
                        clampFocus((int) ((1f - x) * 2000f - 1000f))
                };
            case 270:
                return new int[]{
                        clampFocus((int) ((1f - y) * 2000f - 1000f)),
                        clampFocus((int) (x * 2000f - 1000f))
                };
            case 180:
                return new int[]{
                        clampFocus((int) ((1f - x) * 2000f - 1000f)),
                        clampFocus((int) ((1f - y) * 2000f - 1000f))
                };
            default:
                return new int[]{
                        clampFocus((int) (x * 2000f - 1000f)),
                        clampFocus((int) (y * 2000f - 1000f))
                };
        }
    }

    private static int mapUiToEvTarget(int deviceMin, int deviceMax, int value) {
        if (deviceMin == 0 && deviceMax == 0) {
            return 0;
        }
        int target;
        if (value >= 50) {
            target = Math.round(deviceMax * ((value - 50) / 50f));
        } else {
            target = Math.round(deviceMin * ((50 - value) / 50f));
        }
        return Math.max(deviceMin, Math.min(deviceMax, target));
    }

    /** 在 startPreview 之前写入 EV（对齐 iOS：只写 bias，不锁 AE） */
    private void seedExposureOnParameters(Camera.Parameters params, int uiValue) {
        int deviceMin = params.getMinExposureCompensation();
        int deviceMax = params.getMaxExposureCompensation();
        if (deviceMin == 0 && deviceMax == 0) {
            return;
        }
        int target = mapUiToEvTarget(deviceMin, deviceMax, uiValue);
        params.setExposureCompensation(target);
        lastAppliedEvTarget = target;
    }

    private void applyExposureOnCameraThread(int value, boolean finalizeLock) {
        Camera cam = camera;
        if (cam == null) {
            return;
        }
        try {
            Camera.Parameters params = cam.getParameters();
            int deviceMin = params.getMinExposureCompensation();
            int deviceMax = params.getMaxExposureCompensation();
            if (deviceMin == 0 && deviceMax == 0) {
                return;
            }
            int target = mapUiToEvTarget(deviceMin, deviceMax, value);
            int currentEv = params.getExposureCompensation();
            if (target == lastAppliedEvTarget && target == currentEv) {
                return;
            }
            params.setExposureCompensation(target);
            cam.setParameters(params);
            lastAppliedEvTarget = target;
        } catch (Exception e) {
            Log.w(TAG, "setExposureCompensation", e);
        }
    }

    /** exposure0to100：50=EV0 中性；上半映射到 0..max，下半映射到 min..0（对齐 Demo） */
    public void setExposureCompensation(int exposure0to100) {
        setExposureCompensation(exposure0to100, true);
    }

    /**
     * @param finalizeLock true=抬手可锁 AE；false=拖动中仅写 EV
     */
    public void setExposureCompensation(int exposure0to100, boolean finalizeLock) {
        final int value = Math.max(0, Math.min(100, exposure0to100));
        lastExposureUi = value;
        pendingExposureUi = value;
        pendingExposureFinalizeLock = finalizeLock;
        if (value != 50) {
            exposureLockedByUser.set(true);
        } else {
            exposureLockedByUser.set(false);
        }
        int h = FuBeautyHandle.cameraHandle;
        if (h > 0) {
            BeautyParamApplier.applyBlurForExposure(h, value);
        }
        ensureCameraThread();
        cameraHandler.removeCallbacks(applyPendingExposureRunnable);
        // 拖动：尽快合并到下一 tick；抬手：立即提交
        cameraHandler.postDelayed(applyPendingExposureRunnable, finalizeLock ? 0L : 16L);
    }

    private static int clampFocus(int v) {
        return Math.max(-1000, Math.min(1000, v));
    }

    public void switchCameraFacing() {
        if (skipFrames > 0) {
            return;
        }
        // 先设 skipFrames 拦截所有待渲染帧，再切 facing，避免旧帧用新 mirror 渲染出倒帧
        skipFrames = SWITCH_SKIP_FRAMES;
        useFrontCamera = !useFrontCamera;
        // 预写 FU 矩阵：确保新相机首帧渲染时 flipX/rotateMode 已正确
        queueEvent(() -> {
            try {
                if (faceunity.fuIsLibraryInit() != 0) {
                    applyInputCameraMatrixForFacing(useFrontCamera);
                    faceunity.fuOnCameraChange();
                }
            } catch (Throwable ignored) {
            }
        });
        previewStarted.set(false);
        frozenTexId = 0;
        lastFuTexId = 0;
        cachedFuOutW = 0;
        cachedFuOutH = 0;
        synchronized (frameLock) {
            frameReady = false;
            nv21Buffer = null;
            nv21RenderBuffer = null;
        }
        requestRender();
        BeautyParamApplier.setChangeFramesHoldZero(true);
        int h = FuBeautyHandle.cameraHandle;
        if (h > 0 && faceunity.fuIsLibraryInit() != 0) {
            try {
                faceunity.fuItemSetParam(h, "change_frames", 0.0);
            } catch (Throwable ignored) {
            }
        }
        ensureCameraThread();
        cameraHandler.post(() -> {
            releaseCameraSync();
            queueEvent(this::openCameraOnGlThread);
        });
    }

    public void restartPreview() {
        if (skipFrames > 0) {
            return;
        }
        beginPreviewTransition();
        ensureCameraThread();
        cameraHandler.post(() -> {
            releaseCameraSync();
            queueEvent(this::openCameraOnGlThread);
        });
    }

    /** 渲染时使用的 facing 快照：onDrawFrame 开头捕获，避免切换中途 useFrontCamera 被改导致倒帧 */
    private volatile boolean renderFrontCamera = true;

    private float mirrorFlipX() {
        return renderFrontCamera ? 1f : 0f;
    }

    private float mirrorFlipY() {
        return 0f;
    }

    public void capturePhoto(Context context, CaptureCallback callback) {
        if (context == null || callback == null) {
            return;
        }
        captureCallback = callback;
        capturePending.set(true);
        requestRender();
    }

    public boolean isRecordingVideo() {
        return videoRecorder.isRecording();
    }

    public void startVideoRecord() throws Exception {
        int w;
        int h;
        synchronized (frameLock) {
            w = frameWidth;
            h = frameHeight;
        }
        if (w < 16 || h < 16) {
            throw new IllegalStateException("预览未就绪");
        }
        // 老机/X9/NEX：硬编竖屏分辨率常无法播放；改横帧 + orientationHint
        recordEncodeLandscape = DeviceQuirk.needsLegacyVideoEncode();
        int encW;
        int encH;
        int orientationHint;
        if (recordEncodeLandscape) {
            encW = w & ~1;
            encH = h & ~1;
            int maxSide = Math.max(encW, encH);
            int cap = DeviceQuirk.isVivoX9OrNex() ? 960 : 1280;
            if (maxSide > cap) {
                float scale = (float) cap / maxSide;
                encW = Math.max(16, ((int) (encW * scale)) & ~1);
                encH = Math.max(16, ((int) (encH * scale)) & ~1);
            }
            orientationHint = getDisplayRotation();
        } else {
            // 新机：与拍照同一套竖屏像素，orientationHint=0
            encW = h & ~1;
            encH = w & ~1;
            orientationHint = 0;
        }
        int need = encW * encH * 3 / 2;
        if (recordNv21Scratch == null || recordNv21Scratch.length < need) {
            recordNv21Scratch = new byte[need];
        }
        int rgbaNeed = encW * encH * 4;
        if (recordRgbaScratch == null || recordRgbaScratch.length < rgbaNeed) {
            recordRgbaScratch = new byte[rgbaNeed];
        }
        recordStartNs = System.nanoTime();
        lastRecordOfferNs = 0L;
        // 录制期间连续渲染，避免 WHEN_DIRTY 丢帧导致几乎无内容 / 停录失败
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        videoRecorder.start(getContext(), encW, encH, orientationHint, recordStartNs);
        Log.i(TAG, "startVideoRecord enc=" + encW + "x" + encH
                + " hint=" + orientationHint
                + " landscapeEncode=" + recordEncodeLandscape);
        requestRender();
    }

    public void stopVideoRecord(Context context, CaptureCallback callback) {
        // 立刻停收帧标记在 recorder 内；编码收尾异步，勿堵 UI
        videoRecorder.stop(context, new BeautyVideoRecorder.Callback() {
            @Override
            public void onSuccess(String path) {
                setRenderMode(RENDERMODE_WHEN_DIRTY);
                if (callback != null) {
                    callback.onSuccess(path);
                }
            }

            @Override
            public void onError(String message) {
                setRenderMode(RENDERMODE_WHEN_DIRTY);
                if (callback != null) {
                    callback.onError(message);
                }
            }
        });
    }

    public void cancelVideoRecord() {
        videoRecorder.cancel();
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public boolean isPreviewStarted() {
        return previewStarted.get();
    }

    /**
     * 异步销毁：必须等「相机 release + GL 线程 fuOnDeviceLostSafe」都完成后再回调。
     * 若只等相机、不等 deviceLost，JS 会抢先 loadAIModel/loadBundle，随后旧 GL 的
     * deviceLost 会把刚加载的 AI/item 清掉 → 导入无美颜、回相机黑屏。
     * queueEvent 若因 GL 暂停永不执行，则超时兜底补一次 deviceLost 再回调。
     */
    public void destroyPreviewAsync(Runnable onFinished) {
        destroyPreviewAsync(false, onFinished);
    }

    /**
     * @param keepSession true：只释放相机/本 View GL 资源，不调 fuOnDeviceLostSafe，保留 AI/item
     */
    public void destroyPreviewAsync(boolean keepSession, Runnable onFinished) {
        if (!destroying.compareAndSet(false, true)) {
            if (onFinished != null) {
                new Handler(Looper.getMainLooper()).post(onFinished);
            }
            return;
        }
        releaseFinishedCallback = onFinished;
        previewStarted.set(false);
        lastPreviewStarted = false;
        setVisibility(VISIBLE);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        synchronized (frameLock) {
            frameReady = false;
            nv21Buffer = null;
            nv21RenderBuffer = null;
        }
        cachedFuOutW = 0;
        cachedFuOutH = 0;

        final boolean keep = keepSession;
        final AtomicInteger pending = new AtomicInteger(2);
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Handler main = new Handler(Looper.getMainLooper());
        final Runnable oneDone = () -> {
            if (pending.decrementAndGet() > 0) {
                return;
            }
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            if (destroyTimeoutToken != null) {
                main.removeCallbacks(destroyTimeoutToken);
                destroyTimeoutToken = null;
            }
            notifyReleaseFinished();
        };

        destroyTimeoutToken = () -> {
            if (finished.get()) {
                return;
            }
            Log.e(TAG, "destroyPreviewAsync timeout pending=" + pending.get() + " keep=" + keep);
            if (!keep) {
                try {
                    MediaFuSetup.deviceLostOnCurrentGl();
                } catch (Throwable ignored) {
                }
            }
            pending.set(0);
            oneDone.run();
        };
        main.postDelayed(destroyTimeoutToken, 1500);

        releaseCameraAsync(oneDone);
        try {
            queueEvent(() -> {
                try {
                    releaseGlResourcesOnGlThread(keep);
                } finally {
                    oneDone.run();
                }
            });
            requestRender();
        } catch (Exception e) {
            Log.e(TAG, "queueEvent destroy failed, fallback", e);
            if (!keep) {
                try {
                    MediaFuSetup.deviceLostOnCurrentGl();
                } catch (Throwable ignored) {
                }
            }
            oneDone.run();
        }
        cancelVideoRecord();
    }

    public void bindLayoutSize(int width, int height) {
        if (width <= 32 || height <= 32) {
            return;
        }
        layoutWidth = width;
        layoutHeight = height;
        lastLayoutW = width;
        lastLayoutH = height;
        queueEvent(this::rebuildVertexBuffer);
        requestRender();
    }

    /**
     * 输出分辨率必须与 NV21 输入一致，绝对不能对调。
     * 对调 + BufferMatrix 会在检测到人脸后把画面缩成底部一条。
     */
    private void updateOutputResolutionIfNeeded() {
        int w;
        int h;
        synchronized (frameLock) {
            if (frameWidth <= 32 || frameHeight <= 32) {
                return;
            }
            w = frameWidth;
            h = frameHeight;
        }
        w = w & ~1;
        h = h & ~1;
        if (w <= 32 || h <= 32 || (w == cachedFuOutW && h == cachedFuOutH)) {
            return;
        }
        cachedFuOutW = w;
        cachedFuOutH = h;
        lastFuOutW = w;
        lastFuOutH = h;
        faceunity.fuSetOutputResolution(w, h);
        queueEvent(this::rebuildVertexBuffer);
    }

    /** GL 顺时针 90° 后，横帧在屏幕上按竖屏比例 center-crop */
    private boolean displayContentSize(int[] outWh) {
        int fw;
        int fh;
        synchronized (frameLock) {
            fw = frameWidth;
            fh = frameHeight;
        }
        if (fw <= 0 || fh <= 0) {
            if (cachedFuOutW <= 0 || cachedFuOutH <= 0) {
                return false;
            }
            fw = cachedFuOutW;
            fh = cachedFuOutH;
        }
        outWh[0] = Math.min(fw, fh);
        outWh[1] = Math.max(fw, fh);
        return outWh[0] > 0 && outWh[1] > 0;
    }

    public BeautyCameraGLView(Context context) {
        super(context);
        initGlSurface();
    }

    public BeautyCameraGLView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initGlSurface();
    }

    private void initGlSurface() {
        setEGLContextClientVersion(3);
        // 相机独立 EGL（稳定）；视频 GPU 美颜在相机 GL 跑完后 RGBA 读回，勿跨上下文贴纹理
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        // soft-hide 时保留 EGL，视频美颜帧仍可 queueEvent 到本 GL
        setPreserveEGLContextOnPause(true);
        Log.e(TAG, "BeautyCameraGLView created");
    }

    /**
     * 页面 unload：只释放 Camera，保留 GLSurfaceView 与 FU GL 上下文（避免二次进入黑屏）。
     */
    public void releaseCameraKeepAlive(Runnable onFinished) {
        previewStarted.set(false);
        lastPreviewStarted = false;
        setVisibility(VISIBLE);
        synchronized (frameLock) {
            frameReady = false;
            nv21Buffer = null;
            nv21RenderBuffer = null;
        }
        releaseFinishedCallback = onFinished;
        releaseCameraAsync(() -> notifyReleaseFinished());
    }

    @Override
    protected void onDetachedFromWindow() {
        if (!destroying.get()) {
            stopPreview();
        }
        super.onDetachedFromWindow();
        Log.e(TAG, "onDetachedFromWindow");
    }

    /** 导入视频前：下一帧 GL 上重置为静图同款矩阵，并预热多帧避免首帧原片 */
    public void armVideoStillMatrix() {
        videoStillMatrixPending.set(true);
        videoWarmupLeft.set(8);
    }

    /**
     * 视频美颜宿主模式：停相机采集但保持 GL 线程活跃（华为等 freeze 后 queueEvent 不跑 → 黑屏）。
     */
    public void enterVideoBeautyHosting() {
        videoBeautyHosting.set(true);
        coverHidden.set(false);
        freezeFrame.set(false);
        previewPaused.set(false);
        previewStarted.set(false);
        lastPreviewStarted = false;
        invalidateExposureApplyCache();
        if (cameraHandler != null) {
            cameraHandler.removeCallbacks(applyPendingExposureRunnable);
        }
        releaseCameraAsync(null);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        armVideoStillMatrix();
        try {
            queueEvent(() -> {
                try {
                    if (faceunity.fuIsLibraryInit() != 0) {
                        faceunity.fuOnCameraChange();
                        MediaFuSetup.applyStillLikeBufferMatrix();
                        faceunity.fuSetFaceProcessorDetectMode(1);
                    }
                } catch (Throwable ignored) {
                }
            });
            requestRender();
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "enterVideoBeautyHosting");
    }

    public void exitVideoBeautyHosting() {
        if (!videoBeautyHosting.compareAndSet(true, false)) {
            return;
        }
        videoWarmupLeft.set(0);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        Log.i(TAG, "exitVideoBeautyHosting");
    }

    public boolean isVideoBeautyHosting() {
        return videoBeautyHosting.get();
    }

    /** 导出前：在相机 GL 上同步落地视频/静图矩阵，避免 exporter 花屏或黑帧 */
    public void prepareForVideoExport() {
        armVideoStillMatrix();
        final Object lock = new Object();
        final boolean[] done = {false};
        try {
            queueEvent(() -> {
                try {
                    if (faceunity.fuIsLibraryInit() != 0) {
                        faceunity.fuOnCameraChange();
                        MediaFuSetup.applyStillLikeBufferMatrix();
                        faceunity.fuSetFaceProcessorDetectMode(0);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "prepareForVideoExport", t);
                } finally {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            });
            requestRender();
            long deadline = System.currentTimeMillis() + 800L;
            synchronized (lock) {
                while (!done[0] && System.currentTimeMillis() < deadline) {
                    lock.wait(16);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "prepareForVideoExport wait", t);
        }
    }

    /**
     * 进后台/多任务：停采集并冻住上一帧，保留 EGL，禁止清黑。
     * 相机对象可释放；曝光 UI 值由 lastExposureUi 在重开后恢复。
     */
    public void freezePreview() {
        if (lastFuTexId > 0) {
            frozenTexId = lastFuTexId;
        }
        freezeFrame.set(frozenTexId > 0);
        previewPaused.set(true);
        previewStarted.set(false);
        lastPreviewStarted = false;
        invalidateExposureApplyCache();
        if (cameraHandler != null) {
            cameraHandler.removeCallbacks(applyPendingExposureRunnable);
        }
        // 保留 nv21 无妨；冻帧走 frozenTexId，不依赖新帧
        releaseCameraAsync(null);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        try {
            requestRender();
        } catch (Throwable ignored) {
        }
        Log.e(TAG, "freezePreview tex=" + frozenTexId);
    }

    /** soft-hide / 交视频：停采集。老机额外 coverHidden，避免冻帧仍盖住下一页 */
    public void hidePreview() {
        if (videoBeautyHosting.get()) {
            releaseCameraAsync(null);
            Log.i(TAG, "hidePreview skip freeze (videoBeautyHosting)");
            return;
        }
        if (DeviceQuirk.needsAggressiveSurfaceHide()) {
            coverHidden.set(true);
            freezeFrame.set(false);
            previewPaused.set(true);
            previewStarted.set(false);
            lastPreviewStarted = false;
            invalidateExposureApplyCache();
            if (cameraHandler != null) {
                cameraHandler.removeCallbacks(applyPendingExposureRunnable);
            }
            releaseCameraAsync(null);
            setRenderMode(RENDERMODE_WHEN_DIRTY);
            try {
                queueEvent(() -> {
                    try {
                        GLES20.glClearColor(0f, 0f, 0f, 0f);
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    } catch (Throwable ignored) {
                    }
                });
                requestRender();
            } catch (Throwable ignored) {
            }
            Log.e(TAG, "hidePreview aggressive coverHidden");
            return;
        }
        freezePreview();
        Log.e(TAG, "hidePreview → freezePreview");
    }

    /** 交给视频 GL 前：停采集并 onPause，避免双 GLSurfaceView 抢 current / 搞坏 Nama */
    public void pauseGlForHandoff() {
        hidePreview();
        try {
            onPause();
        } catch (Throwable t) {
            Log.w(TAG, "pauseGlForHandoff onPause", t);
        }
    }

    /** 视频拆除后：onResume + 重开相机，并在本 GL 上复位 Nama */
    public void resumeGlAfterHandoff() {
        try {
            onResume();
        } catch (Throwable t) {
            Log.w(TAG, "resumeGlAfterHandoff onResume", t);
        }
        resumePreview();
        try {
            queueEvent(() -> {
                try {
                    if (faceunity.fuIsLibraryInit() != 0 && FuBeautyHandle.cameraHandle > 0) {
                        faceunity.fuOnCameraChange();
                        MediaFuSetup.enableAdvancedBeautyRuntime(FuBeautyHandle.cameraHandle);
                        MediaFuSetup.ensureBeautyOn(FuBeautyHandle.cameraHandle);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "resumeGlAfterHandoff nama", t);
                }
            });
            requestRender();
        } catch (Throwable ignored) {
        }
    }

    public void resumePreview() {
        exitVideoBeautyHosting();
        coverHidden.set(false);
        freezeFrame.set(false);
        previewPaused.set(false);
        setVisibility(VISIBLE);
        setAlpha(1f);
        // soft-hide 的 hidePreview 未调 onPause，此处禁止无配对 onResume（会卡死 GL → 回页黑屏）
        // 成对 onResume 只走 resumeGlAfterHandoff
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        requestRender();
        // 视频帧可能改过 identity 矩阵：回相机先复位
        try {
            queueEvent(() -> {
                try {
                    if (faceunity.fuIsLibraryInit() != 0) {
                        faceunity.fuOnCameraChange();
                        faceunity.fuSetFaceProcessorDetectMode(1);
                        int h = FuBeautyHandle.cameraHandle;
                        if (h > 0) {
                            MediaFuSetup.enableAdvancedBeautyRuntime(h);
                            MediaFuSetup.ensureBeautyOn(h);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "resumePreview reset nama", t);
                }
            });
        } catch (Throwable ignored) {
        }
        // 暂停时已 release 相机，回前台重开；等 Surface 有效
        previewStarted.set(false);
        if (getHolder() != null && getHolder().getSurface() != null
                && getHolder().getSurface().isValid()) {
            queueEvent(this::openCameraOnGlThread);
        } else {
            postDelayed(() -> {
                try {
                    queueEvent(this::openCameraOnGlThread);
                } catch (Throwable t) {
                    Log.w(TAG, "resumePreview delayed open", t);
                }
            }, 80);
        }
        Log.e(TAG, "resumePreview reopening camera exposureUi=" + lastExposureUi
                + " locked=" + exposureLockedByUser.get());
    }

    /**
     * 视频预览美颜：在相机 GL（Nama 唯一宿主）上处理一帧 RGBA。
     * 视频 Surface 只负责显示，禁止在视频 GL 上调 Nama。
     *
     * @return 美颜后的 RGBA；失败/超时返回原始副本
     */
    public byte[] processVideoRgbaFrame(final byte[] rgbaIn, final int width, final int height,
                                        final int beautyHandle) {
        return processVideoRgbaFrame(rgbaIn, width, height, beautyHandle, 1);
    }

    /**
     * @param minPasses 最少渲染遍数。暂停切滤镜/调参须 ≥2：filter_name 常在下一帧才生效，
     *                  单遍会导致「第一次无效、第二次变成上一次」。
     */
    public byte[] processVideoRgbaFrame(final byte[] rgbaIn, final int width, final int height,
                                        final int beautyHandle, final int minPasses) {
        if (rgbaIn == null || width <= 0 || height <= 0 || beautyHandle <= 0) {
            return rgbaIn;
        }
        if (faceunity.fuIsLibraryInit() == 0) {
            return rgbaIn;
        }
        final byte[] work = java.util.Arrays.copyOf(rgbaIn, rgbaIn.length);
        final Object lock = new Object();
        final boolean[] done = {false};
        final int[] retCode = {-1};
        final int warmupNeed = Math.max(0, videoWarmupLeft.get());
        final int wantPasses = Math.max(1, minPasses);
        final long waitMs = warmupNeed > 0 ? 2000L : (wantPasses > 1 ? 800L
                : (DeviceQuirk.isHuaweiFamily() ? 300L : 50L));
        try {
            queueEvent(() -> {
                try {
                    // 对齐静图：CCROT0_FLIPVERTICAL 纠点位，再 CPU 翻正（勿再显示翻 Y）
                    if (videoStillMatrixPending.compareAndSet(true, false)) {
                        try {
                            faceunity.fuOnCameraChange();
                        } catch (Throwable ignored) {
                        }
                    }
                    MediaFuSetup.applyStillLikeBufferMatrix();
                    // 对齐 iOS 视频预览：image 模式检脸
                    faceunity.fuSetFaceProcessorDetectMode(1);
                    faceunity.fuSetOutputResolution(width, height);
                    final boolean applyBeauty = beautyEnabled || videoBeautyHosting.get();
                    if (applyBeauty) {
                        MediaFuSetup.ensureBeautyOn(beautyHandle);
                        // 调参/单遍预览勿每帧 applyDemoDefaults，否则 change_frames 等会叠加重美颜
                        if (warmupNeed > 0) {
                            MediaFuSetup.ensureAdvancedBeautySwitches(beautyHandle);
                        }
                    } else {
                        try {
                            faceunity.fuItemSetParam(beautyHandle, "is_beauty_on", 0.0);
                        } catch (Throwable ignored) {
                        }
                    }
                    // 首帧 warmup：多遍渲染（每遍从原图重喂），避免第一次导入仍是原片
                    // 暂停调参：至少 2 遍，吃掉 filter_name 一帧延迟
                    int loops = warmupNeed > 0
                            ? Math.max(3, Math.min(warmupNeed, 8))
                            : wantPasses;
                    loops = Math.max(loops, wantPasses);
                    byte[] src = loops > 1 ? java.util.Arrays.copyOf(work, work.length) : null;
                    synchronized (faceunity.class) {
                        flushPendingBeautyParams();
                        FuSpecialBeautySync.reconfirmBeforeRender(beautyHandle);
                        BeautyParamApplier.updateBeautyBlurEffectAfterRender(beautyHandle);
                        final int bufFlags = FLAG_RGBA_READBACK;
                        for (int i = 0; i < loops; i++) {
                            if (i > 0 && src != null) {
                                System.arraycopy(src, 0, work, 0, src.length);
                            }
                            int fid = ++frameId;
                            retCode[0] = faceunity.fuRenderToRgbaImage(
                                    work,
                                    width,
                                    height,
                                    fid,
                                    new int[]{beautyHandle},
                                    bufFlags
                            );
                            try {
                                // 播放预览：去掉每帧 glFinish，系统录像高分辨率时否则极卡
                                if (warmupNeed > 0 || wantPasses > 1) {
                                    GLES20.glFinish();
                                }
                            } catch (Throwable ignored) {
                            }
                            // 暂停刷新：跑满 minPasses，确保新滤镜落到 readback
                            if (warmupNeed <= 0 && wantPasses > 1) {
                                continue;
                            }
                            if (retCode[0] >= 0 && faceunity.fuIsTracking() > 0 && i >= 1) {
                                // 已检出人脸：再喂一次原图拿稳定美颜帧
                                if (src != null) {
                                    System.arraycopy(src, 0, work, 0, src.length);
                                    fid = ++frameId;
                                    retCode[0] = faceunity.fuRenderToRgbaImage(
                                            work,
                                            width,
                                            height,
                                            fid,
                                            new int[]{beautyHandle},
                                            bufFlags
                                    );
                                    try {
                                        if (warmupNeed > 0 || wantPasses > 1) {
                                            GLES20.glFinish();
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                                break;
                            }
                        }
                    }
                    if (retCode[0] >= 0) {
                        BeautyVideoGLView.flipRgbaVertical(work, width, height);
                        if (BeautyVideoGLView.isMostlyBlackRgba(work, width, height)) {
                            retCode[0] = -1;
                        } else if (warmupNeed > 0) {
                            videoWarmupLeft.set(0);
                        }
                    }
                    BeautyVideoGLView.forceOpaqueRgba(work);
                    try {
                        lastTracking = faceunity.fuIsTracking();
                        NamaModule.onFaceTrackingUpdated(lastTracking > 0);
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "processVideoRgbaFrame", t);
                    retCode[0] = -1;
                } finally {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            });
            requestRender();
            long deadline = System.currentTimeMillis() + waitMs;
            synchronized (lock) {
                while (!done[0] && System.currentTimeMillis() < deadline) {
                    lock.wait(16);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "processVideoRgbaFrame schedule", t);
            return rgbaIn;
        }
        if (!done[0] || retCode[0] < 0) {
            return rgbaIn;
        }
        return work;
    }

    public void destroyPreview() {
        stopPreview();
        Log.e(TAG, "destroyPreview");
    }

    public void stopPreview() {
        queueEvent(this::releaseCameraOnGlThread);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        uTexture = GLES20.glGetUniformLocation(program, "uTexture");
        uMirror = GLES20.glGetUniformLocation(program, "uMirror");
        uMirrorY = GLES20.glGetUniformLocation(program, "uMirrorY");
        uRotate = GLES20.glGetUniformLocation(program, "uRotate");
        uBlur = GLES20.glGetUniformLocation(program, "uBlur");
        texBuffer = toFloatBuffer(TEX_COORDS);
        vertexBuffer = toFloatBuffer(FULL_QUAD);
        rebuildVertexBuffer();
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        faceunity.fuSetForceUseGL2(1);
        cachedFuOutW = 0;
        cachedFuOutH = 0;
        MediaFuSetup.enableFaceAlgorithmModules();
        surfaceCreateCount++;
        // 切系统导航后 Activity 重建：park 改 Z-order / setFixedSize 会毁掉第一层 EGL。
        // 第二次 onSurfaceCreated 必须在本 GL 重建 beauty item，否则有画无美颜或黑屏。
        if (surfaceCreateCount > 1 && faceunity.fuIsLibraryInit() != 0) {
            try {
                NamaSdkManager.recreateCameraBeautyItemOnCurrentGl();
            } catch (Throwable t) {
                Log.w(TAG, "onSurfaceCreated recreate beauty item", t);
            }
        }
        if (faceunity.fuIsLibraryInit() != 0 && FuBeautyHandle.cameraHandle > 0) {
            MediaFuSetup.enableAdvancedBeautyRuntime(FuBeautyHandle.cameraHandle);
            if (beautyEnabled) {
                faceunity.fuItemSetParam(FuBeautyHandle.cameraHandle, "is_beauty_on", 1.0);
            } else {
                faceunity.fuItemSetParam(FuBeautyHandle.cameraHandle, "is_beauty_on", 0.0);
            }
            faceunity.fuOnCameraChange();
        }
        Log.e(TAG, "onSurfaceCreated gen=" + surfaceCreateCount
                + " beautyHandle=" + FuBeautyHandle.cameraHandle);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        eglWidth = width;
        eglHeight = height;
        lastEglW = width;
        lastEglH = height;
        GLES20.glViewport(0, 0, width, height);
        rebuildVertexBuffer();
        Log.e(TAG, "onSurfaceChanged egl=" + width + "x" + height
                + " layout=" + layoutWidth + "x" + layoutHeight
                + " view=" + getWidth() + "x" + getHeight());
        if (width > 32 && height > 32 && !previewStarted.get() && !videoBeautyHosting.get()) {
            queueEvent(this::openCameraOnGlThread);
        }
    }

    /** center-crop；GL 内顺时针 90°，此处按竖屏比例裁切。优先用 EGL 实际尺寸，避免 layout/egl 不一致出现侧边黑条 */
    private void rebuildVertexBuffer() {
        int vw = eglWidth > 0 ? eglWidth : layoutWidth;
        int vh = eglHeight > 0 ? eglHeight : layoutHeight;
        if (vw <= 0 || vh <= 0) {
            vertexBuffer = toFloatBuffer(FULL_QUAD);
            return;
        }

        int[] content = new int[2];
        if (!displayContentSize(content)) {
            vertexBuffer = toFloatBuffer(FULL_QUAD);
            return;
        }
        int fw = content[0];
        int fh = content[1];

        float viewAspect = (float) vw / vh;
        float contentAspect = (float) fw / fh;
        float sx = 1f;
        float sy = 1f;
        if (contentAspect > viewAspect) {
            sx = contentAspect / viewAspect;
        } else {
            sy = viewAspect / contentAspect;
        }

        vertexBuffer = toFloatBuffer(new float[]{
                -sx, -sy,
                 sx, -sy,
                -sx,  sy,
                 sx,  sy,
        });
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (eglWidth < 32 || eglHeight < 32) {
            return;
        }
        // 快照 facing：本帧渲染期间 useFrontCamera 可能被 switchCameraFacing 改，
        // 用快照避免旧帧用新 mirror 渲染出倒帧
        renderFrontCamera = useFrontCamera;
        // 视频美颜宿主：须优先于 coverHidden，否则华为等 GL 队列不跑 → 导入黑屏
        if (videoBeautyHosting.get()) {
            GLES20.glViewport(0, 0, Math.max(1, eglWidth), Math.max(1, eglHeight));
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }
        // 强隐藏：不绘冻帧，避免 Surface 盖住导入页
        if (coverHidden.get()) {
            GLES20.glViewport(0, 0, eglWidth, eglHeight);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }
        // soft-hide / 后台：冻帧重绘上一张美颜纹理，禁止清黑（多任务缩略图）
        if (previewPaused.get()) {
            if (freezeFrame.get() && frozenTexId > 0 && program > 0) {
                GLES20.glViewport(0, 0, eglWidth, eglHeight);
                drawPreviewTexture(frozenTexId, 0f);
            }
            return;
        }
        // 切摄 / 改分辨率 / 单双输入切换：丢前 N 个相机帧（对齐 FULiveDemoDroid mSkippedFrames）
        if (skipFrames > 0) {
            synchronized (frameLock) {
                // 只在有新预览帧时扣减，避免重开期间空 requestRender 把计数耗光
                if (frameReady && nv21Buffer != null) {
                    frameReady = false;
                    skipFrames--;
                }
            }
            drawSwitchingBlackFrame();
            return;
        }
        GLES20.glViewport(0, 0, eglWidth, eglHeight);

        byte[] data;
        int width;
        int height;
        int currentFrameId;

        synchronized (frameLock) {
            if (!frameReady || nv21Buffer == null) {
                holdDrawLastFrame();
                return;
            }
            width = frameWidth;
            height = frameHeight;
            if (nv21RenderBuffer == null || nv21RenderBuffer.length != nv21Buffer.length) {
                nv21RenderBuffer = new byte[nv21Buffer.length];
            }
            System.arraycopy(nv21Buffer, 0, nv21RenderBuffer, 0, nv21Buffer.length);
            data = nv21RenderBuffer;
        }
        lastFrameW = width;
        lastFrameH = height;

        if (faceunity.fuIsLibraryInit() == 0) {
            holdDrawLastFrame();
            return;
        }

        updateOutputResolutionIfNeeded();

        int beautyHandle = FuBeautyHandle.cameraHandle;
        lastBeautyHandle = beautyHandle;
        lastPreviewTexId = previewTextureId;

        int texId;
        int ret;
        long renderStartNs = System.nanoTime();
        // 对齐 FULiveDemoDroid：相机有 OES 时一律走双输入（高级美颜/祛斑/丰盈依赖 NV21+OES）
        boolean hasOes = previewTextureId > 0 && previewSurfaceTexture != null;
        boolean useDualRender = beautyHandle > 0 && hasOes;
        synchronized (faceunity.class) {
            flushPendingBeautyParams();
            if (useDualRender) {
                FuSpecialBeautySync.reconfirmBeforeRender(beautyHandle);
                BeautyParamApplier.updateBeautyBlurEffectAfterRender(beautyHandle);
                syncCameraBufferMatrix(beautyHandle);
                // 切摄 hold：每帧强制 change_frames=0，对抗 enableAdvanced 写回 12
                if (BeautyParamApplier.isChangeFramesHoldZero()) {
                    try {
                        faceunity.fuItemSetParam(beautyHandle, "change_frames", 0.0);
                    } catch (Throwable ignored) {
                    }
                }
                // 对齐 iOS 单一路径：fuRenderDualInput（OES 渲染 + NV21 检脸，FULL 高级美颜）
                int[] items = new int[]{beautyHandle};
                boolean oesUpdated = false;
                if (hasOes) {
                    try {
                        previewSurfaceTexture.updateTexImage();
                        oesUpdated = true;
                        oesFrameAvailable.set(false);
                    } catch (Throwable t) {
                        Log.w(TAG, "updateTexImage", t);
                    }
                }
                if (hasOes && oesUpdated) {
                    synchronized (frameLock) {
                        currentFrameId = ++frameId;
                        frameCount = frameId;
                    }
                    // SDK 祛斑/丰盈在无脸时会走 PreBlur 分支；且须用相机帧 viewport，勿用整屏 egl 尺寸
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    GLES20.glViewport(0, 0, width, height);
                    ret = faceunity.fuRenderDualInput(
                            width,
                            height,
                            currentFrameId,
                            items,
                            previewTextureId,
                            FLAG_EXTERNAL_OES,
                            data,
                            FORMAT_NV21_BUFFER,
                            0,
                            0,
                            null
                    );
                    if (ret <= 0) {
                        lastRenderRet = -5;
                        holdDrawLastFrame();
                        return;
                    }
                    texId = ret;
                } else {
                    lastRenderRet = hasOes ? -3 : -1;
                    holdDrawLastFrame();
                    return;
                }
            } else {
                // 单输入：fuRenderToNV21Image 带 items 才有美颜；返回值即输出纹理 id
                MediaFuSetup.ensureBeautyOn(beautyHandle);
                FuSpecialBeautySync.reconfirmBeforeRender(beautyHandle);
                BeautyParamApplier.updateBeautyBlurEffectAfterRender(beautyHandle);
                syncCameraBufferMatrix(beautyHandle);
                synchronized (frameLock) {
                    currentFrameId = ++frameId;
                    frameCount = frameId;
                }
                int[] items = new int[]{beautyHandle};
                ret = faceunity.fuRenderToNV21Image(
                        data,
                        width,
                        height,
                        currentFrameId,
                        items,
                        0
                );
                texId = ret;
            }
        }
        lastRenderTimeMs = (int) Math.max(0L, (System.nanoTime() - renderStartNs) / 1_000_000L);
        lastTracking = faceunity.fuIsTracking();
        NamaModule.onFaceTrackingUpdated(lastTracking > 0);
        lastFuSysErr = faceunity.fuGetSystemError();
        lastFuTexId = texId;

        if (texId <= 0) {
            lastRenderRet = ret;
            holdDrawLastFrame();
            return;
        }
        lastRenderRet = ret;
        renderOkCount++;
        frozenTexId = texId;
        // 恢复预览 viewport（fuRender 内可能改写 FBO/viewport）
        if (eglWidth > 0 && eglHeight > 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, eglWidth, eglHeight);
        }
        // 磨皮默认已在 loadBundle 时按机型写入；此处不再每帧/低频读置信度（易 native 闪退）

        // 先绘预览再读回编码，避免老机 glReadPixels 卡住导致预览卡顿
        drawPreviewTexture(texId, 0f);
        lastGlError = GLES20.glGetError();

        if (videoRecorder.isRecording()) {
            long nowNs = System.nanoTime();
            long minInterval = DeviceQuirk.isVivoX9OrNex()
                    ? 100_000_000L : LEGACY_RECORD_MIN_INTERVAL_NS;
            boolean offerRecord = !DeviceQuirk.needsLegacyVideoEncode()
                    || lastRecordOfferNs <= 0L
                    || nowNs - lastRecordOfferNs >= minInterval;
            if (offerRecord) {
                lastRecordOfferNs = nowNs;
                long ptsUs = recordStartNs > 0
                        ? Math.max(0L, (nowNs - recordStartNs) / 1000L)
                        : 0L;
                appendRecordFrameFromTexture(texId, width, height, ptsUs);
            }
        }

        // 切换 hold 解除：首帧渲染成功后恢复 change_frames 正常计数
        if (BeautyParamApplier.isChangeFramesHoldZero()) {
            BeautyParamApplier.setChangeFramesHoldZero(false);
        }

        if (capturePending.getAndSet(false)) {
            // 用与预览相同的 shader 变换把 FU 纹理落到竖屏 FBO，再按相机帧分辨率导出
            saveCapturedFrameFromTexture(getContext(), texId, width, height);
        }
    }

    private void drawPreviewTexture(int texId, float blur) {
        if (texId <= 0 || program <= 0) {
            return;
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(uTexture, 0);
        GLES20.glUniform1f(uMirror, mirrorFlipX());
        GLES20.glUniform1f(uMirrorY, mirrorFlipY());
        GLES20.glUniform1f(uRotate, 1f);
        GLES20.glUniform1f(uBlur, blur);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
    }

    /**
     * 录像：FBO 读回 RGBA→NV21 再编码。
     * 新机：与拍照同竖屏旋转；老机：横帧 + muxer orientationHint（避免硬编竖屏失败）。
     */
    private void appendRecordFrameFromTexture(int texId, int srcW, int srcH, long ptsUs) {
        if (texId <= 0 || srcW < 16 || srcH < 16) {
            return;
        }
        final boolean landscape = recordEncodeLandscape;
        int outW = landscape ? (srcW & ~1) : (srcH & ~1);
        int outH = landscape ? (srcH & ~1) : (srcW & ~1);
        int needNv21 = outW * outH * 3 / 2;
        int needRgba = outW * outH * 4;
        if (recordNv21Scratch == null || recordNv21Scratch.length < needNv21) {
            recordNv21Scratch = new byte[needNv21];
        }
        if (recordRgbaScratch == null || recordRgbaScratch.length < needRgba) {
            recordRgbaScratch = new byte[needRgba];
        }
        try {
            ensureCaptureFbo(outW, outH);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureFbo);
            GLES20.glViewport(0, 0, outW, outH);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            FloatBuffer fullQuad = toFloatBuffer(FULL_QUAD);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            GLES20.glUniform1i(uTexture, 0);
            GLES20.glUniform1f(uMirror, mirrorFlipX());
            GLES20.glUniform1f(uMirrorY, mirrorFlipY());
            GLES20.glUniform1f(uRotate, landscape ? 0f : 1f);
            GLES20.glUniform1f(uBlur, 0f);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, fullQuad);
            GLES20.glEnableVertexAttribArray(aTexCoord);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aTexCoord);

            if (recordRgbaDirect == null || recordRgbaDirect.capacity() < needRgba) {
                recordRgbaDirect = ByteBuffer.allocateDirect(needRgba);
                recordRgbaDirect.order(ByteOrder.nativeOrder());
            }
            recordRgbaDirect.clear();
            GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, recordRgbaDirect);
            recordRgbaDirect.rewind();
            recordRgbaDirect.get(recordRgbaScratch, 0, needRgba);
            Nv21Utils.rgbaToNv21(recordRgbaScratch, recordNv21Scratch, outW, outH, true);
            videoRecorder.offerNv21(recordNv21Scratch, outW, outH, ptsUs);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (eglWidth > 0 && eglHeight > 0) {
                GLES20.glViewport(0, 0, eglWidth, eglHeight);
            }
        } catch (Throwable t) {
            Log.w(TAG, "appendRecordFrameFromTexture", t);
            try {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 录像：与拍照同一 FBO + 预览 shader（旋转/镜像），读回 RGBA→NV21 再编码。
     */

    /**
     * 拍照：离屏 FBO 套用预览旋转/镜像（等同「应用 matrix」显示结果），
     * 输出尺寸为竖屏的相机帧分辨率（非屏幕像素）。
     */
    private void saveCapturedFrameFromTexture(Context context, int texId, int srcW, int srcH) {
        final CaptureCallback cb = captureCallback;
        captureCallback = null;
        if (cb == null || texId <= 0 || srcW < 16 || srcH < 16) {
            if (cb != null) {
                postCaptureError(cb, "capture size invalid");
            }
            return;
        }
        int outW = srcH & ~1;
        int outH = srcW & ~1;
        try {
            ensureCaptureFbo(outW, outH);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureFbo);
            GLES20.glViewport(0, 0, outW, outH);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            FloatBuffer fullQuad = toFloatBuffer(FULL_QUAD);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            GLES20.glUniform1i(uTexture, 0);
            GLES20.glUniform1f(uMirror, mirrorFlipX());
            GLES20.glUniform1f(uMirrorY, mirrorFlipY());
            GLES20.glUniform1f(uRotate, 1f);
            GLES20.glUniform1f(uBlur, 0f);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, fullQuad);
            GLES20.glEnableVertexAttribArray(aTexCoord);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aTexCoord);
            GLES20.glFinish();

            ByteBuffer buffer = ByteBuffer.allocateDirect(outW * outH * 4);
            buffer.order(ByteOrder.nativeOrder());
            GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
            byte[] rgba = new byte[outW * outH * 4];
            buffer.rewind();
            buffer.get(rgba);

            Bitmap bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[outW * outH];
            for (int y = 0; y < outH; y++) {
                for (int x = 0; x < outW; x++) {
                    int src = (y * outW + x) * 4;
                    int r = rgba[src] & 0xff;
                    int g = rgba[src + 1] & 0xff;
                    int b = rgba[src + 2] & 0xff;
                    // GL 原点在左下，翻转 Y
                    pixels[(outH - 1 - y) * outW + x] = 0xff000000 | (r << 16) | (g << 8) | b;
                }
            }
            bitmap.setPixels(pixels, 0, outW, 0, 0, outW, outH);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (eglWidth > 0 && eglHeight > 0) {
                GLES20.glViewport(0, 0, eglWidth, eglHeight);
            }

            String path = writeBitmapToGallery(context, bitmap);
            bitmap.recycle();
            if (path == null || path.isEmpty()) {
                postCaptureError(cb, "保存相册失败");
            } else {
                new Handler(Looper.getMainLooper()).post(() -> cb.onSuccess(path));
            }
        } catch (Exception e) {
            try {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            } catch (Exception ignored) {
            }
            postCaptureError(cb, e.getMessage() != null ? e.getMessage() : "capture failed");
        }
    }

    private void ensureCaptureFbo(int w, int h) {
        if (captureFbo != 0 && captureFboW == w && captureFboH == h) {
            return;
        }
        releaseCaptureFbo();
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        captureColorTex = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, captureColorTex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        );
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        captureFbo = fbo[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureFbo);
        GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, captureColorTex, 0
        );
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            releaseCaptureFbo();
            throw new IllegalStateException("capture FBO incomplete status=" + status);
        }
        captureFboW = w;
        captureFboH = h;
    }

    private void releaseCaptureFbo() {
        if (captureFbo != 0) {
            int[] fbo = new int[]{captureFbo};
            GLES20.glDeleteFramebuffers(1, fbo, 0);
            captureFbo = 0;
        }
        if (captureColorTex != 0) {
            int[] tex = new int[]{captureColorTex};
            GLES20.glDeleteTextures(1, tex, 0);
            captureColorTex = 0;
        }
        captureFboW = 0;
        captureFboH = 0;
    }

    private static void postCaptureError(CaptureCallback cb, String message) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onError(message));
    }

    private static String writeBitmapToGallery(Context context, Bitmap bitmap) {
        try {
            String name = "FU_" + System.currentTimeMillis() + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FULive");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = context.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
            );
            if (uri == null) {
                return null;
            }
            OutputStream out = context.getContentResolver().openOutputStream(uri);
            if (out == null) {
                return null;
            }
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
            out.flush();
            out.close();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            }
            return uri.toString();
        } catch (Exception e) {
            Log.e(TAG, "writeBitmapToGallery", e);
            return null;
        }
    }

    private void tickFps() {
        fpsTickCount++;
        long now = System.currentTimeMillis();
        if (fpsTickStart == 0) {
            fpsTickStart = now;
        }
        if (now - fpsTickStart >= 1000L) {
            lastFps = fpsTickCount;
            fpsTickCount = 0;
            fpsTickStart = now;
        }
    }

    private void openCameraOnGlThread() {
        if (previewPaused.get() || previewStarted.get() || videoBeautyHosting.get()) {
            return;
        }
        try {
            if (previewTextureId <= 0) {
                int[] tex = new int[1];
                GLES20.glGenTextures(1, tex, 0);
                previewTextureId = tex[0];
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, previewTextureId);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            }
            if (previewSurfaceTexture != null) {
                previewSurfaceTexture.release();
            }
            final SurfaceTexture surfaceTexture = new SurfaceTexture(previewTextureId);
            surfaceTexture.setOnFrameAvailableListener(st -> {
                oesFrameAvailable.set(true);
                requestRender();
            });
            previewSurfaceTexture = surfaceTexture;
            ensureCameraThread();
            cameraHandler.post(() -> openCameraWithSurface(surfaceTexture));
        } catch (Exception e) {
            setLastError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            releaseCameraOnGlThread();
        }
    }

    private void ensureCameraThread() {
        if (cameraThread == null) {
            cameraThread = new HandlerThread("BeautyCamera");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
    }

    private void openCameraWithSurface(SurfaceTexture surfaceTexture) {
        if (previewPaused.get() || previewStarted.get()) {
            return;
        }
        invalidateExposureApplyCache();
        try {
            int cameraId = findCameraId(useFrontCamera);
            synchronized (CAMERA_LOCK) {
                camera = Camera.open(cameraId);
            }
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, info);
            useFrontCamera = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;

            Camera.Parameters parameters = camera.getParameters();
            parameters.setPreviewFormat(ImageFormat.NV21);
            Camera.Size size = choosePreviewSize(
                    parameters.getSupportedPreviewSizes(),
                    targetPreviewW,
                    targetPreviewH
            );
            parameters.setPreviewSize(size.width, size.height);
            List<String> focusModes = parameters.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            try {
                List<String> bands = parameters.getSupportedAntibanding();
                if (bands != null) {
                    if (bands.contains(Camera.Parameters.ANTIBANDING_50HZ)) {
                        parameters.setAntibanding(Camera.Parameters.ANTIBANDING_50HZ);
                    } else if (bands.contains(Camera.Parameters.ANTIBANDING_AUTO)) {
                        parameters.setAntibanding(Camera.Parameters.ANTIBANDING_AUTO);
                    }
                }
            } catch (Throwable ignored) {
            }
            seedExposureOnParameters(parameters, lastExposureUi);
            camera.setParameters(parameters);

            frameWidth = size.width;
            frameHeight = size.height;
            int bufferSize = frameWidth * frameHeight * 3 / 2;
            nv21Buffer = new byte[bufferSize];
            camera.addCallbackBuffer(new byte[bufferSize]);

            camera.setDisplayOrientation(getDisplayRotation());

            camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera cam) {
                    synchronized (frameLock) {
                        if (nv21Buffer == null || data == null || data.length != nv21Buffer.length) {
                            if (cam != null) {
                                cam.addCallbackBuffer(data);
                            }
                            return;
                        }
                        System.arraycopy(data, 0, nv21Buffer, 0, data.length);
                        frameReady = true;
                    }
                    tickFps();
                    requestRender();
                    if (cam != null) {
                        cam.addCallbackBuffer(data);
                    }
                }
            });

            camera.setPreviewTexture(surfaceTexture);
            camera.startPreview();
            previewStarted.set(true);
            lastPreviewStarted = true;
            setLastError("");
            // 重开后重新丢帧：切换期间 onDraw 常把 skipFrames 耗光，偶发首帧倒/缩靠这里再盖住
            skipFrames = SWITCH_SKIP_FRAMES;

            final int openedCameraId = cameraId;
            // FU 矩阵在 GL 线程写入后 requestRender
            queueEvent(() -> {
                try {
                    if (faceunity.fuIsLibraryInit() != 0) {
                        applyInputCameraMatrix(openedCameraId);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "applyInputCameraMatrix on open", t);
                }
                cachedFuOutW = 0;
                cachedFuOutH = 0;
                updateOutputResolutionIfNeeded();
                rebuildVertexBuffer();
                requestRender();
            });
            Log.e(TAG, "cameraStarted size=" + frameWidth + "x" + frameHeight
                    + " front=" + useFrontCamera);
        } catch (Exception e) {
            skipFrames = 0;
            BeautyParamApplier.setChangeFramesHoldZero(false);
            setRenderMode(RENDERMODE_WHEN_DIRTY);
            setLastError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            releaseCameraOnGlThread();
            requestRender();
        }
    }

    private void releaseCameraOnGlThread() {
        releaseCameraAsync(null);
        releaseGlResourcesOnGlThread();
    }

    private void releaseCameraSync() {
        if (camera == null) {
            previewStarted.set(false);
            lastPreviewStarted = false;
            return;
        }
        final Camera cam = camera;
        camera = null;
        previewStarted.set(false);
        lastPreviewStarted = false;
        lastAppliedEvTarget = Integer.MIN_VALUE;
        lastBlurExposureBucket = -1;
        try {
            synchronized (CAMERA_LOCK) {
                cam.setPreviewCallbackWithBuffer(null);
                cam.stopPreview();
                cam.release();
            }
            Log.e(TAG, "cameraReleasedSync");
        } catch (Exception ignored) {
        }
    }

    /** 相机线程异步 release；完成后执行 onDone（可为 null）。 */
    private void releaseCameraAsync(Runnable onDone) {
        previewStarted.set(false);
        lastPreviewStarted = false;
        synchronized (frameLock) {
            frameReady = false;
            nv21Buffer = null;
            nv21RenderBuffer = null;
        }
        cachedFuOutW = 0;
        cachedFuOutH = 0;
        if (cameraHandler != null) {
            cameraHandler.removeCallbacks(applyPendingExposureRunnable);
        }
        if (camera != null) {
            final Camera cam = camera;
            camera = null;
            lastAppliedEvTarget = Integer.MIN_VALUE;
            lastBlurExposureBucket = -1;
            Runnable release = () -> {
                try {
                    synchronized (CAMERA_LOCK) {
                        cam.setPreviewCallbackWithBuffer(null);
                        cam.stopPreview();
                        cam.release();
                    }
                    Log.e(TAG, "cameraReleased");
                } catch (Exception ignored) {
                } finally {
                    if (onDone != null) {
                        onDone.run();
                    }
                }
            };
            ensureCameraThread();
            cameraHandler.post(release);
        } else if (onDone != null) {
            onDone.run();
        }
    }

    private void releaseGlResourcesOnGlThread() {
        releaseGlResourcesOnGlThread(false);
    }

    private void releaseGlResourcesOnGlThread(boolean keepSession) {
        releaseCaptureFbo();
        if (!keepSession && faceunity.fuIsLibraryInit() != 0) {
            faceunity.fuOnDeviceLostSafe();
        }
        if (previewSurfaceTexture != null) {
            try {
                previewSurfaceTexture.release();
            } catch (Exception ignored) {
            }
            previewSurfaceTexture = null;
        }
        if (previewTextureId > 0) {
            int[] tex = new int[]{previewTextureId};
            GLES20.glDeleteTextures(1, tex, 0);
            previewTextureId = 0;
        }
    }

    private void notifyReleaseFinished() {
        final Runnable cb = releaseFinishedCallback;
        releaseFinishedCallback = null;
        if (cb == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(cb);
    }

    private int getDisplayRotation() {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return 90;
        }
        switch (wm.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_0:
                return 90;
            case Surface.ROTATION_90:
                return 0;
            case Surface.ROTATION_180:
                return 270;
            case Surface.ROTATION_270:
                return 180;
            default:
                return 90;
        }
    }

    private int getScreenRotationDegrees() {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return 0;
        }
        switch (wm.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_0:
                return 0;
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    private static int toFuRotationMode(int degrees) {
        switch (degrees) {
            case 90:
                return faceunity.FU_ROTATION_MODE_90;
            case 180:
                return faceunity.FU_ROTATION_MODE_180;
            case 270:
                return faceunity.FU_ROTATION_MODE_270;
            case 0:
            default:
                return faceunity.FU_ROTATION_MODE_0;
        }
    }

    /**
     * CameraMatrix 检脸旋转 + BufferMatrix(4) 校正双输入 NV21 Y 轴（对齐 iOS fuSetInputCameraBufferMatrix）。
     * 输出分辨率仍与 NV21 一致，勿对调 w/h。
     */
    private void applyInputCameraMatrix(int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        applyInputCameraMatrixForFacing(info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT, info, true);
    }

    /** 切摄前预写矩阵（不调 onCameraChange，由调用方已调） */
    private void applyInputCameraMatrixForFacing(boolean front) {
        int cameraId = findCameraId(front);
        if (cameraId < 0) {
            cameraId = 0;
        }
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        applyInputCameraMatrixForFacing(front, info, false);
    }

    private void applyInputCameraMatrixForFacing(boolean front, Camera.CameraInfo info, boolean notifyChange) {
        int screenRotation = getScreenRotationDegrees();
        int degrees;
        if (front) {
            degrees = (360 - info.orientation + screenRotation) % 360;
        } else {
            degrees = (info.orientation - screenRotation + 360) % 360;
        }
        fuInputDegrees = degrees;
        int rotateMode = toFuRotationMode(degrees);
        // 前置镜像只在检脸矩阵写 flipX=1；渲染层勿再 OR FLIP_X，否则前置反向/切摄首帧闪倒立
        int flipX = front ? 1 : 0;
        fuFlipX = flipX;
        fuRotateMode = rotateMode;
        lastCameraBufferMatrix = -1;
        // 固定 BufferMatrix=4（CCROT0_FLIPVERTICAL）；勿再随丰盈/祛斑开关切换，否则关丰盈后美型左右跑偏
        int bufMat = MediaFuSetup.CCROT0_FLIPVERTICAL;
        MediaFuSetup.applyCameraDualInputMatrices(flipX, rotateMode, bufMat);
        lastCameraBufferMatrix = bufMat;
        if (notifyChange) {
            faceunity.fuOnCameraChange();
        }
        cachedFuOutW = 0;
        cachedFuOutH = 0;
        if (notifyChange) {
            updateOutputResolutionIfNeeded();
        }
        Log.e(TAG, "cameraMatrix facingFront=" + front
                + " orientation=" + info.orientation
                + " screen=" + screenRotation
                + " size=" + frameWidth + "x" + frameHeight
                + " flipX=" + flipX
                + " fuDegrees=" + degrees
                + " bufferMat=" + bufMat
                + " glRot=CW90");
    }

    /** 相机预览固定 BufferMatrix=4 */
    private static int bufferMatrixForBeauty(int beautyHandle) {
        return MediaFuSetup.CCROT0_FLIPVERTICAL;
    }

    private void syncCameraBufferMatrix(int beautyHandle) {
        int want = bufferMatrixForBeauty(beautyHandle);
        if (want == lastCameraBufferMatrix) {
            return;
        }
        lastCameraBufferMatrix = want;
        MediaFuSetup.applyCameraBufferMatrix(want);
    }

    /** 从视频/静图页回到相机后恢复双输入矩阵 */
    public void reapplyInputCameraMatrix() {
        queueEvent(() -> {
            if (faceunity.fuIsLibraryInit() == 0 || !previewStarted.get()) {
                return;
            }
            try {
                lastCameraBufferMatrix = -1;
                applyInputCameraMatrixForFacing(useFrontCamera);
                syncCameraBufferMatrix(FuBeautyHandle.cameraHandle);
                faceunity.fuOnCameraChange();
                updateOutputResolutionIfNeeded();
            } catch (Throwable t) {
                Log.w(TAG, "reapplyInputCameraMatrix", t);
            }
        });
    }

    private static int findCameraId(boolean front) {
        int count = Camera.getNumberOfCameras();
        int fallback = 0;
        for (int i = 0; i < count; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (front && info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                return i;
            }
            if (!front && info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                return i;
            }
            fallback = i;
        }
        return fallback;
    }

    private static int findFrontCameraId() {
        return findCameraId(true);
    }

    /** 优先横帧，检脸/美型在横帧坐标系；显示由 GL 转正 */
    private static Camera.Size choosePreviewSize(List<Camera.Size> sizes, int targetW, int targetH) {
        int wantW = Math.max(targetW, targetH);
        int wantH = Math.min(targetW, targetH);
        Camera.Size bestLandscape = null;
        int bestLandscapeDiff = Integer.MAX_VALUE;
        Camera.Size bestAny = sizes.get(0);
        int bestAnyDiff = Integer.MAX_VALUE;
        for (Camera.Size size : sizes) {
            int diff = Math.abs(size.width - wantW) + Math.abs(size.height - wantH);
            if (diff < bestAnyDiff) {
                bestAnyDiff = diff;
                bestAny = size;
            }
            if (size.width >= size.height && diff < bestLandscapeDiff) {
                bestLandscapeDiff = diff;
                bestLandscape = size;
            }
        }
        return bestLandscape != null ? bestLandscape : bestAny;
    }

    private static FloatBuffer toFloatBuffer(float[] data) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(data).position(0);
        return buffer;
    }

    private static int buildProgram(String vertex, String fragment) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }

    /** 视频 GPU 预览：在相机 GL 上创建硬解 Surface */
    public android.view.Surface ensureVideoGpuDecoderSurface(
            int srcW, int srcH, int outW, int outH, android.os.Handler stHandler) {
        final android.view.Surface[] out = new android.view.Surface[1];
        final Object lock = new Object();
        final boolean[] done = {false};
        try {
            queueEvent(() -> {
                try {
                    if (videoGpuPreview == null) {
                        videoGpuPreview = new VideoGpuPreviewHelper();
                    }
                    videoGpuPreview.initOnGl(srcW, srcH, outW, outH, stHandler);
                    out[0] = videoGpuPreview.getDecoderSurface();
                } catch (Throwable t) {
                    Log.w(TAG, "ensureVideoGpuDecoderSurface", t);
                    out[0] = null;
                } finally {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            });
            requestRender();
            long deadline = System.currentTimeMillis() + 3000L;
            synchronized (lock) {
                while (!done[0] && System.currentTimeMillis() < deadline) {
                    lock.wait(16);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureVideoGpuDecoderSurface wait", t);
        }
        return out[0];
    }

    public void releaseVideoGpuPreview() {
        queueEvent(() -> {
            if (videoGpuPreview != null) {
                videoGpuPreview.releaseOnGl();
                videoGpuPreview = null;
            }
        });
    }

    public int getVideoGpuOutW() {
        return videoGpuPreview != null ? videoGpuPreview.getOutW() : 0;
    }

    public int getVideoGpuOutH() {
        return videoGpuPreview != null ? videoGpuPreview.getOutH() : 0;
    }

    public static final class VideoGpuRgbaFrame {
        public byte[] raw;
        public byte[] beauty;
    }

    public VideoGpuRgbaFrame processVideoGpuFrameRgba(int beautyHandle, boolean beautyOn, int minPasses) {
        if (videoGpuPreview == null) {
            return null;
        }
        final VideoGpuRgbaFrame out = new VideoGpuRgbaFrame();
        final Object lock = new Object();
        final boolean[] done = {false};
        try {
            queueEvent(() -> {
                try {
                    long frameWait = DeviceQuirk.isHuaweiFamily() ? 200L : 120L;
                    if (!videoGpuPreview.waitFrameAvailable(frameWait)) {
                        return;
                    }
                    videoGpuPreview.renderBeautyFrame(
                            beautyHandle, beautyOn, minPasses, BeautyCameraGLView::flushPendingBeautyParams);
                    out.raw = videoGpuPreview.readTexRgba(videoGpuPreview.getRawTexId());
                    out.beauty = videoGpuPreview.readDisplayRgba(beautyOn);
                } catch (Throwable t) {
                    Log.w(TAG, "processVideoGpuFrameRgba", t);
                } finally {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            });
            requestRender();
            long deadline = System.currentTimeMillis()
                    + (DeviceQuirk.isHuaweiFamily() ? 400L : 250L);
            synchronized (lock) {
                while (!done[0] && System.currentTimeMillis() < deadline) {
                    lock.wait(8);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "processVideoGpuFrameRgba wait", t);
        }
        return (out.raw != null || out.beauty != null) ? out : null;
    }

    public VideoGpuRgbaFrame redrawVideoGpuFrameRgba(int beautyHandle, boolean beautyOn, int minPasses) {
        if (videoGpuPreview == null) {
            return null;
        }
        final VideoGpuRgbaFrame out = new VideoGpuRgbaFrame();
        final Object lock = new Object();
        final boolean[] done = {false};
        try {
            queueEvent(() -> {
                try {
                    videoGpuPreview.redrawFromCachedFbo(
                            beautyHandle, beautyOn, minPasses, BeautyCameraGLView::flushPendingBeautyParams);
                    out.raw = videoGpuPreview.readTexRgba(videoGpuPreview.getRawTexId());
                    out.beauty = videoGpuPreview.readDisplayRgba(beautyOn);
                } catch (Throwable t) {
                    Log.w(TAG, "redrawVideoGpuFrameRgba", t);
                } finally {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            });
            requestRender();
            long deadline = System.currentTimeMillis() + 400L;
            synchronized (lock) {
                while (!done[0] && System.currentTimeMillis() < deadline) {
                    lock.wait(8);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "redrawVideoGpuFrameRgba wait", t);
        }
        return (out.raw != null || out.beauty != null) ? out : null;
    }

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform float uMirror;\n" +
            "uniform float uMirrorY;\n" +
            "uniform float uRotate;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
            "  vec2 uv = aTexCoord;\n" +
            "  // 预览/拍照：逆时针 90°；老机录像横帧编码时 uRotate=0 保持传感器朝向\n" +
            "  vec2 rotated = uRotate > 0.5 ? vec2(uv.y, 1.0 - uv.x) : uv;\n" +
            "  float mx = uMirror > 0.5 ? (1.0 - rotated.x) : rotated.x;\n" +
            "  float my = uMirrorY > 0.5 ? (1.0 - rotated.y) : rotated.y;\n" +
            "  vTexCoord = vec2(mx, my);\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform float uBlur;\n" +
            "void main() {\n" +
            "  if (uBlur > 0.5) {\n" +
            "    float o = 0.004;\n" +
            "    vec4 c = texture2D(uTexture, vTexCoord);\n" +
            "    c += texture2D(uTexture, vTexCoord + vec2(o, 0.0));\n" +
            "    c += texture2D(uTexture, vTexCoord + vec2(-o, 0.0));\n" +
            "    c += texture2D(uTexture, vTexCoord + vec2(0.0, o));\n" +
            "    c += texture2D(uTexture, vTexCoord + vec2(0.0, -o));\n" +
            "    c += texture2D(uTexture, vTexCoord + vec2(o, o));\n" +
            "    c += texture2D(uTexture, vTexCoord + vec2(-o, -o));\n" +
            "    gl_FragColor = vec4((c / 7.0).rgb * 0.82, 1.0);\n" +
            "  } else {\n" +
            "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "  }\n" +
            "}\n";
}
