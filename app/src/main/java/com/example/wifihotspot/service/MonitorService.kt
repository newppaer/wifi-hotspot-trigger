package com.example.wifihotspot.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 后台监控服务 — 重构版
 *
 * 职责:
 * 1. 持续扫描 WiFi 和蓝牙
 * 2. 发现目标网络 → 广播通知前台（不直接开热点，因为无权限）
 *
 * 前台 Activity 收到广播后:
 * - 有 Shizuku/Root → HotspotController 静默开
 * - 无权限 → 用系统 API (ConnectivityManager)
 *
 * 防抖: 同一目标 60 秒内不重复
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        const val ACTION_TARGET_DETECTED = "com.example.wifihotspot.TARGET_DETECTED"
        const val EXTRA_TARGET = "target"
        const val EXTRA_TYPE = "type"
        private const val NOTIF_ID = 1001
        private const val COOLDOWN_SEC = 60
    }

    var targetSsid = ""
    var targetBt = ""
    private var scanning = false
    private var timerThread: Thread? = null

    private var wifiReceiver: BroadcastReceiver? = null
    private var btReceiver: BroadcastReceiver? = null
    private val lastTriggerTime = mutableMapOf<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopMonitoring()
            stopSelf()
            return START_NOT_STICKY
        }

        val ssid = intent?.getStringExtra("ssid") ?: ""
        val bt = intent?.getStringExtra("bt") ?: ""
        if (ssid.isEmpty() && bt.isEmpty()) {
            stopMonitoring()
            stopSelf()
            return START_NOT_STICKY
        }

        targetSsid = ssid
        targetBt = bt
        lastTriggerTime.clear()
        scanning = true

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotif("开始监控: $ssid / $bt"))
        registerReceivers()
        startScanLoop()

        Log.d(TAG, "✅ 监控已启动: WiFi='$ssid' BT='$bt'")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
    }

    private fun stopMonitoring() {
        scanning = false
        timerThread?.interrupt()
        timerThread = null
        unregisterWifi()
        unregisterBt()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        Log.d(TAG, "⏹ 监控已停止")
    }

    // --- 通知 channel (Android 8.0+ 必须) ---

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val existing = nm.getNotificationChannel("wifi_hotspot_monitor")
            if (existing == null) {
                val channel = android.app.NotificationChannel(
                    "wifi_hotspot_monitor",
                    "WiFi 热点监控",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply { description = "后台扫描目标网络并通知前台" }
                nm.createNotificationChannel(channel)
            }
        } catch (_: Exception) {}
    }

    // --- 注册广播接收器 ---

    private fun registerReceivers() {
        if (targetSsid.isNotEmpty()) registerWifi()
        if (targetBt.isNotEmpty()) registerBt()
    }

    private fun registerWifi() {
        wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!scanning || targetSsid.isEmpty()) return
                try {
                    val wm = getSystemService(WIFI_SERVICE) as WifiManager
                    val match = wm.scanResults.filter { it.SSID.equals(targetSsid, ignoreCase = true) }
                    if (match.isNotEmpty()) onTargetDetected("WiFi: $targetSsid", "wifi")
                } catch (_: Exception) {}
            }
        }
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 34) registerReceiver(wifiReceiver, f, RECEIVER_EXPORTED)
        else registerReceiver(wifiReceiver, f)
    }

    private fun unregisterWifi() {
        try { wifiReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        wifiReceiver = null
    }

    private fun registerBt() {
        btReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!scanning || targetBt.isEmpty()) return
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                val device = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val name = device?.name ?: return
                if (name.equals(targetBt, ignoreCase = true) || name.contains(targetBt, ignoreCase = true)) {
                    onTargetDetected("BT: $name", "bluetooth")
                }
            }
        }
        val f = IntentFilter(BluetoothDevice.ACTION_FOUND)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(btReceiver, f, RECEIVER_EXPORTED)
        else registerReceiver(btReceiver, f)
    }

    private fun unregisterBt() {
        try { btReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        btReceiver = null
    }

    // --- 定时触发扫描 ---

    private fun startScanLoop() {
        timerThread = Thread {
            while (scanning) {
                try {
                    if (targetSsid.isNotEmpty()) {
                        try {
                            (getSystemService(WIFI_SERVICE) as WifiManager).startScan()
                        } catch (_: SecurityException) {}
                    }
                    if (targetBt.isNotEmpty()) {
                        val a = BluetoothAdapter.getDefaultAdapter()
                        if (a?.isEnabled == true) {
                            if (a.isDiscovering) a.cancelDiscovery()
                            a.startDiscovery()
                        }
                    }
                    Thread.sleep(10_000)
                } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    // --- 发现目标 → 广播通知前台 ---

    private fun onTargetDetected(label: String, type: String) {
        val now = System.currentTimeMillis() / 1000
        lastTriggerTime[label]?.let { if (now - it < COOLDOWN_SEC) return }
        lastTriggerTime[label] = now

        Log.d(TAG, "🎯 发现目标: $label")

        // 更新通知
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif("已发现 $label，尝试开启热点..."))

        // 发广播给 Activity → Activity 开热点
        val i = Intent(ACTION_TARGET_DETECTED).apply {
            putExtra(EXTRA_TARGET, label)
            putExtra(EXTRA_TYPE, type)
        }
        sendBroadcast(i)
    }

    // --- 通知 ---

    private fun buildNotif(text: String) = NotificationCompat.Builder(this, "wifi_hotspot_monitor")
        .setContentTitle("WiFi 热点触发器")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_network_available)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0,
                Intent(this, com.example.wifihotspot.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel, "停止",
            PendingIntent.getService(
                this, 1, Intent(this, MonitorService::class.java).apply { action = "STOP" },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()
}
