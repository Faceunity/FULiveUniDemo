#!/usr/bin/env bash
# 编译 Android FaceUnity-Nama.aar（macOS / Linux）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD_DIR="$ROOT/scripts/build-bridge"
DEPS="$BUILD_DIR/deps"
CLASSES="$BUILD_DIR/classes"
OUT="$BUILD_DIR/out"
PLUGIN_ANDROID="$ROOT/nativeplugins/FaceUnity-Nama/android"
LIBS_DIR="$PLUGIN_ANDROID/libs"
ANDROID_JAR="$DEPS/platform-34/android-34/android.jar"
FASTJSON_JAR="$DEPS/fastjson-1.2.83.jar"
KOTLIN_STDLIB="$DEPS/kotlin-stdlib-1.3.72.jar"
PLATFORM_ZIP="$DEPS/platform-34.zip"

FU_CORE_AAR="$LIBS_DIR/fu_core_all_feature_release.aar"
FU_BEAUTY_AAR="$LIBS_DIR/fu_beauty_face_beauty_sticker_release.aar"
FU_MODEL_AAR="$LIBS_DIR/fu_model_all_feature_release.aar"
FU_SDK_EXTRACT="$DEPS/fu_sdk_extract"
AUTHPACK_JAVA="$BUILD_DIR/src/com/faceunity/app/authpack.java"

if [[ ! -f "$AUTHPACK_JAVA" ]]; then
  echo "错误: 请将授权文件放到 $AUTHPACK_JAVA（仅本机，不提交）" >&2
  exit 1
fi

mkdir -p "$DEPS" "$CLASSES" "$OUT"

if [[ ! -f "$FASTJSON_JAR" ]]; then
  echo "下载 fastjson..."
  curl -fL -o "$FASTJSON_JAR" \
    'https://repo1.maven.org/maven2/com/alibaba/fastjson/1.2.83/fastjson-1.2.83.jar'
fi

if [[ ! -f "$KOTLIN_STDLIB" ]]; then
  echo "下载 kotlin-stdlib..."
  curl -fL -o "$KOTLIN_STDLIB" \
    'https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.3.72/kotlin-stdlib-1.3.72.jar'
fi

if [[ ! -f "$ANDROID_JAR" ]]; then
  echo "下载 Android platform-34..."
  curl -fL -o "$PLATFORM_ZIP" \
    'https://dl.google.com/android/repository/platform-34-ext7_r03.zip'
  rm -rf "$DEPS/platform-34"
  mkdir -p "$DEPS/platform-34"
  unzip -q "$PLATFORM_ZIP" -d "$DEPS/platform-34"
  rm -f "$PLATFORM_ZIP"
  if [[ ! -f "$ANDROID_JAR" ]]; then
    found="$(find "$DEPS/platform-34" -name android.jar | head -1)"
    if [[ -n "$found" ]]; then
      target_dir="$(dirname "$ANDROID_JAR")"
      mkdir -p "$target_dir"
      cp "$found" "$ANDROID_JAR"
    fi
  fi
fi

for f in "$FU_CORE_AAR" "$FU_BEAUTY_AAR" "$FU_MODEL_AAR"; do
  if [[ ! -f "$f" ]]; then
    echo "错误: 缺少 $f"
    exit 1
  fi
done

if [[ ! -f "$ANDROID_JAR" ]]; then
  echo "错误: 缺少 $ANDROID_JAR"
  exit 1
fi

rm -rf "$FU_SDK_EXTRACT"
mkdir -p "$FU_SDK_EXTRACT"
unzip -q -o "$FU_CORE_AAR" -d "$FU_SDK_EXTRACT/fu_core"
unzip -q -o "$FU_BEAUTY_AAR" -d "$FU_SDK_EXTRACT/fu_beauty"
unzip -q -o "$FU_MODEL_AAR" -d "$FU_SDK_EXTRACT/fu_model"

NAMA_JAR="$FU_SDK_EXTRACT/fu_core/libs/nama.jar"
FU_CORE_CLASSES="$FU_SDK_EXTRACT/fu_core/classes.jar"
FU_BEAUTY_CLASSES="$FU_SDK_EXTRACT/fu_beauty/classes.jar"

if [[ ! -f "$NAMA_JAR" ]]; then
  echo "错误: fu_core AAR 内缺少 libs/nama.jar"
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

CP="$ANDROID_JAR:$FASTJSON_JAR:$KOTLIN_STDLIB:$NAMA_JAR:$FU_CORE_CLASSES:$FU_BEAUTY_CLASSES"
echo "编译桥接模块（fu_core + fu_beauty + nama.jar）..."
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
HOST_AAR_PATH="$ROOT/android-local-host-5.15/simpleDemo/libs/FaceUnity-Nama.aar"
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

if [[ -d "$(dirname "$HOST_AAR_PATH")" ]]; then
  cp -f "$AAR_PATH" "$HOST_AAR_PATH"
  echo "已同步到本地 Demo: $HOST_AAR_PATH"
fi

echo "已生成: $AAR_PATH"
echo "请确保云打包/自定义基座已包含 libs 下 3 个官方 AAR（fu_core_all_feature / fu_model_all_feature / fu_beauty）"
ls -lh "$AAR_PATH"
