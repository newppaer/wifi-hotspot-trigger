package com.example.wifihotspot.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * 无 root / Shizuku 的热点开启方案（兜底）
 *
 * Android 10+ : ConnectivityManager.startTethering()
 *   - 首次弹系统确认 dialog
 *   - 之后静默成功（系统记住了权限）
 *
 * Android 9-  : 无法自动开热点，只能引导用户手动开
 */
object HotspotManagerNoRoot {
    private const val TAG = "HotspotNoRoot"

    /**
     * @return true = 已调用系统 API（异步回调），false = 系统不支持需手动
     */
    fun startHotspot(
        context: Context,
        onSuccess: () -> Unit = {},
        onFail: () -> Unit = {}
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Android 9 不支持自动开热点")
            Toast.makeText(context, "Android 9 不支持，请手动开启", Toast.LENGTH_LONG).show()
            onFail()
            return false
        }

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+: TetheringRequest
                val request = ConnectivityManager.TetheringRequest.Builder(
                    ConnectivityManager.TETHERING_WIFI
                ).setShouldShowEntitlementUi(false).build()

                cm.startTethering(
                    request,
                    context.mainExecutor,
                    object : ConnectivityManager.OnStartTetheringCallback() {
                        override fun onTetheringStarted() {
                            Log.d(TAG, "✅ 热点开启成功 (Android 11+)")
                            onSuccess()
                        }
                        override fun onTetheringFailed(error: Int) {
                            Log.w(TAG, "❌ 热点开启失败 error=$error，引导手动开")
                            onFail()
                        }
                    }
                )
                return true
            } else {
                // Android 10: 旧版 API（需主线程）
                val looper = Looper.getMainLooper()
                if (looper.thread == Thread.currentThread()) {
                    invokeAndroid10Api(cm, onSuccess, onFail)
                } else {
                    Handler(looper).post { invokeAndroid10Api(cm, onSuccess, onFail) }
                }
                return true
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "权限不足", e)
            onFail()
            return false
        }
    }

    private fun invokeAndroid10Api(
        cm: ConnectivityManager,
        onSuccess: () -> Unit,
        onFail: () -> Unit
    ) {
        @Suppress("DEPRECATION")
        cm.startTethering(
            ConnectivityManager.TETHERING_WIFI,
            true,
            object : ConnectivityManager.OnStartTetheringCallback() {
                override fun onTetheringStarted() {
                    Log.d(TAG, "✅ 热点开启成功 (Android 10)")
                    onSuccess()
                }
                override fun onTetheringFailed() {
                    Log.w(TAG, "❌ 热点开启失败，引导手动开")
                    onFail()
                }
            },
            Handler(Looper.getMainLooper())
        )
    }

    /** 打开系统热点/网络设置页 */
    fun openHotspotSettings(context: Context) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            } else {
                Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
            }
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "请手动: 设置 → 网络 → 热点 → 开启", Toast.LENGTH_LONG).show()
        }
    }

    fun isSystemApiAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}
