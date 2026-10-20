#!/usr/bin/env bash
# 将桥接链接为 FaceUnityNama.framework（依赖 FURenderKit.framework，dead_strip）
# 用于云打包与本地宿主；完整 SDK 由插件目录内 FURenderKit.framework 提供
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PLUGIN_IOS="$ROOT/nativeplugins/FaceUnity-Nama/ios"
SRC_IOS="$ROOT/scripts/build-ios-framework/src/ios"
BUILD_DIR="$ROOT/scripts/build-ios-framework/out-slim"
STUB_INC="$ROOT/scripts/build-ios-framework/stubs/inc"
FW_NAME="FaceUnityNama"
MIN_IOS="11.0"
ARCH="arm64"

mkdir -p "$BUILD_DIR"

# FURenderKit.framework 已内含完整 SDK，不再链接 libCNamaSDK.a / libfuai.a

AUTHPACK_H="${AUTHPACK_H:-$SRC_IOS/auth/authpack.h}"
if [[ ! -f "$AUTHPACK_H" ]]; then
  echo "错误: 请将授权文件放到 $AUTHPACK_H（仅本机，不提交）" >&2
  exit 1
fi
AUTH_INC="$(cd "$(dirname "$AUTHPACK_H")" && pwd)"

# 校验 FURenderKit.framework 已在插件目录
if [[ ! -d "$PLUGIN_IOS/FURenderKit.framework" ]]; then
  echo "错误: 缺少 $PLUGIN_IOS/FURenderKit.framework"
  echo "请从官方 FULiveDemo/FURenderKit 拷贝 FURenderKit.framework 及 Resources 到插件 ios 目录"
  exit 1
fi

if [[ -n "${UNIAPP_IOS_INC:-}" && -f "${UNIAPP_IOS_INC}/DCUniModule.h" ]]; then
  INC_FLAGS=(-I"$UNIAPP_IOS_INC")
  echo "使用 UniApp SDK 头文件: $UNIAPP_IOS_INC"
else
  INC_FLAGS=(-I"$STUB_INC")
  echo "使用编译桩头文件（云打包主工程提供真实 DCUni SDK）"
fi

SDKROOT="$(xcrun --sdk iphoneos --show-sdk-path)"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/obj"

CFLAGS=(
  -arch "$ARCH"
  -isysroot "$SDKROOT"
  -miphoneos-version-min="$MIN_IOS"
  -fobjc-arc
  -fmodules
  -O2
  -Wno-unused-parameter
  -Wno-unused-variable
  -Wno-deprecated-declarations
  -Wno-missing-selector-name
  -Wno-missing-declarations
  -Wno-nullability-completeness
  -I"$SRC_IOS"
  -I"$AUTH_INC"
  -F"$PLUGIN_IOS"
  "${INC_FLAGS[@]}"
)

SOURCES=(
  "$SRC_IOS/NamaModule.m"
  "$SRC_IOS/FURenderKitCameraView.m"
  "$SRC_IOS/BeautyCameraComponent.m"
  "$SRC_IOS/BeautyVideoView.m"
  "$SRC_IOS/VideoBeautyExporter.m"
  # 必须与 NamaModule 一起链进最终 framework；漏掉会留下未定义
  # _OBJC_CLASS_$_PreviewChromeView，自定义基座一加载就闪退
  "$SRC_IOS/PreviewChromeView.m"
  "$SRC_IOS/FuBeautyPanelView.m"
)

echo "编译插件桥接..."
for src in "${SOURCES[@]}"; do
  obj="$BUILD_DIR/obj/$(basename "${src%.m}.o")"
  clang -c "${CFLAGS[@]}" "$src" -o "$obj"
done

FW_DIR="$BUILD_DIR/$FW_NAME.framework"
mkdir -p "$FW_DIR"
BINARY="$FW_DIR/$FW_NAME"

