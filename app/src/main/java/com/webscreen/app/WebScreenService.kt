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
                startForegroundCompat()
                startServer()
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
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
        const val ACTION_START = "com.webscreen.app.START"
        const val ACTION_STOP = "com.webscreen.app.STOP"
        private const val CHANNEL_ID = "webscreen"
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            val intent = Intent(context, WebScreenService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
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
