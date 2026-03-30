package com.example.wifihotspot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

class WifiScanner(private val context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var scanCallback: ((List<WifiNetworkInfo>) -> Unit)? = null

    data class WifiNetworkInfo(
        val ssid: String,
        val bssid: String,
        val level: Int,
        val frequency: Int
    )

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                fetchScanResults()
            }
        }
    }

    private fun fetchScanResults() {
        try {
            val results = wifiManager.scanResults.map {
                WifiNetworkInfo(
                    ssid = it.SSID,
                    bssid = it.BSSID,
                    level = it.level,
                    frequency = it.frequency
                )
            }
            Log.d(TAG, "Fetched ${results.size} WiFi networks")
            scanCallback?.invoke(results)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error fetching scan results", e)
        }
    }

    fun startListening(callback: (List<WifiNetworkInfo>) -> Unit) {
        scanCallback = callback
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ (API 34+) 必须指定导出属性
            context.registerReceiver(wifiScanReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(wifiScanReceiver, intentFilter)
        }
        
        // 立即获取一次当前结果
        fetchScanResults()
    }

    fun stopListening() {
        try {
            context.unregisterReceiver(wifiScanReceiver)
        } catch (_: Exception) {}
        scanCallback = null
    }

    fun startScan(): Boolean {
        return try {
            @Suppress("DEPRECATION")
            wifiManager.startScan().also { Log.d(TAG, "Start scan triggered: $it") }
        } catch (e: SecurityException) {
            false
        }
    }

    companion object {
        private const val TAG = "WifiScanner"
    }
}
