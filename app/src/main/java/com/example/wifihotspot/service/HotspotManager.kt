package com.example.wifihotspot.service

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * WiFi 热点管理器（Root 方案）
 * 自动适配 Android 8-14+，按版本尝试不同命令
 */
object HotspotManager {
    private const val TAG = "HotspotManager"

    /**
     * 检查是否有 Root 权限
     */
    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output.contains("uid=0")
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            false
        }
    }

    /**
     * 通过 Root 开启 WiFi 热点
     * 按 Android 版本分级尝试不同命令
     */
    fun startTethering(context: Context): Boolean {
        return try {
            val sdk = Build.VERSION.SDK_INT
            Log.d(TAG, "Starting tethering on SDK $sdk")

            // Android 14+ (SDK 34+): svc wifi setap 已失效
            // 优先尝试 cmd wifi start-softap
            if (sdk >= 34) {
                if (tryStartSoftap()) return true
                // fallback: cmd connectivity tether（部分 OEM 可用）
                val r = execRoot("cmd connectivity tether start")
                Log.d(TAG, "cmd connectivity tether start -> $r")
                if (!r.contains("error", ignoreCase = true) &&
                    !r.contains("not found", ignoreCase = true)) return true
            }

            // Android 11-13: cmd connectivity tether 通常可用
            if (sdk >= 30) {
                val r = execRoot("cmd connectivity tether start")
                Log.d(TAG, "cmd connectivity tether start -> $r")
                if (!r.contains("error", ignoreCase = true) &&
                    !r.contains("not found", ignoreCase = true)) return true
            }

            // Android 8-10: svc wifi setap
            val r = execRoot("svc wifi setap true")
            Log.d(TAG, "svc wifi setap true -> $r")
            if (!r.contains("error", ignoreCase = true)) return true

            // 最终 fallback：尝试 start-softap
            tryStartSoftap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tethering", e)
            false
        }
    }

    /**
     * 通过 Root 关闭 WiFi 热点
     */
    fun stopTethering(context: Context): Boolean {
        return try {
            val sdk = Build.VERSION.SDK_INT

            if (sdk >= 34) {
                execRoot("cmd wifi stop-softap")
                execRoot("cmd connectivity tether stop")
            } else if (sdk >= 30) {
                execRoot("cmd connectivity tether stop")
                execRoot("svc wifi setap false")
            } else {
                execRoot("svc wifi setap false")
            }

            Log.d(TAG, "Tethering stopped")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop tethering", e)
            false
        }
    }

    /**
     * 尝试 cmd wifi start-softap（Android 14+ 推荐方式）
     * 需要指定 SSID 和安全类型
     */
    private fun tryStartSoftap(): Boolean {
        // 生成默认 SSID
        val ssid = "AndroidAP_${Build.MODEL.replace(" ", "").take(8)}"
        val commands = listOf(
            "cmd wifi start-softap $ssid WPA2 12345678",
            "cmd wifi start-softap $ssid OPEN none",
            "cmd wifi tethering start",
            "cmd wifi start-softap $ssid"
        )
        for (cmd in commands) {
            val result = execRoot(cmd)
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
     * 检查热点是否已开启
     */
    fun isHotspotEnabled(context: Context): Boolean {
        return try {
            // 多种方式检测
            val checks = listOf(
                "dumpsys connectivity tethering | grep -i 'state'",
                "dumpsys wifi | grep -i 'SoftApState'",
                "dumpsys wifi | grep -i 'tethering'"
            )
            for (cmd in checks) {
                val result = execRoot(cmd)
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
     * 获取热点状态描述
     */
    fun getStatusText(context: Context): String {
        return if (isRootAvailable()) {
            if (isHotspotEnabled(context)) "🟢 热点已开启 (Root)"
            else "🔴 热点已关闭 (Root)"
        } else {
            "⚠️ 未获取 Root 权限"
        }
    }

    /**
     * 执行 Root 命令
     */
    private fun execRoot(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = reader.readText()
            val error = errorReader.readText()
            process.waitFor()
            reader.close()
            errorReader.close()
            if (error.isNotBlank()) error else output
        } catch (e: Exception) {
            Log.e(TAG, "Root command failed: $command", e)
            "error: ${e.message}"
        }
    }
}
