package com.example.wifihotspot.service

import android.content.Context
import android.util.Log

/**
 * 统一热点控制器
 * 自动检测 Root/Shizuku 可用性，选择最佳方案
 */
object HotspotController {
    private const val TAG = "HotspotController"

    enum class Mode(val label: String) {
        ROOT("Root"),
        SHIZUKU("Shizuku"),
        NONE("不可用")
    }

    private var cachedMode: Mode? = null

    fun detectMode(): Mode {
        cachedMode?.let { return it }
        val mode = when {
            HotspotManager.isRootAvailable() -> Mode.ROOT
            HotspotManagerShizuku.isShizukuAvailable() &&
                HotspotManagerShizuku.isShizukuGranted() -> Mode.SHIZUKU
            else -> Mode.NONE
        }
        cachedMode = mode
        return mode
    }

    fun refreshMode(): Mode {
        cachedMode = null
        return detectMode()
    }

    fun getFullStatus(context: Context): StatusInfo {
        val rootAvailable = HotspotManager.isRootAvailable()
        val shizukuAvailable = HotspotManagerShizuku.isShizukuAvailable()
        val shizukuGranted = HotspotManagerShizuku.isShizukuGranted()
        val mode = detectMode()
        val hotspotEnabled = when (mode) {
            Mode.ROOT -> HotspotManager.isHotspotEnabled(context)
            Mode.SHIZUKU -> HotspotManagerShizuku.isHotspotEnabled(context)
            Mode.NONE -> false
        }
        return StatusInfo(mode, rootAvailable, shizukuAvailable, shizukuGranted, hotspotEnabled)
    }

    fun startTethering(context: Context): Boolean {
        return when (detectMode()) {
            Mode.ROOT -> HotspotManager.startTethering(context)
            Mode.SHIZUKU -> HotspotManagerShizuku.startTethering(context)
            Mode.NONE -> false
        }
    }

    fun stopTethering(context: Context): Boolean {
        return when (detectMode()) {
            Mode.ROOT -> HotspotManager.stopTethering(context)
            Mode.SHIZUKU -> HotspotManagerShizuku.stopTethering(context)
            Mode.NONE -> false
        }
    }

    fun isHotspotEnabled(context: Context): Boolean {
        return when (detectMode()) {
            Mode.ROOT -> HotspotManager.isHotspotEnabled(context)
            Mode.SHIZUKU -> HotspotManagerShizuku.isHotspotEnabled(context)
            Mode.NONE -> false
        }
    }

    fun isAvailable(): Boolean = detectMode() != Mode.NONE

    data class StatusInfo(
        val mode: Mode,
        val rootAvailable: Boolean,
        val shizukuAvailable: Boolean,
        val shizukuGranted: Boolean,
        val hotspotEnabled: Boolean
    ) {
        val statusText: String get() = when {
            hotspotEnabled -> "🟢 热点已开启 (${mode.label})"
            mode == Mode.NONE -> "❌ 无可用方案 (需要 Root 或 Shizuku)"
            else -> "🔴 热点已关闭 (${mode.label})"
        }
    }
}
