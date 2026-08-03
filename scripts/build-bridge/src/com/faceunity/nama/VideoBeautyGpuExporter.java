package com.faceunity.nama;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.faceunity.wrapper.faceunity;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GPU 离线导出：硬解 → OES → 在<strong>相机 GL</strong>上 {@code fuRenderToTexture} → Surface 硬编。
 * <p>
 * 严禁另起独立 EGL 跑 Nama（会毁掉预览上下文 → 导完美颜丢失/黑屏）。
 */
final class VideoBeautyGpuExporter {

    private static final String TAG = "FaceUnity-VideoGpu";
    private static final int MAX_SIDE = 1280;
    private static final long MAX_DURATION_US = 60_000_000L;
    private static final long GL_TIMEOUT_MS = 8_000L;

    private VideoBeautyGpuExporter() {
    }

    static String process(
            GLSurfaceView glView,
            Context context,
            String path,
            int beautyHandle,
            File cacheDir,
            VideoBeautyProcessor.ProgressListener progress
    ) throws Exception {
        if (glView == null) {
            throw new IOException("glView null");
        }
        if (beautyHandle <= 0 || faceunity.fuIsLibraryInit() == 0) {
            throw new IllegalStateException("SDK/美颜未就绪");
        }
        String real = MediaPathUtil.toLocalFilePath(context, path, ".mp4");
        File srcFile = new File(real);
        if (!srcFile.exists()) {
            throw new IOException("视频文件不存在");
        }

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(real);
        int videoTrack = -1;
        int audioTrack = -1;
        MediaFormat videoFormat = null;
        MediaFormat audioFormat = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                continue;
            }
            if (videoTrack < 0 && mime.startsWith("video/")) {
                videoTrack = i;
                videoFormat = f;
            } else if (audioTrack < 0 && mime.startsWith("audio/")) {
                audioTrack = i;
                audioFormat = f;
            }
        }
        if (videoTrack < 0 || videoFormat == null) {
            extractor.release();
            throw new IOException("无视频轨");
        }
        long durationUs = videoFormat.containsKey(MediaFormat.KEY_DURATION)
                ? videoFormat.getLong(MediaFormat.KEY_DURATION) : 0L;
        if (durationUs > MAX_DURATION_US) {
            extractor.release();
            throw new IOException("视频超过 60 秒限制");
        }

        final int srcW = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
        final int srcH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
        final int rotation = videoFormat.containsKey(MediaFormat.KEY_ROTATION)
                ? videoFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
        int displayW = srcW;
        int displayH = srcH;
        int applyRot = BeautyVideoGLView.rotationForDecodedFrame(srcW, srcH, rotation);
        if (applyRot == 90 || applyRot == 270) {
            displayW = srcH;
            displayH = srcW;
        }
        int[] outSize = scaleEven(displayW, displayH, MAX_SIDE);
        final int outW = outSize[0];
        final int outH = outSize[1];
        int fps = 30;
        if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try {
                fps = Math.max(1, Math.min(30, videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)));
            } catch (Exception ignored) {
                fps = 30;
            }
        }
        final long frameDurationUs = Math.max(1L, 1_000_000L / fps);

        File dir = cacheDir != null ? cacheDir : new File(System.getProperty("java.io.tmpdir"));
        if (!dir.exists() && !dir.mkdirs()) {
            extractor.release();
            throw new IOException("无法创建缓存目录");
        }
        File outFile = new File(dir, "fu_video_gpu_" + System.currentTimeMillis() + ".mp4");
        if (outFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
        }

        HandlerThread stThread = new HandlerThread("fu-export-st");
        stThread.start();
        Handler stHandler = new Handler(stThread.getLooper());

        final Object frameLock = new Object();
        final AtomicBoolean frameAvailable = new AtomicBoolean(false);
        final AtomicInteger oesTexId = new AtomicInteger(0);
        final AtomicInteger fboId = new AtomicInteger(0);
        final AtomicInteger fboTexId = new AtomicInteger(0);
        final AtomicInteger oesProgram = new AtomicInteger(0);
        final AtomicInteger drawProgram = new AtomicInteger(0);
        final AtomicInteger oesAPos = new AtomicInteger(0);
        final AtomicInteger oesAUv = new AtomicInteger(0);
        final AtomicInteger oesUTex = new AtomicInteger(0);
        final AtomicInteger oesUMtx = new AtomicInteger(0);
        final AtomicInteger drawAPos = new AtomicInteger(0);
        final AtomicInteger drawAUv = new AtomicInteger(0);
        final AtomicInteger drawUTex = new AtomicInteger(0);
        final AtomicReference<SurfaceTexture> surfaceTextureRef = new AtomicReference<>();
        final AtomicReference<Surface> decoderSurfaceRef = new AtomicReference<>();
        final AtomicReference<EGLSurface> encoderEglSurfaceRef = new AtomicReference<>();
        final float[] stMatrix = new float[16];
        final FloatBuffer[] quad = new FloatBuffer[3]; // pos, uvNormal, uvFlipXY

        MediaCodec encoder = null;
        MediaCodec decoder = null;
        MediaMuxer muxer = null;
        Surface encoderInputSurface = null;

        try {
            // 1) 在相机 GL 上创建 OES + FBO + 绘制资源（Nama 同一上下文）
            runOnGl(glView, () -> {
                int[] tex = new int[1];
                GLES20.glGenTextures(1, tex, 0);
                oesTexId.set(tex[0]);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

                SurfaceTexture st = new SurfaceTexture(tex[0]);
                st.setDefaultBufferSize(srcW, srcH);
                st.setOnFrameAvailableListener(surfaceTexture -> {
                    synchronized (frameLock) {
                        frameAvailable.set(true);
                        frameLock.notifyAll();
                    }
                }, stHandler);
                surfaceTextureRef.set(st);
                decoderSurfaceRef.set(new Surface(st));

                // FBO：把 OES(+ST transform) 烘焙成直立 2D，再喂 Nama（解决「只有一角」）
                int[] fboTex = new int[1];
                GLES20.glGenTextures(1, fboTex, 0);
                fboTexId.set(fboTex[0]);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[0]);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outW, outH, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                int[] fbo = new int[1];
                GLES20.glGenFramebuffers(1, fbo, 0);
                fboId.set(fbo[0]);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                        GLES20.GL_TEXTURE_2D, fboTex[0], 0);
                int fbStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                if (fbStatus != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    throw new RuntimeException("export FBO incomplete=" + fbStatus);
                }

                int oesProg = buildOesBlitProgram();
                oesProgram.set(oesProg);
                oesAPos.set(GLES20.glGetAttribLocation(oesProg, "aPosition"));
                oesAUv.set(GLES20.glGetAttribLocation(oesProg, "aTexCoord"));
                oesUTex.set(GLES20.glGetUniformLocation(oesProg, "uTexture"));
                oesUMtx.set(GLES20.glGetUniformLocation(oesProg, "uTexMatrix"));

                int prog = buildDrawProgram();
                drawProgram.set(prog);
                drawAPos.set(GLES20.glGetAttribLocation(prog, "aPosition"));
                drawAUv.set(GLES20.glGetAttribLocation(prog, "aTexCoord"));
                drawUTex.set(GLES20.glGetUniformLocation(prog, "uTexture"));
                quad[0] = toFb(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
                // 编码 Surface 与 GL 同向用 identity UV；再翻 Y 反而会上下颠倒进相册
                quad[1] = toFb(new float[]{0, 0, 1, 0, 0, 1, 1, 1});
                quad[2] = quad[1];

                faceunity.fuSetFaceProcessorDetectMode(0);
                MediaFuSetup.enableAdvancedBeautyRuntime(beautyHandle);
                MediaFuSetup.ensureBeautyOn(beautyHandle);
                try {
                    faceunity.fuOnCameraChange();
                } catch (Throwable ignored) {
                }
                applyVideoTextureMatrix();
                faceunity.fuSetOutputResolution(outW, outH);
            });

            Surface decoderSurface = decoderSurfaceRef.get();
            if (decoderSurface == null) {
                throw new IOException("decoder Surface 创建失败");
            }

            // 2) Surface 硬编
            MediaFormat encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH);
            encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(2_000_000, outW * outH * 4));
            encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderInputSurface = encoder.createInputSurface();
            encoder.start();

            final Surface encSurf = encoderInputSurface;
            runOnGl(glView, () -> {
                EGLDisplay dpy = EGL14.eglGetCurrentDisplay();
                EGLContext ctx = EGL14.eglGetCurrentContext();
                EGLSurface oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
                EGLConfig cfg = queryCurrentConfig(dpy, oldDraw);
                int[] attrib = {EGL14.EGL_NONE};
                EGLSurface eglSurf = EGL14.eglCreateWindowSurface(dpy, cfg, encSurf, attrib, 0);
                if (eglSurf == null || eglSurf == EGL14.EGL_NO_SURFACE) {
                    throw new RuntimeException("eglCreateWindowSurface encoder failed");
                }
                encoderEglSurfaceRef.set(eglSurf);
                // 保持相机 surface current，勿长期切到 encoder
                if (!EGL14.eglMakeCurrent(dpy, oldDraw, oldDraw, ctx)) {
                    Log.w(TAG, "restore camera EGL after create encoder surface failed");
                }
            });

            // 3) Surface 硬解
            extractor.selectTrack(videoTrack);
            decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME));
            decoder.configure(videoFormat, decoderSurface, null, 0);
            decoder.start();

            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            boolean inputEos = false;
            boolean outputEos = false;
            boolean encoderEos = false;
            boolean muxerStarted = false;
            int muxVideoTrack = -1;
            int muxAudioTrack = -1;
            int frameId = 500000;
            int rendered = 0;
            int written = 0;
            long nextPtsUs = 0L;
            long lastPtsUs = -1L;
            final ConcurrentLinkedQueue<Long> ptsQueue = new ConcurrentLinkedQueue<>();

            Log.i(TAG, "gpu export start " + srcW + "x" + srcH + " rot=" + rotation
                    + " applyRot=" + applyRot + " out=" + outW + "x" + outH + " fps=" + fps);

            while (!outputEos) {
                if (!inputEos) {
                    int inIndex = decoder.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(inIndex);
                        int sampleSize = extractor.readSampleData(input, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(decInfo, 10_000);
                if (outIndex >= 0) {
                    boolean decEos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (decInfo.size > 0) {
                        frameAvailable.set(false);
                        decoder.releaseOutputBuffer(outIndex, true);
                        waitFrameAvailable(frameLock, frameAvailable, 2_000L);

                        long ptsUs = decInfo.presentationTimeUs;
                        if (ptsUs <= lastPtsUs) {
                            ptsUs = lastPtsUs + frameDurationUs;
                        }
                        lastPtsUs = ptsUs;
                        final long ptsNs = ptsUs * 1000L;
                        final long ptsForQueue = ptsUs;
                        final int fid = ++frameId;
                        final int renderedNow = rendered;
                        final boolean warmup = renderedNow < 2; // 前两帧只喂 Nama，不进编码器（消首帧黑）
                        final int[] renderRet = {-1};

                        runOnGl(glView, () -> {
                            SurfaceTexture st = surfaceTextureRef.get();
                            if (st == null) {
                                return;
                            }
                            st.updateTexImage();
                            st.getTransformMatrix(stMatrix);

                            // OES(+ST matrix) → FBO 直立 2D
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId.get());
                            GLES20.glViewport(0, 0, outW, outH);
                            GLES20.glClearColor(0f, 0f, 0f, 1f);
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                            GLES20.glUseProgram(oesProgram.get());
                            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId.get());
                            GLES20.glUniform1i(oesUTex.get(), 0);
                            GLES20.glUniformMatrix4fv(oesUMtx.get(), 1, false, stMatrix, 0);
                            GLES20.glEnableVertexAttribArray(oesAPos.get());
                            GLES20.glVertexAttribPointer(oesAPos.get(), 2, GLES20.GL_FLOAT, false, 0, quad[0]);
                            GLES20.glEnableVertexAttribArray(oesAUv.get());
                            GLES20.glVertexAttribPointer(oesAUv.get(), 2, GLES20.GL_FLOAT, false, 0, quad[1]);
                            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                            GLES20.glDisableVertexAttribArray(oesAPos.get());
                            GLES20.glDisableVertexAttribArray(oesAUv.get());
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

                            if (renderedNow == 0) {
                                try {
                                    faceunity.fuOnCameraChange();
                                } catch (Throwable ignored) {
                                }
                                applyVideoTextureMatrix();
                            }
                            // 已直立的 2D 纹理进 Nama（ItemsEx2 与导出路径一致，避免 fuRenderTexture 花屏）
                            renderRet[0] = faceunity.fuRenderToTexture(
                                    fboTexId.get(),
                                    outW,
                                    outH,
                                    fid,
                                    new int[]{beautyHandle},
                                    0
                            );
                            if (renderRet[0] > 0 && renderedNow == 0) {
                                renderRet[0] = faceunity.fuRenderToTexture(
                                        fboTexId.get(),
                                        outW,
                                        outH,
                                        fid + 1,
                                        new int[]{beautyHandle},
                                        0
                                );
                            }
                            if (renderRet[0] <= 0 || warmup) {
                                return;
                            }

                            EGLDisplay dpy = EGL14.eglGetCurrentDisplay();
                            EGLContext ctx = EGL14.eglGetCurrentContext();
                            EGLSurface oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
                            EGLSurface oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
                            EGLSurface encEgl = encoderEglSurfaceRef.get();
                            if (encEgl == null) {
                                return;
                            }
                            if (!EGL14.eglMakeCurrent(dpy, encEgl, encEgl, ctx)) {
                                Log.w(TAG, "makeCurrent encoder failed");
                                return;
                            }
                            try {
                                GLES20.glViewport(0, 0, outW, outH);
                                GLES20.glClearColor(0f, 0f, 0f, 1f);
                                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                                GLES20.glUseProgram(drawProgram.get());
                                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, renderRet[0]);
                                GLES20.glUniform1i(drawUTex.get(), 0);
                                GLES20.glEnableVertexAttribArray(drawAPos.get());
                                GLES20.glVertexAttribPointer(drawAPos.get(), 2, GLES20.GL_FLOAT, false, 0, quad[0]);
                                GLES20.glEnableVertexAttribArray(drawAUv.get());
                                GLES20.glVertexAttribPointer(drawAUv.get(), 2, GLES20.GL_FLOAT, false, 0, quad[1]);
                                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                                GLES20.glDisableVertexAttribArray(drawAPos.get());
                                GLES20.glDisableVertexAttribArray(drawAUv.get());
                                EGLExt.eglPresentationTimeANDROID(dpy, encEgl, ptsNs);
                                EGL14.eglSwapBuffers(dpy, encEgl);
                                // 记录解码 PTS：部分机型忽略 eglPresentationTime，墙钟会把 8s 拉成 ~17s
                                ptsQueue.offer(ptsForQueue);
                            } finally {
                                EGL14.eglMakeCurrent(dpy, oldDraw, oldRead, ctx);
                            }
                        });

                        // warmup 也计入 rendered，用于跳过前两帧编码
                        rendered++;
                        if (renderRet[0] <= 0 && !warmup) {
                            // 渲染失败不推进进度也无所谓
                        }
                        if (progress != null && durationUs > 0) {
                            float ratio = Math.min(0.99f, Math.max(0f, ptsUs / (float) durationUs));
                            try {
                                progress.onProgress(ratio);
                            } catch (Throwable ignored) {
                            }
                        }
                    } else {
                        decoder.releaseOutputBuffer(outIndex, false);
                    }

                    DrainResult d = drain(encoder, muxer, encInfo, muxerStarted, muxVideoTrack,
                            muxAudioTrack, audioTrack, audioFormat, nextPtsUs, ptsQueue);
                    muxerStarted = d.muxerStarted;
                    muxVideoTrack = d.muxVideoTrack;
                    muxAudioTrack = d.muxAudioTrack;
                    written += d.written;
                    nextPtsUs = d.nextPtsUs;
                    if (d.eos) {
                        outputEos = true;
                    }

                    if (decEos && !encoderEos) {
                        try {
                            encoder.signalEndOfInputStream();
                        } catch (Throwable t) {
                            Log.w(TAG, "signalEndOfInputStream", t);
                        }
                        encoderEos = true;
                    }
                } else if (inputEos && !encoderEos) {
                    try {
                        encoder.signalEndOfInputStream();
                    } catch (Throwable t) {
                        Log.w(TAG, "signalEndOfInputStream", t);
                    }
                    encoderEos = true;
                }

                DrainResult d = drain(encoder, muxer, encInfo, muxerStarted, muxVideoTrack,
                        muxAudioTrack, audioTrack, audioFormat, nextPtsUs, ptsQueue);
                muxerStarted = d.muxerStarted;
                muxVideoTrack = d.muxVideoTrack;
                muxAudioTrack = d.muxAudioTrack;
                written += d.written;
                nextPtsUs = d.nextPtsUs;
                if (d.eos) {
                    outputEos = true;
                }
            }

            for (int i = 0; i < 64 && !outputEos; i++) {
                DrainResult d = drain(encoder, muxer, encInfo, muxerStarted, muxVideoTrack,
                        muxAudioTrack, audioTrack, audioFormat, nextPtsUs, ptsQueue);
                muxerStarted = d.muxerStarted;
                muxVideoTrack = d.muxVideoTrack;
                muxAudioTrack = d.muxAudioTrack;
                written += d.written;
                nextPtsUs = d.nextPtsUs;
                if (d.eos) {
                    break;
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (muxerStarted && audioTrack >= 0 && muxAudioTrack >= 0) {
                extractor.unselectTrack(videoTrack);
                extractor.selectTrack(audioTrack);
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
                MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();
                while (true) {
                    aInfo.offset = 0;
                    aInfo.size = extractor.readSampleData(buffer, 0);
                    if (aInfo.size < 0) {
                        break;
                    }
                    aInfo.presentationTimeUs = extractor.getSampleTime();
                    aInfo.flags = extractor.getSampleFlags();
                    muxer.writeSampleData(muxAudioTrack, buffer, aInfo);
                    extractor.advance();
                }
            }

            Log.i(TAG, "gpu export done rendered=" + rendered + " written=" + written
                    + " " + outW + "x" + outH);
            int minFrames = 2;
            if (durationUs > 0 && frameDurationUs > 0) {
                minFrames = Math.max(2, (int) (durationUs / frameDurationUs * 0.3));
            }
            if (rendered < minFrames || written < minFrames
                    || !outFile.exists() || outFile.length() < 100) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
                throw new IOException("GPU 导出失败：rendered=" + rendered + " written=" + written);
            }
            if (progress != null) {
                try {
                    progress.onProgress(1f);
                } catch (Throwable ignored) {
                }
            }
            return outFile.getAbsolutePath();
        } finally {
            try {
                if (decoder != null) {
                    decoder.stop();
                    decoder.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (muxer != null) {
                    muxer.release();
                }
            } catch (Throwable ignored) {
            }
            extractor.release();
            try {
                Surface ds = decoderSurfaceRef.getAndSet(null);
                if (ds != null) {
                    ds.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (encoderInputSurface != null) {
                    encoderInputSurface.release();
                }
            } catch (Throwable ignored) {
            }

            // 在相机 GL 上拆资源并恢复预览矩阵，禁止 deviceLost
            try {
                runOnGl(glView, () -> {
                    try {
                        EGLSurface encEgl = encoderEglSurfaceRef.getAndSet(null);
                        if (encEgl != null && encEgl != EGL14.EGL_NO_SURFACE) {
                            EGLDisplay dpy = EGL14.eglGetCurrentDisplay();
                            EGL14.eglDestroySurface(dpy, encEgl);
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        SurfaceTexture st = surfaceTextureRef.getAndSet(null);
                        if (st != null) {
                            st.release();
                        }
                    } catch (Throwable ignored) {
                    }
                    int oes = oesTexId.getAndSet(0);
                    if (oes > 0) {
                        GLES20.glDeleteTextures(1, new int[]{oes}, 0);
                    }
                    int fboTex = fboTexId.getAndSet(0);
                    int fbo = fboId.getAndSet(0);
                    if (fbo > 0) {
                        GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
                    }
                    if (fboTex > 0) {
                        GLES20.glDeleteTextures(1, new int[]{fboTex}, 0);
                    }
                    int oesProg = oesProgram.getAndSet(0);
                    if (oesProg > 0) {
                        GLES20.glDeleteProgram(oesProg);
                    }
                    int prog = drawProgram.getAndSet(0);
                    if (prog > 0) {
                        GLES20.glDeleteProgram(prog);
                    }
                    try {
                        faceunity.fuOnCameraChange();
                        faceunity.fuSetFaceProcessorDetectMode(1);
                        // 回到相机预览常用矩阵状态（具体 facing 由 resume 再写）
                        faceunity.fuSetInputCameraBufferMatrixState(0);
                        faceunity.fuSetInputCameraTextureMatrixState(0);
                        faceunity.fuSetOutputMatrixState(0);
                        MediaFuSetup.ensureBeautyOn(beautyHandle);
                    } catch (Throwable t) {
                        Log.w(TAG, "restore preview nama", t);
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "cleanup gl", t);
            }
            try {
                stThread.quitSafely();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void applyVideoTextureMatrix() {
        try {
            // FBO 已直立：Nama 用 identity，避免和相机预览矩阵互相污染
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

    private static void waitFrameAvailable(Object lock, AtomicBoolean available, long timeoutMs)
            throws InterruptedException, IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (!available.get()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    throw new IOException("等待解码帧超时");
                }
                lock.wait(Math.min(left, 50));
            }
            available.set(false);
        }
    }

    private static void runOnGl(GLSurfaceView glView, Runnable action) throws Exception {
        final Object lock = new Object();
        final Exception[] err = new Exception[1];
        final boolean[] done = {false};
        glView.queueEvent(() -> {
            try {
                synchronized (NamaRenderLock.LOCK) {
                    action.run();
                }
            } catch (Throwable t) {
                err[0] = t instanceof Exception ? (Exception) t : new Exception(t);
            } finally {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        try {
            glView.requestRender();
        } catch (Throwable ignored) {
        }
        long deadline = System.currentTimeMillis() + GL_TIMEOUT_MS;
        synchronized (lock) {
            while (!done[0] && System.currentTimeMillis() < deadline) {
                lock.wait(50);
            }
        }
        if (!done[0]) {
            throw new IOException("export GL 超时");
        }
        if (err[0] != null) {
            throw err[0];
        }
    }

    private static EGLConfig queryCurrentConfig(EGLDisplay dpy, EGLSurface surface) {
        int[] val = new int[1];
        if (!EGL14.eglQuerySurface(dpy, surface, EGL14.EGL_CONFIG_ID, val, 0)) {
            throw new RuntimeException("eglQuerySurface CONFIG_ID failed");
        }
        int[] attrib = {EGL14.EGL_CONFIG_ID, val[0], EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(dpy, attrib, 0, configs, 0, 1, num, 0) || num[0] <= 0) {
            throw new RuntimeException("eglChooseConfig by id failed");
        }
        return configs[0];
    }

    private static final class DrainResult {
        boolean muxerStarted;
        int muxVideoTrack;
        int muxAudioTrack;
        int written;
        boolean eos;
        long nextPtsUs;
    }

    private static DrainResult drain(
            MediaCodec encoder,
            MediaMuxer muxer,
            MediaCodec.BufferInfo encInfo,
            boolean muxerStarted,
            int muxVideoTrack,
            int muxAudioTrack,
            int audioTrack,
            MediaFormat audioFormat,
            long nextPtsUs,
            ConcurrentLinkedQueue<Long> ptsQueue
    ) throws IOException {
        DrainResult r = new DrainResult();
        r.muxerStarted = muxerStarted;
        r.muxVideoTrack = muxVideoTrack;
        r.muxAudioTrack = muxAudioTrack;
        r.nextPtsUs = nextPtsUs;
        while (true) {
            int encOut = encoder.dequeueOutputBuffer(encInfo, 0);
            if (encOut == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
            if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (r.muxerStarted) {
                    throw new IOException("encoder format changed twice");
                }
                r.muxVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                if (audioTrack >= 0 && audioFormat != null) {
                    r.muxAudioTrack = muxer.addTrack(audioFormat);
                }
                muxer.start();
                r.muxerStarted = true;
                continue;
            }
            if (encOut >= 0) {
                ByteBuffer encoded = encoder.getOutputBuffer(encOut);
                if (encoded != null && encInfo.size > 0 && r.muxerStarted
                        && (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    // 优先用解码 PTS 队列，避免机型忽略 eglPresentationTime 导致时长翻倍
                    Long queued = ptsQueue != null ? ptsQueue.poll() : null;
                    long pts = queued != null ? queued : encInfo.presentationTimeUs;
                    if (pts <= r.nextPtsUs) {
                        pts = r.nextPtsUs + 1;
                    }
                    MediaCodec.BufferInfo copy = new MediaCodec.BufferInfo();
                    copy.set(encInfo.offset, encInfo.size, pts, encInfo.flags);
                    encoded.position(copy.offset);
                    encoded.limit(copy.offset + copy.size);
                    muxer.writeSampleData(r.muxVideoTrack, encoded, copy);
                    r.written++;
                    r.nextPtsUs = pts;
                }
                boolean eos = (encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                encoder.releaseOutputBuffer(encOut, false);
                if (eos) {
                    r.eos = true;
                    break;
                }
            }
        }
        return r;
    }

    private static int[] scaleEven(int w, int h, int maxSide) {
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

    private static int buildDrawProgram() {
        String vs =
                "attribute vec4 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main(){\n" +
                "  gl_Position=aPosition;\n" +
                "  vTexCoord=aTexCoord;\n" +
                "}\n";
        String fs =
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform sampler2D uTexture;\n" +
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
