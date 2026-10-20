package com.faceunity.nama;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.faceunity.core.entity.FURenderFrameData;
import com.faceunity.core.entity.FURenderInputData;
import com.faceunity.core.entity.FURenderOutputData;
import com.faceunity.core.listener.OnGlRendererListener;
import com.faceunity.core.listener.OnVideoPlayListener;
import com.faceunity.core.media.video.OnVideoRecordingListener;
import com.faceunity.core.media.video.VideoRecordHelper;
import com.faceunity.core.renderer.VideoRenderer;

import java.io.File;

/**
 * 官方 AAR VideoRenderer 的原生承载层。
 *
 * 页面只通过 NamaModule 操作这个承载层；视频解码、播放、Kit 渲染、编码和原音频
 * 合并全部由 fu_core 的 VideoRenderer / VideoRecordHelper 完成。
 */
public final class VideoRendererGLView extends GLSurfaceView {

    private static final String TAG = "FaceUnity-VideoRenderer";
    private static final float[] IDENTITY_MATRIX = {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
    };

    private static volatile boolean beautyEnabled = true;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile VideoRenderer renderer;
    private volatile VideoRecordHelper recordHelper;
    private volatile boolean playing;
    private volatile boolean firstFrameNotified;
    private volatile boolean preparingFirstFrame;
    private volatile boolean recording;
    private volatile Runnable onFirstFrameListener;
    private volatile Runnable onPlaybackEndedListener;
    private volatile String videoPath;
    private volatile int videoWidth;
    private volatile int videoHeight;

    public interface ExportCallback {
        void onSuccess(String path);

        void onError(Throwable error);

        default void onProgress(float ratio) {
        }
    }

    public VideoRendererGLView(Context context) {
        super(context);
        setZOrderOnTop(true);
        setZOrderMediaOverlay(true);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setPreserveEGLContextOnPause(true);
    }

    public void bindLayoutSize(int width, int height) {
        // 官方 VideoRenderer 根据 Surface 实际尺寸计算视频矩阵；这里保留接口，
        // 让上层只负责 position，不重复实现视频比例和旋转逻辑。
        requestLayout();
    }

    public static void setBeautyEnabledGlobal(boolean enabled) {
        beautyEnabled = enabled;
        FuKitBridge.setBeautyEnabled(enabled);
    }

    public void setBeautyEnabled(boolean enabled) {
        beautyEnabled = enabled;
        FuKitBridge.setBeautyEnabled(enabled);
        VideoRenderer current = renderer;
        if (current != null) {
            current.setFURenderSwitch(enabled);
        }
        requestRender();
    }

    public void setOnFirstFrameListener(Runnable listener) {
        onFirstFrameListener = listener;
        firstFrameNotified = false;
    }

    public void setOnPlaybackEndedListener(Runnable listener) {
        onPlaybackEndedListener = listener;
    }

