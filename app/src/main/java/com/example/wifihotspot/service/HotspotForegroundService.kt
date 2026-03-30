package com.example.wifihotspot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 前台监控服务（非 Shizuku 用户的后备方案）
 * 持续扫描 WiFi/蓝牙，发现目标自动开热点
 */
class HotspotForegroundService : Service() {

    companion object {
        private const val TAG = "HotspotFgService"
        private const val CHANNEL_ID = "hotspot_monitor"
        private const val NOTIF_ID = 1001
        private const val SCAN_INTERVAL = 10_000L

        var isRunning = false
            private set

        fun start(context: Context, targetSsid: String, targetBt: String) {
            val intent = Intent(context, HotspotForegroundService::class.java).apply {
                putExtra("target_ssid", targetSsid)
                putExtra("target_bt", targetBt)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HotspotForegroundService::class.java))
        }
    }

    private var targetSsid = ""
    private var targetBt = ""
    private var running = false
    private var monitorThread: Thread? = null
    private var wifiReceiver: BroadcastReceiver? = null
    private var btReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        targetSsid = intent?.getStringExtra("target_ssid") ?: ""
        targetBt = intent?.getStringExtra("target_bt") ?: ""

        startForeground(NOTIF_ID, buildNotification("正在监控 WiFi/蓝牙..."))
        isRunning = true

        if (!running) {
            running = true
            monitorThread = Thread({ monitorLoop() }, "FgMonitor").apply {
                isDaemon = true
                start()
            }
        }

        return START_STICKY
    }

    private fun monitorLoop() {
        registerReceivers()

        while (running && !Thread.currentThread().isInterrupted) {
            try {
                // WiFi 扫描
                if (targetSsid.isNotEmpty()) {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wm.startScan()
                }

                // 蓝牙发现
                if (targetBt.isNotEmpty()) {
                    val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    if (adapter?.isEnabled == true && !adapter.isDiscovering) {
                        adapter.startDiscovery()
                    }
                }

                Thread.sleep(SCAN_INTERVAL)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Monitor error", e)
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }

        unregisterReceivers()
    }

    private fun registerReceivers() {
        // WiFi
        wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (targetSsid.isEmpty()) return
                try {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val found = wm.scanResults.any { it.SSID.equals(targetSsid, ignoreCase = true) }
                    if (found) {
                        Log.d(TAG, "Target WiFi found: $targetSsid")
                        triggerHotspot()
                        updateNotification("检测到 $targetSsid，已开启热点")
                    }
                } catch (_: Exception) {}
            }
        }
        val wf = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 34) {
            registerReceiver(wifiReceiver, wf, RECEIVER_EXPORTED)
        } else {
            registerReceiver(wifiReceiver, wf)
        }

        // Bluetooth
        btReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_FOUND && targetBt.isNotEmpty()) {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val name = device?.name ?: return
                    if (name.equals(targetBt, ignoreCase = true) || name.contains(targetBt, ignoreCase = true)) {
                        Log.d(TAG, "Target BT found: $name")
                        triggerHotspot()
                        updateNotification("检测到蓝牙 $name，已开启热点")
                    }
                }
            }
        }
        val bf = IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND) }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btReceiver, bf, RECEIVER_EXPORTED)
        } else {
            registerReceiver(btReceiver, bf)
        }
    }

    private fun triggerHotspot() {
        if (checkHotspotActive()) return

        val commands = listOf(
            "cmd wifi start-softap AndroidAP open none",
            "cmd tethering start 0"
        )
        for (cmd in commands) {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
            } catch (_: Exception) {}
            repeat(10) {
                if (checkHotspotActive()) return
                Thread.sleep(500)
            }
        }
    }

    private fun checkHotspotActive(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd wifi status"))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("SAP is enabled", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    private fun unregisterReceivers() {
        try { wifiReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { btReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        wifiReceiver = null
        btReceiver = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "热点监控", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台监控 WiFi/蓝牙触发热点"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("WiFi 热点助手")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        isRunning = false
        monitorThread?.interrupt()
        unregisterReceivers()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
