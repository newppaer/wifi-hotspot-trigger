package com.example.wifihotspot.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Binder
import android.util.Log

/**
 * 后台监控服务（可独立运行或作为 Shizuku UserService）
 * 持续扫描 WiFi/蓝牙，发现目标自动开热点
 */
class MonitorService(private val context: Context) : Binder() {

    companion object {
        private const val TAG = "MonitorService"
        private const val SCAN_INTERVAL = 10_000L
    }

    private var monitoring = false
    private var monitorThread: Thread? = null
    private var targetSsid = ""
    private var targetBluetooth = ""
    private var lastDetection = ""
    private var wifiReceiver: BroadcastReceiver? = null
    private var bluetoothReceiver: BroadcastReceiver? = null

    fun startMonitor(targetSsid: String, targetBluetooth: String): Boolean {
        if (monitoring) stopMonitor()

        this.targetSsid = targetSsid.trim()
        this.targetBluetooth = targetBluetooth.trim()

        if (this.targetSsid.isEmpty() && this.targetBluetooth.isEmpty()) {
            Log.w(TAG, "No target specified")
            return false
        }

        monitoring = true
        Log.d(TAG, "Starting: WiFi='${this.targetSsid}', BT='${this.targetBluetooth}'")

        monitorThread = Thread({ runMonitorLoop() }, "HotspotMonitor").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stopMonitor() {
        monitoring = false
        monitorThread?.interrupt()
        monitorThread = null
        unregisterReceivers()
        Log.d(TAG, "Stopped")
    }

    fun isMonitoring(): Boolean = monitoring
    fun getLastDetection(): String = lastDetection

    private fun runMonitorLoop() {
        registerWifiReceiver()
        if (targetBluetooth.isNotEmpty()) registerBluetoothReceiver()

        while (monitoring && !Thread.currentThread().isInterrupted) {
            try {
                if (targetSsid.isNotEmpty()) triggerWifiScan()
                if (targetBluetooth.isNotEmpty()) triggerBluetoothDiscovery()
                Thread.sleep(SCAN_INTERVAL)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Loop error", e)
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }
        unregisterReceivers()
    }

    private fun registerWifiReceiver() {
        wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    checkWifiResults()
                }
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(wifiReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(wifiReceiver, filter)
        }
    }

    private fun triggerWifiScan() {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.startScan()
        } catch (e: Exception) { Log.e(TAG, "WiFi scan failed", e) }
    }

    private fun checkWifiResults() {
        if (targetSsid.isEmpty()) return
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wm.scanResults.any { it.SSID.equals(targetSsid, ignoreCase = true) }) {
                lastDetection = "WiFi: $targetSsid"
                Log.d(TAG, "Target WiFi found: $targetSsid")
                triggerHotspot()
            }
        } catch (e: Exception) { Log.e(TAG, "WiFi check failed", e) }
    }

    private fun registerBluetoothReceiver() {
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_FOUND) {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val name = device?.name ?: return
                    if (name.equals(targetBluetooth, ignoreCase = true) ||
                        name.contains(targetBluetooth, ignoreCase = true)) {
                        lastDetection = "Bluetooth: $name"
                        Log.d(TAG, "Target BT found: $name")
                        triggerHotspot()
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(bluetoothReceiver, filter)
        }
    }

    private fun triggerBluetoothDiscovery() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            if (!adapter.isEnabled || adapter.isDiscovering) return
            adapter.startDiscovery()
        } catch (e: Exception) { Log.e(TAG, "BT discovery failed", e) }
    }

    private fun triggerHotspot() {
        if (checkHotspotActive()) return
        Log.d(TAG, "Triggering hotspot...")
        val commands = listOf(
            "cmd wifi start-softap AndroidAP open none",
            "cmd tethering start 0",
            "cmd connectivity tethering start 0"
        )
        for (cmd in commands) {
            try { Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor() } catch (_: Exception) {}
            repeat(10) {
                if (checkHotspotActive()) { Log.d(TAG, "Hotspot started"); return }
                Thread.sleep(500)
            }
        }
    }

    private fun checkHotspotActive(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd wifi status"))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("SAP is enabled", ignoreCase = true) || out.contains("SoftApState: 13", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    private fun unregisterReceivers() {
        try { wifiReceiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        try { bluetoothReceiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        wifiReceiver = null; bluetoothReceiver = null
    }
}
