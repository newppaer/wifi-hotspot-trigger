package com.example.wifihotspot.data

import android.content.Context

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("hotspot_prefs", 0)

    companion object {
        const val KEY_TARGET_SSID = "target_ssid"
        const val KEY_TARGET_BLUETOOTH = "target_bluetooth"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_BG_MONITOR = "bg_monitor"
        const val KEY_MIN_SIGNAL = "min_signal"
        const val KEY_TRIGGER_MODE = "trigger_mode" // "wifi", "bluetooth", "both"
    }

    var targetSsid: String
        get() = prefs.getString(KEY_TARGET_SSID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TARGET_SSID, value).apply()

    var targetBluetooth: String
        get() = prefs.getString(KEY_TARGET_BLUETOOTH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TARGET_BLUETOOTH, value).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var bgMonitor: Boolean
        get() = prefs.getBoolean(KEY_BG_MONITOR, false)
        set(value) = prefs.edit().putBoolean(KEY_BG_MONITOR, value).apply()

    var minSignal: Int
        get() = prefs.getInt(KEY_MIN_SIGNAL, -80)
        set(value) = prefs.edit().putInt(KEY_MIN_SIGNAL, value).apply()

    var triggerMode: String
        get() = prefs.getString(KEY_TRIGGER_MODE, "wifi") ?: "wifi"
        set(value) = prefs.edit().putString(KEY_TRIGGER_MODE, value).apply()
}
