package com.faceunity.nama;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import com.faceunity.wrapper.faceunity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 视频预览 GPU 路径：硬解 Surface → OES → FBO 2D → {@code fuRenderTexture}（FULL flags）。
 * 须在相机 {@link BeautyCameraGLView} 的 GL 线程上创建/渲染；显示层通过 RGBA 读回（独立 EGL）。
 */
final class VideoGpuPreviewHelper {

    private static final String TAG = "FaceUnity-VideoGpuPv";

    private final Object frameLock = new Object();

    private SurfaceTexture surfaceTexture;
    private Surface decoderSurface;
    private volatile boolean frameAvailable;

    private int oesTexId;
    private int fboId;
    private int fboTexId;
    private int displayTexId;
    private int outW;
    private int outH;

    private int oesProgram;
    private int oesAPos;
    private int oesAUv;
    private int oesUTex;
    private int oesUMtx;
    private FloatBuffer quadPos;
    private FloatBuffer quadUv;
    private final float[] stMatrix = new float[16];

    private boolean matrixApplied;
    private int frameCounter = 600_000;
    private int readFboId;
    private ByteBuffer readBuf;

    /** 从 2D 纹理读回 RGBA（顶向下，供视频显示层上传） */
    byte[] readDisplayRgba(boolean beautyEnabled) {
        int tex = !beautyEnabled || displayTexId <= 0 ? fboTexId : displayTexId;
        return readTexRgba(tex);
    }

