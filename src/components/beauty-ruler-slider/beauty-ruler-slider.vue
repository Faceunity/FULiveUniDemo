<template>
  <view class="ruler">
    <text v-if="dragging" class="ruler__value">{{ displayValue }}</text>
    <view class="ruler__track">
      <view class="ruler__indicator">
        <view class="ruler__bar" />
      </view>
      <scroll-view
        class="ruler__scroll"
        scroll-x
        :scroll-left="scrollLeft"
        :scroll-with-animation="scrollWithAnimation"
        show-scrollbar="false"
        @scroll="onScroll"
        @touchstart="onDragStart"
        @touchend="onDragEnd"
        @touchcancel="onDragEnd"
      >
        <view class="ruler__content" :style="{ width: contentWidth + 'px' }">
          <view class="ruler__pad" :style="{ width: padWidth + 'px' }" />
          <view class="ruler__ticks" :style="{ width: rulerSpan + 'px' }">
            <view
              v-for="i in tickCount + 1"
              :key="i"
              class="ruler__tick"
              :class="{ 'ruler__tick--major': (i - 1) % 10 === 0 }"
              :style="{ left: (i - 1) * tickGap + 'px' }"
            />
          </view>
          <view class="ruler__pad" :style="{ width: padWidth + 'px' }" />
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, getCurrentInstance, nextTick, onMounted, ref, watch } from 'vue'
import {
  normalizedToSlider,
  normalizedValue,
  sliderToValue,
  type BeautyEffectItem,
} from '@/config/beauty-effects'

const props = defineProps<{
  item: BeautyEffectItem
  modelValue: number
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number]
  change: [payload: { item: BeautyEffectItem; slider: number; value: number }]
}>()

const TICK_GAP = 14
const TICK_COUNT = 100

const tickCount = TICK_COUNT
const tickGap = TICK_GAP
const rulerSpan = TICK_COUNT * TICK_GAP

const padWidth = ref(180)
const scrollLeft = ref(0)
const scrollWithAnimation = ref(false)
const scrolling = ref(false)
const suppressChange = ref(true)
const dragging = ref(false)
let dragHideTimer: ReturnType<typeof setTimeout> | null = null

const contentWidth = computed(() => padWidth.value * 2 + rulerSpan)
const maxScrollLeft = computed(() => rulerSpan)

/** 显示 SDK 实际参数值（滑杆内部仍是 0~100） */
const displayValue = computed(() =>
  sliderToValue(props.modelValue, props.item).toFixed(2),
)

function emitFromSlider(slider: number) {
  emit('update:modelValue', slider)
  if (suppressChange.value) {
    return
  }
  emit('change', {
    item: props.item,
    slider,
    value: sliderToValue(slider, props.item),
  })
}

function scrollLeftFromSlider(slider: number) {
  const n = normalizedValue(slider, props.item)
  return Math.round(n * maxScrollLeft.value)
}

function sliderFromScrollLeft(left: number) {
  const max = maxScrollLeft.value
  if (max <= 0) return 0
  const n = Math.min(1, Math.max(0, left / max))
  return normalizedToSlider(n, props.item)
}

function syncScrollFromModel() {
  scrollWithAnimation.value = false
  suppressChange.value = true
  scrollLeft.value = scrollLeftFromSlider(props.modelValue)
  setTimeout(() => {
    suppressChange.value = false
  }, 120)
}

function measurePad() {
  const instance = getCurrentInstance()
  uni
    .createSelectorQuery()
    .in(instance?.proxy)
    .select('.ruler__track')
    .boundingClientRect((rect) => {
      if (!rect || Array.isArray(rect) || !rect.width || rect.width <= 0) return
      padWidth.value = Math.round(rect.width / 2)
      nextTick(() => syncScrollFromModel())
    })
    .exec()
}

function onDragStart() {
  if (dragHideTimer) {
    clearTimeout(dragHideTimer)
    dragHideTimer = null
  }
  dragging.value = true
}

function onDragEnd() {
  if (dragHideTimer) {
    clearTimeout(dragHideTimer)
  }
  dragHideTimer = setTimeout(() => {
    dragging.value = false
    dragHideTimer = null
  }, 400)
}

function onScroll(e: { detail: { scrollLeft: number } }) {
  if (scrolling.value) return
  scrolling.value = true
  dragging.value = true
  const left = Math.min(maxScrollLeft.value, Math.max(0, e.detail.scrollLeft))
  scrollLeft.value = left
  emitFromSlider(sliderFromScrollLeft(left))
  setTimeout(() => {
    scrolling.value = false
  }, 16)
}

watch(
  () => props.modelValue,
  (v, old) => {
    if (scrolling.value) return
    if (v === old) return
    syncScrollFromModel()
  },
)

watch(
  () => props.item.key,
  () => {
    nextTick(() => {
      measurePad()
      syncScrollFromModel()
    })
  },
)

onMounted(() => {
  nextTick(() => {
    measurePad()
    syncScrollFromModel()
  })
})
</script>

<style scoped>
.ruler {
  position: relative;
  padding-top: 36rpx;
}

.ruler__value {
  position: absolute;
  top: 0;
  left: 50%;
  z-index: 3;
  transform: translateX(-50%);
  font-size: 24rpx;
  font-weight: 600;
  color: #ffcc00;
  line-height: 36rpx;
  white-space: nowrap;
}

.ruler__track {
  position: relative;
  height: 88rpx;
  overflow: hidden;
}

.ruler__indicator {
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 2;
  width: 3rpx;
  height: 44rpx;
  transform: translate(-50%, -50%);
  pointer-events: none;
}

.ruler__bar {
  width: 3rpx;
  height: 44rpx;
  border-radius: 2rpx;
  background: #ffcc00;
}

.ruler__scroll {
  width: 100%;
  height: 88rpx;
  mask-image: linear-gradient(
    to right,
    transparent 0,
    #000 36rpx,
    #000 calc(100% - 36rpx),
    transparent 100%
  );
  -webkit-mask-image: linear-gradient(
    to right,
    transparent 0,
    #000 36rpx,
    #000 calc(100% - 36rpx),
    transparent 100%
  );
}

.ruler__content {
  display: flex;
  flex-direction: row;
  align-items: center;
  height: 88rpx;
}

.ruler__pad {
  flex-shrink: 0;
  height: 1px;
}

.ruler__ticks {
  position: relative;
  flex-shrink: 0;
  height: 44rpx;
}

.ruler__tick {
  position: absolute;
  top: 50%;
  width: 2px;
  height: 12rpx;
  margin-left: -1px;
  transform: translateY(-50%);
  background: rgba(255, 255, 255, 0.28);
}

.ruler__tick--major {
  height: 22rpx;
  background: rgba(255, 255, 255, 0.5);
}
</style>
