package p2p.broadcaster

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.BroadcastOnHome
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import p2p.broadcaster.ui.theme.P2PBroadcasterTheme
import p2p.broadcaster.EventLog

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

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
            if (grants.all { it.value }) startSyncService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissionsAndStart()
        setContent {
            P2PBroadcasterTheme {
                MainScreen(intent)
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startSyncService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startSyncService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, BleForegroundService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE foreground service", e)
            EventLog.log("app", "Failed to start BLE foreground service: ${e.message}")
        }
    }
}

@Composable
fun MainScreen(intent: Intent? = null) {
    val navController = rememberNavController()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val deepLinkFileId = intent?.data?.getQueryParameter("fileId")
    val deepLinkPk = intent?.data?.getQueryParameter("pk")

    LaunchedEffect(deepLinkFileId, deepLinkPk) {
        if (deepLinkFileId != null && deepLinkPk != null) {
            selectedTab = 1
            navController.navigate("subscriptions?fileId=$deepLinkFileId&pk=$deepLinkPk") {
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
                        navController.navigate("broadcasts") {
                            popUpTo("broadcasts") { inclusive = true }
                        }
                    },
                    icon = { Icon(Icons.Default.BroadcastOnHome, contentDescription = "Broadcasts") },
                    label = { Text("Broadcasts") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        navController.navigate("subscriptions") {
                            popUpTo("broadcasts")
                        }
                    },
                    icon = { Icon(Icons.Default.Subscriptions, contentDescription = "Subscriptions") },
                    label = { Text("Subscriptions") }
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
                    label = { Text("Log") }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "broadcasts",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("broadcasts") {
                BroadcastsScreen(
                    onShareQr = { fileId, pk, name, version ->
                        navController.navigate("qr_display?fileId=$fileId&pk=$pk&name=$name&v=$version")
                    }
                )
            }
            composable(
                "subscriptions?fileId={fileId}&pk={pk}",
                arguments = listOf(
                    navArgument("fileId") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("pk") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val fileId = backStackEntry.arguments?.getString("fileId")
                val pk = backStackEntry.arguments?.getString("pk")
                SubscriptionsScreen(
                    initialFileId = fileId,
                    initialPk = pk
                )
            }
            composable("log") {
                LogScreen()
            }
            composable(
                "qr_display?fileId={fileId}&pk={pk}&name={name}&v={v}",
                arguments = listOf(
                    navArgument("fileId") { type = NavType.StringType },
                    navArgument("pk") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType },
                    navArgument("v") { type = NavType.IntType }
                )
            ) { backStackEntry ->
                QrDisplayDialog(
                    fileId = backStackEntry.arguments?.getString("fileId") ?: "",
                    pk = backStackEntry.arguments?.getString("pk") ?: "",
                    name = backStackEntry.arguments?.getString("name") ?: "",
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
