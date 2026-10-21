# 美颜 AAR 自定义 UI 接入说明

这份文档用于给客户接入“美颜能力包”。客户可以完全使用自己的界面，只调用这里提供的美颜方法。

## 交付文件

请把下面 3 个 AAR 一起交付给客户：

```text
fu_core_face_beauty_sticker_release.aar
fu_model_face_beauty_sticker_release.aar
fu_beauty_face_beauty_sticker_release.aar
```

当前工程生成位置：

```text
result/aar/face_beauty_sticker/
```

其中：

- `fu_core`：核心渲染能力
- `fu_model`：美颜需要的模型和资源
- `fu_beauty`：不带界面的美颜控制能力

客户如果要自己做 UI，主要使用 `fu_beauty`。

## 打包命令

在当前工程根目录执行：

```bash
./gradlew :fu_beauty:assembleFace_beauty_stickerRelease
```

打包成功后会生成：

```text
result/aar/face_beauty_sticker/fu_beauty_face_beauty_sticker_release.aar
```

如果还需要重新生成核心包和资源包，可以执行：

```bash
./gradlew :fu_core:assembleFace_beauty_stickerRelease
./gradlew :fu_model:assembleFace_beauty_stickerRelease
./gradlew :fu_beauty:assembleFace_beauty_stickerRelease
```

## 客户工程接入

把 3 个 AAR 放到客户工程的 `app/libs/` 目录。

客户工程的 `app/build.gradle` 增加：

```gradle
dependencies {
    implementation fileTree(dir: 'libs', include: ['*.jar', '*.aar'])
}
```

如果客户工程没有开启本地 AAR 目录，还需要确认：

```gradle
repositories {
    flatDir {
        dirs 'libs'
    }
}
```

## 基本使用

### 第一步：初始化 SDK

客户需要在自己的 `Application` 里初始化一次。

`authpack` 不放在 AAR 里，由客户自己的 App 传入：

```java
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        FaceBeautySdk.setDebug(BuildConfig.DEBUG);
        FaceBeautySdk.init(this, authpack.A(), new BeautyInitializeCallback() {
            @Override
            public void onSuccess(int code, String message) {
                // 初始化成功
            }

            @Override
            public void onFail(int code, String message) {
                // 初始化失败，需要检查 authpack 是否正确
            }
        });
    }
}
```

这里的 `authpack.A()` 只是示例。实际接入时，客户用自己的授权文件提供 `byte[]`。

如果客户使用的是包名绑定校验，可以改成：

```java
FaceBeautySdk.initInternalCheckPackageBind(this, authpack.A(), callback);
```

如果客户使用的是内部校验，可以改成：

```java
FaceBeautySdk.initInternalCheck(this, authpack.A(), callback);
```

如果客户使用本地离线鉴权，可以使用：

```java
FaceBeautySdk.initLocal(this, authpack.A(), offlineBundle, localCallback);
```

或者：

```java
FaceBeautySdk.initDeviceLocal(this, authpack.A(), offlineBundle, localCallback);
```

### 第二步：创建美颜对象

在客户自己的相机或渲染页面中创建美颜对象：

```java
FaceBeautyKit beautyKit = new FaceBeautyKit();
```

### 第三步：绑定到渲染

初始化成功后，把美颜绑定到渲染：

```java
beautyKit.bindToRenderer();
```

打开美颜：

```java
beautyKit.setEnable(true);
```

关闭美颜：

```java
beautyKit.setEnable(false);
```

## 自定义 UI 怎么接

客户自己的 UI 只需要做三类操作：

1. 开关：打开或关闭美颜
2. 滑杆：调节某一个美颜参数
3. 菜单：切换滤镜或重置

### 滑杆调节美颜

例如调节磨皮：

```java
beautyKit.setParamIntensity("blur_level", 3.3);
```

例如调节美白：

```java
beautyKit.setParamIntensity("color_level", 0.4);
```

例如调节大眼：

```java
beautyKit.setParamIntensity("eye_enlarging", 0.4);
```

读取当前值：

```java
double value = beautyKit.getParamIntensity("blur_level");
```

### 切换滤镜

```java
beautyKit.setFilter("ziran1", 0.4);
```

第一个参数是滤镜名称，第二个参数是滤镜强度，范围是 `0.0 - 1.0`。

### 重置美颜

```java
beautyKit.reset();
```

## 给 UI 读取菜单

如果客户想自动生成自己的美颜菜单，可以读取美肤、美型和滤镜列表。

读取美肤项：

