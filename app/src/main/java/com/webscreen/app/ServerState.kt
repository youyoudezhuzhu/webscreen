package com.webscreen.app

import android.os.Build

/**
 * Shared, in-process state of the webscreen server, read by the activity and
 * written by the service.
 */
object ServerState {

    const val MAX_LOG_LINES = 500

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var startTime: Long = 0L

    @Volatile
    var exitCode: Int? = null

    @Volatile
    var lastError: String? = null

    private val logLines = ArrayDeque<String>()
    private val logLock = Any()

    fun setRunning(value: Boolean, pid: Long = 0L) {
        running = value
        if (value) {
            startTime = System.currentTimeMillis()
            exitCode = null
            lastError = null
        } else {
            startTime = 0L
        }
    }

    fun setExited(code: Int) {
        exitCode = code
        running = false
        startTime = 0L
    }

    fun append(line: String) {
        synchronized(logLock) {
            if (logLines.size >= MAX_LOG_LINES) {
                logLines.removeFirst()
            }
            logLines.addLast(line)
        }
    }

    fun snapshot(): List<String> = synchronized(logLock) { logLines.toList() }

    fun clearLog() = synchronized(logLock) { logLines.clear() }

    /** PID of a running webscreen process, found through /proc. */
    fun runningPid(): Int {
        var best = 0
        val proc = java.io.File("/proc")
        val entries = proc.list() ?: return 0
        for (entry in entries) {
            val pid = entry.toIntOrNull() ?: continue
            val cmdline = try {
                java.io.File("/proc/$entry/cmdline").readText()
            } catch (e: Exception) {
                continue
            }
            if (cmdline.contains("webscreen/webscreen")) {
                if (pid > best) best = pid
            }
        }
        return best
    }

    fun deviceSummary(): String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
}
