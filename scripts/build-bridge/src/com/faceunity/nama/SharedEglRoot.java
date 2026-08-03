package com.faceunity.nama;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.util.Log;

/**
 * 安卓侧共享 EGL 根上下文（对齐 iOS sharegroup 思路）。
 * 静图 offscreen / 导出等挂在此 shareContext 上，避免每次新建孤立上下文搞坏 Nama。
 */
final class SharedEglRoot {

    private static final String TAG = "FaceUnity-SharedEgl";
    private static final int PB_SIDE = 64;

    private static EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private static EGLContext context = EGL14.EGL_NO_CONTEXT;
    private static EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private static EGLConfig config;

    private SharedEglRoot() {
    }

    static synchronized void ensureCreated() {
        if (display != EGL14.EGL_NO_DISPLAY
                && context != EGL14.EGL_NO_CONTEXT
                && surface != EGL14.EGL_NO_SURFACE) {
            return;
        }
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("SharedEglRoot eglGetDisplay failed");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw new RuntimeException("SharedEglRoot eglInitialize failed");
        }
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0) {
            throw new RuntimeException("SharedEglRoot eglChooseConfig ES3 failed");
        }
        config = configs[0];
        int[] ctxAttrib = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttrib, 0);
        if (context == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("SharedEglRoot eglCreateContext failed");
        }
        int[] surfAttrib = {
                EGL14.EGL_WIDTH, PB_SIDE,
                EGL14.EGL_HEIGHT, PB_SIDE,
                EGL14.EGL_NONE
        };
        surface = EGL14.eglCreatePbufferSurface(display, config, surfAttrib, 0);
        if (surface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("SharedEglRoot eglCreatePbufferSurface failed");
        }
        Log.i(TAG, "SharedEglRoot created");
    }

    static synchronized EGLContext getShareContext() {
        ensureCreated();
        return context;
    }

    static synchronized EGLDisplay getDisplay() {
        ensureCreated();
        return display;
    }

    static synchronized EGLConfig getConfig() {
        ensureCreated();
        return config;
    }

    /** 在共享根上 makeCurrent，便于 deviceLost / 诊断 */
    static synchronized void makeCurrent() {
        ensureCreated();
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw new RuntimeException("SharedEglRoot eglMakeCurrent failed");
        }
    }

    static synchronized void releaseAll() {
        if (display == EGL14.EGL_NO_DISPLAY) {
            return;
        }
        try {
            EGL14.eglMakeCurrent(display, surface, surface, context);
            MediaFuSetup.deviceLostOnCurrentGl();
        } catch (Throwable t) {
            Log.e(TAG, "releaseAll deviceLost", t);
        } finally {
            try {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            } catch (Throwable ignored) {
            }
            if (surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surface);
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context);
            }
            EGL14.eglTerminate(display);
            display = EGL14.EGL_NO_DISPLAY;
            context = EGL14.EGL_NO_CONTEXT;
            surface = EGL14.EGL_NO_SURFACE;
            config = null;
            Log.i(TAG, "SharedEglRoot released");
        }
    }
}
