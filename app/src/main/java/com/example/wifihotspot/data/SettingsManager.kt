package com.example.wifihotspot.data

import android.content.Context

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("hotspot_prefs", 0)

    companion object {
        const val KEY_TARGET_SSID = "target_ssid"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_MIN_SIGNAL = "min_signal"
    }

    var targetSsid: String
        get() = prefs.getString(KEY_TARGET_SSID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TARGET_SSID, value).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var minSignal: Int
        get() = prefs.getInt(KEY_MIN_SIGNAL, -80)
        set(value) = prefs.edit().putInt(KEY_MIN_SIGNAL, value).apply()
}
