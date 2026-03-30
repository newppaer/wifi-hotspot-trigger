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
import com.example.wifihotspot.service.HotspotController
import com.example.wifihotspot.service.HotspotManagerShizuku
import com.example.wifihotspot.service.WifiScanner
import com.example.wifihotspot.service.BluetoothScanner
import com.example.wifihotspot.ui.WifiHotspotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent {
            WifiHotspotTheme {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }
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
    var autoStart by remember { mutableStateOf(settings.autoStart) }
    var scanResults by remember { mutableStateOf<List<WifiScanner.WifiNetworkInfo>>(emptyList()) }
    var statusInfo by remember { mutableStateOf(HotspotController.getFullStatus(context)) }
    var isScanning by remember { mutableStateOf(false) }
    var lastAction by remember { mutableStateOf("") }

    val hasAnyPermission = statusInfo.mode != HotspotController.Mode.NONE

    fun refreshStatusAsync() {
        scope.launch {
            val freshStatus = withContext(Dispatchers.IO) {
                HotspotController.getFullStatus(context)
            }
            statusInfo = freshStatus
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val freshStatus = withContext(Dispatchers.IO) {
                HotspotController.getFullStatus(context)
            }
            statusInfo = freshStatus
            delay(3000)
        }
    }

    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            scanner.startScan()
            isScanning = true
        } else {
            Toast.makeText(context, "请授予必要的权限以扫描 WiFi", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        scanner.startListening { results ->
            scanResults = results
            isScanning = false
            if (autoStart && targetSsid.isNotBlank() && HotspotController.isAvailable()) {
                val inRange = results.any { it.ssid.equals(targetSsid, ignoreCase = true) }
                if (inRange && !statusInfo.hotspotEnabled) {
                    scope.launch {
                        withContext(Dispatchers.IO) { HotspotController.startTethering(context) }
                        refreshStatusAsync()
                    }
                }
            }
        }
        onDispose { scanner.stopListening() }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("WiFi 热点助手", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { refreshStatusAsync() }) { Icon(Icons.Default.Refresh, "刷新") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(
            Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), Color.Transparent))
        )) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }
                item { StatusCard(statusInfo, activity) }
                item {
                    ControlCard(
                        statusInfo = statusInfo,
                        hasPermission = hasAnyPermission,
                        lastAction = lastAction,
                        onStart = {
                            if (!hasAnyPermission) {
                                Toast.makeText(context, "请先授权", Toast.LENGTH_SHORT).show()
                            } else {
                                scope.launch {
                                    lastAction = "正在尝试开启..."
                                    val ok = withContext(Dispatchers.IO) { HotspotController.startTethering(context) }
                                    lastAction = if (ok) "✅ 开启成功" else "❌ 开启失败"
                                    refreshStatusAsync()
                                }
                            }
                        },
                        onStop = {
                            scope.launch {
                                lastAction = "正在尝试关闭..."
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
                        autoStart = autoStart,
                        onSsidChange = { targetSsid = it; settings.targetSsid = it },
                        onAutoStartChange = { autoStart = it; settings.autoStart = it }
                    )
                }
                item {
                    Button(
                        onClick = {
                            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                            } else {
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            }
                            
                            val allGranted = permissions.all { 
                                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
                            }
                            
                            if (allGranted) {
                                scanner.startScan()
                                isScanning = true
                            } else {
                                wifiPermissionLauncher.launch(permissions)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isScanning) "正在扫描..." else "扫描周围 WiFi")
                    }
                }
                items(
                    items = scanResults.sortedByDescending { it.level },
                    key = { it.bssid }
                ) { wifi ->
                    WifiItem(wifi, isTarget = wifi.ssid.equals(targetSsid, ignoreCase = true)) {
                        targetSsid = it
                        settings.targetSsid = it
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
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
                MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (statusInfo.mode != HotspotController.Mode.NONE) Icons.Default.CheckCircle else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (statusInfo.mode != HotspotController.Mode.NONE) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(8.dp))
                Text("权限状态: ${statusInfo.mode.label}", fontWeight = FontWeight.Bold)
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
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = when {
                !available -> "未检测到"
                !granted -> "未授权"
                else -> "已就绪"
            },
            color = if (available && granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
fun ControlCard(statusInfo: HotspotController.StatusInfo, hasPermission: Boolean, lastAction: String, onStart: () -> Unit, onStop: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(statusInfo.statusText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = hasPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                ) { Text("开启") }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = hasPermission
                ) { Text("关闭") }
            }
            if (lastAction.isNotBlank()) {
                Text(lastAction, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SettingsCard(targetSsid: String, autoStart: Boolean, onSsidChange: (String) -> Unit, onAutoStartChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("自动化配置", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = targetSsid,
                onValueChange = onSsidChange,
                label = { Text("目标 WiFi 名称 (SSID)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("感应到该 WiFi 时自动开启热点", style = MaterialTheme.typography.bodySmall)
                Switch(checked = autoStart, onCheckedChange = onAutoStartChange)
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
            Icon(Icons.Default.Wifi, null, tint = if (isTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (wifi.ssid.isEmpty()) "<隐藏网络>" else wifi.ssid, fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Normal)
                Text("${wifi.level} dBm", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
