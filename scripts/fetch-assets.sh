#!/usr/bin/env bash
# 下载构建所需的一切（VS Code 服务端、CLI、Ubuntu rootfs、proot、构建工具）
# 用法：bash scripts/fetch-assets.sh [连接数]
set -euo pipefail
cd "$(dirname "$0")/.."

CONN="${1:-16}"
SUMS=dl/SHA256SUMS
mkdir -p dl app/assets build
touch "$SUMS"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m错误:\033[0m %s\n' "$*" >&2; exit 1; }

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# 归档完整性校验：下载被截断是最常见的失败，以前要等到解压那一步才炸
verify_integrity() {
  local f="$1"
  [ -s "$f" ] || return 1
  case "$f" in
    *.tar.gz|*.tgz|*.gz) gzip -t "$f" >/dev/null 2>&1 ;;
    *.tar.xz|*.xz)       xz -t "$f" >/dev/null 2>&1 ;;
    *.zip)               unzip -tqq "$f" >/dev/null 2>&1 ;;
    *.deb)               ar t "$f" >/dev/null 2>&1 ;;
    *) return 0 ;;
  esac
}

# 记录 sha256，便于复现同一次构建用的到底是哪份文件
record_sum() {
  local f="$1" sum
  sum="$(sha256 "$f")"
  awk -v p="$f" '$2 != p' "$SUMS" > "$SUMS.tmp"
  mv "$SUMS.tmp" "$SUMS"
  printf '%s  %s\n' "$sum" "$f" >> "$SUMS"
}

# 下载 → 校验完整性 → 记账。$1=url $2=输出 $3=连接数
fetch() {
  local url="$1" out="$2" conn="${3:-$CONN}"
  if [ -s "$out" ]; then
    if verify_integrity "$out"; then record_sum "$out"; return 0; fi
    log "已有文件损坏，重新下载: $out"
    rm -f "$out"
  fi
  node pdl.js "$url" "$out" "$conn"
  verify_integrity "$out" || { rm -f "$out"; die "下载内容损坏: $url"; }
  record_sum "$out"
}

for c in node tar unzip ar; do
  command -v "$c" >/dev/null 2>&1 || die "缺少命令: $c（apt install binutils unzip 等）"
done

# ---------------------------------------------------------------- 主机架构
case "$(uname -m)" in
  aarch64|arm64) SDK_TOOLS_ASSET=android-sdk-tools-static-aarch64.zip ;;
  x86_64|amd64)  SDK_TOOLS_ASSET=android-sdk-tools-static-x86_64.zip ;;
  *) die "不支持的主机架构: $(uname -m)（需要 aarch64 或 x86_64）" ;;
esac
log "主机架构 $(uname -m) → $SDK_TOOLS_ASSET"

# 换了主机架构后，旧的 aapt2/zipalign 不能再用
ARCH_MARK=dl/HOST_ARCH
if [ -f "$ARCH_MARK" ] && [ "$(cat "$ARCH_MARK")" != "$(uname -m)" ]; then
  log "主机架构已变化，清理旧的构建工具"
  rm -rf dl/sdktools dl/sdktools.zip dl/bt dl/build-tools.zip
fi
printf '%s\n' "$(uname -m)" > "$ARCH_MARK"

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
# CLI 的文件名官方改过（linux-arm64 → linux_arm64），直接用接口给的 URL 最稳
fetch "$CLI_URL" dl/vscode_cli.tar.gz
fetch "$BASE/vscode-server-linux-arm64-web.tar.gz" dl/vscode_server_web.tar.gz
cp dl/vscode_cli.tar.gz app/assets/code.gz
tar -xzf dl/vscode_cli.tar.gz -C dl
cp dl/code app/assets/code
cp dl/vscode_server_web.tar.gz app/assets/vscode.tar.gz

# Ubuntu rootfs
fetch "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz" dl/ubuntu-base.tar.gz

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
  fetch "$1" "dl/$f" 8
  rm -rf "dl/deb-$2" && mkdir -p "dl/deb-$2"
  (cd "dl/deb-$2" && ar x "../$f" && (tar -xf data.tar.* 2>/dev/null || tar -xf data.tar.xz))
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
log "拉取 aapt2 / zipalign 与 android.jar"
if [ ! -f dl/platform30/android-11/android.jar ]; then
  mkdir -p dl/platform30
  fetch "https://dl.google.com/android/repository/platform-30_r03.zip" dl/platform30.zip 8
  unzip -q -o dl/platform30.zip -d dl/platform30
fi
if [ ! -f dl/bt/android-14/lib/d8.jar ]; then
  mkdir -p dl/bt
  fetch "https://dl.google.com/android/repository/build-tools_r34-linux.zip" dl/build-tools.zip 8
  unzip -q -o dl/build-tools.zip -d dl/bt
fi

# aapt2/zipalign：x86_64 直接用官方 build-tools 自带的，aarch64 才需要第三方静态构建
case "$(uname -m)" in
  x86_64|amd64)
    log "使用官方 build-tools 自带的 aapt2 / zipalign"
    mkdir -p dl/sdktools
    rm -rf dl/sdktools/build-tools
    ln -sfn ../bt/android-14 dl/sdktools/build-tools
    ;;
  *)
    if [ ! -x dl/sdktools/build-tools/aapt2 ]; then
      mkdir -p dl/sdktools
      fetch "https://github.com/lzhiyong/android-sdk-tools/releases/download/34.0.3/$SDK_TOOLS_ASSET" dl/sdktools.zip 8
      unzip -q -o dl/sdktools.zip -d dl/sdktools
    fi
    ;;
esac

chmod +x dl/sdktools/build-tools/aapt2 dl/sdktools/build-tools/zipalign app/assets/code
log "就绪：现在可以执行 bash build.sh"
log "下载清单（含 sha256）: $SUMS"
