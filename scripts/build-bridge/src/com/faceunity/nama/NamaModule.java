package com.faceunity.nama;

import android.opengl.GLSurfaceView;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupWindow;

import com.alibaba.fastjson.JSONObject;
import com.faceunity.nama.core.BeautyCameraGLView;
import com.faceunity.nama.core.BeautyParamApplier;
import com.faceunity.nama.core.BeautyVideoGLView;
import com.faceunity.nama.core.DeviceQuirk;
import com.faceunity.nama.core.FuBeautyHandle;
import com.faceunity.nama.core.FuBeautyPerfGate;
import com.faceunity.nama.core.ImageBeautyProcessor;
import com.faceunity.nama.core.MediaFuSetup;
import com.faceunity.nama.core.NamaGlExecutor;
import com.faceunity.nama.core.NamaSdkManager;
import com.faceunity.nama.core.VideoBeautyProcessor;
import com.faceunity.nama.ui.FocusHudView;
import com.faceunity.nama.ui.FuBeautyPanelView;
import com.faceunity.nama.ui.FuExportProgressHud;
import com.faceunity.nama.ui.NamaToast;
import com.faceunity.nama.ui.PreviewChromeView;
import com.faceunity.nama.utils.MediaPathUtil;
import com.faceunity.nama.utils.NamaJsResult;
import com.faceunity.wrapper.faceunity;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

/**
 * FaceUnity Nama 桥接：showCamera + GLSurfaceView overlay（原版可出画面方案）
 */
public class NamaModule extends UniModule {

    private static final String TAG = "FaceUnity-Nama";
    /** 对齐 FULiveDemoDroid FileUtils.pickImageFile / pickVideoFile */
    private static final int REQ_PICK_MEDIA = 0x4E414D01;

    private UniJSCallback pickMediaCallback;
    private String pickMediaExt = ".jpg";

    private static BeautyCameraGLView overlayCameraView;
    private static FrameLayout overlayCameraHost;
    /**
     * 系统导航切换会重建 Activity。此后 park 若再 setZOrderOnTop(false)，
     * Android 会毁掉 Surface/EGL，第二次进相机复用旧 beauty handle 就会无美颜或黑屏。
     */
    private static volatile boolean sPreserveCameraEglOnPark = false;
    /** 进后台时记住曝光 UI，回前台恢复（避免拉杆误跳 100） */
    private static int sPausedExposureUi = -1;
    /** 视频预览过后：静图勿再走相机 GL，避免矩阵残留颠倒；纯导入图片仍走相机 GL（已验证正常） */
    private static volatile boolean sAvoidCameraGlForImageAfterVideo = false;
    /** 仅视频交还相机时为 true；soft-hide 不要走 resumeGlAfterHandoff（成对 onPause 缺失会黑屏） */
    private static volatile boolean sCameraGlHandedOff = false;
    private static BeautyVideoGLView overlayVideoView;
    private static FrameLayout overlayVideoHost;
    private static ImageView overlayVideoPlayBtn;
    private static String lastVideoPath = null;
    private static PreviewChromeView previewChromeView;
    private static PopupWindow previewChromePopup;
    private static FuBeautyPanelView beautyPanelView;
    private static PopupWindow beautyPanelPopup;
    /** 媒体页左上角返回（PopupWindow 盖住 setZOrderOnTop 的视频 GLSurfaceView） */
    private static FrameLayout mediaBackBtn;
    private static PopupWindow mediaBackPopup;
    private static int lastCssX = -1;
    private static int lastCssY = -1;
    private static int lastCssW = -1;
    private static int lastCssH = -1;
    /** 旧 Popup 对焦层（无 chrome 时兜底）；有 PreviewChrome 时优先进 chrome 内 FocusHud */
    private static FocusHudView focusHudView;
    private static PopupWindow focusHudPopup;

    private static PopupWindow sExportHudPopup;
    /** SDK DEBUG 文件日志路径（{@link MediaFuSetup#SDK_LOG_FILE_NAME}） */

    /** 视频显示层取相机 GL：Nama 只挂在相机上下文，视频页禁止自建 Nama */
    public static BeautyCameraGLView peekCameraOverlay() {
        return overlayCameraView;
    }

    static void setPreviewTipsEnabled(boolean enabled) {
        NamaToast.setTipsEnabled(enabled);
        if (previewChromeView != null) {
            previewChromeView.setTipsEnabled(enabled);
            if (enabled) {
                previewChromeView.setNoFaceVisible(false);
            }
        }
    }

