package com.faceunity.nama.core;

import android.util.Log;

import com.faceunity.wrapper.faceunity;

/**
 * GL 线程写参：异步投递（可丢）/ 同步等待（特殊算法）。
 * 全局互斥锁为 faceunity.class，与 BeautyCameraGLView / 静图 / 视频导出共用（对齐原 NamaRenderLock）。
 */
public final class NamaGlExecutor {

    private static final String TAG = "FaceUnity-Nama";

    private NamaGlExecutor() {
    }

    /** 全局 Nama 互斥锁内执行（对齐 iOS performWithSharedGLLock）。 */
    public static void runExclusive(Runnable action) {
        synchronized (faceunity.class) {
            action.run();
        }
    }

    /** 非关键写参：异步投递到相机 GL（可丢）；无相机时直接全局锁内执行。 */
    public static void runAsync(BeautyCameraGLView cam, Runnable action) {
        if (action == null) {
            return;
        }
        if (cam != null) {
            try {
                cam.queueEvent(() -> {
                    try {
                        runExclusive(action);
                    } catch (Throwable t) {
                        Log.w(TAG, "runAsync gl", t);
                    }
                });
                cam.requestRender();
                return;
            } catch (Throwable t) {
                Log.w(TAG, "runAsync queue", t);
            }
        }
        try {
            runExclusive(action);
        } catch (Throwable t) {
            Log.w(TAG, "runAsync direct", t);
        }
    }

    /**
     * 特殊算法写参：同步等待 GL 队列执行，与 DualInput 共用全局 Nama 锁。
     * 禁止超时后无锁裸写（会导致 get=set 无画面效果）。
     */
    public static void runSync(BeautyCameraGLView cam, Runnable action) {
        if (action == null) {
            return;
        }
        if (cam != null) {
            final Object waitLock = new Object();
            final boolean[] done = { false };
            final int prevMode = cam.getRenderMode();
            try {
                // WHEN_DIRTY 时强制刷一帧，避免 queueEvent 迟迟不跑
                cam.setRenderMode(BeautyCameraGLView.RENDERMODE_CONTINUOUSLY);
                cam.queueEvent(() -> {
                    try {
                        runExclusive(action);
                    } catch (Throwable t) {
                        Log.w(TAG, "runSync gl", t);
                    } finally {
                        synchronized (waitLock) {
                            done[0] = true;
                            waitLock.notifyAll();
                        }
                    }
                });
                cam.requestRender();
                long deadline = System.currentTimeMillis() + 2000L;
                synchronized (waitLock) {
                    while (!done[0] && System.currentTimeMillis() < deadline) {
                        try {
                            waitLock.wait(16);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "runSync queue", t);
            } finally {
                try {
                    cam.setRenderMode(prevMode);
                } catch (Throwable ignored) {
                }
            }
            if (done[0]) {
                return;
            }
            Log.w(TAG, "runSync timeout → SharedEgl/exclusive fallback");
        }
        // 无相机或队列超时：仍在全局锁内写（必要时 makeCurrent SharedEgl）
        try {
            runExclusive(() -> {
                boolean made = false;
                try {
                    SharedEglRoot.makeCurrent();
                    made = true;
                } catch (Throwable ignored) {
                }
                try {
                    action.run();
                } finally {
                    if (made) {
                        try {
                            android.opengl.EGL14.eglMakeCurrent(
                                    SharedEglRoot.getDisplay(),
                                    android.opengl.EGL14.EGL_NO_SURFACE,
                                    android.opengl.EGL14.EGL_NO_SURFACE,
                                    android.opengl.EGL14.EGL_NO_CONTEXT);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "runSync fallback", t);
        }
    }
}
