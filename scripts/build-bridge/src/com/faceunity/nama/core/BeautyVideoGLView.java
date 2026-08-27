package com.faceunity.nama.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.faceunity.nama.NamaModule;
import com.faceunity.nama.utils.MediaPathUtil;
import com.faceunity.wrapper.faceunity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 本地视频循环播放 + Nama 实时美颜（GLSurfaceView overlay）。
 */
public class BeautyVideoGLView extends GLSurfaceView implements GLSurfaceView.Renderer {

    private static final String TAG = "FaceUnity-Video";
    /** 预览最长边 1080，仅超大源硬封顶（不主动 720 降画质） */
    private static final int PREVIEW_MAX_SIDE_HARD_CAP = 1080;
    /** GPU 硬解预览最长边 */
    private static final int PREVIEW_MAX_SIDE_GPU = 1080;
    /** CPU 回退：4K/高帧率源降 720 减轻卡顿 */
    private static final int PREVIEW_MAX_SIDE_CPU_4K = 720;
    /** 落后超过约一帧再丢帧追时钟（60fps≈16ms） */
    private static final long CATCHUP_BEHIND_US = 33_000L;
    private static final float[] TEX_COORDS = {
            0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f,
    };

    private final Object frameLock = new Object();
    private final AtomicBoolean playing = new AtomicBoolean(false);
    /** Activity 进后台已成对 onPause；回前台须成对 onResume（不成对会把回桌面也搞挂） */
    private final AtomicBoolean glHostPaused = new AtomicBoolean(false);
    private static volatile boolean beautyEnabled = true;

    private String videoPath;
    private HandlerThread decodeThread;
    private Handler decodeHandler;
    private HandlerThread beautyThread;
    private Handler beautyHandler;
    private MediaExtractor extractor;
    private MediaCodec decoder;
    /** 对齐 Demo：视频 Reader 解码画面，独立播放器播音频 */
    private MediaPlayer audioPlayer;
    private int trackIndex = -1;
    private int rotation = 0;
    private long durationUs = 0;
    private float frameIntervalMs = 33f;
    private int sourceFps = 30;

    private byte[] rgbaBuffer;
    /** 解码原图（未美颜），调参/重绘必须从此喂入，禁止用 rgbaBuffer 叠算 */
    private byte[] sourceRgba;
    private int sourceWidth;
    private int sourceHeight;
    private byte[] rgbaRenderBuffer;
    private boolean namaPreparedOnGl = false;
    private boolean frameReady;
    private int frameWidth;
    private int frameHeight;
    private int frameId = 300000;
    private int layoutW;
    private int layoutH;
    private int eglW;
    private int eglH;

    private int program;
    private int aPosition;
    private int aTexCoord;
    private int uTexture;
    private int uMirrorY;
    private int outputTex;
    private int texUploadW;
    private int texUploadH;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texBuffer;
    private boolean glReady;
    private volatile Runnable onFirstFrameListener;
    private final AtomicBoolean firstFrameNotified = new AtomicBoolean(false);
    /** 导出期间跳过预览 fuRender，避免与 processVideo 抢 Nama */
    private volatile boolean pauseNamaDraw = false;
    /** 暂停切滤镜/调参：下一帧强制多遍 Nama（filter_name 下一帧生效） */
    private volatile boolean pendingParamRefresh = false;
    /** 导出中：保留上一帧画面，禁止清屏变黑 */
    private volatile boolean exportingFreeze = false;
    private byte[] lastDisplayRgba;
    private int lastDisplayW;
    private int lastDisplayH;
    /** 最近一帧原始解码图（未美颜），对比按钮按下时直接贴它 */
    private byte[] lastSourceRgba;
    private int lastSourceW;
    private int lastSourceH;
    /** 与 lastDisplayRgba 同帧的原始图，对比视图用它避免与美颜视图不同帧抖动 */
    private byte[] compareSourceRgba;
    private int compareSourceW;
    private int compareSourceH;
    private volatile Runnable onPlaybackEndedListener;
    /** 美颜异步：避免 onDrawFrame 每帧阻塞等相机 GL（否则上限约 12fps） */
    private final AtomicBoolean beautyBusy = new AtomicBoolean(false);
    private final AtomicBoolean beautyPending = new AtomicBoolean(false);
    private byte[] pendingBeautyRgba;
    private int pendingBeautyW;
    private int pendingBeautyH;
    private int pendingBeautyPasses = 1;

    /** GPU 硬解 + fuRenderTexture 预览（与相机 GL 共享 EGL） */
    private boolean gpuPreviewMode;
    private int gpuOutW;
    private int gpuOutH;
    private HandlerThread gpuStThread;
    private Handler gpuStHandler;

    public BeautyVideoGLView(Context context) {
        super(context);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);
        setZOrderMediaOverlay(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setPreserveEGLContextOnPause(true);
        texBuffer = toFloatBuffer(TEX_COORDS);
        vertexBuffer = toFloatBuffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
    }

    public void bindLayoutSize(int width, int height) {
        layoutW = width;
        layoutH = height;
        rebuildVertices();
    }

    public void setOnFirstFrameListener(Runnable listener) {
        onFirstFrameListener = listener;
        firstFrameNotified.set(false);
    }

    public void setOnPlaybackEndedListener(Runnable listener) {
        onPlaybackEndedListener = listener;
    }

    /** 只解一帧用于静帧预览（默认暂停，对齐 Demo） */
    public void prepareFirstFrame() {
        pauseNamaDraw = false;
        playing.set(false);
        if (decoder == null && (videoPath == null || videoPath.isEmpty())) {
            return;
        }
        ensureDecodeThread();
        // 非华为保持原逻辑；仅华为 Hisilicon 需要避 flush + retriever 兜底
        if (!DeviceQuirk.isHuaweiFamily()) {
            decodeHandler.post(this::decodeOneFrame);
            return;
        }
        decodeHandler.post(() -> {
            try {
                // P20 Pro：刚 start 就 flush/seek 易导致解码器永久无输出
                boolean ok = decodeOneFrameInternal(false);
                if (!ok) {
                    Log.w(TAG, "prepareFirstFrame Huawei codec miss, try MediaMetadataRetriever");
                    ok = ingestRetrieverFirstFrame();
                }
                if (!ok) {
                    ok = decodeOneFrameInternal(true);
                }
                if (!ok) {
                    Log.e(TAG, "prepareFirstFrame Huawei all paths failed path=" + videoPath);
                }
            } catch (Throwable t) {
                Log.e(TAG, "prepareFirstFrame Huawei", t);
            }
        });
    }

  /** 对比仅切显示层；美颜在解码线程持续计算，松手无跳变 */
    public void setBeautyEnabled(boolean enabled) {
        beautyEnabled = enabled;
        requestRender();
    }

    /** 新视频挂载前清空上一段缓存，避免首帧黑屏或残留旧片 */
    public void resetFrameCache() {
        synchronized (frameLock) {
            lastDisplayRgba = null;
            lastSourceRgba = null;
            compareSourceRgba = null;
            rgbaBuffer = null;
            sourceRgba = null;
            pendingBeautyRgba = null;
            frameReady = false;
            frameWidth = 0;
            frameHeight = 0;
            lastDisplayW = 0;
            lastDisplayH = 0;
            lastSourceW = 0;
            lastSourceH = 0;
        }
        firstFrameNotified.set(false);
        texUploadW = 0;
        texUploadH = 0;
        beautyPending.set(false);
        requestRender();
    }

    public static void setBeautyEnabledGlobal(boolean enabled) {
        beautyEnabled = enabled;
    }

