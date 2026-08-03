package com.faceunity.nama;

/**
 * 对齐 iOS {@code BeautyCameraView.performWithSharedGLLock}：
 * 特殊算法写参与 Nama 渲染串行，避免 get==set 但画面无效果。
 */
final class NamaRenderLock {

    static final Object LOCK = new Object();

    private NamaRenderLock() {
    }

    static void runExclusive(Runnable action) {
        if (action == null) {
            return;
        }
        synchronized (LOCK) {
            action.run();
        }
    }
}
