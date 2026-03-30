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
 * Shizuku 后台监控服务
 * 跑在 Shizuku 进程里，app 被杀也能继续运行
 * 发现目标 WiFi/蓝牙 → 直接用 ADB 权限开热点
 */
class MonitorService(
    private val context: Context
) : IMonitorService.Stub {

    companion object {
        private const val TAG = "MonitorService"
        private const val SCAN_INTERVAL = 10_000L // 10 秒扫一次
    }

    private var monitoring = false
    private var monitorThread: Thread? = null
    private var targetSsid = ""
    private var targetBluetooth = ""
    private var lastDetection = ""

    // WiFi 扫描接收器
    private var wifiReceiver: BroadcastReceiver? = null
    private var bluetoothReceiver: BroadcastReceiver? = null

    override fun startMonitor(targetSsid: String, targetBluetooth: String): Boolean {
        if (monitoring) stopMonitor()

        this.targetSsid = targetSsid.trim()
        this.targetBluetooth = targetBluetooth.trim()

        if (this.targetSsid.isEmpty() && this.targetBluetooth.isEmpty()) {
            Log.w(TAG, "No target specified")
            return false
        }

        monitoring = true
        Log.d(TAG, "Starting monitor: WiFi='${this.targetSsid}', BT='${this.targetBluetooth}'")

        monitorThread = Thread({
            runMonitorLoop()
        }, "HotspotMonitor").apply {
            isDaemon = true
            start()
        }

        return true
    }

    override fun stopMonitor() {
        monitoring = false
        monitorThread?.interrupt()
        monitorThread = null
        unregisterReceivers()
        Log.d(TAG, "Monitor stopped")
    }

    override fun isMonitoring(): Boolean = monitoring

    override fun getLastDetection(): String = lastDetection

    private fun runMonitorLoop() {
        registerWifiReceiver()
        if (targetBluetooth.isNotEmpty()) {
            registerBluetoothReceiver()
        }

        while (monitoring && !Thread.currentThread().isInterrupted) {
            try {
                // 触发 WiFi 扫描
                triggerWifiScan()

                // 蓝牙发现（每 30 秒一次，因为 discovery 要 12 秒）
                if (targetBluetooth.isNotEmpty()) {
                    triggerBluetoothDiscovery()
                }

                Thread.sleep(SCAN_INTERVAL)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Monitor loop error", e)
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }

        unregisterReceivers()
        Log.d(TAG, "Monitor loop exited")
    }

    // === WiFi ===

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
        if (targetSsid.isEmpty()) return
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.startScan()
        } catch (e: Exception) {
            Log.e(TAG, "WiFi scan trigger failed", e)
        }
    }

    private fun checkWifiResults() {
        if (targetSsid.isEmpty()) return
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val found = wm.scanResults.any {
                it.SSID.equals(targetSsid, ignoreCase = true)
            }
            if (found) {
                lastDetection = "WiFi: $targetSsid"
                Log.d(TAG, "Target WiFi found: $targetSsid")
                triggerHotspot()
            }
        } catch (e: Exception) {
            Log.e(TAG, "WiFi check failed", e)
        }
    }

    // === Bluetooth ===

    private fun registerBluetoothReceiver() {
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
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
                            Log.d(TAG, "Target Bluetooth found: $name")
                            triggerHotspot()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(bluetoothReceiver, filter)
        }
    }

    private fun triggerBluetoothDiscovery() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            if (!adapter.isEnabled) return
            if (adapter.isDiscovering) return
            adapter.startDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "BT discovery failed", e)
        }
    }

    // === 开热点（ADB 权限级别） ===

    private fun triggerHotspot() {
        if (HotspotController.isHotspotEnabled(context)) return

        Log.d(TAG, "Triggering hotspot...")
        val commands = listOf(
            "cmd wifi start-softap AndroidAP open none",
            "cmd tethering start 0",
            "cmd connectivity tethering start 0"
        )

        for (cmd in commands) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                process.waitFor()
            } catch (_: Exception) {}

            // 轮询检测是否开启成功
            repeat(10) {
                if (checkHotspotActive()) {
                    Log.d(TAG, "Hotspot started successfully")
                    return
                }
                Thread.sleep(500)
            }
        }
    }

    private fun checkHotspotActive(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd wifi status"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("SAP is enabled", ignoreCase = true) ||
            output.contains("SoftApState: 13", ignoreCase = true)
        } catch (e: Exception) { false }
    }

    private fun unregisterReceivers() {
        try { wifiReceiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        try { bluetoothReceiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        wifiReceiver = null
        bluetoothReceiver = null
    }
}
