#!/bin/bash
# 构建 fnOS .fpk 包：编译 webscreen（linux/amd64）→ 取 adb → 生成图标 → fnpack 打包
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

VERSION="$(cat fnpack/app/.version)"
PLATFORM_TOOLS_URL="https://dl.google.com/android/repository/platform-tools-latest-linux.zip"
BIN_DIR="fnpack/app/bin"

echo "== [1/5] 编译 webscreen (linux/amd64) =="
mkdir -p "${BIN_DIR}"
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o "${BIN_DIR}/webscreen" .
file "${BIN_DIR}/webscreen"

echo "== [2/5] 准备 adb（Android platform-tools）=="
# adb 属于 Android platform-tools（Google），不提交进仓库，构建时下载后打进包内。
if [ ! -x "${BIN_DIR}/adb" ]; then
    tmp="$(mktemp -d)"
    curl -fsSL -o "${tmp}/platform-tools.zip" "${PLATFORM_TOOLS_URL}"
    python3 - "${tmp}/platform-tools.zip" "${BIN_DIR}" <<'PY'
import sys, zipfile, os, stat
zip_path, out_dir = sys.argv[1], sys.argv[2]
os.makedirs(out_dir, exist_ok=True)
with zipfile.ZipFile(zip_path) as z:
    for name in ("platform-tools/adb", "platform-tools/NOTICE.txt"):
        info = z.getinfo(name)
        target = os.path.join(out_dir, os.path.basename(name))
        with z.open(info) as src, open(target, "wb") as dst:
            dst.write(src.read())
        if name.endswith("adb"):
            os.chmod(target, 0o755)
        print("extracted:", target)
PY
    rm -rf "${tmp}"
fi
# 注意：不要用 `adb version | head` —— 在 set -o pipefail 下 adb 会因 SIGPIPE 让整条流水线失败
adb_info="$("${BIN_DIR}/adb" version 2>&1 || true)"
printf '%s\n' "${adb_info}" | sed -n '1,2p'

echo "== [3/5] 生成应用图标 =="
python3 scripts/make-fpk-icons.py fnpack

echo "== [4/5] 权限修正 =="
chmod +x fnpack/cmd/* fnpack/fnpack-1.2.3-linux-amd64 "${BIN_DIR}/adb"

echo "== [5/5] fnpack 打包 =="
cd fnpack
./fnpack-1.2.3-linux-amd64 build
cd "${ROOT}"
ls -la fnpack/*.fpk 2>/dev/null || { echo "未生成 .fpk，请检查 fnpack 输出"; exit 1; }
echo "构建完成：版本 ${VERSION}"
