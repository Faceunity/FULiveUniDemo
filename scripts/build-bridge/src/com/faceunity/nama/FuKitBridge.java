package com.faceunity.nama;

import android.content.Context;
import android.util.Log;

import com.faceunity.app.authpack;
import com.faceunity.beauty.BeautyInitializeCallback;
import com.faceunity.beauty.FaceBeautyKit;
import com.faceunity.beauty.FaceBeautySdk;
import com.faceunity.core.enumeration.FUAITypeEnum;
import com.faceunity.core.enumeration.FUFaceProcessorDetectModeEnum;
import com.faceunity.core.enumeration.FUHumanProcessorDetectModeEnum;
import com.faceunity.core.faceunity.FUAIKit;
import com.faceunity.core.faceunity.FURenderKit;
import com.faceunity.core.faceunity.FURenderManager;
import com.faceunity.core.utils.FULogger;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Android 官方 fu_core + fu_beauty 栈（与 {@code FaceBeauty_AAR_Custom_UI_Guide.md} 一致）。
 * iOS 仍走既有 Nama framework。
 */
public final class FuKitBridge {

    private static final String TAG = "FaceUnity-FuKit";
    /** 分支 feat/android-face-beauty-aar-kit：Android 相机/美颜走 RenderKit */
    public static final boolean USE_OFFICIAL_KIT = true;

    private static final String AI_BUNDLE = "model" + java.io.File.separator + "ai_face_processor.bundle";
    private static final String BEAUTY_BUNDLE = "graphics/face_beautification.bundle";
    /** 与 JS {@code beauty-effects} 部分 key 不一致时映射到 FaceBeautyKit 参数名 */
    private static final Map<String, String> PARAM_KEY_ALIASES = new HashMap<>();

    static {
        PARAM_KEY_ALIASES.put("color_level_mode2", "color_level");
        PARAM_KEY_ALIASES.put("eye_enlarging_mode3", "eye_enlarging");
        PARAM_KEY_ALIASES.put("cheek_narrow_mode2", "cheek_narrow");
        PARAM_KEY_ALIASES.put("cheek_small_mode2", "cheek_small");
        PARAM_KEY_ALIASES.put("remove_pouch_strength_mode2", "remove_pouch_strength");
        PARAM_KEY_ALIASES.put("remove_nasolabial_folds_strength_mode2", "remove_nasolabial_folds_strength");
        // Uni 前端沿用 Nama 参数后缀；官方 FaceBeautyKit 使用基名。
        PARAM_KEY_ALIASES.put("intensity_forehead_mode2", "intensity_forehead");
        PARAM_KEY_ALIASES.put("intensity_nose_mode2", "intensity_nose");
        PARAM_KEY_ALIASES.put("intensity_mouth_mode3", "intensity_mouth");
    }

    private static volatile boolean sdkReady = false;
    private static volatile boolean aiLoaded = false;
    private static volatile boolean beautyBound = false;
    private static volatile boolean beautyBundleChecked = false;
    private static volatile boolean beautyBundlePresent = false;
    private static volatile long beautyBundleBytes = -1L;
    private static volatile boolean requestedBeautyEnabled = true;
    private static volatile boolean renderKitReleased = true;
    // 视频的 Kit 绑定必须发生在 VideoRenderer.onSurfaceCreated 的 GL 线程。
    private static volatile boolean videoPipelinePreparing = false;
    private static volatile boolean videoSurfaceReady = false;
    private static final Map<String, Double> pendingVideoParams = new LinkedHashMap<>();
    private static volatile String pendingVideoFilterName;
    private static Context appContext;
    private static FaceBeautyKit beautyKit;
    private static final int KIT_BEAUTY_HANDLE = 9001;

    private FuKitBridge() {
    }

    public static boolean isActive() {
        return USE_OFFICIAL_KIT;
    }

    public static boolean isSdkReady() {
        return sdkReady;
    }

    public static int placeholderBeautyHandle() {
        return KIT_BEAUTY_HANDLE;
    }

    public static FaceBeautyKit getBeautyKit() {
        return beautyKit;
    }

