package com.webscreen.app

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Small helpers to talk to the su binary.
 *
 * webscreen itself needs root on the device: it runs `app_process` to start the
 * scrcpy server and reads the display from there.
 */
object RootShell {

    const val WORK_DIR = "/data/local/tmp/webscreen"
    const val REMOTE_BINARY = "$WORK_DIR/webscreen"

    data class Result(val exitCode: Int, val output: String, val timedOut: Boolean) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
    }

    /** True when `su -c id` reports uid=0(root). */
    fun hasRoot(): Boolean {
        val result = run(listOf("id"), timeoutSeconds = 20)
        return result.ok && result.output.contains("uid=0")
    }

    fun run(command: List<String>, timeoutSeconds: Long = 60): Result {
        return try {
            val process = ProcessBuilder(listOf("su", "-c", command.joinToString(" ")))
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText()
            if (!finished) {
                process.destroyForcibly()
                return Result(-1, output, true)
            }
            Result(process.exitValue(), output, false)
        } catch (e: Exception) {
            Result(-1, e.message ?: "failed to run su", false)
        }
    }

    /** File name of the webscreen binary shipped inside the apk. */
    fun packagedBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libwebscreen.so")
}
