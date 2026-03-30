package com.example.wifihotspot

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.wifihotspot.data.SettingsManager
import com.example.wifihotspot.service.HotspotController
import com.example.wifihotspot.service.HotspotManagerShizuku
import com.example.wifihotspot.service.WifiScanner
import com.example.wifihotspot.ui.WifiHotspotTheme
import android.Manifest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WifiHotspotTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val settings = remember { SettingsManager(context) }
    val scanner = remember { WifiScanner(context) }

    var targetSsid by remember { mutableStateOf(settings.targetSsid) }
    var autoStart by remember { mutableStateOf(settings.autoStart) }
    var scanResults by remember { mutableStateOf<List<WifiScanner.WifiNetworkInfo>>(emptyList()) }
    var statusInfo by remember { mutableStateOf(HotspotController.getFullStatus(context)) }
    var isScanning by remember { mutableStateOf(false) }
    var lastAction by remember { mutableStateOf("") }

    // Shizuku 权限请求回调
    val shizukuPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 权限变更后刷新状态
        statusInfo = HotspotController.refreshMode().let {
            HotspotController.getFullStatus(context)
        }
        if (HotspotController.isAvailable()) {
            lastAction = "✅ Shizuku 权限已授予"
        } else {
            lastAction = "❌ Shizuku 授权失败"
        }
    }

    // WiFi 权限请求
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            scanner.startScan()
            isScanning = true
        } else {
            Toast.makeText(context, "需要位置和WiFi权限才能扫描", Toast.LENGTH_SHORT).show()
        }
    }

    fun checkPermissionsAndScan() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            scanner.startScan()
            isScanning = true
        } else {
            wifiPermissionLauncher.launch(permissions)
        }
    }

    fun refreshStatus() {
        statusInfo = HotspotController.getFullStatus(context)
    }

    // 监听扫描结果
    DisposableEffect(Unit) {
        scanner.startListening { results ->
            scanResults = results
            isScanning = false

            // 自动触发热点
            if (autoStart && targetSsid.isNotBlank() && HotspotController.isAvailable()) {
                val inRange = results.any {
                    it.ssid.equals(targetSsid, ignoreCase = true) &&
                    it.level >= settings.minSignal
                }
                if (inRange && !HotspotController.isHotspotEnabled(context)) {
                    val success = HotspotController.startTethering(context)
                    lastAction = if (success) "✅ 检测到 $targetSsid，已开启热点 (${statusInfo.mode.label})"
                                 else "❌ 开启热点失败"
                    refreshStatus()
                }
            }
        }

        onDispose {
            scanner.stopListening()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi 热点触发器") },
                actions = {
                    IconButton(onClick = { refreshStatus() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新状态")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // === 双轨模式状态 ===
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (statusInfo.mode) {
                            HotspotController.Mode.ROOT -> MaterialTheme.colorScheme.primaryContainer
                            HotspotController.Mode.SHIZUKU -> MaterialTheme.colorScheme.secondaryContainer
                            HotspotController.Mode.NONE -> MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "🔧 权限模式: ${statusInfo.mode.label}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Root 状态
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (statusInfo.rootAvailable) "✅ Root" else "⬜ Root",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        // Shizuku 状态
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    statusInfo.shizukuGranted -> "✅ Shizuku (已授权)"
                                    statusInfo.shizukuAvailable -> "🟡 Shizuku (未授权)"
                                    else -> "⬜ Shizuku (未运行)"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        // Shizuku 授权按钮
                        if (statusInfo.shizukuAvailable && !statusInfo.shizukuGranted) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    activity?.let {
                                        HotspotManagerShizuku.requestPermission(it)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("授权 Shizuku")
                            }
                        }

                        // 无可用方案提示
                        if (statusInfo.mode == HotspotController.Mode.NONE) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "需要 Root 或 Shizuku 才能控制热点",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // === 热点状态 + 控制 ===
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("📡 热点状态", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(statusInfo.statusText, style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val ok = HotspotController.startTethering(context)
                                    lastAction = if (ok) "✅ 已开启热点 (${statusInfo.mode.label})"
                                                 else "❌ 开启失败"
                                    refreshStatus()
                                },
                                enabled = statusInfo.mode != HotspotController.Mode.NONE
                            ) {
                                Text("开启热点")
                            }
                            OutlinedButton(
                                onClick = {
                                    val ok = HotspotController.stopTethering(context)
                                    lastAction = if (ok) "✅ 已关闭热点"
                                                 else "❌ 关闭失败"
                                    refreshStatus()
                                },
                                enabled = statusInfo.mode != HotspotController.Mode.NONE
                            ) {
                                Text("关闭热点")
                            }
                            OutlinedButton(onClick = { refreshStatus() }) {
                                Text("刷新")
                            }
                        }
                    }
                }
            }

            // === 目标 WiFi 设置 ===
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("🎯 目标 WiFi", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = targetSsid,
                            onValueChange = {
                                targetSsid = it
                                settings.targetSsid = it
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("输入目标 WiFi 名称") },
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("检测到目标 WiFi 自动开启热点")
                            Switch(checked = autoStart, onCheckedChange = {
                                autoStart = it
                                settings.autoStart = it
                            })
                        }
                    }
                }
            }

            // === 扫描按钮 ===
            item {
                Button(
                    onClick = { checkPermissionsAndScan() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isScanning) "扫描中..." else "🔍 扫描周围 WiFi")
                }
            }

            // === 操作结果 ===
            if (lastAction.isNotBlank()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Text(lastAction, modifier = Modifier.padding(16.dp))
                    }
                }
            }

            // === 扫描结果 ===
            if (scanResults.isNotEmpty()) {
                item {
                    Text(
                        "📡 扫描到 ${scanResults.size} 个 WiFi",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                items(scanResults.sortedByDescending { it.level }) { wifi ->
                    val isTarget = wifi.ssid.equals(targetSsid, ignoreCase = true)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isTarget) {
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        } else {
                            CardDefaults.cardColors()
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    if (wifi.ssid.isBlank()) "<隐藏网络>" else wifi.ssid,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isTarget) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "${wifi.level} dBm | ${if (wifi.frequency > 5000) "5GHz" else "2.4GHz"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isTarget) {
                                Text("🎯", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}