    /**
     * 初始化官方 FaceBeautySdk，在 NamaModule.init 中调用。
     */
    public static String initSdk(Context context) throws Exception {
        if (!USE_OFFICIAL_KIT) {
            return "kit disabled";
        }
        if (sdkReady) {
            return "already ready";
        }
        Context app = context.getApplicationContext();
        appContext = app;
        verifyBeautyBundleAsset(app);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger code = new AtomicInteger(-1);
        final AtomicReference<String> msg = new AtomicReference<>("timeout");
        FURenderManager.setKitDebug(FULogger.LogLevel.DEBUG);
        FURenderManager.setCoreDebug(FULogger.LogLevel.DEBUG);
        FaceBeautySdk.setDebug(true);
        FaceBeautySdk.init(app, authpack.A(), new BeautyInitializeCallback() {
            @Override
            public void onSuccess(int c, String m) {
                code.set(c);
                msg.set(m != null ? m : "ok");
                sdkReady = true;
                latch.countDown();
            }

            @Override
            public void onFail(int c, String m) {
                code.set(c);
                msg.set(m != null ? m : "init fail");
                latch.countDown();
            }
        });
        if (!latch.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FaceBeautySdk.init 超时");
        }
        if (!sdkReady) {
            throw new IllegalStateException("FaceBeautySdk.init 失败: " + code.get() + " " + msg.get());
        }
        Log.i(TAG, "FaceBeautySdk.init ok code=" + code.get() + " " + msg.get());
        return msg.get();
    }

    public static void loadAiFaceProcessor() {
        if (!sdkReady || aiLoaded) {
            return;
        }
        MediaFuSetup.enableFaceAlgorithmModules();
        FUAIKit.getInstance().loadAIProcessor(AI_BUNDLE, FUAITypeEnum.FUAITYPE_FACEPROCESSOR);
        FUAIKit.getInstance().setMaxFaces(4);
        aiLoaded = true;
        Log.i(TAG, "loadAIProcessor " + AI_BUNDLE);
    }

    /** PhotoRenderer 使用图片检测模式；与 Demo ShowPhotoActivity.configureFURenderKit 对齐。 */
    public static void preparePhotoRenderer() {
        videoPipelinePreparing = false;
        videoSurfaceReady = false;
        loadAiFaceProcessor();
        FUAIKit aiKit = FUAIKit.getInstance();
        aiKit.faceProcessorSetDetectMode(FUFaceProcessorDetectModeEnum.IMAGE);
        aiKit.setHumanProcessorDetectMode(FUHumanProcessorDetectModeEnum.IMAGE);
        aiKit.faceProcessorSetFaceLandmarkQuality(
                MediaFuSetup.getDevicePerformanceLevel() >= 2 ? 2 : 1);
        aiKit.fuFaceProcessorSetFaceLandmarkHpOccu(0);
        ensureBeautyKitBound();
    }

    /**
     * GL 首帧 / showCamera 前：创建 FaceBeautyKit 并 bindToRenderer。
     */
    public static synchronized int ensureBeautyKitBound() {
        if (!sdkReady) {
            return 0;
        }
        if (beautyKit == null) {
            beautyKit = new FaceBeautyKit();
            Log.i(TAG, "FaceBeautyKit created bundle=" + BEAUTY_BUNDLE
                    + " bundlePresent=" + beautyBundlePresent
                    + " bundleBytes=" + beautyBundleBytes);
        }
        if (!beautyBound) {
            beautyKit.bindToRenderer();
            // 对比开关由 CameraRenderer.setFURenderSwitch 控制，不能在这里解绑 FaceBeauty。
            renderKitReleased = false;
            beautyBound = true;
            Log.i(TAG, "FaceBeautyKit bindToRenderer enable=" + requestedBeautyEnabled
                    + " bundle=" + BEAUTY_BUNDLE
                    + " present=" + beautyBundlePresent
                    + " bytes=" + beautyBundleBytes);
        }
        return KIT_BEAUTY_HANDLE;
    }

