package com.faceunity.nama;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.app.Activity;
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
import android.widget.TextView;

import com.alibaba.fastjson.JSONObject;
import com.faceunity.app.authpack;
import com.faceunity.wrapper.faceunity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;

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

    private static int beautyItemHandle = 0;
    private static int mediaBeautyItemHandle = 0;
    private static boolean initialized = false;
    private static boolean aiModelLoaded = false;
    private static BeautyCameraGLView overlayCameraView;
    private static FrameLayout overlayCameraHost;
    /** 视频预览过后：静图勿再走相机 GL，避免矩阵残留颠倒；纯导入图片仍走相机 GL（已验证正常） */
    private static volatile boolean sAvoidCameraGlForImageAfterVideo = false;
    /** 仅视频交还相机时为 true；soft-hide 不要走 resumeGlAfterHandoff（成对 onPause 缺失会黑屏） */
    private static volatile boolean sCameraGlHandedOff = false;
    /** true：相机由 nvue &lt;beauty-camera&gt; 托管，不挂 decor 叠层 */
    private static volatile boolean cameraHostedByComponent = false;
    private static BeautyVideoGLView overlayVideoView;
    private static FrameLayout overlayVideoHost;
    private static ImageView overlayVideoPlayBtn;
    private static String lastVideoPath = null;
    private static PreviewChromeView previewChromeView;
    private static PopupWindow previewChromePopup;
    private static FuBeautyPanelView beautyPanelView;
    private static PopupWindow beautyPanelPopup;
    /** 媒体页左上角返回（挂 Decor，对齐 iOS ensureMediaBackButton） */
    private static FrameLayout mediaBackBtn;
    private static int lastCssX = -1;
    private static int lastCssY = -1;
    private static int lastCssW = -1;
    private static int lastCssH = -1;
    /** 旧 Popup 对焦层（无 chrome 时兜底）；有 PreviewChrome 时优先进 chrome 内 FocusHud */
    private static FocusHudView focusHudView;
    private static PopupWindow focusHudPopup;

    /** 滤镜名 / 未检测到人脸：PopupWindow 盖住 ZOrderOnTop 取景与底栏 */
    private static FrameLayout sTipsHost;
    private static TextView sNoFaceTip;
    private static TextView sFilterTip;
    private static PopupWindow sTipsPopup;
    private static final Handler sTipsHandler = new Handler(Looper.getMainLooper());
    private static Runnable sFilterHideTask;
    private static volatile boolean sFaceTracked = true;
    private static volatile boolean sTipsEnabled = false;
    private static volatile long sTipsEnabledAtMs = 0L;
    private static PopupWindow sExportHudPopup;
    /** SDK DEBUG 文件日志路径（{@link MediaFuSetup#SDK_LOG_FILE_NAME}） */
    /** nvue BeautyCameraComponent 创建 GL 后注册 */
    public static void attachHostedCameraView(BeautyCameraGLView view) {
        if (view == null) {
            return;
        }
        // 若仍有旧 decor 叠层，先拆掉，避免双预览
        if (overlayCameraHost != null) {
            try {
                final FrameLayout host = overlayCameraHost;
                final BeautyCameraGLView old = overlayCameraView;
                overlayCameraHost = null;
                if (old != null && old != view) {
                    old.destroyPreviewAsync(null);
                }
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        ViewGroup parent = (ViewGroup) host.getParent();
                        if (parent != null) {
                            parent.removeView(host);
                        }
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable ignored) {
            }
        }
        overlayCameraView = view;
        overlayCameraHost = null;
        cameraHostedByComponent = true;
        Log.e(TAG, "attachHostedCameraView");
    }

    /** 组件 destroy 时注销 */
    public static void detachHostedCameraView(BeautyCameraGLView view) {
        if (view == null) {
            return;
        }
        if (overlayCameraView == view) {
            overlayCameraView = null;
            cameraHostedByComponent = false;
            Log.e(TAG, "detachHostedCameraView");
        }
    }

    public static boolean isCameraHostedByComponent() {
        return cameraHostedByComponent && overlayCameraView != null;
    }

    /** 视频显示层取相机 GL：Nama 只挂在相机上下文，视频页禁止自建 Nama */
    static BeautyCameraGLView peekCameraOverlay() {
        return overlayCameraView;
    }

    static void setPreviewTipsEnabled(boolean enabled) {
        sTipsEnabled = enabled;
        if (enabled) {
            sTipsEnabledAtMs = System.currentTimeMillis();
            sFaceTracked = true;
        }
        sTipsHandler.post(() -> {
            if (previewChromeView != null) {
                previewChromeView.setTipsEnabled(enabled);
                if (enabled) {
                    previewChromeView.setNoFaceVisible(false);
                }
                return;
            }
            if (!enabled) {
                hideTipsOverlay();
            } else {
                Activity act = resolveStaticActivity();
                if (act != null) {
                    ensureTipsOverlay(act);
                    updateNoFaceTipUi();
                }
            }
        });
    }

    /** GL 线程上报人脸跟踪；主线程刷新「未检测到人脸」 */
    static void onFaceTrackingUpdated(boolean tracked) {
        if (sFaceTracked == tracked) {
            return;
        }
        sFaceTracked = tracked;
        sTipsHandler.post(() -> {
            if (previewChromeView != null) {
                previewChromeView.setNoFaceVisible(sTipsEnabled && !tracked);
            } else {
                updateNoFaceTipUi();
            }
        });
    }

    /** 机型限制提示（对齐 iOS showPreviewPerfLimitTip / FULiveDemo 灰显点击 toast） */
    static void showPerfLimitTip(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        sTipsHandler.post(() -> {
            if (previewChromeView != null) {
                previewChromeView.showPerfLimitTip(message);
                return;
            }
            Activity act = resolveStaticActivity();
            if (act == null) {
                return;
            }
            ensureTipsOverlay(act);
            if (sFilterTip != null) {
                sFilterTip.setTextSize(14);
                sFilterTip.setText(message);
                sFilterTip.setVisibility(View.VISIBLE);
                if (sFilterHideTask != null) {
                    sTipsHandler.removeCallbacks(sFilterHideTask);
                }
                sFilterHideTask = () -> {
                    if (sFilterTip != null) {
                        sFilterTip.setVisibility(View.GONE);
                        sFilterTip.setTextSize(20);
                    }
                };
                sTipsHandler.postDelayed(sFilterHideTask, 2000L);
            }
        });
    }

    /** 切滤镜：画面正中短暂显示滤镜名（对齐 Demo） */
    static void showFilterNameTip(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        sTipsHandler.post(() -> {
            boolean mediaOverlay = overlayVideoView != null || overlayVideoHost != null;
            if (!mediaOverlay && previewChromeView != null && overlayCameraView != null) {
                previewChromeView.showFilterNameTip(name);
                return;
            }
            Activity act = resolveStaticActivity();
            if (act == null) {
                return;
            }
            ensureTipsOverlay(act);
            if (sFilterTip == null) {
                return;
            }
            sFilterTip.setText(name);
            sFilterTip.setVisibility(View.VISIBLE);
            if (sFilterHideTask != null) {
                sTipsHandler.removeCallbacks(sFilterHideTask);
            }
            sFilterHideTask = () -> {
                if (sFilterTip != null) {
                    sFilterTip.setVisibility(View.GONE);
                }
            };
            sTipsHandler.postDelayed(sFilterHideTask, 1000L);
        });
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

    private static void ensureTipsOverlay(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            if (sTipsHost == null) {
                sTipsHost = new FrameLayout(activity);
                sTipsHost.setClickable(false);
                sTipsHost.setFocusable(false);

                sNoFaceTip = new TextView(activity);
                sNoFaceTip.setText("未检测到人脸");
                sNoFaceTip.setTextColor(0xFFFFFFFF);
                sNoFaceTip.setTextSize(16);
                sNoFaceTip.setGravity(Gravity.CENTER);
                sNoFaceTip.setShadowLayer(4f, 0f, 1f, 0x99000000);
                sNoFaceTip.setVisibility(View.GONE);
                FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp.gravity = Gravity.CENTER;
                sTipsHost.addView(sNoFaceTip, nlp);

                sFilterTip = new TextView(activity);
                sFilterTip.setTextColor(0xFFFFFFFF);
                sFilterTip.setTextSize(20);
                sFilterTip.setGravity(Gravity.CENTER);
                sFilterTip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                sFilterTip.setShadowLayer(6f, 0f, 2f, 0xCC000000);
                sFilterTip.setVisibility(View.GONE);
                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                flp.gravity = Gravity.CENTER;
                sTipsHost.addView(sFilterTip, flp);
            }
            if (sTipsPopup == null) {
                sTipsPopup = new PopupWindow(sTipsHost,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        false);
                sTipsPopup.setTouchable(false);
                sTipsPopup.setFocusable(false);
                sTipsPopup.setOutsideTouchable(false);
                sTipsPopup.setClippingEnabled(false);
                sTipsPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // 高于 chrome(120) / 美颜面板(140)，低于导出 HUD(300)
                    sTipsPopup.setElevation(200f);
                }
            } else if (sTipsPopup.getContentView() != sTipsHost) {
                sTipsPopup.setContentView(sTipsHost);
            }
            if (!sTipsPopup.isShowing()) {
                if (activity.isFinishing()) {
                    return;
                }
                View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
                if (decor == null || decor.getWindowToken() == null) {
                    // 窗口未就绪：延后一帧，避免 BadToken 闪退
                    decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
                    final Activity act = activity;
                    sTipsHandler.post(() -> {
                        try {
                            if (act.isFinishing() || sTipsPopup == null || sTipsPopup.isShowing()) {
                                return;
                            }
                            View d = act.getWindow() != null ? act.getWindow().getDecorView() : null;
                            if (d != null && d.getWindowToken() != null) {
                                sTipsPopup.showAtLocation(d, Gravity.NO_GRAVITY, 0, 0);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "ensureTipsOverlay delayed show", t);
                        }
                    });
                    return;
                }
                sTipsPopup.showAtLocation(decor, Gravity.NO_GRAVITY, 0, 0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureTipsOverlay", t);
        }
    }

    private static void hideTipsOverlay() {
        try {
            if (sFilterHideTask != null) {
                sTipsHandler.removeCallbacks(sFilterHideTask);
                sFilterHideTask = null;
            }
            if (sNoFaceTip != null) {
                sNoFaceTip.setVisibility(View.GONE);
            }
            if (sFilterTip != null) {
                sFilterTip.setVisibility(View.GONE);
            }
            if (sTipsPopup != null && sTipsPopup.isShowing()) {
                sTipsPopup.dismiss();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void updateNoFaceTipUi() {
        if (!sTipsEnabled || sNoFaceTip == null) {
            return;
        }
        // 开镜前 2s 不提示，避免冷启动误报
        if (System.currentTimeMillis() - sTipsEnabledAtMs < 2000L) {
            sNoFaceTip.setVisibility(View.GONE);
            return;
        }
        sNoFaceTip.setVisibility(sFaceTracked ? View.GONE : View.VISIBLE);
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
        try {
            String version = faceunity.fuGetVersion();
            callback.invoke(success(version));
        } catch (Throwable e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /** Android 进程存活重进：JS 缓存 sdkInited 时校验 native 是否仍就绪 */
    @UniJSMethod(uiThread = false)
    public void isSdkAlive(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            int libInit = faceunity.fuIsLibraryInit();
            JSONObject o = new JSONObject();
            o.put("libInit", libInit);
            o.put("initialized", initialized);
            o.put("cameraHandle", FuBeautyHandle.cameraHandle);
            o.put("mediaHandle", FuBeautyHandle.mediaHandle);
            o.put("aiLoaded", aiModelLoaded);
            o.put("alive", libInit != 0 && initialized);
            callback.invoke(success(o));
        } catch (Throwable e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = false)
    public void init(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            if (initialized && faceunity.fuIsLibraryInit() != 0) {
                JSONObject ok = new JSONObject();
                ok.put("version", faceunity.fuGetVersion());
                ok.put("fuIsLibraryInit", faceunity.fuIsLibraryInit());
                callback.invoke(success(ok));
                return;
            }
            if (faceunity.fuIsLibraryInit() == 0) {
                initialized = false;
                aiModelLoaded = false;
                beautyItemHandle = 0;
                mediaBeautyItemHandle = 0;
                FuBeautyHandle.clearAll();
            }
            byte[] authData = authpack.A();
            try {
                Activity actCtx = resolveHostActivity();
                if (actCtx != null) {
                    MediaFuSetup.setAppContext(actCtx);
                }
            } catch (Throwable ignored) {
            }
            int setupCode = faceunity.fuSetup(new byte[0], authData);
            int libInit = faceunity.fuIsLibraryInit();
            int systemError = faceunity.fuGetSystemError();
            String version = faceunity.fuGetVersion();
            JSONObject diag = new JSONObject();
            diag.put("version", version);
            diag.put("authSize", authData.length);
            diag.put("fuSetupCode", setupCode);
            diag.put("fuIsLibraryInit", libInit);
            diag.put("fuGetSystemError", systemError);
            if (systemError != 0) {
                diag.put("fuGetSystemErrorString", faceunity.fuGetSystemErrorString(systemError));
            }
            if (setupCode == 0) {
                callback.invoke(fail("fuSetup 失败 code=0", diag));
                return;
            }
            if (libInit == 0) {
                callback.invoke(fail("SDK 未就绪 fuIsLibraryInit=0（authpack 与包名/签名不匹配）", diag));
                return;
            }
            initialized = true;
            MediaFuSetup.enableFaceAlgorithmModules();
            callback.invoke(success(diag));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = false)
    public void loadAIModel(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            ensureInitialized();
            byte[] data = readFileBytes(options.getString("path"));
            int aiType = options.getIntValue("aiType");
            if (aiType == 0) {
                aiType = faceunity.FUAITYPE_FACEPROCESSOR;
            }
            // 必须在 loadAIModel 之前开启全部人脸算法子模块（皮肤分割/ARMeshV2/丰盈/瞳孔等）
            MediaFuSetup.enableFaceAlgorithmModules();
            if (aiModelLoaded) {
                try {
                    faceunity.fuReleaseAIModel(faceunity.FUAITYPE_FACEPROCESSOR);
                } catch (Throwable ignored) {
                }
                aiModelLoaded = false;
            }
            // release 可能把 algorithmConfig 打回 -1，load 前必须再开一次（对齐 iOS）
            MediaFuSetup.enableFaceAlgorithmModules();
            FuAiExtras.resetSetUseApplied();
            int handle = faceunity.fuLoadAIModelFromPackage(data, aiType);
            if (handle <= 0) {
                callback.invoke(fail("loadAIModel 失败 handle=" + handle));
                return;
            }
            // load 后再开公开侧运行时开关
            MediaFuSetup.enableAdvancedBeautyRuntime(0);
            aiModelLoaded = true;
            configureFaceProcessor();
            if (beautyItemHandle > 0) {
                FuBeautyPerfGate.enforceOnHandle(beautyItemHandle);
            }
            if (mediaBeautyItemHandle > 0) {
                FuBeautyPerfGate.enforceOnHandle(mediaBeautyItemHandle);
            }
            int faceOk = 0;
            try {
                faceOk = faceunity.fuIsAIModelLoaded(faceunity.FUAITYPE_FACEPROCESSOR);
            } catch (Throwable ignored) {
            }
            int m0 = 0, m1 = 0, m2 = 0, m3 = 0;
            try {
                m0 = faceunity.fuGetModuleCode(0);
                m1 = faceunity.fuGetModuleCode(1);
                m2 = faceunity.fuGetModuleCode(2);
                m3 = faceunity.fuGetModuleCode(3);
            } catch (Throwable ignored) {
            }
            long aiBytes = data != null ? data.length : 0;
            Log.e(TAG, "loadAIModel ok handle=" + handle
                    + " aiBytes=" + aiBytes
                    + " faceLoaded=" + faceOk
                    + " ARMeshV2=1 algo=ENABLE_ALL"
                    + " module=[" + m0 + "," + m1 + "," + m2 + "," + m3 + "]");
            JSONObject dataOut = new JSONObject();
            dataOut.put("handle", handle);
            dataOut.put("aiBytes", aiBytes);
            dataOut.put("faceLoaded", faceOk);
            dataOut.put("moduleCode0", m0);
            dataOut.put("moduleCode1", m1);
            dataOut.put("moduleCode2", m2);
            dataOut.put("moduleCode3", m3);
            callback.invoke(success(dataOut));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = false)
    public void loadBundle(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            ensureInitialized();
            byte[] data = readFileBytes(options.getString("path"));
            String pipeline = options != null ? options.getString("pipeline") : null;
            boolean media = pipeline != null && "media".equalsIgnoreCase(pipeline);
            int old = media ? mediaBeautyItemHandle : beautyItemHandle;
            if (old > 0) {
                try {
                    faceunity.fuDestroyItem(old);
                } catch (Exception ignored) {
                }
            }
            int handle = faceunity.fuCreateItemFromPackage(data);
            if (handle <= 0) {
                callback.invoke(fail("loadBundle 失败 handle=" + handle));
                return;
            }
            MediaFuSetup.enableAdvancedBeautyRuntime(handle);
            MediaFuSetup.ensureBeautyOn(handle);
            if (media) {
                mediaBeautyItemHandle = handle;
                FuBeautyHandle.setPipelineHandle(true, handle);
            } else {
                beautyItemHandle = handle;
                FuBeautyHandle.setPipelineHandle(false, handle);
            }
            JSONObject dataOut = new JSONObject();
            dataOut.put("handle", handle);
            dataOut.put("pipeline", media ? "media" : "camera");
            callback.invoke(success(dataOut));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /** JS 复用相机 beauty handle 时，同步原生 mediaBeautyItemHandle，避免媒体页 handle=0 */
    @UniJSMethod(uiThread = false)
    public void bindMediaBeautyHandle(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            int handle = options != null ? options.getIntValue("handle") : 0;
            if (handle <= 0) {
                handle = beautyItemHandle > 0 ? beautyItemHandle : FuBeautyHandle.cameraHandle;
            }
            if (handle <= 0) {
                callback.invoke(fail("无可用 beauty handle"));
                return;
            }
            mediaBeautyItemHandle = handle;
            FuBeautyHandle.setPipelineHandle(true, handle);
            MediaFuSetup.enableAdvancedBeautyRuntime(handle);
            MediaFuSetup.ensureBeautyOn(handle);
            Log.i(TAG, "bindMediaBeautyHandle handle=" + handle
                    + " camera=" + beautyItemHandle
                    + " media=" + mediaBeautyItemHandle);
            JSONObject data = new JSONObject();
            data.put("handle", handle);
            data.put("mediaHandle", mediaBeautyItemHandle);
            data.put("cameraHandle", beautyItemHandle);
            callback.invoke(success(data));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = false)
    public void setParam(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            ensureInitialized();
            int handle = options.getIntValue("handle");
            if (handle <= 0) {
                String pipeline = options.getString("pipeline");
                boolean media = pipeline != null && "media".equalsIgnoreCase(pipeline);
                handle = media ? mediaBeautyItemHandle : beautyItemHandle;
                if (handle <= 0) {
                    handle = media ? FuBeautyHandle.mediaHandle : FuBeautyHandle.cameraHandle;
                }
            }
            if (handle <= 0) {
                callback.invoke(fail("请先 loadBundle"));
                return;
            }
            int code;
            String key = options.getString("key");
            String stringValue = options.getString("stringValue");
            if (stringValue != null) {
                code = BeautyParamApplier.setString(handle, key, stringValue);
            } else {
                double value = options.getDoubleValue("value");
                boolean special = isSpecialAlgoBeautyKey(key);
                final int h = handle;
                final String k = key;
                final double v = value;
                if (special) {
                    // 对齐 iOS performWithSharedGLLock：与 DualInput 串行写参，禁止超时落到裸线程
                    final int[] codeBox = { -1 };
                    runOnNamaGlSync(() ->
                            codeBox[0] = BeautyParamApplier.applySpecialAlgoParam(h, k, v));
                    code = codeBox[0];
                } else {
                    code = BeautyParamApplier.setDouble(handle, key, value);
                }
                double got = 0;
                try {
                    got = faceunity.fuItemGetParam(handle, key);
                } catch (Throwable ignored) {
                }
                JSONObject dataOut = new JSONObject();
                dataOut.put("ret", code);
                if (special) {
                    double skinseg = 0;
                    double delspotOff = 0;
                    int m0 = 0, m1 = 0, m2 = 0, m3 = 0;
                    try {
                        skinseg = faceunity.fuItemGetParam(handle, "enable_skinseg");
                        delspotOff = faceunity.fuItemGetParam(handle, "disable_delspot");
                        m0 = faceunity.fuGetModuleCode(0);
                        m1 = faceunity.fuGetModuleCode(1);
                        m2 = faceunity.fuGetModuleCode(2);
                        m3 = faceunity.fuGetModuleCode(3);
                    } catch (Throwable ignored) {
                    }
                    dataOut.put("key", key);
                    dataOut.put("set", value);
                    dataOut.put("get", got);
                    dataOut.put("enable_skinseg", skinseg);
                    dataOut.put("disable_delspot", delspotOff);
                    dataOut.put("handle", handle);
                    dataOut.put("moduleCode0", m0);
                    dataOut.put("moduleCode1", m1);
                    dataOut.put("moduleCode2", m2);
                    dataOut.put("moduleCode3", m3);
                    String nativeDiag = "[NamaNative] special key=" + key
                            + " set=" + value + " get=" + got + " ret=" + code
                            + " skinseg=" + skinseg
                            + " disable_delspot=" + delspotOff
                            + " handle=" + handle
                            + " module=[" + m0 + "," + m1 + "," + m2 + "," + m3 + "]";
                    dataOut.put("nativeDiag", nativeDiag);
                    Log.i(TAG, nativeDiag);
                }
                callback.invoke(success(dataOut));
                return;
            }
            callback.invoke(success(code));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
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
            boolean hostedOnly = options.getBooleanValue("hostedOnly");
            // nvue <beauty-camera> 托管：只恢复预览，不挂 decor 叠层
            if (cameraHostedByComponent && overlayCameraView != null) {
                int width = options.getIntValue("width");
                int height = options.getIntValue("height");
                if (width > 0 && height > 0) {
                    Activity act = resolveHostActivity();
                    float density = act != null ? act.getResources().getDisplayMetrics().density : 3f;
                    overlayCameraView.bindLayoutSize(
                            cssToPhysical(density, width),
                            cssToPhysical(density, height)
                    );
                } else {
                    int vw = overlayCameraView.getWidth();
                    int vh = overlayCameraView.getHeight();
                    if (vw > 32 && vh > 32) {
                        overlayCameraView.bindLayoutSize(vw, vh);
                    }
                }
                overlayCameraView.setVisibility(View.VISIBLE);
                unparkCameraOverlay();
                // unpark 已 resumePreview / resumeGlAfterHandoff，勿再调一次
                JSONObject data = new JSONObject();
                data.put("hosted", true);
                data.put("cameraError", BeautyCameraGLView.getLastError());
                data.put("diag", BeautyCameraGLView.getPreviewDiag());
                callback.invoke(success(data));
                return;
            }
            // nvue 页必须等组件挂好：绝不能再走 decor + ZOrderOnTop，否则会黑屏盖住全部 UI
            if (hostedOnly) {
                Log.e(TAG, "showCamera hostedOnly but component not ready");
                callback.invoke(fail("hosted camera not ready"));
                return;
            }
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
                            if (!resizeOnlyBind) {
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
            // keepSession=true：只隐藏显示（soft hide），不拆 GL、不输出黑帧
            // keepSession=false：拆除 overlay（离开美颜页 / 强制重建）
            if (keepSession) {
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
                // 相机：勿 GONE（会毁 Surface/EGL → 回页黑屏无美颜）；移出屏幕外隐藏
                parkCameraOverlayHidden(false);
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
                    overlayVideoView.requestRender();
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
        // 点按对焦沿用相机记住的曝光，避免 Home 回前台后 chrome 默认值覆盖成「随机」
        int ev = overlayCameraView.getLastExposureUi();
        if (ev == 50 && exposure != 50) {
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
            int exposure = options != null ? options.getIntValue("exposure") : 50;
            if (options != null && options.containsKey("value") && !options.containsKey("exposure")) {
                // 兼容 iOS setExposureBias：value 为 -1~1 或 0~1，映射到 0~100
                double v = options.getDoubleValue("value");
                if (v >= -1.0 && v <= 1.0) {
                    exposure = (int) Math.round((v + 1.0) * 50.0);
                } else {
                    exposure = (int) Math.round(v);
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
            ensureTipsOverlay(activity);
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
            ensureTipsOverlay(activity);
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
                    NamaRenderLock.runExclusive(() -> {
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
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) {
            return;
        }
        FrameLayout parent = (FrameLayout) decor;
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
        if (mediaBackBtn.getParent() != parent) {
            if (mediaBackBtn.getParent() instanceof ViewGroup) {
                ((ViewGroup) mediaBackBtn.getParent()).removeView(mediaBackBtn);
            }
            parent.addView(mediaBackBtn);
        }
        layoutMediaBackButton(activity);
        mediaBackBtn.setVisibility(View.VISIBLE);
        parent.bringChildToFront(mediaBackBtn);
        if (overlayVideoHost != null) {
            parent.bringChildToFront(overlayVideoHost);
        }
        if (beautyPanelPopup != null && beautyPanelPopup.isShowing()) {
            // PopupWindow 在 decor 上层，返回键需在 Popup 之下由 decor child order 保证可点
        }
    }

    private void layoutMediaBackButton(Activity activity) {
        if (mediaBackBtn == null || mediaBackBtn.getParent() == null) {
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
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = dpToPx(10);
        lp.topMargin = topSafe + dpToPx(8);
        mediaBackBtn.setLayoutParams(lp);
        mediaBackBtn.setElevation(24f);
    }

    private static void hideMediaBackButton() {
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
        boolean special = isSpecialAlgoBeautyKey(key);
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
            NamaRenderLock.runExclusive(apply);
        }
        refreshPausedVideoBeautyIfNeeded();
        if (overlayCameraView != null) {
            overlayCameraView.requestRender();
        }
    }

    private int resolvePanelBeautyHandle() {
        // 导入视频叠层：优先 media handle，避免写到相机道具而预览仍用 media
        if (overlayVideoView != null || overlayVideoHost != null) {
            int media = mediaBeautyItemHandle > 0 ? mediaBeautyItemHandle : FuBeautyHandle.mediaHandle;
            if (media > 0) {
                return media;
            }
        }
        int handle = beautyItemHandle > 0 ? beautyItemHandle : FuBeautyHandle.cameraHandle;
        if (handle <= 0) {
            handle = mediaBeautyItemHandle > 0 ? mediaBeautyItemHandle : FuBeautyHandle.mediaHandle;
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
        if (action == null) {
            return;
        }
        BeautyCameraGLView cam = overlayCameraView;
        if (cam != null) {
            try {
                cam.queueEvent(() -> {
                    try {
                        NamaRenderLock.runExclusive(action);
                    } catch (Throwable t) {
                        Log.w(TAG, "runOnNamaGl", t);
                    }
                });
                cam.requestRender();
                return;
            } catch (Throwable t) {
                Log.w(TAG, "runOnNamaGl queue", t);
            }
        }
        try {
            NamaRenderLock.runExclusive(action);
        } catch (Throwable t) {
            Log.w(TAG, "runOnNamaGl direct", t);
        }
    }

    /**
     * 特殊算法写参：同步等待 GL 队列执行，与 DualInput 共用 {@link NamaRenderLock}。
     * 对齐 iOS performWithSharedGLLock；禁止超时后无锁裸写（会导致 get=set 无画面效果）。
     */
    private void runOnNamaGlSync(Runnable action) {
        if (action == null) {
            return;
        }
        BeautyCameraGLView cam = overlayCameraView;
        if (cam != null) {
            final Object waitLock = new Object();
            final boolean[] done = { false };
            final int prevMode = cam.getRenderMode();
            try {
                // WHEN_DIRTY 时强制刷一帧，避免 queueEvent 迟迟不跑
                cam.setRenderMode(BeautyCameraGLView.RENDERMODE_CONTINUOUSLY);
                cam.queueEvent(() -> {
                    try {
                        NamaRenderLock.runExclusive(action);
                    } catch (Throwable t) {
                        Log.w(TAG, "runOnNamaGlSync gl", t);
                    } finally {
                        synchronized (waitLock) {
                            done[0] = true;
                            waitLock.notifyAll();
                        }
                    }
                });
                cam.requestRender();
                long deadline = System.currentTimeMillis() + 2000L;
                synchronized (waitLock) {
                    while (!done[0] && System.currentTimeMillis() < deadline) {
                        try {
                            waitLock.wait(16);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "runOnNamaGlSync queue", t);
            } finally {
                try {
                    cam.setRenderMode(prevMode);
                } catch (Throwable ignored) {
                }
            }
            if (done[0]) {
                return;
            }
            Log.w(TAG, "runOnNamaGlSync timeout → SharedEgl/exclusive fallback");
        }
        // 无相机或队列超时：仍在 NamaRenderLock 内写（必要时 makeCurrent SharedEgl）
        try {
            NamaRenderLock.runExclusive(() -> {
                boolean made = false;
                try {
                    SharedEglRoot.makeCurrent();
                    made = true;
                } catch (Throwable ignored) {
                }
                try {
                    action.run();
                } finally {
                    if (made) {
                        try {
                            android.opengl.EGL14.eglMakeCurrent(
                                    SharedEglRoot.getDisplay(),
                                    android.opengl.EGL14.EGL_NO_SURFACE,
                                    android.opengl.EGL14.EGL_NO_SURFACE,
                                    android.opengl.EGL14.EGL_NO_CONTEXT);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "runOnNamaGlSync fallback", t);
        }
    }

    private void applyBeautyPanelFilter(String filterKey) {
        if (filterKey == null || filterKey.isEmpty()) {
            return;
        }
        final String key = filterKey;
        // 先同步写滤镜名/强度，再重绘暂停帧，避免 requestRender 抢在 setParam 之前
        NamaRenderLock.runExclusive(() -> {
            int handle = resolvePanelBeautyHandle();
            if (handle <= 0) {
                return;
            }
            try {
                BeautyParamApplier.setString(handle, "filter_name", key);
                double level = -1;
                if (beautyPanelView != null) {
                    level = beautyPanelView.peekSdkParamValue("filter_level");
                }
                if (level < 0) {
                    level = 0.4;
                }
                if ("origin".equalsIgnoreCase(key) || "filter_origin".equalsIgnoreCase(key)) {
                    level = 0;
                }
                BeautyParamApplier.setDouble(handle, "filter_level", level);
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
            int handle = mediaBeautyItemHandle > 0 ? mediaBeautyItemHandle : FuBeautyHandle.mediaHandle;
            if (handle <= 0) {
                handle = beautyItemHandle > 0 ? beautyItemHandle : FuBeautyHandle.cameraHandle;
            }
            if (handle <= 0) {
                callback.invoke(fail("请先 loadBundle"));
                return;
            }
            if (mediaBeautyItemHandle <= 0 && handle > 0) {
                mediaBeautyItemHandle = handle;
                FuBeautyHandle.setPipelineHandle(true, handle);
            }
            Activity activity = resolveHostActivity();
            File cacheDir = null;
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
            FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(dpToPx(64), dpToPx(64));
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
            view.bindLayoutSize(dpToPx(64), dpToPx(64));
            overlayCameraView = view;
            overlayCameraHost = host;
            cameraHostedByComponent = false;
            // 不打开摄像头，只保留 EGL 供视频帧 Nama
            view.hidePreview();
            host.setAlpha(0f);
            host.setEnabled(false);
            view.setAlpha(0f);
            view.armVideoStillMatrix();
            Log.i(TAG, "ensureParkedNamaGlForMedia created");
        } catch (Throwable t) {
            Log.w(TAG, "ensureParkedNamaGlForMedia", t);
        }
    }

    private void mountVideoOverlay(Activity activity, JSONObject options, UniJSCallback callback) {
        try {
            int beautyHandle = mediaBeautyItemHandle > 0 ? mediaBeautyItemHandle : FuBeautyHandle.mediaHandle;
            if (beautyHandle <= 0) {
                beautyHandle = beautyItemHandle > 0 ? beautyItemHandle : FuBeautyHandle.cameraHandle;
            }
            if (beautyHandle <= 0) {
                callback.invoke(fail("美颜 handle 无效，请先在首页/相机完成 init+loadBundle"));
                return;
            }
            if (mediaBeautyItemHandle <= 0) {
                mediaBeautyItemHandle = beautyHandle;
                FuBeautyHandle.setPipelineHandle(true, beautyHandle);
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
            MediaFuSetup.ensureBeautyOn(beautyHandle);
            MediaFuSetup.enableAdvancedBeautyRuntime(beautyHandle);
            try {
                faceunity.fuSetFaceProcessorDetectMode(1);
            } catch (Throwable ignored) {
            }
            // 视频美颜矩阵必须在相机 GL 线程用 StillLike；此处禁止 UI 线程写 identity
            ensureParkedNamaGlForMedia(activity);
            if (overlayCameraView != null) {
                overlayCameraView.armVideoStillMatrix();
                // 预热 GL 队列，避免首帧仍是原片
                try {
                    overlayCameraView.queueEvent(() -> {
                        try {
                            faceunity.fuOnCameraChange();
                            MediaFuSetup.applyStillLikeBufferMatrix();
                            faceunity.fuSetFaceProcessorDetectMode(0);
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
                }
            });
            overlayVideoPlayBtn = playBtn;

            // 先藏住，等首帧再显；默认暂停 + 中心播放钮（对齐 Demo）
            view.setVisibility(View.INVISIBLE);
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
            int handle = mediaBeautyItemHandle > 0 ? mediaBeautyItemHandle : FuBeautyHandle.mediaHandle;
            if (handle <= 0) {
                callback.invoke(fail("请先 loadBundle(media)"));
                return;
            }
            Activity activity = resolveHostActivity();
            // Demo 风格：全屏导出 loading，不黑屏拆会话
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
            File cacheDir = activity != null ? activity.getCacheDir() : null;
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
            Log.e(TAG, "processVideo", e);
            callback.invoke(fail(e.getMessage()));
        } finally {
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
        if (cameraHostedByComponent || overlayCameraHost == null) {
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
            destroyVideoPreviewInternal(false, () -> hideCameraInternal(false, () -> {
                try {
                    if (initialized) {
                        faceunity.fuDestroyAllItems();
                        faceunity.fuDestroyLibData();
                    }
                    initialized = false;
                    aiModelLoaded = false;
                    beautyItemHandle = 0;
                    mediaBeautyItemHandle = 0;
                    FuBeautyHandle.clearAll();
                    callback.invoke(success(0));
                } catch (Exception e) {
                    callback.invoke(fail(e.getMessage()));
                }
            }));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    private static int cssToPhysical(float density, int css) {
        return (int) (css * density + 0.5f);
    }

    private void configureFaceProcessor() {
        int level = MediaFuSetup.getDevicePerformanceLevel();
        faceunity.fuSetMaxFaces(level >= MediaFuSetup.PERF_EXCELLENT ? 4 : 2);
        faceunity.fuSetFaceProcessorDetectMode(1);
        faceunity.fuFaceProcessorSetMinFaceRatio(0.05f);
        try {
            faceunity.fuFaceProcessorSetFaceLandmarkQuality(level >= MediaFuSetup.PERF_HIGH ? 1 : 0);
            faceunity.fuFaceProcessorSetDetectSmallFace(level >= MediaFuSetup.PERF_HIGH ? 1 : 0);
        } catch (Throwable t) {
            Log.w(TAG, "configureFaceProcessor quality", t);
        }
        Log.e(TAG, "configureFaceProcessor ok maxFaces=" + (level >= MediaFuSetup.PERF_EXCELLENT ? 4 : 2)
                + " level=" + level);
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
        if (stopCamera && overlayCameraView != null) {
            // 默认只停采集、保住 EGL（静图 processOnGlView 仍依赖相机 GL）
            overlayCameraView.hidePreview();
            // 导入视频时卸顶，避免盖住视频叠层；勿 INVISIBLE/GONE（会 surfaceDestroyed → 回页黑屏）
            try {
                overlayCameraView.setZOrderOnTop(false);
                overlayCameraView.setZOrderMediaOverlay(false);
            } catch (Throwable ignored) {
            }
        }
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
                previewBox.setLayoutParams(boxLp);
            }
        }
        if (overlayCameraHost != null) {
            overlayCameraHost.setAlpha(0f);
            overlayCameraHost.setEnabled(false);
            overlayCameraHost.setVisibility(View.VISIBLE);
            if (overlayCameraView != null) {
                overlayCameraView.setVisibility(View.VISIBLE);
                overlayCameraView.setAlpha(0f);
            }
        } else if (overlayCameraView != null) {
            overlayCameraView.setTranslationX(4096f);
            overlayCameraView.setAlpha(0f);
            overlayCameraView.setVisibility(View.VISIBLE);
        }
        dismissPreviewChrome();
        dismissBeautyPanel();
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
            try {
                // 恢复盖在 WebView 上的取景叠层
                overlayCameraView.setZOrderMediaOverlay(true);
                overlayCameraView.setZOrderOnTop(true);
            } catch (Throwable ignored) {
            }
            // 仅视频交接后才 resumeGlAfterHandoff；soft-hide 只 resumePreview，避免无配对 onPause 的 onResume 黑屏
            if (sCameraGlHandedOff) {
                sCameraGlHandedOff = false;
                overlayCameraView.resumeGlAfterHandoff();
            } else {
                overlayCameraView.resumePreview();
            }
            try {
                if (previewChromeView != null) {
                    previewChromeView.setFocusExposure(overlayCameraView.getLastExposureUi());
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
            cameraHostedByComponent = false;
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        // 组件托管：只销毁预览资源，不 removeView（组件仍持有 host）
        if (cameraHostedByComponent && overlayCameraHost == null) {
            final BeautyCameraGLView view = overlayCameraView;
            final boolean keep = keepSession;
            view.setVisibility(View.VISIBLE);
            if (keep) {
                // 保留会话：仍须等本 View GL 释放完，避免与导入 processImage / 新相机抢 EGL
                view.destroyPreviewAsync(true, () -> {
                    resetOverlayLayoutCache();
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
                return;
            }
            view.destroyPreviewAsync(false, () -> {
                markNamaResourcesLost("hideCamera-hosted");
                resetOverlayLayoutCache();
                if (onComplete != null) {
                    onComplete.run();
                }
            });
            return;
        }
        final BeautyCameraGLView view = overlayCameraView;
        final FrameLayout host = overlayCameraHost;
        final boolean keep = keepSession;
        overlayCameraView = null;
        overlayCameraHost = null;
        cameraHostedByComponent = false;

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
        try {
            MediaGlContext.releaseAll();
        } catch (Throwable ignored) {
        }
        beautyItemHandle = 0;
        mediaBeautyItemHandle = 0;
        FuBeautyHandle.clearAll();
        aiModelLoaded = false;
        // 资源已 lost，后续静图可再走相机 GL / 新 offscreen，勿继续强制 avoid
        sAvoidCameraGlForImageAfterVideo = false;
        Log.e(TAG, "markNamaResourcesLost reason=" + reason);
    }

    /** 仅当静图 offscreen EGL 确实存在时释放并清 handle */
    private static void releaseMediaGlIfNeeded(String reason) {
        boolean lost;
        try {
            lost = MediaGlContext.releaseAll();
        } catch (Throwable t) {
            lost = false;
        }
        if (lost) {
            beautyItemHandle = 0;
            mediaBeautyItemHandle = 0;
            FuBeautyHandle.clearAll();
            aiModelLoaded = false;
            Log.e(TAG, "releaseMediaGlIfNeeded reason=" + reason);
        }
    }

    private static boolean isSpecialAlgoBeautyKey(String key) {
        return "body_blur_level".equals(key)
                || "delspot_level".equals(key)
                || "facial_plump".equals(key)
                || "intensity_eye_pupil".equals(key)
                || "enable_skinseg".equals(key);
    }

    private void resetOverlayLayoutCache() {
        lastCssX = -1;
        lastCssY = -1;
        lastCssW = -1;
        lastCssH = -1;
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
        if (!initialized) {
            throw new IllegalStateException("请先 init");
        }
        if (faceunity.fuIsLibraryInit() == 0) {
            throw new IllegalStateException("SDK 未就绪 fuIsLibraryInit=0，请重新 init");
        }
    }

    private byte[] readFileBytes(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new IOException("path 不能为空");
        }
        String realPath = path.startsWith("file://") ? path.substring(7) : path;
        File file = new File(realPath);
        if (!file.exists()) {
            throw new IOException("文件不存在: " + realPath);
        }
        FileInputStream in = new FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        int read = in.read(data);
        in.close();
        if (read <= 0) {
            throw new IOException("读取失败: " + realPath);
        }
        return data;
    }

    private JSONObject success(Object data) {
        JSONObject ret = new JSONObject();
        ret.put("code", 0);
        ret.put("data", data);
        return ret;
    }

    private JSONObject fail(String message) {
        return fail(message, null);
    }

    private JSONObject fail(String message, JSONObject diag) {
        JSONObject ret = new JSONObject();
        ret.put("code", -1);
        ret.put("message", message != null ? message : "unknown error");
        if (diag != null) {
            ret.put("data", diag);
        }
        return ret;
    }
}
