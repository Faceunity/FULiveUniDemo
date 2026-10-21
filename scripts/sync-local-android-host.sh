#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST_ROOT="$ROOT/android-local-host-5.15/simpleDemo"
APP_ID="$(node -p 'require(process.argv[1]).appid' "$ROOT/src/manifest.json")"
if [[ ! "$APP_ID" =~ ^__UNI__[A-Za-z0-9]+$ ]]; then
  printf 'invalid appid in src/manifest.json\n' >&2
  exit 1
fi
WEB_SRC="$ROOT/dist/build/app-plus"
if [[ ! -d "$WEB_SRC" ]]; then
  WEB_SRC="$ROOT/dist/build/app"
fi
WEB_DST="$HOST_ROOT/src/main/assets/apps/$APP_ID/www"
LIBS_DST="$HOST_ROOT/libs"
JNI_DST="$HOST_ROOT/src/main/jniLibs"
PLUGIN_ROOT="$ROOT/nativeplugins/FaceUnity-Nama/android"

if [[ ! -d "$HOST_ROOT" ]]; then
  printf 'missing host project: %s\n' "$HOST_ROOT" >&2
  exit 1
fi

if [[ ! -d "$WEB_SRC" ]]; then
  printf 'missing app-plus build output: %s\n' "$WEB_SRC" >&2
  exit 1
fi
if [[ ! -f "$WEB_SRC/manifest.json" ]]; then
  printf 'missing manifest.json in build output: %s\n' "$WEB_SRC" >&2
  exit 1
fi

if [[ ! -f "$PLUGIN_ROOT/FaceUnity-Nama.aar" ]]; then
  printf 'missing plugin aar: %s\n' "$PLUGIN_ROOT/FaceUnity-Nama.aar" >&2
  exit 1
fi

mkdir -p "$HOST_ROOT/src/main/assets/apps/$APP_ID"
mkdir -p "$LIBS_DST"
mkdir -p "$JNI_DST"

find "$HOST_ROOT/src/main/assets/apps" -mindepth 1 -maxdepth 1 -type d ! -name "$APP_ID" -exec rm -rf {} +
if [[ -d "$WEB_DST" ]]; then
  rm -rf "$WEB_DST"
fi
cp -R "$WEB_SRC" "$WEB_DST"

MANIFEST_JSON="$WEB_DST/manifest.json"
if [[ -f "$MANIFEST_JSON" ]]; then
  python3 - "$MANIFEST_JSON" "$APP_ID" "$HOST_ROOT/src/main/assets/data/dcloud_control.xml" <<'PY'
import json, sys, xml.etree.ElementTree as ET
path, appid, control = sys.argv[1:]
with open(path, 'r', encoding='utf-8') as f:
    data = json.load(f)
data['id'] = appid
with open(path, 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
    f.write('\n')
print(f'patched manifest appid -> {appid}')
tree = ET.parse(control)
for app in tree.findall('./apps/app'):
    app.set('appid', appid)
tree.write(control, encoding='utf-8', xml_declaration=True)
PY
fi

find "$LIBS_DST" -maxdepth 1 -type f \( -name 'FaceUnity-Nama.aar' -o -name 'nama.jar' -o -name 'fu_*' -o -name 'libCNamaSDK*' \) -delete
find "$JNI_DST" -mindepth 1 -maxdepth 1 -type d \( -name 'armeabi-v7a' -o -name 'arm64-v8a' \) -exec rm -rf {} +
cp -f "$PLUGIN_ROOT/FaceUnity-Nama.aar" "$LIBS_DST/"
while IFS= read -r -d '' lib; do
  cp -f "$lib" "$LIBS_DST/"
done < <(find "$PLUGIN_ROOT/libs" -maxdepth 1 -type f \( -name '*.aar' -o -name '*.jar' \) -print0)
while IFS= read -r -d '' abi; do
  cp -R "$abi" "$JNI_DST/"
done < <(find "$PLUGIN_ROOT/libs" -mindepth 1 -maxdepth 1 -type d \( -name 'armeabi-v7a' -o -name 'arm64-v8a' \) -print0)

printf 'synced host project:\n'
printf '  web assets -> %s\n' "$WEB_DST"
printf '  plugin aar -> %s/FaceUnity-Nama.aar\n' "$LIBS_DST"
find "$LIBS_DST" -maxdepth 1 -type f \( -name '*.aar' -o -name '*.jar' \) | sed 's#^#  plugin lib -> #' | sort
find "$JNI_DST" -type f -name '*.so' | sed 's#^#  plugin so  -> #' | sort
