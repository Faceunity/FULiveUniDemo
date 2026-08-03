#!/usr/bin/env bash
# 编译 Android FaceUnity-Nama.aar（macOS / Linux）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD_DIR="$ROOT/scripts/build-bridge"
DEPS="$BUILD_DIR/deps"
CLASSES="$BUILD_DIR/classes"
OUT="$BUILD_DIR/out"
PLUGIN_ANDROID="$ROOT/nativeplugins/FaceUnity-Nama/android"
ANDROID_JAR="$DEPS/platform-34/android-34/android.jar"
FASTJSON_JAR="$DEPS/fastjson-1.2.83.jar"
NAMA_JAR="$PLUGIN_ANDROID/libs/nama.jar"
PLATFORM_ZIP="$DEPS/platform-34.zip"

mkdir -p "$DEPS" "$CLASSES" "$OUT"

if [[ ! -f "$FASTJSON_JAR" ]]; then
  echo "下载 fastjson..."
  curl -L -o "$FASTJSON_JAR" \
    'https://repo1.maven.org/maven2/com/alibaba/fastjson/1.2.83/fastjson-1.2.83.jar'
fi

if [[ ! -f "$ANDROID_JAR" ]]; then
  echo "下载 Android platform-34..."
  curl -L -o "$PLATFORM_ZIP" \
    'https://dl.google.com/android/repository/platform-34-ext7_r03.zip'
  rm -rf "$DEPS/platform-34"
  mkdir -p "$DEPS/platform-34"
  unzip -q "$PLATFORM_ZIP" -d "$DEPS/platform-34"
  rm -f "$PLATFORM_ZIP"
  # zip 内可能是 android-34/ 或嵌套一层
  if [[ ! -f "$ANDROID_JAR" ]]; then
    found="$(find "$DEPS/platform-34" -name android.jar | head -1)"
    if [[ -n "$found" ]]; then
      target_dir="$(dirname "$ANDROID_JAR")"
      mkdir -p "$target_dir"
      cp "$found" "$ANDROID_JAR"
    fi
  fi
fi

if [[ ! -f "$ANDROID_JAR" ]]; then
  echo "错误: 缺少 $ANDROID_JAR"
  exit 1
fi
if [[ ! -f "$NAMA_JAR" ]]; then
  echo "错误: 缺少 $NAMA_JAR"
  exit 1
fi

rm -rf "$CLASSES" "$OUT"
mkdir -p "$CLASSES" "$OUT"

STUB_SOURCES=()
while IFS= read -r f; do STUB_SOURCES+=("$f"); done < <(find "$BUILD_DIR/stubs" -name '*.java')
NAMA_SOURCES=()
while IFS= read -r f; do NAMA_SOURCES+=("$f"); done < <(find "$BUILD_DIR/src/com/faceunity/nama" -name '*.java')
AUTH_SOURCES=()
while IFS= read -r f; do AUTH_SOURCES+=("$f"); done < <(find "$BUILD_DIR/src/com/faceunity/app" -name '*.java')

CP="$ANDROID_JAR:$FASTJSON_JAR:$NAMA_JAR"
echo "编译桥接模块..."
javac -encoding UTF-8 -source 8 -target 8 -cp "$CP" -d "$CLASSES" \
  "${NAMA_SOURCES[@]}" "${AUTH_SOURCES[@]}" "${STUB_SOURCES[@]}"

jar cf "$OUT/classes.jar" -C "$CLASSES" com/faceunity/nama -C "$CLASSES" com/faceunity/app

CHROME_ASSETS="$BUILD_DIR/assets/fu_chrome"
if [[ -d "$CHROME_ASSETS" ]]; then
  STAGE="$OUT/asset_stage/fu_chrome"
  mkdir -p "$STAGE"
  cp -f "$CHROME_ASSETS"/* "$STAGE/"
  ( cd "$OUT/asset_stage" && jar uf "$OUT/classes.jar" fu_chrome )
  echo "已打包 fu_chrome 图标资源"
fi

printf '%s\n' '<?xml version="1.0" encoding="utf-8"?><manifest package="com.faceunity.nama" />' \
  > "$OUT/AndroidManifest.xml"

AAR_PATH="$PLUGIN_ANDROID/FaceUnity-Nama.aar"
rm -f "$AAR_PATH"
(
  cd "$OUT"
  jar cf "$AAR_PATH" AndroidManifest.xml classes.jar
  if [[ -d "$CHROME_ASSETS" ]]; then
    mkdir -p aar_assets/assets/fu_chrome
    cp -f "$CHROME_ASSETS"/* aar_assets/assets/fu_chrome/
    ( cd aar_assets && jar uf "$AAR_PATH" assets )
  fi
)

echo "已生成: $AAR_PATH"
ls -lh "$AAR_PATH"
