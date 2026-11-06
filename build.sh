#!/usr/bin/env bash
# 构建 VS Code Android APK（aarch64 主机上用 ARM 版 aapt2）
set -euo pipefail
cd "$(dirname "$0")"

VCODE="${VCODE:-1}"
VNAME="${VNAME:-1.0}"
JAR=dl/platform30/android-11/android.jar
BT=dl/sdktools/build-tools

rm -rf build/classes build/dex
mkdir -p build/classes build/dex

echo "== javac"
javac -source 8 -target 8 -nowarn -bootclasspath "$JAR" -classpath "$JAR" \
  -d build/classes app/src/io/dsh/vscode/*.java 2>&1 | grep -v "^Note:" || true

echo "== d8"
java -cp dl/bt/android-14/lib/d8.jar com.android.tools.r8.D8 \
  --lib "$JAR" --min-api 26 --output build/dex build/classes/io/dsh/vscode/*.class

echo "== aapt2 compile"
if [ -d app/res ]; then
  "$BT/aapt2" compile --dir app/res -o build/res.zip
fi

echo "== aapt2 link"
rm -f build/app-unsigned.apk build/app-aligned.apk build/vscode.apk
RESARG=""; [ -f build/res.zip ] && RESARG="-R build/res.zip"
"$BT/aapt2" link -o build/app-unsigned.apk -I "$JAR" \
  --manifest app/AndroidManifest.xml -A app/assets -0 gz ${RESARG:-} \
  --min-sdk-version 26 --target-sdk-version 28 \
  --version-code "$VCODE" --version-name "$VNAME"

echo "== add dex"
(cd build && zip -q -j app-unsigned.apk dex/classes.dex)

echo "== zipalign"
"$BT/zipalign" -f -p 4 build/app-unsigned.apk build/app-aligned.apk

echo "== sign"
if [ ! -f build/vscode.jks ]; then
  keytool -genkeypair -keystore build/vscode.jks -alias vscode -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android -dname "CN=VSCode Android, O=DSH, C=CN"
fi
java -jar dl/bt/android-14/lib/apksigner.jar sign \
  --ks build/vscode.jks --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 26 --out build/vscode.apk build/app-aligned.apk

ls -lh build/vscode.apk
