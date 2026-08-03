package com.faceunity.nama;

/**
 * 双管线美颜 item：相机与媒体互不销毁对方的 handle。
 */
public final class FuBeautyHandle {

    /** 摄像头实时美颜 */
    public static volatile int cameraHandle = 0;
    /** 相册图片/视频美颜 */
    public static volatile int mediaHandle = 0;

    /** @deprecated 兼容旧代码，等同 cameraHandle */
    public static volatile int itemHandle = 0;

    private FuBeautyHandle() {
    }

    public static int forPipeline(boolean media) {
        return media ? mediaHandle : cameraHandle;
    }

    public static void setPipelineHandle(boolean media, int handle) {
        if (media) {
            mediaHandle = handle;
        } else {
            cameraHandle = handle;
            itemHandle = handle;
        }
    }

    public static void clearCamera() {
        cameraHandle = 0;
        itemHandle = 0;
    }

    public static void clearMedia() {
        mediaHandle = 0;
    }

    public static void clearAll() {
        cameraHandle = 0;
        mediaHandle = 0;
        itemHandle = 0;
    }
}
