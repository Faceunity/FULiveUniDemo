import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

/** App 原生插件 component 标签，须排除 Vue 组件解析（见 pages.json usingComponents） */
const NATIVE_COMPONENT_TAGS = new Set(['beauty-camera'])

export default defineConfig({
  plugins: [
    uni({
      vueOptions: {
        template: {
          compilerOptions: {
            isCustomElement: (tag) => NATIVE_COMPONENT_TAGS.has(tag),
          },
        },
      },
    }),
  ],
})
