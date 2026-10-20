package com.faceunity.nama;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.util.Log;

import com.faceunity.core.entity.FURenderFrameData;
import com.faceunity.core.entity.FURenderInputData;
import com.faceunity.core.entity.FURenderOutputData;
import com.faceunity.core.listener.OnGlRendererListener;
import com.faceunity.core.media.photo.OnPhotoRecordingListener;
import com.faceunity.core.media.photo.PhotoRecordHelper;
import com.faceunity.core.renderer.PhotoRenderer;
import com.faceunity.core.utils.GlUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * AAR PhotoRenderer 的原生承载层。
 *
 * 页面不接触 GLSurfaceView 或 PhotoRenderer；NamaModule 只负责把这个承载层
 * 挂到指定 position。真正的图片读取、纹理创建和 FURenderKit 渲染全部由 AAR 完成。
 */
public final class PhotoRendererGLView extends GLSurfaceView {

    private static final String TAG = "FaceUnity-PhotoRenderer";
    private static volatile boolean beautyEnabled = true;

    private volatile PhotoRenderer renderer;
    private volatile Runnable onFirstFrameListener;
    private volatile boolean firstFrameNotified;
    private volatile CaptureRequest captureRequest;
    private int debugFrameCount;
    private String photoPath;

    public interface CaptureCallback {
        void onSuccess(String path);

        void onError(Throwable error);
    }

    private static final class CaptureRequest {
        final File output;
        final CaptureCallback callback;

        CaptureRequest(File output, CaptureCallback callback) {
            this.output = output;
            this.callback = callback;
        }
    }

    public PhotoRendererGLView(Context context) {
        super(context);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);
        setZOrderMediaOverlay(true);
        setPreserveEGLContextOnPause(true);
    }

    /** SurfaceView 的实际布局尺寸由 position 容器决定，保留该方法兼容旧桥接调用。 */
    public void bindLayoutSize(int width, int height) {
        // PhotoRenderer 在 surfaceChanged 中按真实 Surface 尺寸计算裁剪矩阵。
    }

    public void setOnFirstFrameListener(Runnable listener) {
        onFirstFrameListener = listener;
        firstFrameNotified = false;
    }

    public static void setBeautyEnabledGlobal(boolean enabled) {
        beautyEnabled = enabled;
    }

    public void setBeautyEnabled(boolean enabled) {
        beautyEnabled = enabled;
        PhotoRenderer current = renderer;
        if (current != null) {
            current.setFURenderSwitch(enabled);
        }
        requestRender();
    }

    /**
     * PhotoRenderer 在构造时接收图片路径；因此图片预览每次换图都创建新的原生承载层。
     */
    public boolean loadImage(String path) throws Exception {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("图片路径为空");
        }
        photoPath = MediaPathUtil.toLocalFilePath(getContext(), path, ".jpg");
        firstFrameNotified = false;
        renderer = new PhotoRenderer(this, photoPath, new OnGlRendererListener() {
            @Override
            public void onSurfaceCreated() {
                // 与 Demo ShowPhotoActivity 完全一致：在 PhotoRenderer 的
                // surfaceCreated 完成后再配置 AI 检测和 FaceBeauty 模型。
                try {
                    FuKitBridge.preparePhotoRenderer();
                    Log.i(TAG, "onSurfaceCreated configured kit=" + FuKitBridge.isActive());
                } catch (Throwable t) {
                    Log.e(TAG, "configure PhotoRenderer", t);
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
                if (debugFrameCount < 3) {
                    int textureId = outputData != null && outputData.getTexture() != null
                            ? outputData.getTexture().getTexId() : 0;
                    Log.i(TAG, "onRenderAfter texture=" + textureId);
                }
                CaptureRequest request = captureRequest;
                if (request != null && outputData != null && outputData.getTexture() != null
                        && frameData != null) {
                    captureRequest = null;
                    FURenderOutputData.FUTexture texture = outputData.getTexture();
                    PhotoRecordHelper helper = new PhotoRecordHelper(new OnPhotoRecordingListener() {
                        @Override
                        public void onRecordSuccess(Bitmap bitmap) {
                            try {
                                File parent = request.output.getParentFile();
                                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                                    throw new IOException("创建图片输出目录失败");
                                }
                                FileOutputStream stream = new FileOutputStream(request.output);
                                try {
                                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                                        throw new IOException("图片编码失败");
                                    }
                                } finally {
                                    stream.close();
                                    bitmap.recycle();
                                }
                                post(() -> request.callback.onSuccess(request.output.getAbsolutePath()));
                            } catch (Throwable error) {
                                post(() -> request.callback.onError(error));
                            }
                        }
                    });
                    helper.sendRecordingData(
                            texture.getTexId(),
                            frameData.getTexMatrix(),
                            GlUtil.IDENTITY_MATRIX,
                            texture.getWidth(),
                            texture.getHeight(),
                            false,
                            true
                    );
                }
            }

            @Override
            public void onDrawFrameAfter() {
                if (firstFrameNotified) {
                    return;
                }
                debugFrameCount++;
                firstFrameNotified = true;
                final Runnable callback = onFirstFrameListener;
                if (callback != null) {
                    post(callback);
                }
            }

            @Override
            public void onSurfaceDestroy() {
                // 图片切换/页面退出会创建新的 PhotoRenderer Surface，
                // 旧 Surface 的 Kit 绑定必须失效，下一张图片才能重新 bind。
                FuKitBridge.onPhotoGlSurfaceDestroyed();
            }
        });
        renderer.setFURenderSwitch(beautyEnabled);
        requestRender();
        Log.i(TAG, "PhotoRenderer mounted path=" + photoPath);
        return true;
    }

    public void redrawBeautyFrame() {
        requestRender();
    }

    /** 对齐 Demo Activity.onResume：恢复 GL 线程并补一帧，避免页面回显时停在黑帧。 */
    public void resumePreview() {
        PhotoRenderer current = renderer;
        if (current == null) {
            return;
        }
        try {
            current.onResume();
        } catch (Throwable t) {
            Log.w(TAG, "resume PhotoRenderer", t);
        }
        requestRender();
        Log.i(TAG, "PhotoRenderer resumed");
    }

    /** 在官方 PhotoRenderer 的当前输出帧上截图，不重新走旧 Nama item。 */
    public void captureCurrentFrame(File output, CaptureCallback callback) {
        if (output == null || callback == null) {
            return;
        }
        captureRequest = new CaptureRequest(output, callback);
        requestRender();
    }

    public void destroyPreviewAsync(boolean keepSession, Runnable onFinished) {
        final PhotoRenderer current = renderer;
        renderer = null;
        captureRequest = null;
        try {
            if (current != null) {
                current.onPause();
                current.onDestroy();
            }
        } catch (Throwable t) {
            Log.w(TAG, "destroy PhotoRenderer", t);
        }
        post(() -> {
            if (onFinished != null) {
                onFinished.run();
            }
        });
    }
}
