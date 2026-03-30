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

    /**
     * 检测当前可用模式（带缓存，需手动刷新）
     */
    fun detectMode(): Mode {
        cachedMode?.let { return it }

        val mode = when {
            HotspotManager.isRootAvailable() -> Mode.ROOT
            HotspotManagerShizuku.isShizukuAvailable() &&
                HotspotManagerShizuku.isShizukuGranted() -> Mode.SHIZUKU
            else -> Mode.NONE
        }
        cachedMode = mode
        Log.d(TAG, "Detected mode: ${mode.label}")
        return mode
    }

    /**
     * 刷新模式检测（用户手动点击刷新时调用）
     */
    fun refreshMode(): Mode {
        cachedMode = null
        return detectMode()
    }

    /**
     * 获取完整状态信息
     */
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

        return StatusInfo(
            mode = mode,
            rootAvailable = rootAvailable,
            shizukuAvailable = shizukuAvailable,
            shizukuGranted = shizukuGranted,
            hotspotEnabled = hotspotEnabled
        )
    }

    /**
     * 开启热点
     */
    fun startTethering(context: Context): Boolean {
        return when (detectMode()) {
            Mode.ROOT -> HotspotManager.startTethering(context)
            Mode.SHIZUKU -> HotspotManagerShizuku.startTethering(context)
            Mode.NONE -> {
                Log.w(TAG, "No method available to start tethering")
                false
            }
        }
    }

    /**
     * 关闭热点
     */
    fun stopTethering(context: Context): Boolean {
        return when (detectMode()) {
            Mode.ROOT -> HotspotManager.stopTethering(context)
            Mode.SHIZUKU -> HotspotManagerShizuku.stopTethering(context)
            Mode.NONE -> false
        }
    }

    /**
     * 检查热点是否开启
     */
    fun isHotspotEnabled(context: Context): Boolean {
        return when (detectMode()) {
            Mode.ROOT -> HotspotManager.isHotspotEnabled(context)
            Mode.SHIZUKU -> HotspotManagerShizuku.isHotspotEnabled(context)
            Mode.NONE -> false
        }
    }

    /**
     * 是否有可用方案
     */
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
            mode == Mode.NONE -> buildUnavailableText(rootAvailable, shizukuAvailable, shizukuGranted)
            else -> "🔴 热点已关闭 (${mode.label})"
        }

        private fun buildUnavailableText(root: Boolean, shizuku: Boolean, granted: Boolean): String {
            return buildString {
                append("❌ 无可用方案\n")
                if (!root) append("  • Root: 未获取\n") else append("  • Root: ✅\n")
                if (!shizuku) append("  • Shizuku: 未运行\n")
                else if (!granted) append("  • Shizuku: 未授权\n")
                else append("  • Shizuku: ✅\n")
            }
        }
    }
}
