#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT_INPUT="${1:-}"
ANDROID_SDK_ROOT_LOCAL="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"

print_line() {
  printf '%s\n' "${1-}"
}

print_check() {
  local label="$1"
  local value="$2"
  printf '%-28s %s\n' "$label" "$value"
}

print_line "FULiveUniDemo local Android environment check"
print_line

PROJECT_RUNTIME="$(node -p 'require(process.argv[1]).dependencies["@dcloudio/uni-app"]' "$ROOT/package.json")"
APP_ID="$(node -p 'require(process.argv[1]).appid' "$ROOT/src/manifest.json")"
PACKAGE_NAME="$(node -p 'require(process.argv[1]).packageName || "not configured"' "$ROOT/.hbuilderx/launch.json")"

print_check "Project runtime" "${PROJECT_RUNTIME:-unknown}"
print_check "App id" "${APP_ID:-unknown}"
print_check "Package name" "${PACKAGE_NAME:-unknown}"

print_line
print_line "[Java]"
if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_HOME_LOCAL="$(/usr/libexec/java_home 2>/dev/null || true)"
  print_check "JAVA_HOME" "${JAVA_HOME_LOCAL:-not found}"
else
  print_check "JAVA_HOME" "java_home tool not found"
fi

if command -v javac >/dev/null 2>&1; then
  print_check "javac" "$(javac -version 2>&1)"
else
  print_check "javac" "not found"
fi

print_line
print_line "[Android SDK]"
print_check "Android SDK root" "$ANDROID_SDK_ROOT_LOCAL"

if [[ -d "$ANDROID_SDK_ROOT_LOCAL/platforms" ]]; then
  PLATFORM_COUNT="$(find "$ANDROID_SDK_ROOT_LOCAL/platforms" -maxdepth 1 -type d -name 'android-*' | wc -l | tr -d ' ')"
  print_check "Platforms" "$PLATFORM_COUNT installed"
else
  print_check "Platforms" "missing"
fi

if [[ -d "$ANDROID_SDK_ROOT_LOCAL/build-tools" ]]; then
  BUILD_TOOLS_COUNT="$(find "$ANDROID_SDK_ROOT_LOCAL/build-tools" -maxdepth 1 -mindepth 1 -type d | wc -l | tr -d ' ')"
  print_check "Build tools" "$BUILD_TOOLS_COUNT installed"
else
  print_check "Build tools" "missing"
fi

print_line
print_line "[Nama plugin output]"
AAR_PATH="$ROOT/nativeplugins/FaceUnity-Nama/android/FaceUnity-Nama.aar"
KIT_LIBS="$ROOT/nativeplugins/FaceUnity-Nama/android/libs"

if [[ -f "$AAR_PATH" ]]; then
  print_check "FaceUnity-Nama.aar" "present"
else
  print_check "FaceUnity-Nama.aar" "missing"
fi

for lib in fu_core_all_feature_release.aar fu_model_all_feature_release.aar fu_beauty_face_beauty_sticker_release.aar; do
  if [[ -f "$KIT_LIBS/$lib" ]]; then
    print_check "$lib" "present"
  else
    print_check "$lib" "missing"
  fi
done

print_line
print_line "[DCloud offline SDK]"
if [[ -z "$SDK_ROOT_INPUT" ]]; then
  print_check "Offline SDK path" "not provided"
  print_line "Hint: bash scripts/check-local-android-env.sh /path/to/DCloud-Android-SDK"
  exit 0
fi

print_check "Offline SDK path" "$SDK_ROOT_INPUT"

check_dir() {
  local name="$1"
  local path="$2"
  if [[ -d "$path" ]]; then
    print_check "$name" "present"
  else
    print_check "$name" "missing"
  fi
}

check_dir "HBuilder-Integrate-AS" "$SDK_ROOT_INPUT/HBuilder-Integrate-AS"
check_dir "HBuilder-Hello" "$SDK_ROOT_INPUT/HBuilder-Hello"
check_dir "SDK" "$SDK_ROOT_INPUT/SDK"
check_dir "UniPlugin-Hello-AS" "$SDK_ROOT_INPUT/UniPlugin-Hello-AS"

APP_LIB_AAR="$SDK_ROOT_INPUT/SDK/libs/lib.5plus.base-release.aar"
UNI_V8_AAR="$SDK_ROOT_INPUT/SDK/libs/uniapp-v8-release.aar"
APP_DATA_DIR="$SDK_ROOT_INPUT/SDK/assets/data"

if [[ -f "$APP_LIB_AAR" ]]; then
  print_check "lib.5plus.base-release" "present"
else
  print_check "lib.5plus.base-release" "missing"
fi

if [[ -f "$UNI_V8_AAR" ]]; then
  print_check "uniapp-v8-release" "present"
else
  print_check "uniapp-v8-release" "missing"
fi

if [[ -d "$APP_DATA_DIR" ]]; then
  print_check "SDK/assets/data" "present"
else
  print_check "SDK/assets/data" "missing"
fi
