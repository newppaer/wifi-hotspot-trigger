package com.example.wifihotspot.service

import android.content.Context

object HotspotController {
    enum class Mode(val label: String) {
        ROOT("Root"),
        SHIZUKU("Shizuku"),
        NONE("未授权")
    }

    /**
     * 实时检测当前可用模式，移除缓存以保证授权后立即生效
     */
    fun detectMode(): Mode {
        if (HotspotManager.isRootAvailable()) return Mode.ROOT
        if (HotspotManagerShizuku.isShizukuAvailable() && HotspotManagerShizuku.isShizukuGranted()) return Mode.SHIZUKU
        return Mode.NONE
    }

    fun getFullStatus(context: Context): StatusInfo {
        val mode = detectMode()
        val enabled = when (mode) {
            Mode.ROOT -> HotspotManager.isHotspotEnabled(context)
            Mode.SHIZUKU -> HotspotManagerShizuku.isHotspotEnabled(context)
            else -> false
        }
        return StatusInfo(
            mode = mode,
            rootAvailable = HotspotManager.isRootAvailable(),
            shizukuAvailable = HotspotManagerShizuku.isShizukuAvailable(),
            shizukuGranted = HotspotManagerShizuku.isShizukuGranted(),
            hotspotEnabled = enabled
        )
    }

    fun startTethering(context: Context): Boolean = when (detectMode()) {
        Mode.ROOT -> HotspotManager.startTethering(context)
        Mode.SHIZUKU -> HotspotManagerShizuku.startTethering(context)
        else -> false
    }

    fun stopTethering(context: Context): Boolean = when (detectMode()) {
        Mode.ROOT -> HotspotManager.stopTethering(context)
        Mode.SHIZUKU -> HotspotManagerShizuku.stopTethering(context)
        else -> false
    }

    fun isHotspotEnabled(context: Context): Boolean = getFullStatus(context).hotspotEnabled

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
            mode == Mode.NONE -> "❌ 权限受限"
            else -> "🔴 热点已关闭 (${mode.label})"
        }
    }
}
