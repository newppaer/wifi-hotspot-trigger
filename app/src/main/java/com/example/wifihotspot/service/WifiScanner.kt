package com.example.wifihotspot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.util.Log

/**
 * WiFi 扫描器
 * 扫描周围 WiFi 网络
 */
class WifiScanner(private val context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var scanCallback: ((List<WifiNetworkInfo>) -> Unit)? = null

    data class WifiNetworkInfo(
        val ssid: String,
        val bssid: String,
        val level: Int,        // 信号强度 (dBm)
        val frequency: Int     // 频率 (MHz)
    )

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                val results = wifiManager.scanResults.map {
                    WifiNetworkInfo(
                        ssid = it.SSID,
                        bssid = it.BSSID,
                        level = it.level,
                        frequency = it.frequency
                    )
                }
                Log.d(TAG, "Scan complete: ${results.size} networks found")
                scanCallback?.invoke(results)
            }
        }
    }

    fun startListening(callback: (List<WifiNetworkInfo>) -> Unit) {
        scanCallback = callback
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(wifiScanReceiver, intentFilter)
    }

    fun stopListening() {
        try {
            context.unregisterReceiver(wifiScanReceiver)
        } catch (_: Exception) {}
        scanCallback = null
    }

    /**
     * 触发一次扫描
     */
    fun startScan(): Boolean {
        return try {
            wifiManager.startScan()
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission for WiFi scan", e)
            false
        }
    }

    /**
     * 检查目标 WiFi 是否在范围内
     */
    fun isTargetInRange(targetSsid: String, minLevel: Int = -80): Boolean {
        val results = wifiManager.scanResults
        return results.any {
            it.SSID.equals(targetSsid, ignoreCase = true) && it.level >= minLevel
        }
    }

    companion object {
        private const val TAG = "WifiScanner"
    }
}
