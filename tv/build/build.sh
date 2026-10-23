#!/bin/bash
# FloatWin 模块构建脚本（无 Gradle 链）
#   javac(8) -> d8(35.0.0) -> aapt2(win) -> zipalign -> apksigner
# 用法: bash build.sh v11
set -e

VER="${1:-v11}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# ---- 以下路径按你的环境覆盖（都可用环境变量改写，未设则用默认值）----
#   JAVA_HOME  : JDK 根目录（需要 javac/keytool/java）
#   ANDROID_SDK: Android SDK 根目录（platforms/android-34 + build-tools/35.0.0）
#   PY         : python 解释器（仅用于把 dex 注入 apk）
JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-25.0.4.7-hotspot}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
KEYTOOL="$JAVA_HOME/bin/keytool"
SDK="${ANDROID_SDK:-/d/android_sdk}"
AJ="$SDK/platforms/android-34/android.jar"
BT="$SDK/build-tools/35.0.0"
AAPT2="$ROOT/build/aapt2win/aapt2.exe"
KS="$ROOT/build/debug.keystore"
PY="${PY:-python}"

if [ ! -f "$AAPT2" ]; then
  echo "缺少 $AAPT2（Google 官方 Windows 版 aapt2）"; exit 1
fi
if [ ! -f "$KS" ]; then
  echo "== 生成 debug.keystore =="
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

echo "== 1/5 javac =="
rm -rf build/out/classes build/out/dex; mkdir -p build/out/classes build/out/dex
"$JAVAC" -source 8 -target 8 -nowarn \
  -bootclasspath "$AJ" \
  -cp "build/api-82.jar" \
  -d build/out/classes \
  src/com/gpro27u/floatwin/HookEntry.java 2>&1 | grep -v "^warning" || true
ls build/out/classes/com/gpro27u/floatwin/ || { echo "javac 失败"; exit 1; }

echo "== 2/5 d8 =="
"$BT/d8" --lib "$AJ" --output build/out/dex $(find build/out/classes -name "*.class")

echo "== 3/5 aapt2 link =="
"$AAPT2" link -I "$AJ" \
  --manifest AndroidManifest.xml \
  -A assets \
  --min-sdk-version 26 --target-sdk-version 34 \
  -o build/out/base.apk

echo "== 4/5 注入 dex =="
"$PY" - <<'PYEOF'
import zipfile, shutil
shutil.copyfile('build/out/base.apk', 'build/out/unsigned.apk')
z = zipfile.ZipFile('build/out/unsigned.apk', 'a', zipfile.ZIP_DEFLATED)
z.write('build/out/dex/classes.dex', 'classes.dex', zipfile.ZIP_DEFLATED)
z.close()
print('  dex 已注入')
PYEOF
if "$BT/zipalign" -f 4 build/out/unsigned.apk build/out/aligned.apk 2>/dev/null; then
  echo "  zipalign ok"
else
  echo "  zipalign 不可用，用未对齐包（条目均为 DEFLATED）"
  cp build/out/unsigned.apk build/out/aligned.apk
fi

echo "== 5/5 apksigner =="
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "build/out/FloatWin-$VER.apk" build/out/aligned.apk

cp "build/out/FloatWin-$VER.apk" "dist/FloatWin-$VER.apk"

echo
echo "== 产物 =="
ls -l "dist/FloatWin-$VER.apk"
"$JAVA" -jar "$BT/lib/apksigner.jar" verify --print-certs "dist/FloatWin-$VER.apk" 2>&1 | head -5
"$AAPT2" dump badging "dist/FloatWin-$VER.apk" | head -3
