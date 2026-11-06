Official **VS Code** packed into an Android APK — proot + Ubuntu rootfs + `code serve-web`. Install it on a tablet or phone, no Termux and no PC required.

> Unofficial project: it only repacks official components that you download yourself. No Microsoft binaries are distributed in this repository.

## Disclaimer

- This is an **unofficial** project. It is not affiliated with, authorized, sponsored or endorsed by Microsoft Corporation.
- This repository contains **no Microsoft binaries or source code**. `scripts/fetch-assets.sh` only lets *you* download components from official endpoints.
- The APK attached to a Release is the author's **personal build**, intended for running on hardware the author owns, and is not offered for redistribution. If you want to use this project, build it yourself with `bash build.sh`.
- **VS Code**, **Visual Studio Code** and their logos are trademarks of Microsoft Corporation. The icon is derived from the VS Code logo for personal builds only and does not imply endorsement.
- **proot** is distributed with the APK and comes from Termux under **GPLv2**; source: <https://github.com/termux/proot>.
- Any compliance responsibility arising from use of this project rests with the user.

## What it can do

- **Real VS Code**: the official `code serve-web` UI, rendered full-screen in a WebView — not a look-alike editor
- **Integrated terminal**: ships Ubuntu 24.04 + bash, so `git`, `node`, `python`, `npm` just work
- **Extension marketplace**: install extensions, themes and language packs; Chinese UI works out of the box
- **GitHub sign-in**: the session is stored on the device and survives app restarts
- **Edit files on your phone**: `/sdcard` is mounted into the Linux side
- **Works offline**: the server runs locally on the device (`127.0.0.1:18080`)
- **Stays alive in background**: a foreground service keeps the server running
- **Data survives upgrades**: extensions, settings and sign-in live in `persist/`

## How it fits together

```
APK (assets)
├── rootfs.tar.gz        Ubuntu 24.04 base (aarch64)
├── vscode.tar.gz        official VS Code server + web client
├── code                 official VS Code CLI (serve-web)
├── proot + loader       proot from Termux
└── ca-certificates.crt  root certs (extension host HTTPS)
        │ unpacked into app-private storage on first launch
        ▼
proot -0 -r rootfs …  code serve-web --host 127.0.0.1 --port 18080
        ▼
full-screen WebView → http://127.0.0.1:18080
```

Three directories under `persist/` are bind-mounted into the container:

| Host | Inside | Contents |
| --- | --- | --- |
| `persist/data` | `/root/data` | user data, installed extensions, settings |
| `persist/cli` | `/root/.vscode` | CLI cache, server build |
| `persist/codex` | `/root/.codex` | optional CLI tool config |

## Requirements

| Item | Requirement |
| --- | --- |
| OS | **Android 8.0 (API 26)+**, `targetSdkVersion=28` |
| CPU | **arm64-v8a (aarch64) only** |
| RAM | **4GB+** recommended, 8GB is comfortable |
| Storage | APK ~266MB, **~1.2GB** after first-launch extraction |
| Root | **Not required** (proot is a userspace container) |
| WebView | system Android System WebView (Chromium) |
| Network | not needed for first launch; needed for extensions / GitHub sign-in |

Not supported: x86/x86_64 emulators, Windows Subsystem for Android, 32-bit ARM devices, Android 7 and below.

## Build

Requires an **aarch64 Linux** host (the aapt2 we use is an ARM build), **JDK 17** and **Node.js 18+**.

```bash
bash scripts/fetch-assets.sh      # downloads VS Code server/CLI, Ubuntu rootfs, proot, arm64 aapt2
bash build.sh                     # produces build/vscode.apk
adb install -r build/vscode.apk
```

`fetch-assets.sh` resolves the latest stable VS Code commit by itself, so bumping the version needs no code change.

- Change the icon: replace `icon-src.webp`, then `node make-icon.js`
- Change localisation: unpack a language pack into `dl/language-pack`, re-run `fetch-assets.sh`

## CI

Pushing a `v*` tag builds the APK on `ubuntu-24.04-arm` and attaches it to the GitHub Release.


## License

Code is MIT licensed, see [LICENSE](LICENSE).

VS Code and Visual Studio Code are trademarks of Microsoft Corporation. This project is not affiliated with Microsoft, and does not contain or redistribute Microsoft binaries — the build scripts fetch them from official endpoints at build time. The icon is derived from the VS Code logo and is intended for personal builds.
