package com.webscreen.app

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Small helpers to talk to the su binary.
 *
 * webscreen itself needs root on the device: it runs `app_process` to start the
 * scrcpy server and reads the display from there.
 *
 * The very first `su` call makes the root manager (Magisk / KernelSU / APatch)
 * ask the user for permission, so a failed check usually just means "not
 * approved yet" and is simply retried.
 */
object RootShell {

    private const val TAG = "webscreen"

    const val WORK_DIR = "/data/local/tmp/webscreen"
    const val REMOTE_BINARY = "$WORK_DIR/webscreen"

    // Absolute paths first: /system/bin is not always part of an app's PATH.
    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/magisk/su",
        "su"
    )

    data class Result(val exitCode: Int, val output: String, val timedOut: Boolean) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
    }

    /** First usable su binary. */
    fun suBinary(): String {
        for (candidate in SU_CANDIDATES) {
            if (!candidate.contains('/')) return candidate
            if (File(candidate).exists()) return candidate
        }
        return "su"
    }

    /** True when `su -c id` reports uid=0(root). */
    fun hasRoot(): Boolean {
        val result = run(listOf("id"), timeoutSeconds = 25)
        val granted = result.ok && result.output.contains("uid=0")
        Log.i(
            TAG,
            "root check: su=${suBinary()} exit=${result.exitCode} timedOut=${result.timedOut} " +
                "granted=$granted out=${result.output.take(200).trim()}"
        )
        return granted
    }

    fun run(command: List<String>, timeoutSeconds: Long = 60): Result {
        val su = suBinary()
        return try {
            val process = ProcessBuilder(listOf(su, "-c", command.joinToString(" ")))
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText()
            if (!finished) {
                process.destroyForcibly()
                Log.w(TAG, "su command timed out: ${command.joinToString(" ")}")
                return Result(-1, output, true)
            }
            Result(process.exitValue(), output, false)
        } catch (e: Exception) {
            Log.w(TAG, "su command failed ($su): ${e.message}")
            Result(-1, e.message ?: "failed to run su", false)
        }
    }

    /** File name of the webscreen binary shipped inside the apk. */
    fun packagedBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libwebscreen.so")
}