    /** GL 线程上报人脸跟踪；主线程刷新「未检测到人脸」 */
    public static void onFaceTrackingUpdated(boolean tracked) {
        NamaToast.onFaceTrackingUpdated(tracked);
        final boolean faceTracked = tracked;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (previewChromeView != null) {
                    // PreviewChrome 自带 tipsEnabled 判断；勿在 GL 线程直接改 View
                    previewChromeView.setNoFaceVisible(!faceTracked);
                } else {
                    Activity act = resolveStaticActivity();
                    if (act != null) {
                        NamaToast.setNoFaceVisible(act, !faceTracked);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "onFaceTrackingUpdated ui", t);
            }
        });
    }

    /** 机型限制提示（对齐 iOS showPreviewPerfLimitTip / FULiveDemo 灰显点击 toast） */
    public static void showPerfLimitTip(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        if (previewChromeView != null) {
            previewChromeView.showPerfLimitTip(message);
            return;
        }
        Activity act = resolveStaticActivity();
        if (act != null) {
            NamaToast.showPerfLimitTip(act, message);
        }
    }

    /** 切滤镜：画面正中短暂显示滤镜名（对齐 Demo） */
    public static void showFilterNameTip(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        boolean mediaOverlay = overlayVideoView != null || overlayVideoHost != null;
        if (!mediaOverlay && previewChromeView != null && overlayCameraView != null) {
            previewChromeView.showFilterNameTip(name);
            return;
        }
        Activity act = resolveStaticActivity();
        if (act != null) {
            NamaToast.showFilterName(act, name);
        }
    }

    private static Activity resolveStaticActivity() {
        try {
            if (overlayCameraView != null && overlayCameraView.getContext() instanceof Activity) {
                return (Activity) overlayCameraView.getContext();
            }
            if (overlayVideoView != null && overlayVideoView.getContext() instanceof Activity) {
                return (Activity) overlayVideoView.getContext();
            }
            if (previewChromeView != null && previewChromeView.getContext() instanceof Activity) {
                return (Activity) previewChromeView.getContext();
            }
            if (beautyPanelView != null && beautyPanelView.getContext() instanceof Activity) {
                return (Activity) beautyPanelView.getContext();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @UniJSMethod(uiThread = false)
    public void drainSdkLog(UniJSCallback callback) {
        if (callback != null) {
            callback.invoke(success(""));
        }
    }

    @UniJSMethod(uiThread = false)
    public void getNamaSdkLogPath(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        JSONObject data = new JSONObject();
        data.put("path", "");
        data.put("exists", false);
        data.put("size", 0L);
        callback.invoke(success(data));
    }

    /** Android 已禁用 SDK 文件日志与分享 */
    @UniJSMethod(uiThread = true)
    public void shareNamaSdkLog(UniJSCallback callback) {
        if (callback != null) {
            callback.invoke(fail("SDK 文件日志已禁用"));
        }
    }

    @UniJSMethod(uiThread = true)
    public void getVersion(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        callback.invoke(NamaSdkManager.getVersion());
    }

    /** Android 进程存活重进：JS 缓存 sdkInited 时校验 native 是否仍就绪 */
    @UniJSMethod(uiThread = false)
    public void isSdkAlive(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        watchHostRecreate();
        JSONObject info = NamaSdkManager.isSdkAlive();
        // 额外校验宿主 Activity 是否仍有效（导航模式切换等配置变更会重建 Activity）
        boolean ctxAlive = false;
        try {
            Activity host = resolveHostActivity();
            ctxAlive = host != null && !host.isFinishing() && !host.isDestroyed();
        } catch (Throwable ignored) {
        }
        try {
            JSONObject data = info.getJSONObject("data");
            data.put("contextAlive", ctxAlive);
            boolean overlayMounted = overlayCameraView != null;
            boolean overlayActDead = false;
            boolean overlaySurfaceValid = false;
            try {
                if (overlayCameraView != null) {
                    if (overlayCameraView.getContext() instanceof Activity) {
                        Activity oa = (Activity) overlayCameraView.getContext();
                        overlayActDead = oa.isFinishing() || oa.isDestroyed();
                        if (overlayActDead) {
                            sPreserveCameraEglOnPark = true;
                        }
                    }
                    android.view.SurfaceHolder holder = overlayCameraView.getHolder();
                    if (holder != null && holder.getSurface() != null) {
                        overlaySurfaceValid = holder.getSurface().isValid();
                    }
                }
            } catch (Throwable ignored) {
            }
            data.put("overlayMounted", overlayMounted);
            data.put("overlayActDead", overlayActDead);
            data.put("overlaySurfaceValid", overlaySurfaceValid);
        } catch (Throwable ignored) {
        }
        callback.invoke(info);
    }

    private static void runOnMainSync(Runnable action) {
        if (action == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
            return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                action.run();
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void detachOverlayHost(View host, View view) {
        try {
            if (host != null) {
                host.setVisibility(View.GONE);
                ViewGroup parent = (ViewGroup) host.getParent();
                if (parent != null) {
                    parent.removeView(host);
                }
                return;
            }
            if (view != null) {
                view.setVisibility(View.GONE);
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) {
                    parent.removeView(view);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void resetParkedBoxCache() {
        sParkedBoxLeft = Integer.MIN_VALUE;
        sParkedBoxTop = Integer.MIN_VALUE;
        sParkedBoxW = -1;
        sParkedBoxH = -1;
    }

    /** Activity 被重建后清理 stale 的 overlay 引用，让后续 showCamera/showVideo 能重建 */
    @UniJSMethod(uiThread = false)
    public void resetStaleOverlays(UniJSCallback callback) {
        try {
            resetStaleOverlaysInternal();
        } catch (Throwable ignored) {
        }
        if (callback != null) {
            callback.invoke(success(new JSONObject()));
        }
    }

    private static void resetStaleOverlaysInternal() {
        final BeautyCameraGLView cam = overlayCameraView;
        final FrameLayout camHost = overlayCameraHost;
        final BeautyVideoGLView video = overlayVideoView;
        final FrameLayout videoHost = overlayVideoHost;
        overlayCameraView = null;
        overlayCameraHost = null;
        overlayVideoView = null;
        overlayVideoHost = null;
        sPreserveCameraEglOnPark = true;
        resetParkedBoxCache();
        // 必须从窗口拆掉，只 destroyPreview 会留下暂停帧挡住下一页
        runOnMainSync(() -> {
            detachOverlayHost(camHost, cam);
            if (cam != null) {
                try {
                    cam.destroyPreview();
                } catch (Throwable ignored) {
                }
            }
            detachOverlayHost(videoHost, video);
            if (video != null) {
                try {
                    video.stopAndRelease();
                } catch (Throwable ignored) {
                }
            }
        });
        // EGL context 已随旧 Activity 销毁，通知 SDK 清理 stale GL 资源
        try {
            MediaFuSetup.deviceLostOnCurrentGl();
        } catch (Throwable ignored) {
        }
        // 清 beauty handle，让后续 loadBundle 重建
        try {
            NamaSdkManager.markResourcesLost("resetStaleOverlays");
        } catch (Throwable ignored) {
        }
        try {
            if (focusHudPopup != null) {
                focusHudPopup.dismiss();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mediaBackPopup != null) {
                mediaBackPopup.dismiss();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (beautyPanelPopup != null) {
                beautyPanelPopup.dismiss();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (previewChromePopup != null) {
                previewChromePopup.dismiss();
            }
        } catch (Throwable ignored) {
        }
        overlayCameraView = null;
        overlayCameraHost = null;
        overlayVideoView = null;
        overlayVideoHost = null;
        previewChromeView = null;
        beautyPanelView = null;
        focusHudView = null;
        focusHudPopup = null;
        mediaBackBtn = null;
        mediaBackPopup = null;
        beautyPanelPopup = null;
        previewChromePopup = null;
        Log.i(TAG, "resetStaleOverlays done");
    }

    @UniJSMethod(uiThread = false)
    public void init(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        watchHostRecreate();
        callback.invoke(NamaSdkManager.init(resolveHostActivity()));
    }

    /** 切导航后进程还在、Activity 已重建：整进程冷启动，禁止半死复用 overlay */
    @UniJSMethod(uiThread = true)
    public void relaunchProcess(UniJSCallback callback) {
        Activity act = resolveHostActivity();
        Context ctx = act;
        if (ctx == null) {
            ctx = resolveStaticActivity();
        }
        NamaHostRecreateWatch.relaunch(ctx);
        if (callback != null) {
            callback.invoke(success(0));
        }
    }

    @UniJSMethod(uiThread = false)
    public void loadAIModel(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        callback.invoke(NamaSdkManager.loadAIModel(options));
    }

    @UniJSMethod(uiThread = false)
    public void loadBundle(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        callback.invoke(NamaSdkManager.loadBundle(options));
    }

    /** JS 复用相机 beauty handle 时，同步原生 mediaBeautyItemHandle，避免媒体页 handle=0 */
    @UniJSMethod(uiThread = false)
    public void bindMediaBeautyHandle(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        callback.invoke(NamaSdkManager.bindMediaBeautyHandle(options));
    }

    @UniJSMethod(uiThread = false)
    public void setParam(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        callback.invoke(NamaSdkManager.setParam(options, overlayCameraView));
    }

    @UniJSMethod(uiThread = true)
    public void showCamera(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        if (options == null) {
            options = new JSONObject();
        }
        try {
            ensureInitialized();
            int x = options.getIntValue("x");
            int y = options.getIntValue("y");
            int width = options.getIntValue("width");
            int height = options.getIntValue("height");
            if (width <= 0 || height <= 0) {
                callback.invoke(fail("width/height 无效"));
                return;
            }
            // 已有相机层时只改 LayoutParams，避免 destroy → fuOnDeviceLostSafe 弄失效美颜 handle
            if (overlayCameraView != null && overlayCameraHost != null) {
                // 安全检查：若 overlay 来自已销毁的 Activity，清理 stale 引用后走重建
                boolean staleView = false;
                try {
                    if (overlayCameraView.getContext() instanceof Activity) {
                        Activity oldAct = (Activity) overlayCameraView.getContext();
                        if (oldAct.isFinishing() || oldAct.isDestroyed()) {
                            staleView = true;
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (staleView) {
                    sPreserveCameraEglOnPark = true;
                    try {
                        overlayCameraView.destroyPreview();
                    } catch (Throwable ignored) {
                    }
                    overlayCameraView = null;
                    overlayCameraHost = null;
                    previewChromeView = null;
                    beautyPanelView = null;
                }
            }
            if (overlayCameraView != null && overlayCameraHost != null) {
                Activity act = resolveHostActivity();
                if (act == null) {
                    scheduleShowCameraActivityRetry(options, callback, 0);
                    return;
                }
                float density = act.getResources().getDisplayMetrics().density;
                int pxX = cssToPhysical(density, x);
                int pxY = cssToPhysical(density, y);
                int pxW = cssToPhysical(density, width);
                int pxH = cssToPhysical(density, height);
                if (overlayCameraHost.getChildCount() > 0) {
                    View previewBox = overlayCameraHost.getChildAt(0);
                    ViewGroup.LayoutParams rawLp = previewBox.getLayoutParams();
                    if (rawLp instanceof FrameLayout.LayoutParams) {
                        FrameLayout.LayoutParams boxLp = (FrameLayout.LayoutParams) rawLp;
                        boxLp.width = pxW;
                        boxLp.height = pxH;
                        boxLp.leftMargin = pxX;
                        boxLp.topMargin = pxY;
                        previewBox.setLayoutParams(boxLp);
                    }
                    // 布局落地后再按真实像素绑尺寸；resizeOnly 禁止 setFixedSize（面板展开会跳画面）
                    final boolean resizeOnlyBind = options.getBooleanValue("resizeOnly");
                    previewBox.post(() -> {
                        int aw = Math.max(previewBox.getWidth(), pxW);
                        int ah = Math.max(previewBox.getHeight(), pxH);
                        if (overlayCameraView != null) {
                            overlayCameraView.bindLayoutSize(aw, ah);
                            if (!resizeOnlyBind && !sPreserveCameraEglOnPark) {
                                try {
                                    if (overlayCameraView.getHolder() != null) {
                                        overlayCameraView.getHolder().setFixedSize(aw, ah);
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                            overlayCameraView.requestRender();
                        }
                    });
                }
                overlayCameraView.bindLayoutSize(pxW, pxH);
                lastCssX = x;
                lastCssY = y;
                lastCssW = width;
                lastCssH = height;
                bringOverlayToFront();
                unparkCameraOverlay();
                overlayCameraHost.setVisibility(View.VISIBLE);
                overlayCameraView.setVisibility(View.VISIBLE);
                boolean resizeOnly = options.getBooleanValue("resizeOnly");
                if (resizeOnly) {
                    overlayCameraView.requestRender();
                }
                // 非 resizeOnly：unpark 已 resume，勿重复 resumePreview
                syncPreviewChromeLayout(pxX, pxY, pxW, pxH);
                Log.e(TAG, "showCamera resized css:" + width + "x" + height + "@" + x + "," + y
                        + " resizeOnly=" + resizeOnly
                        + " previewStarted=" + overlayCameraView.isPreviewStarted());
                JSONObject reused = new JSONObject();
                reused.put("x", x);
                reused.put("y", y);
                reused.put("width", width);
                reused.put("height", height);
                reused.put("reused", true);
                reused.put("resized", true);
                reused.put("resizeOnly", resizeOnly);
                reused.put("cameraError", BeautyCameraGLView.getLastError());
                reused.put("diag", BeautyCameraGLView.getPreviewDiag());
                callback.invoke(success(reused));
                return;
            }
            Activity activity = resolveHostActivity();
            if (activity == null) {
                scheduleShowCameraActivityRetry(options, callback, 0);
                return;
            }

            final JSONObject opts = options;
            final UniJSCallback cb = callback;
            hideCameraInternal(true, () -> mountCameraOverlay(activity, opts, cb));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /** 首进页 Activity 可能尚未绑定到 UniSDKInstance，短延迟重试避免「执行出错」 */
    private void scheduleShowCameraActivityRetry(final JSONObject options, final UniJSCallback callback, final int attempt) {
        if (attempt >= 16) {
            callback.invoke(fail("activity null"));
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureInitialized();
                } catch (Exception e) {
                    if (attempt >= 15) {
                        callback.invoke(fail(e.getMessage()));
                    } else {
                        scheduleShowCameraActivityRetry(options, callback, attempt + 1);
                    }
                    return;
                }
                Activity activity = resolveHostActivity();
                if (activity == null) {
                    scheduleShowCameraActivityRetry(options, callback, attempt + 1);
                    return;
                }
                if (overlayCameraView != null && overlayCameraHost != null) {
                    showCamera(options, callback);
                    return;
                }
                final JSONObject opts = options;
                final UniJSCallback cb = callback;
                hideCameraInternal(true, () -> mountCameraOverlay(activity, opts, cb));
            }
        }, 50L + attempt * 50L);
    }

    private void mountCameraOverlay(Activity activity, JSONObject options, UniJSCallback callback) {
        try {
            int x = options.getIntValue("x");
            int y = options.getIntValue("y");
            int width = options.getIntValue("width");
            int height = options.getIntValue("height");

            float density = activity.getResources().getDisplayMetrics().density;
            int pxX = cssToPhysical(density, x);
            int pxY = cssToPhysical(density, y);
            int pxW = cssToPhysical(density, width);
            int pxH = cssToPhysical(density, height);

            ViewGroup root = resolveOverlayRoot(activity);
            FrameLayout host = new FrameLayout(activity);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                host.setElevation(48f);
            }
            attachOverlayHostOnDecor(root, host);

            FrameLayout previewBox = new FrameLayout(activity);
            FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(pxW, pxH);
            boxLp.leftMargin = pxX;
            boxLp.topMargin = pxY;
            host.addView(previewBox, boxLp);

            resetParkedBoxCache();
            BeautyCameraGLView view = new BeautyCameraGLView(activity);
            previewBox.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            view.bindLayoutSize(pxW, pxH);
            overlayCameraView = view;
            overlayCameraHost = host;
            lastCssX = x;
            lastCssY = y;
            lastCssW = width;
            lastCssH = height;

            bringOverlayToFront();

            Log.e(TAG, "showCamera css:" + width + "x" + height + "@" + x + "," + y
                    + " physical:" + pxW + "x" + pxH + "@" + pxX + "," + pxY
                    + " density=" + density);

            JSONObject data = new JSONObject();
            data.put("x", x);
            data.put("y", y);
            data.put("width", width);
            data.put("height", height);
            data.put("cameraError", BeautyCameraGLView.getLastError());
            data.put("diag", BeautyCameraGLView.getPreviewDiag());
            callback.invoke(success(data));

            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.e(TAG, "previewDiag " + BeautyCameraGLView.getPreviewDiag()
                            + " view=" + view.getWidth() + "x" + view.getHeight()
                            + " box=" + width + "x" + height);
                }
            }, 3000);
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void resizeCameraPreview(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        if (options == null) {
            options = new JSONObject();
        }
        options.put("resizeOnly", true);
        showCamera(options, callback);
    }

    @UniJSMethod(uiThread = true)
    public void pauseCameraPreview(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            // 进后台/多任务：冻帧停采集，勿 park 出屏（否则系统缩略图黑屏）
            if (overlayCameraView != null) {
                sPausedExposureUi = overlayCameraView.getLastExposureUi();
                overlayCameraView.freezePreview();
            }
            dismissFocusHud();
            callback.invoke(success(0));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void resumeCameraPreview(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            bringOverlayToFront();
            unparkCameraOverlay();
            if (overlayCameraHost != null) {
                overlayCameraHost.setVisibility(View.VISIBLE);
            }
            if (overlayCameraView != null) {
                overlayCameraView.setVisibility(View.VISIBLE);
                // unpark 已 resume，勿重复
            }
            callback.invoke(success(0));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void destroyCameraPreview(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            // 彻底拆除（含 deviceLost）
            hideCameraInternal(false, () -> callback.invoke(success(0)));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void hideCamera(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            boolean keepSession = true;
            if (options != null && options.containsKey("keepSession")) {
                keepSession = options.getBooleanValue("keepSession");
            }
            boolean detach = options != null && options.getBooleanValue("detach");
            // detach：从窗口拆掉 overlay，保留 SDK（脏会话离开，禁止留下暂停帧）
            // keepSession=true：只 soft-hide，不拆 GL
            // keepSession=false：拆除 overlay + deviceLost
            if (detach) {
                hideCameraInternal(true, () -> callback.invoke(success(0)));
            } else if (keepSession) {
                softHideCameraOverlay(() -> callback.invoke(success(0)));
            } else {
                hideCameraInternal(false, () -> callback.invoke(success(0)));
            }
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /** 兼容旧基座签名：无 options 时按保留会话处理 */
    @UniJSMethod(uiThread = true)
    public void hideCamera(UniJSCallback callback) {
        hideCamera(null, callback);
    }

    @UniJSMethod(uiThread = true)
    public void setOverlayWindowsHidden(JSONObject options, UniJSCallback callback) {
        try {
            boolean hidden = options != null && options.getBooleanValue("hidden");
            if (hidden) {
                // 相机：停采 + 移出屏幕（老 vivo ZOrderOnTop 不跟 alpha，须 hidePreview）
                parkCameraOverlayHidden(true);
                // 仅当调用方明确要藏视频时才 GONE；媒体页进视频前也会调 hidden=true，
                // 若与 mount 竞态会把刚挂上的视频藏掉 → 黑屏。视频改由 destroyVideoPreview 拆除。
            } else {
                // 只恢复视频叠层；相机必须由 resumeCameraPreview / showCamera 显式恢复
                if (overlayVideoHost != null) {
                    overlayVideoHost.setVisibility(View.VISIBLE);
                    bringVideoOverlayToFront();
                } else if (overlayVideoView != null) {
                    overlayVideoView.setVisibility(View.VISIBLE);
                }
            }
            if (callback != null) {
                callback.invoke(success(0));
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.invoke(fail(e.getMessage()));
            }
        }
    }

    @UniJSMethod(uiThread = false)
    public void getDevicePerformanceLevel(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            int level = MediaFuSetup.getDevicePerformanceLevel();
            JSONObject data = new JSONObject();
            data.put("level", level);
            data.put("ramGb", Math.round(MediaFuSetup.getTotalRamGbForDiag() * 100.0) / 100.0);
            data.put("cores", Runtime.getRuntime().availableProcessors());
            callback.invoke(success(data));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = false)
    public void getPreviewDiag(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        JSONObject data = new JSONObject();
        data.put("diag", BeautyCameraGLView.getPreviewDiag());
        data.put("mounted", overlayCameraView != null);
        data.put("stats", BeautyCameraGLView.getPreviewStatsJson());
        callback.invoke(success(data));
    }

    @UniJSMethod(uiThread = false)
    public void getPreviewStats(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        callback.invoke(success(BeautyCameraGLView.getPreviewStatsJson()));
    }

    @UniJSMethod(uiThread = true)
    public void setBeautyEnabled(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            boolean enabled = options == null || options.getBooleanValue("enabled");
            String pipeline = options != null ? options.getString("pipeline") : null;
            boolean mediaOnly = pipeline != null && "media".equalsIgnoreCase(pipeline);
            boolean cameraOnly = pipeline != null && "camera".equalsIgnoreCase(pipeline);
            if (!mediaOnly) {
                BeautyCameraGLView.setBeautyEnabledGlobal(enabled);
                if (overlayCameraView != null) {
                    overlayCameraView.setBeautyEnabled(enabled);
                    overlayCameraView.requestRender();
                }
            }
            if (!cameraOnly) {
                BeautyVideoGLView.setBeautyEnabledGlobal(enabled);
                if (overlayVideoView != null) {
                    overlayVideoView.setBeautyEnabled(enabled);
                }
            }
            callback.invoke(success(enabled ? 1 : 0));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void setDualInput(JSONObject options, UniJSCallback callback) {
        try {
            boolean dual = options == null || options.getBooleanValue("dual");
            if (overlayCameraView != null) {
                overlayCameraView.setDualInputEnabled(dual);
            }
            if (previewChromeView != null) {
                previewChromeView.setDualInputState(dual);
            }
            if (callback != null) {
                callback.invoke(success(dual ? 1 : 0));
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.invoke(fail(e.getMessage()));
            }
        }
    }

    @UniJSMethod(uiThread = true)
    public void switchCamera(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            if (overlayCameraView == null) {
                callback.invoke(fail("相机未挂载"));
                return;
            }
            overlayCameraView.switchCameraFacing();
            callback.invoke(success(0));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /**
     * 点击对焦 + 显示原生十字/曝光滑杆（PopupWindow 盖住 setZOrderOnTop 的取景）。
     * 兼容 iOS 同名接口：优先 nx/ny（0~1）；也可传 localX/localY + preview 框 css。
     */
    @UniJSMethod(uiThread = true)
    public void tapFocus(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            if (overlayCameraView == null || overlayCameraHost == null) {
                callback.invoke(fail("相机未挂载"));
                return;
            }
            Activity activity = resolveHostActivity();
            if (activity == null) {
                callback.invoke(fail("activity null"));
                return;
            }
            if (options == null) {
                options = new JSONObject();
            }
            int previewX = options.containsKey("previewX") ? options.getIntValue("previewX") : lastCssX;
            int previewY = options.containsKey("previewY") ? options.getIntValue("previewY") : lastCssY;
            int previewW = options.containsKey("previewW") ? options.getIntValue("previewW") : lastCssW;
            int previewH = options.containsKey("previewH") ? options.getIntValue("previewH") : lastCssH;
            int exposure = options.containsKey("exposure")
                    ? options.getIntValue("exposure")
                    : (previewChromeView != null ? previewChromeView.getFocusExposure() : 50);
            if (previewW <= 0 || previewH <= 0) {
                callback.invoke(fail("preview size 无效"));
                return;
            }

            float localX;
            float localY;
            if (options.containsKey("nx") || options.containsKey("ny")) {
                float nx = (float) options.getDoubleValue("nx");
                float ny = (float) options.getDoubleValue("ny");
                nx = Math.max(0f, Math.min(1f, nx));
                ny = Math.max(0f, Math.min(1f, ny));
                localX = nx * previewW;
                localY = ny * previewH;
            } else {
                localX = (float) options.getDoubleValue("localX");
                localY = (float) options.getDoubleValue("localY");
            }

            applyFocusAtCss(activity, localX, localY, previewX, previewY, previewW, previewH, exposure);
            callback.invoke(success(0));
        } catch (Exception e) {
            Log.e(TAG, "tapFocus", e);
            callback.invoke(fail(e.getMessage()));
        }
    }

    private void applyFocusAtCss(
            Activity activity,
            float localCssX,
            float localCssY,
            int previewX,
            int previewY,
            int previewW,
            int previewH,
            int exposure
    ) {
        if (overlayCameraView == null) {
            return;
        }
        // exposure 参数保留兼容调用方；实际以相机记住的值为准
        if (exposure < 0 || exposure > 100) {
            exposure = 50;
        }
        overlayCameraView.tapToFocus(localCssX, localCssY, previewW, previewH);
        // 用户已调过曝光（含 UI=0 最低档）时始终沿用相机记忆值，勿被 chrome 默认 50 覆盖
        int ev = overlayCameraView.getLastExposureUi();
        if (!overlayCameraView.isExposureLockedByUser() && ev == 50 && exposure != 50) {
            ev = exposure;
        }
        overlayCameraView.setExposureCompensation(ev);

        Activity host = activity != null ? activity : resolveHostActivity();
        if (host == null) {
            return;
        }
        ensurePreviewChrome(host);
        if (previewChromeView == null) {
            return;
        }
        float density = host.getResources().getDisplayMetrics().density;
        int pxX = cssToPhysical(density, previewX);
        int pxY = cssToPhysical(density, previewY);
        int pxW = cssToPhysical(density, previewW);
        int pxH = cssToPhysical(density, previewH);
        // 对焦画在 chrome 同一层底层；先保证 chrome popup 尺寸对齐取景
        syncPreviewChromeLayout(pxX, pxY, pxW, pxH);
        previewChromeView.setFocusExposure(ev);
        previewChromeView.showFocusAt(localCssX * density, localCssY * density, ev);
    }

    @UniJSMethod(uiThread = true)
    public void setCameraExposure(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            if (overlayCameraView == null) {
                callback.invoke(fail("相机未挂载"));
                return;
            }
            int exposure = 50;
            if (options != null) {
                if (options.containsKey("exposure")) {
                    exposure = options.getIntValue("exposure");
                } else if (options.containsKey("value")) {
                    // 兼容 iOS setExposureBias：value 为 -1~1 或 0~1，映射到 0~100
                    double v = options.getDoubleValue("value");
                    if (v >= -1.0 && v <= 1.0) {
                        exposure = (int) Math.round((v + 1.0) * 50.0);
                    } else {
                        exposure = (int) Math.round(v);
                    }
                }
            }
            exposure = Math.max(0, Math.min(100, exposure));
            overlayCameraView.setExposureCompensation(exposure);
            if (previewChromeView != null) {
                previewChromeView.setFocusExposure(exposure);
            }
            callback.invoke(success(exposure));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /** 对齐 iOS 方法名 */
    @UniJSMethod(uiThread = true)
    public void setExposureBias(JSONObject options, UniJSCallback callback) {
        setCameraExposure(options, callback);
    }

    @UniJSMethod(uiThread = true)
    public void hideFocusHud(UniJSCallback callback) {
        try {
            dismissFocusHud();
            if (callback != null) {
                callback.invoke(success(0));
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.invoke(fail(e.getMessage()));
            }
        }
    }

    /** 对齐 iOS 方法名 */
    @UniJSMethod(uiThread = true)
    public void hideFocusChrome(UniJSCallback callback) {
        hideFocusHud(callback);
    }

    @UniJSMethod(uiThread = true)
    public void showPreviewChrome(JSONObject options, UniJSCallback callback) {
        try {
            Activity activity = resolveHostActivity();
            if (activity == null || overlayCameraView == null) {
                if (callback != null) {
                    callback.invoke(fail("camera not ready"));
                }
                return;
            }
            int previewX = options != null && options.containsKey("x") ? options.getIntValue("x") : lastCssX;
            int previewY = options != null && options.containsKey("y") ? options.getIntValue("y") : lastCssY;
            int previewW = options != null && options.containsKey("width") ? options.getIntValue("width") : lastCssW;
            int previewH = options != null && options.containsKey("height") ? options.getIntValue("height") : lastCssH;
            if (previewW <= 0 || previewH <= 0) {
                if (callback != null) {
                    callback.invoke(fail("preview size 无效"));
                }
                return;
            }
            ensurePreviewChrome(activity);
            if (previewChromeView != null && options != null) {
                String resId = options.getString("resolutionId");
                if (resId != null && !resId.isEmpty()) {
                    previewChromeView.setSelectedResolutionId(resId);
                }
                if (options.containsKey("dualInput")) {
                    boolean dual = options.getBooleanValue("dualInput");
                    previewChromeView.setDualInputState(dual);
                    if (overlayCameraView != null) {
                        overlayCameraView.setDualInputEnabled(dual);
                    }
                }
            }
            float density = activity.getResources().getDisplayMetrics().density;
            int pxX = cssToPhysical(density, previewX);
            int pxY = cssToPhysical(density, previewY);
            int pxW = cssToPhysical(density, previewW);
            int pxH = cssToPhysical(density, previewH);
            syncPreviewChromeLayout(pxX, pxY, pxW, pxH);
            setPreviewTipsEnabled(true);
            if (callback != null) {
                callback.invoke(success(0));
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.invoke(fail(e.getMessage()));
            }
        }
    }

    @UniJSMethod(uiThread = true)
    public void updatePreviewChromeStats(JSONObject options, UniJSCallback callback) {
        try {
            if (previewChromeView != null && options != null) {
                String res = options.getString("resolution");
                if (res == null) {
                    res = String.valueOf(options.getIntValue("resolution"));
                }
                previewChromeView.updateStats(
                        res != null ? res : "-",
                        options.getIntValue("fps"),
                        options.getIntValue("renderTime")
                );
            }
            if (callback != null) {
                callback.invoke(success(0));
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.invoke(fail(e.getMessage()));
            }
        }
    }

    @UniJSMethod(uiThread = true)
    public void setPreviewChromeRecording(JSONObject options, UniJSCallback callback) {
        try {
            if (previewChromeView != null) {
                previewChromeView.setRecording(options != null && options.getBooleanValue("recording"));
            }
            if (callback != null) {
                callback.invoke(success(0));
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.invoke(fail(e.getMessage()));
            }
        }
    }

    @UniJSMethod(uiThread = true)
    public void hidePreviewChrome(UniJSCallback callback) {
        try {
            dismissPreviewChrome();
            if (callback != null) {
                callback.invoke(success(0));
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.invoke(fail(e.getMessage()));
            }
        }
    }

    @UniJSMethod(uiThread = true)
    public void showBeautyPanel(JSONObject options, UniJSCallback callback) {
        Activity activity = resolveHostActivity();
        if (activity == null) {
            if (callback != null) {
                callback.invoke(fail("activity null"));
            }
            return;
        }
        try {
            ensureBeautyPanel(activity);
            JSONObject cfg = options != null ? options : new JSONObject();
            cfg.put("devicePerfLevel", MediaFuSetup.getDevicePerformanceLevel());
            beautyPanelView.applyConfig(cfg);
            setPreviewTipsEnabled(true);
            syncBeautyPanelLayout(activity);
            int ht = beautyPanelView.getCurrentPanelHeightPx();
            applyBeautyPanelBottomInset(ht);
            String panelMode = options != null ? options.getString("mode") : "camera";
            syncMediaBackButton(activity, panelMode);
            fireBeautyPanelEvent("panelHeight", mapOf("height", ht));
            Log.i(TAG, "showBeautyPanel height=" + ht + " mode=" + (options != null ? options.getString("mode") : "camera"));
            if (callback != null) {
                JSONObject data = new JSONObject();
                data.put("height", ht);
                callback.invoke(success(data));
            }
        } catch (Exception e) {
            Log.e(TAG, "showBeautyPanel", e);
            if (callback != null) {
                callback.invoke(fail(e.getMessage()));
            }
        }
    }

    @UniJSMethod(uiThread = true)
    public void hideBeautyPanel(UniJSCallback callback) {
        try {
            dismissBeautyPanel();
            hideMediaBackButton();
            if (previewChromeView != null) {
                previewChromeView.setBottomChromeInset(0, true);
                previewChromeView.setCompareButtonHidden(false);
            }
            if (callback != null) {
                callback.invoke(success(0));
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.invoke(fail(e.getMessage()));
            }
        }
    }

    /** 原生确认框：美颜面板 Popup 会盖住 uni.showModal */
    @UniJSMethod(uiThread = true)
    public void showConfirm(JSONObject options, UniJSCallback callback) {
        Activity activity = resolveHostActivity();
        if (activity == null) {
            if (callback != null) {
                callback.invoke(fail("activity null"));
            }
            return;
        }
        String title = options != null ? options.getString("title") : null;
        String content = options != null ? options.getString("content") : null;
        String confirmText = options != null ? options.getString("confirmText") : null;
        String cancelText = options != null ? options.getString("cancelText") : null;
        if (title == null || title.isEmpty()) {
            title = "提示";
        }
        if (content == null) {
            content = "";
        }
        if (confirmText == null || confirmText.isEmpty()) {
            confirmText = "确定";
        }
        if (cancelText == null || cancelText.isEmpty()) {
            cancelText = "取消";
        }
        final UniJSCallback cb = callback;
        // keepAlive pending
        if (cb != null) {
            JSONObject pending = new JSONObject();
            pending.put("pending", 1);
            try {
                cb.invokeAndKeepAlive(success(pending));
            } catch (Throwable t) {
                Log.w(TAG, "showConfirm pending", t);
            }
        }
        try {
            new android.app.AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(content)
                    .setCancelable(true)
                    .setPositiveButton(confirmText, (d, w) -> {
                        if (cb != null) {
                            JSONObject data = new JSONObject();
                            data.put("confirm", 1);
                            cb.invoke(success(data));
                        }
                    })
                    .setNegativeButton(cancelText, (d, w) -> {
                        if (cb != null) {
                            JSONObject data = new JSONObject();
                            data.put("confirm", 0);
                            cb.invoke(success(data));
                        }
                    })
                    .setOnCancelListener(d -> {
                        if (cb != null) {
                            JSONObject data = new JSONObject();
                            data.put("confirm", 0);
                            cb.invoke(success(data));
                        }
                    })
                    .show();
        } catch (Exception e) {
            if (cb != null) {
                cb.invoke(fail(e.getMessage()));
            }
        }
    }

    @UniJSMethod(uiThread = true)
    public void updateBeautyPanelValues(JSONObject options, UniJSCallback callback) {
        try {
            if (beautyPanelView != null && options != null) {
                JSONObject values = options.getJSONObject("values");
                if (values != null) {
                    beautyPanelView.updateValues(values);
                }
                if (options.containsKey("filterId")) {
                    beautyPanelView.setSelectedFilterId(options.getString("filterId"));
                }
                if (options.containsKey("whiteningMode")) {
                    beautyPanelView.setWhiteningMode(options.getString("whiteningMode"));
                }
            }
            if (callback != null) {
                callback.invoke(success(0));
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.invoke(fail(e.getMessage()));
            }
        }
    }

    @UniJSMethod(uiThread = true)
    public void setBeautyPanelMode(JSONObject options, UniJSCallback callback) {
        try {
            String mode = options != null ? options.getString("mode") : "camera";
            if (mode == null || mode.isEmpty()) {
                mode = "camera";
            }
            if (beautyPanelView != null) {
                beautyPanelView.setMode(mode);
                Activity activity = resolveHostActivity();
                if (activity != null) {
                    syncBeautyPanelLayout(activity);
                }
                applyBeautyPanelBottomInset(beautyPanelView.getCurrentPanelHeightPx());
            }
            Activity act = resolveHostActivity();
            syncMediaBackButton(act, mode);
            if (callback != null) {
                JSONObject data = new JSONObject();
                data.put("mode", mode);
                callback.invoke(success(data));
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.invoke(fail(e.getMessage()));
            }
        }
    }

    private void ensureBeautyPanel(Activity activity) {
        if (beautyPanelView == null) {
            beautyPanelView = new FuBeautyPanelView(activity);
            beautyPanelView.setListener(new FuBeautyPanelView.Listener() {
                @Override
                public void onPanelHeightChanged(int heightPx) {
                    Activity act = resolveHostActivity();
                    if (act != null) {
                        syncBeautyPanelLayout(act);
                    }
                    applyBeautyPanelBottomInset(heightPx);
                    fireBeautyPanelEvent("panelHeight", mapOf("height", heightPx));
                }

                @Override
                public void onSelectTab(String tabId, boolean expanded) {
                    fireBeautyPanelEvent("tab", mapOf("tab", tabId, "expanded", expanded));
                }

                @Override
                public void onSelectEffect(String key) {
                    fireBeautyPanelEvent("selectEffect", mapOf("key", key));
                }

                @Override
                public void onSliderChanged(String key, double sdkValue) {
                    applyBeautyPanelSdkParam(key, sdkValue);
                    fireBeautyPanelEvent("slider", mapOf("key", key, "value", sdkValue));
                }

                @Override
                public void onSelectFilter(String filterId, String filterKey) {
                    applyBeautyPanelFilter(filterKey);
                    fireBeautyPanelEvent("filter", mapOf("id", filterId, "key", filterKey));
                }

                @Override
                public void onWhiteningMode(String mode) {
                    final double skinseg = "skin".equals(mode) ? 1.0 : 0.0;
                    // Demo：enableSkinSegmentation + 同一 color_level；对齐 iOS 写参并回写美白强度即时生效
                    NamaGlExecutor.runExclusive(() -> {
                        int handle = resolvePanelBeautyHandle();
                        if (handle <= 0) {
                            return;
                        }
                        BeautyParamApplier.applySpecialAlgoParam(handle, "enable_skinseg", skinseg);
                        double color = -1;
                        if (beautyPanelView != null) {
                            color = beautyPanelView.peekSdkParamValue("color_level_mode2");
                            if (color < 0) {
                                color = beautyPanelView.peekSdkParamValue("color_level");
                            }
                        }
                        if (color < 0) {
                            color = 0.4;
                        }
                        BeautyParamApplier.setDouble(handle, "color_level", color);
                    });
                    refreshPausedVideoBeautyIfNeeded();
                    if (overlayCameraView != null) {
                        overlayCameraView.requestRender();
                    }
                    fireBeautyPanelEvent("whiteningMode", mapOf("mode", mode));
                }

                @Override
                public void onRecoverTab(String tabId) {
                    // 对齐 iOS：原生确认 + 原生写参；JS 仅在 confirmed=1 时同步状态
                    final String tab = tabId == null || tabId.isEmpty() ? "skin" : tabId;
                    final String tabLabel = "shape".equals(tab) ? "美型" : "美肤";
                    Activity activity = resolveHostActivity();
                    if (activity == null) {
                        fireBeautyPanelEvent("recover", mapOf("tab", tab));
                        return;
                    }
                    try {
                        new android.app.AlertDialog.Builder(activity)
                                .setTitle("恢复默认")
                                .setMessage("确定将当前「" + tabLabel + "」参数恢复为默认值？")
                                .setCancelable(true)
                                .setPositiveButton("恢复", (d, w) -> {
                                    if (beautyPanelView != null) {
                                        beautyPanelView.recoverTabDefaults(tab);
                                    }
                                    fireBeautyPanelEvent("recover", mapOf("tab", tab, "confirmed", 1));
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    } catch (Throwable t) {
                        Log.w(TAG, "onRecoverTab dialog", t);
                        fireBeautyPanelEvent("recover", mapOf("tab", tab));
                    }
                }

                @Override
                public void onCompareStart() {
                    BeautyCameraGLView.setBeautyEnabledGlobal(false);
                    BeautyVideoGLView.setBeautyEnabledGlobal(false);
                    if (overlayCameraView != null) {
                        overlayCameraView.setBeautyEnabled(false);
                    }
                    if (overlayVideoView != null) {
                        overlayVideoView.setBeautyEnabled(false);
                    }
                    fireBeautyPanelEvent("compareStart", null);
                }

                @Override
                public void onCompareEnd() {
                    BeautyCameraGLView.setBeautyEnabledGlobal(true);
                    BeautyVideoGLView.setBeautyEnabledGlobal(true);
                    if (overlayCameraView != null) {
                        overlayCameraView.setBeautyEnabled(true);
                    }
                    if (overlayVideoView != null) {
                        overlayVideoView.setBeautyEnabled(true);
                    }
                    fireBeautyPanelEvent("compareEnd", null);
                }

                @Override
                public void onSave() {
                    fireBeautyPanelEvent("save", null);
                }
            });
        }
        // 相机页：挂到 PreviewChrome 内，拍摄钮与面板同窗、空白可穿透
        if (previewChromeView != null && previewChromePopup != null && previewChromePopup.isShowing()) {
            if (beautyPanelPopup != null && beautyPanelPopup.isShowing()) {
                try {
                    beautyPanelPopup.dismiss();
                } catch (Throwable ignored) {
                }
            }
            if (beautyPanelView.getParent() != previewChromeView) {
                if (beautyPanelView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) beautyPanelView.getParent()).removeView(beautyPanelView);
                }
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                previewChromeView.addView(beautyPanelView, lp);
            }
            previewChromeView.attachBeautyPanel(beautyPanelView);
            return;
        }
        // 媒体页：独立 Popup 贴底
        if (beautyPanelView.getParent() instanceof ViewGroup
                && beautyPanelView.getParent() != null
                && !(beautyPanelView.getParent() instanceof PopupWindow)) {
            // 若曾挂在 chrome 上，先拆下
            ViewGroup parent = (ViewGroup) beautyPanelView.getParent();
            if (parent == previewChromeView) {
                parent.removeView(beautyPanelView);
            }
        }
        if (beautyPanelPopup == null) {
            beautyPanelPopup = new PopupWindow(beautyPanelView, 1, 1, false);
            beautyPanelPopup.setTouchable(true);
            beautyPanelPopup.setFocusable(false);
            beautyPanelPopup.setOutsideTouchable(false);
            beautyPanelPopup.setClippingEnabled(false);
            beautyPanelPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            if (Build.VERSION.SDK_INT >= 21) {
                beautyPanelPopup.setElevation(140f);
            }
            beautyPanelPopup.setTouchInterceptor((v, event) -> {
                if (beautyPanelView == null) {
                    return false;
                }
                return !beautyPanelView.hitInteractive(event.getX(), event.getY());
            });
        }
        if (beautyPanelPopup.getContentView() != beautyPanelView) {
            if (beautyPanelView.getParent() instanceof ViewGroup) {
                ((ViewGroup) beautyPanelView.getParent()).removeView(beautyPanelView);
            }
            beautyPanelPopup.setContentView(beautyPanelView);
        }
    }

    private void syncBeautyPanelLayout(Activity activity) {
        if (beautyPanelView == null || activity == null) {
            return;
        }
        // 挂在 chrome 内：随 chrome 全屏
        if (beautyPanelView.getParent() == previewChromeView) {
            beautyPanelView.setVisibility(View.VISIBLE);
            beautyPanelView.requestLayout();
            return;
        }
        if (mediaBackBtn != null && mediaBackBtn.getVisibility() == View.VISIBLE) {
            layoutMediaBackButton(activity);
        }
        if (beautyPanelPopup == null) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        int w = decor.getWidth();
        if (w <= 0) {
            w = activity.getResources().getDisplayMetrics().widthPixels;
        }
        int h = beautyPanelView.getPreferredPopupHeightPx();
        beautyPanelPopup.setWidth(w);
        beautyPanelPopup.setHeight(h);
        if (beautyPanelPopup.isShowing()) {
            beautyPanelPopup.update(0, 0, w, h, true);
        } else {
            beautyPanelPopup.showAtLocation(decor, Gravity.BOTTOM | Gravity.START, 0, 0);
        }
        beautyPanelView.requestLayout();
    }

    private void applyBeautyPanelBottomInset(int panelHeightPx) {
        if (previewChromeView != null) {
            int inset = Math.max(0, panelHeightPx - dpToPx(8));
            previewChromeView.setBottomChromeInset(inset, true);
            previewChromeView.setCompareButtonHidden(true);
        }
    }

    private int dpToPx(int dp) {
        Activity activity = resolveHostActivity();
        float density = activity != null
                ? activity.getResources().getDisplayMetrics().density
                : 3f;
        return Math.round(dp * density);
    }

    private void syncMediaBackButton(Activity activity, String mode) {
        if (activity == null || mode == null) {
            return;
        }
        boolean media = "image".equals(mode) || "video".equals(mode);
        if (media) {
            ensureMediaBackButton(activity);
        } else {
            hideMediaBackButton();
        }
    }

    private void ensureMediaBackButton(Activity activity) {
        if (mediaBackBtn == null) {
            mediaBackBtn = new FrameLayout(activity);
            mediaBackBtn.setBackground(null);
            mediaBackBtn.setClickable(true);
            ImageView icon = new ImageView(activity);
            Bitmap bmp = PreviewChromeView.loadAssetBitmap(activity, "back.png");
            if (bmp != null) {
                icon.setImageBitmap(bmp);
            }
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int pad = dpToPx(8);
            icon.setPadding(pad, pad, pad, pad);
            mediaBackBtn.addView(icon, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            mediaBackBtn.setOnClickListener(v -> fireBeautyPanelEvent("back", null));
        }
        int topSafe = 0;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                android.view.WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    topSafe = insets.getSystemWindowInsetTop();
                }
            }
        } catch (Throwable ignored) {
        }
        if (topSafe < dpToPx(20)) {
            topSafe = dpToPx(44);
        }
        int size = dpToPx(44);
        int left = dpToPx(10);
        int top = topSafe + dpToPx(8);

        if (mediaBackPopup == null || !mediaBackPopup.isShowing()) {
            int[] loc = new int[2];
            mediaBackPopup = new PopupWindow(mediaBackBtn, size, size, false);
            mediaBackPopup.setClippingEnabled(false);
            mediaBackPopup.setFocusable(false);
            mediaBackPopup.setOutsideTouchable(false);
            try {
                mediaBackPopup.showAtLocation(activity.getWindow().getDecorView(),
                        Gravity.TOP | Gravity.START, left, top);
            } catch (Throwable t) {
                Log.w(TAG, "ensureMediaBackButton showAtLocation", t);
            }
        } else {
            try {
                mediaBackPopup.update(left, top, size, size);
            } catch (Throwable ignored) {
            }
        }
        mediaBackBtn.setVisibility(View.VISIBLE);
    }

    private void layoutMediaBackButton(Activity activity) {
        if (mediaBackBtn == null) {
            return;
        }
        int topSafe = 0;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                android.view.WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    topSafe = insets.getSystemWindowInsetTop();
                }
            }
        } catch (Throwable ignored) {
        }
        if (topSafe < dpToPx(20)) {
            topSafe = dpToPx(44);
        }
        int size = dpToPx(44);
        int left = dpToPx(10);
        int top = topSafe + dpToPx(8);
        if (mediaBackPopup != null && mediaBackPopup.isShowing()) {
            try {
                mediaBackPopup.update(left, top, size, size);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hideMediaBackButton() {
        if (mediaBackPopup != null) {
            try {
                mediaBackPopup.dismiss();
            } catch (Throwable ignored) {
            }
            mediaBackPopup = null;
        }
        if (mediaBackBtn != null) {
            mediaBackBtn.setVisibility(View.GONE);
            if (mediaBackBtn.getParent() instanceof ViewGroup) {
                ((ViewGroup) mediaBackBtn.getParent()).removeView(mediaBackBtn);
            }
        }
    }

    private static void dismissBeautyPanel() {
        hideMediaBackButton();
        try {
            if (previewChromeView != null) {
                previewChromeView.clearBeautyPanel();
            }
            if (beautyPanelView != null && beautyPanelView.getParent() instanceof ViewGroup) {
                ((ViewGroup) beautyPanelView.getParent()).removeView(beautyPanelView);
            }
            if (beautyPanelPopup != null && beautyPanelPopup.isShowing()) {
                beautyPanelPopup.dismiss();
            }
        } catch (Throwable ignored) {
        }
        beautyPanelView = null;
        beautyPanelPopup = null;
    }

    private void applyBeautyPanelSdkParam(String key, double value) {
        if (key == null || key.isEmpty()) {
            return;
        }
        final double appliedValue = FuBeautyPerfGate.clampValue(key, value);
        int handle = resolvePanelBeautyHandle();
        if (handle <= 0) {
            return;
        }
        boolean special = "body_blur_level".equals(key)
                || "delspot_level".equals(key)
                || "facial_plump".equals(key)
                || "intensity_eye_pupil".equals(key)
                || "enable_skinseg".equals(key);
        // 对齐 iOS：在相机 GL 线程写参（亮眼等须在 fuRender 前落地）
        Runnable apply = () -> {
            try {
                if (special) {
                    BeautyParamApplier.applySpecialAlgoParam(handle, key, appliedValue);
                } else {
                    BeautyParamApplier.setDouble(handle, key, appliedValue);
                }
            } catch (Throwable t) {
                Log.w(TAG, "applyBeautyPanelSdkParam " + key, t);
            }
        };
        if (overlayCameraView != null) {
            runOnNamaGl(apply);
        } else {
            NamaGlExecutor.runExclusive(apply);
        }
        refreshPausedVideoBeautyIfNeeded();
        if (overlayCameraView != null) {
            overlayCameraView.requestRender();
        }
    }

    private int resolvePanelBeautyHandle() {
        // 导入视频叠层：优先 media handle，避免写到相机道具而预览仍用 media
        if (overlayVideoView != null || overlayVideoHost != null) {
            int media = NamaSdkManager.getMediaBeautyHandle() > 0
                    ? NamaSdkManager.getMediaBeautyHandle() : FuBeautyHandle.mediaHandle;
            if (media > 0) {
                return media;
            }
        }
        int handle = NamaSdkManager.getCameraBeautyHandle() > 0
                ? NamaSdkManager.getCameraBeautyHandle() : FuBeautyHandle.cameraHandle;
        if (handle <= 0) {
            handle = NamaSdkManager.getMediaBeautyHandle() > 0
                    ? NamaSdkManager.getMediaBeautyHandle() : FuBeautyHandle.mediaHandle;
        }
        return handle;
    }

    /** 对齐 iOS：视频暂停时调参/切滤镜强制重绘当前帧 */
    private void refreshPausedVideoBeautyIfNeeded() {
        BeautyVideoGLView video = overlayVideoView;
        if (video == null || video.isPlaying()) {
            return;
        }
        try {
            video.redrawBeautyFrame();
        } catch (Throwable t) {
            Log.w(TAG, "refreshPausedVideoBeautyIfNeeded", t);
        }
    }

    private double peekPanelWhiteningValue() {
        if (beautyPanelView == null) {
            return -1;
        }
        double v = beautyPanelView.peekParamValue("color_level_mode2");
        if (v < 0) {
            v = beautyPanelView.peekParamValue("color_level");
        }
        return v;
    }

    /** 非关键写参：异步投递到相机 GL（可丢）。 */
    private void runOnNamaGl(Runnable action) {
        NamaGlExecutor.runAsync(overlayCameraView, action);
    }

    /**
     * 特殊算法写参：同步等待 GL 队列执行，与 DualInput 共用全局 Nama 锁。
     * 对齐 iOS performWithSharedGLLock；禁止超时后无锁裸写（会导致 get=set 无画面效果）。
     */
    private void runOnNamaGlSync(Runnable action) {
        NamaGlExecutor.runSync(overlayCameraView, action);
    }

    private void applyBeautyPanelFilter(String filterKey) {
        if (filterKey == null || filterKey.isEmpty()) {
            return;
        }
        final String key = filterKey;
        // 先同步写滤镜名/强度，再重绘暂停帧，避免 requestRender 抢在 setParam 之前
        NamaGlExecutor.runExclusive(() -> {
            int handle = resolvePanelBeautyHandle();
            if (handle <= 0) {
                return;
            }
            try {
                BeautyParamApplier.setString(handle, "filter_name", key);
                double level = -1;
                if (beautyPanelView != null) {
                    String fid = beautyPanelView.getSelectedFilterId();
                    level = beautyPanelView.peekFilterSdkLevel(fid);
                }
        if (level < 0) {
                    // 未单独调过：仅切 filter_name，不写 filter_level（对齐 iOS，避免用全局强度覆盖）
                    if ("origin".equalsIgnoreCase(key) || "filter_origin".equalsIgnoreCase(key)) {
                        BeautyParamApplier.setDouble(handle, "filter_level", 0);
                    }
                } else {
                    if ("origin".equalsIgnoreCase(key) || "filter_origin".equalsIgnoreCase(key)) {
                        level = 0;
                    }
                    BeautyParamApplier.setDouble(handle, "filter_level", level);
                }
            } catch (Throwable t) {
                Log.w(TAG, "applyBeautyPanelFilter", t);
            }
        });
        refreshPausedVideoBeautyIfNeeded();
        if (overlayCameraView != null) {
            overlayCameraView.requestRender();
        }
    }

    private void fireBeautyPanelEvent(String action, java.util.Map<String, Object> extra) {
        try {
            Object instance = resolveUniSDKInstance();
            if (instance == null) {
                return;
            }
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("action", action);
            if (extra != null) {
                payload.putAll(extra);
            }
            java.lang.reflect.Method m = instance.getClass()
                    .getMethod("fireGlobalEventCallback", String.class, java.util.Map.class);
            m.invoke(instance, "namaBeautyPanel", payload);
        } catch (Throwable t) {
            Log.w(TAG, "fireBeautyPanelEvent " + action, t);
        }
    }

    private void fireVideoEvent(String action, java.util.Map<String, Object> extra) {
        try {
            Object instance = resolveUniSDKInstance();
            if (instance == null) {
                return;
            }
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("action", action);
            if (extra != null) {
                payload.putAll(extra);
            }
            java.lang.reflect.Method m = instance.getClass()
                    .getMethod("fireGlobalEventCallback", String.class, java.util.Map.class);
            m.invoke(instance, "namaVideo", payload);
        } catch (Throwable t) {
            Log.w(TAG, "fireVideoEvent " + action, t);
        }
    }

    private static java.util.Map<String, Object> mapOf(Object... kv) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        if (kv == null) {
            return m;
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private void ensurePreviewChrome(Activity activity) {
        if (previewChromeView == null) {
            previewChromeView = new PreviewChromeView(activity);
            previewChromeView.setListener(new PreviewChromeView.Listener() {
                @Override
                public void onCaptureTouchDown() {
                    firePreviewChromeEvent("captureDown", null);
                }

                @Override
                public void onCaptureLongPress() {
                    firePreviewChromeEvent("captureLongPress", null);
                }

                @Override
                public void onCaptureTouchUp() {
                    java.util.Map<String, Object> extra = new java.util.HashMap<>();
                    extra.put("longPress", previewChromeView != null && previewChromeView.wasLongPress());
                    firePreviewChromeEvent("captureUp", extra);
                }

                @Override
                public void onCompareStart() {
                    firePreviewChromeEvent("compareStart", null);
                }

                @Override
                public void onCompareEnd() {
                    firePreviewChromeEvent("compareEnd", null);
                }

                @Override
                public void onHome() {
                    firePreviewChromeEvent("home", null);
                }

                @Override
                public void onSwitchCamera() {
                    firePreviewChromeEvent("switchCamera", null);
                }

                @Override
                public void onToggleDualInput(boolean dual) {
                    if (overlayCameraView != null) {
                        overlayCameraView.setDualInputEnabled(dual);
                    }
                    java.util.Map<String, Object> extra = new java.util.HashMap<>();
                    extra.put("dual", dual);
                    firePreviewChromeEvent("dualInput", extra);
                }

                @Override
                public void onSelectResolution(String id) {
                    java.util.Map<String, Object> extra = new java.util.HashMap<>();
                    extra.put("id", id != null ? id : "");
                    firePreviewChromeEvent("resolution", extra);
                }

                @Override
                public void onImportMedia() {
                    firePreviewChromeEvent("importMedia", null);
                }

                @Override
                public void onDebugVisibleChanged(boolean visible) {
                    java.util.Map<String, Object> extra = new java.util.HashMap<>();
                    extra.put("visible", visible);
                    firePreviewChromeEvent("debugVisible", extra);
                }
            });
            FocusHudView focus = previewChromeView.focusHud();
            focus.setOnExposureChangeListener((value, finalizeLock) -> {
                if (overlayCameraView != null) {
                    overlayCameraView.setExposureCompensation(value, finalizeLock);
                }
            });
            focus.setOnTapListener((localPxX, localPxY) -> {
                if (overlayCameraView == null || lastCssW <= 0 || lastCssH <= 0) {
                    return;
                }
                Activity act = resolveHostActivity();
                if (act == null) {
                    return;
                }
                float density = Math.max(0.5f, act.getResources().getDisplayMetrics().density);
                float localCssX = localPxX / density;
                float localCssY = localPxY / density;
                int exposure = previewChromeView.getFocusExposure();
                applyFocusAtCss(act, localCssX, localCssY, lastCssX, lastCssY, lastCssW, lastCssH, exposure);
            });
            focus.setOnAutoHideListener(NamaModule::dismissFocusHud);
        }
        if (previewChromePopup == null) {
            previewChromePopup = new PopupWindow(previewChromeView, 1, 1, false);
            previewChromePopup.setTouchable(true);
            previewChromePopup.setFocusable(false);
            previewChromePopup.setOutsideTouchable(false);
            previewChromePopup.setClippingEnabled(false);
            previewChromePopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                previewChromePopup.setElevation(120f);
            }
            previewChromePopup.setTouchInterceptor((v, event) -> {
                if (previewChromeView == null) {
                    return false;
                }
                // 按钮 / 曝光条：交给子 View（同层 order 已保证按钮在对焦上）
                if (previewChromeView.hitInteractive(event.getX(), event.getY())) {
                    return false;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    Activity act = resolveHostActivity();
                    if (act != null && lastCssW > 0 && lastCssH > 0) {
                        float density = Math.max(0.5f, act.getResources().getDisplayMetrics().density);
                        float localCssX = event.getX() / density;
                        float localCssY = event.getY() / density;
                        int exposure = previewChromeView.getFocusExposure();
                        applyFocusAtCss(act, localCssX, localCssY,
                                lastCssX, lastCssY, lastCssW, lastCssH, exposure);
                    }
                    return true;
                }
                return event.getActionMasked() != MotionEvent.ACTION_UP
                        && event.getActionMasked() != MotionEvent.ACTION_CANCEL;
            });
        }
        if (previewChromePopup.getContentView() != previewChromeView) {
            previewChromePopup.setContentView(previewChromeView);
        }
    }

    private void syncPreviewChromeLayout(int pxX, int pxY, int pxW, int pxH) {
        if (previewChromeView == null || previewChromePopup == null || pxW <= 0 || pxH <= 0) {
            return;
        }
        Activity activity = resolveHostActivity();
        if (activity == null) {
            return;
        }
        previewChromePopup.setWidth(pxW);
        previewChromePopup.setHeight(pxH);
        if (previewChromePopup.isShowing()) {
            previewChromePopup.update(pxX, pxY, pxW, pxH, true);
        } else {
            View decor = activity.getWindow().getDecorView();
            previewChromePopup.showAtLocation(decor, Gravity.NO_GRAVITY, pxX, pxY);
        }
    }

    private static void dismissPreviewChrome() {
        try {
            if (previewChromePopup != null && previewChromePopup.isShowing()) {
                previewChromePopup.dismiss();
            }
        } catch (Throwable ignored) {
        }
    }

    private void firePreviewChromeEvent(String action, java.util.Map<String, Object> extra) {
        try {
            Object instance = resolveUniSDKInstance();
            if (instance == null) {
                return;
            }
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("action", action);
            if (extra != null) {
                payload.putAll(extra);
            }
            java.lang.reflect.Method m = instance.getClass()
                    .getMethod("fireGlobalEventCallback", String.class, java.util.Map.class);
            m.invoke(instance, "namaPreviewChrome", payload);
            Log.i(TAG, "firePreviewChromeEvent " + action);
        } catch (Throwable t) {
            Log.w(TAG, "firePreviewChromeEvent " + action, t);
        }
    }

    private void ensureFocusHud(Activity activity) {
        if (focusHudView == null) {
            focusHudView = new FocusHudView(activity);
            focusHudView.setOnExposureChangeListener((value, finalizeLock) -> {
                if (overlayCameraView != null) {
                    overlayCameraView.setExposureCompensation(value, finalizeLock);
                }
            });
            focusHudView.setOnTapListener((localPxX, localPxY) -> {
                if (overlayCameraView == null || lastCssW <= 0 || lastCssH <= 0) {
                    return;
                }
                Activity act = resolveHostActivity();
                if (act == null) {
                    return;
                }
                float density = Math.max(0.5f, act.getResources().getDisplayMetrics().density);
                float localCssX = localPxX / density;
                float localCssY = localPxY / density;
                int exposure = focusHudView.getExposureProgress();
                applyFocusAtCss(act, localCssX, localCssY, lastCssX, lastCssY, lastCssW, lastCssH, exposure);
            });
            focusHudView.setOnAutoHideListener(NamaModule::dismissFocusHud);
        }
        if (focusHudPopup == null) {
            focusHudPopup = new PopupWindow(focusHudView, 1, 1, false);
            // elevation 在下方统一设置（低于 PreviewChrome）
            // elevation 在下方统一设置（低于 PreviewChrome）
            focusHudPopup.setTouchable(true);
            focusHudPopup.setFocusable(false);
            focusHudPopup.setOutsideTouchable(false);
            focusHudPopup.setClippingEnabled(false);
            focusHudPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                focusHudPopup.setElevation(16f);
            }
            focusHudPopup.setTouchInterceptor((v, event) -> {
                if (focusHudView == null) {
                    return false;
                }
                // 曝光条：交给 FocusHudView；其它区域：更新对焦位置（避免全屏 popup 吞掉二次点击）
                if (focusHudView.hitExposureRail(event.getX(), event.getY())) {
                    return false;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    focusHudView.onTouchEvent(event);
                    return true;
                }
                return event.getActionMasked() != MotionEvent.ACTION_UP
                        && event.getActionMasked() != MotionEvent.ACTION_CANCEL;
            });
        }
        if (focusHudPopup.getContentView() != focusHudView) {
            focusHudPopup.setContentView(focusHudView);
        }
    }

    private static void dismissFocusHud() {
        try {
            if (previewChromeView != null) {
                previewChromeView.hideFocusHud();
            }
            if (focusHudView != null) {
                focusHudView.hideAll();
            }
            if (focusHudPopup != null && focusHudPopup.isShowing()) {
                focusHudPopup.dismiss();
            }
        } catch (Exception ignored) {
        }
    }

    @UniJSMethod(uiThread = true)
    public void setPreviewResolution(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        if (options == null) {
            callback.invoke(fail("options null"));
            return;
        }
        try {
            int width = options.getIntValue("width");
            int height = options.getIntValue("height");
            if (width <= 0 || height <= 0) {
                callback.invoke(fail("width/height 无效"));
                return;
            }
            BeautyCameraGLView.setTargetPreviewSize(width, height);
            if (overlayCameraView != null) {
                overlayCameraView.restartPreview();
            }
            callback.invoke(success(0));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /** 进相机页重置为 720，不跨页记忆 */
    @UniJSMethod(uiThread = false)
    public void resetPreviewResolution(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            BeautyCameraGLView.resetTargetPreviewSizeToDefault();
            callback.invoke(success(0));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void capturePhoto(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            Activity activity = resolveHostActivity();
            if (overlayCameraView == null || activity == null) {
                callback.invoke(fail("相机未挂载"));
                return;
            }
            overlayCameraView.capturePhoto(activity, new BeautyCameraGLView.CaptureCallback() {
                @Override
                public void onSuccess(String path) {
                    JSONObject data = new JSONObject();
                    data.put("path", path);
                    callback.invoke(success(data));
                }

                @Override
                public void onError(String message) {
                    callback.invoke(fail(message));
                }
            });
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void startVideoRecord(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            if (overlayCameraView == null) {
                callback.invoke(fail("相机未挂载"));
                return;
            }
            overlayCameraView.startVideoRecord();
            callback.invoke(success(0));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void stopVideoRecord(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            Activity activity = resolveHostActivity();
            if (overlayCameraView == null || activity == null) {
                callback.invoke(fail("相机未挂载"));
                return;
            }
            // recorder.stop 已异步：此处立即返回，勿在 UI 线程同步 drain/写相册
            overlayCameraView.stopVideoRecord(activity, new BeautyCameraGLView.CaptureCallback() {
                @Override
                public void onSuccess(String path) {
                    JSONObject data = new JSONObject();
                    data.put("path", path);
                    callback.invoke(success(data));
                }

                @Override
                public void onError(String message) {
                    callback.invoke(fail(message));
                }
            });
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = false)
    public void resolveLocalMediaPath(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            if (options == null) {
                callback.invoke(fail("options null"));
                return;
            }
            String path = options.getString("path");
            String ext = options.getString("ext");
            Activity activity = resolveHostActivity();
            if (activity == null) {
                callback.invoke(fail("activity null"));
                return;
            }
            String local = MediaPathUtil.toLocalFilePath(activity, path, ext != null ? ext : ".jpg");
            JSONObject data = new JSONObject();
            data.put("path", local);
            callback.invoke(success(data));
        } catch (Exception e) {
            Log.e(TAG, "resolveLocalMediaPath", e);
            callback.invoke(fail(e.getMessage()));
        }
    }

    /**
     * 对齐 FULiveDemoDroid：ACTION_OPEN_DOCUMENT + image/* / video/*。
     * 系统文档选择器，不依赖 READ_MEDIA_*，避免 uni.chooseImage 空相册。
     */
    @UniJSMethod(uiThread = true)
    public void pickMediaFromAlbum(JSONObject options, UniJSCallback callback) {
        Activity activity = resolveHostActivity();
        if (activity == null) {
            if (callback != null) {
                callback.invoke(fail("activity null"));
            }
            return;
        }
        if (pickMediaCallback != null) {
            if (callback != null) {
                callback.invoke(fail("相册占用中，请返回后重试"));
            }
            return;
        }
        String type = options != null ? options.getString("type") : "image";
        boolean video = type != null && "video".equalsIgnoreCase(type);
        pickMediaExt = video ? ".mp4" : ".jpg";
        pickMediaCallback = callback;
        if (callback != null) {
            JSONObject pending = new JSONObject();
            pending.put("pending", 1);
            try {
                callback.invokeAndKeepAlive(success(pending));
            } catch (Throwable t) {
                Log.w(TAG, "invokeAndKeepAlive fallback", t);
            }
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(video ? "video/*" : "image/*");
            activity.startActivityForResult(intent, REQ_PICK_MEDIA);
            Log.i(TAG, "pickMediaFromAlbum OPEN_DOCUMENT type=" + (video ? "video" : "image"));
        } catch (Exception e) {
            Log.e(TAG, "pickMediaFromAlbum", e);
            UniJSCallback cb = pickMediaCallback;
            pickMediaCallback = null;
            if (cb != null) {
                cb.invoke(fail(e.getMessage()));
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_MEDIA) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        UniJSCallback cb = pickMediaCallback;
        pickMediaCallback = null;
        if (cb == null) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            cb.invoke(fail("用户取消选择"));
            return;
        }
        Uri uri = data.getData();
        Activity activity = resolveHostActivity();
        if (activity == null) {
            cb.invoke(fail("activity null"));
            return;
        }
        try {
            try {
                final int takeFlags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (takeFlags != 0) {
                    activity.getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (Throwable ignored) {
                // 临时读权限足够拷贝
            }
            String local = MediaPathUtil.toLocalFilePath(activity, uri.toString(), pickMediaExt);
            JSONObject out = new JSONObject();
            out.put("path", local);
            out.put("type", ".mp4".equals(pickMediaExt) ? "video" : "image");
            cb.invoke(success(out));
            Log.i(TAG, "pickMediaFromAlbum ok -> " + local);
        } catch (Exception e) {
            Log.e(TAG, "pickMediaFromAlbum result", e);
            cb.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = false)
    public void processImage(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            ensureInitialized();
            if (options == null) {
                callback.invoke(fail("options null"));
                return;
            }
            String path = options.getString("path");
            // 优先媒体 handle；未绑定时复用相机 handle（首页/相机已 init，只换输入源）
            int handle = NamaSdkManager.getMediaBeautyHandle() > 0
                    ? NamaSdkManager.getMediaBeautyHandle() : FuBeautyHandle.mediaHandle;
            if (handle <= 0) {
                handle = NamaSdkManager.getCameraBeautyHandle() > 0
                        ? NamaSdkManager.getCameraBeautyHandle() : FuBeautyHandle.cameraHandle;
            }
            if (handle <= 0) {
                callback.invoke(fail("请先 loadBundle"));
                return;
            }
            if (NamaSdkManager.getMediaBeautyHandle() <= 0 && handle > 0) {
                NamaSdkManager.setMediaBeautyHandle(handle);
            }
            Activity activity = resolveHostActivity();
            java.io.File cacheDir = null;
            if (activity != null) {
                cacheDir = activity.getCacheDir();
            }
            // 纯「相机→导入图片」：相机 soft-hide 后 Surface 仍有效，走相机 GL（历史已验证正常，对齐稳妥出图）
            // 视频预览后：避开相机 GL，改 offscreen，防止矩阵残留导致静图颠倒/回相机异常
            final BeautyCameraGLView cam = overlayCameraView;
            String outPath;
            boolean videoAlive = overlayVideoView != null || overlayVideoHost != null;
            boolean avoidCamGl = videoAlive || sAvoidCameraGlForImageAfterVideo;
            boolean camGlUsable = !avoidCamGl
                    && cam != null
                    && cam.getHolder() != null
                    && cam.getHolder().getSurface() != null
                    && cam.getHolder().getSurface().isValid();
            if (camGlUsable) {
                try {
                    outPath = ImageBeautyProcessor.processOnGlView(cam, activity, path, handle, cacheDir);
                } catch (Exception camGlErr) {
                    Log.w(TAG, "processImage camera GL failed, fallback offscreen", camGlErr);
                    outPath = ImageBeautyProcessor.process(activity, path, handle, cacheDir);
                }
            } else {
                if (avoidCamGl) {
                    Log.i(TAG, "processImage offscreen afterVideo=" + sAvoidCameraGlForImageAfterVideo
                            + " videoAlive=" + videoAlive);
                }
                outPath = ImageBeautyProcessor.process(activity, path, handle, cacheDir);
            }
            // 静图不在此处 deviceLost，否则下一帧无美颜且需整段重载
            JSONObject data = new JSONObject();
            data.put("path", outPath);
            callback.invoke(success(data));
        } catch (Exception e) {
            Log.e(TAG, "processImage", e);
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void showVideoPreview(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            ensureInitialized();
            if (options == null) {
                callback.invoke(fail("options null"));
                return;
            }
            String path = options.getString("path");
            int x = options.getIntValue("x");
            int y = options.getIntValue("y");
            int width = options.getIntValue("width");
            int height = options.getIntValue("height");
            if (path == null || path.isEmpty()) {
                callback.invoke(fail("path 不能为空"));
                return;
            }
            if (width <= 0 || height <= 0) {
                callback.invoke(fail("width/height 无效"));
                return;
            }
            // 同路径已挂载：只改预览框，避免面板展开时 destroy → deviceLost → 全量 reload
            if (overlayVideoView != null && overlayVideoHost != null
                    && lastVideoPath != null && lastVideoPath.equals(path)) {
                Activity act = resolveHostActivity();
                if (act == null) {
                    callback.invoke(fail("activity null"));
                    return;
                }
                float density = act.getResources().getDisplayMetrics().density;
                int pxX = cssToPhysical(density, x);
                int pxY = cssToPhysical(density, y);
                int pxW = cssToPhysical(density, width);
                int pxH = cssToPhysical(density, height);
                if (overlayVideoHost.getChildCount() > 0) {
                    View previewBox = overlayVideoHost.getChildAt(0);
                    ViewGroup.LayoutParams rawLp = previewBox.getLayoutParams();
                    if (rawLp instanceof FrameLayout.LayoutParams) {
                        FrameLayout.LayoutParams boxLp = (FrameLayout.LayoutParams) rawLp;
                        boxLp.width = pxW;
                        boxLp.height = pxH;
                        boxLp.leftMargin = pxX;
                        boxLp.topMargin = pxY;
                        previewBox.setLayoutParams(boxLp);
                    }
                }
                overlayVideoView.bindLayoutSize(pxW, pxH);
                lastCssX = x;
                lastCssY = y;
                lastCssW = width;
                lastCssH = height;
                bringVideoOverlayToFront();
                Log.i(TAG, "showVideoPreview resized css:" + width + "x" + height + "@" + x + "," + y);
                JSONObject reused = new JSONObject();
                reused.put("x", x);
                reused.put("y", y);
                reused.put("width", width);
                reused.put("height", height);
                reused.put("reused", true);
                reused.put("resized", true);
                callback.invoke(success(reused));
                return;
            }
            Activity activity = resolveHostActivity();
            if (activity == null) {
                callback.invoke(fail("activity null"));
                return;
            }
            final JSONObject opts = options;
            final UniJSCallback cb = callback;
            // 进视频：只 soft-hide 相机（停采集+挪出屏幕），不 onPause / 不 destroy / 不 deviceLost
            // 首页已 init，会话保持；禁止 pauseGlForHandoff，否则会污染共享 Nama / 回相机黑屏
            sCameraGlHandedOff = false;
            // 冷启动直进导入视频：无相机叠层则建驻留 GL，否则首帧无美颜
            ensureParkedNamaGlForMedia(activity);
            if (overlayCameraView != null) {
                overlayCameraView.enterVideoBeautyHosting();
            }
            destroyVideoPreviewInternal(true, () ->
                    softHideCameraOverlay(() -> mountVideoOverlay(activity, opts, cb)));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /**
     * 媒体视频美颜依赖相机 GL 跑 Nama。冷启动未开相机时建一个移出屏幕的驻留 GLSurfaceView。
     */
    private void ensureParkedNamaGlForMedia(Activity activity) {
        if (activity == null || overlayCameraView != null) {
            if (overlayCameraView != null) {
                overlayCameraView.armVideoStillMatrix();
                overlayCameraView.enterVideoBeautyHosting();
                wakeGlSurfaceView(overlayCameraView, activity);
            }
            return;
        }
        try {
            ViewGroup root = resolveOverlayRoot(activity);
            FrameLayout host = new FrameLayout(activity);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                host.setElevation(8f);
            }
            attachOverlayHostOnDecor(root, host);

            FrameLayout previewBox = new FrameLayout(activity);
            int parkedSide = DeviceQuirk.isHuaweiFamily() ? dpToPx(320) : dpToPx(128);
            FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(parkedSide, parkedSide);
            boxLp.leftMargin = 100000;
            boxLp.topMargin = 0;
            host.addView(previewBox, boxLp);

            BeautyCameraGLView view = new BeautyCameraGLView(activity);
            try {
                view.setZOrderOnTop(false);
                view.setZOrderMediaOverlay(false);
            } catch (Throwable ignored) {
            }
            previewBox.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            view.bindLayoutSize(parkedSide, parkedSide);
            overlayCameraView = view;
            overlayCameraHost = host;
            // 先进入视频宿主再 hide，避免华为 coverHidden 卡死 GL
            view.enterVideoBeautyHosting();
            view.hidePreview();
            host.setAlpha(0f);
            host.setEnabled(true);
            host.setVisibility(View.VISIBLE);
            view.setAlpha(0f);
            view.setVisibility(View.VISIBLE);
            wakeGlSurfaceView(view, activity);
            Log.i(TAG, "ensureParkedNamaGlForMedia created parked=" + parkedSide);
        } catch (Throwable t) {
            Log.w(TAG, "ensureParkedNamaGlForMedia", t);
        }
    }

    private void mountVideoOverlay(Activity activity, JSONObject options, UniJSCallback callback) {
        try {
            int beautyHandle = NamaSdkManager.getMediaBeautyHandle() > 0
                    ? NamaSdkManager.getMediaBeautyHandle() : FuBeautyHandle.mediaHandle;
            if (beautyHandle <= 0) {
                beautyHandle = NamaSdkManager.getCameraBeautyHandle() > 0
                        ? NamaSdkManager.getCameraBeautyHandle() : FuBeautyHandle.cameraHandle;
            }
            if (beautyHandle <= 0) {
                callback.invoke(fail("美颜 handle 无效，请先在首页/相机完成 init+loadBundle"));
                return;
            }
            if (NamaSdkManager.getMediaBeautyHandle() <= 0) {
                NamaSdkManager.setMediaBeautyHandle(beautyHandle);
            }
            String path = options.getString("path");
            int x = options.getIntValue("x");
            int y = options.getIntValue("y");
            int width = options.getIntValue("width");
            int height = options.getIntValue("height");
            float density = activity.getResources().getDisplayMetrics().density;
            int pxX = cssToPhysical(density, x);
            int pxY = cssToPhysical(density, y);
            int pxW = cssToPhysical(density, width);
            int pxH = cssToPhysical(density, height);

            ViewGroup root = resolveOverlayRoot(activity);
            FrameLayout host = new FrameLayout(activity);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                host.setElevation(48f);
            }
            attachOverlayHostOnDecor(root, host);

            FrameLayout previewBox = new FrameLayout(activity);
            FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(pxW, pxH);
            boxLp.leftMargin = pxX;
            boxLp.topMargin = pxY;
            host.addView(previewBox, boxLp);

            BeautyVideoGLView view = new BeautyVideoGLView(activity);
            previewBox.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            view.bindLayoutSize(pxW, pxH);
            wakeGlSurfaceView(view, activity);
            MediaFuSetup.ensureBeautyOn(beautyHandle);
            MediaFuSetup.enableAdvancedBeautyRuntime(beautyHandle);
            try {
                faceunity.fuSetFaceProcessorDetectMode(1);
            } catch (Throwable ignored) {
            }
            // 视频美颜矩阵必须在相机 GL 线程用 StillLike；此处禁止 UI 线程写 identity
            ensureParkedNamaGlForMedia(activity);
            if (overlayCameraView != null) {
                wakeGlSurfaceView(overlayCameraView, activity);
                overlayCameraView.enterVideoBeautyHosting();
                // 预热 GL 队列，避免首帧仍是原片
                try {
                    overlayCameraView.queueEvent(() -> {
                        try {
                            faceunity.fuOnCameraChange();
                            MediaFuSetup.applyStillLikeBufferMatrix();
                            faceunity.fuSetFaceProcessorDetectMode(1);
                        } catch (Throwable ignored) {
                        }
                    });
                    overlayCameraView.requestRender();
                } catch (Throwable ignored) {
                }
            }
            setPreviewTipsEnabled(true);

            ImageView playBtn = new ImageView(activity);
            Bitmap playBmp = PreviewChromeView.loadAssetBitmap(activity, "play.png");
            int playSize = dpToPx(85);
            FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(playSize, playSize);
            playLp.gravity = Gravity.CENTER;
            if (playBmp != null) {
                playBtn.setImageBitmap(playBmp);
                playBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
                playBtn.setBackgroundColor(Color.TRANSPARENT);
            } else {
                GradientDrawable playBg = new GradientDrawable();
                playBg.setColor(0xEBFFFFFF);
                playBg.setCornerRadius(playSize / 2f);
                playBtn.setBackground(playBg);
            }
            previewBox.addView(playBtn, playLp);
            playBtn.setOnClickListener(v -> {
                try {
                    playBtn.setVisibility(View.GONE);
                    view.play();
                    fireVideoEvent("playing", null);
                } catch (Throwable t) {
                    Log.w(TAG, "video playBtn", t);
                    try {
                        playBtn.setVisibility(View.VISIBLE);
                    } catch (Throwable ignored) {
                    }
                }
            });
            overlayVideoPlayBtn = playBtn;

            // 对齐 Demo：默认暂停；有解码帧即显示（勿等美颜完成，否则华为等一直 INVISIBLE 黑屏）
            view.setVisibility(View.VISIBLE);
            playBtn.setVisibility(View.GONE);
            view.setOnFirstFrameListener(() -> {
                try {
                    if (overlayVideoView == view) {
                        view.setVisibility(View.VISIBLE);
                        if (overlayVideoPlayBtn != null) {
                            overlayVideoPlayBtn.setVisibility(View.VISIBLE);
                        }
                        bringVideoOverlayToFront();
                        fireVideoEvent("paused", null);
                    }
                } catch (Throwable ignored) {
                }
            });
            view.setOnPlaybackEndedListener(() -> {
                try {
                    if (overlayVideoPlayBtn != null) {
                        overlayVideoPlayBtn.setVisibility(View.VISIBLE);
                    }
                    fireVideoEvent("ended", null);
                } catch (Throwable ignored) {
                }
            });
            view.loadVideo(path);
            view.prepareFirstFrame();

            overlayVideoView = view;
            overlayVideoHost = host;
            lastVideoPath = path;
            // 视频与相机共享 EGL：后续静图仍可走相机 GL；标记仅作兼容旧路径
            sAvoidCameraGlForImageAfterVideo = false;
            lastCssX = x;
            lastCssY = y;
            lastCssW = width;
            lastCssH = height;
            bringVideoOverlayToFront();
            // 再次确认相机卸顶，避免 ZOrderOnTop 冻帧盖住视频
            parkCameraOverlayHidden(true);

            JSONObject data = new JSONObject();
            data.put("x", x);
            data.put("y", y);
            data.put("width", width);
            data.put("height", height);
            callback.invoke(success(data));
        } catch (Exception e) {
            Log.e(TAG, "mountVideoOverlay", e);
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void pauseVideoPreview(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            if (overlayVideoView != null) {
                overlayVideoView.pause();
            }
            if (overlayVideoPlayBtn != null) {
                overlayVideoPlayBtn.setVisibility(View.VISIBLE);
            }
            fireVideoEvent("paused", null);
            callback.invoke(success(0));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /**
     * 锁屏/回桌面：只停视频层 + 视频 GL onPause。
     * 严禁 pause 驻留相机 GL：视频美颜/调参都走相机 GL 的 queueEvent，一 pause 就会卡死无美颜。
     */
    @UniJSMethod(uiThread = true)
    public void parkVideoForBackground(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            if (overlayVideoView != null) {
                overlayVideoView.onHostPause();
            }
            if (overlayVideoPlayBtn != null) {
                overlayVideoPlayBtn.setVisibility(View.VISIBLE);
            }
            fireVideoEvent("paused", null);
            callback.invoke(success(0));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /**
     * 回前台：先确保驻留相机 Nama GL 醒着，再重置视频为「首帧 + Play」。
     * 不 invalidate、不 exit hosting。
     */
    @UniJSMethod(uiThread = true)
    public void resetVideoToIdle(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            JSONObject data = new JSONObject();
            Activity activity = resolveHostActivity();
            if (overlayVideoView == null) {
                data.put("ok", 0);
                callback.invoke(success(data));
                return;
            }
            // 美颜宿主必须先醒，否则 idle 解一帧 / 之后点播放都会卡在 processVideoRgbaFrame
            if (overlayCameraView != null) {
                wakeGlSurfaceView(overlayCameraView, activity);
                overlayCameraView.enterVideoBeautyHosting();
                try {
                    overlayCameraView.queueEvent(() -> {
                        try {
                            faceunity.fuOnCameraChange();
                            MediaFuSetup.applyStillLikeBufferMatrix();
                            faceunity.fuSetFaceProcessorDetectMode(1);
                        } catch (Throwable ignored) {
                        }
                    });
                    overlayCameraView.requestRender();
                } catch (Throwable ignored) {
                }
            }
            boolean ok = overlayVideoView.onHostResumeToIdle();
            if (overlayVideoPlayBtn != null) {
                overlayVideoPlayBtn.setVisibility(View.VISIBLE);
            }
            bringVideoOverlayToFront();
            fireVideoEvent("paused", null);
            data.put("ok", ok ? 1 : 0);
            callback.invoke(success(data));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void resumeVideoPreview(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            bringVideoOverlayToFront();
            if (overlayVideoHost != null) {
                overlayVideoHost.setVisibility(View.VISIBLE);
            }
            if (overlayVideoPlayBtn != null) {
                overlayVideoPlayBtn.setVisibility(View.GONE);
            }
            if (overlayVideoView != null) {
                overlayVideoView.play();
            }
            fireVideoEvent("playing", null);
            callback.invoke(success(0));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void destroyVideoPreview(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            boolean keepSession = true;
            if (options != null && options.containsKey("keepSession")) {
                keepSession = options.getBooleanValue("keepSession");
            }
            final boolean keep = keepSession;
            destroyVideoPreviewInternal(keep, () -> {
                if (overlayCameraView != null) {
                    overlayCameraView.exitVideoBeautyHosting();
                    overlayCameraView.reapplyInputCameraMatrix();
                }
                JSONObject data = new JSONObject();
                // 视频显示层拆除不丢 Nama 会话
                data.put("resourcesLost", false);
                callback.invoke(success(data));
            });
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void destroyVideoPreview(UniJSCallback callback) {
        destroyVideoPreview(null, callback);
    }

    @UniJSMethod(uiThread = false)
    public void processVideo(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        View exportHud = null;
        try {
            ensureInitialized();
            if (options == null) {
                callback.invoke(fail("options null"));
                return;
            }
            String path = options.getString("path");
            int handle = NamaSdkManager.getMediaBeautyHandle() > 0
                    ? NamaSdkManager.getMediaBeautyHandle() : FuBeautyHandle.mediaHandle;
            if (handle <= 0) {
                callback.invoke(fail("请先 loadBundle(media)"));
                return;
            }
            Activity activity = resolveHostActivity();
            VideoBeautyProcessor.clearExportCancel();
            // Demo 风格：全屏导出 loading + 取消（对齐 iOS）
            exportHud = showExportLoadingHud(activity);
            final Object pauseLock = new Object();
            final boolean[] paused = {false};
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    if (overlayVideoPlayBtn != null) {
                        overlayVideoPlayBtn.setVisibility(View.GONE);
                    }
                    if (overlayVideoView != null) {
                        // 冻帧显示，禁止 stopAndRelease（会清屏/拆解码器抢会话）
                        overlayVideoView.beginExportFreeze();
                    }
                } finally {
                    synchronized (pauseLock) {
                        paused[0] = true;
                        pauseLock.notifyAll();
                    }
                }
            });
            synchronized (pauseLock) {
                long deadline = System.currentTimeMillis() + 2000;
                while (!paused[0] && System.currentTimeMillis() < deadline) {
                    try {
                        pauseLock.wait(100);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
            java.io.File cacheDir = activity != null ? activity.getCacheDir() : null;
            // Nama 挂在相机 GL：必须优先走相机上下文，勿用视频显示层 EGL（易只出一帧）
            final BeautyCameraGLView cameraGl = overlayCameraView;
            final BeautyVideoGLView videoGl = overlayVideoView;
            final View hudRef = exportHud;
            VideoBeautyProcessor.ProgressListener progress = ratio -> {
                if (!(hudRef instanceof FuExportProgressHud)) {
                    return;
                }
                final float r = ratio;
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        ((FuExportProgressHud) hudRef).setProgress(r);
                    } catch (Throwable ignored) {
                    }
                });
            };
            String outPath;
            if (cameraGl != null
                    && cameraGl.getHolder() != null
                    && cameraGl.getHolder().getSurface() != null
                    && cameraGl.getHolder().getSurface().isValid()) {
                cameraGl.prepareForVideoExport();
                outPath = VideoBeautyProcessor.processOnGlView(cameraGl, activity, path, handle, cacheDir, progress);
            } else if (videoGl != null) {
                outPath = VideoBeautyProcessor.processOnGlView(videoGl, activity, path, handle, cacheDir, progress);
            } else {
                outPath = VideoBeautyProcessor.process(activity, path, handle, cacheDir, progress);
            }
            if (hudRef instanceof FuExportProgressHud) {
                final FuExportProgressHud doneHud = (FuExportProgressHud) hudRef;
                new Handler(Looper.getMainLooper()).post(() -> doneHud.setProgress(1f));
            }
            JSONObject data = new JSONObject();
            data.put("path", outPath);
            callback.invoke(success(data));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "导出失败";
            if (VideoBeautyProcessor.isExportCancelled() || msg.contains("取消")) {
                Log.i(TAG, "processVideo cancelled");
                callback.invoke(fail("导出已取消"));
            } else {
                Log.e(TAG, "processVideo", e);
                callback.invoke(fail(msg));
            }
        } finally {
            VideoBeautyProcessor.clearExportCancel();
            final View hud = exportHud;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    if (overlayVideoView != null) {
                        overlayVideoView.endExportFreeze();
                        if (overlayVideoPlayBtn != null) {
                            overlayVideoPlayBtn.setVisibility(View.VISIBLE);
                        }
                    }
                } catch (Throwable ignored) {
                }
                dismissExportLoadingHud(hud);
            });
        }
    }

    private View showExportLoadingHud(Activity activity) {
        if (activity == null) {
            return null;
        }
        final View[] holder = new View[1];
        final Object lock = new Object();
        final boolean[] done = {false};
        activity.runOnUiThread(() -> {
            try {
                dismissExportLoadingHud(null);
                FuExportProgressHud root = new FuExportProgressHud(activity);
                root.setOnCancelListener(() -> {
                    Log.i(TAG, "export cancel tapped");
                    VideoBeautyProcessor.requestCancelExport();
                });
                PopupWindow popup = new PopupWindow(root,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        true);
                popup.setTouchable(true);
                popup.setFocusable(true);
                popup.setOutsideTouchable(false);
                popup.setClippingEnabled(false);
                popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // 必须高于美颜面板 Popup(140) / tip(200)，盖住全局
                    popup.setElevation(300f);
                }
                View decor = activity.getWindow().getDecorView();
                popup.showAtLocation(decor, Gravity.NO_GRAVITY, 0, 0);
                sExportHudPopup = popup;
                holder[0] = root;
            } catch (Throwable t) {
                Log.w(TAG, "showExportLoadingHud", t);
            } finally {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 1500;
            while (!done[0] && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(50);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
        return holder[0];
    }

    private void dismissExportLoadingHud(View hud) {
        try {
            if (sExportHudPopup != null) {
                if (sExportHudPopup.isShowing()) {
                    sExportHudPopup.dismiss();
                }
                sExportHudPopup = null;
            }
        } catch (Throwable ignored) {
        }
        if (hud == null) {
            return;
        }
        try {
            ViewGroup parent = (ViewGroup) hud.getParent();
            if (parent != null) {
                parent.removeView(hud);
            }
        } catch (Throwable ignored) {
        }
    }

    private ViewGroup resolveOverlayRoot(Activity activity) {
        return (ViewGroup) activity.getWindow().getDecorView();
    }

    private void bringOverlayToFront() {
        if (overlayCameraHost == null) {
            return;
        }
        ViewGroup decor = resolveOverlayRoot(resolveHostActivity());
        if (decor == null) {
            return;
        }
        if (overlayCameraHost.getParent() != decor) {
            attachOverlayHostOnDecor(decor, overlayCameraHost);
            return;
        }
        decor.bringChildToFront(overlayCameraHost);
        overlayCameraHost.requestLayout();
    }

    private void bringVideoOverlayToFront() {
        if (overlayVideoHost == null) {
            return;
        }
        ViewGroup decor = resolveOverlayRoot(resolveHostActivity());
        if (decor == null) {
            return;
        }
        if (overlayVideoHost.getParent() != decor) {
            attachOverlayHostOnDecor(decor, overlayVideoHost);
            return;
        }
        decor.bringChildToFront(overlayVideoHost);
        overlayVideoHost.requestLayout();
    }

    private void attachOverlayHostOnDecor(ViewGroup decorRoot, FrameLayout host) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        if (host.getParent() instanceof ViewGroup) {
            ((ViewGroup) host.getParent()).removeView(host);
        }
        decorRoot.addView(host, lp);
        decorRoot.bringChildToFront(host);
    }

    private void destroyVideoPreviewInternal(boolean keepSession, Runnable onComplete) {
        setPreviewTipsEnabled(false);
        if (overlayVideoView == null) {
            if (overlayVideoHost != null) {
                try {
                    ViewGroup parent = (ViewGroup) overlayVideoHost.getParent();
                    if (parent != null) {
                        parent.removeView(overlayVideoHost);
                    }
                } catch (Exception ignored) {
                }
                overlayVideoHost = null;
            }
            // 仅释放静图 offscreen EGL；keepSession 时勿清 AI/handle
            if (!keepSession) {
                releaseMediaGlIfNeeded("destroyVideoPreview-idle");
            }
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        final BeautyVideoGLView view = overlayVideoView;
        final FrameLayout host = overlayVideoHost;
        // 视频只是显示层：拆掉即可，绝不 markNamaResourcesLost / deviceLost
        overlayVideoView = null;
        overlayVideoHost = null;
        overlayVideoPlayBtn = null;
        lastVideoPath = null;
        sAvoidCameraGlForImageAfterVideo = false;
        view.destroyPreviewAsync(true, () -> {
            try {
                // keepSession：马上挂新视频，勿 exit，否则华为 park 缩 1px → GL 停转黑屏
                if (!keepSession && overlayCameraView != null) {
                    overlayCameraView.exitVideoBeautyHosting();
                }
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) {
                    parent.removeView(view);
                }
                if (host != null) {
                    ViewGroup hostParent = (ViewGroup) host.getParent();
                    if (hostParent != null) {
                        hostParent.removeView(host);
                    }
                }
            } catch (Exception ignored) {
            }
            // 复位检测模式（相机 GL 上的 Nama 仍在）
            try {
                faceunity.fuSetFaceProcessorDetectMode(1);
                if (overlayCameraView != null) {
                    overlayCameraView.reapplyInputCameraMatrix();
                }
            } catch (Throwable ignored) {
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    @UniJSMethod(uiThread = true)
    public void destroy(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            destroyVideoPreviewInternal(false, () -> hideCameraInternal(false, () ->
                    callback.invoke(NamaSdkManager.destroySdk())));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    private static int cssToPhysical(float density, int css) {
        return (int) (css * density + 0.5f);
    }

    private void releaseCameraKeepAliveInternal(Runnable onComplete) {
        dismissFocusHud();
        if (overlayCameraView == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        overlayCameraView.releaseCameraKeepAlive(() -> {
            if (overlayCameraHost != null) {
                overlayCameraHost.setVisibility(View.GONE);
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    /**
     * 对齐 iOS softHide：停相机并藏住预览，不 removeView、不 destroyPreview、不 GONE。
     * ZOrderOnTop 的 GLSurfaceView 一旦 GONE 就会 surfaceDestroyed → EGL 重建 → 美颜 handle 失效（回页有画无美颜）。
     * 做法：保持 VISIBLE，平移出屏幕 + alpha=0。
     */
    private void softHideCameraOverlay(Runnable onComplete) {
        dismissFocusHud();
        try {
            parkCameraOverlayHidden(true);
        } catch (Exception e) {
            Log.w(TAG, "softHideCameraOverlay", e);
        }
        if (onComplete != null) {
            onComplete.run();
        }
    }

    /** soft-hide 时把 previewBox 挪出屏幕；ZOrderOnTop 的 Surface 常不跟 parent alpha/translation */
    private static int sParkedBoxLeft = Integer.MIN_VALUE;
    private static int sParkedBoxTop = Integer.MIN_VALUE;
    private static int sParkedBoxW = -1;
    private static int sParkedBoxH = -1;

    /** @param stopCamera true 时停采集（soft hide / pause）；false 仅藏层（setOverlayWindowsHidden） */
    private void parkCameraOverlayHidden(boolean stopCamera) {
        if (overlayCameraView != null && stopCamera) {
            overlayCameraView.enterVideoBeautyHosting();
        }
        final boolean keepVideoGlAlive = overlayVideoView != null
                || (overlayCameraView != null && overlayCameraView.isVideoBeautyHosting());
        if (overlayCameraView != null) {
            if (stopCamera) {
                overlayCameraView.hidePreview();
            }
            // 切导航重建 Activity 后禁止改 Z-order：会毁掉 Surface/EGL，第二次进相机美颜失效。
            // 老 vivo 仍须卸顶，否则 Surface 不跟 parent 平移会挡住下一页。
            if (!sPreserveCameraEglOnPark) {
                try {
                    overlayCameraView.setZOrderOnTop(false);
                    overlayCameraView.setZOrderMediaOverlay(false);
                } catch (Throwable ignored) {
                }
            }
        }
        final boolean aggressive = DeviceQuirk.needsAggressiveSurfaceHide()
                || sPreserveCameraEglOnPark;
        // ZOrderOnTop：把 previewBox 移出屏幕 + alpha，保持 VISIBLE 以保住 Surface/EGL
        if (overlayCameraHost != null && overlayCameraHost.getChildCount() > 0) {
            View previewBox = overlayCameraHost.getChildAt(0);
            ViewGroup.LayoutParams rawLp = previewBox.getLayoutParams();
            if (rawLp instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams boxLp = (FrameLayout.LayoutParams) rawLp;
                if (sParkedBoxLeft == Integer.MIN_VALUE) {
                    sParkedBoxLeft = boxLp.leftMargin;
                    sParkedBoxTop = boxLp.topMargin;
                    sParkedBoxW = boxLp.width;
                    sParkedBoxH = boxLp.height;
                }
                boxLp.leftMargin = 100000;
                boxLp.topMargin = 0;
                // 老机 Surface 不跟 margin：必须缩到小块，否则全屏黑 Surface 盖住导入视频（P20 Pro 黑屏主因）
                if (aggressive && !keepVideoGlAlive) {
                    boxLp.width = 1;
                    boxLp.height = 1;
                } else if (aggressive && keepVideoGlAlive) {
                    // 保留小块 EGL 给视频美颜；绝不能沿用全屏 sParkedBoxW/H
                    int parked = DeviceQuirk.isHuaweiFamily() ? dpToPx(320) : dpToPx(128);
                    boxLp.width = parked;
                    boxLp.height = parked;
                }
                previewBox.setLayoutParams(boxLp);
                if (aggressive && keepVideoGlAlive && overlayCameraView != null) {
                    try {
                        overlayCameraView.bindLayoutSize(boxLp.width, boxLp.height);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (overlayCameraHost != null) {
            overlayCameraHost.setAlpha(0f);
            overlayCameraHost.setEnabled(keepVideoGlAlive);
            if (aggressive && !keepVideoGlAlive) {
                overlayCameraHost.setVisibility(View.INVISIBLE);
            } else {
                overlayCameraHost.setVisibility(View.VISIBLE);
            }
            if (overlayCameraView != null) {
                overlayCameraView.setAlpha(0f);
                if (aggressive && !keepVideoGlAlive) {
                    overlayCameraView.setVisibility(View.INVISIBLE);
                } else {
                    overlayCameraView.setVisibility(View.VISIBLE);
                }
                if (keepVideoGlAlive) {
                    overlayCameraView.enterVideoBeautyHosting();
                }
            }
        } else if (overlayCameraView != null) {
            overlayCameraView.setTranslationX(4096f);
            overlayCameraView.setAlpha(0f);
            if (aggressive && !keepVideoGlAlive) {
                overlayCameraView.setVisibility(View.INVISIBLE);
            } else {
                overlayCameraView.setVisibility(View.VISIBLE);
            }
            if (keepVideoGlAlive) {
                overlayCameraView.enterVideoBeautyHosting();
            }
        }
        dismissPreviewChrome();
        dismissBeautyPanel();
        Log.i(TAG, "parkCameraOverlayHidden stopCamera=" + stopCamera
                + " aggressive=" + aggressive + " keepVideoGl=" + keepVideoGlAlive);
    }

    /** 动态加入的 GLSurfaceView 须 onResume，否则 GL 线程不跑（华为视频黑屏主因之一） */
    private static void wakeGlSurfaceView(GLSurfaceView view, Activity activity) {
        if (view == null || activity == null || activity.isFinishing()) {
            return;
        }
        try {
            view.setVisibility(View.VISIBLE);
            view.onResume();
            view.requestRender();
        } catch (Throwable t) {
            Log.w(TAG, "wakeGlSurfaceView", t);
        }
    }

    private void unparkCameraOverlay() {
        sAvoidCameraGlForImageAfterVideo = false;
        // 回相机：复位视频可能改过的检测/矩阵
        try {
            faceunity.fuSetFaceProcessorDetectMode(1);
            if (overlayCameraView != null) {
                overlayCameraView.reapplyInputCameraMatrix();
            }
        } catch (Throwable ignored) {
        }
        if (overlayCameraHost != null && overlayCameraHost.getChildCount() > 0
                && sParkedBoxLeft != Integer.MIN_VALUE) {
            View previewBox = overlayCameraHost.getChildAt(0);
            ViewGroup.LayoutParams rawLp = previewBox.getLayoutParams();
            if (rawLp instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams boxLp = (FrameLayout.LayoutParams) rawLp;
                boxLp.leftMargin = sParkedBoxLeft;
                boxLp.topMargin = sParkedBoxTop;
                if (sParkedBoxW > 0) {
                    boxLp.width = sParkedBoxW;
                }
                if (sParkedBoxH > 0) {
                    boxLp.height = sParkedBoxH;
                }
                previewBox.setLayoutParams(boxLp);
            }
            sParkedBoxLeft = Integer.MIN_VALUE;
            sParkedBoxTop = Integer.MIN_VALUE;
            sParkedBoxW = -1;
            sParkedBoxH = -1;
        }
        if (overlayCameraHost != null) {
            overlayCameraHost.setTranslationX(0f);
            overlayCameraHost.setAlpha(1f);
            overlayCameraHost.setEnabled(true);
            overlayCameraHost.setVisibility(View.VISIBLE);
        }
        if (overlayCameraView != null) {
            overlayCameraView.setTranslationX(0f);
            overlayCameraView.setAlpha(1f);
            overlayCameraView.setVisibility(View.VISIBLE);
            if (!sPreserveCameraEglOnPark) {
                try {
                    overlayCameraView.setZOrderMediaOverlay(true);
                    overlayCameraView.setZOrderOnTop(true);
                } catch (Throwable ignored) {
                }
            }
            // 仅视频交接后才 resumeGlAfterHandoff；soft-hide 只 resumePreview，避免无配对 onPause 的 onResume 黑屏
            if (sCameraGlHandedOff) {
                sCameraGlHandedOff = false;
                overlayCameraView.resumeGlAfterHandoff();
            } else {
                overlayCameraView.resumePreview();
            }
            try {
                if (previewChromeView != null && overlayCameraView != null) {
                    int ev = sPausedExposureUi >= 0
                            ? sPausedExposureUi
                            : overlayCameraView.getLastExposureUi();
                    sPausedExposureUi = -1;
                    overlayCameraView.setExposureCompensation(ev);
                    previewChromeView.setFocusExposure(ev);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void hideCameraInternal(boolean keepSession, Runnable onComplete) {
        setPreviewTipsEnabled(false);
        dismissFocusHud();
        if (overlayCameraView == null) {
            resetOverlayLayoutCache();
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        final BeautyCameraGLView view = overlayCameraView;
        final FrameLayout host = overlayCameraHost;
        final boolean keep = keepSession;
        overlayCameraView = null;
        overlayCameraHost = null;

        // 立刻从层级移除，用户马上看到下层页面，不再等 GL destroy
        try {
            if (host != null) {
                host.setVisibility(View.GONE);
                ViewGroup hostParent = (ViewGroup) host.getParent();
                if (hostParent != null) {
                    hostParent.removeView(host);
                }
            } else {
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) {
                    parent.removeView(view);
                }
            }
        } catch (Exception ignored) {
        }

        if (keep) {
            // 必须等旧 GL 资源释放完再回调，否则立刻 remount / processImage / 视频叠层会黑屏
            view.destroyPreviewAsync(true, () -> {
                resetOverlayLayoutCache();
                if (onComplete != null) {
                    onComplete.run();
                }
            });
            return;
        }

        view.setVisibility(View.VISIBLE);
        view.destroyPreviewAsync(false, () -> {
            try {
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) {
                    parent.removeView(view);
                }
            } catch (Exception ignored) {
            }
            markNamaResourcesLost("hideCamera");
            resetOverlayLayoutCache();
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    /** deviceLost 之后 AI 与全部 beauty item 均失效 */
    private static void markNamaResourcesLost(String reason) {
        NamaSdkManager.markResourcesLost(reason);
        // 资源已 lost，后续静图可再走相机 GL / 新 offscreen，勿继续强制 avoid
        sAvoidCameraGlForImageAfterVideo = false;
    }

    /** 仅当静图 offscreen EGL 确实存在时释放并清 handle */
    private static void releaseMediaGlIfNeeded(String reason) {
        NamaSdkManager.releaseMediaGlIfNeeded(reason);
    }

    private void resetOverlayLayoutCache() {
        lastCssX = -1;
        lastCssY = -1;
        lastCssW = -1;
        lastCssH = -1;
        resetParkedBoxCache();
    }

    private void watchHostRecreate() {
        try {
            Activity host = resolveHostActivity();
            if (host != null) {
                NamaHostRecreateWatch.install(host);
                return;
            }
            Activity overlay = resolveStaticActivity();
            if (overlay != null) {
                NamaHostRecreateWatch.install(overlay);
            }
        } catch (Throwable ignored) {
        }
    }

    private Activity resolveHostActivity() {
        Object instance = resolveUniSDKInstance();
        if (instance == null) {
            return null;
        }
        try {
            Object ctx = instance.getClass().getMethod("getContext").invoke(instance);
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }
        } catch (Exception e) {
            Log.e(TAG, "resolveHostActivity", e);
        }
        return null;
    }

    private Object resolveUniSDKInstance() {
        Class<?> clazz = getClass();
        while (clazz != null) {
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                String typeName = field.getType().getName();
                if (typeName.contains("UniSDKInstance") || typeName.contains("SDKInstance")) {
                    try {
                        field.setAccessible(true);
                        return field.get(this);
                    } catch (Exception ignored) {
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private void ensureInitialized() {
        NamaSdkManager.ensureInitialized();
    }

    private JSONObject success(Object data) {
        return NamaJsResult.success(data);
    }

    private JSONObject fail(String message) {
        return NamaJsResult.fail(message);
    }

    private JSONObject fail(String message, JSONObject diag) {
        return NamaJsResult.fail(message, diag);
    }
}
