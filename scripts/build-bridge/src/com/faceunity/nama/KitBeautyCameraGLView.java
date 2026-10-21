package com.faceunity.nama;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup;

import com.alibaba.fastjson.JSONObject;
import com.faceunity.core.entity.FUCameraConfig;
import com.faceunity.core.entity.FURenderFrameData;
import com.faceunity.core.entity.FURenderInputData;
import com.faceunity.core.entity.FURenderOutputData;
import com.faceunity.core.listener.OnGlRendererListener;
import com.faceunity.core.media.photo.OnPhotoRecordingListener;
import com.faceunity.core.media.photo.PhotoRecordHelper;
import com.faceunity.core.renderer.CameraRenderer;
import com.faceunity.core.utils.GlUtil;

import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于官方 {@link CameraRenderer} 的 Uni 相机预览（Android fu_kit 分支）。
 */
public class KitBeautyCameraGLView extends android.opengl.GLSurfaceView implements ICameraOverlay {

    private static final String TAG = "FaceUnity-KitCamera";

    private static volatile String lastError = "";
    private static volatile boolean beautyEnabledGlobal = true;
    /** 预览最高帧率；>0 走官方 FUCamera 软件限帧，<=0 不限制（对齐 demo 默认 -1）。默认 30，对齐 iOS internalCameraSetting.fps。 */
    private static volatile int sMaxPreviewFps = 30;

    /** 曝光补偿缓存（0~100）：Home 返回重建 overlay 时跨实例继承，相机重开后重放。 */
    private static volatile int sExposureCache = 50;

    private CameraRenderer cameraRenderer;
    private volatile boolean previewStarted;
    private volatile boolean rendererPaused;
    private final AtomicBoolean capturePending = new AtomicBoolean(false);
    private volatile CameraCaptureCallback captureCallback;
    private volatile CameraCaptureCallback recordCallback;
    private final CameraRecordSession recordSession = new CameraRecordSession();

    public KitBeautyCameraGLView(Context context) {
        super(context);
        initKitRenderer(context);
    }

