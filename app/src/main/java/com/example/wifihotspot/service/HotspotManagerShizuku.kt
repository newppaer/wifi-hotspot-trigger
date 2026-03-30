package com.example.wifihotspot.service

import android.content.Context
import android.os.Build
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * WiFi 热点管理器（Shizuku 方案）
 * 针对 Android 15 (SDK 36) 精准优化
 */
object HotspotManagerShizuku {
    private const val TAG = "HotspotMgrShizuku"
    const val SHIZUKU_PERMISSION_REQUEST = 1001

    fun isShizukuAvailable() = try { Shizuku.pingBinder() } catch (e: Exception) { false }

    fun isShizukuGranted() = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    fun requestPermission(activity: android.app.Activity) {
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
    }

    private fun exec(command: String): String {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (error.isNotBlank() && !error.contains("warning", ignoreCase = true)) "$output\n$error".trim() else output.trim()
        } catch (e: Exception) { "" }
    }

    fun startTethering(context: Context): Boolean {
        val commands = listOf(
            "cmd wifi start-softap AndroidAP open none",
            "cmd tethering start 0",
            "cmd connectivity tethering start 0"
        )
        for (cmd in commands) {
            exec(cmd)
            repeat(10) {
                if (isHotspotEnabledOnce()) return true
                Thread.sleep(500)
            }
        }
        return isHotspotEnabledOnce()
    }

    fun stopTethering(context: Context): Boolean {
        exec("cmd wifi stop-softap")
        exec("cmd tethering stop 0")
        repeat(5) {
            if (!isHotspotEnabledOnce()) return true
            Thread.sleep(400)
        }
        return !isHotspotEnabledOnce()
    }

    private fun isHotspotEnabledOnce(): Boolean {
        return try {
            val wifiStatus = exec("cmd wifi status")
            if (wifiStatus.contains("SAP is enabled", ignoreCase = true) || 
                wifiStatus.contains("SoftApState: 13", ignoreCase = true)) return true
            
            val ipAddr = exec("ip addr show")
            val interfaces = listOf("wlan2", "wlan1", "ap0", "swlan0")
            for (iface in interfaces) {
                if (ipAddr.contains("$iface: <", ignoreCase = true)) {
                    val line = ipAddr.lines().find { it.contains("$iface: <") } ?: ""
                    if (line.contains("UP", ignoreCase = true) && !line.contains("state DOWN", ignoreCase = true)) {
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) { false }
    }

    fun isHotspotEnabled(context: Context): Boolean = isHotspotEnabledOnce()
}
