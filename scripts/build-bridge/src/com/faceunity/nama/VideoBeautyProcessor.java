package com.faceunity.nama;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import com.faceunity.wrapper.faceunity;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 离线视频美颜：decode → fuRenderToRgbaImage → 竖直翻回 → NV21→NV12 encode。
 * 美颜渲染应在已有 Nama 的 GL 线程执行（视频/相机 GLSurfaceView），
 * 避免独立 MediaGlContext 与预览 EGL 分裂导致「时长对但只有一帧」。
 */
final class VideoBeautyProcessor {

    private static final String TAG = "FaceUnity-VideoExport";
    private static final int MAX_SIDE = 1920;
    private static final long MAX_DURATION_US = 60_000_000L;

    interface GlBridge {
        void run(Runnable action) throws Exception;
    }

    interface ProgressListener {
        void onProgress(float ratio);
    }

    private VideoBeautyProcessor() {
    }

    static String process(android.content.Context context, String path, int beautyHandle, File cacheDir)
            throws Exception {
        return processWithGl(context, path, beautyHandle, cacheDir, null, null);
    }

    static String process(android.content.Context context, String path, int beautyHandle, File cacheDir,
                          ProgressListener progress) throws Exception {
        return processWithGl(context, path, beautyHandle, cacheDir, null, progress);
    }

    /** 在指定 GLSurfaceView 的 GL 线程上跑 Nama 渲染（对齐 ImageBeautyProcessor.processOnGlView） */
    static String processOnGlView(
            GLSurfaceView glView,
            android.content.Context context,
            String path,
            int beautyHandle,
            File cacheDir
    ) throws Exception {
        return processOnGlView(glView, context, path, beautyHandle, cacheDir, null);
    }

