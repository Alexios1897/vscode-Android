#!/usr/bin/env bash
# 构建 VS Code Android APK（主机可以是 aarch64 或 x86_64，aapt2/zipalign 由 fetch-assets.sh 按架构选）
set -euo pipefail
cd "$(dirname "$0")"

VCODE="${VCODE:-1}"
VNAME="${VNAME:-1.0}"
JAR=dl/platform30/android-11/android.jar
BT=dl/sdktools/build-tools

rm -rf build/classes build/dex
mkdir -p build/classes build/dex

echo "== 注入服务端 commit"
SRC=build/src
rm -rf "$SRC" && mkdir -p "$SRC" && cp app/src/io/dsh/vscode/*.java "$SRC"/
if [ -f dl/COMMIT ]; then
  C="$(tr -d '[:space:]' < dl/COMMIT)"
  if ! printf '%s' "$C" | grep -qE '^[0-9a-f]{40}$'; then
    echo "dl/COMMIT 内容不合法: $C" >&2; exit 1
  fi
  sed -i -E "s/static final String COMMIT = \"[0-9a-f]{40}\"/static final String COMMIT = \"$C\"/" "$SRC/Boot.java"
  grep -q "String COMMIT = \"$C\"" "$SRC/Boot.java" || { echo "注入 COMMIT 失败" >&2; exit 1; }
  echo "   COMMIT=$C"
else
  echo "   没有 dl/COMMIT，沿用源码里的值"
fi

echo "== javac"
# 以前这里是 `javac ... | grep -v "^Note:" || true`：编译错误连同 pipefail 一起被吞掉，
# 一路走到 d8/aapt2 才炸，报错还极难懂。这里保留日志并显式失败。
JAVAC_LOG=build/javac.log
if ! javac -source 8 -target 8 -nowarn -bootclasspath "$JAR" -classpath "$JAR" \
     -d build/classes "$SRC"/*.java >"$JAVAC_LOG" 2>&1; then
  echo "== javac 失败，输出如下：" >&2
  cat "$JAVAC_LOG" >&2
  exit 1
fi
grep -v "^Note:" "$JAVAC_LOG" || true

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
