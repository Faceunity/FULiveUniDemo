package com.faceunity.nama;

import android.content.ContentValues;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将美颜后的 NV21 帧编码为 MP4（含 AAC 音轨），并写入系统相册。
 * <p>
 * 停录必须异步：旧实现在 UI 线程 drainEncoder 死等 EOS + 同步拷相册，偶发 ANR / 未保存。
 */
final class BeautyVideoRecorder {

    private static final String TAG = "FU-VideoRecorder";
    private static final long STOP_DRAIN_BUDGET_MS = 2500L;
    private static final long OFFER_INPUT_TIMEOUT_US = 0L;
    private static final long DRAIN_IDLE_TIMEOUT_US = 0L;
    private static final long DRAIN_EOS_TIMEOUT_US = 10_000L;

    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_CHANNEL_COUNT = 1;
    private static final int AUDIO_BIT_RATE = 128_000;

    interface Callback {
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

    boolean isRecording() {
        return recording.get();
    }

    void start(int frameW, int frameH) throws Exception {
        start(frameW, frameH, 0, 0L);
    }

    void start(int frameW, int frameH, int orientationDegrees) throws Exception {
        start(frameW, frameH, orientationDegrees, 0L);
    }

    void start(int frameW, int frameH, int orientationDegrees, long avSyncOriginNs) throws Exception {
        synchronized (lock) {
            if (recording.get() || stopping.get()) {
                throw new IllegalStateException("正在录制或收尾中");
            }
            int w = Math.max(16, frameW & ~1);
            int h = Math.max(16, frameH & ~1);
            width = w;
            height = h;
            orientationHint = ((orientationDegrees % 360) + 360) % 360;
            tempFile = File.createTempFile("fu_rec_", ".mp4", null);
            if (tempFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(2_000_000, width * height * 4));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(tempFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
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
            try {
                setupAudioLocked();
                wantAudio = true;
            } catch (Throwable t) {
                Log.w(TAG, "audio setup failed, video-only", t);
                releaseAudioLocked();
                wantAudio = false;
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

    void offerNv21(byte[] nv21, int frameW, int frameH, long ptsUs) {
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
                nv21ToNv12(nv21, nv12Scratch, width, height);
                int inIndex = encoder.dequeueInputBuffer(OFFER_INPUT_TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer inBuf = encoder.getInputBuffer(inIndex);
                    if (inBuf != null) {
                        inBuf.clear();
                        int capacity = inBuf.capacity();
                        int len = Math.min(nv12Scratch.length, capacity);
                        inBuf.put(nv12Scratch, 0, len);
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
    void stop(Context context, Callback callback) {
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

    void cancel() {
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
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
            if (galleryPath == null || galleryPath.isEmpty()) {
                notifyError(callback, "保存视频失败");
            } else {
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

    private static String writeVideoToGallery(Context context, File file) {
        if (context == null || file == null || !file.exists()) {
            return null;
        }
        Uri uri = null;
        try {
            String name = "FU_" + System.currentTimeMillis() + ".mp4";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FULive");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
            }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            }
            return uri.toString();
        } catch (Exception e) {
            Log.e(TAG, "writeVideoToGallery", e);
            if (uri != null) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }
}
