# WebScreen 在飞牛 OS（fnOS）上的部署

把 **飞牛 NAS 当作 WebScreen 服务端**：NAS 通过 **USB 有线 ADB** 或 **Wi-Fi ADB** 连接多台
Android 设备，浏览器打开 NAS 上的 Web UI 即可投屏与操作（视频、音频、鼠标、键盘、触摸、
剪贴板）。不需要在电脑上运行 webscreen，也不需要 Termux、Docker 或手动敲 adb 命令。

```
Android ──USB──┐
               ├─► 飞牛 NAS（webscreen + adb） ──► 浏览器 / 飞牛面板
Android ──WiFi─┘
```

包结构、生命周期脚本与构建方式遵循飞牛原生 `.fpk` 规范（见 `fnpack/`）。

---

## 1. 安装

**方式一：应用中心界面（推荐）**

应用中心 → 手动安装 → 选择 `webscreen_<version>.fpk` → 向导里选择运行权限：

- **超级管理员 (Root)（推荐）**：USB 直连需要访问 `/dev/bus/usb`，必须 root
- 普通用户 (Package)：只支持 Wi-Fi ADB

**方式二：命令行**（需要向导参数，否则会被拒绝）

```bash
trim-cli app install-fpk --remote-path /vol1/1000/workspace/webscreen-1.1.0.fpk \
  --volume-id 1 \
  --custom-parameters '[{"key":"wizard_webscreen_run_user","value":"root"}]' --yes
```

## 2. 升级

实测（`trim-cli` + App Center 后端）：**`app install-fpk` 对已存在的 appname 一律拒绝**，
即使 fpk 内版本号更高：

```
Error: app-center request POST /app-center/v1/install/task failed: {"code":10236}   # 应用已存在
```

因此升级走以下任一方式：

1. **应用中心界面**里对已安装的 WebScreen 选择新版 fpk（界面会走更新流程）；
2. 先卸载（向导中选 **保留数据**），再安装新版。

设备自定义名称、无线地址等数据存放在 `$TRIM_PKGVAR`，**升级/重装不会丢失**。

## 3. 运行、停止与端口

| 项目 | 说明 |
| --- | --- |
| 控制台 | 桌面入口「WebScreen 控制台」→ `http://<NAS>:8079/console`（已验证可经飞牛远程访问，无需额外反代） |
| 默认端口 | **8079**（可用应用设置项 `webscreen_port` 覆盖；改后 `config_callback` 会自动重启应用生效） |
| 启动 | 应用中心「启动」，或 `cmd/main start`：先确保 adb server 在跑，再以自身 bin 目录为工作目录启动 webscreen |
| 停止 | 应用中心「停止」，或 `cmd/main stop`：SIGTERM → 兜底 SIGKILL，清理 webscreen 与残留 `scrcpy.Server`；**仅当 adb server 是本应用启动的**（`$TRIM_PKGVAR/adb-started-by-app` 标记）才关闭它，避免影响 NAS 上其它使用 adb 的程序 |
| 状态 | `cmd/main status`：运行返回 0，停止返回 3 |

## 4. 设备管理

统一由 `webservice/android/adbmanager.go`（ADB Manager）管理，USB 与 Wi-Fi 用同一套逻辑：

- **USB 自动发现**：插入数据线即被发现（adb server 枚举 `/dev/bus/usb`），无需填写 IP
- **设备标识**：以 **ADB serial** 为内部 ID；显示名优先用用户自定义名称，否则用自动识别的
  「厂商 + 型号」（如 `OnePlus GM1911`）
- **连接类型**：区分 `USB` / `WiFi`（来自 `adb devices -l` 的 `usb:` transport 与 `ip:port` 形态）
- **USB 优先**：同一台手机同时通过 USB 与 Wi-Fi 在线时，按**硬件序列号归并为一台设备**，
  优先使用 USB，无线地址保留为回退目标（USB 掉线后可用它恢复）
- **状态机**：`ONLINE` / `UNAUTHORIZED` / `OFFLINE` / `CONNECTING` / `DISCONNECTED` / `ERROR`
- **热插拔**：2 秒轮询；拔出 → `已断开`，插回 → 自动恢复；**一台设备异常不影响其它设备**
- **事件日志**：控制台底部「设备事件」（检测到设备 / 等待授权 / 已连接 / 已断开 / 重连），
  同时写入服务日志
- **改名 / 断开 / 重连**：设备卡片上的按钮；`UNAUTHORIZED` 的卡片会禁用投屏按钮并提示
  「请查看手机屏幕，点击『允许 USB 调试』」

### 无线接入

- 「无线配对」：输入手机的无线调试配对码（`adb pair`）
- 「连接设备」：输入 `ip:端口`（`adb connect`）
- 「连接设备」弹窗内还有 **USB 设备列表** 与 **「刷新并请求授权」** 按钮（见下节）

