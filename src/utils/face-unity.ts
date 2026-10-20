/**
 * FaceUnity 对 uni-app 页面公开的 JS Facade。
 *
 * 页面只应从这里调用 FaceUnity 能力；底层原生插件对象、方法名和
 * position 原生叠加实现统一收口在 nama-app.ts，避免页面依赖 Android/iOS 细节。
 */

export * from './nama-app'

import {
  detachCameraOverlay,
  ensureLocalMediaFile,
  hideCameraPreview,
  pickMediaFromAlbum,
  processStillImage,
  processStillVideo,
  resizeCameraPreview,
  showCameraPreview,
  showImagePreview,
  showVideoPreview,
} from './nama-app'

/** 挂载相机原生预览层；页面只提供预览区域位置和大小。 */
export const mountCameraPreview = showCameraPreview

/** 调整相机原生预览层位置和大小。 */
export const resizeNativePreview = resizeCameraPreview

/** 暂时隐藏相机预览但保留 SDK 会话。 */
export const hideNativePreview = hideCameraPreview

/** 页面销毁时拆除相机原生预览层。 */
export const destroyNativePreview = detachCameraOverlay

/** 挂载图片/视频原生预览层。 */
export const mountImagePreview = showImagePreview
export const mountVideoPreview = showVideoPreview

/** 调用方选择媒体和整理本地文件的适配能力。 */
export const chooseMedia = pickMediaFromAlbum
export const normalizeLocalMediaFile = ensureLocalMediaFile

/** 原生图片/视频处理能力。 */
export const processImage = processStillImage
export const processVideo = processStillVideo
