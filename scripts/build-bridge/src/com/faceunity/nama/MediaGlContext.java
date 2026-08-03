package com.faceunity.nama;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.Log;

/**
 * 媒体静图/导出共用的 offscreen EGL。
 * 固定最大 PBuffer，避免按图尺寸放大时静默重建上下文却未 deviceLost，
 * 导致 Nama GL 资源挂在已毁上下文上（白屏/花屏/无美颜）。
 */
final class MediaGlContext {

    private static final String TAG = "FaceUnity-MediaGL";
    /** 与 ImageBeautyProcessor / VideoBeautyProcessor MAX_SIDE 对齐 */
    private static final int PB_SIDE = 1920;

    private static EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private static EGLContext context = EGL14.EGL_NO_CONTEXT;
    private static EGLSurface surface = EGL14.EGL_NO_SURFACE;

    private MediaGlContext() {
    }

    static synchronized void makeCurrent(int width, int height) {
        // width/height 仅用于校验；实际 surface 固定最大边，不再因单次更大图而重建
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid size");
        }
        if (display == EGL14.EGL_NO_DISPLAY || context == EGL14.EGL_NO_CONTEXT
                || surface == EGL14.EGL_NO_SURFACE) {
            create(PB_SIDE, PB_SIDE);
        }
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            releaseEglOnly();
            create(PB_SIDE, PB_SIDE);
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new RuntimeException("eglMakeCurrent failed");
            }
        }
    }

    /** @return true 若确实销毁了 EGL 并执行了 deviceLost（调用方应清 handle / 重载） */
    static synchronized boolean releaseAll() {
        boolean had = display != EGL14.EGL_NO_DISPLAY
                && context != EGL14.EGL_NO_CONTEXT
                && surface != EGL14.EGL_NO_SURFACE;
        if (!had) {
            return false;
        }
        try {
            EGL14.eglMakeCurrent(display, surface, surface, context);
            MediaFuSetup.deviceLostOnCurrentGl();
        } catch (Throwable t) {
            Log.e(TAG, "releaseAll deviceLost", t);
        } finally {
            releaseEglOnly();
        }
        return true;
    }

    private static void create(int width, int height) {
        SharedEglRoot.ensureCreated();
        display = SharedEglRoot.getDisplay();
        EGLConfig cfg = SharedEglRoot.getConfig();
        int[] ctxAttrib = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        // 与 SharedEglRoot 共享，对齐 iOS sharegroup：静图/导出可复用 Nama GL 资源
        context = EGL14.eglCreateContext(display, cfg, SharedEglRoot.getShareContext(), ctxAttrib, 0);
        if (context == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("eglCreateContext(share) failed");
        }
        int[] surfAttrib = {
                EGL14.EGL_WIDTH, Math.max(width, 64),
                EGL14.EGL_HEIGHT, Math.max(height, 64),
                EGL14.EGL_NONE
        };
        surface = EGL14.eglCreatePbufferSurface(display, cfg, surfAttrib, 0);
        if (surface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("eglCreatePbufferSurface failed");
        }
        Log.i(TAG, "create offscreen EGL(shared) " + width + "x" + height);
    }

    private static void releaseEglOnly() {
        // 只拆自己的 surface/context，勿 terminate 共享 display
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surface);
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context);
            }
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        surface = EGL14.EGL_NO_SURFACE;
    }
}
