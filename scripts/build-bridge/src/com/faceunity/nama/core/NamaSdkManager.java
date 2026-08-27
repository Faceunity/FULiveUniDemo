package com.faceunity.nama.core;

import android.app.Activity;
import android.util.Log;

import com.alibaba.fastjson.JSONObject;
import com.faceunity.app.authpack;
import com.faceunity.nama.utils.NamaJsResult;
import com.faceunity.wrapper.faceunity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * FaceUnity SDK 生命周期：init / loadAIModel / loadBundle / bindMediaBeautyHandle / setParam / destroy。
 * beauty handle 与 init 状态集中在此，NamaModule 通过 getter / 委托方法访问。
 */
public final class NamaSdkManager {

    private static final String TAG = "FaceUnity-Nama";

    private static int beautyItemHandle = 0;
    private static int mediaBeautyItemHandle = 0;
    private static boolean initialized = false;
    private static boolean aiModelLoaded = false;
    /** 相机 beauty bundle 路径：Surface/EGL 重建后在当前 GL 上重建 item */
    private static String lastCameraBeautyPath;

    private NamaSdkManager() {
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean isAiModelLoaded() {
        return aiModelLoaded;
    }

    public static int getCameraBeautyHandle() {
        return beautyItemHandle;
    }

    public static int getMediaBeautyHandle() {
        return mediaBeautyItemHandle;
    }

    /** 媒体页复用相机 handle 时同步原生 media handle（processImage / mountVideoOverlay）。 */
    public static void setMediaBeautyHandle(int handle) {
        mediaBeautyItemHandle = handle;
        FuBeautyHandle.setPipelineHandle(true, handle);
    }

    public static void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("请先 init");
        }
        if (faceunity.fuIsLibraryInit() == 0) {
            throw new IllegalStateException("SDK 未就绪 fuIsLibraryInit=0，请重新 init");
        }
    }

    public static JSONObject getVersion() {
        try {
            return NamaJsResult.success(faceunity.fuGetVersion());
        } catch (Throwable e) {
            return NamaJsResult.fail(e.getMessage());
        }
    }

    /** Android 进程存活重进：JS 缓存 sdkInited 时校验 native 是否仍就绪 */
    public static JSONObject isSdkAlive() {
        try {
            int libInit = faceunity.fuIsLibraryInit();
            JSONObject o = new JSONObject();
            o.put("libInit", libInit);
            o.put("initialized", initialized);
            o.put("cameraHandle", FuBeautyHandle.cameraHandle);
            o.put("mediaHandle", FuBeautyHandle.mediaHandle);
            o.put("aiLoaded", aiModelLoaded);
            o.put("alive", libInit != 0 && initialized);
            return NamaJsResult.success(o);
        } catch (Throwable e) {
            return NamaJsResult.fail(e.getMessage());
        }
    }

    public static JSONObject init(Activity activity) {
        try {
            if (initialized && faceunity.fuIsLibraryInit() != 0) {
                JSONObject ok = new JSONObject();
                ok.put("version", faceunity.fuGetVersion());
                ok.put("fuIsLibraryInit", faceunity.fuIsLibraryInit());
                return NamaJsResult.success(ok);
            }
            if (faceunity.fuIsLibraryInit() == 0) {
                resetHandles();
            }
            byte[] authData = authpack.A();
            try {
                if (activity != null) {
                    MediaFuSetup.setAppContext(activity);
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
                return NamaJsResult.fail("fuSetup 失败 code=0", diag);
            }
            if (libInit == 0) {
                return NamaJsResult.fail("SDK 未就绪 fuIsLibraryInit=0（authpack 与包名/签名不匹配）", diag);
            }
            initialized = true;
            MediaFuSetup.enableFaceAlgorithmModules();
            return NamaJsResult.success(diag);
        } catch (Exception e) {
            return NamaJsResult.fail(e.getMessage());
        }
    }

    public static JSONObject loadAIModel(JSONObject options) {
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
            int handle = faceunity.fuLoadAIModelFromPackage(data, aiType);
            if (handle <= 0) {
                return NamaJsResult.fail("loadAIModel 失败 handle=" + handle);
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
            return NamaJsResult.success(dataOut);
        } catch (Exception e) {
            return NamaJsResult.fail(e.getMessage());
        }
    }

    public static JSONObject loadBundle(JSONObject options) {
        try {
            ensureInitialized();
            String path = options.getString("path");
            byte[] data = readFileBytes(path);
            String pipeline = options != null ? options.getString("pipeline") : null;
            boolean media = pipeline != null && "media".equalsIgnoreCase(pipeline);
            if (!media && path != null && path.length() > 0) {
                lastCameraBeautyPath = path;
            }
            int old = media ? mediaBeautyItemHandle : beautyItemHandle;
            if (old > 0) {
                try {
                    faceunity.fuDestroyItem(old);
                } catch (Exception ignored) {
                }
            }
            int handle = faceunity.fuCreateItemFromPackage(data);
            if (handle <= 0) {
                return NamaJsResult.fail("loadBundle 失败 handle=" + handle);
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
            return NamaJsResult.success(dataOut);
        } catch (Exception e) {
            return NamaJsResult.fail(e.getMessage());
        }
    }

    /** JS 复用相机 beauty handle 时，同步原生 mediaBeautyItemHandle，避免媒体页 handle=0 */
    public static JSONObject bindMediaBeautyHandle(JSONObject options) {
        try {
            int handle = options != null ? options.getIntValue("handle") : 0;
            if (handle <= 0) {
                handle = beautyItemHandle > 0 ? beautyItemHandle : FuBeautyHandle.cameraHandle;
            }
            if (handle <= 0) {
                return NamaJsResult.fail("无可用 beauty handle");
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
            return NamaJsResult.success(data);
        } catch (Exception e) {
            return NamaJsResult.fail(e.getMessage());
        }
    }

    public static JSONObject setParam(JSONObject options, BeautyCameraGLView cam) {
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
                return NamaJsResult.fail("请先 loadBundle");
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
                    NamaGlExecutor.runSync(cam, () ->
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
                return NamaJsResult.success(dataOut);
            }
            return NamaJsResult.success(code);
        } catch (Exception e) {
            return NamaJsResult.fail(e.getMessage());
        }
    }

    public static JSONObject destroySdk() {
        try {
            if (initialized) {
                faceunity.fuDestroyAllItems();
                faceunity.fuDestroyLibData();
            }
            resetHandles();
            return NamaJsResult.success(0);
        } catch (Exception e) {
            return NamaJsResult.fail(e.getMessage());
        }
    }

    /**
     * Surface/EGL 被重建后，在当前 GL 线程重建相机 beauty item（不 reload AI）。
     * 切系统导航后 park 改 Z-order / setFixedSize 会毁掉第一层 EGL，旧 handle 在新 EGL 上无效。
     */
    public static int recreateCameraBeautyItemOnCurrentGl() {
        if (lastCameraBeautyPath == null || lastCameraBeautyPath.length() == 0) {
            return beautyItemHandle;
        }
        if (faceunity.fuIsLibraryInit() == 0) {
            return beautyItemHandle;
        }
        try {
            byte[] data = readFileBytes(lastCameraBeautyPath);
            int old = beautyItemHandle;
            if (old > 0) {
                try {
                    faceunity.fuDestroyItem(old);
                } catch (Exception ignored) {
                }
            }
            int handle = faceunity.fuCreateItemFromPackage(data);
            if (handle <= 0) {
                Log.e(TAG, "recreateCameraBeautyItemOnCurrentGl failed");
                return 0;
            }
            beautyItemHandle = handle;
            FuBeautyHandle.setPipelineHandle(false, handle);
            MediaFuSetup.enableAdvancedBeautyRuntime(handle);
            MediaFuSetup.ensureBeautyOn(handle);
            Log.i(TAG, "recreateCameraBeautyItemOnCurrentGl handle=" + handle);
            return handle;
        } catch (Throwable t) {
            Log.e(TAG, "recreateCameraBeautyItemOnCurrentGl", t);
            return beautyItemHandle;
        }
    }

    /** deviceLost 之后 AI 与全部 beauty item 均失效（不改 initialized，需重新 loadAI/loadBundle） */
    public static void markResourcesLost(String reason) {
        try {
            MediaGlContext.releaseAll();
        } catch (Throwable ignored) {
        }
        clearBeautyHandles();
        Log.e(TAG, "markNamaResourcesLost reason=" + reason);
    }

    /** 仅当静图 offscreen EGL 确实存在时释放并清 handle */
    public static void releaseMediaGlIfNeeded(String reason) {
        boolean lost;
        try {
            lost = MediaGlContext.releaseAll();
        } catch (Throwable t) {
            lost = false;
        }
        if (lost) {
            clearBeautyHandles();
            Log.e(TAG, "releaseMediaGlIfNeeded reason=" + reason);
        }
    }

    private static void clearBeautyHandles() {
        aiModelLoaded = false;
        beautyItemHandle = 0;
        mediaBeautyItemHandle = 0;
        FuBeautyHandle.clearAll();
    }

    private static void resetHandles() {
        initialized = false;
        clearBeautyHandles();
    }

    private static void configureFaceProcessor() {
        faceunity.fuSetMaxFaces(4);
        faceunity.fuSetFaceProcessorDetectMode(1);
        faceunity.fuFaceProcessorSetMinFaceRatio(0.05f);
        try {
            int level = MediaFuSetup.getDevicePerformanceLevel();
            faceunity.fuFaceProcessorSetFaceLandmarkQuality(level >= MediaFuSetup.PERF_HIGH ? 1 : 0);
            faceunity.fuFaceProcessorSetDetectSmallFace(level >= MediaFuSetup.PERF_HIGH ? 1 : 0);
        } catch (Throwable t) {
            Log.w(TAG, "configureFaceProcessor quality", t);
        }
        Log.e(TAG, "configureFaceProcessor ok maxFaces=4");
    }

    private static boolean isSpecialAlgoBeautyKey(String key) {
        return "body_blur_level".equals(key)
                || "delspot_level".equals(key)
                || "facial_plump".equals(key)
                || "intensity_eye_pupil".equals(key)
                || "enable_skinseg".equals(key);
    }

    private static byte[] readFileBytes(String path) throws IOException {
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
}
