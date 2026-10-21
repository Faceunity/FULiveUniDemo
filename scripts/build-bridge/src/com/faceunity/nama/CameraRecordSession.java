package com.faceunity.nama;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.faceunity.core.entity.FURenderFrameData;
import com.faceunity.core.entity.FURenderOutputData;
import com.faceunity.core.media.video.OnVideoRecordingListener;
import com.faceunity.core.media.video.VideoRecordHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 相机录像适配层：只把官方 CameraRenderer 的输出帧转交给 AAR 编码器。
 */
final class CameraRecordSession {

    private static final String TAG = "FaceUnity-CameraRecord";

    interface Callback {
        void onSuccess(String path);

        void onError(String message);
    }

    private final Object lock = new Object();
    private VideoRecordHelper helper;
    private Callback callback;
    private boolean recording;

    boolean isRecording() {
        synchronized (lock) {
            return recording;
        }
    }

    void start(GLSurfaceView surface, int width, int height, Callback callback) throws Exception {
        if (surface == null || callback == null) {
            throw new IllegalArgumentException("录像参数不能为空");
        }
        synchronized (lock) {
            if (recording || helper != null) {
                throw new IllegalStateException("正在录制或收尾中");
            }
            this.callback = callback;
            VideoRecordHelper next = new VideoRecordHelper(surface.getContext(),
                    new OnVideoRecordingListener() {
                        @Override
                        public void onPrepared() {
                            synchronized (lock) {
                                recording = true;
                            }
                        }

                        @Override
                        public void onProcess(Long elapsedMs) {
                        }

                        @Override
                        public void onFinish(File file) {
                            Callback done;
                            synchronized (lock) {
                                recording = false;
                                helper = null;
                                done = CameraRecordSession.this.callback;
                                CameraRecordSession.this.callback = null;
                            }
                            if (done != null) {
                                if (file != null && file.exists() && file.length() > 0) {
                                    try {
                                        done.onSuccess(saveToGallery(surface.getContext(), file));
                                    } catch (Throwable t) {
                                        Log.e(TAG, "save camera video to gallery", t);
                                        done.onError(t.getMessage() != null
                                                ? t.getMessage() : "视频保存失败");
                                    }
                                } else {
                                    done.onError("官方录像文件为空");
                                }
                            }
                        }
                    });
            helper = next;
            try {
                next.startRecording(surface, width, height);
            } catch (Exception e) {
                helper = null;
                this.callback = null;
                throw e;
            }
        }
    }

    void offerFrame(FURenderOutputData outputData, FURenderFrameData frameData) {
        VideoRecordHelper current;
        synchronized (lock) {
            if (!recording || helper == null) {
                return;
            }
            current = helper;
        }
        if (outputData == null || outputData.getTexture() == null || frameData == null) {
            return;
        }
        current.frameAvailableSoon(
                outputData.getTexture().getTexId(),
                frameData.getTexMatrix(),
                IDENTITY_MATRIX
        );
    }

    void stop() {
        VideoRecordHelper current;
        synchronized (lock) {
            current = helper;
        }
        if (current != null) {
            current.stopRecording();
        }
    }

    void cancel() {
        VideoRecordHelper current;
        synchronized (lock) {
            current = helper;
            helper = null;
            recording = false;
            callback = null;
        }
        if (current != null) {
            try {
                current.stopRecording();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String saveToGallery(Context context, File source) throws Exception {
        if (context == null || source == null || !source.exists()) {
            throw new IllegalStateException("录像文件不存在");
        }
        String name = "FU_" + System.currentTimeMillis() + ".mp4";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/FULive");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("无法创建相册视频记录");
            }
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IllegalStateException("无法写入相册视频");
                }
                copy(in, out);
                values.clear();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
                Log.i(TAG, "camera video saved uri=" + uri + " bytes=" + source.length());
                return uri.toString();
            } catch (Throwable t) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                }
                throw t;
            }
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "FULive");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建视频目录");
        }
        File target = new File(dir, name);
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
        context.sendBroadcast(new android.content.Intent(
                android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(target)));
        return target.getAbsolutePath();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) >= 0) {
            if (count > 0) {
                out.write(buffer, 0, count);
            }
        }
        out.flush();
    }

    private static final float[] IDENTITY_MATRIX = {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
    };
}
