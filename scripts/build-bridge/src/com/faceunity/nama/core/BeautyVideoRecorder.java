package com.faceunity.nama.core;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将美颜后的 NV21 帧编码为 MP4（含 AAC 音轨），并写入系统相册。
 * <p>
 * 停录必须异步：旧实现在 UI 线程 drainEncoder 死等 EOS + 同步拷相册，偶发 ANR / 未保存。
 */
public final class BeautyVideoRecorder {

    private static final String TAG = "FU-VideoRecorder";
    private static final long STOP_DRAIN_BUDGET_MS = 2500L;
    private static final long OFFER_INPUT_TIMEOUT_US = 0L;
    private static final long DRAIN_IDLE_TIMEOUT_US = 0L;
    private static final long DRAIN_EOS_TIMEOUT_US = 10_000L;

    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_CHANNEL_COUNT = 1;
    private static final int AUDIO_BIT_RATE = 128_000;

    public interface Callback {
        void onSuccess(String path);

        void onError(String message);
    }

    private final Object lock = new Object();
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean audioRunning = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HandlerThread stopThread;
    private Handler stopHandler;

    private MediaCodec encoder;
    private MediaCodec audioEncoder;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private MediaMuxer muxer;
    private int trackIndex = -1;
    private int audioTrackIndex = -1;
    private boolean videoTrackAdded;
    private boolean audioTrackAdded;
    private boolean muxerStarted;
    private boolean wantAudio;
    private int encodedFrameCount;
    private File tempFile;
    private int width;
    private int height;
    private int orientationHint;
    private long startUs;
    /** 音视频 PTS 共同墙钟起点（nanoTime） */
    private long avSyncOriginNs;
    private byte[] nv12Scratch;
    /** muxer 未 start 前暂存视频样，避免等音频轨时丢帧 */
    private final java.util.ArrayList<PendingSample> pendingVideo = new java.util.ArrayList<>();

    private static final class PendingSample {
        final byte[] data;
        final MediaCodec.BufferInfo info;

        PendingSample(ByteBuffer src, MediaCodec.BufferInfo info) {
            this.data = new byte[info.size];
            src.position(info.offset);
            src.get(this.data);
            this.info = new MediaCodec.BufferInfo();
            this.info.set(0, info.size, info.presentationTimeUs, info.flags);
        }
    }

    public boolean isRecording() {
        return recording.get();
    }

    void start(int frameW, int frameH) throws Exception {
        start(null, frameW, frameH, 0, 0L);
    }

    void start(int frameW, int frameH, int orientationDegrees) throws Exception {
        start(null, frameW, frameH, orientationDegrees, 0L);
    }

    void start(int frameW, int frameH, int orientationDegrees, long avSyncOriginNs) throws Exception {
        start(null, frameW, frameH, orientationDegrees, avSyncOriginNs);
    }

