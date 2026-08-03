#!/usr/bin/env bash
# 将桥接 + libCNamaSDK.a + libfuai.a 链接为单个动态 FaceUnityNama.framework（dead_strip）
# 体积约 16MB，用于云打包免费档；完整 .a 放在 sdk-libs/（scripts 已被 .hbuilderxignore）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PLUGIN_IOS="$ROOT/nativeplugins/FaceUnity-Nama/ios"
SRC_IOS="$ROOT/scripts/build-ios-framework/src/ios"
SDK_LIBS="$ROOT/scripts/build-ios-framework/sdk-libs"
BUILD_DIR="$ROOT/scripts/build-ios-framework/out-slim"
STUB_INC="$ROOT/scripts/build-ios-framework/stubs/inc"
FW_NAME="FaceUnityNama"
MIN_IOS="11.0"
ARCH="arm64"

mkdir -p "$SDK_LIBS"

# 若完整 SDK 还在插件目录，先挪到 sdk-libs（避免云打包计入体积）
for lib in libCNamaSDK.a libfuai.a; do
  if [[ -f "$PLUGIN_IOS/$lib" ]]; then
    if [[ ! -f "$SDK_LIBS/$lib" ]] || [[ "$PLUGIN_IOS/$lib" -nt "$SDK_LIBS/$lib" ]]; then
      echo "迁移 $lib → sdk-libs/"
      mv -f "$PLUGIN_IOS/$lib" "$SDK_LIBS/$lib"
    else
      rm -f "$PLUGIN_IOS/$lib"
    fi
  fi
done

for lib in libCNamaSDK.a libfuai.a; do
  if [[ ! -f "$SDK_LIBS/$lib" ]]; then
    echo "错误: 缺少 $SDK_LIBS/$lib"
    echo "请从官方 ios_release 拷贝 libCNamaSDK.a / libfuai.a 到该目录"
    exit 1
  fi
done

AUTHPACK_H="${AUTHPACK_H:-$PLUGIN_IOS/authpack.h}"
if [[ ! -f "$AUTHPACK_H" ]]; then
  AUTHPACK_H="$SRC_IOS/authpack.h"
fi
if [[ ! -f "$AUTHPACK_H" ]]; then
  echo "错误: 缺少 authpack.h"
  exit 1
fi
cp -f "$AUTHPACK_H" "$SRC_IOS/authpack.h"
if [[ "$AUTHPACK_H" != "$PLUGIN_IOS/authpack.h" ]]; then
  cp -f "$AUTHPACK_H" "$PLUGIN_IOS/authpack.h"
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
  -I"$SRC_IOS"
  "${INC_FLAGS[@]}"
)

SOURCES=(
  "$SRC_IOS/NamaModule.m"
  "$SRC_IOS/BeautyCameraView.m"
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
xcrun clang++ -dynamiclib -arch "$ARCH" \
  -isysroot "$SDKROOT" \
  -miphoneos-version-min="$MIN_IOS" \
  -install_name "@rpath/$FW_NAME.framework/$FW_NAME" \
  -o "$BINARY" \
  "$BUILD_DIR/obj"/*.o \
  -Wl,-ld_classic \
  -Wl,-dead_strip \
  -undefined dynamic_lookup \
  -L"$SDK_LIBS" -lCNamaSDK -lfuai \
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
  -lc++ \
  -lz

strip -S -x "$BINARY"

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

# 清理插件 ios 目录中的旧静态库产物，只保留 framework + authpack
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
printf '%s\n' "$NM_EXPORTS" | grep " _fuSetup$" || true
if [[ "$NM_EXPORTS" != *OBJC_CLASS*PreviewChromeView* ]]; then
  echo "错误: 最终 framework 缺少 PreviewChromeView 类（会导致自定义基座加载闪退）" >&2
  exit 1
fi

echo ""
echo "插件 ios 目录体积:"
du -sh "$PLUGIN_IOS"
du -sh "$ROOT/nativeplugins/FaceUnity-Nama"
echo "完整 SDK 静态库（不上传云打包）: $SDK_LIBS"
ls -lh "$SDK_LIBS"/*.a
