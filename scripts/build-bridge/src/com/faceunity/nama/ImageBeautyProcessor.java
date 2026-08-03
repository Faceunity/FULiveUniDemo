package com.faceunity.nama;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.opengl.GLES20;
import android.util.Log;

import com.faceunity.wrapper.faceunity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * 静图美颜：RGBA buffer → 多帧 fuRenderToRgbaImage（检脸收敛）→ 写缓存 jpg。
 * 对齐 iOS processImage：identity 矩阵、detectMode=0、不在每次处理后 deviceLost。
 */
final class ImageBeautyProcessor {

    private static final String TAG = "FaceUnity-Nama";
    private static final int MAX_SIDE = 1920;
    /** 首帧检脸预热；同图后续调参可少跑几遍 */
    private static final int WARMUP_FRAMES_FIRST = 8;
    private static final int WARMUP_FRAMES_FAST = 2;
    private static int sFrameId = 100000;

    /** 同路径 RGBA 缓存，避免每次滑杆都重新解码缩放 */
    private static String sCachedPath;
    private static byte[] sCachedRgbaSrc;
    private static int sCachedW;
    private static int sCachedH;
    private static boolean sCachedTracked;

    private ImageBeautyProcessor() {
    }

    static void clearSourceCache() {
        sCachedPath = null;
        sCachedRgbaSrc = null;
        sCachedW = 0;
        sCachedH = 0;
        sCachedTracked = false;
    }

    static String process(Context context, String path, int beautyHandle, File cacheDir) throws Exception {
        synchronized (ImageBeautyProcessor.class) {
            return processLocked(context, path, beautyHandle, cacheDir, true);
        }
    }

