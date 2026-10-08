package com.webscreen.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File

/**
 * Runs the webscreen server (the Go binary shipped as libwebscreen.so) as root
 * in the background, so that closing the activity, closing the browser or
 * turning the screen off does not stop the stream.
 */
class WebScreenService : Service() {

    private var process: Process? = null
    private var monitor: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForegroundCompat()
                stopSelf()
            }
            else -> {
                // Android 15+（SDK 35+）的部分 ROM 会拒绝前台服务：
                // 实测一加 7 Pro（Android 16）上 startForeground 明明成功
                // （日志 "[app] 前台服务已启动（type=2）"），系统仍在 ~96ms 后
                // 以 "has died: prcp FGS" 杀掉整个进程，并伴随
                // "ForegroundServiceTypeLoggerModule: Logger should be tracking
                // FGS types correctly"。也就是说拒绝发生在 ROM 的 FGS 校验层，
                // 与我们声明哪种类型无关 —— 换类型无效。
                //
                // 因此在这些系统上干脆不提升前台服务，只跑普通服务。
                // webscreen 二进制是以 exec 启动的独立进程，服务随后被回收
                // 也不会中断串流；而普通服务不触发 FGS 校验，进程不会被杀。
                if (Build.VERSION.SDK_INT >= 35) {
                    ServerState.append("[app] Android 15+：跳过前台服务提升，改用普通服务（规避 ROM 的 FGS 校验）")
                } else {
                    try {
                        startForegroundCompat()
                    } catch (t: Throwable) {
                        ServerState.append("[app] 前台服务启动异常（已忽略，降级运行）: ${t.javaClass.simpleName}: ${t.message}")
                        Log.e(TAG, "startForegroundCompat failed, degrade to background service", t)
                    }
                }
                try {
                    startServer()
                } catch (t: Throwable) {
                    ServerState.append("[app] 启动 webscreen 异常: ${t.javaClass.simpleName}: ${t.message}")
                    Log.e(TAG, "startServer failed", t)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // The Go process is started with `exec`, so it survives the service only
        // if it was not asked to stop. Nothing to do here beyond bookkeeping.
        ServerState.append("[service] stopped")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ server

    private fun startServer(): Unit {
        if (process != null && ServerState.running) {
            ServerState.append("[app] server already running")
            return
        }

        val prefs = Prefs(this)
        val binary = RootShell.packagedBinary(this)
        if (!binary.isFile) {
            fail("apk 中缺少 webscreen 二进制 (${binary.absolutePath})")
            return
        }

        val port = prefs.port

        // 注意：这里**不要**用 `pkill -f` 清理残留进程。
        // 这些命令本身是通过 `su -c` 执行的，命令行里字面包含 "webscreen"，
        // 加上 KernelSU 的 su→sh 包装，pkill 会匹配到自己所在的进程组，
        // 发出 SIGTERM 把 App 进程一起带走（真机实测：
        // "Process <pid> exited due to signal 15 (Terminated)" 紧跟 su 执行之后），
        // 表现就是"点启动就闪退"。
        // 残留实例改由下面的端口占用检查提示用户，不再自动 pkill。
        val portState = RootShell.run(
            listOf("sh", "-c", "\"ss -ltn 2>/dev/null | grep -q ':$port ' && echo busy || echo free\""),
            timeoutSeconds = 15
        )
        if (portState.output.contains("busy")) {
            ServerState.append("[app] 端口 $port 仍被占用，启动可能失败（请先停止其它 webscreen 实例）")
        }

        val remote = RootShell.REMOTE_BINARY
        val args = StringBuilder()
        args.append("'").append(remote).append("'")
        args.append(" -host 0.0.0.0")
        args.append(" -port ").append(port)
        args.append(" -local-root")
        if (prefs.pin.isNotEmpty()) {
            args.append(" -pin ").append(prefs.pin)
        }

        val command = listOf(
            "mkdir", "-p", RootShell.WORK_DIR, "&&",
            "cp", "-f", "'${binary.absolutePath}'", "'$remote'", "&&",
            "chmod", "755", "'$remote'", "&&",
            "exec", args.toString()
        ).joinToString(" ")

        ServerState.append("[app] starting webscreen on port $port")
        ServerState.setRunning(true)

        try {
            val builder = ProcessBuilder(listOf("su", "-c", command))
            builder.redirectErrorStream(true)
            val started = builder.start()
            process = started

            // Read whatever the server prints (logs + scrcpy output).
            monitor = Thread {
                try {
                    started.inputStream.bufferedReader().forEachLine { line ->
                        ServerState.append(line)
                    }
                } catch (e: Exception) {
                    ServerState.append("[app] log reader stopped: ${e.message}")
                }
                val code = try {
                    started.waitFor()
                } catch (e: InterruptedException) {
                    -1
                }
                process = null
                if (ServerState.running) {
                    ServerState.setExited(code)
                    ServerState.append("[app] webscreen exited with code $code")
                }
            }
            monitor?.isDaemon = true
            monitor?.start()

            updateNotification()
        } catch (e: Exception) {
            fail("启动失败: ${e.message}")
        }
    }

    private fun stopServer() {
        val running = process
        if (running != null) {
            running.destroy()
        }
        // The binary runs through `exec`, so killing our child stops the server.
        // Its scrcpy server is a grandchild and must be killed too, otherwise it
        // keeps holding the abstract socket and the next session cannot start.
        // Both patterns use the [x] trick so that pkill does not match the
        // shell that is running it.
        RootShell.run(
            listOf(
                "pkill", "-f", "[c]om.genymobile.scrcpy.Server", ";",
                "pkill", "-f", "[w]ebscreen/webscreen"
            ),
            timeoutSeconds = 20
        )
        ServerState.setRunning(false)
        ServerState.append("[app] webscreen stopped")
        process = null
    }

    private fun fail(message: String) {
        ServerState.lastError = message
        ServerState.append("[app] $message")
        ServerState.setRunning(false)
    }

    // ------------------------------------------------------------ notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.notification_channel_desc)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val port = Prefs(this).port
        val address = NetUtils.addressFor(port)

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WebScreenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running, address))
            .setSmallIcon(R.drawable.ic_stat_webscreen)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.action_stop),
                    stopIntent
                ).build()
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun startForegroundCompat() {
        val notification = try {
            buildNotification()
        } catch (t: Throwable) {
            Log.e(TAG, "buildNotification failed", t)
            ServerState.append("[app] 构建通知失败: ${t.javaClass.simpleName}: ${t.message}")
            return
        }

        // Android 14+ 必须声明 foregroundServiceType；而 specialUse 在侧载应用上
        // 会因缺少 Play 审核被 AppOps 直接拒绝（真机 Android 16 + targetSdk 34 实测：
        // "AppOps: Operation not started ... op=START_FOREGROUND" → 进程被杀、反复重启）。
        // 因此按"最可能被接受"的顺序依次尝试，任何一个成功即返回；
        // 全部失败也不抛异常（否则就是用户看到的"点启动就闪退"），
        // 降级为普通服务继续运行，并把真实原因写进界面日志。
        val candidates = ArrayList<Int>()
        if (Build.VERSION.SDK_INT >= 34) {
            candidates.add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            candidates.add(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        candidates.add(-1) // -1 = 不指定类型（老系统路径）

        var lastError: Exception? = null
        for (type in candidates) {
            try {
                if (type < 0) {
                    startForeground(NOTIFICATION_ID, notification)
                } else {
                    startForeground(NOTIFICATION_ID, notification, type)
                }
                if (type < 0) {
                    ServerState.append("[app] 前台服务已启动（未指定类型）")
                } else {
                    ServerState.append("[app] 前台服务已启动（type=$type）")
                }
                return
            } catch (e: Exception) {
                lastError = e
                ServerState.append("[app] 前台服务类型 $type 被拒绝: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // 全部失败：不抛异常，避免进程被系统判定为崩溃后反复重启
        ServerState.append("[app] 警告：所有前台服务类型都被拒绝，降级为普通服务运行（后台可能被系统回收）")
        if (lastError != null) {
            ServerState.append("[app] 最后错误: ${lastError.javaClass.simpleName}: ${lastError.message}")
        }
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val TAG = "WebScreen"
        const val ACTION_START = "com.webscreen.app.START"
        const val ACTION_STOP = "com.webscreen.app.STOP"
        private const val CHANNEL_ID = "webscreen"
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            val intent = Intent(context, WebScreenService::class.java).setAction(ACTION_START)
            // 优先用普通 startService：
            // Android 15+ 的部分 ROM 即使 startForeground 成功也会杀掉 FGS 进程
            // （见 onStartCommand 的说明），而 startForegroundService 还额外要求
            // 5 秒内完成 startForeground，失败即杀。普通服务两者都不触发。
            // 用户是在前台点按钮，因此 startService 不会被后台限制拒绝。
            try {
                context.startService(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "startService 失败，退回 startForegroundService", e)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "两种方式都无法启动服务", e2)
                ServerState.append("[app] 启动服务失败: ${e2.javaClass.simpleName}: ${e2.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WebScreenService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        /** True when a webscreen process is alive (checked through su). */
        fun detectRunning(): Boolean {
            val result = RootShell.run(listOf("pgrep", "-f", "[w]ebscreen/webscreen"), timeoutSeconds = 20)
            if (!result.ok) return false
            val pid = result.output.trim().lines().firstOrNull()?.trim().orEmpty()
            return pid.isNotEmpty() && pid.all { it.isDigit() }
        }
    }
}

private fun File.isFile() = this.exists() && this.isFile
