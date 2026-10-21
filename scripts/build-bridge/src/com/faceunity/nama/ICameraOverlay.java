package com.faceunity.nama;

import android.content.Context;

import com.alibaba.fastjson.JSONObject;

/**
 * Uni 相机叠层：由官方 Kit 相机 View 提供实现。
 */
interface ICameraOverlay {

    void bindLayoutSize(int width, int height);

    void setVisibility(int visibility);

    int getWidth();

    int getHeight();

    void requestRender();

    boolean isPreviewStarted();

    void setBeautyEnabled(boolean enabled);

    void setDualInputEnabled(boolean dual);

    /** 官方 FUCamera 的采集分辨率切换；页面只传业务分辨率，不接触相机对象。 */
    default void setPreviewResolution(int width, int height) {
    }

    void switchCameraFacing();

    void tapToFocus(float localX, float localY, float previewW, float previewH);

    int getLastExposureUi();

    void setExposureCompensation(int exposure0to100);

    void freezePreview();

    void hidePreview();

    void pauseGlForHandoff();

    void resumeGlAfterHandoff();

    void resumePreview();

    void destroyPreviewAsync(boolean keepSession, Runnable onFinished);

    default     void destroyPreviewAsync(Runnable onFinished) {
        destroyPreviewAsync(false, onFinished);
    }

    boolean isGlSurfaceValid();

    default void capturePhoto(Context context, CameraCaptureCallback callback) {
        if (callback != null) {
            callback.onError("当前相机不支持拍照");
        }
    }

    default void startVideoRecord() throws Exception {
        throw new IllegalStateException("当前相机不支持录像");
    }

    default void stopVideoRecord(Context context, CameraCaptureCallback callback) {
        if (callback != null) {
            callback.onError("当前相机不支持录像");
        }
    }

    default void setFixedSurfaceSize(int width, int height) {
        android.view.View v = asView();
        if (v instanceof android.opengl.GLSurfaceView) {
            android.view.SurfaceHolder holder = ((android.opengl.GLSurfaceView) v).getHolder();
            if (holder != null) {
                holder.setFixedSize(Math.max(1, width), Math.max(1, height));
            }
        }
    }

    default void queueGlEvent(Runnable action) {
        if (action == null) {
            return;
        }
        android.view.View v = asView();
        if (v instanceof android.opengl.GLSurfaceView) {
            ((android.opengl.GLSurfaceView) v).queueEvent(() -> {
                try {
                    action.run();
                } catch (Throwable ignored) {
                }
            });
        } else {
            action.run();
        }
    }

    android.view.View asView();

    static ICameraOverlay create(Context context) {
        return new KitBeautyCameraGLView(context);
    }

    static String lastError() {
        return KitBeautyCameraGLView.getLastError();
    }

    static String previewDiag() {
        return KitBeautyCameraGLView.getPreviewDiag();
    }

    static JSONObject previewStats() {
        return KitBeautyCameraGLView.getPreviewStatsJson();
    }

    static void setBeautyEnabledGlobal(boolean enabled) {
        KitBeautyCameraGLView.setBeautyEnabledGlobal(enabled);
    }

    static void setMaxPreviewFps(int maxFps) {
        KitBeautyCameraGLView.setMaxPreviewFps(maxFps);
    }

    static int getMaxPreviewFps() {
        return KitBeautyCameraGLView.getMaxPreviewFps();
    }

    static void resetMaxPreviewFps() {
        KitBeautyCameraGLView.resetMaxPreviewFps();
    }
}
