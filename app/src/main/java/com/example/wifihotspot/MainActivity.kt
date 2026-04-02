package com.example.wifihotspot

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.example.wifihotspot.service.HotspotController
import com.example.wifihotspot.service.HotspotManagerNoRoot
import com.example.wifihotspot.service.HotspotManagerShizuku
import com.example.wifihotspot.service.WifiScanner
import com.example.wifihotspot.service.BluetoothScanner
import com.example.wifihotspot.service.MonitorService
import com.example.wifihotspot.ui.WifiHotspotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> recreate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent { WifiHotspotTheme { MainScreen() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }
}

/**
 * 统一开热点逻辑：
 * 1. 优先 Shizuku/Root (静默，无确认)
 * 2. 无权限 → 用系统 API (ConnectivityManager，首次弹确认框)
 * 3. 太旧系统 → 失败
 */
private suspend fun tryOpenHotspot(context: Context): Boolean = withContext(Dispatchers.IO) {
    when {
        HotspotController.isAvailable() -> HotspotController.startTethering(context)
        HotspotManagerNoRoot.isSystemApiAvailable() -> {
            var ok = false
            val latch = CountDownLatch(1)
            HotspotManagerNoRoot.startHotspot(
                context,
                onSuccess = { ok = true; latch.countDown() },
                onFail = { latch.countDown() }
            )
            latch.await(15, TimeUnit.SECONDS)
            ok
        }
        else -> false
    }
}

