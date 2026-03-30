package com.example.wifihotspot.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * 后台监控服务
 * 持续扫描 WiFi/蓝牙，发现目标自动开热点
 */
class MonitorService(private val context: Context) {

    companion object {
        private const val TAG = "MonitorService"
        private const val SCAN_INTERVAL = 10_000L
    }

    private var monitoring = false
    private var thread: Thread? = null
    private var targetSsid = ""
    private var targetBt = ""
    private var lastDetection = ""
    private var wifiReceiver: BroadcastReceiver? = null
    private var btReceiver: BroadcastReceiver? = null

    fun startMonitor(ssid: String, bt: String): Boolean {
        if (monitoring) stopMonitor()
        targetSsid = ssid.trim()
        targetBt = bt.trim()
        if (targetSsid.isEmpty() && targetBt.isEmpty()) return false

        monitoring = true
        Log.d(TAG, "Start: WiFi='$targetSsid', BT='$targetBt'")
        thread = Thread({ loop() }, "Monitor").apply { isDaemon = true; start() }
        return true
    }

    fun stopMonitor() {
        monitoring = false
        thread?.interrupt()
        thread = null
        unregister()
    }

    fun isMonitoring() = monitoring
    fun getLastDetection() = lastDetection

    private fun loop() {
        registerWifi()
        if (targetBt.isNotEmpty()) registerBt()

        while (monitoring && !Thread.currentThread().isInterrupted) {
            try {
                if (targetSsid.isNotEmpty()) {
                    val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wm.startScan()
                }
                if (targetBt.isNotEmpty()) {
                    val a = BluetoothAdapter.getDefaultAdapter()
                    if (a?.isEnabled == true && !a.isDiscovering) a.startDiscovery()
                }
                Thread.sleep(SCAN_INTERVAL)
            } catch (e: InterruptedException) { break }
            catch (e: Exception) {
                Log.e(TAG, "Error", e)
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }
        unregister()
    }

    private fun registerWifi() {
        wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (targetSsid.isEmpty()) return
                try {
                    val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    if (wm.scanResults.any { it.SSID.equals(targetSsid, true) }) {
                        lastDetection = "WiFi: $targetSsid"
                        Log.d(TAG, "Found WiFi: $targetSsid")
                        trigger()
                    }
                } catch (_: Exception) {}
            }
        }
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(wifiReceiver, f, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(wifiReceiver, f)
        }
    }

    private fun registerBt() {
        btReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND || targetBt.isEmpty()) return
                val d = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val name = d?.name ?: return
                if (name.equals(targetBt, true) || name.contains(targetBt, true)) {
                    lastDetection = "BT: $name"
                    Log.d(TAG, "Found BT: $name")
                    trigger()
                }
            }
        }
        val f = IntentFilter(BluetoothDevice.ACTION_FOUND)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(btReceiver, f, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(btReceiver, f)
        }
    }

    private fun trigger() {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd wifi status"))
            if (p.inputStream.bufferedReader().readText().contains("SAP is enabled", true)) return
        } catch (_: Exception) {}

        val cmds = listOf("cmd wifi start-softap AndroidAP open none", "cmd tethering start 0")
        for (c in cmds) {
            try { Runtime.getRuntime().exec(arrayOf("sh", "-c", c)).waitFor() } catch (_: Exception) {}
            repeat(5) {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd wifi status"))
                    if (p.inputStream.bufferedReader().readText().contains("SAP is enabled", true)) return
                } catch (_: Exception) {}
                Thread.sleep(500)
            }
        }
    }

    private fun unregister() {
        try { wifiReceiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        try { btReceiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        wifiReceiver = null; btReceiver = null
    }
}
