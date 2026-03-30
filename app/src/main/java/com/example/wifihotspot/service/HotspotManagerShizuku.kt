package com.example.wifihotspot.service

import android.content.Context
import android.os.Build
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * WiFi 热点管理器（Shizuku 方案）
 * 通过 Shizuku 以 ADB 权限（shell 用户）执行命令
 * 不需要 Root，但需要用户安装并激活 Shizuku
 */
object HotspotManagerShizuku {
    private const val TAG = "HotspotMgrShizuku"
    const val SHIZUKU_PERMISSION_REQUEST = 1001

    /**
     * Shizuku Binder 是否可用（Shizuku 是否在运行）
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Shizuku 权限是否已授予
     */
    fun isShizukuGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 请求 Shizuku 权限（需要 Activity 上下文）
     */
    fun requestPermission(activity: android.app.Activity) {
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
    }

    /**
     * 通过 Shizuku 执行 shell 命令
     */
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

    /**
     * 开启热点
     * 按版本分级尝试，Shizuku 有 ADB 权限，比 Root 方案更稳
     */
    fun startTethering(context: Context): Boolean {
        val sdk = Build.VERSION.SDK_INT
        Log.d(TAG, "Starting tethering via Shizuku, SDK=$sdk")

        // Android 12+ (SDK 31+): cmd wifi start-softap 最可靠
        if (sdk >= 31) {
            if (tryStartSoftap()) return true
        }

        // 通用 fallback 列表
        val commands = mutableListOf<String>()

        if (sdk >= 30) {
            commands += "cmd connectivity tether start"
        }
        if (sdk < 31) {
            commands += "svc wifi setap true"
        }
        // 放在最后兜底
        if (sdk < 31) {
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

    /**
     * 关闭热点
     */
    fun stopTethering(context: Context): Boolean {
        val sdk = Build.VERSION.SDK_INT

        val commands = mutableListOf<String>()
        if (sdk >= 31) {
            commands += "cmd wifi stop-softap"
        }
        commands += "cmd connectivity tether stop"
        if (sdk < 31) {
            commands += "svc wifi setap false"
        }

        for (cmd in commands) {
            exec(cmd) // 忽略错误，全部执行一遍确保清理
        }
        Log.d(TAG, "Tethering stopped via Shizuku")
        return true
    }

    /**
     * 尝试 cmd wifi start-softap
     */
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

    /**
     * 检查热点是否开启
     */
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

    /**
     * 状态描述
     */
    fun getStatusText(context: Context): String {
        return when {
            !isShizukuAvailable() -> "⚠️ Shizuku 未运行"
            !isShizukuGranted() -> "⚠️ Shizuku 未授权"
            isHotspotEnabled(context) -> "🟢 热点已开启 (Shizuku)"
            else -> "🔴 热点已关闭 (Shizuku)"
        }
    }
}