    public KitBeautyCameraGLView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initKitRenderer(context);
    }

    private void initKitRenderer(Context context) {
        if (!FuKitBridge.isActive()) {
            return;
        }
        setZOrderOnTop(true);
        getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        FUCameraConfig config = new FUCameraConfig();
        int cameraFps = resolveCameraFpsForConfig(sMaxPreviewFps);
        if (cameraFps > 0) {
            config.setCameraFPS(cameraFps);
        }
        cameraRenderer = new CameraRenderer(this, config, new OnGlRendererListener() {
            @Override
            public void onSurfaceCreated() {
                FuKitBridge.onCameraGlSurfaceCreated();
                if (cameraRenderer != null) {
                    // Demo 的对比实现是 renderSwitch，不是解绑 FaceBeautyKit。
                    cameraRenderer.setFURenderSwitch(beautyEnabledGlobal);
                }
                previewStarted = true;
                rendererPaused = false;
                resetBenchmark();
                // 相机重开后曝光 builder 重建：主线程延迟重放缓存值（等 startPreview 完成），
                // onDrawFrameAfter 的 restoreExposureIfPending 仅作兜底。
                final CameraRenderer rendererAtCreate = cameraRenderer;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (rendererAtCreate == null || rendererAtCreate.getFUCamera() == null) {
                        return;
                    }
                    if (sExposureCache != 50) {
                        rendererAtCreate.getFUCamera().setExposureCompensation(sExposureCache / 100f);
                        Log.d(TAG, "restoreExposure[delayed]: applied " + sExposureCache
                                + " view=" + KitBeautyCameraGLView.this);
                    }
                }, 800);
                Log.d(TAG, "onSurfaceCreated: view=" + KitBeautyCameraGLView.this
                        + " cache=" + sExposureCache);
            }

            @Override
            public void onSurfaceChanged(int width, int height) {
                // no-op
            }

            @Override
            public void onRenderBefore(FURenderInputData inputData) {
                // 对比开关已由 CameraRenderer.setFURenderSwitch 处理。
                if (inputData != null) {
                    benchmarkFrameWidth = inputData.getWidth();
                    benchmarkFrameHeight = inputData.getHeight();
                }
                markBenchmarkFrameStart();
            }

            @Override
            public void onRenderAfter(FURenderOutputData outputData, FURenderFrameData frameData) {
                recordSession.offerFrame(outputData, frameData);
                if (capturePending.get()
                        && outputData != null
                        && outputData.getTexture() != null
                        && frameData != null) {
                    capturePending.set(false);
                    captureCurrentFrame(outputData, frameData);
                }
            }

            @Override
            public void onDrawFrameAfter() {
                accumulateBenchmarkFrame();
            }
            @Override
            public void onSurfaceDestroy() {
                previewStarted = false;
                rendererPaused = true;
                resetBenchmark();
                recordSession.cancel();
                setRenderMode(RENDERMODE_WHEN_DIRTY);
                CameraCaptureCallback cb = captureCallback;
                captureCallback = null;
                capturePending.set(false);
                if (cb != null) {
                    postCaptureError(cb, "相机预览已停止");
                }
                FuKitBridge.onCameraGlSurfaceDestroyed();
            }
        });
    }

    public static String getLastError() {
        return lastError != null ? lastError : "";
    }

    public static String getPreviewDiag() {
        return "kitCamera=1";
    }

    //region Benchmark（对齐官方 Demo BaseFaceUnityActivity.benchmarkFPS）
    private static final int BENCHMARK_FRAME_COUNT = 10;
    private int benchmarkFrameCnt = 0;
    private long benchmarkLastStamp = 0;
    private long benchmarkRenderNanos = 0;
    private boolean benchmarkRenderThisFrame = false;
    private long benchmarkFrameStartNanos = 0;
    private int benchmarkFrameWidth = 0;
    private int benchmarkFrameHeight = 0;
    private static volatile int previewStatsFps = 0;
    private static volatile double previewStatsRenderTimeMs = 0;
    private static volatile int previewStatsWidth = 0;
    private static volatile int previewStatsHeight = 0;

    /** 记录每帧渲染前时间戳，需在 GL 线程 onRenderBefore 中调用。 */
    private void markBenchmarkFrameStart() {
        benchmarkFrameStartNanos = System.nanoTime();
        benchmarkRenderThisFrame = true;
    }

    /** 对齐 Demo：每攒满 BENCHMARK_FRAME_COUNT 帧统计一次 fps 与平均渲染耗时。 */
    private void accumulateBenchmarkFrame() {
        long now = System.nanoTime();
        if (benchmarkRenderThisFrame) {
            benchmarkRenderNanos += now - benchmarkFrameStartNanos;
            benchmarkRenderThisFrame = false;
        }
        if (++benchmarkFrameCnt < BENCHMARK_FRAME_COUNT) {
            return;
        }
        if (benchmarkLastStamp > 0) {
            long elapsed = now - benchmarkLastStamp;
            if (elapsed > 0) {
                // 对齐官方 BaseFaceUnityActivity：double 测算后 (int) 截断，不用 Math.round（30.x 不会进位成 31）
                int fps = (int) (BENCHMARK_FRAME_COUNT * 1000000000.0 / elapsed);
                if (sMaxPreviewFps > 0) {
                    fps = Math.min(fps, sMaxPreviewFps);
                }
                previewStatsFps = Math.max(0, fps);
            }
        }
        previewStatsRenderTimeMs = (double) benchmarkRenderNanos / BENCHMARK_FRAME_COUNT / 1000000.0;
        previewStatsWidth = benchmarkFrameWidth;
        previewStatsHeight = benchmarkFrameHeight;
        benchmarkFrameCnt = 0;
        benchmarkLastStamp = now;
        benchmarkRenderNanos = 0;
    }

    private void resetBenchmark() {
        benchmarkFrameCnt = 0;
        benchmarkLastStamp = 0;
        benchmarkRenderNanos = 0;
        benchmarkRenderThisFrame = false;
        benchmarkFrameStartNanos = 0;
        benchmarkFrameWidth = 0;
        benchmarkFrameHeight = 0;
        previewStatsFps = 0;
        previewStatsRenderTimeMs = 0;
        previewStatsWidth = 0;
        previewStatsHeight = 0;
    }

    public static JSONObject getPreviewStatsJson() {
        JSONObject o = new JSONObject();
        o.put("fps", previewStatsFps);
        o.put("renderTime", previewStatsRenderTimeMs);
        o.put("frameWidth", previewStatsWidth);
        o.put("frameHeight", previewStatsHeight);
        o.put("resolution", 0);
        o.put("tracking", -1);
        o.put("label", "kit");
        return o;
    }
    //endregion

    public static void setBeautyEnabledGlobal(boolean enabled) {
        beautyEnabledGlobal = enabled;
        FuKitBridge.setBeautyEnabled(enabled);
    }

    public void setBeautyEnabled(boolean enabled) {
        beautyEnabledGlobal = enabled;
        FuKitBridge.setBeautyEnabled(enabled);
        if (cameraRenderer != null) {
            // 与 Demo BaseFURenderer.setFURenderSwitch 一致：只跳过本帧美颜渲染。
            cameraRenderer.setFURenderSwitch(enabled);
        }
        requestRender();
    }

    public void bindLayoutSize(int width, int height) {
        int layoutW = Math.max(1, width);
        int layoutH = Math.max(1, height);
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null) {
            lp.width = layoutW;
            lp.height = layoutH;
            setLayoutParams(lp);
        }
        requestLayout();
    }

    public boolean isPreviewStarted() {
        return previewStarted;
    }

    public void resumePreview() {
        CameraRenderer renderer = cameraRenderer;
        if (renderer == null) {
            return;
        }
        if (rendererPaused) {
            renderer.onResume();
            rendererPaused = false;
        }
        try {
            // CameraRenderer.onPause 会在 Demo 对齐的 destroyGlSurface 中释放 RenderKit。
            // 仅调用 onResume 不一定重新触发 onSurfaceCreated，因此必须在 GL 线程重绑。
            queueEvent(() -> {
                FuKitBridge.onCameraGlSurfaceCreated();
                CameraRenderer current = cameraRenderer;
                if (current != null) {
                    current.setFURenderSwitch(beautyEnabledGlobal);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "resumePreview rebind kit", t);
        }
        setVisibility(VISIBLE);
        requestRender();
    }

    public void hidePreview() {
        setVisibility(INVISIBLE);
        if (cameraRenderer != null && !rendererPaused) {
            cameraRenderer.onPause();
            rendererPaused = true;
        }
    }

    public void switchCameraFacing() {
        if (cameraRenderer != null) {
            cameraRenderer.switchCamera();
        }
    }

    public void setDualInputEnabled(boolean dual) {
        // RenderKit 相机路径由 CameraRenderer 管理双输入，暂固定双输入
        Log.d(TAG, "setDualInputEnabled ignored in kit mode dual=" + dual);
    }

    /** 大幅切分辨率时丢弃的过渡帧数（对齐 dev BeautyCameraGLView.SWITCH_SKIP_FRAMES）。 */
    private static final int RESOLUTION_SWITCH_IGNORE_FRAMES = 6;

    /**
     * 切换 Camera1 采集尺寸。Demo 在 UI 线程直调 {@code FUCamera.changeResolution}；
     * 重开相机期间用 {@link CameraRenderer#setOpenCameraIgnoreFrame} 避免旧 MVP 矩阵拉伸画面。
     */
    @Override
    public void setPreviewResolution(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        CameraRenderer renderer = cameraRenderer;
        if (renderer == null || renderer.getFUCamera() == null) {
            return;
        }
        // 对齐 Demo CameraRenderer.switchCamera：重开相机后丢弃尺寸未稳定的帧，
        // 避免 1080→480 等大幅切换时旧 MVP 矩阵把画面横向拉宽。
        renderer.setOpenCameraIgnoreFrame(RESOLUTION_SWITCH_IGNORE_FRAMES);
        renderer.getFUCamera().changeResolution(width, height);
        requestRender();
    }

    public void destroyPreviewAsync(boolean keepSession, Runnable onFinished) {
        CameraRenderer renderer = cameraRenderer;
        try {
            // 对齐 Demo：先 onPause，让 GLSurfaceView 在 GL 线程释放 FURenderKit，
            // 再 onDestroy 清掉 CameraRenderer 的引用。
            if (renderer != null) {
                if (!rendererPaused) {
                    renderer.onPause();
                    rendererPaused = true;
                }
                renderer.onDestroy();
            }
        } catch (Throwable t) {
            Log.w(TAG, "destroyPreviewAsync", t);
        }
        cameraRenderer = null;
        previewStarted = false;
        rendererPaused = true;
        if (!keepSession) {
            FuKitBridge.releaseSession();
        } else {
            FuKitBridge.onCameraGlSurfaceDestroyed();
        }
        if (onFinished != null) {
            onFinished.run();
        }
    }

    public void freezePreview() {
        hidePreview();
    }

    public void pauseGlForHandoff() {
        hidePreview();
    }

    public void resumeGlAfterHandoff() {
        resumePreview();
    }

    public void tapToFocus(float localX, float localY, float previewW, float previewH) {
        CameraRenderer renderer = cameraRenderer;
        if (renderer == null || renderer.getFUCamera() == null) {
            return;
        }
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0 || previewW <= 0f || previewH <= 0f) {
            return;
        }
        // Uni 页面传 CSS 局部坐标；官方 FUCamera 接收 Surface 像素坐标。
        float rawX = Math.max(0f, Math.min(viewW, localX / previewW * viewW));
        float rawY = Math.max(0f, Math.min(viewH, localY / previewH * viewH));
        float density = getResources().getDisplayMetrics().density;
        int areaSize = Math.max(1, Math.round(150f * density));
        renderer.getFUCamera().handleFocus(viewW, viewH, rawX, rawY, areaSize);
    }

    public int getLastExposureUi() {
        return sExposureCache;
    }

    public void setExposureCompensation(int exposure0to100) {
        int value = Math.max(0, Math.min(100, exposure0to100));
        sExposureCache = value;
        Log.d(TAG, "setExposureCompensation: value=" + value + " cache=" + sExposureCache);
        CameraRenderer renderer = cameraRenderer;
        if (renderer == null || renderer.getFUCamera() == null) {
            return;
        }
        renderer.getFUCamera().setExposureCompensation(value / 100f);
    }

    @Override
    public void capturePhoto(Context context, CameraCaptureCallback callback) {
        if (context == null || callback == null) {
            return;
        }
        if (!previewStarted || getWidth() < 16 || getHeight() < 16) {
            callback.onError("预览未就绪");
            return;
        }
        captureCallback = callback;
        capturePending.set(true);
        requestRender();
    }

    @Override
    public void startVideoRecord() throws Exception {
        // 录制分辨率用相机采集尺寸（对齐官方 demo：startRecording(surface, getCameraHeight(), getCameraWidth())），
        // 否则用 View 布局尺寸会与采集比例不一致，导致录出的视频被拉长。
        int w;
        int h;
        if (cameraRenderer != null && cameraRenderer.getFUCamera() != null
                && cameraRenderer.getFUCamera().getCameraHeight() >= 16
                && cameraRenderer.getFUCamera().getCameraWidth() >= 16) {
            w = cameraRenderer.getFUCamera().getCameraHeight() & ~1;
            h = cameraRenderer.getFUCamera().getCameraWidth() & ~1;
        } else {
            w = getWidth() & ~1;
            h = getHeight() & ~1;
        }
        if (!previewStarted || w < 16 || h < 16) {
            throw new IllegalStateException("预览未就绪");
        }
        // 官方 CameraRenderer 默认按需渲染；长按录制期间必须连续出帧。
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        try {
            recordSession.start(this, w, h, new CameraRecordSession.Callback() {
                @Override
                public void onSuccess(String path) {
                    setRenderMode(RENDERMODE_WHEN_DIRTY);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (recordCallback != null) {
                            recordCallback.onSuccess(path);
                            recordCallback = null;
                        }
                    });
                }

                @Override
                public void onError(String message) {
                    setRenderMode(RENDERMODE_WHEN_DIRTY);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (recordCallback != null) {
                            recordCallback.onError(message);
                            recordCallback = null;
                        }
                    });
                }
            });
        } catch (Exception e) {
            setRenderMode(RENDERMODE_WHEN_DIRTY);
            throw e;
        }
        requestRender();
    }

    @Override
    public void stopVideoRecord(Context context, CameraCaptureCallback callback) {
        recordCallback = callback;
        recordSession.stop();
    }

    /**
     * 使用官方 PhotoRecordHelper 读取 CameraRenderer 的处理后纹理。
     * 这里不再读取 GLSurfaceView 的屏幕像素，避免把 Surface 的显示层当成
     * SDK 输出，也避免重复实现 RGBA 转换和上下翻转。
     */
    private void captureCurrentFrame(FURenderOutputData outputData,
                                     FURenderFrameData frameData) {
        final CameraCaptureCallback cb = captureCallback;
        captureCallback = null;
        if (cb == null) {
            return;
        }
        FURenderOutputData.FUTexture texture = outputData.getTexture();
        try {
            PhotoRecordHelper helper = new PhotoRecordHelper(new OnPhotoRecordingListener() {
                @Override
                public void onRecordSuccess(Bitmap bitmap) {
                    try {
                        String path = writeBitmapToGallery(getContext(), bitmap);
                        if (path == null || path.isEmpty()) {
                            postCaptureError(cb, "保存相册失败");
                        } else {
                            new Handler(Looper.getMainLooper()).post(() -> cb.onSuccess(path));
                        }
                    } catch (Throwable t) {
                        postCaptureError(cb, t.getMessage() != null ? t.getMessage() : "拍照失败");
                    } finally {
                        if (bitmap != null && !bitmap.isRecycled()) {
                            bitmap.recycle();
                        }
                    }
                }
            });
            helper.sendRecordingData(
                    texture.getTexId(),
                    frameData.getTexMatrix(),
                    GlUtil.IDENTITY_MATRIX,
                    texture.getWidth(),
                    texture.getHeight(),
                    false,
                    true
            );
        } catch (Throwable t) {
            postCaptureError(cb, t.getMessage() != null ? t.getMessage() : "拍照失败");
        }
    }

    private static void postCaptureError(CameraCaptureCallback callback, String message) {
        new Handler(Looper.getMainLooper()).post(() -> callback.onError(message));
    }

    private static String writeBitmapToGallery(Context context, Bitmap bitmap) {
        if (context == null || bitmap == null) {
            return null;
        }
        Uri uri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "FU_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FULive");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            uri = context.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            OutputStream out = context.getContentResolver().openOutputStream(uri);
            if (out == null) {
                return null;
            }
            try (OutputStream stream = out) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream);
                stream.flush();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            }
            return uri.toString();
        } catch (Throwable t) {
            Log.e(TAG, "writeBitmapToGallery", t);
            if (uri != null) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    @Override
    public android.view.View asView() {
        return this;
    }

    @Override
    public void requestRender() {
        super.requestRender();
    }

    @Override
    public boolean isGlSurfaceValid() {
        try {
            return getHolder() != null
                    && getHolder().getSurface() != null
                    && getHolder().getSurface().isValid();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void destroyPreviewAsync(Runnable onFinished) {
        destroyPreviewAsync(false, onFinished);
    }

    /** SDK 内部 clamp 到 [10, 100]；<=0 表示不限帧。 */
    static int resolveCameraFpsForConfig(int maxFps) {
        if (maxFps <= 0) {
            return -1;
        }
        return Math.max(10, Math.min(100, maxFps));
    }

    public static int getMaxPreviewFps() {
        return sMaxPreviewFps;
    }

    /** 设置预览最高帧率；<=0 为不限。下次创建 CameraRenderer 时生效。 */
    public static void setMaxPreviewFps(int maxFps) {
        sMaxPreviewFps = maxFps <= 0 ? 0 : resolveCameraFpsForConfig(maxFps);
        Log.d(TAG, "setMaxPreviewFps=" + sMaxPreviewFps + " (cameraFPS="
                + resolveCameraFpsForConfig(sMaxPreviewFps) + ")");
    }

    public static void resetMaxPreviewFps() {
        sMaxPreviewFps = 30;
    }
}