    void start(Context context, int frameW, int frameH, int orientationDegrees, long avSyncOriginNs) throws Exception {
        synchronized (lock) {
            if (recording.get() || stopping.get()) {
                throw new IllegalStateException("正在录制或收尾中");
            }
            int w = Math.max(16, frameW & ~1);
            int h = Math.max(16, frameH & ~1);
            width = w;
            height = h;
            orientationHint = ((orientationDegrees % 360) + 360) % 360;
            // Android 10+ 禁止直写公共 DCIM；先落应用缓存，停录再走 MediaStore/相册
            tempFile = resolveMuxerOutputFile(context);
            if (tempFile == null) {
                File cacheDir = context != null ? context.getCacheDir() : null;
                tempFile = File.createTempFile("fu_rec_", ".mp4", cacheDir);
                if (tempFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tempFile.delete();
                }
            }

            // 输入是 NV12(SemiPlanar)；老机勿硬设 Baseline profile（易出无法解析的码流）
            int bitRate = Math.max(1_200_000, Math.min(4_000_000, width * height * 3));
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    format.setInteger(MediaFormat.KEY_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                } catch (Throwable ignored) {
                }
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            try {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Throwable t) {
                Log.w(TAG, "configure failed, retry plain", t);
                format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            encoder.start();

            try {
                muxer = new MediaMuxer(tempFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (Exception muxerOpen) {
                Log.w(TAG, "muxer open failed path=" + tempFile.getAbsolutePath(), muxerOpen);
                File fallback = resolveCacheMuxerFile(context);
                if (fallback == null) {
                    throw muxerOpen;
                }
                tempFile = fallback;
                muxer = new MediaMuxer(tempFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }
            if (orientationHint != 0) {
                muxer.setOrientationHint(orientationHint);
            }
            trackIndex = -1;
            audioTrackIndex = -1;
            videoTrackAdded = false;
            audioTrackAdded = false;
            muxerStarted = false;
            encodedFrameCount = 0;
            startUs = 0;
            avSyncOriginNs = avSyncOriginNs > 0L ? avSyncOriginNs : System.nanoTime();
            nv12Scratch = new byte[width * height * 3 / 2];
            wantAudio = false;
            if (!DeviceQuirk.wantsVideoOnlyRecord()) {
                try {
                    setupAudioLocked();
                    wantAudio = true;
                } catch (Throwable t) {
                    Log.w(TAG, "audio setup failed, video-only", t);
                    releaseAudioLocked();
                    wantAudio = false;
                }
            } else {
                Log.i(TAG, "legacy encode: video-only (skip audio)");
            }
            recording.set(true);
            if (wantAudio) {
                audioRunning.set(true);
                audioThread = new Thread(this::audioCaptureLoop, "fu-rec-audio");
                audioThread.start();
            }
            Log.i(TAG, "start " + width + "x" + height
                    + " orientationHint=" + orientationHint
                    + " audio=" + wantAudio
                    + " -> " + tempFile.getAbsolutePath());
        }
    }

    private void setupAudioLocked() throws Exception {
        MediaFormat aFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
        aFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        aFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
        aFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEncoder.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();

        int minBuf = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            throw new IllegalStateException("AudioRecord minBuf=" + minBuf);
        }
        int bufSize = Math.max(minBuf, 4096) * 2;
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
        );
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioRecord not initialized");
        }
        audioRecord.startRecording();
        if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IllegalStateException("AudioRecord failed to start");
        }
    }

