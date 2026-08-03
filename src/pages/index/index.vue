<template>
  <view class="page">
    <view class="header" :style="headerStyle">
      <view class="header__title">FU Live Demo 特效版</view>
    </view>

    <view class="body" :class="{ 'body--compact': isCompactScreen }" :style="scrollStyle">
      <view class="logo-box">
        <image class="logo-box__img" src="/static/homepage/banner.png" mode="aspectFill" />
      </view>

      <view class="section">
        <view class="section__head">
          <view class="section__bar" />
          <text class="section__title">人脸特效</text>
        </view>
        <view class="grid" :class="{ 'grid--compact': isCompactScreen }">
          <view class="effect-card" @click="goBeauty">
            <view class="effect-card__thumb">
              <image
                class="effect-card__icon"
                src="/static/homepage/beauty.png"
                mode="aspectFit"
              />
            </view>
            <view class="effect-card__title">
              <image
                class="effect-card__title-bg"
                src="/static/homepage/cell-bottom.png"
                mode="aspectFill"
              />
              <text class="effect-card__label">美颜</text>
            </view>
          </view>
        </view>
      </view>

      <!-- #ifndef APP-PLUS -->
      <text class="tip">请运行到 App 自定义基座</text>
      <!-- #endif -->
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
import { ensureMediaPermissions, preloadNamaSdk } from '@/utils/nama-app'

const HEADER_CONTENT_PX = 44
const safeAreaTop = ref(0)
const isCompactScreen = ref(false)

function syncLayoutMetrics() {
  const sys = uni.getSystemInfoSync()
  const top = sys.safeAreaInsets?.top
  safeAreaTop.value =
    typeof top === 'number' && top > 0
      ? Math.round(top)
      : Math.round(sys.statusBarHeight || 0)
  const h = sys.windowHeight || sys.screenHeight || 667
  const w = sys.windowWidth || sys.screenWidth || 375
  // iPhone 8/8 Plus 等矮屏：banner 过高会把功能卡片挤到图下
  isCompactScreen.value = h <= 736 || w <= 375
}

const headerStyle = computed(() => ({
  paddingTop: `${safeAreaTop.value}px`,
  height: `${safeAreaTop.value + HEADER_CONTENT_PX}px`,
  boxSizing: 'border-box' as const,
}))

const scrollStyle = computed(() => ({
  paddingTop: `${safeAreaTop.value + HEADER_CONTENT_PX}px`,
}))

onLoad(() => {
  syncLayoutMetrics()
  hideNativeTitleNView()
  applyStatusBarStyle()

  // #ifdef APP-PLUS
  // 相机/麦克风/存储一并在首页申请，避免美颜页拍摄时弹系统权限导致黑屏、相册写入异常
  ensureMediaPermissions().catch(() => {
    // 已授权时不弹 toast；真正缺权限会在美颜页再次提示
  })
  // 尽快预热：别拖太久，否则秒进美颜页会在黑屏里等 init+AI+bundle
  setTimeout(() => {
    preloadNamaSdk().catch(() => undefined)
  }, 200)
  // #endif
})

onShow(() => {
  syncLayoutMetrics()
  hideNativeTitleNView()
  applyStatusBarStyle()
})

function goBeauty() {
  // #ifdef APP-PLUS
  uni.navigateTo({ url: '/pages/beauty/beauty' })
  // #endif
  // #ifndef APP-PLUS
  uni.showToast({ title: '请运行到 App 自定义基座', icon: 'none' })
  // #endif
}

</script>

<style scoped src="./index.css"></style>
