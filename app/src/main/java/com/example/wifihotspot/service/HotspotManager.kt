package com.example.wifihotspot.service

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object HotspotManager {
    private const val TAG = "HotspotManager"

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("uid=0")
        } catch (e: Exception) { false }
    }

    fun startTethering(context: Context): Boolean {
        Log.d(TAG, "Starting (Root) on SDK ${Build.VERSION.SDK_INT}")
        val commands = listOf(
            "cmd wifi start-softap AndroidAP open none",
            "cmd tethering start 0",
            "cmd connectivity tethering start 0"
        )
        for (cmd in commands) {
            execRoot(cmd)
            repeat(10) {
                if (isHotspotEnabledOnce()) return true
                Thread.sleep(500)
            }
        }
        return isHotspotEnabledOnce()
    }

    fun stopTethering(context: Context): Boolean {
        execRoot("cmd wifi stop-softap")
        execRoot("cmd tethering stop 0")
        // 给系统一点时间清理网卡状态
        repeat(5) {
            if (!isHotspotEnabledOnce()) return true
            Thread.sleep(400)
        }
        return !isHotspotEnabledOnce()
    }

    private fun isHotspotEnabledOnce(): Boolean {
        return try {
            val wifiStatus = execRoot("cmd wifi status")
            if (wifiStatus.contains("SAP is enabled", ignoreCase = true) || 
                wifiStatus.contains("SoftApState: 13", ignoreCase = true)) return true
            
            val ipAddr = execRoot("ip addr show")
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

    private fun execRoot(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (error.isNotBlank() && !error.contains("warning", ignoreCase = true)) "$output\n$error".trim() else output.trim()
        } catch (e: Exception) { "" }
    }
}
