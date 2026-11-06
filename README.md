# VS Code for Android

[English](README.en.md) | 中文

把**官方 VS Code** 的服务端和一套迷你 Linux 一起打进 APK。安卓平板或手机装上就能用，不需要 Termux，也不需要一台电脑在旁边。

> 非官方项目：只是把自己下载的官方组件打包成一个 App，不分发任何微软的二进制。

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

## 构建

需要一台 **aarch64 Linux**（树莓派、ARM 服务器、或者安卓上的 proot 都行）、**JDK 17**、**Node.js 18+**。

```bash
bash scripts/fetch-assets.sh      # 下载 VS Code 服务端/CLI、Ubuntu rootfs、proot、aarch64 版 aapt2
bash build.sh                     # 产出 build/vscode.apk
adb install -r build/vscode.apk
```

`fetch-assets.sh` 会自己查最新稳定版的 commit，所以换版本不用改代码。

想换图标：替换 `icon-src.webp`（正方形图），然后 `node make-icon.js`。
想换中文文案：把语言包解压到 `dl/language-pack`，重跑 `fetch-assets.sh` 会生成 `app/assets/nls.zh-cn.js`。

## 安装与首次启动

- 直接安装 APK（需要允许「未知来源」）
- 首次启动会把约 700MB 解压到应用私有目录，之后启动是秒开
- 需要约 1.2GB 可用空间

## 已知限制

- 只支持 **arm64** 设备（proot 与 VS Code 服务端都是 aarch64 构建）
- 首次启动较慢（解压），界面会显示进度
- `targetSdkVersion=28`：这是为了允许在应用私有目录里执行 Linux 程序，安装时系统可能提示「为旧版 Android 构建」
- 通过 `http://127.0.0.1` 访问，个别依赖 WebCrypto 的扩展能力受限
- 没有实现自动更新，换 VS Code 版本需要重新构建

## CI

打 `v*` tag 会在 `ubuntu-24.04-arm` 上自动构建并把 APK 附到 Release（见 `.github/workflows/release.yml`）。

想让自己发布的每个版本签名一致，在仓库 Secrets 里加一个 `KEYSTORE_BASE64`（你 `vscode.jks` 的 base64）；不加的话 CI 会临时生成一把新密钥，版本之间无法覆盖安装。

## 许可

代码以 MIT 许可发布，见 [LICENSE](LICENSE)。

VS Code、Visual Studio Code 是 Microsoft Corporation 的商标。本项目与微软无关，不包含也不分发微软的二进制文件——构建时由脚本从官方地址下载。图标取自 VS Code 标志，仅用于个人构建。
