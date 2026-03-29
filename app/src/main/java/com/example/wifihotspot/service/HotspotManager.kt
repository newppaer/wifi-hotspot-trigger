package com.example.wifihotspot.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import java.lang.reflect.Method

/**
 * WiFi 热点管理器（反射调用系统 API）
 * 兼容 Android 10+，部分机型可能不适用
 */
object HotspotManager {
    private const val TAG = "HotspotManager"

    /**
     * 通过反射开启 WiFi 热点
     * @return true 表示调用成功（不代表热点一定开启，部分机型可能静默失败）
     */
    fun startTethering(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // 方式1: 直接调用 startTethering (反射)
            val callbackClass = Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
            val method: Method = cm.javaClass.getDeclaredMethod(
                "startTethering",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                callbackClass,
                android.os.Handler::class.java
            )
            method.isAccessible = true
            method.invoke(cm, 0, false, null, null) // 0 = TETHERING_WIFI
            Log.d(TAG, "startTethering called via reflection")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startTethering reflection failed, trying alternative", e)
            startTetheringAlternative(context)
        }
    }

    /**
     * 备用方案：通过 WifiManager 反射
     */
    private fun startTetheringAlternative(context: Context): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            // 尝试调用 WifiManager.startSoftAp()
            val method = wifiManager.javaClass.getDeclaredMethod(
                "setWifiApEnabled",
                android.net.wifi.WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(wifiManager, null, true)
            Log.d(TAG, "setWifiApEnabled called via reflection")
            true
        } catch (e: Exception) {
            Log.e(TAG, "All reflection methods failed", e)
            false
        }
    }

    /**
     * 通过反射关闭 WiFi 热点
     */
    fun stopTethering(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val method: Method = cm.javaClass.getDeclaredMethod(
                "stopTethering",
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(cm, 0) // 0 = TETHERING_WIFI
            Log.d(TAG, "stopTethering called via reflection")
            true
        } catch (e: Exception) {
            Log.e(TAG, "stopTethering reflection failed", e)
            false
        }
    }

    /**
     * 检查热点是否已开启
     */
    fun isHotspotEnabled(context: Context): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as Boolean
        } catch (e: Exception) {
            Log.e(TAG, "isWifiApEnabled reflection failed", e)
            false
        }
    }

    /**
     * 获取热点状态描述
     */
    fun getStatusText(context: Context): String {
        return when {
            isHotspotEnabled(context) -> "🟢 热点已开启"
            else -> "🔴 热点已关闭"
        }
    }
}