### RSA 授权（USB）

授权对话框**由手机侧弹出**：本应用启动时执行 `adb start-server`，我们的 adb 守护进程与手机的
`adbd` 握手；手机发现这把主机公钥未被授权 → 由 `adbd`/系统弹出「允许 USB 调试吗？」。
ADB 协议里没有「应用直接请求弹窗」的动作，**唯一触发方式就是重新发起一次未授权握手**，
这也正是「连接设备 → 刷新并请求授权」（`POST /api/adb/usb/rescan`，先 `adb reconnect device`，
兜底重启 adb server）的实现原理。

## 5. USB 权限如何处理

- 应用以 **root 运行**（`fnpack/config/privilege`），因此可直接访问 `/dev/bus/usb`——
  **不需要用户手动 `chmod` 设备节点**，也没有引入 udev 规则（避免改动系统配置）
- `cmd/install_callback` 会检测 `/dev/bus/usb` 是否存在并写入 `info.log`，缺失时提示只能走 Wi-Fi
- 随包提供 `adb`（来自 Android platform-tools），由 `cmd/main` 在启动时执行 `adb start-server`
- webscreen 通过 `utils.GetADBPath()` 依次查找 `./adb` → `PATH` → 下载，因此包内 adb 与
  webscreen 放在同一目录、并把该目录设为工作目录即可命中（无需改动上游代码）

## 6. 数据与日志

| 路径 | 内容 |
| --- | --- |
| `$TRIM_PKGVAR/devices.json` | 设备注册表：自定义名称、无线地址（升级保留） |
| `$TRIM_PKGVAR/info.log` | 应用生命周期日志（安装/启动/停止/异常） |
| `$TRIM_PKGVAR/webscreen.log` | webscreen 服务输出（Gin 路由、scrcpy、WebRTC） |
| `$TRIM_PKGVAR/app.pid` | 主进程 PID |
| `$TRIM_PKGVAR/adb-started-by-app` | 停止时是否需要关闭 adb server 的标记 |

## 7. 从源码构建

```bash
# 本机（NAS）构建：需要 Go 工具链
bash scripts/build-fpk.sh
# 产物：fnpack/webscreen.fpk
```

脚本做四件事：

1. `make ci` 编译 webscreen（linux/amd64，**必须先构建内嵌用的 `sdriver/linux/bin/recorder`**，
   该二进制被 `.gitignore` 忽略，直接 `go build` 会报 `pattern bin/recorder: no matching files found`）
2. 下载 Android platform-tools 并取出 `adb`（**仓库不提交该二进制**）
3. `scripts/make-fpk-icons.py` 生成 64/256 图标（上游只提供 SVG）
4. `fnpack-1.2.3-linux-amd64 build`

云编译：`.github/workflows/build-fpk.yml`（`push` / `pull_request` / `workflow_dispatch`），
产物为 artifact `WebScreen-fpk`。

**开发热更**（NAS 上无 root 时的快速迭代，正式交付仍走 fpk）：

```bash
# 1. 本地编译 linux/amd64 二进制
GOOS=linux GOARCH=amd64 make ci DIST_DIR=dist SUFFIX=-linux-amd64
# 2. 上传为名字叫 webscreen 的副本（远端名 = 本地文件名，--overwrite replace 覆盖）
cp dist/webscreen-linux-amd64 /tmp/hotfix/webscreen
trim-cli file upload /vol1/@appcenter/webscreen/bin /tmp/hotfix/webscreen --overwrite replace
# 3. 重启应用（cmd/main 每次启动都会 chmod +x，正好补齐执行位）
trim-cli app restart webscreen --yes
```

## 8. 已知限制

- 仅 **x86_64** NAS（包内二进制为 linux/amd64）
- `H.265` 需要浏览器支持 HEVC（如 Safari）；Chrome/Chromium 的 WebRTC 不支持 H.265，
  服务端会**自动回退到 H.264 并提示**，不会像上游那样直接失败
- 随包 `adb` 来自 Google Android platform-tools，许可与归属见 `fnpack/app/bin/NOTICE.txt`
  （构建时下载，仓库不提交）
- 手机端 APK（本机 root 模式）不使用本应用的 ADB Manager，两者互不影响
- 多设备并发投屏的实际上限取决于 NAS 的 CPU/内存与网络（每路 WebRTC 都会持续编码与转发）

## 9. 许可与归属

- webscreen 本体：**AGPL-3.0**（版权归原作者）
- 内嵌 scrcpy-server：Apache-2.0
- WebRTC（pion）等依赖：各自许可
- Android platform-tools 的 `adb`：Google 的 Android SDK 许可（随包内容见 NOTICE.txt）
