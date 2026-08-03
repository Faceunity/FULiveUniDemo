/** App-PLUS 页面样式：隐藏系统原生导航栏，避免「Nama 初始化」「美颜相机」叠层 */

export function hideNativeTitleNView() {
  // #ifdef APP-PLUS
  try {
    const style: Record<string, unknown> = {
      titleNView: false,
    }
    const pages = getCurrentPages()
    const page = pages[pages.length - 1] as {
      $getAppWebview?: () => { setStyle: (s: Record<string, unknown>) => void }
    }
    page?.$getAppWebview?.()?.setStyle(style)
    if (typeof plus !== 'undefined' && plus.webview?.currentWebview) {
      plus.webview.currentWebview().setStyle(style)
    }
  } catch {
    // ignore
  }
  // #endif
}

export function applyTransparentWebViewStyle() {
  // #ifdef APP-PLUS
  try {
    const style: Record<string, unknown> = {
      titleNView: false,
    }
    if (typeof plus !== 'undefined' && plus.os.name === 'iOS') {
      style.popGesture = 'none'
    }
    const pages = getCurrentPages()
    const page = pages[pages.length - 1] as {
      $getAppWebview?: () => { setStyle: (s: Record<string, unknown>) => void }
    }
    page?.$getAppWebview?.()?.setStyle(style)
    if (typeof plus !== 'undefined' && plus.webview?.currentWebview) {
      plus.webview.currentWebview().setStyle(style)
    }
  } catch {
    // ignore
  }
  // #endif
}

export function applyStatusBarStyle() {
  // #ifdef APP-PLUS
  try {
    plus.navigator.setStatusBarStyle('light')
    plus.navigator.setStatusBarBackground('#000000')
  } catch {
    // ignore
  }
  // #endif
}