    public static void setBeautyEnabled(boolean enabled) {
        requestedBeautyEnabled = enabled;
        Log.i(TAG, "setBeautyEnabled requested=" + enabled
                + " thread=" + Thread.currentThread().getName());
    }

    /** 对齐 Demo：在 RenderKit 的 GL surface 销毁回调中释放 FURenderKit。 */
    public static synchronized void onCameraGlSurfaceDestroyed() {
        releaseRenderKitResources("surfaceDestroy");
        beautyBound = false;
        Log.i(TAG, "FaceBeautyKit render surface released");
    }

    /**
     * PhotoRenderer 使用独立的 GLSurfaceView；图片切换时旧 Surface 销毁后，
     * 新 Surface 必须重新 bindToRenderer，不能沿用旧 Surface 的绑定状态。
     */
    public static synchronized void onPhotoGlSurfaceDestroyed() {
        releaseRenderKitResources("photoSurfaceDestroy");
        beautyBound = false;
        Log.i(TAG, "FaceBeautyKit photo render surface released");
    }

    public static int setParamIntensity(String key, double value) {
        if (videoPipelinePreparing && !videoSurfaceReady) {
            synchronized (pendingVideoParams) {
                pendingVideoParams.put(key, value);
            }
            return 0;
        }
        if (beautyKit == null || !beautyBound) {
            ensureBeautyKitBound();
        }
        if (beautyKit == null) {
            return -1;
        }
        String resolved = PARAM_KEY_ALIASES.containsKey(key) ? PARAM_KEY_ALIASES.get(key) : key;
        double before = readParamIntensity(resolved);
        int code;
        if ("filter_level".equals(resolved)) {
            String name = beautyKit.getFaceBeauty().getFilterName();
            beautyKit.setFilter(name != null ? name : "origin", value);
            code = 0;
        } else if ("enable_skinseg".equals(resolved)) {
            beautyKit.setSkinSegEnable(value > 0.5);
            code = 0;
        } else {
            beautyKit.setParamIntensity(resolved, value);
            code = 0;
        }
        double after = readParamIntensity(resolved);
        if (isDiagnosticParam(key) || isDiagnosticParam(resolved)) {
            Log.i(TAG, "setParam key=" + key
                    + " resolved=" + resolved
                    + " requested=" + value
                    + " before=" + before
                    + " ret=" + code
                    + " after=" + after
                    + " bundle=" + BEAUTY_BUNDLE
                    + " bundlePresent=" + beautyBundlePresent
                    + " bundleBytes=" + beautyBundleBytes
                    + " featureSupported=" + MediaFuSetup.isBeautyFeatureSupported(key)
                    + " deviceLevel=" + MediaFuSetup.getDevicePerformanceLevel()
                    + " beautyBound=" + beautyBound
                    + " thread=" + Thread.currentThread().getName());
        }
        return code;
    }

    public static double getParamIntensity(String key) {
        if (beautyKit == null || !beautyBound) {
            ensureBeautyKitBound();
        }
        if (beautyKit == null) {
            return 0.0;
        }
        String resolved = PARAM_KEY_ALIASES.containsKey(key) ? PARAM_KEY_ALIASES.get(key) : key;
        return readParamIntensity(resolved);
    }

    public static int setFilter(String name, double intensity) {
        if (videoPipelinePreparing && !videoSurfaceReady) {
            synchronized (pendingVideoParams) {
                pendingVideoFilterName = name == null || name.isEmpty() ? "origin" : name;
                pendingVideoParams.put("filter_level", intensity);
            }
            return 0;
        }
        if (beautyKit == null || !beautyBound) {
            ensureBeautyKitBound();
        }
        if (beautyKit == null) {
            return -1;
        }
        String resolvedName = name == null || name.isEmpty() ? "origin" : name;
        double beforeIntensity = beautyKit.getFaceBeauty().getFilterIntensity();
        beautyKit.setFilter(resolvedName, intensity);
        Log.i(TAG, "setFilter name=" + resolvedName
                + " requested=" + intensity
                + " before=" + beforeIntensity
                + " afterName=" + beautyKit.getFaceBeauty().getFilterName()
                + " afterIntensity=" + beautyKit.getFaceBeauty().getFilterIntensity()
                + " bundlePresent=" + beautyBundlePresent
                + " bundleBytes=" + beautyBundleBytes
                + " thread=" + Thread.currentThread().getName());
        return 0;
    }

