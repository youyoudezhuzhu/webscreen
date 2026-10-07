# Android APK（Root 手机直接运行 WebScreen）

这个 fork 在**不改变 webscreen 原有实现**的前提下，增加了把它打包成 APK、
在**已 Root 的 Android 手机上直接运行 webscreen 服务**的能力。

原来的用法是「PC 上跑 webscreen + 用 adb 驱动一台 Android 设备」。现在多了一种用法：

```
已 Root 的 Android 手机
        │  安装 WebScreen.apk
        ▼
打开 App → 授予 Root → 点「启动 WebScreen」
        │
        ▼
手机自己运行 webscreen（监听 0.0.0.0:8079）
        │
        ▼
同一局域网里 PC 浏览器访问 http://手机IP:8079
```

不需要 Termux、不需要 adb、不需要在 PC 上装任何东西。

## 原理（为什么不用 adb 也能跑）

webscreen 的投屏核心是 scrcpy-server：它是一个 Java jar，由 `app_process` 在设备上
拉起，再通过 socket 把 H.264/H.265、Opus、控制通道交给 webscreen。

- **原来（主机模式）**：`adb push` jar → `adb reverse` 建隧道 → `adb shell app_process ...`
  启动 server，webscreen 在本机监听 TCP 端口接收 3 条连接。
- **现在（本机模式，`-local-root`）**：webscreen 自己在手机上以 root 运行，于是

  1. 把内嵌的 scrcpy-server 写到 `/data/local/tmp/webscreen/scrcpy-server`；
  2. 用 `/system/bin/sh -c "CLASSPATH=... app_process64 / com.genymobile.scrcpy.Server ..."`
     在本机启动 server，并加 `tunnel_forward=true`（让 server 自己创建 abstract unix socket）
     与 `send_dummy_byte=false`（保持与主机模式一致的流格式）；
  3. webscreen 直接连 `@scrcpy_<scid>` abstract socket（视频/音频/控制各一条）。

  其余全部复用原实现：WebRTC、音频、鼠标、键盘、触摸、剪贴板、UHID、PIN 认证、
  Web UI 一个字都没改。

设备列表里会多出一台「本机」设备（用 `ro.product.model` 命名），选中它即可开始投屏。

## 构建 APK

推荐用 GitHub Actions 云端构建（不需要本地装 Android SDK）：

```
仓库 → Actions → Build APK → Run workflow
```

跑完之后在 **Actions → 该次运行 → Artifacts → `WebScreen-debug`** 下载
`WebScreen-debug.apk`。

workflow 文件：`.github/workflows/build.yml`，触发条件 `push` / `pull_request` /
`workflow_dispatch`（手动）。它做了三件事：

1. `GOOS=android GOARCH=arm64 make ci` 构建 webscreen 本机二进制；
2. 把它作为 `app/src/main/jniLibs/arm64-v8a/libwebscreen.so` 放进 APK
   （保留 `lib*.so` 命名是为了让 Android 安装时把它释放到
   `nativeLibraryDir`，并允许 `android:extractNativeLibs=true` 让它以真实文件存在，
   这样才能 `cp` 到 `/data/local/tmp` 再执行）；
3. `./gradlew assembleDebug` 出包并上传 artifact。

本地构建（可选）：

```bash
# 1. 编译 android/arm64 二进制
GOOS=android GOARCH=arm64 CGO_ENABLED=0 make ci DIST_DIR=dist SUFFIX="-android-arm64"
# 2. 放进 jniLibs
mkdir -p app/src/main/jniLibs/arm64-v8a
cp dist/webscreen-android-arm64 app/src/main/jniLibs/arm64-v8a/libwebscreen.so
# 3. 出包
./gradlew assembleDebug
```

## 安装 / 使用

1. 安装 `WebScreen-debug.apk`（arm64 设备）。
2. 打开 App，第一次会请求 Root 授权：**在 Magisk / KernelSU / APatch 的弹窗里点「允许」**。
   App 用 `su -c id` 检测，只有拿到 `uid=0(root)` 才会启用「启动 WebScreen」；
   没有 Root（或还没批准）时界面会明确显示「需要 Root 权限才能运行 WebScreen」。
   注意：部分 root 管理器把新应用默认设为「静默拒绝」，此时不会弹窗，
   需要在管理器里手动找到 WebScreen 并允许（允许后 App 会自动检测到，无需重启）。
3. 可选：填 6 位 PIN（对应原项目的 PIN 认证）、打开「开机自动启动」。
4. 点「启动 WebScreen」。看到 `● Running` 后，界面会显示
   `http://手机IP:8079`，点「复制地址」可直接复制。
5. 手机和 PC 连同一个局域网，PC 浏览器打开该地址即可：选设备列表里的「本机」→ 开始投屏。

服务跑在前台 Service 里：关掉 Activity 或关掉浏览器都不会停；屏幕熄灭也能继续
（Android 14+ 使用 `specialUse` 类型的前台服务，Android 13+ 会请求通知权限）。

## 端口

APK 默认使用 **8079**（原程序命令行默认是 8081，APK 启动时显式传 `-port 8079`），
并监听 `0.0.0.0`，局域网内其它设备可以访问。

## 已验证 / 未验证

已在真机验证（OnePlus 7 Pro，Android 16，KernelSU-Next root，arm64）：

- 云端 Actions 构建 APK 成功，产物 `WebScreen-debug.apk`（arm64，含 `lib/arm64-v8a/libwebscreen.so`）；
- APK 可安装、可启动，控制页正常显示 Root 状态 / 端口 / 访问地址 / PIN / 开机自启 / 运行日志；
- `-local-root` 模式下 app_process 成功拉起 scrcpy-server；
- **端到端投屏打通**：手机以 root 运行 webscreen 后，PC 浏览器访问
  `http://手机IP:8079` → 选「本机」设备 → 实时看到手机屏幕（1440×3120 的 H.264，
  `readyState=4` 且帧数持续增长），右侧控制按钮（音量/电源/返回/主页/多任务）齐全；
- 设备列表显示真实机型名（本地 getprop）；编码器下拉来自本机 `media_codecs*.xml`
  （c2.qti.avc.encoder 等），不再依赖 adb。

未验证 / 已知限制：

- 浏览器端 Audio / 鼠标 / 键盘 / 触摸 / 剪贴板 未逐项实测（视频通路已通）；
- 「App 内点启动 → 服务跑起来」这一步在**本机 KernelSU 默认静默拒绝新应用**的策略下
  需要先在管理器里允许本应用（App 侧代码路径与真机 su 调用均已确认可达内核钩子）；
- 仅 arm64-v8a；未做 armeabi-v7a / x86_64；
- 未 Root 设备不支持（也不打算支持）。

## 许可

- webscreen 本体：**AGPL-3.0**（见 `LICENSE`，版权归原作者 Hiroi）。
- scrcpy-server（内嵌，`sdriver/scrcpy/bin/`）：**Apache-2.0**
  （见 `sdriver/scrcpy/bin/LICENSE`）。
- WebRTC 使用 Pion（BSD-3-Clause）等依赖，许可随各自模块。
- APK 只是把上述组件打包在同一进程/同一安装包里，各组件许可与版权声明均原样保留。

`ci/signing/webscreen.p12.b64` 是**仅供测试的固定签名密钥**（口令 `webscreen`），
目的是让连续多次 CI 构建出来的 APK 能互相覆盖安装；它不是发布密钥，请勿用于正式分发。
