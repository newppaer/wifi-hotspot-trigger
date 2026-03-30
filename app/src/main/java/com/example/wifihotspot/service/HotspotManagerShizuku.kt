package com.example.wifihotspot.service

import android.content.Context
import android.os.Build
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * WiFi 热点管理器（Shizuku 方案）
 * 通过 Shizuku 以 ADB 权限（shell 用户）执行命令
 */
object HotspotManagerShizuku {
    private const val TAG = "HotspotMgrShizuku"
    const val SHIZUKU_PERMISSION_REQUEST = 1001

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun isShizukuGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission(activity: android.app.Activity) {
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
    }

    private fun exec(command: String): String {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (error.isNotBlank()) error else output
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku exec failed: $command", e)
            "error: ${e.message}"
        }
    }

    fun startTethering(context: Context): Boolean {
        val sdk = Build.VERSION.SDK_INT
        Log.d(TAG, "Starting tethering via Shizuku, SDK=$sdk")

        if (sdk >= 31) {
            if (tryStartSoftap()) return true
        }

        val commands = mutableListOf<String>()
        if (sdk >= 30) commands += "cmd connectivity tether start"
        if (sdk < 31) {
            commands += "svc wifi setap true"
            commands += "cmd wifi start-softap AndroidAP WPA2 12345678"
        }

        for (cmd in commands) {
            val result = exec(cmd)
            Log.d(TAG, "$cmd -> $result")
            if (!result.contains("error", ignoreCase = true) &&
                !result.contains("not found", ignoreCase = true) &&
                !result.contains("failed", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun stopTethering(context: Context): Boolean {
        val sdk = Build.VERSION.SDK_INT
        if (sdk >= 31) exec("cmd wifi stop-softap")
        exec("cmd connectivity tether stop")
        if (sdk < 31) exec("svc wifi setap false")
        Log.d(TAG, "Tethering stopped via Shizuku")
        return true
    }

    private fun tryStartSoftap(): Boolean {
        val ssid = "AndroidAP"
        val commands = listOf(
            "cmd wifi start-softap $ssid WPA2 12345678",
            "cmd wifi start-softap $ssid OPEN none",
            "cmd wifi tethering start"
        )
        for (cmd in commands) {
            val result = exec(cmd)
            Log.d(TAG, "$cmd -> $result")
            if (!result.contains("error", ignoreCase = true) &&
                !result.contains("not found", ignoreCase = true) &&
                !result.contains("failed", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun isHotspotEnabled(context: Context): Boolean {
        return try {
            val checks = listOf(
                "dumpsys connectivity tethering | grep -i 'state'",
                "dumpsys wifi | grep -i 'SoftApState'",
                "dumpsys wifi | grep -i 'tethering'"
            )
            for (cmd in checks) {
                val result = exec(cmd)
                if (result.contains("ENABLED", ignoreCase = true) ||
                    result.contains("TETHERED", ignoreCase = true) ||
                    result.contains("STARTED", ignoreCase = true)) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    fun getStatusText(context: Context): String {
        return when {
            !isShizukuAvailable() -> "⚠️ Shizuku 未运行"
            !isShizukuGranted() -> "⚠️ Shizuku 未授权"
            isHotspotEnabled(context) -> "🟢 热点已开启 (Shizuku)"
            else -> "🔴 热点已关闭 (Shizuku)"
        }
    }
}
