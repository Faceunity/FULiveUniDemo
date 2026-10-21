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
    private static final int REQ_PICK_MEDIA = 0x4D01;

    private UniJSCallback pickMediaCallback;
    private String pickMediaExt = ".jpg";

    private static int beautyItemHandle = 0;
    private static int mediaBeautyItemHandle = 0;
    private static boolean initialized = false;
    private static boolean aiModelLoaded = false;
    private static ICameraOverlay overlayCameraView;
    private static FrameLayout overlayCameraHost;
    /** 视频预览过后：静图勿再走相机 GL，避免矩阵残留颠倒；纯导入图片仍走相机 GL（已验证正常） */
    private static volatile boolean sAvoidCameraGlForImageAfterVideo = false;
    /** 仅视频交还相机时为 true；soft-hide 不要走 resumeGlAfterHandoff（成对 onPause 缺失会黑屏） */
    private static volatile boolean sCameraGlHandedOff = false;
    /** true：相机由 nvue &lt;beauty-camera&gt; 托管，不挂 decor 叠层 */
    private static volatile boolean cameraHostedByComponent = false;
    private static VideoRendererGLView overlayVideoView;
    private static FrameLayout overlayVideoHost;
    private static ImageView overlayVideoPlayBtn;
    private static String lastVideoPath = null;
    private static PhotoRendererGLView overlayImageView;
    private static FrameLayout overlayImageHost;
    private static String lastImagePath = null;
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
    /** SDK DEBUG 文件日志路径（nama_log_boot.log） */
    private static volatile String sNamaBootLogPath;

    /** nvue 原生相机组件创建 GL 后注册 */
    public static void attachHostedCameraView(ICameraOverlay view) {
        if (view == null) {
            return;
        }
        // 若仍有旧 decor 叠层，先拆掉，避免双预览
        if (overlayCameraHost != null) {
            try {
                final FrameLayout host = overlayCameraHost;
                final ICameraOverlay old = overlayCameraView;
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
    public static void detachHostedCameraView(ICameraOverlay view) {
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

    /** 切滤镜：画面正中短暂显示滤镜名（对齐 Demo） */
    static void showFilterNameTip(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        sTipsHandler.post(() -> {
            // 走全屏 tips overlay（elevation 200，高于 chrome/面板），避免被展开的美颜面板盖住
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

    /** JS 侧切滤镜时调用：画面显示滤镜名（媒体页 JS 面板/相机页共用） */
    @UniJSMethod(uiThread = true)
    public void showFilterNameTip(JSONObject options, UniJSCallback callback) {
        try {
            String name = options != null ? options.getString("name") : null;
            showFilterNameTip(name);
            if (callback != null) {
                callback.invoke(success(0));
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.invoke(fail(e.getMessage()));
            }
        }
    }

    private static Activity resolveStaticActivity() {
        try {
            if (overlayCameraView != null && overlayCameraView.asView().getContext() instanceof Activity) {
                return (Activity) overlayCameraView.asView().getContext();
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
                sFilterTip.setTextSize(32);
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
        if (callback == null) {
            return;
        }
        try {
            String text = MediaFuSetup.drainSdkFileLog();
            callback.invoke(success(text != null ? text : ""));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = false)
    public void getNamaSdkLogPath(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            String path = sNamaBootLogPath;
            if (path == null || path.isEmpty()) {
                path = MediaFuSetup.getSdkLogPath();
            }
            File f = path != null ? new File(path) : null;
            JSONObject data = new JSONObject();
            data.put("path", path != null ? path : "");
            data.put("exists", f != null && f.exists());
            data.put("size", f != null && f.exists() ? f.length() : 0L);
            callback.invoke(success(data));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /**
     * 系统分享面板发送 nama_log_boot.log（可选钉钉 / 微信等）。
     */
    @UniJSMethod(uiThread = true)
    public void shareNamaSdkLog(UniJSCallback callback) {
        Activity activity = resolveHostActivity();
        if (activity == null) {
            if (callback != null) {
                callback.invoke(fail("无 Activity"));
            }
            return;
        }
        String path = sNamaBootLogPath;
        if (path == null || path.isEmpty()) {
            path = MediaFuSetup.getSdkLogPath();
        }
        File logFile = path != null ? new File(path) : null;
        if (logFile == null || !logFile.exists() || logFile.length() <= 0) {
            if (callback != null) {
                callback.invoke(fail("暂无日志文件，请先进入美颜页初始化 SDK"));
            }
            return;
        }
        try {
            // 拷到 cache，便于 FileProvider 分享
            File cacheDir = activity.getExternalCacheDir();
            if (cacheDir == null) {
                cacheDir = activity.getCacheDir();
            }
            if (!cacheDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
            }
            File shareFile = new File(cacheDir, "nama_log_boot.log");
            copyFile(logFile, shareFile);
            // 若 SDK 文件几乎只有头：再拼一份 logcat 相关行，避免分享空壳
            appendLogcatFallback(shareFile);
            Uri uri = uriForShareFile(activity, shareFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            // 必须按文件分享：不要 text/plain + EXTRA_TEXT，否则钉钉只会收到一行字
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, "nama_log_boot.log");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= 16) {
                intent.setClipData(android.content.ClipData.newUri(
                        activity.getContentResolver(), "nama_log_boot.log", uri));
            }
            Intent chooser = Intent.createChooser(intent, "分享 SDK 日志文件到钉钉等");
            java.util.List<android.content.pm.ResolveInfo> resInfoList =
                    activity.getPackageManager().queryIntentActivities(chooser, 0);
            for (android.content.pm.ResolveInfo resolveInfo : resInfoList) {
                activity.grantUriPermission(
                        resolveInfo.activityInfo.packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            }
            activity.startActivity(chooser);
            JSONObject data = new JSONObject();
            data.put("path", shareFile.getAbsolutePath());
            data.put("size", shareFile.length());
            if (callback != null) {
                callback.invoke(success(data));
            }
        } catch (Exception e) {
            Log.e(TAG, "shareNamaSdkLog", e);
            if (callback != null) {
                callback.invoke(fail(e.getMessage() != null ? e.getMessage() : "分享失败"));
            }
        }
    }

    private static Uri uriForShareFile(Activity activity, File file) throws Exception {
        String pkg = activity.getPackageName();
        String[] authorities = {
                pkg + ".dc.fileprovider",
                pkg + ".fileprovider",
                "io.dcloud.PandoraEntry.fileprovider"
        };
        for (String auth : authorities) {
            try {
                Class<?> clazz = Class.forName("androidx.core.content.FileProvider");
                java.lang.reflect.Method m = clazz.getMethod(
                        "getUriForFile",
                        android.content.Context.class,
                        String.class,
                        File.class
                );
                Object uri = m.invoke(null, activity, auth, file);
                if (uri instanceof Uri) {
                    return (Uri) uri;
                }
            } catch (Throwable ignored) {
            }
        }
        // 兜底：旧系统 file://（Android 7+ 可能被拒，尽量走 FileProvider）
        return Uri.fromFile(file);
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    /** 文件过小时附加 logcat（FaceUnity/Nama 相关），保证分享出去有内容 */
    private static void appendLogcatFallback(File shareFile) {
        if (shareFile == null) {
            return;
        }
        if (shareFile.length() > 8 * 1024) {
            return;
        }
        Process process = null;
        try (java.io.FileWriter fw = new java.io.FileWriter(shareFile, true);
             java.io.BufferedReader reader = new java.io.BufferedReader(
                     new java.io.InputStreamReader(
                             (process = Runtime.getRuntime().exec(new String[]{
                                     "logcat", "-d", "-v", "time",
                                     "-t", "2000"
                             })).getInputStream()
                     ))) {
            fw.write("\n=== logcat fallback (SDK file was small) ===\n");
            String line;
            int kept = 0;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase();
                if (lower.contains("faceunity")
                        || lower.contains("nama")
                        || lower.contains("fu-")
                        || lower.contains("beautycamera")
                        || lower.contains("videogpu")
                        || lower.contains("videoexport")) {
                    fw.write(line);
                    fw.write('\n');
                    kept++;
                    if (kept > 800) {
                        break;
                    }
                }
            }
            fw.write("=== logcat kept lines=" + kept + " ===\n");
            fw.flush();
        } catch (Throwable t) {
            Log.w(TAG, "appendLogcatFallback", t);
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @UniJSMethod(uiThread = true)
    public void getVersion(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.invoke(success("official-kit"));
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
            Activity act = resolveHostActivity();
            if (act == null) {
                callback.invoke(fail("init 需要 Activity 上下文"));
                return;
            }
            String initMsg = FuKitBridge.initSdk(act);
            initialized = true;
            JSONObject diag = new JSONObject();
            diag.put("kit", true);
            diag.put("initMsg", initMsg);
            MediaFuSetup.setAppContext(act);
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
            FuKitBridge.loadAiFaceProcessor();
            aiModelLoaded = true;
            JSONObject dataOut = new JSONObject();
            dataOut.put("handle", 1);
            dataOut.put("embeddedAi", true);
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
            String pipeline = options != null ? options.getString("pipeline") : null;
            boolean media = pipeline != null && "media".equalsIgnoreCase(pipeline);
            boolean officialImage = media && options != null
                    && options.getBooleanValue("officialImage");
            if (!media) {
                FuKitBridge.loadAiFaceProcessor();
                int handle = FuKitBridge.ensureBeautyKitBound();
                FuKitBridge.applyDefaultBeautyParams();
                beautyItemHandle = handle;
                JSONObject dataOut = new JSONObject();
                dataOut.put("handle", handle);
                dataOut.put("pipeline", "camera");
                dataOut.put("kit", true);
                dataOut.put("beautyBundlePath", FuKitBridge.beautyBundlePath());
                dataOut.put("beautyBundlePresent", FuKitBridge.isBeautyBundlePresent());
                dataOut.put("beautyBundleBytes", FuKitBridge.beautyBundleBytes());
                callback.invoke(success(dataOut));
                return;
            }
            String assetPath = options != null ? options.getString("asset") : null;
            // Android 媒体统一使用官方 AAR：图片由 PhotoRenderer，视频由 VideoRenderer。
            // 不再创建旧 Nama media item；返回的 handle 只是兼容现有 JS 参数接口的占位值。
            if (media) {
                Log.i(TAG, "loadBundle official media: skip legacy media item image=" + officialImage);
                if (officialImage) {
                    FuKitBridge.preparePhotoRenderer();
                } else {
                    // VideoRenderer 尚未创建 GL Surface；绑定和参数应用延迟到
                    // VideoRenderer.onSurfaceCreated，避免桥接线程绑定到错误 EGL 上下文。
                    FuKitBridge.prepareVideoPipeline();
                }
                FuKitBridge.applyDefaultBeautyParams();
                mediaBeautyItemHandle = FuKitBridge.placeholderBeautyHandle();
                JSONObject dataOut = new JSONObject();
                dataOut.put("handle", FuKitBridge.placeholderBeautyHandle());
                dataOut.put("pipeline", "media");
                dataOut.put("kit", true);
                dataOut.put("officialImage", officialImage);
                dataOut.put("asset", assetPath);
                dataOut.put("offline", true);
                callback.invoke(success(dataOut));
                return;
            }
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /** 保留旧 JS 方法名；Android 官方 Kit 不复用旧 Nama media handle。 */
    @UniJSMethod(uiThread = false)
    public void bindMediaBeautyHandle(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            ensureInitialized();
            mediaBeautyItemHandle = FuKitBridge.placeholderBeautyHandle();
            JSONObject data = new JSONObject();
            data.put("handle", mediaBeautyItemHandle);
            data.put("mediaHandle", mediaBeautyItemHandle);
            data.put("cameraHandle", beautyItemHandle);
            data.put("kit", true);
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
            if (options == null) {
                callback.invoke(fail("参数不能为空"));
                return;
            }
            String pipeline = options.getString("pipeline");
            boolean mediaPipe = pipeline != null && "media".equalsIgnoreCase(pipeline);
            int handle = options.getIntValue("handle");
            if (handle <= 0) {
                handle = mediaPipe ? mediaBeautyItemHandle : beautyItemHandle;
            }
            if (handle <= 0) {
                callback.invoke(fail("请先 loadBundle"));
                return;
            }
            final String key = options.getString("key");
            final String stringValue = options.getString("stringValue");
            final double numericValue = options.getDoubleValue("value");
            final int[] codeBox = {-1};
            Runnable apply = () -> {
                if (stringValue != null) {
                    if ("filter_name".equals(key)) {
                        codeBox[0] = FuKitBridge.setFilter(
                                stringValue, numericValue > 0 ? numericValue : 0.5);
                    }
                } else {
                    codeBox[0] = FuKitBridge.setParamIntensity(key, numericValue);
                }
            };
            if (mediaPipe && overlayImageView != null) {
                runOnImageGlSync(apply);
            } else if (mediaPipe && overlayVideoView != null) {
                // 暂停冻结帧时 VideoRenderer 的 GL 线程阻塞在 SurfaceTexture.updateTexImage()
                // 等新帧；queueEvent 到该线程再同步等待会超时丢参（get==set 但画面无效果）。
                // FuKitBridge 写的是 FaceBeauty 模型，官方 Demo 在任意线程直接改模型，
                // 播放恢复时下一帧即生效，无需排队到 GL 线程。
                NamaRenderLock.runExclusive(apply);
            } else if (!mediaPipe) {
                runOnNamaGlSync(apply);
            } else {
                // 视频 Surface 尚未创建时，FuKitBridge 会缓存参数，首帧创建后再写入。
                NamaRenderLock.runExclusive(apply);
            }
            JSONObject dataOut = new JSONObject();
            dataOut.put("ret", codeBox[0]);
            dataOut.put("kit", true);
            dataOut.put("handle", handle > 0 ? handle : FuKitBridge.placeholderBeautyHandle());
            dataOut.put("key", key);
            if (stringValue == null) {
                dataOut.put("set", numericValue);
            }
            callback.invoke(success(dataOut));
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
                data.put("cameraError", ICameraOverlay.lastError());
                data.put("diag", ICameraOverlay.previewDiag());
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
                    callback.invoke(fail("activity null"));
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
                                    overlayCameraView.setFixedSurfaceSize(aw, ah);
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
                reused.put("cameraError", ICameraOverlay.lastError());
                reused.put("diag", ICameraOverlay.previewDiag());
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
            hideCameraInternal(true, () -> mountCameraOverlay(activity, opts, cb));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
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

            ICameraOverlay view = ICameraOverlay.create(activity);
            previewBox.addView(view.asView(), new FrameLayout.LayoutParams(
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
            data.put("cameraError", ICameraOverlay.lastError());
            data.put("diag", ICameraOverlay.previewDiag());
            callback.invoke(success(data));

            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.e(TAG, "previewDiag " + ICameraOverlay.previewDiag()
                            + " view=" + view.asView().getWidth() + "x" + view.asView().getHeight()
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
            callback.invoke(success(data));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /** Android 重进时查询 SDK/宿主是否仍存活：libInit/handles/contextAlive 供 JS ensureAndroidNamaAlive 判断。 */
    @UniJSMethod(uiThread = false)
    public void isSdkAlive(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("libInit", initialized ? 1 : 0);
            data.put("initialized", initialized);
            data.put("aiLoaded", aiModelLoaded);
            data.put("cameraHandle", beautyItemHandle);
            data.put("mediaHandle", mediaBeautyItemHandle);
            boolean ctxAlive = false;
            try {
                Activity host = resolveHostActivity();
                ctxAlive = host != null && !host.isFinishing() && !host.isDestroyed();
            } catch (Throwable ignored) {
            }
            data.put("contextAlive", ctxAlive);
            callback.invoke(success(data));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /** Activity 被重建后清理 stale overlay 引用，让后续 showCamera/showVideo 能重建。 */
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
        if (overlayCameraView != null) {
            try {
                overlayCameraView.destroyPreviewAsync(false, null);
            } catch (Throwable ignored) {
            }
            overlayCameraView = null;
            overlayCameraHost = null;
        }
        if (overlayVideoView != null) {
            try {
                overlayVideoView.destroyPreviewAsync(false, null);
            } catch (Throwable ignored) {
            }
            overlayVideoView = null;
            overlayVideoHost = null;
        }
        if (overlayImageView != null) {
            try {
                overlayImageView.destroyPreviewAsync(false, null);
            } catch (Throwable ignored) {
            }
            overlayImageView = null;
            overlayImageHost = null;
        }
        try {
            if (previewChromePopup != null && previewChromePopup.isShowing()) {
                previewChromePopup.dismiss();
            }
        } catch (Throwable ignored) {
        }
        previewChromeView = null;
        previewChromePopup = null;
        try {
            if (beautyPanelPopup != null && beautyPanelPopup.isShowing()) {
                beautyPanelPopup.dismiss();
            }
        } catch (Throwable ignored) {
        }
        beautyPanelView = null;
        beautyPanelPopup = null;
        dismissFocusHud();
        focusHudView = null;
        focusHudPopup = null;
        sCameraGlHandedOff = false;
        sAvoidCameraGlForImageAfterVideo = false;
    }

    @UniJSMethod(uiThread = false)
    public void getPreviewDiag(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        JSONObject data = new JSONObject();
        data.put("diag", ICameraOverlay.previewDiag());
        data.put("mounted", overlayCameraView != null);
        data.put("stats", ICameraOverlay.previewStats());
        callback.invoke(success(data));
    }

    @UniJSMethod(uiThread = false)
    public void getPreviewStats(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        callback.invoke(success(ICameraOverlay.previewStats()));
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
                ICameraOverlay.setBeautyEnabledGlobal(enabled);
                if (overlayCameraView != null) {
                    overlayCameraView.setBeautyEnabled(enabled);
                    overlayCameraView.requestRender();
                }
            }
            if (!cameraOnly) {
                VideoRendererGLView.setBeautyEnabledGlobal(enabled);
                if (overlayVideoView != null) {
                    overlayVideoView.setBeautyEnabled(enabled);
                    overlayVideoView.requestRender();
                }
                PhotoRendererGLView.setBeautyEnabledGlobal(enabled);
                if (overlayImageView != null) {
                    overlayImageView.setBeautyEnabled(enabled);
                    overlayImageView.requestRender();
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
            if (options != null) {
                beautyPanelView.applyConfig(options);
            }
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
                    Runnable kitApply = () -> {
                        double color = beautyPanelView != null
                                ? beautyPanelView.peekSdkParamValue("color_level_mode2") : -1;
                        if (color < 0 && beautyPanelView != null) {
                            color = beautyPanelView.peekSdkParamValue("color_level");
                        }
                        if (color < 0) {
                            color = 0.4;
                        }
                        FuKitBridge.setParamIntensity("enable_skinseg", skinseg);
                        FuKitBridge.setParamIntensity("color_level", color);
                    };
                    if (overlayImageView != null) {
                        runOnImageGlSync(kitApply);
                    } else if (overlayVideoView != null) {
                        runOnVideoGlSync(kitApply);
                    } else if (overlayCameraView != null) {
                        overlayCameraView.queueGlEvent(kitApply);
                        overlayCameraView.requestRender();
                    } else {
                        NamaRenderLock.runExclusive(kitApply);
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
                                    applyKitBeautyPanelTabDefaults(tab);
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
                    ICameraOverlay.setBeautyEnabledGlobal(false);
                    VideoRendererGLView.setBeautyEnabledGlobal(false);
                    PhotoRendererGLView.setBeautyEnabledGlobal(false);
                    if (overlayCameraView != null) {
                        overlayCameraView.setBeautyEnabled(false);
                    }
                    if (overlayVideoView != null) {
                        overlayVideoView.setBeautyEnabled(false);
                    }
                    if (overlayImageView != null) {
                        overlayImageView.setBeautyEnabled(false);
                    }
                    fireBeautyPanelEvent("compareStart", null);
                }

                @Override
                public void onCompareEnd() {
                    ICameraOverlay.setBeautyEnabledGlobal(true);
                    VideoRendererGLView.setBeautyEnabledGlobal(true);
                    PhotoRendererGLView.setBeautyEnabledGlobal(true);
                    if (overlayCameraView != null) {
                        overlayCameraView.setBeautyEnabled(true);
                    }
                    if (overlayVideoView != null) {
                        overlayVideoView.setBeautyEnabled(true);
                    }
                    if (overlayImageView != null) {
                        overlayImageView.setBeautyEnabled(true);
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
        Runnable apply = () -> {
            try {
                FuKitBridge.setParamIntensity(key, value);
            } catch (Throwable t) {
                Log.w(TAG, "applyBeautyPanelSdkParam " + key, t);
            }
        };
        if (overlayImageView != null) {
            runOnImageGlSync(apply);
        } else if (overlayVideoView != null) {
            runOnVideoGlSync(apply);
        } else if (overlayCameraView != null) {
            overlayCameraView.queueGlEvent(apply);
            overlayCameraView.requestRender();
        } else {
            NamaRenderLock.runExclusive(apply);
        }
    }

    /** 官方 Kit：重置后在相机 GL 队列一次性写入整组默认值，避免逐项异步写入残留旧效果。 */
    private void applyKitBeautyPanelTabDefaults(String tab) {
        if (beautyPanelView == null
                || !FuKitBridge.isKitBeautyHandle(resolvePanelBeautyHandle())) {
            return;
        }
        final String targetTab = "shape".equals(tab) ? "shape" : "skin";
        final JSONObject defaults = beautyPanelView.getTabDefaultSdkValues(targetTab);
        Runnable apply = () -> {
            try {
                FuKitBridge.setParamIntensity("is_beauty_on", 1.0);
                if ("skin".equals(targetTab)) {
                    // 与 Demo 的 FaceBeauty 默认值一致：FineSkin、非朦胧、全局美白。
                    FuKitBridge.setParamIntensity("blur_type", 2.0);
                    FuKitBridge.setParamIntensity("heavy_blur", 0.0);
                    FuKitBridge.setParamIntensity("skin_detect", 0.0);
                    FuKitBridge.setParamIntensity("enable_skinseg", 0.0);
                }
                for (String key : defaults.keySet()) {
                    FuKitBridge.setParamIntensity(key, defaults.getDoubleValue(key));
                }
            } catch (Throwable t) {
                Log.w(TAG, "applyKitBeautyPanelTabDefaults " + targetTab, t);
            }
        };
        if (overlayImageView != null) {
            runOnImageGlSync(apply);
        } else if (overlayVideoView != null) {
            runOnVideoGlSync(apply);
        } else if (overlayCameraView != null) {
            overlayCameraView.queueGlEvent(() -> {
                try {
                    NamaRenderLock.runExclusive(apply);
                } catch (Throwable t) {
                    Log.w(TAG, "applyKitBeautyPanelTabDefaults gl", t);
                }
            });
            overlayCameraView.setBeautyEnabled(true);
            overlayCameraView.requestRender();
        } else {
            apply.run();
        }
    }

    private int resolvePanelBeautyHandle() {
        // 导入图片/视频叠层：优先 media handle，避免写到相机道具而预览仍用 media
        if (overlayImageView != null || overlayImageHost != null
                || overlayVideoView != null || overlayVideoHost != null) {
            int media = mediaBeautyItemHandle;
            if (media > 0) {
                return media;
            }
        }
        int handle = beautyItemHandle;
        if (handle <= 0) {
            handle = mediaBeautyItemHandle;
        }
        return handle;
    }

    private boolean applyOnImagePreviewIfNeeded(Runnable apply) {
        PhotoRendererGLView image = overlayImageView;
        if (image == null || apply == null) {
            return false;
        }
        try {
            image.queueEvent(() -> {
                try {
                    NamaRenderLock.runExclusive(apply);
                } catch (Throwable t) {
                    Log.w(TAG, "applyOnImagePreviewIfNeeded", t);
                }
            });
            image.redrawBeautyFrame();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "applyOnImagePreviewIfNeeded queue", t);
            return false;
        }
    }

    private void refreshImageBeautyIfNeeded() {
        PhotoRendererGLView image = overlayImageView;
        if (image == null) {
            return;
        }
        try {
            image.redrawBeautyFrame();
        } catch (Throwable t) {
            Log.w(TAG, "refreshImageBeautyIfNeeded", t);
        }
    }

    /** 对齐 iOS：视频暂停时调参/切滤镜强制重绘当前帧 */
    private void refreshPausedVideoBeautyIfNeeded() {
        VideoRendererGLView video = overlayVideoView;
        if (video == null || video.isPlaying()) {
            return;
        }
        try {
            video.redrawBeautyFrame();
        } catch (Throwable t) {
            Log.w(TAG, "refreshPausedVideoBeautyIfNeeded", t);
        }
    }

    /** 官方 VideoRenderer 的参数写入必须和当前视频渲染串行，并立即补一帧。 */
    private void runOnVideoGlSync(Runnable action) {
        if (action == null) {
            return;
        }
        VideoRendererGLView video = overlayVideoView;
        if (video == null) {
            action.run();
            return;
        }
        final Object waitLock = new Object();
        final boolean[] done = {false};
        try {
            video.queueEvent(() -> {
                try {
                    NamaRenderLock.runExclusive(action);
                } catch (Throwable t) {
                    Log.w(TAG, "runOnVideoGlSync", t);
                } finally {
                    synchronized (waitLock) {
                        done[0] = true;
                        waitLock.notifyAll();
                    }
                }
            });
            video.requestRender();
            long deadline = System.currentTimeMillis() + 3000L;
            synchronized (waitLock) {
                while (!done[0] && System.currentTimeMillis() < deadline) {
                    try {
                        waitLock.wait(16L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (!done[0]) {
                Log.w(TAG, "runOnVideoGlSync timeout");
            }
        } catch (Throwable t) {
            Log.w(TAG, "runOnVideoGlSync queue", t);
            action.run();
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
        ICameraOverlay cam = overlayCameraView;
        if (cam != null) {
            try {
                cam.queueGlEvent(() -> {
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
        ICameraOverlay overlay = overlayCameraView;
        if (overlay != null) {
            final Object waitLock = new Object();
            final boolean[] done = { false };
            try {
                // 官方 CameraRenderer 的 FaceBeauty 参数必须在相机 GL 队列写入。
                overlay.queueGlEvent(() -> {
                    try {
                        NamaRenderLock.runExclusive(action);
                    } catch (Throwable t) {
                        Log.w(TAG, "runOnNamaGlSync kit gl", t);
                    } finally {
                        synchronized (waitLock) {
                            done[0] = true;
                            waitLock.notifyAll();
                        }
                    }
                });
                overlay.requestRender();
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
                if (done[0]) {
                    return;
                }
                Log.w(TAG, "runOnNamaGlSync kit gl timeout");
            } catch (Throwable t) {
                Log.w(TAG, "runOnNamaGlSync kit queue", t);
            }
        }
        // 没有相机 Surface 时仅在锁内执行，官方视频/图片链路会自行使用各自 GL 队列。
        try {
            NamaRenderLock.runExclusive(action);
        } catch (Throwable t) {
            Log.w(TAG, "runOnNamaGlSync fallback", t);
        }
    }

    /**
     * 静图 Texture 预览与参数写入共用同一条 GL 线程。
     * 连续预览时如果 JS/桥接线程直接写 Nama，会与 fuRenderDualInput 并发，
     * 表现为参数返回成功但画面恢复原图或效果消失。
     */
    private void runOnImageGlSync(Runnable action) {
        if (action == null) {
            return;
        }
        PhotoRendererGLView image = overlayImageView;
        if (image == null) {
            NamaRenderLock.runExclusive(action);
            return;
        }
        final Object waitLock = new Object();
        final boolean[] done = {false};
        try {
            image.queueEvent(() -> {
                try {
                    NamaRenderLock.runExclusive(action);
                } catch (Throwable t) {
                    Log.w(TAG, "runOnImageGlSync", t);
                } finally {
                    synchronized (waitLock) {
                        done[0] = true;
                        waitLock.notifyAll();
                    }
                }
            });
            image.requestRender();
            long deadline = System.currentTimeMillis() + 3000L;
            synchronized (waitLock) {
                while (!done[0] && System.currentTimeMillis() < deadline) {
                    try {
                        waitLock.wait(16L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (!done[0]) {
                Log.w(TAG, "runOnImageGlSync timeout");
            }
        } catch (Throwable t) {
            Log.w(TAG, "runOnImageGlSync queue", t);
            NamaRenderLock.runExclusive(action);
        }
    }

    private void applyBeautyPanelFilter(String filterKey) {
        if (filterKey == null || filterKey.isEmpty()) {
            return;
        }
        final String key = filterKey;
        Runnable apply = () -> {
            double level = beautyPanelView != null
                    ? beautyPanelView.peekFilterSdkIntensity(key) : 0.4;
            if ("origin".equalsIgnoreCase(key) || "filter_origin".equalsIgnoreCase(key)) {
                level = 0;
            }
            FuKitBridge.setFilter(key, level);
        };
        if (overlayImageView != null) {
            runOnImageGlSync(apply);
        } else if (overlayVideoView != null) {
            runOnVideoGlSync(apply);
        } else if (overlayCameraView != null) {
            overlayCameraView.queueGlEvent(apply);
            overlayCameraView.requestRender();
        } else {
            NamaRenderLock.runExclusive(apply);
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
                    boolean started = false;
                    String errMsg = null;
                    try {
                        if (overlayCameraView == null) {
                            errMsg = "相机未挂载";
                        } else {
                            overlayCameraView.startVideoRecord();
                            started = true;
                        }
                    } catch (Exception e) {
                        errMsg = e.getMessage() != null ? e.getMessage() : "开始录制失败";
                    }
                    java.util.Map<String, Object> extra = new java.util.HashMap<>();
                    extra.put("ok", started);
                    extra.put("message", errMsg != null ? errMsg : "");
                    firePreviewChromeEvent("captureLongPress", extra);
                }

                @Override
                public void onCaptureTouchUp() {
                    boolean wasLongPress = previewChromeView != null && previewChromeView.wasLongPress();
                    if (wasLongPress && overlayCameraView != null) {
                        Activity activity = resolveHostActivity();
                        if (activity != null) {
                            overlayCameraView.stopVideoRecord(activity, new CameraCaptureCallback() {
                                @Override
                                public void onSuccess(String path) {
                                    if (previewChromeView != null) {
                                        previewChromeView.setRecording(false);
                                    }
                                    java.util.Map<String, Object> stopped = new java.util.HashMap<>();
                                    stopped.put("path", path != null ? path : "");
                                    stopped.put("ok", path != null && !path.isEmpty());
                                    stopped.put("message", "");
                                    firePreviewChromeEvent("recordStopped", stopped);
                                }

                                @Override
                                public void onError(String message) {
                                    if (previewChromeView != null) {
                                        previewChromeView.setRecording(false);
                                    }
                                    java.util.Map<String, Object> stopped = new java.util.HashMap<>();
                                    stopped.put("path", "");
                                    stopped.put("ok", false);
                                    stopped.put("message", message != null ? message : "");
                                    firePreviewChromeEvent("recordStopped", stopped);
                                }
                            });
                        }
                    }
                    java.util.Map<String, Object> extra = new java.util.HashMap<>();
                    extra.put("longPress", wasLongPress);
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
                    // 对齐 Demo BaseFaceUnityActivity：原生直接 changeResolution 一次；
                    // firePreviewChromeEvent 仅同步 Vue 选中态，勿再调 setPreviewResolution。
                    applyPreviewResolutionById(id);
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
                Log.i(TAG, "onExposureChange[previewChrome] value=" + value + " finalize=" + finalizeLock);
                if (overlayCameraView != null) {
                    overlayCameraView.setExposureCompensation(value);
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
                Log.i(TAG, "onExposureChange[focusHud] value=" + value + " finalize=" + finalizeLock);
                if (overlayCameraView != null) {
                    overlayCameraView.setExposureCompensation(value);
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
            applyPreviewResolutionSize(width, height);
            callback.invoke(success(0));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /** 对齐官方 BaseFaceUnityActivity：480→640x480，720→1280x720，1080→1920x1080 */
    private void applyPreviewResolutionById(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        switch (id) {
            case "480":
                applyPreviewResolutionSize(640, 480);
                break;
            case "1080":
                applyPreviewResolutionSize(1920, 1080);
                break;
            case "720":
            default:
                applyPreviewResolutionSize(1280, 720);
                break;
        }
    }

    /**
     * 采集分辨率切换（Kit 相机路径）。
     * 对齐 Demo {@code FUCamera.changeResolution}：只重开相机，不 resumePreview / 不重绑 Kit。
     * 过渡帧丢弃见 {@link KitBeautyCameraGLView#setPreviewResolution}。
     */
    private void applyPreviewResolutionSize(int width, int height) {
        if (width <= 0 || height <= 0 || overlayCameraView == null) {
            return;
        }
        // 对齐 Demo：仅 FUCamera.changeResolution，不切 GL / 不重绑 Kit。
        overlayCameraView.setPreviewResolution(width, height);
    }

    /** 进相机页重置为 720，不跨页记忆 */
    @UniJSMethod(uiThread = true)
    public void resetPreviewResolution(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            applyPreviewResolutionById("720");
            callback.invoke(success(0));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    /**
     * 预览最高帧率（软件限帧，对齐官方 FUCameraConfig.cameraFPS）。
     * fps &gt; 0：上限帧率，默认 30；fps &lt;= 0：不限（demo 默认 -1）。
     * 下次 mount CameraRenderer 时生效（进相机页 / 重建 overlay）。
     */
    @UniJSMethod(uiThread = false)
    public void setPreviewMaxFps(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            int fps = options != null ? options.getIntValue("fps") : 30;
            ICameraOverlay.setMaxPreviewFps(fps);
            JSONObject data = new JSONObject();
            data.put("fps", ICameraOverlay.getMaxPreviewFps());
            callback.invoke(success(data));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = false)
    public void resetPreviewMaxFps(UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            ICameraOverlay.resetMaxPreviewFps();
            JSONObject data = new JSONObject();
            data.put("fps", ICameraOverlay.getMaxPreviewFps());
            callback.invoke(success(data));
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
            overlayCameraView.capturePhoto(activity, new CameraCaptureCallback() {
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
            overlayCameraView.stopVideoRecord(activity, new CameraCaptureCallback() {
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
        // 兼容运行时只注册旧方法名的基座：图片预览借用已存在的
        // processImage 入口转发到官方 PhotoRenderer，不生成静态结果图。
        if (options != null
                && options.getBooleanValue("preview")
                && "image".equalsIgnoreCase(options.getString("renderer"))) {
            final JSONObject previewOptions = options;
            new Handler(Looper.getMainLooper()).post(() -> showImagePreview(previewOptions, callback));
            return;
        }
        // 官方图片链路的保存直接读取 PhotoRenderer 当前输出帧；不再创建或调用旧 Nama item。
        if (FuKitBridge.isActive()) {
            final PhotoRendererGLView image = overlayImageView;
            Log.i(TAG, "processImage official capture overlay=" + (image != null));
            if (image == null) {
                callback.invoke(fail("图片预览尚未挂载"));
                return;
            }
            Activity activity = resolveHostActivity();
            if (activity == null) {
                callback.invoke(fail("activity null"));
                return;
            }
            File output = new File(activity.getCacheDir(),
                    "nama_image_export_" + System.currentTimeMillis() + ".jpg");
            image.captureCurrentFrame(output, new PhotoRendererGLView.CaptureCallback() {
                @Override
                public void onSuccess(String path) {
                    try {
                        JSONObject data = new JSONObject();
                        data.put("path", path);
                        callback.invoke(success(data));
                    } catch (Exception e) {
                        callback.invoke(fail(e.getMessage()));
                    }
                }

                @Override
                public void onError(Throwable error) {
                    callback.invoke(fail(error != null ? error.getMessage() : "图片保存失败"));
                }
            });
            return;
        }
        callback.invoke(fail("官方图片预览未挂载，无法保存当前帧"));
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
            destroyImagePreviewInternal(true, null);
            destroyVideoPreviewInternal(true, () ->
                    softHideCameraOverlay(() -> mountVideoOverlay(activity, opts, cb)));
        } catch (Exception e) {
            Log.e(TAG, "showVideoPreview failed", e);
            callback.invoke(fail(e.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void showImagePreview(JSONObject options, UniJSCallback callback) {
        if (callback == null) {
            return;
        }
        Log.i(TAG, "showImagePreview entered");
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
            Log.i(TAG, "showImagePreview path=" + path + " rect=" + x + "," + y
                    + " " + width + "x" + height + " mediaHandle=" + mediaBeautyItemHandle
                    + " kit=" + FuKitBridge.isActive());
            if (path == null || path.isEmpty()) {
                callback.invoke(fail("path 不能为空"));
                return;
            }
            if (width <= 0 || height <= 0) {
                callback.invoke(fail("width/height 无效"));
                return;
            }
            Activity activity = resolveHostActivity();
            if (activity == null) {
                callback.invoke(fail("activity null"));
                return;
            }
            final JSONObject opts = options;
            final UniJSCallback cb = callback;
            sCameraGlHandedOff = false;
            destroyImagePreviewInternal(true, () ->
                    destroyVideoPreviewInternal(true, () ->
                            softHideCameraOverlay(() -> mountImageOverlay(activity, opts, cb))));
        } catch (Exception e) {
            callback.invoke(fail(e.getMessage()));
        }
    }

    private void mountImageOverlay(Activity activity, JSONObject options, UniJSCallback callback) {
        try {
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
            Log.i(TAG, "mountImageOverlay native path=" + path + " pxRect=" + pxX + "," + pxY
                    + " " + pxW + "x" + pxH);

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

            PhotoRendererGLView view = new PhotoRendererGLView(activity);
            previewBox.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            view.bindLayoutSize(pxW, pxH);
            view.setOnFirstFrameListener(() -> {
                try {
                    if (overlayImageView == view) {
                        bringImageOverlayToFront();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "mountImageOverlay first frame", t);
                }
            });
            overlayImageView = view;
            overlayImageHost = host;
            lastImagePath = path;
            view.loadImage(path);
            bringImageOverlayToFront();
            Log.i(TAG, "mountImageOverlay PhotoRenderer loaded path=" + path);
            JSONObject data = new JSONObject();
            data.put("x", x);
            data.put("y", y);
            data.put("width", width);
            data.put("height", height);
            callback.invoke(success(data));
        } catch (Exception e) {
            Log.e(TAG, "mountImageOverlay", e);
            callback.invoke(fail(e.getMessage()));
        }
    }

    private void mountVideoOverlay(Activity activity, JSONObject options, UniJSCallback callback) {
        try {
            Log.i(TAG, "mountVideoOverlay stage=begin thread=" + Thread.currentThread().getName());
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

            VideoRendererGLView view = new VideoRendererGLView(activity);
            Log.i(TAG, "mountVideoOverlay stage=viewCreated");
            previewBox.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            view.bindLayoutSize(pxW, pxH);
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

            // SurfaceView 必须保持 VISIBLE 才会创建 Surface；首帧前用透明度隐藏，
            // 避免 INVISIBLE 导致 Android 根本不创建视频 GL Surface。
            view.setVisibility(View.VISIBLE);
            view.setAlpha(0f);
            playBtn.setVisibility(View.GONE);
            view.setOnFirstFrameListener(() -> {
                try {
                    if (overlayVideoView == view) {
                        view.setAlpha(1f);
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
            overlayVideoView = view;
            overlayVideoHost = host;
            lastVideoPath = path;
            view.loadVideo(path);
            Log.i(TAG, "mountVideoOverlay stage=videoLoaded");
            view.prepareFirstFrame();
            Log.i(TAG, "mountVideoOverlay stage=firstFrameRequested");

            // 视频完全由官方 VideoRenderer 接管，不再创建驻留旧 Nama GL 或视频 handle。
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
    public void redrawVideoPreview(UniJSCallback callback) {
        try {
            if (overlayVideoView != null) {
                overlayVideoView.redrawBeautyFrame();
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

    @UniJSMethod(uiThread = true)
    public void redrawImagePreview(UniJSCallback callback) {
        try {
            if (overlayImageView != null) {
                overlayImageView.redrawBeautyFrame();
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
    public void resumeImagePreview(UniJSCallback callback) {
        try {
            if (overlayImageView != null) {
                overlayImageView.resumePreview();
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
    public void destroyImagePreview(UniJSCallback callback) {
        try {
            destroyImagePreviewInternal(false, () -> {
                if (callback != null) {
                    callback.invoke(success(0));
                }
            });
        } catch (Exception e) {
            if (callback != null) {
                callback.invoke(fail(e.getMessage()));
            }
        }
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
            Activity activity = resolveHostActivity();
            final VideoRendererGLView video = overlayVideoView;
            if (video == null) {
                callback.invoke(fail("官方视频预览未挂载"));
                return;
            }
            // 官方 VideoRecordHelper 负责编码和原音频合并；预览仍由同一个 VideoRenderer 输出。
            exportHud = showExportLoadingHud(activity);
            final View hudRef = exportHud;
            video.exportVideo(path, new VideoRendererGLView.ExportCallback() {
                @Override
                public void onProgress(float ratio) {
                    if (hudRef instanceof FuExportProgressHud) {
                        ((FuExportProgressHud) hudRef).setProgress(ratio);
                    }
                }

                @Override
                public void onSuccess(String outPath) {
                    if (hudRef instanceof FuExportProgressHud) {
                        ((FuExportProgressHud) hudRef).setProgress(1f);
                    }
                    JSONObject data = new JSONObject();
                    data.put("path", outPath);
                    callback.invoke(success(data));
                    dismissExportLoadingHud(hudRef);
                }

                @Override
                public void onError(Throwable error) {
                    callback.invoke(fail(error != null ? error.getMessage() : "视频导出失败"));
                    dismissExportLoadingHud(hudRef);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "processVideo", e);
            callback.invoke(fail(e.getMessage()));
            dismissExportLoadingHud(exportHud);
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

    private void bringImageOverlayToFront() {
        if (overlayImageHost == null) {
            return;
        }
        ViewGroup decor = resolveOverlayRoot(resolveHostActivity());
        if (decor == null) {
            return;
        }
        if (overlayImageHost.getParent() != decor) {
            attachOverlayHostOnDecor(decor, overlayImageHost);
            return;
        }
        decor.bringChildToFront(overlayImageHost);
        overlayImageHost.requestLayout();
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
            FuKitBridge.finishVideoPipeline();
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
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        final VideoRendererGLView view = overlayVideoView;
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
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    private void destroyImagePreviewInternal(boolean keepSession, Runnable onComplete) {
        if (overlayImageView == null) {
            if (overlayImageHost != null) {
                try {
                    ViewGroup parent = (ViewGroup) overlayImageHost.getParent();
                    if (parent != null) {
                        parent.removeView(overlayImageHost);
                    }
                } catch (Exception ignored) {
                }
                overlayImageHost = null;
            }
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        final PhotoRendererGLView view = overlayImageView;
        final FrameLayout host = overlayImageHost;
        overlayImageView = null;
        overlayImageHost = null;
        lastImagePath = null;
        view.destroyPreviewAsync(keepSession, () -> {
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
                    FuKitBridge.releaseSession();
                    initialized = false;
                    aiModelLoaded = false;
                    beautyItemHandle = 0;
                    mediaBeautyItemHandle = 0;
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

    private void releaseCameraKeepAliveInternal(Runnable onComplete) {
        dismissFocusHud();
        // 官方 Kit 相机不再保留旧 Nama 的 keep-alive 分支；隐藏和恢复由统一的
        // parkCameraOverlayHidden / resumePreview 生命周期处理。
        if (overlayCameraHost != null) {
            overlayCameraHost.setVisibility(View.GONE);
        }
        if (onComplete != null) {
            onComplete.run();
        }
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
            overlayCameraView.hidePreview();
            View camView = overlayCameraView.asView();
            if (camView instanceof android.opengl.GLSurfaceView) {
                try {
                    android.opengl.GLSurfaceView gl = (android.opengl.GLSurfaceView) camView;
                    gl.setZOrderOnTop(false);
                    gl.setZOrderMediaOverlay(false);
                } catch (Throwable ignored) {
                }
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
                overlayCameraView.asView().setAlpha(0f);
            }
        } else if (overlayCameraView != null) {
            View camView = overlayCameraView.asView();
            camView.setTranslationX(4096f);
            camView.setAlpha(0f);
            overlayCameraView.setVisibility(View.VISIBLE);
        }
        dismissPreviewChrome();
        dismissBeautyPanel();
    }

    private void unparkCameraOverlay() {
        sAvoidCameraGlForImageAfterVideo = false;
        // 官方 CameraRenderer 自己维护相机检测模式和输入矩阵；这里仅恢复 View 布局。
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
            View camView = overlayCameraView.asView();
            camView.setTranslationX(0f);
            camView.setAlpha(1f);
            overlayCameraView.setVisibility(View.VISIBLE);
            if (camView instanceof android.opengl.GLSurfaceView) {
                try {
                    android.opengl.GLSurfaceView gl = (android.opengl.GLSurfaceView) camView;
                    gl.setZOrderMediaOverlay(true);
                    gl.setZOrderOnTop(true);
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
            final ICameraOverlay view = overlayCameraView;
            final boolean keep = keepSession;
            if (view == null) {
                resetOverlayLayoutCache();
                if (onComplete != null) {
                    onComplete.run();
                }
                return;
            }
            view.setVisibility(View.VISIBLE);
            if (keep) {
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
        final ICameraOverlay view = overlayCameraView;
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
            } else if (view != null) {
                ViewGroup parent = (ViewGroup) view.asView().getParent();
                if (parent != null) {
                    parent.removeView(view.asView());
                }
            }
        } catch (Exception ignored) {
        }

        if (view == null) {
            resetOverlayLayoutCache();
            if (onComplete != null) {
                onComplete.run();
            }
            return;
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
                ViewGroup parent = (ViewGroup) view.asView().getParent();
                if (parent != null) {
                    parent.removeView(view.asView());
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
        beautyItemHandle = 0;
        mediaBeautyItemHandle = 0;
        aiModelLoaded = false;
        // 资源已 lost，后续静图可再走相机 GL / 新 offscreen，勿继续强制 avoid
        sAvoidCameraGlForImageAfterVideo = false;
        Log.e(TAG, "markNamaResourcesLost reason=" + reason);
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
        if (!FuKitBridge.isSdkReady()) {
            throw new IllegalStateException("SDK 未就绪");
        }
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
