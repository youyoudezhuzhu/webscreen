# Webscreen

[简体中文](README.zh.md) | [日本語](README.ja.md)

Self-hosted screen streaming in the browser: view and control an Android phone's screen from a web page. Android support is based on [scrcpy](https://github.com/Genymobile/scrcpy).

![screenshot](doc/assets/screenshot.png)

---

| Your setup | Download | Install on | What the phone needs |
|---|---|---|---|
| **fnOS (飞牛) NAS** as the server | `webscreen_<version>.fpk` | **NAS only** | **Nothing to install** — just enable USB / wireless debugging and authorize |
| **Rooted Android phone**, no NAS | `WebScreen-<version>.apk` | **Phone only** | No NAS, no adb, no Termux, no PC |

### Option A: fnOS NAS — install the `.fpk`

The NAS becomes the webscreen server and drives the phone over **USB ADB** or **Wi-Fi ADB**.

- The phone side needs **no app at all** — just enable USB debugging (or wireless debugging) in Developer Options and authorize the connection
- Open `http://<NAS-IP>:8079` in any browser on your LAN to manage devices
- Multiple devices, USB hot-plug, per-device custom names
- Install / upgrade / ports / known limitations: [`doc/fnos-fpk.md`](doc/fnos-fpk.md)

### Option B: Rooted Android phone — install the `.apk`

The phone runs webscreen on itself and streams its own screen. **No PC and no NAS involved.**

```
rooted phone → install APK → grant root → tap Start
             → open http://<phone-IP>:8079 in a browser on the same LAN
```

- Requires: arm64, Android 8+, root
- Full instructions (and limitations): [`doc/android-apk.md`](doc/android-apk.md)

> **No need to install both files** — both options give you the same thing: a web page that shows and controls the phone.

---

## Features

Android (via [scrcpy](https://github.com/Genymobile/scrcpy)):

- Video / audio / control
- UHID virtual devices: mouse, keyboard, gamepad
- Clipboard sync
- Multi-touch with pressure
- H.264 / H.265
- Multiple simultaneous connections
- **Controlled-side blackout**: the screen goes dark but **stays unlocked** — streaming and touch keep working (for privacy)

Linux (Xvfb / Xorg / Sway): video, control, touch, H.264/H.265, GPU (Xorg/Sway)

## Other platforms (upstream usage)

The same code also runs on Termux, Linux, Windows and macOS (amd64 / arm64), or via Docker:

```bash
wget https://raw.githubusercontent.com/youyoudezhuzhu/webscreen/refs/heads/main/docker-compose.yml
docker compose up -d
```

Default port is `8079` (`-port 8080` to change), optional 6-digit PIN (`-pin 555555`, empty by default).
The client needs a browser supporting WebRTC (H.264 High Profile, or H.265 Main Profile).

## FAQ

- **MediaCodec 0x80001001 crash on custom Android devices** (hardcoded H.264 High Profile): set `video_codec_options` to `profile=1`

## License

AGPL-3.0 — see [LICENSE](LICENSE).
