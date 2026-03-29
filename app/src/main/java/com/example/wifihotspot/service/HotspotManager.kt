package com.example.wifihotspot.service

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * WiFi 热点管理器（Root 方案）
 * 通过 su 执行系统命令控制热点
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
     */
    fun startTethering(context: Context): Boolean {
        return try {
            // 方式1: 直接调用 svc 命令
            val result1 = execRoot("svc wifi setap true")
            Log.d(TAG, "svc wifi setap true -> $result1")

            if (!result1.contains("error", ignoreCase = true)) {
                return true
            }

            // 方式2: 调用 cmd connectivity
            val result2 = execRoot("cmd connectivity tether start")
            Log.d(TAG, "cmd connectivity tether start -> $result2")
            true
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
            execRoot("svc wifi setap false")
            execRoot("cmd connectivity tether stop")
            Log.d(TAG, "Tethering stopped")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop tethering", e)
            false
        }
    }

    /**
     * 检查热点是否已开启
     */
    fun isHotspotEnabled(context: Context): Boolean {
        return try {
            val result = execRoot("dumpsys connectivity tethering | grep -i 'state'")
            result.contains("ENABLED", ignoreCase = true) ||
            result.contains("TETHERED", ignoreCase = true)
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
