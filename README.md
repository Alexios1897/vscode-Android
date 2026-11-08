# VS Code for Android

[English](README.en.md) | 中文

把**官方 VS Code** 的服务端和一套迷你 Linux 一起打进 APK。安卓平板或手机装上就能用，不需要 Termux，也不需要一台电脑在旁边。

> 非官方项目：只是把自己下载的官方组件打包成一个 App，不分发任何微软的二进制。

## 免责声明

- 本项目**非官方**，与 Microsoft Corporation 无任何关联，未获其授权、赞助或背书。
- 仓库中**不包含**任何微软的二进制或源码；`scripts/fetch-assets.sh` 只是让**使用者自己**从官方地址下载组件。
- Release 中提供的 APK 是作者的**个人自用构建**，用于在自有设备上运行，不为再分发目的提供；如需使用，建议自行 `bash build.sh` 构建。
- **VS Code**、**Visual Studio Code** 及其标志是 Microsoft Corporation 的商标。图标取自 VS Code 标志，仅用于个人构建，不代表官方。
- 随 APK 分发的 **proot** 来自 Termux，采用 **GPLv2** 许可，源码见 <https://github.com/termux/proot>。
- 使用本项目产生的任何合规责任由使用者自行承担。

## 它能做什么

- **真正的 VS Code**：官方 `code serve-web` 界面，原生全屏 WebView 呈现，不是仿制的编辑器
- **集成终端**：APK 内自带 Ubuntu 24.04 + bash，`git`、`node`、`python`、`npm` 都能直接跑
- **扩展市场**：搜索、安装扩展、主题、语言包，中文界面自动生效
- **GitHub 登录**：登录状态保存在设备上，关掉 App 再打开依然是登录态
- **直接改手机上的文件**：`/sdcard` 已挂进 Linux，打开相册、下载目录里的项目没问题
- **离线可用**：服务端跑在设备本地（`127.0.0.1:18080`），没有网也能写代码
- **后台保活**：前台服务常驻，切到别的 App 不会掉线
- **数据不丢**：扩展、设置、登录态放在 `persist/` 目录，升级 App 不会清空

## 结构

```
APK (assets)
├── rootfs.tar.gz        Ubuntu 24.04 base (aarch64)
├── vscode.tar.gz        官方 VS Code 服务端 + web 前端
├── code                 官方 VS Code CLI（serve-web）
├── proot + loader        Termux 的 proot 与加载器
└── ca-certificates.crt  根证书（扩展主机的 HTTPS 需要）
        │ 首次启动解压到应用私有目录
        ▼
proot -0 -r rootfs …  code serve-web --host 127.0.0.1 --port 18080
        ▼
全屏 WebView → http://127.0.0.1:18080
```

`persist/` 里的三个目录会挂进 Linux：

| 宿主机 | 容器内 | 内容 |
| --- | --- | --- |
| `persist/data` | `/root/data` | 用户数据、已装扩展、设置 |
| `persist/cli` | `/root/.vscode` | CLI 缓存、服务端 |
| `persist/codex` | `/root/.codex` | Codex/CLI 工具的自定义配置（可选） |

## 安全

服务只监听 `127.0.0.1`，但 Android **没有** per-App 网络隔离——同一台设备上的其它 App 一样能连上 `127.0.0.1:18080`。
所以 v2.6 起不再用 `--without-connection-token`：

- 首次启动生成 64 个十六进制字符的令牌，落在 `persist/cli/token`（容器内 `/root/.vscode/token`，权限 `0600`）
- 服务端用 `--connection-token-file` 读它，WebView 加载时自动带上 `?tkn=…`
- 日志里出现的 `tkn=` 一律打码，令牌不会写进日志文件
- 想在设备外部（例如桌面浏览器）打开：长按启动界面能看到完整地址；或 `adb shell su -c cat /data/data/io.dsh.vscode/files/persist/cli/token`
- 删掉 `persist/cli/token` 再启动会重新生成（旧的 WebView cookie 随之失效）

## 构建

需要一台 **aarch64 或 x86_64 Linux**（树莓派、ARM/x86 服务器、或者安卓上的 proot 都行）、**JDK 17**、**Node.js 18+**。`fetch-assets.sh` 会按 `uname -m` 自动选对应架构的 aapt2/zipalign。

```bash
bash scripts/fetch-assets.sh      # 下载 VS Code 服务端/CLI、Ubuntu rootfs、proot、aarch64 版 aapt2
bash build.sh                     # 产出 build/vscode.apk
adb install -r build/vscode.apk
```

`fetch-assets.sh` 会自己查最新稳定版的 commit，所以换版本不用改代码。

想换图标：替换 `icon-src.webp`（正方形图），然后 `node make-icon.js`。
想换中文文案：把语言包解压到 `dl/language-pack`，重跑 `fetch-assets.sh` 会生成 `app/assets/nls.zh-cn.js`。

## 持续集成

两个工作流分工：

| 工作流 | 触发 | 干什么 |
| --- | --- | --- |
| `.github/workflows/build.yml` | 手动 / 向 `main` 提 PR | 跑同一套脚本产出 `vscode.apk` 构件，用来验证改动能编过 |
| `.github/workflows/release.yml` | 推送 `v*` tag | 构建并把 APK 附到 GitHub Release |

两者都在 x86_64 runner 上执行（`fetch-assets.sh` 会按 `uname -m` 自动挑 aapt2/zipalign）。下载缓存走 actions/cache；每次下载都会做归档完整性校验，并把 sha256 记进 `dl/SHA256SUMS`。


## 安装与首次启动

- 直接安装 APK（需要允许「未知来源」）
- 首次启动把约 765MB 解压到应用私有目录（v2.6 起直接从 APK 流式解压，不再额外落一份 tar 副本），之后启动是秒开
- 建议留出约 1.1GB 可用空间
- 通知栏有「停止」按钮，用来结束常驻的服务端；停止后重新打开 App 会再次拉起

## 已知限制

- 只支持 **arm64** 设备（proot 与 VS Code 服务端都是 aarch64 构建）
- 首次启动较慢（解压），界面会显示进度
- `targetSdkVersion=28`：这是为了允许在应用私有目录里执行 Linux 程序，安装时系统可能提示「为旧版 Android 构建」
- 通过 `http://127.0.0.1` 访问，个别依赖 WebCrypto 的扩展能力受限
- 没有实现自动更新，换 VS Code 版本需要重新构建

## 许可

代码以 MIT 许可发布，见 [LICENSE](LICENSE)。

VS Code、Visual Studio Code 是 Microsoft Corporation 的商标。本项目与微软无关，不包含也不分发微软的二进制文件——构建时由脚本从官方地址下载。图标取自 VS Code 标志，仅用于个人构建。
