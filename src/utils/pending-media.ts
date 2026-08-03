/** 跨页传递导入媒体路径（避免 navigateTo query 丢路径 / 编码问题） */

export type PendingMedia = {
  type: 'image' | 'video'
  path: string
}

let pending: PendingMedia | null = null

export function setPendingMedia(media: PendingMedia) {
  pending = media
}

export function takePendingMedia(): PendingMedia | null {
  const media = pending
  pending = null
  return media
}

export function peekPendingMedia(): PendingMedia | null {
  return pending
}
