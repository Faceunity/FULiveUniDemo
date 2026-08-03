<template>
  <view class="beauty-slider">
    <view class="beauty-slider__head">
      <text class="beauty-slider__name">{{ item.name }}</text>
      <text class="beauty-slider__value">{{ displayValue }}</text>
    </view>
    <slider
      class="beauty-slider__bar"
      :value="modelValue"
      min="0"
      max="100"
      step="1"
      activeColor="#007aff"
      backgroundColor="#e5e5e5"
      block-size="18"
      @changing="onChanging"
      @change="onChange"
    />
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { sliderToValue, type BeautyEffectItem } from '@/config/beauty-effects'

const props = defineProps<{
  item: BeautyEffectItem
  modelValue: number
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number]
  change: [payload: { item: BeautyEffectItem; slider: number; value: number }]
}>()

const displayValue = computed(() => sliderToValue(props.modelValue, props.item).toFixed(2))

function emitChange(slider: number) {
  emit('change', {
    item: props.item,
    slider,
    value: sliderToValue(slider, props.item),
  })
}

function onChanging(e: { detail: { value: number } }) {
  const slider = Number(e.detail.value)
  emit('update:modelValue', slider)
  emitChange(slider)
}

function onChange(e: { detail: { value: number } }) {
  const slider = Number(e.detail.value)
  emit('update:modelValue', slider)
  emitChange(slider)
}
</script>

<style scoped>
.beauty-slider {
  padding: 20rpx 0;
}

.beauty-slider__head {
  display: flex;
  flex-direction: row;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8rpx;
}

.beauty-slider__name {
  font-size: 28rpx;
  color: #333;
}

.beauty-slider__value {
  font-size: 24rpx;
  color: #007aff;
}

.beauty-slider__bar {
  margin: 0;
}
</style>