echo "链接动态 framework（dead_strip）..."
# DCUniModule 由 HBuilder 基座在最终链接时提供
# Xcode 15+ 新 ld 解析 strip 过的 .a 会 CompactUnwind assert，强制经典链接器
# FURenderKit.framework 是动态库：FaceUnityNama.framework 通过 -F/-framework 引用其符号，
# 运行时由主工程 embed FURenderKit.framework 提供
xcrun clang++ -dynamiclib -arch "$ARCH" \
  -isysroot "$SDKROOT" \
  -miphoneos-version-min="$MIN_IOS" \
  -install_name "@rpath/$FW_NAME.framework/$FW_NAME" \
  -o "$BINARY" \
  "$BUILD_DIR/obj"/*.o \
  -Wl,-ld_classic \
  -Wl,-dead_strip \
  -undefined dynamic_lookup \
  -F"$PLUGIN_IOS" -framework FURenderKit \
  -framework Foundation \
  -framework UIKit \
  -framework AVFoundation \
  -framework CoreMedia \
  -framework CoreVideo \
  -framework GLKit \
  -framework OpenGLES \
  -framework Accelerate \
  -framework CoreML \
  -framework Photos \
  -framework CoreGraphics \
  -framework QuartzCore \
  -framework WebKit \
  -framework AudioToolbox \
  -framework CoreImage \
  -framework CoreMotion \
  -framework ImageIO \
  -framework Metal \
  -lc++ \
  -lz

# 不要 strip 动态 framework：链接时已有 -Wl,-dead_strip 瘦身；
# strip -S -x 会在 cctools-port 下把 LC_CODE_SIGNATURE 段写坏，
# 导致 embed 重新签名时 Identifier 变成 "FaceUnityNama.strip"，真机安装报
# "Code signing identifier does not match bundle identifier" 失败。

cat > "$FW_DIR/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDevelopmentRegion</key>
	<string>en</string>
	<key>CFBundleExecutable</key>
	<string>$FW_NAME</string>
	<key>CFBundleIdentifier</key>
	<string>com.faceunity.$FW_NAME</string>
	<key>CFBundleInfoDictionaryVersion</key>
	<string>6.0</string>
	<key>CFBundleName</key>
	<string>$FW_NAME</string>
	<key>CFBundlePackageType</key>
	<string>FMWK</string>
	<key>CFBundleShortVersionString</key>
	<string>1.0.0</string>
	<key>CFBundleVersion</key>
	<string>1</string>
	<key>MinimumOSVersion</key>
	<string>$MIN_IOS</string>
</dict>
</plist>
EOF

# 清理旧静态库产物；授权文件只在 scripts 中维护。
rm -f "$PLUGIN_IOS"/libFaceUnityNamaPlugin.a \
      "$PLUGIN_IOS"/libCNamaSDK.a \
      "$PLUGIN_IOS"/libfuai.a
rm -rf "$PLUGIN_IOS/$FW_NAME.framework"
cp -R "$FW_DIR" "$PLUGIN_IOS/$FW_NAME.framework"

# PreviewChrome 图标：打进 framework，避免仅靠 www/static（Documents）偶发读不到
CHROME_SRC=""
if [[ -d "$ROOT/scripts/build-bridge/assets/fu_chrome" ]]; then
  CHROME_SRC="$ROOT/scripts/build-bridge/assets/fu_chrome"
elif [[ -d "$ROOT/src/static/fu-chrome" ]]; then
  CHROME_SRC="$ROOT/src/static/fu-chrome"
fi
if [[ -n "$CHROME_SRC" ]]; then
  mkdir -p "$PLUGIN_IOS/$FW_NAME.framework/fu_chrome"
  cp -f "$CHROME_SRC"/*.png "$PLUGIN_IOS/$FW_NAME.framework/fu_chrome/" 2>/dev/null || true
  echo "已打包 PreviewChrome 图标: $PLUGIN_IOS/$FW_NAME.framework/fu_chrome"
  ls -la "$PLUGIN_IOS/$FW_NAME.framework/fu_chrome" || true
fi

echo ""
echo "完成: $PLUGIN_IOS/$FW_NAME.framework"
ls -lh "$PLUGIN_IOS/$FW_NAME.framework/$FW_NAME"
lipo -info "$PLUGIN_IOS/$FW_NAME.framework/$FW_NAME"
file "$PLUGIN_IOS/$FW_NAME.framework/$FW_NAME"

# 先整表导出再检查，避免 pipefail + grep -q 提前退出导致误判
NM_EXPORTS="$(nm -gU "$PLUGIN_IOS/$FW_NAME.framework/$FW_NAME" 2>/dev/null || true)"
WX_COUNT=$(printf '%s\n' "$NM_EXPORTS" | grep -c "wx_export_method_" || true)
UNI_COUNT=$(printf '%s\n' "$NM_EXPORTS" | grep -c "uni_export_method_" || true)
echo "  wx_export_method_ 导出: $WX_COUNT"
echo "  uni_export_method_ 导出: $UNI_COUNT"
printf '%s\n' "$NM_EXPORTS" | grep "OBJC_CLASS.*NamaModule" || true
printf '%s\n' "$NM_EXPORTS" | grep "OBJC_CLASS.*PreviewChromeView" || true
# FURenderKit 是动态库，符号由主工程 embed 提供；FaceUnityNama 需导出 NamaModule/PreviewChromeView 即可
printf '%s\n' "$NM_EXPORTS" | grep "OBJC_CLASS.*FURenderKitCameraView" || true
if [[ "$NM_EXPORTS" != *OBJC_CLASS*PreviewChromeView* ]]; then
  echo "错误: 最终 framework 缺少 PreviewChromeView 类（会导致自定义基座加载闪退）" >&2
  exit 1
fi

echo ""
echo "插件 ios 目录体积:"
du -sh "$PLUGIN_IOS"
du -sh "$ROOT/nativeplugins/FaceUnity-Nama"
echo "完整 SDK 由 FURenderKit.framework 提供（已 embed）"
