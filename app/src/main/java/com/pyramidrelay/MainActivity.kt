package com.pyramidrelay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.State
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.BroadcastOnHome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import java.net.URLDecoder
import java.net.URLEncoder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pyramidrelay.ui.theme.P2PBroadcasterTheme
import com.pyramidrelay.EventLog

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private val REQUIRED_PERMISSION_NAMES = mapOf(
            Manifest.permission.ACCESS_FINE_LOCATION to "Location",
            Manifest.permission.BLUETOOTH_SCAN to "Bluetooth Scan",
            Manifest.permission.BLUETOOTH_CONNECT to "Bluetooth Connect",
            Manifest.permission.BLUETOOTH_ADVERTISE to "Bluetooth Advertise",
            Manifest.permission.POST_NOTIFICATIONS to "Notifications",
            Manifest.permission.NEARBY_WIFI_DEVICES to "Nearby WiFi Devices"
        )
    }

    private var showMissingPermsDialog = mutableStateOf(false)
    private var missingPermsMessage = mutableStateOf("")
    private var batteryOptimizationRequestLaunched = false
    private val activeIntent = mutableStateOf<Intent?>(null)

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
                perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            return perms.toTypedArray()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val denied = grants.filter { !it.value }.keys
            if (denied.isNotEmpty()) {
                showPermissionError(denied.toList())
            } else {
                startSyncService()
                requestBatteryOptimizationExemption()
            }
        }

    private fun showPermissionError(denied: List<String>) {
        val names = denied.map { REQUIRED_PERMISSION_NAMES[it] ?: it }
        missingPermsMessage.value = "The following permissions are required:\n\n${names.joinToString("\n") { "• $it" }}\n\nPlease grant them in Settings > Apps > Pyramid Relay > Permissions, then reopen the app."
        showMissingPermsDialog.value = true
        EventLog.log("app", "Permissions denied: ${denied.joinToString(", ")}")
        Log.w(TAG, "Permissions denied: $denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        activeIntent.value = intent
        requestPermissionsAndStart()
        setContent {
            P2PBroadcasterTheme {
                if (showMissingPermsDialog.value) {
                    AlertDialog(
                        onDismissRequest = { finishAffinity() },
                        title = { Text("Missing Permissions") },
                        text = { Text(missingPermsMessage.value) },
                        confirmButton = {
                            TextButton(onClick = { finishAffinity() }) {
                                Text("OK")
                            }
                        }
                    )
                }
                MainScreen(activeIntent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        activeIntent.value = intent
    }

    private fun requestPermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startSyncService()
            requestBatteryOptimizationExemption()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startSyncService() {
        val settingsStore = SettingsStore(this)
        if (!settingsStore.showPersistentNotification) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, BleForegroundService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE foreground service", e)
            EventLog.log("app", "Failed to start BLE foreground service: ${e.message}")
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (batteryOptimizationRequestLaunched || isBatteryOptimizationIgnored()) return
        batteryOptimizationRequestLaunched = true
        openBatteryOptimizationSettings()
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to open battery optimization settings", e2)
                }
            }
        }
    }
}

@Composable
fun MainScreen(intentState: State<Intent?>? = null) {
    val navController = rememberNavController()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val intent = intentState?.value
    val deepLinkFileId = intent?.data?.getQueryParameter("fileId")
    val deepLinkPk = intent?.data?.getQueryParameter("pk")
    val deepLinkRelayName = intent?.data?.getQueryParameter("relayName")
    val deepLinkFileName = intent?.data?.getQueryParameter("fileName")

    LaunchedEffect(deepLinkFileId, deepLinkPk, deepLinkRelayName, deepLinkFileName) {
        if (deepLinkFileId != null && deepLinkPk != null) {
            selectedTab = 1
            val relay = deepLinkRelayName?.let { "relayName=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
            val file = deepLinkFileName?.let { "fileName=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
            val params = listOf(relay, file).filter { it.isNotEmpty() }.joinToString("&")
            navController.navigate("subscriptions?fileId=$deepLinkFileId&pk=$deepLinkPk${if (params.isNotEmpty()) "&$params" else ""}") {
                popUpTo("broadcasts") { inclusive = true }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        navController.navigate("subscriptions") {
                            popUpTo("subscriptions") { inclusive = true }
                        }
                    },
                    icon = { Icon(Icons.Default.Subscriptions, contentDescription = "Subscriptions") },
                    label = { Text("Relays", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        navController.navigate("broadcasts") {
                            popUpTo("subscriptions")
                        }
                    },
                    icon = { Icon(Icons.Default.BroadcastOnHome, contentDescription = "Broadcasts") },
                    label = { Text("Broadcasts", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                        navController.navigate("log") {
                            popUpTo("broadcasts")
                        }
                    },
                    icon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Log") },
                    label = { Text("Log", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = {
                        selectedTab = 3
                        navController.navigate("settings") {
                            popUpTo("log")
                        }
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "subscriptions",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("broadcasts") {
                BroadcastsScreen(
                    onShareQr = { fileId, pk, relayName, fileName, version ->
                        val params = buildList {
                            relayName?.let { add("relayName=$it") }
                            fileName?.let { add("fileName=${java.net.URLEncoder.encode(it, "UTF-8")}") }
                        }.joinToString("&")
                        navController.navigate("qr_display?fileId=$fileId&pk=$pk${if (params.isNotEmpty()) "&$params" else ""}&v=$version")
                    }
                )
            }
            composable(
                "subscriptions?fileId={fileId}&pk={pk}&relayName={relayName}&fileName={fileName}",
                arguments = listOf(
                    navArgument("fileId") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("pk") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("relayName") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("fileName") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val fileId = backStackEntry.arguments?.getString("fileId")
                val pk = backStackEntry.arguments?.getString("pk")
                val relayName = backStackEntry.arguments?.getString("relayName")?.let { decodeParam(it) }
                val fileName = backStackEntry.arguments?.getString("fileName")?.let { decodeParam(it) }
                SubscriptionsScreen(
                    initialFileId = fileId,
                    initialPk = pk,
                    initialRelayName = relayName,
                    initialFileName = fileName
                )
            }
            composable("log") {
                LogScreen()
            }
            composable("settings") {
                SettingsScreen()
            }
            composable(
                "qr_display?fileId={fileId}&pk={pk}&relayName={relayName}&fileName={fileName}&v={v}",
                arguments = listOf(
                    navArgument("fileId") { type = NavType.StringType },
                    navArgument("pk") { type = NavType.StringType },
                    navArgument("relayName") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("fileName") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("v") { type = NavType.IntType }
                )
            ) { backStackEntry ->
                QrDisplayDialog(
                    fileId = backStackEntry.arguments?.getString("fileId") ?: "",
                    pk = backStackEntry.arguments?.getString("pk") ?: "",
                    relayName = backStackEntry.arguments?.getString("relayName"),
                    fileName = backStackEntry.arguments?.getString("fileName"),
                    version = backStackEntry.arguments?.getInt("v") ?: 1,
                    onDismiss = { navController.popBackStack() }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    P2PBroadcasterTheme {
        MainScreen()
    }
}

private fun decodeParam(value: String): String {
    return try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: Exception) {
        value
    }
}
