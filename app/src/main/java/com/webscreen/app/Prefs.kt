package com.webscreen.app

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("webscreen", Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt("port", DEFAULT_PORT)
        set(value) = sp.edit().putInt("port", value).apply()

    var pin: String
        get() = sp.getString("pin", "") ?: ""
        set(value) = sp.edit().putString("pin", value).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", false)
        set(value) = sp.edit().putBoolean("auto_start", value).apply()

    companion object {
        const val DEFAULT_PORT = 8079
    }
}
