#!/usr/bin/env bash
# 将官方 Fat 静态库瘦身为 arm64-only（与 package.json validArchitectures 一致）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# 完整 SDK 静态库已迁到 sdk-libs（不进云打包）；瘦身后需再跑 build:ios-framework
SDK_LIBS="$ROOT/scripts/build-ios-framework/sdk-libs"
ARCH="arm64"

LIBS=(libCNamaSDK.a libfuai.a)

for lib in "${LIBS[@]}"; do
  src="$SDK_LIBS/$lib"
  if [[ ! -f "$src" ]]; then
    echo "错误: 缺少 $src"
    exit 1
  fi
  if ! lipo -info "$src" | grep -q "are:"; then
    echo "跳过 $lib（已是单架构）"
    lipo -info "$src"
    continue
  fi
  tmp="$SDK_LIBS/.${lib}.thin"
  lipo -thin "$ARCH" "$src" -output "$tmp"
  mv "$tmp" "$src"
  echo "瘦身完成: $lib"
  lipo -info "$src"
  ls -lh "$src"
done

echo ""
echo "完成。请执行 npm run build:ios-framework 重新生成 FaceUnityNama.framework。"
