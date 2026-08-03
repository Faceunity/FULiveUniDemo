<template>
  <view class="page">
    <!-- 对齐 FULiveDemo FUMediaPickerViewController / FULiveDemoDroid SelectDataActivity -->
    <view class="back-btn" :style="backStyle" @click="goBack">
      <image class="back-btn__icon" src="/static/media-picker/back.png" mode="aspectFit" />
    </view>

    <text class="message">请从相册中选择图片或视频</text>

    <view
      class="pick-btn pick-btn--image"
      :class="{ 'pick-btn--disabled': picking }"
      @click="onChooseType('image')"
    >
      <image class="pick-btn__icon" src="/static/media-picker/icon-image.png" mode="aspectFit" />
      <text class="pick-btn__text">选择图片</text>
    </view>

    <view
      class="pick-btn pick-btn--video"
      :class="{ 'pick-btn--disabled': picking }"
      @click="onChooseType('video')"
    >
      <image class="pick-btn__icon" src="/static/media-picker/icon-video.png" mode="aspectFit" />
      <text class="pick-btn__text">选择视频</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import {
  applyStatusBarStyle,
  hideNativeTitleNView,
} from '@/utils/app-plus-style'
import {
  ensureAlbumReadPermission,
  ensureLocalMediaFile,
  pauseCameraPreview,
  pickMediaFromAlbum,
  setOverlayWindowsHidden,
} from '@/utils/nama-app'
import { setPendingMedia } from '@/utils/pending-media'

const safeAreaTop = ref(0)
const picking = ref(false)

function isIOSPlatform() {
  const sys = uni.getSystemInfoSync()
  return sys.platform === 'ios' || (sys as UniApp.GetSystemInfoResult & { osName?: string }).osName === 'ios'
}

function syncLayoutMetrics() {
  if (isIOSPlatform()) {
    safeAreaTop.value = 0
    return
  }
  const sys = uni.getSystemInfoSync()
  const top = sys.safeAreaInsets?.top
  safeAreaTop.value =
    typeof top === 'number' && top > 0
      ? Math.round(top)
      : Math.round(sys.statusBarHeight || 0)
}

const backStyle = computed(() => ({
  top: `${safeAreaTop.value + 8}px`,
}))

onLoad(() => {
  syncLayoutMetrics()
  hideNativeTitleNView()
  applyStatusBarStyle()
  void setOverlayWindowsHidden(true).catch(() => undefined)
  void pauseCameraPreview().catch(() => undefined)
})

onShow(() => {
  syncLayoutMetrics()
  hideNativeTitleNView()
  applyStatusBarStyle()
  void setOverlayWindowsHidden(true).catch(() => undefined)
  void pauseCameraPreview().catch(() => undefined)
})

function goBack() {
  uni.navigateBack()
}

function goMediaBeauty(type: 'image' | 'video', path: string) {
  setPendingMedia({ type, path })
  uni.navigateTo({
    url: `/pages/media-beauty/media-beauty?type=${type}`,
  })
}

function sleep(ms: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, ms))
}

function isCancelMessage(msg: string) {
  const lower = msg.toLowerCase()
  return lower.includes('cancel') || msg.includes('取消')
}

async function onChooseType(type: 'image' | 'video') {
  // #ifndef APP-PLUS
  uni.showToast({ title: '请运行到 App 自定义基座', icon: 'none' })
  return
  // #endif

  // #ifdef APP-PLUS
  if (picking.value) {
    return
  }
  picking.value = true
  try {
    await setOverlayWindowsHidden(true).catch(() => undefined)
    await pauseCameraPreview().catch(() => undefined)
    await sleep(80)

    // iOS：UIImagePicker 需要相册权限
    // Android：OPEN_DOCUMENT 对齐 Demo，不依赖 READ_MEDIA_*（避免空相册）
    if (isIOSPlatform()) {
      try {
        await ensureAlbumReadPermission()
      } catch (e) {
        uni.showModal({
          title: '',
          content: (e as Error).message || '需要相册权限才能选择媒体',
          confirmText: '确定',
          cancelText: '取消',
          success: (r) => {
            if (r.confirm && typeof plus !== 'undefined') {
              try {
                plus.runtime.openURL('app-settings:')
              } catch {
                // ignore
              }
            }
          },
        })
        return
      }
    }

    let path = ''
    try {
      // 双端原生选文件：iOS UIImagePicker；Android ACTION_OPEN_DOCUMENT（FULiveDemoDroid）
      path = await pickMediaFromAlbum(type)
    } catch (e) {
      const msg = (e as Error).message || ''
      if (!isCancelMessage(msg)) {
        uni.showToast({ title: msg.slice(0, 80), icon: 'none' })
      }
      return
    }

    if (!path) {
      uni.showToast({
        title: type === 'image' ? '未选择图片' : '未选择视频',
        icon: 'none',
      })
      return
    }

    const local = await ensureLocalMediaFile(
      path,
      type === 'video' ? '.mp4' : '.jpg',
    )
    goMediaBeauty(type, local)
  } finally {
    await sleep(400)
    picking.value = false
  }
  // #endif
}
</script>

<style scoped src="./media-import.css"></style>
