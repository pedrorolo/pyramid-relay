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
import androidx.compose.material.icons.filled.Description
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
            Manifest.permission.ACCESS_COARSE_LOCATION to "Location (approximate, Android 8–11 only, for Bluetooth scanning)",
            Manifest.permission.BLUETOOTH_SCAN to "Bluetooth Scan",
            Manifest.permission.BLUETOOTH_CONNECT to "Bluetooth Connect",
            Manifest.permission.BLUETOOTH_ADVERTISE to "Bluetooth Advertise",
            Manifest.permission.POST_NOTIFICATIONS to "Notifications"
        )
    }

    private var showMissingPermsDialog = mutableStateOf(false)
    private var missingPermsMessage = mutableStateOf("")
    private var showDisclosureDialog = mutableStateOf(false)
    private var batteryOptimizationRequestLaunched = false
    private val activeIntent = mutableStateOf<Intent?>(null)

    private val requiredPermissions: Array<String>
        get() {
            // Location is NOT a feature: the app never determines or records
            // position. It is requested only on API <= 30, where Android 8-11
            // refuses BLE scans without a location permission. On API 31+,
            // BLUETOOTH_SCAN with neverForLocation suffices, so no location
            // permission is requested at all.
            val perms = mutableListOf<String>()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
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
                if (showDisclosureDialog.value) {
                    AlertDialog(
                        onDismissRequest = { finishAffinity() },
                        title = { Text("How Pyramid Relay uses your data") },
                        text = {
                            Text(
                                "Pyramid Relay shares files directly between nearby " +
                                    "devices over Bluetooth Low Energy — no servers, no " +
                                    "uploads.\n\n" +
                                    "• Bluetooth scan / advertise / connect: to find " +
                                    "nearby peers and transfer files you broadcast or " +
                                    "subscribe to, including in the background while " +
                                    "the foreground notification is shown.\n" +
                                    "• Approximate location (Android 8–11 only): " +
                                    "required by the OS for Bluetooth scanning on " +
                                    "those versions. Never requested on Android 12+, " +
                                    "and never used to determine your position.\n" +
                                    "• Camera: only to scan QR subscribe codes, " +
                                    "on-device.\n" +
                                    "• Notifications: to keep transfers running and " +
                                    "show progress.\n\n" +
                                    "Nearby devices can see a short beacon announcing " +
                                    "which files this device has or wants (file ID, " +
                                    "version, device ID) — but never file contents. " +
                                    "You stay in control: deleting a broadcast or " +
                                    "subscription stops advertising it."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                SettingsStore(this@MainActivity).dataDisclosureAccepted = true
                                showDisclosureDialog.value = false
                                requestPermissionsAndStart()
                            }) {
                                Text("Accept and continue")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { finishAffinity() }) {
                                Text("Decline and exit")
                            }
                        }
                    )
                }
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
        // Prominent disclosure must precede any runtime permission request.
        if (!SettingsStore(this).dataDisclosureAccepted) {
            showDisclosureDialog.value = true
            return
        }
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
        val intent = Intent(this, BleForegroundService::class.java)
        try {
            // Always a foreground service: background relay is the core feature
            // and Play foreground-service policy requires user-perceptible work.
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE service", e)
            EventLog.log("app", "Failed to start BLE service: ${e.message}")
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
        // Guidance-only flow: send the user to the system battery-optimization
        // settings list so they can exempt the app themselves. We deliberately
        // do NOT fire ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (restricted
        // under Play Device and Network Abuse policy).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open battery optimization settings", e)
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
                    icon = { Icon(Icons.Default.Description, contentDescription = "Docs") },
                    label = { Text("Docs", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
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