    public static void resetBeauty() {
        if (beautyKit != null) {
            beautyKit.reset();
            beautyKit.setEnable(true);
        }
    }

    /** CameraRenderer onSurfaceCreated：加载 AI + 美颜绑定 */
    public static void onCameraGlSurfaceCreated() {
        try {
            loadAiFaceProcessor();
            ensureBeautyKitBound();
        } catch (Throwable t) {
            Log.e(TAG, "onCameraGlSurfaceCreated", t);
        }
    }

    /** VideoRenderer onSurfaceCreated：视频使用官方 Kit 的 VIDEO 检测模式。 */
    public static synchronized void onVideoGlSurfaceCreated() {
        try {
            // VideoRenderer 可能在相机/图片 Renderer 之后创建，必须让官方 Kit 重新绑定到当前 Surface。
            if (beautyBound) {
                releaseRenderKitResources("videoSurfaceCreate");
                beautyBound = false;
            }
            loadAiFaceProcessor();
            FUAIKit aiKit = FUAIKit.getInstance();
            aiKit.faceProcessorSetDetectMode(FUFaceProcessorDetectModeEnum.VIDEO);
            aiKit.setHumanProcessorDetectMode(FUHumanProcessorDetectModeEnum.VIDEO);
            aiKit.faceProcessorSetFaceLandmarkQuality(
                    MediaFuSetup.getDevicePerformanceLevel() >= 2 ? 2 : 1);
            aiKit.fuFaceProcessorSetFaceLandmarkHpOccu(0);
            ensureBeautyKitBound();
            videoSurfaceReady = true;
            applyPendingVideoParams();
            Log.i(TAG, "video surface ready; pending params applied");
        } catch (Throwable t) {
            Log.e(TAG, "onVideoGlSurfaceCreated", t);
        }
    }

    /** VideoRenderer onSurfaceDestroy：释放当前 RenderKit 绑定，下一次可重新绑定新 Surface。 */
    public static synchronized void onVideoGlSurfaceDestroyed() {
        finishVideoPipeline();
        releaseRenderKitResources("videoSurfaceDestroy");
        beautyBound = false;
        Log.i(TAG, "FaceBeautyKit video render surface released");
    }

    /**
     * 视频链路结束（Surface 已销毁或尚未创建即退出）：复位 pending 缓存标志，
     * 否则 {@link #setParamIntensity} 会把相机写参误判为「等视频 Surface」而静默丢参。
     */
    public static synchronized void finishVideoPipeline() {
        videoPipelinePreparing = false;
        videoSurfaceReady = false;
        synchronized (pendingVideoParams) {
            pendingVideoParams.clear();
            pendingVideoFilterName = null;
        }
        Log.i(TAG, "finishVideoPipeline");
    }

    public static void releaseSession() {
        releaseRenderKitResources("releaseSession");
        beautyBound = false;
        beautyKit = null;
        requestedBeautyEnabled = true;
        appContext = null;
        videoPipelinePreparing = false;
        videoSurfaceReady = false;
        synchronized (pendingVideoParams) {
            pendingVideoParams.clear();
            pendingVideoFilterName = null;
        }
    }

    /** 开始视频媒体链路：只准备算法，等待官方 VideoRenderer 创建 GL Surface。 */
    public static synchronized void prepareVideoPipeline() {
        videoPipelinePreparing = true;
        videoSurfaceReady = false;
        loadAiFaceProcessor();
        FUAIKit aiKit = FUAIKit.getInstance();
        aiKit.faceProcessorSetDetectMode(FUFaceProcessorDetectModeEnum.VIDEO);
        aiKit.setHumanProcessorDetectMode(FUHumanProcessorDetectModeEnum.VIDEO);
        aiKit.faceProcessorSetFaceLandmarkQuality(
                MediaFuSetup.getDevicePerformanceLevel() >= 2 ? 2 : 1);
        aiKit.fuFaceProcessorSetFaceLandmarkHpOccu(0);
        synchronized (pendingVideoParams) {
            pendingVideoParams.clear();
            pendingVideoFilterName = null;
        }
        Log.i(TAG, "prepareVideoPipeline: wait for VideoRenderer surface");
    }

