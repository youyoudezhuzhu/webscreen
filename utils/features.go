package utils

import (
	"os"
	"strings"
)

// HideLocalDesktop reports whether the upstream "Linux Desktop" device entry
// should be hidden. It is hidden by default; set webscreen_hide_local_desktop
// to 0/false/no/off to get the upstream behaviour back.
//
// webscreen ships a driver that mirrors the desktop of the machine it runs on
// (sdriver/linux, wayland/xorg/xvfb). That makes sense on a PC, but both
// deployments of this fork (the fnOS package on a headless NAS and the Android
// apk running on the device itself) have no desktop to capture, so the entry can
// only confuse users - clicking stream fails with
//
//	failed to connect to recorder after 5 seconds: dial tcp 127.0.0.1:27184
func HideLocalDesktop() bool {
	switch strings.ToLower(strings.TrimSpace(os.Getenv("webscreen_hide_local_desktop"))) {
	case "0", "false", "no", "off":
		return false
	}
	return true
}
