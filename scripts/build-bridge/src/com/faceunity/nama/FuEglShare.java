package com.faceunity.nama;

import android.opengl.GLSurfaceView;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

/**
 * 相机 / 视频预览共享 EGLContext，视频层才能直接贴 Nama 输出的 GL 纹理。
 */
final class FuEglShare {

    private static volatile EGLContext sSharedContext;

    private FuEglShare() {
    }

    static GLSurfaceView.EGLContextFactory createContextFactory() {
        return new Factory();
    }

    private static final class Factory implements GLSurfaceView.EGLContextFactory {
        private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;

        @Override
        public EGLContext createContext(EGL10 egl, EGLDisplay display, EGLConfig config) {
            int[] attrib = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE};
            EGLContext shared = sSharedContext;
            EGLContext ctx = egl.eglCreateContext(display, config, shared, attrib);
            if (shared == null && ctx != null && ctx != EGL10.EGL_NO_CONTEXT) {
                sSharedContext = ctx;
            }
            return ctx;
        }

        @Override
        public void destroyContext(EGL10 egl, EGLDisplay display, EGLContext context) {
            if (context != null && context.equals(sSharedContext)) {
                sSharedContext = null;
            }
            egl.eglDestroyContext(display, context);
        }
    }
}
