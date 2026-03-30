package com.example.wifihotspot.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * 蓝牙扫描器（UI 用）
 * 使用 startDiscovery() 发现附近蓝牙设备，不需要配对
 */
class BluetoothScanner(private val context: Context) {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var callback: ((List<BluetoothDeviceInfo>) -> Unit)? = null
    private var deviceCallback: ((BluetoothDeviceInfo) -> Unit)? = null
    private val discoveredDevices = mutableMapOf<String, BluetoothDeviceInfo>()

    data class BluetoothDeviceInfo(
        val name: String,
        val address: String,
        val rssi: Int
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    device?.let {
                        val info = BluetoothDeviceInfo(
                            name = it.name ?: "<未知设备>",
                            address = it.address,
                            rssi = rssi
                        )
                        discoveredDevices[it.address] = info
                        deviceCallback?.invoke(info)
                        callback?.invoke(discoveredDevices.values.toList())
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    callback?.invoke(discoveredDevices.values.toList())
                    Log.d(TAG, "Discovery finished, found ${discoveredDevices.size} devices")
                }
            }
        }
    }

    fun isAvailable(): Boolean = bluetoothAdapter != null

    fun isEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun startListening(onDeviceFound: (BluetoothDeviceInfo) -> Unit) {
        deviceCallback = onDeviceFound
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun startListening(onListUpdate: (List<BluetoothDeviceInfo>) -> Unit) {
        callback = onListUpdate
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun stopListening() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {}
        callback = null
        deviceCallback = null
    }

    /**
     * 开始扫描（约 12 秒自动结束）
     */
    fun startScan(): Boolean {
        return try {
            bluetoothAdapter?.let { adapter ->
                if (adapter.isDiscovering) adapter.cancelDiscovery()
                discoveredDevices.clear()
                adapter.startDiscovery()
            } ?: false
        } catch (e: SecurityException) {
            Log.e(TAG, "BT scan permission denied", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "BT scan failed", e)
            false
        }
    }

    fun stopScan() {
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "BluetoothScanner"
    }
}