    byte[] readTexRgba(int texId) {
        if (texId <= 0 || outW <= 0 || outH <= 0) {
            return null;
        }
        ensureReadFbo();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readFboId);
        GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                texId,
                0
        );
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            return null;
        }
        int need = outW * outH * 4;
        if (readBuf == null || readBuf.capacity() < need) {
            readBuf = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder());
        }
        readBuf.clear();
        GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuf);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        byte[] rgba = new byte[need];
        readBuf.rewind();
        readBuf.get(rgba);
        flipRgbaVerticalInPlace(rgba, outW, outH);
        return rgba;
    }

    private void ensureReadFbo() {
        if (readFboId > 0) {
            return;
        }
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        readFboId = fbo[0];
    }

    private static void flipRgbaVerticalInPlace(byte[] rgba, int w, int h) {
        int rowBytes = w * 4;
        byte[] line = new byte[rowBytes];
        for (int y = 0; y < h / 2; y++) {
            int top = y * rowBytes;
            int bottom = (h - 1 - y) * rowBytes;
            System.arraycopy(rgba, top, line, 0, rowBytes);
            System.arraycopy(rgba, bottom, rgba, top, rowBytes);
            System.arraycopy(line, 0, rgba, bottom, rowBytes);
        }
    }

    void initOnGl(int srcW, int srcH, int outW, int outH, Handler stHandler) {
        releaseOnGl();
        this.outW = outW;
        this.outH = outH;
        matrixApplied = false;
        displayTexId = 0;

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        oesTexId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        surfaceTexture = new SurfaceTexture(oesTexId);
        surfaceTexture.setDefaultBufferSize(srcW, srcH);
        surfaceTexture.setOnFrameAvailableListener(st -> {
            synchronized (frameLock) {
                frameAvailable = true;
                frameLock.notifyAll();
            }
        }, stHandler);
        decoderSurface = new Surface(surfaceTexture);

        int[] fboTex = new int[1];
        GLES20.glGenTextures(1, fboTex, 0);
        fboTexId = fboTex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outW, outH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        fboId = fbo[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTexId, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("video preview FBO incomplete=" + status);
        }

        oesProgram = buildOesBlitProgram();
        oesAPos = GLES20.glGetAttribLocation(oesProgram, "aPosition");
        oesAUv = GLES20.glGetAttribLocation(oesProgram, "aTexCoord");
        oesUTex = GLES20.glGetUniformLocation(oesProgram, "uTexture");
        oesUMtx = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix");
        quadPos = toFb(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
        quadUv = toFb(new float[]{0, 0, 1, 0, 0, 1, 1, 1});

        Log.i(TAG, "initOnGl src=" + srcW + "x" + srcH + " out=" + outW + "x" + outH);
    }

    Surface getDecoderSurface() {
        return decoderSurface;
    }

    int getDisplayTexId() {
        return displayTexId > 0 ? displayTexId : fboTexId;
    }

    int getRawTexId() {
        return fboTexId;
    }

    int getOutW() {
        return outW;
    }

    int getOutH() {
        return outH;
    }

    boolean waitFrameAvailable(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (frameLock) {
            while (!frameAvailable) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                try {
                    frameLock.wait(Math.min(left, 32L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            frameAvailable = false;
            return true;
        }
    }

    /** @return Nama 输出纹理 id；美颜关时返回 FBO 原图 */
    int renderBeautyFrame(int beautyHandle, boolean beautyEnabled, int minPasses, Runnable flushParams) {
        if (surfaceTexture == null || fboTexId <= 0) {
            return 0;
        }
        try {
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(stMatrix);
        } catch (Throwable t) {
            Log.w(TAG, "updateTexImage", t);
            return displayTexId;
        }
        blitOesToFbo();

        if (!matrixApplied) {
            try {
                faceunity.fuOnCameraChange();
            } catch (Throwable ignored) {
            }
            applyVideoTextureMatrix();
            matrixApplied = true;
        }
        faceunity.fuSetFaceProcessorDetectMode(0);
        faceunity.fuSetOutputResolution(outW, outH);

        if (!beautyEnabled || beautyHandle <= 0 || faceunity.fuIsLibraryInit() == 0) {
            displayTexId = fboTexId;
            return fboTexId;
        }

        MediaFuSetup.ensureBeautyOn(beautyHandle);
        int loops = Math.max(1, minPasses);
        int ret = 0;
        synchronized (NamaRenderLock.LOCK) {
            if (flushParams != null) {
                flushParams.run();
            }
            FuSpecialBeautySync.reconfirmBeforeRender(beautyHandle);
            BeautyParamApplier.updateBeautyBlurEffectAfterRender(beautyHandle);
            int flags = 0;
            for (int i = 0; i < loops; i++) {
                int fid = ++frameCounter;
                ret = faceunity.fuRenderToTexture(
                        fboTexId,
                        outW,
                        outH,
                        fid,
                        new int[]{beautyHandle},
                        flags
                );
            }
        }
        displayTexId = ret > 0 ? ret : fboTexId;
        try {
            NamaModule.onFaceTrackingUpdated(faceunity.fuIsTracking() > 0);
        } catch (Throwable ignored) {
        }
        return displayTexId;
    }

    /** 暂停调参：不重解帧，对缓存 FBO 重跑 Nama */
    int redrawFromCachedFbo(int beautyHandle, boolean beautyEnabled, int minPasses, Runnable flushParams) {
        if (fboTexId <= 0) {
            return 0;
        }
        faceunity.fuSetFaceProcessorDetectMode(0);
        faceunity.fuSetOutputResolution(outW, outH);
        if (!beautyEnabled || beautyHandle <= 0 || faceunity.fuIsLibraryInit() == 0) {
            displayTexId = fboTexId;
            return fboTexId;
        }
        MediaFuSetup.ensureBeautyOn(beautyHandle);
        int loops = Math.max(1, minPasses);
        int ret = 0;
        synchronized (NamaRenderLock.LOCK) {
            if (flushParams != null) {
                flushParams.run();
            }
            FuSpecialBeautySync.reconfirmBeforeRender(beautyHandle);
            BeautyParamApplier.updateBeautyBlurEffectAfterRender(beautyHandle);
            int flags = 0;
            for (int i = 0; i < loops; i++) {
                int fid = ++frameCounter;
                ret = faceunity.fuRenderToTexture(
                        fboTexId,
                        outW,
                        outH,
                        fid,
                        new int[]{beautyHandle},
                        flags
                );
            }
        }
        displayTexId = ret > 0 ? ret : fboTexId;
        return displayTexId;
    }

    void releaseOnGl() {
        if (decoderSurface != null) {
            try {
                decoderSurface.release();
            } catch (Throwable ignored) {
            }
            decoderSurface = null;
        }
        if (surfaceTexture != null) {
            try {
                surfaceTexture.release();
            } catch (Throwable ignored) {
            }
            surfaceTexture = null;
        }
        if (oesTexId > 0) {
            GLES20.glDeleteTextures(1, new int[]{oesTexId}, 0);
            oesTexId = 0;
        }
        if (fboTexId > 0) {
            GLES20.glDeleteTextures(1, new int[]{fboTexId}, 0);
            fboTexId = 0;
        }
        if (fboId > 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{fboId}, 0);
            fboId = 0;
        }
        if (readFboId > 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{readFboId}, 0);
            readFboId = 0;
        }
        readBuf = null;
        if (oesProgram > 0) {
            GLES20.glDeleteProgram(oesProgram);
            oesProgram = 0;
        }
        displayTexId = 0;
        matrixApplied = false;
        synchronized (frameLock) {
            frameAvailable = false;
        }
    }

    private void blitOesToFbo() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        GLES20.glViewport(0, 0, outW, outH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(oesProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        GLES20.glUniform1i(oesUTex, 0);
        GLES20.glUniformMatrix4fv(oesUMtx, 1, false, stMatrix, 0);
        GLES20.glEnableVertexAttribArray(oesAPos);
        GLES20.glVertexAttribPointer(oesAPos, 2, GLES20.GL_FLOAT, false, 0, quadPos);
        GLES20.glEnableVertexAttribArray(oesAUv);
        GLES20.glVertexAttribPointer(oesAUv, 2, GLES20.GL_FLOAT, false, 0, quadUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(oesAPos);
        GLES20.glDisableVertexAttribArray(oesAUv);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    }

    private static void applyVideoTextureMatrix() {
        try {
            faceunity.fuSetDefaultRotationMode(faceunity.FU_ROTATION_MODE_0);
            faceunity.fuSetInputCameraMatrix(0, 0, faceunity.FU_ROTATION_MODE_0);
            faceunity.fuSetInputTextureMatrix(0);
            faceunity.fuSetInputBufferMatrix(0);
            faceunity.fuSetOutputMatrix(0);
            faceunity.fuSetInputCameraTextureMatrixState(0);
            faceunity.fuSetInputCameraBufferMatrixState(0);
            faceunity.fuSetOutputMatrixState(0);
        } catch (Throwable t) {
            Log.w(TAG, "applyVideoTextureMatrix", t);
            MediaFuSetup.applyIdentityBufferMatrix();
        }
    }

    static int[] scaleEven(int w, int h, int maxSide) {
        double longSide = Math.max(w, h);
        double scale = longSide > maxSide ? (maxSide / longSide) : 1.0;
        int nw = ((int) Math.floor(w * scale)) & ~1;
        int nh = ((int) Math.floor(h * scale)) & ~1;
        if (nw < 2) {
            nw = 2;
        }
        if (nh < 2) {
            nh = 2;
        }
        return new int[]{nw, nh};
    }

    private static FloatBuffer toFb(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    private static int buildOesBlitProgram() {
        String vs =
                "attribute vec4 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "uniform mat4 uTexMatrix;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main(){\n" +
                "  gl_Position=aPosition;\n" +
                "  vTexCoord=(uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
                "}\n";
        String fs =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "void main(){\n" +
                "  gl_FragColor=texture2D(uTexture,vTexCoord);\n" +
                "}\n";
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private static int loadShader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }
}
