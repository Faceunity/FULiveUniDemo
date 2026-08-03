import { createSSRApp } from "vue";
import App from "./App.vue";

export function createApp() {
  const app = createSSRApp(App);
  // Vue3 + uni-app：原生插件 component 不是 .vue 组件，避免 resolveComponent 警告
  app.config.compilerOptions.isCustomElement = (tag) => tag === "beauty-camera";
  return {
    app,
  };
}
