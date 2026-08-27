#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PLUGIN_IOS="$ROOT/nativeplugins/FaceUnity-Nama/ios"
SRC_IOS="$ROOT/scripts/build-ios-framework/src/ios"
BUILD_DIR="$ROOT/scripts/build-ios-framework/out"
STUB_INC="$ROOT/scripts/build-ios-framework/stubs/inc"
PLUGIN_LIB="libFaceUnityNamaPlugin.a"
MIN_IOS="11.0"
ARCH="arm64"

AUTHPACK_H="$SRC_IOS/auth/authpack.h"
if [[ ! -f "$AUTHPACK_H" ]]; then
  echo "错误: 缺少 authpack.h"
  echo "请将证书方提供的 authpack.h 放到:"
  echo "  $AUTHPACK_H"
  exit 1
fi
echo "已找到 authpack.h"

if [[ -n "${UNIAPP_IOS_INC:-}" && -f "${UNIAPP_IOS_INC}/DCUniModule.h" ]]; then
  INC_FLAGS=(-I"$UNIAPP_IOS_INC")
  echo "使用 UniApp SDK 头文件: $UNIAPP_IOS_INC"
else
  INC_FLAGS=(-I"$STUB_INC")
  echo "使用编译桩头文件（云打包主工程会提供真实 DCUni SDK）"
fi

SDK="$(xcrun --sdk iphoneos --show-sdk-path)"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/obj"

CFLAGS=(
  -arch "$ARCH"
  -isysroot "$SDK"
  -miphoneos-version-min="$MIN_IOS"
  -fobjc-arc
  -fmodules
  -O2
  -Wno-unused-parameter
  -Wno-unused-variable
  -Wno-deprecated-declarations
  -I"$SRC_IOS"
  -I"$SRC_IOS/auth"
  -I"$SRC_IOS/core"
  -I"$SRC_IOS/ui"
  "${INC_FLAGS[@]}"
)

SOURCES=(
  "$SRC_IOS/NamaModule.m"
  "$SRC_IOS/core/BeautyCameraView.m"
  "$SRC_IOS/ui/BeautyCameraComponent.m"
  "$SRC_IOS/core/BeautyVideoView.m"
  "$SRC_IOS/core/VideoBeautyExporter.m"
  "$SRC_IOS/ui/PreviewChromeView.m"
  "$SRC_IOS/ui/FuBeautyPanelView.m"
)

# 仅用桩头编译时，补一份 DCUniComponent 空实现以便链接进 .o（真机基座用 SDK 真类）
if [[ ! -n "${UNIAPP_IOS_INC:-}" || ! -f "${UNIAPP_IOS_INC}/DCUniModule.h" ]]; then
  if [[ -f "$STUB_INC/DCUniComponent.m" ]]; then
    SOURCES+=("$STUB_INC/DCUniComponent.m")
  fi
fi

echo "编译插件桥接源码..."
for src in "${SOURCES[@]}"; do
  obj="$BUILD_DIR/obj/$(basename "${src%.m}.o")"
  clang -c "${CFLAGS[@]}" "$src" -o "$obj"
done

# 云打包体积限制：改为链接 dead_strip 动态 framework（约 16MB）
echo ""
echo "桥接 .o 已编译，转交 build-slim-framework.sh 生成 FaceUnityNama.framework ..."
bash "$(cd "$(dirname "$0")" && pwd)/build-slim-framework.sh"