private fun startMonitorService(context: Context, ssid: String, bt: String) {
    val intent = Intent(context, MonitorService::class.java).apply {
        putExtra("ssid", ssid)
        putExtra("bt", bt)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
    else context.startService(intent)
}

private fun stopMonitorService(context: Context) {
    val intent = Intent(context, MonitorService::class.java).apply { action = "STOP" }
    try { context.startService(intent) } catch (_: Exception) {}
}

@Composable
private fun NotificationPermissionRequest(onGranted: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onGranted() }
    // 立即触发
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val settings = remember { SettingsManager(context) }
    val scanner = remember { WifiScanner(context) }
    val btScanner = remember { BluetoothScanner(context) }
    val scope = rememberCoroutineScope()

    var targetSsid by remember { mutableStateOf(settings.targetSsid) }
    var targetBt by remember { mutableStateOf(settings.targetBluetooth) }
    var autoStart by remember { mutableStateOf(settings.autoStart) }
    var monitorEnabled by remember { mutableStateOf(settings.monitorEnabled) }
    var scanResults by remember { mutableStateOf<List<WifiScanner.WifiNetworkInfo>>(emptyList()) }
    var btResults by remember { mutableStateOf(emptyList<BluetoothScanner.BluetoothDeviceInfo>()) }
    var statusInfo by remember { mutableStateOf(HotspotController.getFullStatus(context)) }
    var isScanning by remember { mutableStateOf(false) }
    var isBtScanning by remember { mutableStateOf(false) }
    var lastAction by remember { mutableStateOf("") }
    var monitorDetectedLabel by remember { mutableStateOf("") }
    var showMonitorRunning by remember { mutableStateOf(false) }
    // 用来追踪是否正在请求通知权限
    var requestingNotifPerm by remember { mutableStateOf(false) }

    val hasAnyPermission = statusInfo.mode != HotspotController.Mode.NONE
    val canUseSystemApi = HotspotManagerNoRoot.isSystemApiAvailable()

    fun refreshStatusAsync() {
        scope.launch {
            val s = withContext(Dispatchers.IO) { HotspotController.getFullStatus(context) }
            statusInfo = s
        }
    }

    LaunchedEffect(Unit) {
        while (true) { refreshStatusAsync(); delay(3000) }
    }

    // 广播接收 - MonitorService 发现目标
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val label = intent.getStringExtra(MonitorService.EXTRA_TARGET) ?: ""
                val type = intent.getStringExtra(MonitorService.EXTRA_TYPE) ?: ""
                scope.launch {
                    monitorDetectedLabel = label
                    lastAction = "发现 $label，正在开热点..."
                    val ok = tryOpenHotspot(context)
                    lastAction = if (ok) "✅ 热点已开启" else "❌ 自动开启失败"
                    if (ok) refreshStatusAsync()
                }
            }
        }
        val f = if (Build.VERSION.SDK_INT >= 33) {
            IntentFilter(MonitorService.ACTION_TARGET_DETECTED, Context.RECEIVER_NOT_EXPORTED)
        } else {
            IntentFilter(MonitorService.ACTION_TARGET_DETECTED)
        }
        context.registerReceiver(receiver, f)
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }

    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            val started = scanner.startScan()
            isScanning = started
            if (!started) Toast.makeText(context, "扫描失败，请检查定位", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "需要定位权限", Toast.LENGTH_SHORT).show()
        }
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            val started = btScanner.startScan()
            isBtScanning = started
            if (!started) Toast.makeText(context, "蓝牙扫描失败", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "需要蓝牙权限", Toast.LENGTH_SHORT).show()
        }
    }

    // WiFi 前台扫描 + 自动触发 (前台 UI 打开时)
    DisposableEffect(Unit) {
        scanner.startListening { results ->
            scanResults = results
            isScanning = false
            if (autoStart && targetSsid.isNotBlank() && !statusInfo.hotspotEnabled) {
                val found = results.any { it.ssid.equals(targetSsid, ignoreCase = true) }
                if (found) {
                    scope.launch {
                        monitorDetectedLabel = targetSsid
                        lastAction = "发现 $targetSsid，尝试开热点..."
                        val ok = tryOpenHotspot(context)
                        lastAction = if (ok) "✅ 热点已开启" else "❌ 自动失败"
                        if (ok) refreshStatusAsync()
                    }
                }
            }
        }
        onDispose { scanner.stopListening() }
    }

    DisposableEffect(Unit) {
        btScanner.startListening { results ->
            btResults = results
            isBtScanning = false
        }
        onDispose { btScanner.stopListening() }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("WiFi 热点助手", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { refreshStatusAsync() }) { Icon(Icons.Default.Refresh, "刷新") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)
            .background(Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    Color.Transparent
                )
            ))
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                item { StatusCard(statusInfo, activity) }
                item {
                    ControlCard(
                        statusInfo = statusInfo,
                        lastAction = lastAction,
                        monitorDetectedLabel = monitorDetectedLabel,
                        monitorRunning = showMonitorRunning,
                        canUseSystemApi = canUseSystemApi,
                        hasAnyPermission = hasAnyPermission,
                        onStart = {
                            when {
                                hasAnyPermission -> {
                                    scope.launch {
                                        lastAction = "正在开启..."
                                        val ok = withContext(Dispatchers.IO) {
                                            HotspotController.startTethering(context)
                                        }
                                        lastAction = if (ok) "✅ 开启成功" else "❌ 开启失败"
                                        refreshStatusAsync()
                                    }
                                }
                                canUseSystemApi -> {
                                    scope.launch {
                                        lastAction = "通过系统 API 开启..."
                                        HotspotManagerNoRoot.startHotspot(
                                            context,
                                            onSuccess = {
                                                scope.launch {
                                                    lastAction = "✅ 热点已开启 (系统 API)"
                                                    refreshStatusAsync()
                                                }
                                            },
                                            onFail = {
                                                scope.launch {
                                                    lastAction = "❌ 请手动开启"
                                                    HotspotManagerNoRoot.openHotspotSettings(context)
                                                }
                                            }
                                        )
                                    }
                                }
                                else -> Toast.makeText(context, "无可用权限", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onStop = {
                            scope.launch {
                                lastAction = "正在关闭..."
                                val ok = withContext(Dispatchers.IO) { HotspotController.stopTethering(context) }
                                lastAction = if (ok) "✅ 已关闭" else "❌ 关闭失败"
                                refreshStatusAsync()
                            }
                        }
                    )
                }
                item {
                    SettingsCard(
                        targetSsid = targetSsid,
                        targetBt = targetBt,
                        autoStart = autoStart,
                        monitorEnabled = monitorEnabled,
                        monitorRunning = showMonitorRunning,
                        onSsidChange = { targetSsid = it; settings.targetSsid = it },
                        onBtChange = { targetBt = it; settings.targetBluetooth = it },
                        onAutoStartChange = { autoStart = it; settings.autoStart = it },
                        onMonitorChange = { enabled ->
                            if (enabled) {
                                if (targetSsid.isEmpty() && targetBt.isEmpty()) {
                                    Toast.makeText(context, "请先设置目标 WiFi 或蓝牙",
                                        Toast.LENGTH_SHORT).show()
                                    return@SettingsCard
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.POST_NOTIFICATIONS
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!granted) {
                                        requestingNotifPerm = true
                                        // 通过 LaunchedEffect 触发权限请求
                                    } else {
                                        startMonitorService(context, targetSsid, targetBt)
                                        showMonitorRunning = true
                                    }
                                } else {
                                    startMonitorService(context, targetSsid, targetBt)
                                    showMonitorRunning = true
                                }
                            } else {
                                stopMonitorService(context)
                                showMonitorRunning = false
                            }
                            monitorEnabled = enabled
                            settings.monitorEnabled = enabled
                        }
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                                } else {
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION)
                                }
                                val ok = perms.all {
                                    ContextCompat.checkSelfPermission(context, it) ==
                                        PackageManager.PERMISSION_GRANTED
                                }
                                if (ok) {
                                    val started = scanner.startScan()
                                    isScanning = started
                                    if (!started) Toast.makeText(context, "扫描失败", Toast.LENGTH_SHORT).show()
                                } else wifiPermissionLauncher.launch(perms)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Wifi, null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (isScanning) "扫描中..." else "扫描 WiFi")
                        }
                        Button(
                            onClick = {
                                val btPerms = mutableListOf<String>()
                                if (Build.VERSION.SDK_INT >= 31) {
                                    btPerms += Manifest.permission.BLUETOOTH_SCAN
                                    btPerms += Manifest.permission.BLUETOOTH_CONNECT
                                }
                                val needed = btPerms.filter {
                                    ContextCompat.checkSelfPermission(context, it) !=
                                        PackageManager.PERMISSION_GRANTED
                                }
                                if (needed.isEmpty()) {
                                    val started = btScanner.startScan()
                                    isBtScanning = started
                                    if (!started) Toast.makeText(context, "蓝牙扫描失败", Toast.LENGTH_SHORT).show()
                                } else {
                                    btPermissionLauncher.launch(needed.toTypedArray())
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            enabled = btScanner.isAvailable()
                        ) {
                            Icon(Icons.Default.Bluetooth, null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (isBtScanning) "扫描中..." else "扫描蓝牙")
                        }
                    }
                }
                items(scanResults.sortedByDescending { it.level }, key = { it.bssid }) { wifi ->
                    WifiItem(wifi, isTarget = wifi.ssid.equals(targetSsid, ignoreCase = true)) {
                        targetSsid = it; settings.targetSsid = it
                    }
                }
                if (btResults.isNotEmpty()) {
                    item { Text("蓝牙 (${btResults.size})", fontWeight = FontWeight.Bold) }
                    items(btResults.size) { i ->
                        val bt = btResults[i]
                        val isTarget = bt.name.equals(targetBt, ignoreCase = true) ||
                            bt.name.contains(targetBt, ignoreCase = true)
                        Surface(
                            onClick = { targetBt = bt.name; settings.targetBluetooth = bt.name },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                            color = if (isTarget) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                        ) {
                            Row(modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Bluetooth, null,
                                    tint = if (isTarget) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(bt.name, fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Normal)
                                    Text("${bt.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // 通知权限请求弹窗
    if (requestingNotifPerm) {
        NotificationPermissionRequest {
            requestingNotifPerm = false
            startMonitorService(context, targetSsid, targetBt)
            showMonitorRunning = true
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
                    imageVector = if (statusInfo.mode != HotspotController.Mode.NONE)
                        Icons.Default.CheckCircle else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (statusInfo.mode == HotspotController.Mode.NONE)
                        MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
                )
                Spacer(Modifier.width(8.dp))
                Text("权限状态: ${statusInfo.mode.label}", fontWeight = FontWeight.Bold)
            }
            if (statusInfo.mode != HotspotController.Mode.NONE) {
                Text("✅ 可静默开热点",
                    color = Color(0xFF4CAF50),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 32.dp, top = 2.dp))
            } else {
                Text("📱 可用系统 API，首次弹窗确认",
                    color = Color(0xFFFFC107),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 32.dp, top = 2.dp))
            }
            Spacer(Modifier.height(12.dp))
            PermissionRow("Root 状态", statusInfo.rootAvailable)
            PermissionRow("Shizuku 状态", statusInfo.shizukuAvailable, statusInfo.shizukuGranted)
            if (statusInfo.shizukuAvailable && !statusInfo.shizukuGranted) {
                Button(
                    onClick = { activity?.let { HotspotManagerShizuku.requestPermission(it) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("点击授权 Shizuku") }
            }
        }
    }
}

@Composable
fun PermissionRow(label: String, available: Boolean, granted: Boolean = true) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = when { !available -> "未检测到"; !granted -> "未授权"; else -> "已就绪" },
            color = if (available && granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
fun ControlCard(
    statusInfo: HotspotController.StatusInfo,
    lastAction: String,
    monitorDetectedLabel: String,
    monitorRunning: Boolean,
    canUseSystemApi: Boolean,
    hasAnyPermission: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        Column(modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(statusInfo.statusText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold)
            if (monitorDetectedLabel.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("🎯 已发现: $monitorDetectedLabel",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (monitorRunning) {
                Spacer(Modifier.height(4.dp))
                Text("🔍 后台监控运行中",
                    color = Color(0xFF4CAF50),
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (canUseSystemApi && statusInfo.mode == HotspotController.Mode.NONE) {
                Spacer(Modifier.height(4.dp))
                Text("无 Shizuku/Root，将使用系统 API",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFC107))
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStart,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)) { Text("开启") }
                OutlinedButton(onClick = onStop,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)) { Text("关闭") }
            }
            if (lastAction.isNotBlank()) {
                Text(lastAction, modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SettingsCard(
    targetSsid: String, targetBt: String, autoStart: Boolean,
    monitorEnabled: Boolean, monitorRunning: Boolean,
    onSsidChange: (String) -> Unit,
    onBtChange: (String) -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    onMonitorChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("触发配置", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = targetSsid, onValueChange = onSsidChange,
                label = { Text("目标 WiFi (SSID)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = targetBt, onValueChange = onBtChange,
                label = { Text("目标蓝牙设备名") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
                placeholder = { Text("例: CAR-Multimedia") }
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("前台发现目标自动开热点", style = MaterialTheme.typography.bodySmall)
                Switch(checked = autoStart, onCheckedChange = onAutoStartChange)
            }
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("后台监控服务", fontWeight = FontWeight.Bold)
                    Text(
                        text = if (monitorRunning) "已启动，持续扫描中..." else "已停止",
                        color = if (monitorRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = monitorEnabled, onCheckedChange = onMonitorChange)
            }
        }
    }
}

@Composable
fun WifiItem(wifi: WifiScanner.WifiNetworkInfo, isTarget: Boolean, onClick: (String) -> Unit) {
    Surface(
        onClick = { onClick(wifi.ssid) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)),
        color = if (isTarget) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Wifi, null,
                tint = if (isTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (wifi.ssid.isEmpty()) "<隐藏网络>" else wifi.ssid,
                    fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Normal)
                Text("${wifi.level} dBm", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