    private void releaseAudioLocked() {
        audioRunning.set(false);
        Thread t = audioThread;
        audioThread = null;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Throwable ignored) {
            }
            try {
                audioRecord.release();
            } catch (Throwable ignored) {
            }
            audioRecord = null;
        }
        if (audioEncoder != null) {
            try {
                audioEncoder.stop();
            } catch (Throwable ignored) {
            }
            try {
                audioEncoder.release();
            } catch (Throwable ignored) {
            }
            audioEncoder = null;
        }
    }

    private void audioCaptureLoop() {
        byte[] pcm = new byte[2048];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (audioRunning.get()) {
            AudioRecord ar = audioRecord;
            MediaCodec aEnc = audioEncoder;
            if (ar == null || aEnc == null) {
                break;
            }
            int n;
            try {
                n = ar.read(pcm, 0, pcm.length);
            } catch (Throwable t) {
                Log.w(TAG, "audio read", t);
                break;
            }
            if (n <= 0) {
                continue;
            }
            synchronized (lock) {
                if (!recording.get() && !stopping.get()) {
                    break;
                }
                try {
                    int inIndex = aEnc.dequeueInputBuffer(0);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = aEnc.getInputBuffer(inIndex);
                        if (inBuf != null) {
                            inBuf.clear();
                            int len = Math.min(n, inBuf.capacity());
                            inBuf.put(pcm, 0, len);
                            long origin = avSyncOriginNs > 0L ? avSyncOriginNs : System.nanoTime();
                            long pts = Math.max(0L, (System.nanoTime() - origin) / 1000L);
                            aEnc.queueInputBuffer(inIndex, 0, len, pts, 0);
                        }
                    }
                    drainAudioEncoderLocked(false, info);
                } catch (Throwable t) {
                    Log.w(TAG, "audio encode", t);
                }
            }
        }
    }

    public void offerNv21(byte[] nv21, int frameW, int frameH, long ptsUs) {
        if (!recording.get() || stopping.get() || nv21 == null) {
            return;
        }
        synchronized (lock) {
            if (!recording.get() || stopping.get() || encoder == null) {
                return;
            }
            try {
                if ((frameW & ~1) != width || (frameH & ~1) != height) {
                    return;
                }
                // 统一 NV21→NV12（SemiPlanar）；直喂 NV21 人脸发蓝
                nv21ToNv12(nv21, nv12Scratch, width, height);
                final byte[] yuv = nv12Scratch;
                int inIndex = encoder.dequeueInputBuffer(OFFER_INPUT_TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer inBuf = encoder.getInputBuffer(inIndex);
                    if (inBuf != null) {
                        inBuf.clear();
                        int capacity = inBuf.capacity();
                        int need = width * height * 3 / 2;
                        int len = Math.min(need, Math.min(yuv.length, capacity));
                        inBuf.put(yuv, 0, len);
                        long ts = ptsUs;
                        if (startUs == 0) {
                            startUs = ts;
                        }
                        long relPts = Math.max(0L, ts - startUs);
                        encoder.queueInputBuffer(inIndex, 0, len, relPts, 0);
                    }
                }
                drainVideoEncoderLocked(false, 0L);
            } catch (Exception e) {
                Log.e(TAG, "offerNv21", e);
            }
        }
    }

    /**
     * 异步停录：立刻停止收帧，后台 drain/封装/写相册，避免卡 UI。
     */
    public void stop(Context context, Callback callback) {
        if (!recording.getAndSet(false)) {
            if (stopping.get()) {
                return;
            }
            notifyError(callback, "未在录制");
            return;
        }
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        audioRunning.set(false);
        final Context appCtx = context != null ? context.getApplicationContext() : null;
        ensureStopThread();
        stopHandler.post(() -> finishStopOnWorker(appCtx, callback));
    }

    public void cancel() {
        recording.set(false);
        stopping.set(true);
        audioRunning.set(false);
        ensureStopThread();
        stopHandler.post(() -> {
            synchronized (lock) {
                releaseQuietly();
                stopping.set(false);
            }
        });
    }

    private void finishStopOnWorker(Context context, Callback callback) {
        // 先停音频线程（勿持 lock，避免与 audioCaptureLoop 死锁）
        audioRunning.set(false);
        Thread audioT = audioThread;
        audioThread = null;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Throwable ignored) {
            }
        }
        if (audioT != null) {
            try {
                audioT.join(800);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        File outFile;
        boolean hadMuxed;
        int frames;
        synchronized (lock) {
            try {
                signalAudioEndOfStreamLocked();
                drainAudioEncoderLocked(true, new MediaCodec.BufferInfo());
                signalEndOfStreamLocked();
                drainVideoEncoderLocked(true, System.currentTimeMillis() + STOP_DRAIN_BUDGET_MS);
                drainAudioEncoderLocked(true, new MediaCodec.BufferInfo());
                forceStartMuxerVideoOnlyIfNeededLocked();
                drainVideoEncoderLocked(true, System.currentTimeMillis() + 500L);
            } catch (Throwable t) {
                Log.e(TAG, "finishStop drain", t);
            }
            hadMuxed = muxerStarted;
            frames = encodedFrameCount;
            outFile = tempFile;
            tempFile = null;
            try {
                if (encoder != null) {
                    try {
                        encoder.stop();
                    } catch (Throwable ignored) {
                    }
                    try {
                        encoder.release();
                    } catch (Throwable ignored) {
                    }
                    encoder = null;
                }
                if (audioEncoder != null) {
                    try {
                        audioEncoder.stop();
                    } catch (Throwable ignored) {
                    }
                    try {
                        audioEncoder.release();
                    } catch (Throwable ignored) {
                    }
                    audioEncoder = null;
                }
                if (muxer != null) {
                    try {
                        if (muxerStarted) {
                            muxer.stop();
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "muxer.stop", t);
                    }
                    try {
                        muxer.release();
                    } catch (Throwable ignored) {
                    }
                    muxer = null;
                }
            } finally {
                muxerStarted = false;
                trackIndex = -1;
                audioTrackIndex = -1;
                videoTrackAdded = false;
                audioTrackAdded = false;
                nv12Scratch = null;
                pendingVideo.clear();
                if (audioRecord != null) {
                    try {
                        audioRecord.release();
                    } catch (Throwable ignored) {
                    }
                    audioRecord = null;
                }
            }
        }

        try {
            if (!hadMuxed || frames <= 0 || outFile == null || !outFile.exists() || outFile.length() < 32) {
                if (outFile != null && outFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                }
                notifyError(callback, frames <= 0 ? "录制内容为空" : "保存视频失败");
                return;
            }
            String galleryPath = writeVideoToGallery(context, outFile);
            if (galleryPath == null || galleryPath.isEmpty()) {
                if (outFile != null && outFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                }
                notifyError(callback, "保存视频失败");
            } else {
                // 已直接写到相册目录则勿删；仅删除临时落点
                if (outFile != null && outFile.exists()
                        && !galleryPath.equals(outFile.getAbsolutePath())) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                }
                notifySuccess(callback, galleryPath);
            }
        } catch (Throwable t) {
            Log.e(TAG, "finishStop save", t);
            if (outFile != null && outFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
            notifyError(callback, t.getMessage() != null ? t.getMessage() : "stop failed");
        } finally {
            stopping.set(false);
        }
    }

    private void signalAudioEndOfStreamLocked() {
        if (audioEncoder == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + 500L;
        while (System.currentTimeMillis() < deadline) {
            try {
                int inIndex = audioEncoder.dequeueInputBuffer(20_000);
                if (inIndex >= 0) {
                    long origin = avSyncOriginNs > 0L ? avSyncOriginNs : System.nanoTime();
                    long eosPts = Math.max(0L, (System.nanoTime() - origin) / 1000L);
                    audioEncoder.queueInputBuffer(inIndex, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "signal audio EOS", t);
                return;
            }
        }
    }

    private void signalEndOfStreamLocked() {
        if (encoder == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + 800L;
        while (System.currentTimeMillis() < deadline) {
            try {
                int inIndex = encoder.dequeueInputBuffer(20_000);
                if (inIndex >= 0) {
                    encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "signal EOS", t);
                return;
            }
        }
        Log.w(TAG, "signal EOS timeout, force drain");
    }

    private void maybeStartMuxerLocked() {
        if (muxerStarted || muxer == null) {
            return;
        }
        boolean videoReady = videoTrackAdded;
        boolean audioReady = !wantAudio || audioTrackAdded;
        if (!videoReady || !audioReady) {
            return;
        }
        try {
            muxer.start();
            muxerStarted = true;
            Log.i(TAG, "muxer started videoTrack=" + trackIndex + " audioTrack=" + audioTrackIndex);
            flushPendingVideoLocked();
        } catch (Throwable t) {
            Log.e(TAG, "muxer start", t);
        }
    }

    /** 停录时若音频轨始终未就绪，降级纯视频以免整段丢失 */
    private void forceStartMuxerVideoOnlyIfNeededLocked() {
        if (muxerStarted || !videoTrackAdded || muxer == null) {
            return;
        }
        if (wantAudio && !audioTrackAdded) {
            Log.w(TAG, "audio track missing, fallback video-only muxer");
            wantAudio = false;
        }
        maybeStartMuxerLocked();
    }

    private void flushPendingVideoLocked() {
        if (!muxerStarted || trackIndex < 0 || pendingVideo.isEmpty()) {
            return;
        }
        for (PendingSample s : pendingVideo) {
            try {
                ByteBuffer buf = ByteBuffer.wrap(s.data);
                muxer.writeSampleData(trackIndex, buf, s.info);
                encodedFrameCount++;
            } catch (Throwable t) {
                Log.w(TAG, "flush pending video", t);
            }
        }
        pendingVideo.clear();
    }

    private void writeOrQueueVideoLocked(ByteBuffer encoded, MediaCodec.BufferInfo info) {
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size <= 0) {
            return;
        }
        if (muxerStarted && trackIndex >= 0) {
            encoded.position(info.offset);
            encoded.limit(info.offset + info.size);
            muxer.writeSampleData(trackIndex, encoded, info);
            encodedFrameCount++;
            return;
        }
        if (pendingVideo.size() < 90) {
            pendingVideo.add(new PendingSample(encoded, info));
        }
    }

    private void drainAudioEncoderLocked(boolean endOfStream, MediaCodec.BufferInfo info) {
        if (audioEncoder == null || muxer == null) {
            return;
        }
        long deadline = endOfStream ? System.currentTimeMillis() + 800L : 0L;
        while (true) {
            if (endOfStream && System.currentTimeMillis() > deadline) {
                break;
            }
            int outIndex;
            try {
                outIndex = audioEncoder.dequeueOutputBuffer(info, endOfStream ? 10_000L : 0L);
            } catch (Throwable t) {
                break;
            }
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break;
                }
                continue;
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!audioTrackAdded) {
                    try {
                        audioTrackIndex = muxer.addTrack(audioEncoder.getOutputFormat());
                        audioTrackAdded = true;
                        maybeStartMuxerLocked();
                    } catch (Throwable t) {
                        Log.e(TAG, "add audio track", t);
                    }
                }
                continue;
            }
            if (outIndex >= 0) {
                try {
                    ByteBuffer encoded = audioEncoder.getOutputBuffer(outIndex);
                    if (encoded != null && info.size > 0 && muxerStarted
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && audioTrackIndex >= 0) {
                        encoded.position(info.offset);
                        encoded.limit(info.offset + info.size);
                        muxer.writeSampleData(audioTrackIndex, encoded, info);
                    }
                    audioEncoder.releaseOutputBuffer(outIndex, false);
                } catch (Throwable t) {
                    try {
                        audioEncoder.releaseOutputBuffer(outIndex, false);
                    } catch (Throwable ignored) {
                    }
                    break;
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } else {
                break;
            }
        }
    }

    private void releaseQuietly() {
        audioRunning.set(false);
        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        } catch (Exception ignored) {
        }
        encoder = null;
        try {
            if (audioEncoder != null) {
                audioEncoder.stop();
                audioEncoder.release();
            }
        } catch (Exception ignored) {
        }
        audioEncoder = null;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception ignored) {
            }
            try {
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }
        try {
            if (muxer != null) {
                if (muxerStarted) {
                    muxer.stop();
                }
                muxer.release();
            }
        } catch (Exception ignored) {
        }
        muxer = null;
        if (tempFile != null && tempFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
        tempFile = null;
        muxerStarted = false;
        trackIndex = -1;
        audioTrackIndex = -1;
        videoTrackAdded = false;
        audioTrackAdded = false;
        encodedFrameCount = 0;
        nv12Scratch = null;
        pendingVideo.clear();
    }

    private void drainVideoEncoderLocked(boolean endOfStream, long deadlineMs) {
        if (encoder == null || muxer == null) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            if (endOfStream && deadlineMs > 0 && System.currentTimeMillis() > deadlineMs) {
                Log.w(TAG, "drainEncoder EOS budget exceeded frames=" + encodedFrameCount);
                break;
            }
            long timeoutUs = endOfStream ? DRAIN_EOS_TIMEOUT_US : DRAIN_IDLE_TIMEOUT_US;
            int outIndex;
            try {
                outIndex = encoder.dequeueOutputBuffer(info, timeoutUs);
            } catch (Throwable t) {
                Log.w(TAG, "dequeueOutputBuffer", t);
                break;
            }
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break;
                }
                continue;
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (videoTrackAdded) {
                    Log.w(TAG, "format changed twice, ignore");
                    continue;
                }
                try {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    trackIndex = muxer.addTrack(newFormat);
                    videoTrackAdded = true;
                    maybeStartMuxerLocked();
                } catch (Throwable t) {
                    Log.e(TAG, "muxer add video", t);
                    break;
                }
                continue;
            }
            if (outIndex >= 0) {
                try {
                    ByteBuffer encoded = encoder.getOutputBuffer(outIndex);
                    if (encoded != null && info.size > 0) {
                        writeOrQueueVideoLocked(encoded, info);
                    }
                    encoder.releaseOutputBuffer(outIndex, false);
                } catch (Throwable t) {
                    Log.w(TAG, "writeSample", t);
                    try {
                        encoder.releaseOutputBuffer(outIndex, false);
                    } catch (Throwable ignored) {
                    }
                    break;
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } else {
                break;
            }
        }
    }

    private void ensureStopThread() {
        if (stopThread != null && stopHandler != null) {
            return;
        }
        synchronized (this) {
            if (stopThread != null && stopHandler != null) {
                return;
            }
            stopThread = new HandlerThread("fu-video-stop");
            stopThread.start();
            stopHandler = new Handler(stopThread.getLooper());
        }
    }

    private void notifySuccess(Callback callback, String path) {
        if (callback == null) {
            return;
        }
        mainHandler.post(() -> {
            try {
                callback.onSuccess(path);
            } catch (Throwable t) {
                Log.w(TAG, "onSuccess", t);
            }
        });
    }

    private void notifyError(Callback callback, String message) {
        if (callback == null) {
            return;
        }
        final String msg = message != null ? message : "unknown";
        mainHandler.post(() -> {
            try {
                callback.onError(msg);
            } catch (Throwable t) {
                Log.w(TAG, "onError", t);
            }
        });
    }

    private static void nv21ToNv12(byte[] nv21, byte[] nv12, int width, int height) {
        int ySize = width * height;
        System.arraycopy(nv21, 0, nv12, 0, ySize);
        int uvSize = ySize / 2;
        for (int i = 0; i < uvSize; i += 2) {
            nv12[ySize + i] = nv21[ySize + i + 1]; // U
            nv12[ySize + i + 1] = nv21[ySize + i]; // V
        }
    }

    /**
     * 对齐 FULiveDemoDroid {@code FileUtils.addVideoToAlbum}：
     * FaceUnity 目录存在则进 DCIM/FaceUnity，否则进 videoFilePath（vivo→/sdcard/相机/）。
     * 老系统 / vivo：文件拷贝 + MEDIA_SCANNER；Android 10+ 非 vivo 可走 MediaStore。
     */
    private static String writeVideoToGallery(Context context, File videoFile) {
        if (context == null || videoFile == null || !videoFile.exists()) {
            return null;
        }
        if (videoFile.length() < 1024) {
            Log.e(TAG, "writeVideoToGallery file too small len=" + videoFile.length());
            return null;
        }
        // 已落在 Demo 相册目录：只扫库，不再二次拷贝
        String abs = videoFile.getAbsolutePath();
        if (isDemoAlbumPath(abs)) {
            scanAlbumFile(context, videoFile);
            Log.i(TAG, "gallery already in place path=" + abs + " len=" + videoFile.length());
            return abs;
        }
        // vivo / 华为 / 老系统：严格走 Demo 文件路径；其它 Android 10+ 走 MediaStore
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || DeviceQuirk.isVivo()
                || DeviceQuirk.isHuaweiFamily()) {
            return addVideoToAlbumDemoStyle(context, videoFile);
        }
        String mediaStore = writeVideoToGalleryMediaStore(context, videoFile);
        if (mediaStore != null) {
            return mediaStore;
        }
        return addVideoToAlbumDemoStyle(context, videoFile);
    }

    private static File resolveMuxerOutputFile(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return resolveCacheMuxerFile(context);
        }
        try {
            File dir = new File(demoExportVideoDir());
            if (!dir.exists() && !dir.mkdirs()) {
                return resolveCacheMuxerFile(context);
            }
            File f = new File(dir, "FU_" + System.currentTimeMillis() + ".mp4");
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            return f;
        } catch (Throwable t) {
            Log.w(TAG, "resolveMuxerOutputFile", t);
            return resolveCacheMuxerFile(context);
        }
    }

    /** 应用私有缓存：Android 10+ 录制 muxer 输出（无需存储权限） */
    private static File resolveCacheMuxerFile(Context context) {
        if (context == null) {
            return null;
        }
        try {
            File dir = new File(context.getCacheDir(), "fu_record");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File f = new File(dir, "FU_" + System.currentTimeMillis() + ".mp4");
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            return f;
        } catch (Throwable t) {
            Log.w(TAG, "resolveCacheMuxerFile", t);
            return null;
        }
    }

    private static String writeVideoToGalleryMediaStore(Context context, File file) {
        Uri uri = null;
        try {
            String name = "FU_" + System.currentTimeMillis() + ".mp4";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/FaceUnity");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            try (OutputStream out = context.getContentResolver().openOutputStream(uri);
                 FileInputStream in = new FileInputStream(file)) {
                if (out == null) {
                    throw new IllegalStateException("openOutputStream null");
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            return uri.toString();
        } catch (Exception e) {
            Log.e(TAG, "writeVideoToGalleryMediaStore", e);
            if (uri != null) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    /** 字面对齐 FULiveDemoDroid FileUtils.addVideoToAlbum */
    private static String addVideoToAlbumDemoStyle(Context context, File videoFile) {
        String exportVideoDir = demoExportVideoDir();
        String videoFilePath = demoVideoFilePath();
        File fileDir = new File(exportVideoDir);
        if (!fileDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            fileDir.mkdirs();
        }
        File dcimFile;
        if (fileDir.exists()) {
            dcimFile = new File(exportVideoDir, "FU_" + System.currentTimeMillis() + ".mp4");
        } else {
            File fallback = new File(videoFilePath);
            if (!fallback.exists()) {
                //noinspection ResultOfMethodCallIgnored
                fallback.mkdirs();
            }
            dcimFile = new File(fallback, "FU_" + System.currentTimeMillis() + ".mp4");
        }
        if (dcimFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dcimFile.delete();
        }
        BufferedInputStream bis = null;
        BufferedOutputStream bos = null;
        try {
            bis = new BufferedInputStream(new FileInputStream(videoFile));
            bos = new BufferedOutputStream(new FileOutputStream(dcimFile));
            byte[] bytes = new byte[1024 * 10];
            int length;
            while ((length = bis.read(bytes)) != -1) {
                bos.write(bytes, 0, length);
            }
            bos.flush();
        } catch (Exception e) {
            Log.e(TAG, "addVideoToAlbumDemoStyle copy", e);
            return null;
        } finally {
            if (bis != null) {
                try {
                    bis.close();
                } catch (Exception ignored) {
                }
            }
            if (bos != null) {
                try {
                    bos.close();
                } catch (Exception ignored) {
                }
            }
        }
        scanAlbumFile(context, dcimFile);
        Log.i(TAG, "demo-style album path=" + dcimFile.getAbsolutePath()
                + " len=" + dcimFile.length());
        return dcimFile.getAbsolutePath();
    }

    private static void scanAlbumFile(Context context, File file) {
        if (context == null || file == null) {
            return;
        }
        try {
            context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)));
        } catch (Throwable t) {
            Log.w(TAG, "MEDIA_SCANNER_SCAN_FILE", t);
        }
        try {
            MediaScannerConnection.scanFile(
                    context,
                    new String[]{file.getAbsolutePath()},
                    new String[]{"video/mp4"},
                    null);
        } catch (Throwable t) {
            Log.w(TAG, "MediaScannerConnection", t);
        }
    }

    private static boolean isDemoAlbumPath(String abs) {
        if (abs == null) {
            return false;
        }
        String n = abs.replace('\\', '/');
        return n.contains("/DCIM/FaceUnity/") || n.contains("/相机/");
    }

    private static String demoExportVideoDir() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getPath()
                + File.separator + "FaceUnity" + File.separator;
    }

    /** 对齐 Demo static videoFilePath：vivo→/sdcard/相机/，其它→DCIM/Camera/ */
    private static String demoVideoFilePath() {
        if (DeviceQuirk.isVivo()) {
            return Environment.getExternalStorageDirectory().getPath()
                    + File.separator + "相机" + File.separator;
        }
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getPath()
                + File.separator + "Camera" + File.separator;
    }
}