    /**
     * 在已有 {@link BeautyCameraGLView} 的 GL 线程上处理静图，避免独立 PBuffer 与相机上下文分裂导致全黑。
     */
    static String processOnGlView(
            BeautyCameraGLView glView,
            Context context,
            String path,
            int beautyHandle,
            File cacheDir
    ) throws Exception {
        if (glView == null) {
            return process(context, path, beautyHandle, cacheDir);
        }
        final Object lock = new Object();
        final String[] out = new String[1];
        final Exception[] err = new Exception[1];
        final boolean[] done = new boolean[1];
        glView.queueEvent(() -> {
            try {
                synchronized (ImageBeautyProcessor.class) {
                    out[0] = processLocked(context, path, beautyHandle, cacheDir, false);
                }
            } catch (Exception e) {
                err[0] = e;
            } finally {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        // 唤醒 GL 线程（WHEN_DIRTY 时 queueEvent 可能迟迟不跑）
        glView.requestRender();
        long deadline = System.currentTimeMillis() + 20_000L;
        synchronized (lock) {
            while (!done[0] && System.currentTimeMillis() < deadline) {
                lock.wait(200);
            }
        }
        if (!done[0]) {
            throw new IOException("processImage GL 超时");
        }
        if (err[0] != null) {
            throw err[0];
        }
        return out[0];
    }

    private static String processLocked(
            Context context,
            String path,
            int beautyHandle,
            File cacheDir,
            boolean useOffscreenGl
    ) throws Exception {
        if (path == null || path.isEmpty()) {
            throw new IOException("path 不能为空");
        }
        if (beautyHandle <= 0) {
            throw new IllegalStateException("请先 loadBundle");
        }
        if (faceunity.fuIsLibraryInit() == 0) {
            throw new IllegalStateException("SDK 未就绪");
        }

        String realPath = MediaPathUtil.toLocalFilePath(context, path, ".jpg");
        byte[] rgbaSrc;
        int width;
        int height;
        boolean reuseCache = sCachedPath != null
                && sCachedPath.equals(realPath)
                && sCachedRgbaSrc != null
                && sCachedW > 0
                && sCachedH > 0;
        if (reuseCache) {
            width = sCachedW;
            height = sCachedH;
            rgbaSrc = sCachedRgbaSrc;
        } else {
            Bitmap src = decodeOrientedBitmap(realPath);
            if (src == null) {
                throw new IOException("无法解码图片: " + realPath);
            }

            Bitmap scaled = maybeScale(src);
            if (scaled != src) {
                src.recycle();
            }

            width = scaled.getWidth() & ~1;
            height = scaled.getHeight() & ~1;
            if (width <= 0 || height <= 0) {
                scaled.recycle();
                throw new IOException("图片尺寸无效");
            }
            if (width != scaled.getWidth() || height != scaled.getHeight()) {
                Bitmap even = Bitmap.createBitmap(scaled, 0, 0, width, height);
                scaled.recycle();
                scaled = even;
            }

            rgbaSrc = bitmapToRgba(scaled);
            scaled.recycle();
            forceOpaqueAlpha(rgbaSrc);
            sCachedPath = realPath;
            sCachedRgbaSrc = rgbaSrc;
            sCachedW = width;
            sCachedH = height;
            sCachedTracked = false;
        }
        byte[] rgba = Arrays.copyOf(rgbaSrc, rgbaSrc.length);

        int prevDetectMode = 1;
        int warmup = (reuseCache && sCachedTracked) ? WARMUP_FRAMES_FAST : WARMUP_FRAMES_FIRST;
        try {
            faceunity.fuSetFaceProcessorDetectMode(0);
            MediaFuSetup.enableFaceAlgorithmModules();
            MediaFuSetup.ensureBeautyOn(beautyHandle);

            if (useOffscreenGl) {
                MediaGlContext.makeCurrent(width, height);
            }
            // 静图 RGBA readback：identity 下点位 Y 常与像素反向（下巴↔额头）
            // 用 CCROT0_FLIPVERTICAL 对齐点位，再 flipRgbaVertical 整图翻正（美型跟随像素）
            try {
                faceunity.fuOnCameraChange();
            } catch (Throwable ignored) {
            }
            try {
                faceunity.fuSetDefaultRotationMode(faceunity.FU_ROTATION_MODE_0);
                faceunity.fuSetInputCameraMatrix(0, 0, faceunity.FU_ROTATION_MODE_0);
                faceunity.fuSetInputBufferMatrix(MediaFuSetup.CCROT0_FLIPVERTICAL);
                faceunity.fuSetInputTextureMatrix(0);
                faceunity.fuSetOutputMatrix(0);
                faceunity.fuSetInputCameraBufferMatrixState(1);
                faceunity.fuSetInputCameraTextureMatrixState(0);
                faceunity.fuSetOutputMatrixState(0);
            } catch (Throwable t) {
                MediaFuSetup.applyIdentityBufferMatrix();
            }
            MediaFuSetup.enableAdvancedBeautyRuntime(beautyHandle);
            MediaFuSetup.ensureBeautyOn(beautyHandle);
            faceunity.fuSetOutputResolution(width, height);
            // 视频预览可能改过 detectMode；静图强制 image 模式
            faceunity.fuSetFaceProcessorDetectMode(0);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            int lastRet = -1;
            int lastTrack = -1;
            synchronized (NamaRenderLock.LOCK) {
                for (int i = 0; i < warmup; i++) {
                    // 每帧从原图拷贝，避免 readback 结果再喂入导致累积花屏/发白
                    System.arraycopy(rgbaSrc, 0, rgba, 0, rgbaSrc.length);
                    int frameId = ++sFrameId;
                    lastRet = faceunity.fuRenderToRgbaImage(
                            rgba,
                            width,
                            height,
                            frameId,
                            new int[]{beautyHandle},
                            faceunity.FU_ADM_FLAG_ENABLE_READBACK
                    );
                    lastTrack = faceunity.fuIsTracking();
                    GLES20.glFinish();
                    if (lastRet >= 0 && i == 0) {
                        MediaFuSetup.tryApplySetUseAfterRender(beautyHandle);
                    }
                    if (lastTrack > 0 && i >= Math.min(1, warmup - 1)) {
                        System.arraycopy(rgbaSrc, 0, rgba, 0, rgbaSrc.length);
                        frameId = ++sFrameId;
                        lastRet = faceunity.fuRenderToRgbaImage(
                                rgba,
                                width,
                                height,
                                frameId,
                                new int[]{beautyHandle},
                                faceunity.FU_ADM_FLAG_ENABLE_READBACK
                        );
                        GLES20.glFinish();
                        break;
                    }
                }
            }
            if (lastTrack > 0) {
                sCachedTracked = true;
            }
            int sysErr = faceunity.fuGetSystemError();
            Log.i(TAG, "processImage render ret=" + lastRet
                    + " track=" + lastTrack + " sys=" + sysErr
                    + " handle=" + beautyHandle + " " + width + "x" + height
                    + " warmup=" + warmup + " cache=" + reuseCache
                    + " setUse=" + FuAiExtras.isSetUseApplied());
            if (lastRet < 0 || sysErr != 0) {
                String errStr = "";
                try {
                    errStr = faceunity.fuGetSystemErrorString(sysErr);
                } catch (Throwable ignored) {
                }
                throw new IOException("fuRenderToRgbaImage 失败 ret=" + lastRet + " sys=" + sysErr + " " + errStr);
            }
            // 独立 GL 上下文失败时常见全黑：回退原图，避免导入页「美颜黑、对比才有」
            if (isMostlyBlack(rgba)) {
                Log.w(TAG, "processImage mostly black, fallback to original pixels");
                System.arraycopy(rgbaSrc, 0, rgba, 0, rgbaSrc.length);
            }
        } finally {
            try {
                faceunity.fuSetFaceProcessorDetectMode(prevDetectMode);
            } catch (Throwable ignored) {
            }
        }

        forceOpaqueAlpha(rgba);
        // FLIPVERTICAL 输入后 readback 相对屏幕常颠倒；整图翻正，美型随像素一起翻
        flipRgbaVertical(rgba, width, height);

        Bitmap out = rgbaToBitmap(rgba, width, height);
        File dir = cacheDir != null ? cacheDir : new File(System.getProperty("java.io.tmpdir"));
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建缓存目录");
        }
        File outFile = new File(dir, "fu_still_" + System.currentTimeMillis() + ".jpg");
        FileOutputStream fos = new FileOutputStream(outFile);
        try {
            // 预览略降 JPEG 质量，减轻滑杆连续调参时的编码耗时
            out.compress(Bitmap.CompressFormat.JPEG, reuseCache ? 85 : 92, fos);
            fos.flush();
        } finally {
            fos.close();
            out.recycle();
        }
        Log.i(TAG, "processImage ok " + outFile.getAbsolutePath() + " " + width + "x" + height);
        return outFile.getAbsolutePath();
    }

    private static void forceOpaqueAlpha(byte[] rgba) {
        for (int i = 3; i < rgba.length; i += 4) {
            rgba[i] = (byte) 0xff;
        }
    }

    private static boolean isMostlyBlack(byte[] rgba) {
        if (rgba == null || rgba.length < 16) {
            return true;
        }
        int samples = 0;
        int dark = 0;
        int step = Math.max(4, (rgba.length / 4 / 400) * 4);
        for (int i = 0; i + 3 < rgba.length; i += step) {
            int r = rgba[i] & 0xff;
            int g = rgba[i + 1] & 0xff;
            int b = rgba[i + 2] & 0xff;
            samples++;
            if (r < 8 && g < 8 && b < 8) {
                dark++;
            }
        }
        return samples > 0 && dark * 10 >= samples * 9;
    }

    private static void flipRgbaVertical(byte[] rgba, int w, int h) {
        if (rgba == null || w <= 0 || h <= 0) {
            return;
        }
        byte[] tmp = new byte[w * 4];
        for (int y = 0; y < h / 2; y++) {
            int top = y * w * 4;
            int bot = (h - 1 - y) * w * 4;
            System.arraycopy(rgba, top, tmp, 0, w * 4);
            System.arraycopy(rgba, bot, rgba, top, w * 4);
            System.arraycopy(tmp, 0, rgba, bot, w * 4);
        }
    }

    private static Bitmap decodeOrientedBitmap(String path) throws IOException {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(path, opts);
        if (bitmap == null) {
            return null;
        }
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            ExifInterface exif = new ExifInterface(path);
            orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
            );
        } catch (Exception ignored) {
        }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270);
                break;
            default:
                return bitmap;
        }
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        bitmap.recycle();
        return rotated;
    }

    private static Bitmap maybeScale(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int maxSide = Math.max(w, h);
        if (maxSide <= MAX_SIDE) {
            return src;
        }
        float scale = (float) MAX_SIDE / (float) maxSide;
        int nw = Math.max(2, Math.round(w * scale)) & ~1;
        int nh = Math.max(2, Math.round(h * scale)) & ~1;
        if (nw <= 0 || nh <= 0) {
            return src;
        }
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    private static byte[] bitmapToRgba(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int o = i * 4;
            rgba[o] = (byte) ((c >> 16) & 0xff);
            rgba[o + 1] = (byte) ((c >> 8) & 0xff);
            rgba[o + 2] = (byte) (c & 0xff);
            rgba[o + 3] = (byte) 0xff;
        }
        return rgba;
    }

    private static Bitmap rgbaToBitmap(byte[] rgba, int w, int h) {
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int o = i * 4;
            int r = rgba[o] & 0xff;
            int g = rgba[o + 1] & 0xff;
            int b = rgba[o + 2] & 0xff;
            pixels[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
        }
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        return bitmap;
    }
}
