/**
 * Android Nama 会话决策（纯函数）。
 *
 * 切系统导航会重建 Activity，进程却还在 → 半死。这种必须整进程冷启动，
 * 禁止 reset overlay 后再 init（那就是死一半）。
 * 只有库/handle 丢了、Activity 还活着时才就地 reinit。
 */

export type NamaSessionProbe = {
  libInit?: number
  initialized?: boolean
  cameraHandle?: number
  mediaHandle?: number
  overlayMounted?: boolean
  overlayActDead?: boolean
  overlaySurfaceValid?: boolean
  contextAlive?: boolean
}

export type JsNamaSession = {
  sdkInited: boolean
  cameraHandle: number
  sessionDirty: boolean
}

export function shouldMarkAndroidNamaSessionDirty(
  native: NamaSessionProbe,
  js: JsNamaSession,
): boolean {
  if (js.sessionDirty) {
    return true
  }
  if (native.overlayActDead === true) {
    return true
  }
  if (!js.sdkInited && native.overlayMounted === true) {
    return true
  }
  return false
}

export function androidNamaRelaunchReason(
  native: NamaSessionProbe,
  js: JsNamaSession,
): string | null {
  if (native.overlayActDead === true) {
    return 'overlayActDead'
  }
  if (native.contextAlive === false) {
    return 'contextDead'
  }
  if (!js.sdkInited && native.overlayMounted === true) {
    return 'staleOverlayAfterJsReload'
  }
  return null
}

export function shouldRelaunchAndroidProcess(
  native: NamaSessionProbe,
  js: JsNamaSession,
): boolean {
  return androidNamaRelaunchReason(native, js) != null
}

export function androidNamaReinitReason(
  native: NamaSessionProbe,
  js: JsNamaSession,
): string | null {
  if (androidNamaRelaunchReason(native, js) != null) {
    return null
  }
  const nativeHandle = Math.max(Number(native.cameraHandle) || 0, Number(native.mediaHandle) || 0)
  const libOk = Number(native.libInit) === 1 && native.initialized !== false

  if (js.sdkInited && js.cameraHandle > 0 && !libOk) {
    return 'nativeLibLost'
  }
  if (js.sdkInited && js.cameraHandle > 0 && nativeHandle <= 0) {
    return 'nativeHandleLost'
  }
  return null
}

export function shouldReinitAndroidNamaSession(
  native: NamaSessionProbe,
  js: JsNamaSession,
): boolean {
  return androidNamaReinitReason(native, js) != null
}

export type AndroidNamaCameraLeaveAction = 'park' | 'detach'

/** 离开美颜页一律 park，保住 EGL；禁止 detach 拆 GL */
export function androidNamaCameraLeaveAction(_sessionDirty: boolean): AndroidNamaCameraLeaveAction {
  return 'park'
}
