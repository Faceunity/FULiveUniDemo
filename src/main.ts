import { createSSRApp } from "vue";
import App from "./App.vue";

export function createApp() {
  const app = createSSRApp(App);
  // 当前采用 position 原生叠加方案；保留历史标签兼容配置，但不启用
  // <face-unity-view> 原生组件。页面通过 src/utils/face-unity.ts 调用桥接能力。
  app.config.compilerOptions.isCustomElement = (tag) => tag === "beauty-camera";
  return {
    app,
  };
}