    static String processOnGlView(
            GLSurfaceView glView,
            android.content.Context context,
            String path,
            int beautyHandle,
            File cacheDir,
            ProgressListener progress
    ) throws Exception {
        if (glView == null) {
            return process(context, path, beautyHandle, cacheDir, progress);
        }
        // 优先 GPU（相机 GL + Surface 硬解硬编）；失败回退 CPU buffer，保证可导出
        try {
            long t0 = System.currentTimeMillis();
            String out = VideoBeautyGpuExporter.process(glView, context, path, beautyHandle, cacheDir, progress);
            Log.i(TAG, "GPU export ok in " + (System.currentTimeMillis() - t0) + "ms -> " + out);
            return out;
        } catch (Throwable gpuErr) {
            Log.w(TAG, "GPU export failed, fallback CPU: " + gpuErr.getMessage(), gpuErr);
        }
        GlBridge bridge = action -> {
            final Object lock = new Object();
            final Exception[] err = new Exception[1];
            final boolean[] done = {false};
            glView.queueEvent(() -> {
                try {
                    action.run();
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
            long deadline = System.currentTimeMillis() + 15_000L;
            synchronized (lock) {
                while (!done[0] && System.currentTimeMillis() < deadline) {
                    lock.wait(100);
                }
            }
            if (!done[0]) {
                throw new IOException("export GL 超时");
            }
            if (err[0] != null) {
                throw err[0];
            }
        };
        return processWithGl(context, path, beautyHandle, cacheDir, bridge, progress);
    }

    private static String processWithGl(
            android.content.Context context,
            String path,
            int beautyHandle,
            File cacheDir,
            GlBridge glBridge
    ) throws Exception {
        return processWithGl(context, path, beautyHandle, cacheDir, glBridge, null);
    }

    private static String processWithGl(
            android.content.Context context,
            String path,
            int beautyHandle,
            File cacheDir,
            GlBridge glBridge,
            ProgressListener progress
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
                ? videoFormat.getLong(MediaFormat.KEY_DURATION) : 0;
        if (durationUs > MAX_DURATION_US) {
            extractor.release();
            throw new IOException("视频超过 60 秒限制");
        }

        int srcW = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
        int srcH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int rotation = videoFormat.containsKey(MediaFormat.KEY_ROTATION)
                ? videoFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
        int displayW = srcW;
        int displayH = srcH;
        if (rotation == 90 || rotation == 270) {
            displayW = srcH;
            displayH = srcW;
        }
        int[] outSize = scaleEven(displayW, displayH, MAX_SIDE);
        int outW = outSize[0];
        int outH = outSize[1];
        int fps = 30;
        if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try {
                fps = Math.max(1, Math.min(30, videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)));
            } catch (Exception ignored) {
                fps = 30;
            }
        }
        long frameDurationUs = Math.max(1L, 1_000_000L / fps);

        File dir = cacheDir != null ? cacheDir : new File(System.getProperty("java.io.tmpdir"));
        if (!dir.exists() && !dir.mkdirs()) {
            extractor.release();
            throw new IOException("无法创建缓存目录");
        }
        File outFile = new File(dir, "fu_video_" + System.currentTimeMillis() + ".mp4");
        if (outFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
        }

        MediaMuxer muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        MediaFormat encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH);
        encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        encFormat.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(2_000_000, outW * outH * 4));
        encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, outW * outH * 3 / 2);
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        extractor.selectTrack(videoTrack);
        try {
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        } catch (Exception ignored) {
        }
        MediaCodec decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME));
        decoder.configure(videoFormat, null, null, 0);
        decoder.start();

        final GlBridge bridge = glBridge != null ? glBridge : action -> {
            MediaGlContext.makeCurrent(outW, outH);
            action.run();
        };

        bridge.run(() -> {
            faceunity.fuSetFaceProcessorDetectMode(0);
            MediaFuSetup.enableAdvancedBeautyRuntime(beautyHandle);
            try {
                faceunity.fuOnCameraChange();
            } catch (Throwable ignored) {
            }
            MediaFuSetup.applyStillLikeBufferMatrix();
            MediaFuSetup.ensureBeautyOn(beautyHandle);
            faceunity.fuSetOutputResolution(outW, outH);
        });

        MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
        boolean inputEos = false;
        boolean encoderEosSignaled = false;
        boolean outputEos = false;
        boolean muxerStarted = false;
        int muxVideoTrack = -1;
        int muxAudioTrack = -1;
        int frameId = 400000;
        int renderedFrames = 0;
        int fedFrames = 0;
        int writtenSamples = 0;
        long nextMuxPtsUs = 0L;
        long lastDecoderPtsUs = -1L;
        byte[] scaledRgba = new byte[outW * outH * 4];
        byte[] nv21 = new byte[outW * outH * 3 / 2];
        byte[] nv12 = new byte[outW * outH * 3 / 2];

        try {
            while (!outputEos) {
                if (!inputEos) {
                    int inIndex = decoder.dequeueInputBuffer(10000);
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

                int outIndex = decoder.dequeueOutputBuffer(decInfo, 10000);
                if (outIndex >= 0) {
                    boolean decEos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (decInfo.size > 0) {
                        int[] wh = new int[2];
                        byte[] rgba = decodeFrameRgba(decoder, outIndex, decInfo, wh);
                        int iw = wh[0];
                        int ih = wh[1];
                        if (rgba != null && iw > 0 && ih > 0) {
                            int applyRot = BeautyVideoGLView.rotationForDecodedFrame(iw, ih, rotation);
                            if (applyRot == 90 || applyRot == 270 || applyRot == 180) {
                                rgba = BeautyVideoGLView.rotateRgba(rgba, iw, ih, applyRot);
                                if (applyRot == 90 || applyRot == 270) {
                                    int t = iw;
                                    iw = ih;
                                    ih = t;
                                }
                            }
                            BeautyVideoGLView.forceOpaqueRgba(rgba);
                            scaleRgba(rgba, iw, ih, scaledRgba, outW, outH);
                            BeautyVideoGLView.forceOpaqueRgba(scaledRgba);

                            final byte[] renderBuf = scaledRgba;
                            final int[] renderRet = {-1};
                            final int fid = ++frameId;
                            final boolean first = renderedFrames == 0;
                            final byte[] srcForRetry = rgba;
                            final int srcW2 = iw;
                            final int srcH2 = ih;
                            bridge.run(() -> {
                                // 对齐静图 / 预览：StillLike matrix + 输出翻正
                                if (first) {
                                    try {
                                        faceunity.fuOnCameraChange();
                                    } catch (Throwable ignored) {
                                    }
                                }
                                MediaFuSetup.applyStillLikeBufferMatrix();
                                MediaFuSetup.ensureBeautyOn(beautyHandle);
                                faceunity.fuSetFaceProcessorDetectMode(0);
                                faceunity.fuSetOutputResolution(outW, outH);
                                renderRet[0] = faceunity.fuRenderToRgbaImage(
                                        renderBuf, outW, outH, fid, new int[]{beautyHandle},
                                        faceunity.FU_ADM_FLAG_ENABLE_READBACK
                                );
                                if (renderRet[0] >= 0 && first) {
                                    MediaFuSetup.tryApplySetUseAfterRender(beautyHandle);
                                    scaleRgba(srcForRetry, srcW2, srcH2, renderBuf, outW, outH);
                                    BeautyVideoGLView.forceOpaqueRgba(renderBuf);
                                    faceunity.fuRenderToRgbaImage(
                                            renderBuf, outW, outH, fid + 1, new int[]{beautyHandle},
                                            faceunity.FU_ADM_FLAG_ENABLE_READBACK
                                    );
                                }
                                if (renderRet[0] >= 0) {
                                    BeautyVideoGLView.flipRgbaVertical(renderBuf, outW, outH);
                                }
                                BeautyVideoGLView.forceOpaqueRgba(renderBuf);
                                GLES20.glFinish();
                            });
                            frameId = fid + (first ? 1 : 0);

                            // StillLike 后已 flipRgbaVertical 成直立缓冲，勿再按 GL readPixels 翻一次
                            Nv21Utils.rgbaToNv21(scaledRgba, nv21, outW, outH, false);
                            nv21ToNv12(nv21, nv12, outW, outH);

                            long ptsUs = decInfo.presentationTimeUs;
                            if (ptsUs <= lastDecoderPtsUs) {
                                ptsUs = lastDecoderPtsUs + frameDurationUs;
                            }
                            lastDecoderPtsUs = ptsUs;
                            if (feedFrame(encoder, nv12, ptsUs)) {
                                fedFrames++;
                                renderedFrames++;
                            }
                            if (progress != null && durationUs > 0) {
                                float ratio = Math.min(0.99f, Math.max(0f, ptsUs / (float) durationUs));
                                try {
                                    progress.onProgress(ratio);
                                } catch (Throwable ignored) {
                                }
                            }
                            DrainResult d = drain(encoder, muxer, encInfo, muxerStarted, muxVideoTrack,
                                    muxAudioTrack, audioTrack, audioFormat, nextMuxPtsUs, frameDurationUs);
                            muxerStarted = d.muxerStarted;
                            muxVideoTrack = d.muxVideoTrack;
                            muxAudioTrack = d.muxAudioTrack;
                            writtenSamples += d.written;
                            nextMuxPtsUs = d.nextPtsUs;
                            if (d.eos) {
                                outputEos = true;
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false);
                    if (decEos && !encoderEosSignaled) {
                        signalEncoderEos(encoder);
                        encoderEosSignaled = true;
                    }
                } else if (inputEos && !encoderEosSignaled) {
                    signalEncoderEos(encoder);
                    encoderEosSignaled = true;
                }

                DrainResult d = drain(encoder, muxer, encInfo, muxerStarted, muxVideoTrack,
                        muxAudioTrack, audioTrack, audioFormat, nextMuxPtsUs, frameDurationUs);
                muxerStarted = d.muxerStarted;
                muxVideoTrack = d.muxVideoTrack;
                muxAudioTrack = d.muxAudioTrack;
                writtenSamples += d.written;
                nextMuxPtsUs = d.nextPtsUs;
                if (d.eos) {
                    outputEos = true;
                }
            }

            for (int i = 0; i < 64 && !outputEos; i++) {
                DrainResult d = drain(encoder, muxer, encInfo, muxerStarted, muxVideoTrack,
                        muxAudioTrack, audioTrack, audioFormat, nextMuxPtsUs, frameDurationUs);
                muxerStarted = d.muxerStarted;
                muxVideoTrack = d.muxVideoTrack;
                muxAudioTrack = d.muxAudioTrack;
                writtenSamples += d.written;
                nextMuxPtsUs = d.nextPtsUs;
                if (d.eos) {
                    break;
                }
                Thread.sleep(5);
            }

            if (muxerStarted && audioTrack >= 0 && muxAudioTrack >= 0) {
                extractor.unselectTrack(videoTrack);
                extractor.selectTrack(audioTrack);
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                ByteBuffer buffer = ByteBuffer.allocate(1024 * 256);
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
        } finally {
            try {
                decoder.stop();
                decoder.release();
            } catch (Exception ignored) {
            }
            try {
                encoder.stop();
                encoder.release();
            } catch (Exception ignored) {
            }
            try {
                muxer.release();
            } catch (Exception ignored) {
            }
            extractor.release();
        }

        Log.i(TAG, "export done rendered=" + renderedFrames + " fed=" + fedFrames
                + " written=" + writtenSamples + " " + outW + "x" + outH + " fps=" + fps
                + " durationUs=" + durationUs + " glBridge=" + (glBridge != null));
        int minFrames = 2;
        if (durationUs > 0 && frameDurationUs > 0) {
            // 至少覆盖约 40% 时长，避免「音频拉满时长、画面只有一两帧」
            minFrames = Math.max(2, (int) (durationUs / frameDurationUs * 0.4));
        }
        if (renderedFrames < minFrames || writtenSamples < minFrames
                || !outFile.exists() || outFile.length() < 100) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
            throw new IOException("导出失败：rendered=" + renderedFrames
                    + " written=" + writtenSamples + " need>=" + minFrames);
        }
        return outFile.getAbsolutePath();
    }

    private static byte[] decodeFrameRgba(MediaCodec decoder, int outIndex, MediaCodec.BufferInfo info, int[] wh) {
        Image image = null;
        try {
            image = decoder.getOutputImage(outIndex);
        } catch (Exception ignored) {
        }
        if (image != null) {
            byte[] rgba = BeautyVideoGLView.yuvImageToRgba(image);
            wh[0] = image.getWidth();
            wh[1] = image.getHeight();
            image.close();
            return rgba;
        }
        ByteBuffer outBuf = decoder.getOutputBuffer(outIndex);
        MediaFormat outFormat = decoder.getOutputFormat();
        if (outBuf == null || outFormat == null) {
            return null;
        }
        int w = outFormat.containsKey(MediaFormat.KEY_WIDTH) ? outFormat.getInteger(MediaFormat.KEY_WIDTH) : 0;
        int h = outFormat.containsKey(MediaFormat.KEY_HEIGHT) ? outFormat.getInteger(MediaFormat.KEY_HEIGHT) : 0;
        int color = outFormat.containsKey(MediaFormat.KEY_COLOR_FORMAT)
                ? outFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT) : 0;
        wh[0] = w;
        wh[1] = h;
        return BeautyVideoGLView.yuvBufferToRgba(outBuf, info.offset, info.size, w, h, color);
    }

    private static boolean feedFrame(MediaCodec encoder, byte[] nv12, long ptsUs) throws InterruptedException {
        int inIndex = -1;
        for (int i = 0; i < 80; i++) {
            inIndex = encoder.dequeueInputBuffer(10000);
            if (inIndex >= 0) {
                break;
            }
            // 输入堵了就先 drain，避免整段只吃进一两帧
            Thread.sleep(5);
        }
        if (inIndex < 0) {
            Log.w(TAG, "drop frame pts=" + ptsUs);
            return false;
        }
        ByteBuffer input = encoder.getInputBuffer(inIndex);
        if (input == null) {
            return false;
        }
        input.clear();
        if (input.capacity() < nv12.length) {
            Log.w(TAG, "encoder buffer too small cap=" + input.capacity() + " need=" + nv12.length);
            encoder.queueInputBuffer(inIndex, 0, 0, ptsUs, 0);
            return false;
        }
        // 拷贝一份，避免部分机型异步读 input 时源数组被下一帧覆盖
        input.put(nv12, 0, nv12.length);
        encoder.queueInputBuffer(inIndex, 0, nv12.length, ptsUs, 0);
        return true;
    }

    private static void signalEncoderEos(MediaCodec encoder) {
        for (int i = 0; i < 50; i++) {
            int idx = encoder.dequeueInputBuffer(10000);
            if (idx >= 0) {
                encoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                return;
            }
        }
        Log.w(TAG, "signalEncoderEos failed");
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
            long frameDurationUs
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
                    long pts = encInfo.presentationTimeUs;
                    if (pts <= r.nextPtsUs) {
                        pts = r.nextPtsUs + 1;
                    }
                    MediaCodec.BufferInfo copy = new MediaCodec.BufferInfo();
                    copy.set(encInfo.offset, encInfo.size, pts, encInfo.flags);
                    encoded.position(copy.offset);
                    encoded.limit(copy.offset + copy.size);
                    muxer.writeSampleData(r.muxVideoTrack, encoded, copy);
                    r.written++;
                    // 勿强制 +frameDuration：错误 fps/墙钟 PTS 会把 8s 拉成 ~17s
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

    private static void nv21ToNv12(byte[] nv21, byte[] nv12, int width, int height) {
        int ySize = width * height;
        System.arraycopy(nv21, 0, nv12, 0, ySize);
        for (int i = 0; i < ySize / 2; i += 2) {
            nv12[ySize + i] = nv21[ySize + i + 1];
            nv12[ySize + i + 1] = nv21[ySize + i];
        }
    }

    private static void scaleRgba(byte[] src, int sw, int sh, byte[] dst, int dw, int dh) {
        for (int y = 0; y < dh; y++) {
            int sy = y * sh / dh;
            for (int x = 0; x < dw; x++) {
                int sx = x * sw / dw;
                int si = (sy * sw + sx) * 4;
                int di = (y * dw + x) * 4;
                dst[di] = src[si];
                dst[di + 1] = src[si + 1];
                dst[di + 2] = src[si + 2];
                dst[di + 3] = src[si + 3];
            }
        }
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
}
