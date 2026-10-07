package com.webscreen.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Optionally starts the webscreen server right after boot. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }
        val prefs = Prefs(context)
        if (!prefs.autoStart) {
            Log.i(TAG, "auto start disabled, nothing to do")
            return
        }
        try {
            WebScreenService.start(context)
        } catch (e: Exception) {
            Log.w(TAG, "could not start service on boot: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "webscreen-boot"
    }
}
