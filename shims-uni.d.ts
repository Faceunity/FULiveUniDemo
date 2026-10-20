/// <reference types='@dcloudio/types' />
import 'vue'

declare module '@vue/runtime-core' {
  type Hooks = App.AppInstance & Page.PageInstance;

  interface ComponentCustomOptions extends Hooks {

  }
}

declare global {
  interface Plus {
    globalEvent: {
      addEventListener: (name: string, handler: (e?: Record<string, unknown>) => void) => void
      removeEventListener: (name: string, handler: (e?: Record<string, unknown>) => void) => void
    }
  }
  interface PlusWebviewWebviewObject {
    getPosition: () => { left?: number; top?: number }
  }
}
