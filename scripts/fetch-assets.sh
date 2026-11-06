#!/usr/bin/env bash
# 下载构建所需的一切（VS Code 服务端、CLI、Ubuntu rootfs、proot、构建工具）
# 用法：bash scripts/fetch-assets.sh [连接数]
set -euo pipefail
cd "$(dirname "$0")/.."

CONN="${1:-16}"
mkdir -p dl app/assets build

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

# ---------------------------------------------------------------- 版本
log "查询最新稳定版 VS Code…"
CLI_URL=$(node -e '
fetch("https://update.code.visualstudio.com/api/update/cli-linux-arm64/stable/latest")
  .then(r => r.json()).then(j => console.log(j.url)).catch(() => process.exit(1));
')
COMMIT=$(printf '%s' "$CLI_URL" | sed -E 's#.*/stable/([0-9a-f]{40})/.*#\1#')
BASE="${CLI_URL%/*}"
echo "commit=$COMMIT"
echo "$COMMIT" > dl/COMMIT
echo "$BASE" > dl/BASE_URL

# ---------------------------------------------------------------- 运行时
[ -s dl/vscode_cli.tar.gz ] || node pdl.js "$BASE/vscode_cli_linux-arm64_cli.tar.gz" dl/vscode_cli.tar.gz "$CONN"
[ -s dl/vscode_server_web.tar.gz ] || node pdl.js "$BASE/vscode-server-linux-arm64-web.tar.gz" dl/vscode_server_web.tar.gz "$CONN"
cp dl/vscode_cli.tar.gz app/assets/code.gz
tar -xzf dl/vscode_cli.tar.gz -C dl
cp dl/code app/assets/code
cp dl/vscode_server_web.tar.gz app/assets/vscode.tar.gz

# Ubuntu rootfs
[ -s dl/ubuntu-base.tar.gz ] || node pdl.js \
  "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz" \
  dl/ubuntu-base.tar.gz "$CONN"

rm -rf dl/rootfs && mkdir -p dl/rootfs
tar -xzf dl/ubuntu-base.tar.gz -C dl/rootfs
mkdir -p dl/rootfs/{opt/vscode,root/data,root/.vscode,root/.codex,dev/pts,dev/shm}
chmod 1777 dl/rootfs/tmp dl/rootfs/var/tmp
printf 'nameserver 223.5.5.5\nnameserver 1.1.1.1\n' > dl/rootfs/etc/resolv.conf
printf '127.0.0.1 localhost\n' > dl/rootfs/etc/hosts
tar --hard-dereference -czf app/assets/rootfs.tar.gz -C dl/rootfs .

# ---------------------------------------------------------------- proot
log "拉取 proot（Termux 构建）"
fetch_deb() { # $1=url $2=输出目录
  local f
  f="$(basename "$1")"
  [ -s "dl/$f" ] || node pdl.js "$1" "dl/$f" 8
  rm -rf "dl/deb-$2" && mkdir -p "dl/deb-$2"
  (cd "dl/deb-$2" && ar x "../$f" && tar -xf data.tar.* 2>/dev/null || tar -xf data.tar.xz)
}

TERMUX=https://packages.termux.dev/apt/termux-main/pool/main
fetch_deb "$TERMUX/p/proot/proot_5.1.107.102_aarch64.deb" proot
fetch_deb "$TERMUX/libt/libtalloc/libtalloc_2.5.0_aarch64.deb" talloc
fetch_deb "$TERMUX/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb" shmem

P=dl/deb-proot/data/data/com.termux/files/usr
cp "$P/bin/proot" app/assets/proot
cp "$P/lib/libtalloc.so.2" app/assets/ 2>/dev/null || cp dl/deb-talloc/data/data/com.termux/files/usr/lib/libtalloc.so.2 app/assets/
cp dl/deb-shmem/data/data/com.termux/files/usr/lib/libandroid-shmem.so app/assets/
cp "$P/libexec/proot/loader" app/assets/loader
cp "$P/libexec/proot/loader32" app/assets/loader32

# ---------------------------------------------------------------- 证书
cp /etc/ssl/certs/ca-certificates.crt app/assets/ca-certificates.crt

# ---------------------------------------------------------------- 中文消息表（可选）
if [ -d dl/language-pack ]; then
  log "生成本机构建对应的中文消息表"
  mkdir -p dl/server && rm -rf dl/server/* && tar -xzf dl/vscode_server_web.tar.gz -C dl/server --strip-components=1
  node make-nls.js dl/server dl/language-pack app/assets/nls.zh-cn.js
else
  log "跳过中文消息表（shell 会回落到官方 CDN）"
fi

# ---------------------------------------------------------------- 构建工具
log "拉取 ARM 版 aapt2 / zipalign 与 android.jar"
if [ ! -d dl/sdktools ]; then
  mkdir -p dl/sdktools
  node pdl.js "https://github.com/lzhiyong/android-sdk-tools/releases/download/34.0.3/android-sdk-tools-static-aarch64.zip" dl/sdktools.zip 8
  unzip -q -o dl/sdktools.zip -d dl/sdktools
fi
if [ ! -f dl/platform30/android-11/android.jar ]; then
  mkdir -p dl/platform30
  node pdl.js "https://dl.google.com/android/repository/platform-30_r03.zip" dl/platform30.zip 8
  unzip -q -o dl/platform30.zip -d dl/platform30
fi
if [ ! -f dl/bt/android-14/lib/d8.jar ]; then
  mkdir -p dl/bt
  node pdl.js "https://dl.google.com/android/repository/build-tools_r34-linux.zip" dl/build-tools.zip 8
  unzip -q -o dl/build-tools.zip -d dl/bt
fi

chmod +x dl/sdktools/build-tools/aapt2 dl/sdktools/build-tools/zipalign app/assets/code
log "就绪：现在可以执行 bash build.sh"