    public boolean loadVideo(String path) throws Exception {
        resetFrameCache();
        releaseDecoder();
        videoPath = MediaPathUtil.toLocalFilePath(getContext(), path, ".mp4");
        extractor = new MediaExtractor();
        extractor.setDataSource(videoPath);
        trackIndex = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                trackIndex = i;
                format = f;
                break;
            }
        }
        if (trackIndex < 0 || format == null) {
            throw new IllegalStateException("无视频轨");
        }
        extractor.selectTrack(trackIndex);
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            durationUs = format.getLong(MediaFormat.KEY_DURATION);
        }
        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            rotation = format.getInteger(MediaFormat.KEY_ROTATION);
        }
        int fps = 30;
        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try {
                fps = Math.max(1, format.getInteger(MediaFormat.KEY_FRAME_RATE));
            } catch (Throwable t) {
                try {
                    fps = Math.max(1, Math.round(format.getFloat(MediaFormat.KEY_FRAME_RATE)));
                } catch (Throwable ignored) {
                }
            }
        }
        sourceFps = fps;
        if (fps > 120 || fps < 5) {
            fps = 30;
            sourceFps = 30;
        }
        // 以视频元数据帧率为参考；真实节奏以 PTS 为准，坏 PTS 走固定间隔
        frameIntervalMs = 1000f / Math.max(1, Math.min(fps, 60));

        String mime = format.getString(MediaFormat.KEY_MIME);
        decoder = MediaCodec.createDecoderByType(mime);
        gpuPreviewMode = false;
        if (!trySetupGpuDecoder(format)) {
            try {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            } catch (Exception ignored) {
            }
            decoder.configure(format, null, null, 0);
        }
        decoder.start();

        prepareAudioPlayer(videoPath);

        Log.i(TAG, "loadVideo " + videoPath + " rot=" + rotation
                + " fps=" + fps + " durationUs=" + durationUs
                + " gpu=" + gpuPreviewMode
                + " mime=" + mime
                + " audio=" + (audioPlayer != null));
        return true;
    }

    private boolean trySetupGpuDecoder(MediaFormat format) {
        if (DeviceQuirk.prefersCpuVideoPreview()) {
            Log.i(TAG, "trySetupGpuDecoder: Huawei CPU decode path");
            return false;
        }
        return trySetupGpuDecoderImpl(format);
    }

    private boolean trySetupGpuDecoderImpl(MediaFormat format) {
        BeautyCameraGLView cam = NamaModule.peekCameraOverlay();
        if (cam == null) {
            Log.w(TAG, "trySetupGpuDecoder: no camera GL host");
            return false;
        }
        int srcW = format.getInteger(MediaFormat.KEY_WIDTH);
        int srcH = format.getInteger(MediaFormat.KEY_HEIGHT);
        int applyRot = rotationForDecodedFrame(srcW, srcH, rotation);
        int dw = srcW;
        int dh = srcH;
        if (applyRot == 90 || applyRot == 270) {
            dw = srcH;
            dh = srcW;
        }
        int[] scaled = VideoGpuPreviewHelper.scaleEven(dw, dh, PREVIEW_MAX_SIDE_GPU);
        ensureGpuStThread();
        android.view.Surface surface = cam.ensureVideoGpuDecoderSurface(
                srcW, srcH, scaled[0], scaled[1], gpuStHandler);
        if (surface == null) {
            return false;
        }
        try {
            decoder.configure(format, surface, null, 0);
            gpuPreviewMode = true;
            gpuOutW = scaled[0];
            gpuOutH = scaled[1];
            return true;
        } catch (Exception e) {
            Log.w(TAG, "trySetupGpuDecoder configure failed", e);
            cam.releaseVideoGpuPreview();
            gpuPreviewMode = false;
            return false;
        }
    }

    private void ensureGpuStThread() {
        if (gpuStThread != null && gpuStHandler != null) {
            return;
        }
        gpuStThread = new HandlerThread("fu-video-gpu-st");
        gpuStThread.start();
        gpuStHandler = new Handler(gpuStThread.getLooper());
    }

    private void prepareAudioPlayer(String path) {
        releaseAudioPlayer();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(path);
            mp.setLooping(false);
            mp.prepare();
            mp.seekTo(0);
            audioPlayer = mp;
        } catch (Throwable t) {
            Log.w(TAG, "prepareAudioPlayer", t);
            releaseAudioPlayer();
        }
    }

    private void releaseAudioPlayer() {
        MediaPlayer mp = audioPlayer;
        audioPlayer = null;
        if (mp == null) {
            return;
        }
        try {
            mp.stop();
        } catch (Throwable ignored) {
        }
        try {
            mp.release();
        } catch (Throwable ignored) {
        }
    }

    private void startAudioPlayback() {
        MediaPlayer mp = audioPlayer;
        if (mp == null) {
            return;
        }
        try {
            mp.seekTo(0);
            mp.start();
        } catch (Throwable t) {
            Log.w(TAG, "startAudioPlayback", t);
        }
    }

    private void pauseAudioPlayback() {
        MediaPlayer mp = audioPlayer;
        if (mp == null) {
            return;
        }
        try {
            if (mp.isPlaying()) {
                mp.pause();
            }
        } catch (Throwable ignored) {
        }
    }

    private void resetAudioToStart() {
        MediaPlayer mp = audioPlayer;
        if (mp == null) {
            return;
        }
        try {
            if (mp.isPlaying()) {
                mp.pause();
            }
            mp.seekTo(0);
        } catch (Throwable ignored) {
        }
    }

    /** 暂停态重绘：对缓存的解码帧重新走 Nama，用于切滤镜/调参即时生效（对齐 iOS redrawBeautyFrame） */
    public void redrawBeautyFrame() {
        if (!glReady) {
            return;
        }
        pauseNamaDraw = false;
        pendingParamRefresh = true;
        if (gpuPreviewMode) {
            BeautyCameraGLView cam = NamaModule.peekCameraOverlay();
            if (cam != null) {
                int handle = FuBeautyHandle.mediaHandle;
                if (handle <= 0) {
                    handle = FuBeautyHandle.cameraHandle;
                }
                int passes = 2;
                BeautyCameraGLView.VideoGpuRgbaFrame frame =
                        cam.redrawVideoGpuFrameRgba(handle, true, passes);
                if (frame != null && (frame.beauty != null || frame.raw != null)) {
                    synchronized (frameLock) {
                        if (frame.raw != null) {
                            lastSourceRgba = frame.raw;
                            lastSourceW = gpuOutW;
                            lastSourceH = gpuOutH;
                        }
                        if (frame.beauty != null) {
                            lastDisplayRgba = frame.beauty;
                            lastDisplayW = gpuOutW;
                            lastDisplayH = gpuOutH;
                        }
                        frameWidth = gpuOutW;
                        frameHeight = gpuOutH;
                        frameReady = true;
                    }
                    requestRender();
                    return;
                }
            }
        }
        synchronized (frameLock) {
            if (sourceRgba != null && sourceWidth > 0 && sourceHeight > 0) {
                pendingBeautyRgba = sourceRgba;
                pendingBeautyW = sourceWidth;
                pendingBeautyH = sourceHeight;
                // 原图单遍即可；多遍会叠加重美颜/滤镜
                pendingBeautyPasses = 1;
                beautyPending.set(true);
            }
        }
        scheduleBeautyJob();
        requestRender();
    }

    public void play() {
        pauseNamaDraw = false;
        // 仅当进后台成对 pause 过才 resume，避免多次裸 onResume 搞挂回桌面场景
        if (glHostPaused.compareAndSet(true, false)) {
            try {
                onResume();
            } catch (Throwable t) {
                Log.w(TAG, "play onResume", t);
            }
        }
        if (decoder == null && videoPath != null) {
            try {
                loadVideo(videoPath);
            } catch (Throwable t) {
                Log.e(TAG, "play reload", t);
                return;
            }
        }
        if (decoder == null) {
            return;
        }
        if (!playing.compareAndSet(false, true)) {
            return;
        }
        ensureDecodeThread();
        decodeHandler.post(() -> {
            try {
                seekToStartQuietly();
                startAudioPlayback();
                decodeLoop();
            } catch (Throwable t) {
                Log.e(TAG, "play decodeLoop", t);
                playing.set(false);
            }
        });
    }

    public void pause() {
        playing.set(false);
        pauseAudioPlayback();
        requestRender();
    }

    /** 锁屏/回桌面：停播 + GL onPause（对齐 Demo Activity.onPause） */
    public void onHostPause() {
        pause();
        if (glHostPaused.compareAndSet(false, true)) {
            try {
                onPause();
            } catch (Throwable t) {
                Log.w(TAG, "onHostPause", t);
                glHostPaused.set(false);
            }
        }
    }

    /**
     * 回前台重置为初始态：onResume → 重建解码器 → 开头静帧。
     * 锁屏后 MediaCodec 常已废但对象非 null，必须强制 reload。
     */
    public boolean onHostResumeToIdle() {
        if (glHostPaused.compareAndSet(true, false)) {
            try {
                onResume();
            } catch (Throwable t) {
                Log.w(TAG, "onHostResumeToIdle onResume", t);
            }
        }
        pauseNamaDraw = false;
        exportingFreeze = false;
        glReady = true;
        playing.set(false);
        pauseAudioPlayback();
        resetAudioToStart();
        if (videoPath == null || videoPath.isEmpty()) {
            return false;
        }
        try {
            // 锁屏/回桌面后解码器经常僵死：一律按路径重建
            loadVideo(videoPath);
        } catch (Throwable t) {
            Log.e(TAG, "onHostResumeToIdle reload", t);
            return false;
        }
        if (decoder == null || extractor == null) {
            return false;
        }
        ensureDecodeThread();
        if (decodeHandler != null) {
            decodeHandler.post(() -> {
                try {
                    seekToStartQuietly();
                    decodeOneFrame();
                } catch (Throwable t) {
                    Log.e(TAG, "onHostResumeToIdle decode", t);
                }
            });
        }
        requestRender();
        return true;
    }

    /** 导出：停解码+跳过 Nama，但继续贴上一帧，避免整页黑屏 */
    public void beginExportFreeze() {
        playing.set(false);
        pauseAudioPlayback();
        pauseNamaDraw = true;
        exportingFreeze = true;
        requestRender();
    }

    public void endExportFreeze() {
        exportingFreeze = false;
        pauseNamaDraw = false;
        requestRender();
    }

    public boolean isPlaying() {
        return playing.get();
    }

    public void stopAndRelease() {
        playing.set(false);
        pauseNamaDraw = true;
        releaseDecoder();
        if (decodeThread != null) {
            decodeThread.quitSafely();
            decodeThread = null;
            decodeHandler = null;
        }
        if (beautyThread != null) {
            beautyThread.quitSafely();
            beautyThread = null;
            beautyHandler = null;
        }
        beautyBusy.set(false);
        beautyPending.set(false);
    }

    /**
     * 销毁视频显示层：只拆本 View 的纹理/解码器。
     * 视频不拥有 Nama，禁止在此 deviceLost（否则会毁掉相机上下文的 AI/bundle）。
     */
    public void destroyPreviewAsync(Runnable onFinished) {
        destroyPreviewAsync(true, onFinished);
    }

    /** @param keepSession 忽略；视频层永不 deviceLost */
    public void destroyPreviewAsync(boolean keepSession, Runnable onFinished) {
        playing.set(false);
        resetFrameCache();
        releaseDecoder();
        if (decodeThread != null) {
            decodeThread.quitSafely();
            decodeThread = null;
            decodeHandler = null;
        }
        if (beautyThread != null) {
            beautyThread.quitSafely();
            beautyThread = null;
            beautyHandler = null;
        }
        beautyBusy.set(false);
        beautyPending.set(false);
        final Handler main = new Handler(android.os.Looper.getMainLooper());
        final AtomicBoolean done = new AtomicBoolean(false);
        final Runnable finish = () -> {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            if (onFinished != null) {
                main.post(onFinished);
            }
        };
        main.postDelayed(finish, 1200);
        try {
            setVisibility(VISIBLE);
            setRenderMode(RENDERMODE_CONTINUOUSLY);
            queueEvent(() -> {
                try {
                    if (outputTex > 0) {
                        int[] tex = new int[]{outputTex};
                        GLES20.glDeleteTextures(1, tex, 0);
                        outputTex = 0;
                    }
                    // 永不 deviceLost
                } catch (Throwable t) {
                    Log.e(TAG, "destroyPreviewAsync gl", t);
                } finally {
                    glReady = false;
                    main.removeCallbacks(finish);
                    finish.run();
                }
            });
            requestRender();
        } catch (Exception e) {
            Log.e(TAG, "destroyPreviewAsync", e);
            main.removeCallbacks(finish);
            finish.run();
        }
    }

    private void ensureDecodeThread() {
        if (decodeThread != null) {
            return;
        }
        decodeThread = new HandlerThread("fu-video-decode");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());
    }

    private void ensureBeautyThread() {
        if (beautyThread != null) {
            return;
        }
        beautyThread = new HandlerThread("fu-video-beauty");
        beautyThread.start();
        beautyHandler = new Handler(beautyThread.getLooper());
    }

    private void releaseDecoder() {
        resetAudioToStart();
        releaseAudioPlayer();
        if (gpuPreviewMode) {
            BeautyCameraGLView cam = NamaModule.peekCameraOverlay();
            if (cam != null) {
                cam.releaseVideoGpuPreview();
            }
            gpuPreviewMode = false;
            gpuOutW = 0;
            gpuOutH = 0;
        }
        if (gpuStThread != null) {
            try {
                gpuStThread.quitSafely();
            } catch (Throwable ignored) {
            }
            gpuStThread = null;
            gpuStHandler = null;
        }
        try {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
        } catch (Exception ignored) {
        }
        decoder = null;
        try {
            if (extractor != null) {
                extractor.release();
            }
        } catch (Exception ignored) {
        }
        extractor = null;
        trackIndex = -1;
    }

    private void decodeLoop() {
        if (!playing.get() || decoder == null || extractor == null) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputEos = false;
        long startWallNs = -1L;
        long startPtsUs = -1L;
        long lastPtsUs = -1L;
        int consecutiveSkips = 0;
        int pacedFrameIndex = 0;
        final long frameIntervalUs = Math.max(8_000L, (long) (frameIntervalMs * 1000f));

        while (playing.get()) {
            try {
                if (!inputEos) {
                    int inIndex = decoder.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(inIndex);
                        int sampleSize = extractor.readSampleData(input, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "preview decoder FORMAT_CHANGED " + decoder.getOutputFormat()
                            + " gpu=" + gpuPreviewMode);
                    continue;
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    Log.i(TAG, "preview decoder BUFFERS_CHANGED gpu=" + gpuPreviewMode);
                    continue;
                }
                if (outIndex >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (info.size > 0) {
                        if (startPtsUs < 0) {
                            startPtsUs = info.presentationTimeUs;
                            startWallNs = System.nanoTime();
                            lastPtsUs = info.presentationTimeUs;
                            pacedFrameIndex = 0;
                        }
                        long ptsUs = Math.max(0L, info.presentationTimeUs);
                        boolean ptsReliable = ptsUs > lastPtsUs + 500L;
                        long targetUs;
                        if (ptsReliable) {
                            lastPtsUs = ptsUs;
                            targetUs = Math.max(0L, ptsUs - startPtsUs);
                        } else {
                            pacedFrameIndex++;
                            targetUs = pacedFrameIndex * frameIntervalUs;
                        }
                        // 超前：等时钟到点再展示（丢帧会直接把视频「播完/卡死」）
                        while (playing.get() && !eos) {
                            long elapsedUs = (System.nanoTime() - startWallNs) / 1000L;
                            long aheadUs = targetUs - elapsedUs;
                            if (aheadUs <= 2000L) {
                                break;
                            }
                            try {
                                Thread.sleep(Math.min(32L, Math.max(1L, aheadUs / 2000L)));
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        long elapsedUs = (System.nanoTime() - startWallNs) / 1000L;
                        long behindUs = elapsedUs - targetUs;
                        long catchupThreshold = sourceFps >= 50 ? 20_000L : CATCHUP_BEHIND_US;
                        if (ptsReliable && behindUs > catchupThreshold && consecutiveSkips < 96) {
                            decoder.releaseOutputBuffer(outIndex, false);
                            consecutiveSkips++;
                        } else {
                            consecutiveSkips = 0;
                            if (gpuPreviewMode) {
                                ingestGpuDecodedOutput(decoder, outIndex, info);
                            } else {
                                ingestDecodedOutput(decoder, outIndex, info);
                            }
                        }
                    } else {
                        decoder.releaseOutputBuffer(outIndex, false);
                    }
                    if (eos) {
                        playing.set(false);
                        resetAudioToStart();
                        seekToStartQuietly();
                        decodeOneFrame();
                        final Runnable ended = onPlaybackEndedListener;
                        if (ended != null) {
                            post(ended);
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "decodeLoop", e);
                playing.set(false);
                break;
            }
        }
    }

    /** 解一帧并显示（暂停态美颜仍可对当前帧重绘） */
    private void decodeOneFrame() {
        decodeOneFrameInternal(true);
    }

    /**
     * @param seekFirst true 时先 seek+flush；首帧预览应传 false（华为 flush 刚 start 的解码器易无输出）
     */
    private boolean decodeOneFrameInternal(boolean seekFirst) {
        if (decoder == null || extractor == null) {
            return false;
        }
        try {
            if (seekFirst) {
                seekToStartQuietly();
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean got = false;
            int maxAttempts = DeviceQuirk.isHuaweiFamily() ? 180 : 80;
            for (int attempt = 0; attempt < maxAttempts && !got; attempt++) {
                int inIndex = decoder.dequeueInputBuffer(10_000);
                if (inIndex >= 0) {
                    ByteBuffer input = decoder.getInputBuffer(inIndex);
                    int sampleSize = extractor.readSampleData(input, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
                int outIndex = decoder.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "decodeOneFrame FORMAT_CHANGED " + decoder.getOutputFormat()
                            + " seekFirst=" + seekFirst);
                    continue;
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    continue;
                }
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        if (gpuPreviewMode) {
                            ingestGpuDecodedOutput(decoder, outIndex, info);
                        } else {
                            ingestDecodedOutput(decoder, outIndex, info);
                        }
                        synchronized (frameLock) {
                            got = frameReady;
                        }
                        if (!got) {
                            Log.w(TAG, "decodeOneFrame ingest no frameReady attempt=" + attempt
                                    + " size=" + info.size);
                        }
                    } else {
                        decoder.releaseOutputBuffer(outIndex, false);
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
            if (!got) {
                Log.e(TAG, "decodeOneFrame failed after " + maxAttempts
                        + " attempts gpu=" + gpuPreviewMode + " seekFirst=" + seekFirst);
            }
            return got;
        } catch (Exception e) {
            Log.e(TAG, "decodeOneFrame", e);
            return false;
        }
    }

    /** MediaCodec 首帧失败时用系统取帧兜底（解决华为黑屏无播放钮） */
    private boolean ingestRetrieverFirstFrame() {
        if (videoPath == null || videoPath.isEmpty()) {
            return false;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap bmp = null;
        try {
            retriever.setDataSource(videoPath);
            bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bmp == null) {
                bmp = retriever.getFrameAtTime(-1);
            }
            if (bmp == null) {
                Log.w(TAG, "ingestRetrieverFirstFrame null bitmap");
                return false;
            }
            int w = bmp.getWidth() & ~1;
            int h = bmp.getHeight() & ~1;
            if (w < 2 || h < 2) {
                return false;
            }
            int[] pixels = new int[w * h];
            bmp.getPixels(pixels, 0, w, 0, 0, w, h);
            byte[] rgba = new byte[w * h * 4];
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                int o = i * 4;
                rgba[o] = (byte) ((p >> 16) & 0xff);
                rgba[o + 1] = (byte) ((p >> 8) & 0xff);
                rgba[o + 2] = (byte) (p & 0xff);
                rgba[o + 3] = (byte) 0xff;
            }
            forceOpaqueRgba(rgba);
            PreviewFrame pf = maybeDownscalePreviewFrame(rgba, w, h);
            byte[] stored = java.util.Arrays.copyOf(pf.rgba, pf.rgba.length);
            synchronized (frameLock) {
                sourceRgba = stored;
                sourceWidth = pf.width;
                sourceHeight = pf.height;
                lastSourceRgba = stored;
                lastSourceW = pf.width;
                lastSourceH = pf.height;
                lastDisplayRgba = stored;
                lastDisplayW = pf.width;
                lastDisplayH = pf.height;
                frameWidth = pf.width;
                frameHeight = pf.height;
                frameReady = true;
                frameId++;
                pendingBeautyRgba = stored;
                pendingBeautyW = pf.width;
                pendingBeautyH = pf.height;
                pendingBeautyPasses = 2;
                pendingParamRefresh = false;
                beautyPending.set(true);
            }
            Log.i(TAG, "ingestRetrieverFirstFrame ok " + pf.width + "x" + pf.height);
            notifyFirstFrameIfNeeded();
            scheduleBeautyJob();
            requestRender();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "ingestRetrieverFirstFrame", t);
            return false;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }
        }
    }

    private void seekToStartQuietly() {
        try {
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            decoder.flush();
        } catch (Exception ignored) {
        }
    }

    private void ingestDecodedOutput(MediaCodec codec, int outIndex, MediaCodec.BufferInfo info) {
        Image image = null;
        try {
            image = codec.getOutputImage(outIndex);
        } catch (Exception ignored) {
        }
        byte[] rgba = null;
        int w = 0;
        int h = 0;
        ByteBuffer outBuf = null;
        MediaFormat outFormat = null;
        byte[] imageRgba = null;
        if (image != null) {
            w = image.getWidth();
            h = image.getHeight();
            imageRgba = yuvImageToRgba(image);
            if (imageRgba != null && isMostlyBlackRgba(imageRgba, w, h)) {
                Log.w(TAG, "ingest image path black, retry buffer w=" + w + " h=" + h);
                imageRgba = null;
            }
        }
        try {
            outBuf = codec.getOutputBuffer(outIndex);
            outFormat = codec.getOutputFormat();
        } catch (Exception ignored) {
        }
        if (outBuf != null && outFormat != null) {
            if (w <= 0) {
                w = outFormat.containsKey(MediaFormat.KEY_WIDTH)
                        ? outFormat.getInteger(MediaFormat.KEY_WIDTH) : 0;
            }
            if (h <= 0) {
                h = outFormat.containsKey(MediaFormat.KEY_HEIGHT)
                        ? outFormat.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            }
            // 华为 crop 后真实显示尺寸可能小于 buffer width/height
            if (outFormat.containsKey("crop-right") && outFormat.containsKey("crop-left")
                    && outFormat.containsKey("crop-bottom") && outFormat.containsKey("crop-top")) {
                try {
                    int cw = outFormat.getInteger("crop-right") - outFormat.getInteger("crop-left") + 1;
                    int ch = outFormat.getInteger("crop-bottom") - outFormat.getInteger("crop-top") + 1;
                    if (cw > 0 && ch > 0) {
                        w = cw;
                        h = ch;
                    }
                } catch (Throwable ignored) {
                }
            }
            int color = outFormat.containsKey(MediaFormat.KEY_COLOR_FORMAT)
                    ? outFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT) : 0;
            byte[] bufRgba = yuvBufferToRgba(outBuf, info.offset, info.size, w, h, color);
            if (bufRgba == null && DeviceQuirk.isHuaweiFamily() && w > 0 && h > 0) {
                bufRgba = tryHuaweiYuvFallbacks(outBuf, info.offset, info.size, w, h);
            }
            if (DeviceQuirk.isHuaweiFamily()) {
                rgba = pickLessBlueRgba(imageRgba, bufRgba, w, h);
            } else {
                rgba = imageRgba != null ? imageRgba : bufRgba;
            }
        } else {
            rgba = imageRgba;
        }
        if (image != null) {
            image.close();
        }
        codec.releaseOutputBuffer(outIndex, false);
        if (rgba == null || w <= 0 || h <= 0) {
            if (rgba == null) {
                Log.e(TAG, "ingestDecodedOutput no rgba w=" + w + " h=" + h
                        + " gpu=" + gpuPreviewMode);
            }
            return;
        }
        {
            int applyRot = rotationForDecodedFrame(w, h, rotation);
            int dw = w;
            int dh = h;
            if (applyRot == 90 || applyRot == 270) {
                dw = h;
                dh = w;
                rgba = rotateRgba(rgba, w, h, applyRot);
            } else if (applyRot == 180) {
                rgba = rotateRgba(rgba, w, h, 180);
            }
            forceOpaqueRgba(rgba);
            PreviewFrame pf = maybeDownscalePreviewFrame(rgba, dw, dh);
            rgba = pf.rgba;
            dw = pf.width;
            dh = pf.height;
            synchronized (frameLock) {
                byte[] stored = java.util.Arrays.copyOf(rgba, rgba.length);
                sourceRgba = stored;
                sourceWidth = dw;
                sourceHeight = dh;
                lastSourceRgba = stored;
                lastSourceW = dw;
                lastSourceH = dh;
                frameWidth = dw;
                frameHeight = dh;
                frameReady = true;
                frameId++;
                pendingBeautyRgba = sourceRgba;
                pendingBeautyW = dw;
                pendingBeautyH = dh;
                pendingBeautyPasses = pendingParamRefresh ? 2 : 1;
                pendingParamRefresh = false;
                beautyPending.set(true);
            }
            if (frameId <= 2 || (frameId % 60) == 0) {
                Log.i(TAG, "ingestDecodedOutput ok #" + frameId
                        + " " + dw + "x" + dh + " gpu=" + gpuPreviewMode
                        + " beautyPending=" + beautyPending.get());
            }
            notifyFirstFrameIfNeeded();
            scheduleBeautyJob();
            requestRender();
        }
    }

    /** GPU 硬解：Surface → OES → fuRenderTexture（相机 GL） */
    private void ingestGpuDecodedOutput(MediaCodec codec, int outIndex, MediaCodec.BufferInfo info) {
        if (exportingFreeze) {
            codec.releaseOutputBuffer(outIndex, false);
            return;
        }
        codec.releaseOutputBuffer(outIndex, true);
        BeautyCameraGLView cam = NamaModule.peekCameraOverlay();
        if (cam == null) {
            return;
        }
        int handle = FuBeautyHandle.mediaHandle;
        if (handle <= 0) {
            handle = FuBeautyHandle.cameraHandle;
        }
        int passes = pendingParamRefresh ? 2 : 1;
        pendingParamRefresh = false;
        BeautyCameraGLView.VideoGpuRgbaFrame frame =
                cam.processVideoGpuFrameRgba(handle, true, passes);
        if (frame == null) {
            Log.w(TAG, "ingestGpuDecodedOutput null frame w=" + gpuOutW + " h=" + gpuOutH);
        }
        if (frame != null && (frame.beauty != null || frame.raw != null)) {
            synchronized (frameLock) {
                if (frame.raw != null) {
                    lastSourceRgba = frame.raw;
                    lastSourceW = gpuOutW;
                    lastSourceH = gpuOutH;
                    compareSourceRgba = frame.raw;
                    compareSourceW = gpuOutW;
                    compareSourceH = gpuOutH;
                }
                byte[] show = frame.beauty != null ? frame.beauty : frame.raw;
                if (show != null) {
                    lastDisplayRgba = show;
                    lastDisplayW = gpuOutW;
                    lastDisplayH = gpuOutH;
                }
                frameWidth = gpuOutW;
                frameHeight = gpuOutH;
                frameReady = true;
                frameId++;
            }
            notifyFirstFrameIfNeeded();
            requestRender();
        }
    }

    /** 异步美颜：解码线程只投递最新帧，不阻塞；忙则丢中间帧 */
    private void scheduleBeautyJob() {
        if (pauseNamaDraw || exportingFreeze) {
            return;
        }
        if (!beautyBusy.compareAndSet(false, true)) {
            return;
        }
        final byte[] src;
        final int w;
        final int h;
        final int passes;
        synchronized (frameLock) {
            if (!beautyPending.get() || pendingBeautyRgba == null) {
                beautyBusy.set(false);
                return;
            }
            beautyPending.set(false);
            w = pendingBeautyW;
            h = pendingBeautyH;
            passes = Math.max(1, pendingBeautyPasses);
            src = java.util.Arrays.copyOf(pendingBeautyRgba, pendingBeautyRgba.length);
        }
        // 在独立线程跑美颜，勿占解码 Handler（否则解码被美颜串行拖死）
        ensureBeautyThread();
        if (beautyHandler == null) {
            beautyBusy.set(false);
            return;
        }
        beautyHandler.post(() -> {
            try {
                byte[] out = src;
                if (!pauseNamaDraw) {
                    int beautyHandle = FuBeautyHandle.mediaHandle;
                    if (beautyHandle <= 0) {
                        beautyHandle = FuBeautyHandle.cameraHandle;
                    }
                    BeautyCameraGLView cam = NamaModule.peekCameraOverlay();
                    if (cam != null && beautyHandle > 0) {
                        byte[] processed = cam.processVideoRgbaFrame(src, w, h, beautyHandle, passes);
                        if (processed != null) {
                            out = processed;
                        }
                    }
                }
                synchronized (frameLock) {
                    if (lastDisplayRgba == null || lastDisplayRgba.length != out.length) {
                        lastDisplayRgba = new byte[out.length];
                    }
                    System.arraycopy(out, 0, lastDisplayRgba, 0, out.length);
                    lastDisplayW = w;
                    lastDisplayH = h;
                    rgbaBuffer = lastDisplayRgba;
                    frameWidth = w;
                    frameHeight = h;
                    frameReady = true;
                }
                notifyFirstFrameIfNeeded();
            } catch (Throwable t) {
                Log.w(TAG, "scheduleBeautyJob", t);
            } finally {
                beautyBusy.set(false);
                if (beautyPending.get()) {
                    scheduleBeautyJob();
                } else {
                    requestRender();
                }
            }
        });
    }

    private static final class PreviewFrame {
        final byte[] rgba;
        final int width;
        final int height;

        PreviewFrame(byte[] rgba, int width, int height) {
            this.rgba = rgba;
            this.width = width;
            this.height = height;
        }
    }

    /** 预览最长边：GPU 走 1080；CPU 4K/高帧率降 720，其余封顶 1080 */
    private int previewMaxSide(int w, int h) {
        int max = Math.max(w, h);
        if (gpuPreviewMode) {
            if (max > PREVIEW_MAX_SIDE_GPU) {
                return PREVIEW_MAX_SIDE_GPU;
            }
            return max;
        }
        if (max >= 3000 || (max >= 1920 && sourceFps >= 50)) {
            return PREVIEW_MAX_SIDE_CPU_4K;
        }
        if (max > PREVIEW_MAX_SIDE_HARD_CAP) {
            return PREVIEW_MAX_SIDE_HARD_CAP;
        }
        return max;
    }

    private PreviewFrame maybeDownscalePreviewFrame(byte[] rgba, int width, int height) {
        if (rgba == null || width <= 0 || height <= 0) {
            return new PreviewFrame(rgba, width, height);
        }
        int limit = previewMaxSide(width, height);
        int maxSide = Math.max(width, height);
        if (maxSide <= limit) {
            return new PreviewFrame(rgba, width, height);
        }
        float scale = (float) limit / (float) maxSide;
        int nw = Math.max(2, (Math.round(width * scale) & ~1));
        int nh = Math.max(2, (Math.round(height * scale) & ~1));
        byte[] dst = scaleRgbaBilinear(rgba, width, height, nw, nh);
        if (dst == null) {
            return new PreviewFrame(rgba, width, height);
        }
        return new PreviewFrame(dst, nw, nh);
    }

    private static byte[] scaleRgbaBilinear(byte[] src, int sw, int sh, int dw, int dh) {
        if (src == null || sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) {
            return null;
        }
        if (src.length < sw * sh * 4) {
            return null;
        }
        byte[] dst = new byte[dw * dh * 4];
        float xRatio = (float) (sw - 1) / Math.max(1, dw - 1);
        float yRatio = (float) (sh - 1) / Math.max(1, dh - 1);
        for (int y = 0; y < dh; y++) {
            float sy = y * yRatio;
            int y0 = (int) sy;
            int y1 = Math.min(sh - 1, y0 + 1);
            float fy = sy - y0;
            int dstRow = y * dw * 4;
            for (int x = 0; x < dw; x++) {
                float sx = x * xRatio;
                int x0 = (int) sx;
                int x1 = Math.min(sw - 1, x0 + 1);
                float fx = sx - x0;
                int i00 = (y0 * sw + x0) * 4;
                int i10 = (y0 * sw + x1) * 4;
                int i01 = (y1 * sw + x0) * 4;
                int i11 = (y1 * sw + x1) * 4;
                int di = dstRow + x * 4;
                for (int c = 0; c < 4; c++) {
                    float v00 = src[i00 + c] & 0xFF;
                    float v10 = src[i10 + c] & 0xFF;
                    float v01 = src[i01 + c] & 0xFF;
                    float v11 = src[i11 + c] & 0xFF;
                    float top = v00 + (v10 - v00) * fx;
                    float bot = v01 + (v11 - v01) * fx;
                    dst[di + c] = (byte) Math.round(top + (bot - top) * fy);
                }
            }
        }
        return dst;
    }

    private static byte[] scaleRgbaNearest(byte[] src, int sw, int sh, int dw, int dh) {
        if (src == null || sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) {
            return null;
        }
        if (src.length < sw * sh * 4) {
            return null;
        }
        byte[] dst = new byte[dw * dh * 4];
        for (int y = 0; y < dh; y++) {
            int sy = Math.min(sh - 1, (int) ((long) y * sh / dh));
            int srcRow = sy * sw * 4;
            int dstRow = y * dw * 4;
            for (int x = 0; x < dw; x++) {
                int sx = Math.min(sw - 1, (int) ((long) x * sw / dw));
                int si = srcRow + sx * 4;
                int di = dstRow + x * 4;
                dst[di] = src[si];
                dst[di + 1] = src[si + 1];
                dst[di + 2] = src[si + 2];
                dst[di + 3] = src[si + 3];
            }
        }
        return dst;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // 未出帧前透明，避免 ZOrderOnTop 整块黑盖住媒体页
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        program = buildProgram();
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        uTexture = GLES20.glGetUniformLocation(program, "uTexture");
        uMirrorY = GLES20.glGetUniformLocation(program, "uMirrorY");
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        outputTex = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        glReady = true;
        namaPreparedOnGl = false;
        requestRender();
        if (decoder != null && !firstFrameNotified.get()) {
            ensureDecodeThread();
            if (decodeHandler != null) {
                decodeHandler.post(this::decodeOneFrame);
            }
        }
        // 故意不在此 GL 调 Nama / fuOnCameraChange：会抢走相机上下文导致回相机黑屏或无美颜
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        eglW = width;
        eglH = height;
        GLES20.glViewport(0, 0, width, height);
        rebuildVertices();
        requestRender();
        if (decoder != null && !firstFrameNotified.get()) {
            ensureDecodeThread();
            if (decodeHandler != null) {
                decodeHandler.post(this::decodeOneFrame);
            }
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (!glReady || eglW < 32 || eglH < 32) {
            return;
        }
        GLES20.glViewport(0, 0, eglW, eglH);
        if (exportingFreeze) {
            // 导出：只贴缓存帧，不调 Nama、不清透明黑
            if (lastDisplayRgba != null && lastDisplayW > 0 && lastDisplayH > 0) {
                drawRgbaFrame(lastDisplayRgba, lastDisplayW, lastDisplayH, false);
            }
            return;
        }
        if (pauseNamaDraw) {
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }

        if (gpuPreviewMode) {
            byte[] data;
            int width;
            int height;
            synchronized (frameLock) {
                if (!frameReady) {
                    GLES20.glClearColor(0f, 0f, 0f, 1f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    return;
                }
                if (!beautyEnabled && lastSourceRgba != null && lastSourceW > 0 && lastSourceH > 0) {
                    width = lastSourceW;
                    height = lastSourceH;
                    if (rgbaRenderBuffer == null || rgbaRenderBuffer.length != lastSourceRgba.length) {
                        rgbaRenderBuffer = new byte[lastSourceRgba.length];
                    }
                    System.arraycopy(lastSourceRgba, 0, rgbaRenderBuffer, 0, lastSourceRgba.length);
                    data = rgbaRenderBuffer;
                } else if (lastDisplayRgba != null && lastDisplayW > 0 && lastDisplayH > 0) {
                    width = lastDisplayW;
                    height = lastDisplayH;
                    if (rgbaRenderBuffer == null || rgbaRenderBuffer.length != lastDisplayRgba.length) {
                        rgbaRenderBuffer = new byte[lastDisplayRgba.length];
                    }
                    System.arraycopy(lastDisplayRgba, 0, rgbaRenderBuffer, 0, lastDisplayRgba.length);
                    data = rgbaRenderBuffer;
                } else {
                    GLES20.glClearColor(0f, 0f, 0f, 0f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    return;
                }
            }
            drawRgbaFrame(data, width & ~1, height & ~1, false);
            notifyFirstFrameIfNeeded();
            return;
        }

        byte[] data;
        int width;
        int height;
        synchronized (frameLock) {
            if (!frameReady) {
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                return;
            }
            // 对比按下：直接贴原始解码帧（对齐 FULiveUinDemo）
            if (!beautyEnabled && lastSourceRgba != null && lastSourceW > 0 && lastSourceH > 0) {
                width = lastSourceW;
                height = lastSourceH;
                if (rgbaRenderBuffer == null || rgbaRenderBuffer.length != lastSourceRgba.length) {
                    rgbaRenderBuffer = new byte[lastSourceRgba.length];
                }
                System.arraycopy(lastSourceRgba, 0, rgbaRenderBuffer, 0, lastSourceRgba.length);
                data = rgbaRenderBuffer;
            } else if (lastDisplayRgba != null && lastDisplayW > 0 && lastDisplayH > 0) {
                width = lastDisplayW;
                height = lastDisplayH;
                if (rgbaRenderBuffer == null || rgbaRenderBuffer.length != lastDisplayRgba.length) {
                    rgbaRenderBuffer = new byte[lastDisplayRgba.length];
                }
                System.arraycopy(lastDisplayRgba, 0, rgbaRenderBuffer, 0, lastDisplayRgba.length);
                data = rgbaRenderBuffer;
            } else if (beautyEnabled && lastSourceRgba != null && lastSourceW > 0 && lastSourceH > 0) {
                // 美颜未完成前先显示解码帧，避免华为等首屏全黑
                width = lastSourceW;
                height = lastSourceH;
                if (rgbaRenderBuffer == null || rgbaRenderBuffer.length != lastSourceRgba.length) {
                    rgbaRenderBuffer = new byte[lastSourceRgba.length];
                }
                System.arraycopy(lastSourceRgba, 0, rgbaRenderBuffer, 0, lastSourceRgba.length);
                data = rgbaRenderBuffer;
            } else if (frameReady && rgbaBuffer != null) {
                width = frameWidth;
                height = frameHeight;
                if (rgbaRenderBuffer == null || rgbaRenderBuffer.length != rgbaBuffer.length) {
                    rgbaRenderBuffer = new byte[rgbaBuffer.length];
                }
                System.arraycopy(rgbaBuffer, 0, rgbaRenderBuffer, 0, rgbaBuffer.length);
                data = rgbaRenderBuffer;
            } else {
                GLES20.glClearColor(0f, 0f, 0f, 0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                return;
            }
        }

        int w = width & ~1;
        int h = height & ~1;
        if (w != width || h != height) {
            width = w;
            height = h;
        }

        drawRgbaFrame(data, width, height, false);
        notifyFirstFrameIfNeeded();
    }

    private void drawRgbaFrame(byte[] data, int width, int height, boolean flipY) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        ByteBuffer texBuf = ByteBuffer.wrap(data);
        texBuf.position(0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTex);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        if (width == texUploadW && height == texUploadH) {
            GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, 0, 0,
                    width, height,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                    texBuf
            );
        } else {
            GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    width, height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                    texBuf
            );
            texUploadW = width;
            texUploadH = height;
        }

        rebuildVertices(width, height);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTex);
        GLES20.glUniform1i(uTexture, 0);
        // 静图同款 matrix+CPU 翻正后，显示勿再翻 Y（否则美型相对人脸扭曲）
        GLES20.glUniform1f(uMirrorY, flipY ? 1f : 0f);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
    }

    private void drawGpuTexture(int texId, int width, int height) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        rebuildVertices(width, height);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(uTexture, 0);
        GLES20.glUniform1f(uMirrorY, 0f);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void notifyFirstFrameIfNeeded() {
        if (!firstFrameNotified.compareAndSet(false, true)) {
            return;
        }
        Log.i(TAG, "firstFrame ready " + frameWidth + "x" + frameHeight
                + " gpu=" + gpuPreviewMode
                + " egl=" + eglW + "x" + eglH
                + " glReady=" + glReady);
        final Runnable cb = onFirstFrameListener;
        if (cb == null) {
            return;
        }
        post(cb);
    }

    private void rebuildVertices() {
        rebuildVertices(frameWidth, frameHeight);
    }

    private void rebuildVertices(int fw, int fh) {
        int vw = layoutW > 0 ? layoutW : eglW;
        int vh = layoutH > 0 ? layoutH : eglH;
        if (vw <= 0 || vh <= 0 || fw <= 0 || fh <= 0) {
            vertexBuffer = toFloatBuffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
            return;
        }
        float viewAspect = (float) vw / vh;
        float contentAspect = (float) fw / fh;
        float sx = 1f;
        float sy = 1f;
        if (contentAspect > viewAspect) {
            sx = contentAspect / viewAspect;
        } else {
            sy = viewAspect / contentAspect;
        }
        vertexBuffer = toFloatBuffer(new float[]{-sx, -sy, sx, -sy, -sx, sy, sx, sy});
    }

    private static String normalizePath(String path) {
        if (path != null && path.startsWith("file://")) {
            return path.substring(7);
        }
        return path;
    }

    /** YUV_420_888 → RGBA */
    /**
     * ByteBuffer 输出回退：支持常见 YUV420 Flexible / SemiPlanar / Planar。
     */
    public static byte[] yuvBufferToRgba(ByteBuffer buffer, int offset, int size, int width, int height, int colorFormat) {
        if (buffer == null || width <= 0 || height <= 0 || size <= 0) {
            return null;
        }
        byte[] yuv = new byte[size];
        int pos = buffer.position();
        try {
            buffer.position(offset);
            int toRead = Math.min(size, buffer.remaining());
            buffer.get(yuv, 0, toRead);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                buffer.position(pos);
            } catch (Exception ignored) {
            }
        }
        // color-format=21 = YUV420SemiPlanar / NV12（YYYY+UVUV）
        boolean semiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                || colorFormat == 21
                || colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                || colorFormat == 0;
        if (semiPlanar) {
            byte[] nv12 = nv12ToRgba(yuv, width, height);
            if (!DeviceQuirk.isHuaweiFamily()) {
                return nv12;
            }
            // 华为 Hisilicon：声明 21 实为 NV12，旧逻辑误用 NV21 → 导出人脸发蓝
            byte[] nv21 = nv21ToRgba(yuv, width, height);
            byte[] best = pickLessBlueRgba(nv12, nv21, width, height);
            if (best != null) {
                Log.i(TAG, "yuvBufferToRgba Huawei pick=" + (best == nv12 ? "NV12" : "NV21")
                        + " color=" + colorFormat + " " + width + "x" + height);
            }
            return best;
        }
        return i420ToRgba(yuv, width, height);
    }

    /** 华为硬解 colorFormat 常不可靠：穷举 NV21/NV12/I420 取首个非黑帧 */
    public static byte[] tryHuaweiYuvFallbacks(ByteBuffer buffer, int offset, int size, int width, int height) {
        if (buffer == null || width <= 0 || height <= 0 || size <= 0) {
            return null;
        }
        int[] formats = new int[]{
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                21,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                0
        };
        for (int color : formats) {
            byte[] rgba = yuvBufferToRgba(buffer, offset, size, width, height, color);
            if (rgba != null && !isMostlyBlackRgba(rgba, width, height)) {
                Log.i(TAG, "tryHuaweiYuvFallbacks ok color=" + color);
                return rgba;
            }
        }
        Log.w(TAG, "tryHuaweiYuvFallbacks all failed w=" + width + " h=" + height + " size=" + size);
        return null;
    }

    private static byte[] nv21ToRgba(byte[] nv21, int width, int height) {
        int frame = width * height;
        if (nv21.length < frame * 3 / 2) {
            return null;
        }
        byte[] rgba = new byte[frame * 4];
        int idx = 0;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int y = nv21[j * width + i] & 0xff;
                int uvIndex = frame + (j >> 1) * width + (i & ~1);
                int v = nv21[uvIndex] & 0xff;
                int u = nv21[uvIndex + 1] & 0xff;
                int c = y - 16;
                int d = u - 128;
                int e = v - 128;
                int r = (298 * c + 409 * e + 128) >> 8;
                int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
                int b = (298 * c + 516 * d + 128) >> 8;
                rgba[idx++] = (byte) clamp(r);
                rgba[idx++] = (byte) clamp(g);
                rgba[idx++] = (byte) clamp(b);
                rgba[idx++] = (byte) 255;
            }
        }
        return rgba;
    }

    private static byte[] nv12ToRgba(byte[] nv12, int width, int height) {
        int frame = width * height;
        if (nv12.length < frame * 3 / 2) {
            return null;
        }
        byte[] rgba = new byte[frame * 4];
        int idx = 0;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int y = nv12[j * width + i] & 0xff;
                int uvIndex = frame + (j >> 1) * width + (i & ~1);
                int u = nv12[uvIndex] & 0xff;
                int v = nv12[uvIndex + 1] & 0xff;
                int c = y - 16;
                int d = u - 128;
                int e = v - 128;
                int r = (298 * c + 409 * e + 128) >> 8;
                int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
                int b = (298 * c + 516 * d + 128) >> 8;
                rgba[idx++] = (byte) clamp(r);
                rgba[idx++] = (byte) clamp(g);
                rgba[idx++] = (byte) clamp(b);
                rgba[idx++] = (byte) 255;
            }
        }
        return rgba;
    }

    private static byte[] i420ToRgba(byte[] i420, int width, int height) {
        int frame = width * height;
        int q = frame / 4;
        if (i420.length < frame + q * 2) {
            return null;
        }
        byte[] rgba = new byte[frame * 4];
        int idx = 0;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int y = i420[j * width + i] & 0xff;
                int u = i420[frame + (j >> 1) * (width >> 1) + (i >> 1)] & 0xff;
                int v = i420[frame + q + (j >> 1) * (width >> 1) + (i >> 1)] & 0xff;
                int c = y - 16;
                int d = u - 128;
                int e = v - 128;
                int r = (298 * c + 409 * e + 128) >> 8;
                int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
                int b = (298 * c + 516 * d + 128) >> 8;
                rgba[idx++] = (byte) clamp(r);
                rgba[idx++] = (byte) clamp(g);
                rgba[idx++] = (byte) clamp(b);
                rgba[idx++] = (byte) 255;
            }
        }
        return rgba;
    }

    public static byte[] yuvImageToRgba(Image image) {
        if (image == null) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 2) {
            return null;
        }
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        byte[] yBytes = new byte[yBuf.remaining()];
        yBuf.get(yBytes);
        byte[] uBytes = new byte[uBuf.remaining()];
        uBuf.get(uBytes);
        byte[] vBytes = null;
        if (planes.length >= 3) {
            ByteBuffer vBuf = planes[2].getBuffer();
            vBytes = new byte[vBuf.remaining()];
            vBuf.get(vBytes);
        }

        byte[] rgba = null;
        // pixelStride=2：NV12(UV) / NV21(VU)；华为勿先入为主选 NV21（会蓝脸）
        if (uvPixelStride == 2) {
            byte[] asNv12 = yuvInterleavedToRgba(yBytes, uBytes, width, height, yRowStride, uvRowStride,
                    uvPixelStride, false);
            byte[] asNv21 = yuvInterleavedToRgba(yBytes, uBytes, width, height, yRowStride, uvRowStride,
                    uvPixelStride, true);
            rgba = DeviceQuirk.isHuaweiFamily()
                    ? pickLessBlueRgba(asNv12, asNv21, width, height)
                    : (asNv12 != null ? asNv12 : asNv21);
            if (rgba != null && !isMostlyBlackRgba(rgba, width, height)) {
                if (DeviceQuirk.isHuaweiFamily()) {
                    Log.i(TAG, "yuvImageToRgba interleaved pick="
                            + (rgba == asNv12 ? "NV12" : "NV21")
                            + " " + width + "x" + height);
                }
                return rgba;
            }
        }

        if (vBytes == null) {
            return rgba;
        }

        rgba = new byte[width * height * 4];
        int idx = 0;
        for (int j = 0; j < height; j++) {
            int yRow = j * yRowStride;
            int uvRow = (j >> 1) * uvRowStride;
            for (int i = 0; i < width; i++) {
                int y = yBytes[Math.min(yRow + i, yBytes.length - 1)] & 0xff;
                int uvIndex = uvRow + (i >> 1) * uvPixelStride;
                int u = uBytes[Math.min(uvIndex, uBytes.length - 1)] & 0xff;
                int v = vBytes[Math.min(uvIndex, vBytes.length - 1)] & 0xff;
                idx = writeYuvToRgba(rgba, idx, y, u, v);
            }
        }
        return rgba;
    }

    private static int writeYuvToRgba(byte[] rgba, int idx, int y, int u, int v) {
        int c = y - 16;
        int d = u - 128;
        int e = v - 128;
        int r = (298 * c + 409 * e + 128) >> 8;
        int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
        int b = (298 * c + 516 * d + 128) >> 8;
        rgba[idx++] = (byte) clamp(r);
        rgba[idx++] = (byte) clamp(g);
        rgba[idx++] = (byte) clamp(b);
        rgba[idx++] = (byte) 255;
        return idx;
    }

    /** 交错 chroma（NV21 / NV12） */
    private static byte[] yuvInterleavedToRgba(byte[] yBytes, byte[] uvBytes, int width, int height,
                                               int yRowStride, int uvRowStride, int uvPixelStride,
                                               boolean nv21) {
        if (yBytes == null || uvBytes == null || width <= 0 || height <= 0) {
            return null;
        }
        byte[] rgba = new byte[width * height * 4];
        int idx = 0;
        for (int j = 0; j < height; j++) {
            int yRow = j * yRowStride;
            int uvRow = (j >> 1) * uvRowStride;
            for (int i = 0; i < width; i++) {
                int y = yBytes[Math.min(yRow + i, yBytes.length - 1)] & 0xff;
                int uvIndex = uvRow + (i >> 1) * uvPixelStride;
                int u;
                int v;
                if (nv21) {
                    v = uvBytes[Math.min(uvIndex, uvBytes.length - 1)] & 0xff;
                    u = uvBytes[Math.min(uvIndex + 1, uvBytes.length - 1)] & 0xff;
                } else {
                    u = uvBytes[Math.min(uvIndex, uvBytes.length - 1)] & 0xff;
                    v = uvBytes[Math.min(uvIndex + 1, uvBytes.length - 1)] & 0xff;
                }
                idx = writeYuvToRgba(rgba, idx, y, u, v);
            }
        }
        return rgba;
    }

    public static byte[] rotateRgba(byte[] src, int w, int h, int degrees) {
        if (degrees == 0) {
            return src;
        }
        if (degrees == 180) {
            byte[] dst = new byte[src.length];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int si = (y * w + x) * 4;
                    int di = ((h - 1 - y) * w + (w - 1 - x)) * 4;
                    dst[di] = src[si];
                    dst[di + 1] = src[si + 1];
                    dst[di + 2] = src[si + 2];
                    dst[di + 3] = src[si + 3];
                }
            }
            return dst;
        }
        int nw = h;
        int nh = w;
        byte[] dst = new byte[nw * nh * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int si = (y * w + x) * 4;
                int dx;
                int dy;
                if (degrees == 90) {
                    // 与 iOS preferredTransform 一致：缓冲顺时针 90° 到直立
                    dx = h - 1 - y;
                    dy = x;
                } else { // 270
                    dx = y;
                    dy = w - 1 - x;
                }
                int di = (dy * nw + dx) * 4;
                dst[di] = src[si];
                dst[di + 1] = src[si + 1];
                dst[di + 2] = src[si + 2];
                dst[di + 3] = src[si + 3];
            }
        }
        return dst;
    }

    /**
     * 部分机型解码已直立，再按 KEY_ROTATION 转会整帧颠倒（对齐 iOS rotationForBufferWidth）。
     */
    public static int rotationForDecodedFrame(int width, int height, int metaRotation) {
        if (metaRotation == 0) {
            return 0;
        }
        if ((metaRotation == 90 || metaRotation == 270) && height > width) {
            // 元数据说要转 90/270，但缓冲已经是竖图 → 视为已直立
            return 0;
        }
        return metaRotation;
    }

    public static void forceOpaqueRgba(byte[] rgba) {
        if (rgba == null) {
            return;
        }
        for (int i = 3; i < rgba.length; i += 4) {
            rgba[i] = (byte) 0xff;
        }
    }

    public static void flipRgbaVertical(byte[] rgba, int w, int h) {
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

    /**
     * UV 颠倒时肤色偏蓝：选 |B-R| 更小的那份。
     * 不能只靠 isMostlyBlack——蓝脸亮度正常，旧逻辑会误收 NV21。
     */
    public static byte[] pickLessBlueRgba(byte[] a, byte[] b, int w, int h) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        boolean aBlack = isMostlyBlackRgba(a, w, h);
        boolean bBlack = isMostlyBlackRgba(b, w, h);
        if (aBlack && !bBlack) {
            return b;
        }
        if (bBlack && !aBlack) {
            return a;
        }
        return blueCastScore(a, w, h) <= blueCastScore(b, w, h) ? a : b;
    }

    /** 抽样平均 max(0, B-R)；越大越偏蓝 */
    private static int blueCastScore(byte[] rgba, int w, int h) {
        if (rgba == null || w <= 0 || h <= 0) {
            return Integer.MAX_VALUE;
        }
        int step = Math.max(1, (w * h) / 128);
        long score = 0;
        int samples = 0;
        for (int i = 0; i < w * h; i += step) {
            int o = i * 4;
            if (o + 2 >= rgba.length) {
                break;
            }
            int r = rgba[o] & 0xff;
            int g = rgba[o + 1] & 0xff;
            int b = rgba[o + 2] & 0xff;
            if (r + g + b < 40) {
                continue;
            }
            score += Math.max(0, b - r);
            samples++;
        }
        return samples <= 0 ? Integer.MAX_VALUE : (int) (score / samples);
    }

    /** 美颜失败时 readback 常近黑，用于回退原帧 */
    public static boolean isMostlyBlackRgba(byte[] rgba, int w, int h) {
        if (rgba == null || w <= 0 || h <= 0) {
            return true;
        }
        int step = Math.max(1, (w * h) / 64);
        int dark = 0;
        int samples = 0;
        for (int i = 0; i < w * h; i += step) {
            int o = i * 4;
            if (o + 2 >= rgba.length) {
                break;
            }
            int r = rgba[o] & 0xff;
            int g = rgba[o + 1] & 0xff;
            int b = rgba[o + 2] & 0xff;
            if (r + g + b < 24) {
                dark++;
            }
            samples++;
        }
        return samples > 0 && dark * 10 >= samples * 8;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (Math.min(v, 255));
    }

    private static FloatBuffer toFloatBuffer(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    private static int buildProgram() {
        String vs =
                "attribute vec2 aPosition;"
                        + "attribute vec2 aTexCoord;"
                        + "varying vec2 vTexCoord;"
                        + "uniform float uMirrorY;"
                        + "void main(){"
                        + "  gl_Position=vec4(aPosition,0.0,1.0);"
                        + "  float ty=mix(aTexCoord.y,1.0-aTexCoord.y,uMirrorY);"
                        + "  vTexCoord=vec2(aTexCoord.x,ty);"
                        + "}";
        String fs =
                "precision mediump float;"
                        + "varying vec2 vTexCoord;"
                        + "uniform sampler2D uTexture;"
                        + "void main(){ gl_FragColor=texture2D(uTexture,vTexCoord); }";
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }
}