```java
List<BeautyItem> skinItems = beautyKit.getSkinBeautyItems();
```

读取美型项：

```java
List<BeautyItem> shapeItems = beautyKit.getShapeBeautyItems();
```

读取滤镜项：

```java
List<BeautyFilterItem> filterItems = beautyKit.getFilterItems();
```

每个 `BeautyItem` 里包含：

```text
key：美颜参数名称
name：默认显示名称
range：默认值、无效果值、最小值、最大值
```

客户可以不用默认名称，完全换成自己的文案和图标。

## 常用参数

### 美肤

```text
blur_level：磨皮，范围 0.0 - 6.0
body_blur_level：全身磨皮，范围 0.0 - 6.0
color_level：美白，范围 0.0 - 1.0
red_level：红润，范围 0.0 - 1.0
sharpen：锐化，范围 0.0 - 1.0
eye_bright：亮眼，范围 0.0 - 1.0
tooth_whiten：美牙，范围 0.0 - 1.0
remove_pouch_strength：祛黑眼圈，范围 0.0 - 1.0
remove_nasolabial_folds_strength：祛法令纹，范围 0.0 - 1.0
face_threed：五官立体，范围 0.0 - 1.0
clarity：清晰，范围 0.0 - 1.0
delspot_level：祛斑痘，范围 0.0 - 1.0
facial_plump：面部丰盈，范围 0.0 - 1.0
```

### 美型

```text
cheek_thinning：瘦脸，范围 0.0 - 1.0
cheek_v：V脸，范围 0.0 - 1.0
cheek_narrow：窄脸，范围 0.0 - 1.0
cheek_short：短脸，范围 0.0 - 1.0
cheek_small：小脸，范围 0.0 - 1.0
intensity_cheekbones：瘦颧骨，范围 0.0 - 1.0
intensity_lower_jaw：瘦下颌骨，范围 0.0 - 1.0
eye_enlarging：大眼，范围 0.0 - 1.0
intensity_eye_circle：圆眼，范围 0.0 - 1.0
intensity_eye_pupil：瞳孔大小，范围 0.0 - 1.0
intensity_chin：下巴，范围 0.0 - 1.0，0.5 为无变化
intensity_forehead：额头，范围 0.0 - 1.0，0.5 为无变化
intensity_nose：瘦鼻，范围 0.0 - 1.0
intensity_mouth：嘴型，范围 0.0 - 1.0，0.5 为无变化
intensity_lip_thick：嘴唇厚度，范围 0.0 - 1.0，0.5 为无变化
intensity_eye_height：眼睛位置，范围 0.0 - 1.0，0.5 为无变化
intensity_canthus：开眼角，范围 0.0 - 1.0
intensity_eye_lid：眼睑下至，范围 0.0 - 1.0
intensity_eye_space：眼距，范围 0.0 - 1.0，0.5 为无变化
intensity_eye_rotate：眼睛角度，范围 0.0 - 1.0，0.5 为无变化
intensity_long_nose：长鼻，范围 0.0 - 1.0，0.5 为无变化
intensity_philtrum：缩人中，范围 0.0 - 1.0，0.5 为无变化
intensity_smile：微笑嘴角，范围 0.0 - 1.0
intensity_brow_height：眉毛上下，范围 0.0 - 1.0，0.5 为无变化
intensity_brow_space：眉间距，范围 0.0 - 1.0，0.5 为无变化
intensity_brow_thick：眉毛粗细，范围 0.0 - 1.0，0.5 为无变化
```

## 推荐接入顺序

1. 先接入 3 个 AAR
2. 在 `Application` 里调用 `FaceBeautySdk.init(...)`
3. 初始化成功后，在相机渲染页面创建 `FaceBeautyKit`
4. 调用 `bindToRenderer()`
5. 用自己的开关调用 `setEnable(true/false)`
6. 用自己的滑杆调用 `setParamIntensity(key, value)`
7. 用自己的滤镜菜单调用 `setFilter(filterName, intensity)`
8. 用重置按钮调用 `reset()`

## 注意事项

- `fu_beauty` 不包含现成 UI，客户可以完全自定义界面。
- `authpack` 不包含在 AAR 里，必须由客户 App 自己传入。
- SDK 初始化建议放在客户自己的 `Application` 里，只做一次。
- 参数值会自动限制在可用范围内，超出范围会按最小值或最大值处理。
- 如果客户只换 UI，不改美颜能力，通常只需要调用 `FaceBeautyKit`。
- 美颜资源路径默认使用 `graphics/face_beautification.bundle`，需要确认客户工程里包含对应资源。
