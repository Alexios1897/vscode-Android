把**官方 VS Code** 的服务端和一套迷你 Linux 一起打进 APK。装上就能在安卓平板/手机上写代码，不需要 Termux，也不需要电脑。

> 非官方项目：只把自己下载的官方组件打包成一个 App，不分发任何微软的二进制。

## 适用环境

| 项目 | 要求 |
| --- | --- |
| 系统 | **Android 8.0（API 26）及以上**，`targetSdkVersion=28` |
| 架构 | **仅 arm64-v8a（aarch64）** |
| 内存 | 建议 **4GB 以上**，8GB 更稳 |
| 存储 | APK 约 266MB；首次解压后总计约 **1.2GB** |
| Root | **不需要**（proot 用户态容器） |
| WebView | 系统 Android System WebView（Chromium 内核） |
| 网络 | 首次启动不需要；装扩展、GitHub 登录需要 |

**不适用**：x86/x86_64 模拟器、Windows Subsystem for Android、32 位 ARM 设备、Android 7 及以下。

## 安装

1. 下载本页的 `vscode.apk`
2. 允许「未知来源」后安装（系统提示「为旧版 Android 构建」属正常）
3. 首次启动解压约 700MB，约 2–3 分钟；之后秒开

## 功能

官方 VS Code 界面 · 内置终端（Ubuntu 24.04 + bash）· 扩展市场 · GitHub 登录可持久化 · 直接编辑 `/sdcard` · 离线可用 · 后台保活 · 升级不丢扩展和登录态

## 源码

见仓库内 `scripts/fetch-assets.sh` 与 `build.sh`。English README: [README.en.md](https://github.com/Alexios1897/vscode-Android/blob/main/README.en.md)
