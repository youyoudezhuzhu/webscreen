package webservice

import (
	"context"
	"encoding/base64"
	"fmt"
	"log"
	"net/http"
	"os/exec"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"webscreen/utils"
)

// 受控端背光控制（隐私保护：屏幕黑、但不锁屏、串流与触控照常）。
//
// 为什么不按电源键：KEYCODE_POWER 会让设备熄屏并**锁定**，恢复时需要解锁，
// 与"串流仍可操作"的目标冲突。这里只把 backlight 的 brightness 写 0：
// 面板停止发光，设备保持唤醒状态，触摸/按键/串流全部正常。
//
// 关闭前会记录每个背光节点的原值与 max，恢复到文件里；恢复优先写回原值，
// 原值不可用时退回 max_brightness。
const backlightOffScript = `
set -e
prev=/data/local/tmp/webscreen_backlight_prev
: > "$prev"
for d in /sys/class/backlight/*/; do
  f="${d}brightness"
  [ -w "$f" ] || continue
  cur=$(cat "$f" 2>/dev/null || echo 0)
  max=$(cat "${d}max_brightness" 2>/dev/null || echo 0)
  [ "$cur" -gt 0 ] 2>/dev/null || continue
  echo "$d $cur $max" >> "$prev"
  echo 0 > "$f"
done
echo WEBSREEN_BACKLIGHT_OK
`

const backlightOnScript = `
set -e
prev=/data/local/tmp/webscreen_backlight_prev
if [ -s "$prev" ]; then
  while read -r d cur max; do
    f="${d}brightness"
    [ -w "$f" ] || continue
    v=$cur
    [ "$v" -gt 0 ] 2>/dev/null || v=$max
    [ "$v" -gt 0 ] 2>/dev/null || v=255
    echo "$v" > "$f"
  done < "$prev"
  : > "$prev"
else
  for d in /sys/class/backlight/*/; do
    f="${d}brightness"
    [ -w "$f" ] || continue
    max=$(cat "${d}max_brightness" 2>/dev/null || echo 0)
    [ "$max" -gt 0 ] 2>/dev/null && echo "$max" > "$f"
  done
fi
echo WEBSREEN_BACKLIGHT_OK
`

type backlightRequest struct {
	Serial string `json:"serial"`
	Off    *bool  `json:"off"`
}

// runDeviceShell 在受控设备上以 root 执行一段 shell：
//   - serial 非空 → 通过 adb（NAS 托管模式，webscreen 跑在 NAS 上）
//   - serial 为空 → 直接在本机执行（APK 内嵌模式，服务就跑在手机里）
func runDeviceShell(serial, script string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	// 脚本用 base64 传输：`adb shell su -c "<script>"` 会把参数按空格重新拼接，
	// 多行脚本的换行与引号会被破坏（真机实测：命令返回 0 但背光没变），
	// 因此统一编码后由远端 shell 解码执行。
	encoded := base64.StdEncoding.EncodeToString([]byte(script))
	wrapped := fmt.Sprintf("echo %s | base64 -d | sh", encoded)

	if strings.TrimSpace(serial) == "" {
		// 本机（APK 内嵌模式）：exec 直接传参，不经 shell 拼接，无需引号
		cmd := exec.CommandContext(ctx, "/system/bin/su", "-c", wrapped)
		out, err := cmd.CombinedOutput()
		return string(out), err
	}

	adbPath, err := utils.GetADBPath()
	if err != nil {
		return "", err
	}
	// 关键：`adb shell` 会把它收到的参数用空格拼成一条远程命令。
	// 若直接传 "su", "-c", wrapped，远程实际执行的是
	//   su -c echo <b64> | base64 -d | sh
	// —— 管道被外层 shell 截获，su 只拿到 "echo"，脚本在本地乱跑，
	// 远端背光不变而退出码仍为 0（真机实测：API 返回 success 但 brightness 没变）。
	// 因此必须把 "su -c '<script>'" 作为**单个参数**交给 adb。
	quoted := "'" + strings.ReplaceAll(wrapped, "'", `'\''`) + "'"
	cmd := exec.CommandContext(ctx, adbPath, "-s", serial, "shell", "su -c "+quoted)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

func (wm *WebMaster) handleBacklight(c *gin.Context) {
	var req backlightRequest
	if err := c.BindJSON(&req); err != nil || req.Off == nil {
		c.JSON(http.StatusBadRequest, gin.H{"result": "error", "message": "invalid request"})
		return
	}

	script := backlightOnScript
	if *req.Off {
		script = backlightOffScript
	}

	out, err := runDeviceShell(req.Serial, script)
	trimmed := strings.TrimSpace(out)
	if err != nil || !strings.Contains(trimmed, "WEBSREEN_BACKLIGHT_OK") {
		msg := trimmed
		if err != nil {
			msg = trimmed + " " + err.Error()
		}
		log.Printf("[backlight] failed serial=%q off=%v: %s", req.Serial, *req.Off, msg)
		c.JSON(http.StatusInternalServerError, gin.H{"result": "error", "message": msg})
		return
	}
	log.Printf("[backlight] serial=%q off=%v ok", req.Serial, *req.Off)
	c.JSON(http.StatusOK, gin.H{"result": "success", "off": *req.Off})
}
