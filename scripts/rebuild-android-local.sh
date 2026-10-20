#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST_ROOT="$ROOT/android-local-host-5.15"
PLUGIN_LIBS="$ROOT/nativeplugins/FaceUnity-Nama/android/libs"
DEMO_ROOT="${DEMO_ROOT:-$ROOT/../Kotlin_FaceUnity_Demo}"
DEMO_ALL_FEATURE_AAR_DIR="$DEMO_ROOT/result/aar/all_feature"
DEMO_FACE_BEAUTY_AAR_DIR="$DEMO_ROOT/result/aar/face_beauty_sticker"
APK_PATH="$HOST_ROOT/simpleDemo/build/outputs/apk/debug/simpleDemo-debug.apk"
ADB_BIN="$(command -v adb || true)"
ADB_SERIAL="${ADB_SERIAL:-}"

ANDROID_SDK_LOCAL="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
if [[ -z "$ADB_BIN" && -x "$ANDROID_SDK_LOCAL/platform-tools/adb" ]]; then
  ADB_BIN="$ANDROID_SDK_LOCAL/platform-tools/adb"
fi

if [[ ! -x "$DEMO_ROOT/gradlew" ]]; then
  printf 'missing official Android demo: %s (set DEMO_ROOT)\n' "$DEMO_ROOT" >&2
  exit 1
fi

if [[ -z "$ADB_BIN" ]]; then
  printf 'missing adb in PATH\n' >&2
  exit 1
fi

if [[ -z "$ADB_SERIAL" ]]; then
  usb_device="$("$ADB_BIN" devices -l | awk '/device usb:/{print $1; exit}')"
  if [[ -n "$usb_device" ]]; then
    ADB_SERIAL="$usb_device"
  else
    first_device="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
    if [[ -n "$first_device" ]]; then
      ADB_SERIAL="$first_device"
    fi
  fi
fi

if [[ -z "$ADB_SERIAL" ]]; then
  printf 'no connected adb device found\n' >&2
  exit 1
fi

printf '\n[1/8] 重新生成 FaceUnity 官方 AAR...\n'
cd "$DEMO_ROOT"
./gradlew \
  :fu_core:clean \
  :fu_model:clean \
  :fu_beauty:clean \
  :fu_core:assembleAll_featureRelease \
  :fu_model:assembleAll_featureRelease \
  :fu_beauty:assembleFace_beauty_stickerRelease

for f in \
  "$DEMO_ALL_FEATURE_AAR_DIR/fu_core_all_feature_release.aar" \
  "$DEMO_ALL_FEATURE_AAR_DIR/fu_model_all_feature_release.aar" \
  "$DEMO_FACE_BEAUTY_AAR_DIR/fu_beauty_face_beauty_sticker_release.aar"; do
  if [[ ! -f "$f" ]]; then
    printf 'missing demo aar after build: %s\n' "$f" >&2
    exit 1
  fi
done

printf '\n[2/8] 替换 FaceUnity 官方 AAR...\n'
mkdir -p "$PLUGIN_LIBS"
rm -f "$PLUGIN_LIBS/fu_core_face_beauty_sticker_release.aar"
rm -f "$PLUGIN_LIBS/fu_model_face_beauty_sticker_release.aar"
cp -f "$DEMO_ALL_FEATURE_AAR_DIR/fu_core_all_feature_release.aar" "$PLUGIN_LIBS/"
cp -f "$DEMO_ALL_FEATURE_AAR_DIR/fu_model_all_feature_release.aar" "$PLUGIN_LIBS/"
cp -f "$DEMO_FACE_BEAUTY_AAR_DIR/fu_beauty_face_beauty_sticker_release.aar" "$PLUGIN_LIBS/"

printf '\n[3/8] 重建 uni-app 原生桥 AAR...\n'
cd "$ROOT"
"$ROOT/scripts/build-bridge/build-aar.sh"

printf '\n[4/8] 清理 uni-app 构建产物...\n'
rm -rf "$ROOT/dist/build/app-plus"

printf '\n[5/8] 清理 Android 本地宿主缓存...\n'
rm -rf "$HOST_ROOT/build"
rm -rf "$HOST_ROOT/simpleDemo/build"

printf '\n[6/8] 重新构建 uni-app app-plus...\n'
cd "$ROOT"
npm run build:custom -- app-plus

printf '\n[7/8] 同步前端资源和原生 AAR 到本地宿主...\n'
"$ROOT/scripts/sync-local-android-host.sh"

printf '\n[8/8] 重新编译并安装 Android APK...\n'
cd "$HOST_ROOT"
./gradlew clean :simpleDemo:assembleDebug
printf '安装目标设备：%s\n' "$ADB_SERIAL"
"$ADB_BIN" -s "$ADB_SERIAL" install -r "$APK_PATH"

printf '\n完成。\n'
printf 'APK 路径：%s\n' "$APK_PATH"