    public boolean loadVideo(String path) throws Exception {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("视频路径为空");
        }
        String localPath = MediaPathUtil.toLocalFilePath(getContext(), path, ".mp4");
        videoPath = localPath;
        firstFrameNotified = false;
        preparingFirstFrame = false;
        playing = false;
        VideoRenderer old = renderer;
        renderer = null;
        if (old != null) {
            try {
                old.onPause();
                old.onDestroy();
            } catch (Throwable t) {
                Log.w(TAG, "release previous VideoRenderer", t);
            }
        }
        // openUnDrawMode=true：导出时 renderVideoUnDrawTexture 才能后台播放到结束并触发 onPlayFinish
        renderer = new VideoRenderer(this, localPath, new OnGlRendererListener() {
            @Override
            public void onSurfaceCreated() {
                try {
                    Log.i(TAG, "VideoRenderer surface created");
                    FuKitBridge.onVideoGlSurfaceCreated();
                    VideoRenderer current = renderer;
                    if (current != null) {
                        current.setFURenderSwitch(beautyEnabled);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "configure VideoRenderer", t);
                }
            }

            @Override
            public void onSurfaceChanged(int width, int height) {
            }

            @Override
            public void onRenderBefore(FURenderInputData inputData) {
            }

            @Override
            public void onRenderAfter(FURenderOutputData outputData, FURenderFrameData frameData) {
                if (outputData != null && outputData.getTexture() != null) {
                    videoWidth = outputData.getTexture().getWidth();
                    videoHeight = outputData.getTexture().getHeight();
                    VideoRecordHelper helper = recordHelper;
                    if (recording && helper != null && frameData != null) {
                        helper.frameAvailableSoon(
                                outputData.getTexture().getTexId(),
                                frameData.getTexMatrix(),
                                IDENTITY_MATRIX
                        );
                    }
                }
            }

            @Override
            public void onDrawFrameAfter() {
                if (firstFrameNotified) {
                    return;
                }
                firstFrameNotified = true;
                Log.i(TAG, "VideoRenderer first frame drawn");
                if (preparingFirstFrame) {
                    preparingFirstFrame = false;
                    VideoRenderer current = renderer;
                    if (current != null) {
                        current.pauseMediaPlayer();
                    }
                    playing = false;
                }
                Runnable listener = onFirstFrameListener;
                if (listener != null) {
                    post(listener);
                }
            }

            @Override
            public void onSurfaceDestroy() {
                recording = false;
                recordHelper = null;
                FuKitBridge.onVideoGlSurfaceDestroyed();
            }
        }, true);
        renderer.setFURenderSwitch(beautyEnabled);
        renderer.onResume();
        requestRender();
        Log.i(TAG, "VideoRenderer mounted path=" + localPath);
        return true;
    }

    /** 官方 Renderer 没有单独的 prepareFirstFrame；启动后在首帧立即暂停即可。 */
    public void prepareFirstFrame() {
        VideoRenderer current = renderer;
        if (current == null) {
            return;
        }
        firstFrameNotified = false;
        preparingFirstFrame = true;
        current.startMediaPlayer(playListener);
    }

    public void play() {
        VideoRenderer current = renderer;
        if (current == null) {
            return;
        }
        preparingFirstFrame = false;
        playing = true;
        current.startMediaPlayer(playListener);
    }

    public void pause() {
        VideoRenderer current = renderer;
        if (current != null) {
            current.pauseMediaPlayer();
        }
        playing = false;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void redrawBeautyFrame() {
        requestRender();
    }

    public void resumePreview() {
        VideoRenderer current = renderer;
        if (current != null) {
            current.onResume();
            requestRender();
        }
    }

    public void exportVideo(String sourcePath, ExportCallback callback) {
        if (callback == null) {
            return;
        }
        final VideoRenderer current = renderer;
        if (current == null) {
            callback.onError(new IllegalStateException("官方视频预览未挂载"));
            return;
        }
        if (recording) {
            callback.onError(new IllegalStateException("视频正在导出"));
            return;
        }
        final String localPath;
        try {
            localPath = MediaPathUtil.toLocalFilePath(getContext(), sourcePath, ".mp4");
        } catch (Throwable t) {
            callback.onError(t);
            return;
        }
        final int width = videoWidth > 0 ? videoWidth : Math.max(1, getWidth());
        final int height = videoHeight > 0 ? videoHeight : Math.max(1, getHeight());
        final long duration = Math.max(1L, current.getDuration());
        final VideoRecordHelper[] helperBox = new VideoRecordHelper[1];
        final VideoRecordHelper helper = new VideoRecordHelper(getContext(), new OnVideoRecordingListener() {
            @Override
            public void onPrepared() {
                recording = true;
                recordHelper = helperBox[0];
                current.renderVideoUnDrawTexture(new OnVideoPlayListener() {
                    @Override
                    public void onPlayFinish() {
                        stopExportForPlaybackEnd();
                    }

                    @Override
                    public void onError(String message) {
                        stopExportForPlaybackEnd();
                        postError(callback, new IllegalStateException(message));
                    }
                });
            }

            @Override
            public void onProcess(Long elapsedMs) {
                float ratio = elapsedMs == null ? 0f : elapsedMs / (float) duration;
                callback.onProgress(Math.max(0f, Math.min(1f, ratio)));
            }

            @Override
            public void onFinish(File file) {
                recording = false;
                recordHelper = null;
                post(() -> callback.onSuccess(file.getAbsolutePath()));
            }
        });
        helperBox[0] = helper;
        recordHelper = helper;
        try {
            helper.startRecording(this, width, height, localPath);
        } catch (Throwable t) {
            recording = false;
            recordHelper = null;
            callback.onError(t);
        }
    }

    private void stopExportForPlaybackEnd() {
        VideoRecordHelper helper = recordHelper;
        if (helper != null) {
            helper.stopRecording();
        }
    }

    private void postError(ExportCallback callback, Throwable error) {
        mainHandler.post(() -> callback.onError(error));
    }

    private final OnVideoPlayListener playListener = new OnVideoPlayListener() {
        @Override
        public void onPlayFinish() {
            playing = false;
            Runnable listener = onPlaybackEndedListener;
            if (listener != null) {
                post(listener);
            }
        }

        @Override
        public void onError(String message) {
            playing = false;
            Log.e(TAG, "video playback error: " + message);
        }
    };

    public void destroyPreviewAsync(boolean keepSession, Runnable onFinished) {
        final VideoRenderer current = renderer;
        renderer = null;
        playing = false;
        preparingFirstFrame = false;
        recording = false;
        recordHelper = null;
        if (current != null) {
            try {
                current.onPause();
                current.onDestroy();
            } catch (Throwable t) {
                Log.w(TAG, "destroy VideoRenderer", t);
            }
        }
        post(() -> {
            if (onFinished != null) {
                onFinished.run();
            }
        });
    }
}