    public static void applyDefaultBeautyParams() {
        setParamIntensity("skin_detect", 0.0);
        setParamIntensity("blur_level", 3.3);
        setParamIntensity("color_level", 0.4);
        setParamIntensity("red_level", 0.3);
        setParamIntensity("body_blur_level", MediaFuSetup.getDemoDefaultValue("body_blur_level"));
        setParamIntensity("delspot_level", MediaFuSetup.getDemoDefaultValue("delspot_level"));
        setParamIntensity("facial_plump", MediaFuSetup.getDemoDefaultValue("facial_plump"));
        setParamIntensity("intensity_eye_pupil", MediaFuSetup.getDemoDefaultValue("intensity_eye_pupil"));
        setParamIntensity("enable_skinseg", 0.0);
    }

    public static boolean isKitBeautyHandle(int handle) {
        return handle == KIT_BEAUTY_HANDLE;
    }

    public static String beautyBundlePath() {
        return BEAUTY_BUNDLE;
    }

    public static boolean isBeautyBundlePresent() {
        return beautyBundlePresent;
    }

    public static long beautyBundleBytes() {
        return beautyBundleBytes;
    }

    private static double readParamIntensity(String key) {
        try {
            return beautyKit.getParamIntensity(key);
        } catch (Throwable t) {
            Log.w(TAG, "getParamIntensity key=" + key, t);
            return 0.0;
        }
    }

    private static boolean isDiagnosticParam(String key) {
        return "blur_level".equals(key)
                || "color_level".equals(key)
                || "color_level_mode2".equals(key)
                || "blur_type".equals(key)
                || "heavy_blur".equals(key)
                || "blur_use_mask".equals(key)
                || "intensity_eye_pupil".equals(key)
                || "facial_plump".equals(key)
                || "delspot_level".equals(key)
                || "body_blur_level".equals(key)
                || "enable_skinseg".equals(key);
    }

    private static synchronized void releaseRenderKitResources(String reason) {
        if (renderKitReleased) {
            return;
        }
        try {
            FURenderKit.getInstance().release();
            renderKitReleased = true;
            Log.i(TAG, "FURenderKit.release reason=" + reason
                    + " thread=" + Thread.currentThread().getName());
        } catch (Throwable t) {
            Log.e(TAG, "FURenderKit.release reason=" + reason, t);
        }
    }

    private static void applyPendingVideoParams() {
        Map<String, Double> params;
        String filterName;
        synchronized (pendingVideoParams) {
            params = new LinkedHashMap<>(pendingVideoParams);
            filterName = pendingVideoFilterName;
            pendingVideoParams.clear();
            pendingVideoFilterName = null;
        }
        if (filterName != null) {
            Double level = params.remove("filter_level");
            setFilter(filterName, level == null ? 0.5 : level);
        }
        for (Map.Entry<String, Double> entry : params.entrySet()) {
            if (!"filter_level".equals(entry.getKey())) {
                setParamIntensity(entry.getKey(), entry.getValue());
            }
        }
    }

    private static synchronized void verifyBeautyBundleAsset(Context context) {
        if (beautyBundleChecked) {
            return;
        }
        beautyBundleChecked = true;
        if (context == null) {
            Log.e(TAG, "beauty bundle check failed: context=null path=" + BEAUTY_BUNDLE);
            return;
        }
        try (InputStream input = context.getAssets().open(BEAUTY_BUNDLE)) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
            }
            beautyBundlePresent = true;
            beautyBundleBytes = total;
            Log.i(TAG, "beauty bundle present path=" + BEAUTY_BUNDLE + " bytes=" + total);
        } catch (Throwable t) {
            beautyBundlePresent = false;
            beautyBundleBytes = -1L;
            Log.e(TAG, "beauty bundle missing path=" + BEAUTY_BUNDLE, t);
        }
    }
}
