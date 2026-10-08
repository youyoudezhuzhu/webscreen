# Webscreen

[English](README.md) | [日本語](README.ja.md)

基于 WebRTC 的自托管屏幕串流：在浏览器里查看并操控 Android 手机画面。Android 侧基于 [scrcpy](https://github.com/Genymobile/scrcpy)。

![screenshot](doc/assets/screenshot.png)

---

## ⚠️ 该下载哪个文件？（二选一，**不是两个都装**）

本仓库提供两个**互斥**的安装包 —— 它们是同一套服务的两种部署方式，**只需安装其中一个**。

| 你的情况 | 下载这个 | 装在哪里 | 手机端需要做什么 |
|---|---|---|---|
| 有**飞牛 NAS（fnOS）**，想让 NAS 管理手机 | `webscreen_<版本>.fpk` | **只装 NAS** | **不用装任何软件**，只需打开 USB 调试或无线调试并授权 |
| 手机已 **root**，没有 NAS / 不想用 NAS | `WebScreen-<版本>.apk` | **只装手机** | 不用 NAS、不用 adb、不用 Termux、不用电脑 |

### 方案 A：飞牛 NAS —— 安装 `.fpk`

NAS 装上后就是 WebScreen 服务端，通过 **USB 有线 ADB** 或 **Wi-Fi ADB** 连接手机。

- 手机端**不需要安装任何 App**，只需在开发者选项里打开 USB 调试（或无线调试）并允许授权
- 局域网内任意浏览器访问 `http://<NAS 的 IP>:8079`，进入控制台管理设备
- 支持多台设备同时连接、USB 热插拔、设备自定义命名
- 安装 / 升级 / 端口 / 已知限制：[`doc/fnos-fpk.md`](doc/fnos-fpk.md)

### 方案 B：已 root 的 Android 手机 —— 安装 `.apk`

手机自己既是服务端也是被控端，**完全不依赖电脑和 NAS**。

```
已 root 手机 → 安装 APK → 授予 root → 点「启动」
             → 局域网浏览器访问 http://<手机 IP>:8079
```

- 要求：arm64、Android 8+、已 root
- 完整说明（含功能限制）：[`doc/android-apk.md`](doc/android-apk.md)

> **不要两个都装。** 两种方案得到的是同一个东西：一个在浏览器里操作手机画面的页面。

---

## 功能

Android（基于 [scrcpy](https://github.com/Genymobile/scrcpy)）：

- 视频 / 音频 / 控制
- UHID 虚拟设备：鼠标、键盘、手柄
- 剪贴板同步
- 多指触控（带压力感应）
- H.264 / H.265
- 多路连接
- **受控端黑屏**：屏幕熄灭但**不锁屏**，串流与触控照常 —— 用于隐私场景

Linux（Xvfb / Xorg / Sway）：视频、控制、触控、H.264/H.265、GPU（Xorg/Sway）

## 其他平台（上游用法）

同一份代码也可运行在 Termux、Linux、Windows、macOS（amd64 / arm64），或使用 Docker：

```bash
wget https://raw.githubusercontent.com/youyoudezhuzhu/webscreen/refs/heads/main/docker-compose.yml
docker compose up -d
```

默认端口 `8079`，可用 `-port 8080` 指定；支持 6 位 PIN（`-pin 555555`，默认为空）。
客户端需要支持 WebRTC（H.264 High Profile 或 H.265 Main Profile）的浏览器。

## 常见问题

- **自定义 Android 设备上 MediaCodec 0x80001001 崩溃**（硬编码 H.264 High Profile 所致）：把 `video_codec_options` 设为 `profile=1`

## 许可证

AGPL-3.0 —— 见 [LICENSE](LICENSE)。
