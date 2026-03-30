package com.example.wifihotspot

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.wifihotspot.data.SettingsManager
import com.example.wifihotspot.service.*
import com.example.wifihotspot.ui.WifiHotspotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> recreate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        setContent { WifiHotspotTheme { MainScreen() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val settings = remember { SettingsManager(context) }
    val wifiScanner = remember { WifiScanner(context) }
    val btScanner = remember { BluetoothScanner(context) }
    val scope = rememberCoroutineScope()

    var targetSsid by remember { mutableStateOf(settings.targetSsid) }
    var targetBt by remember { mutableStateOf(settings.targetBluetooth) }
    var autoStart by remember { mutableStateOf(settings.autoStart) }
    var bgMonitor by remember { mutableStateOf(settings.bgMonitor) }
    var triggerMode by remember { mutableStateOf(settings.triggerMode) }
    var scanResults by remember { mutableStateOf<List<WifiScanner.WifiNetworkInfo>>(emptyList()) }
    var btResults by remember { mutableStateOf<List<BluetoothScanner.BluetoothDeviceInfo>>(emptyList()) }
    var statusInfo by remember { mutableStateOf(HotspotController.getFullStatus(context)) }
    var isWifiScanning by remember { mutableStateOf(false) }
    var isBtScanning by remember { mutableStateOf(false) }
    var lastAction by remember { mutableStateOf("") }

    val hasAnyPermission = statusInfo.mode != HotspotController.Mode.NONE

    fun refreshStatus() {
        scope.launch {
            statusInfo = withContext(Dispatchers.IO) { HotspotController.getFullStatus(context) }
        }
    }

    // 自动刷新状态
    LaunchedEffect(Unit) {
        while (true) {
            statusInfo = withContext(Dispatchers.IO) { HotspotController.getFullStatus(context) }
            delay(3000)
        }
    }

    // WiFi + 蓝牙 + 通知权限
    val multiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            Toast.makeText(context, "权限已授予", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestAllPermissions() {
        val perms = mutableListOf<String>()
        // WiFi
        perms += Manifest.permission.ACCESS_FINE_LOCATION
        perms += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        // Bluetooth
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        // 通知
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            multiPermLauncher.launch(needed.toTypedArray())
        }
    }

    // WiFi 扫描监听
    DisposableEffect(Unit) {
        wifiScanner.startListening { results ->
            scanResults = results
            isWifiScanning = false

            if (autoStart && targetSsid.isNotBlank() && HotspotController.isAvailable()) {
                val inRange = results.any { it.ssid.equals(targetSsid, ignoreCase = true) }
                if (inRange && !statusInfo.hotspotEnabled) {
                    scope.launch {
                        withContext(Dispatchers.IO) { HotspotController.startTethering(context) }
                        lastAction = "✅ 检测到 WiFi $targetSsid，已开启热点"
                        refreshStatus()
                    }
                }
            }
        }
        onDispose { wifiScanner.stopListening() }
    }

    // 蓝牙扫描监听
    DisposableEffect(Unit) {
        btScanner.startListening { device ->
            if (targetBt.isNotBlank() && autoStart && HotspotController.isAvailable()) {
                if (device.name.equals(targetBt, ignoreCase = true) ||
                    device.name.contains(targetBt, ignoreCase = true)) {
                    if (!statusInfo.hotspotEnabled) {
                        scope.launch {
                            withContext(Dispatchers.IO) { HotspotController.startTethering(context) }
                            lastAction = "✅ 检测到蓝牙 ${device.name}，已开启热点"
                            refreshStatus()
                        }
                    }
                }
            }
        }
        onDispose { btScanner.stopListening() }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("WiFi 热点助手", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { refreshStatus() }) { Icon(Icons.Default.Refresh, "刷新") }
                    IconButton(onClick = { requestAllPermissions() }) { Icon(Icons.Default.Security, "权限") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), Color.Transparent)
                ))
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }

                // === 权限状态 ===
                item { StatusCard(statusInfo, activity) }

                // === 热点控制 ===
                item {
                    ControlCard(
                        statusInfo = statusInfo,
                        hasPermission = hasAnyPermission,
                        lastAction = lastAction,
                        onStart = {
                            scope.launch {
                                lastAction = "正在开启..."
                                val ok = withContext(Dispatchers.IO) { HotspotController.startTethering(context) }
                                lastAction = if (ok) "✅ 开启成功" else "❌ 开启失败"
                                refreshStatus()
                            }
                        },
                        onStop = {
                            scope.launch {
                                lastAction = "正在关闭..."
                                val ok = withContext(Dispatchers.IO) { HotspotController.stopTethering(context) }
                                lastAction = if (ok) "✅ 已关闭" else "❌ 关闭失败"
                                refreshStatus()
                            }
                        }
                    )
                }

                // === 触发配置 ===
                item {
                    TriggerConfigCard(
                        targetSsid = targetSsid,
                        targetBt = targetBt,
                        autoStart = autoStart,
                        bgMonitor = bgMonitor,
                        onSsidChange = { targetSsid = it; settings.targetSsid = it },
                        onBtChange = { targetBt = it; settings.targetBluetooth = it },
                        onAutoStartChange = { autoStart = it; settings.autoStart = it },
                        onBgMonitorChange = {
                            bgMonitor = it
                            settings.bgMonitor = it
                            if (it) {
                                // 启动前台服务
                                HotspotForegroundService.start(context, targetSsid, targetBt)
                                lastAction = "✅ 后台监控已开启"
                            } else {
                                HotspotForegroundService.stop(context)
                                lastAction = "⬜ 后台监控已关闭"
                            }
                        }
                    )
                }

                // === WiFi 扫描 ===
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                wifiScanner.startScan()
                                isWifiScanning = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Wifi, null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (isWifiScanning) "扫描中..." else "WiFi")
                        }

                        Button(
                            onClick = {
                                btScanner.startScan()
                                isBtScanning = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            enabled = btScanner.isAvailable()
                        ) {
                            Icon(Icons.Default.Bluetooth, null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (isBtScanning) "扫描中..." else "蓝牙")
                        }
                    }
                }

                // WiFi 结果
                if (scanResults.isNotEmpty()) {
                    item { Text("📡 WiFi (${scanResults.size})", fontWeight = FontWeight.Bold) }
                    items(scanResults.sortedByDescending { it.level }) { wifi ->
                        WifiItem(wifi, wifi.ssid.equals(targetSsid, ignoreCase = true)) {
                            targetSsid = it; settings.targetSsid = it
                        }
                    }
                }

                // 蓝牙结果
                if (btResults.isNotEmpty()) {
                    item { Text("🔵 蓝牙 (${btResults.size})", fontWeight = FontWeight.Bold) }
                    items(btResults, key = { it.address }) { bt ->
                        BtItem(bt, bt.name.equals(targetBt, ignoreCase = true) || bt.name.contains(targetBt, ignoreCase = true)) {
                            targetBt = it.name; settings.targetBluetooth = it.name
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
fun StatusCard(statusInfo: HotspotController.StatusInfo, activity: Activity?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (statusInfo.mode != HotspotController.Mode.NONE)
                MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (statusInfo.mode != HotspotController.Mode.NONE) Icons.Default.CheckCircle else Icons.Default.Info,
                    null,
                    tint = if (statusInfo.mode != HotspotController.Mode.NONE) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(8.dp))
                Text("权限: ${statusInfo.mode.label}", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            PermissionRow("Root", statusInfo.rootAvailable)
            PermissionRow("Shizuku", statusInfo.shizukuAvailable, statusInfo.shizukuGranted)
            if (statusInfo.shizukuAvailable && !statusInfo.shizukuGranted) {
                Button(
                    onClick = { activity?.let { HotspotManagerShizuku.requestPermission(it) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("授权 Shizuku") }
            }
        }
    }
}

@Composable
fun PermissionRow(label: String, available: Boolean, granted: Boolean = true) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            when { !available -> "未检测到"; !granted -> "未授权"; else -> "已就绪" },
            color = if (available && granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
fun ControlCard(statusInfo: HotspotController.StatusInfo, hasPermission: Boolean, lastAction: String, onStart: () -> Unit, onStop: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(statusInfo.statusText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onStart, Modifier.weight(1f), RoundedCornerShape(12.dp), enabled = hasPermission) { Text("开启") }
                OutlinedButton(onStop, Modifier.weight(1f), RoundedCornerShape(12.dp), enabled = hasPermission) { Text("关闭") }
            }
            if (lastAction.isNotBlank()) {
                Text(lastAction, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun TriggerConfigCard(
    targetSsid: String, targetBt: String, autoStart: Boolean, bgMonitor: Boolean,
    onSsidChange: (String) -> Unit, onBtChange: (String) -> Unit,
    onAutoStartChange: (Boolean) -> Unit, onBgMonitorChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("🎯 触发配置", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                targetSsid, onSsidChange,
                label = { Text("目标 WiFi (SSID)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Wifi, null) },
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                targetBt, onBtChange,
                label = { Text("目标蓝牙设备名") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Bluetooth, null) },
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("如: CAR-Multimedia") }
            )
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("发现目标自动开热点", style = MaterialTheme.typography.bodySmall)
                Switch(autoStart, onAutoStartChange)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("后台持续监控 (前台服务)", style = MaterialTheme.typography.bodySmall)
                Switch(bgMonitor, onBgMonitorChange)
            }
        }
    }
}

@Composable
fun WifiItem(wifi: WifiScanner.WifiNetworkInfo, isTarget: Boolean, onClick: (String) -> Unit) {
    Surface(
        onClick = { onClick(wifi.ssid) },
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        color = if (isTarget) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Wifi, null, tint = if (isTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (wifi.ssid.isEmpty()) "<隐藏>" else wifi.ssid, fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Normal)
                Text("${wifi.level} dBm | ${if (wifi.frequency > 5000) "5G" else "2.4G"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun BtItem(bt: BluetoothScanner.BluetoothDeviceInfo, isTarget: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        color = if (isTarget) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bluetooth, null, tint = if (isTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(bt.name, fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Normal)
                Text("${bt.rssi} dBm | ${bt.address}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
