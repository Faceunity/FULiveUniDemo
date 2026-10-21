# FULiveUniDemo Android 本地打包

Android 使用官方 Kit 处理相机、图片和视频。`android-local-host-5.15/` 保留离线宿主工程；前端资源和含授权的桥接 AAR 由本机生成。

## 准备

- Node.js 和项目 npm 依赖。
- JDK 17、Android SDK 35、build-tools 35.0.0，用于离线宿主 Gradle 8.7 构建。
- 与本项目 DCloud npm 依赖版本匹配的离线 SDK。目录名中的 `5.15` 是宿主模板版本，不能代替版本核对；需要时使用匹配版本的 SDK 更新宿主运行库和 `assets/data`。
- 官方 Android Kit 依赖，已放在 `nativeplugins/FaceUnity-Nama/android/libs/`。
- 自己的 FaceUnity `authpack.java`，放入 `scripts/build-bridge/src/com/faceunity/app/`。授权必须与包名和签名匹配。
- DCloud AppKey。在本机 `~/.gradle/gradle.properties` 中设置 `dcloudAppKey`，不要提交到仓库。

宿主默认包名为 `uni.app.D06F9A1`。Debug 使用 Gradle 默认调试签名，正式分发需自行设置匹配授权的签名。仓库不包含私人证书、签名口令或已授权的桥接产物。

## 构建

在项目根目录执行：

```bash
npm install
npm run type-check
npm run build:native-bridge
npm run build:custom -- app-plus
bash scripts/sync-local-android-host.sh
```

同步脚本会将当前 `src/manifest.json` 的 appid、生成的前端资源和插件依赖同步到离线宿主，覆盖宿主上一次生成的资源。

然后在宿主目录编译：

```bash
cd android-local-host-5.15
./gradlew :simpleDemo:assembleDebug
```

APK 在 `simpleDemo/build/outputs/apk/debug/`。连接设备后可用 `adb install -r` 安装。

Windows 可用 `npm run build:native-bridge:win` 编译桥接；后续资源同步可在 Git Bash 中运行，再使用 `gradlew.bat` 编译宿主。

## 修改官方 Kit

日常使用仓库已有的官方 AAR 即可。需要从官方 Android Demo 源码重建 Kit 时，将 Demo 放在本工程相邻的 `Kotlin_FaceUnity_Demo/`，或设置 `DEMO_ROOT` 指向实际目录。

```bash
bash scripts/rebuild-android-local.sh
```

该命令会重建官方 AAR、桥接、前端和离线宿主，并安装到连接的设备。多台设备时先设置 `ADB_SERIAL`。只有明确需要修改官方 Kit 时才执行此流程。

## 真机检查

拍照和录像保存后检查尺寸、比例及声音；测试图片/视频美颜导入导出、滤镜强度、预览分辨率切换、切摄像头，以及切换系统导航或锁屏后重新进入应用。
