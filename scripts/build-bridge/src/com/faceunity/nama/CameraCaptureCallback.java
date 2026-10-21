package com.faceunity.nama;

/**
 * 相机拍照/录像结果回调。
 *
 * 不依赖旧 Nama 相机实现，官方 Kit 相机和桥接层共用这个最小接口。
 */
interface CameraCaptureCallback {
    void onSuccess(String path);

    void onError(String message);
}
