package com.pyramidrelay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as P2PBroadcasterApp
    var showPersistentNotification by remember { mutableStateOf(app.settingsStore.showPersistentNotification) }
    var showLicenses by remember { mutableStateOf(false) }
    var showEula by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var showReadme by remember { mutableStateOf(false) }
    val licensesText = remember {
        try {
            context.assets.open("licenses.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Unable to load license information."
        }
    }
    val eulaText = remember {
        try {
            context.assets.open("eula.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Unable to load the end user license agreement."
        }
    }
    val privacyText = remember {
        try {
            context.assets.open("privacy_policy.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Unable to load the privacy policy."
        }
    }
    val licenseText = remember {
        try {
            context.assets.open("license.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Unable to load the license."
        }
    }
    val readmeText = remember {
        try {
            context.assets.open("readme.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Unable to load the README."
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleMedium
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Switch(
                checked = showPersistentNotification,
                onCheckedChange = { newValue ->
                    showPersistentNotification = newValue
                    app.settingsStore.showPersistentNotification = newValue
                }
            )
            Text(
                text = "Show persistent notification",
                style = MaterialTheme.typography.bodyMedium
            )
            if (!showPersistentNotification) {
                Text(
                    text = "Warning: Disabling this may cause the app to stop working properly. " +
                        "Android requires a visible notification for background services. " +
                        "The app may be killed by the system without it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        Text(
            text = "Documentation",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showPrivacy = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Privacy Policy",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showEula = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "End User License Agreement",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showLicense = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "License",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showLicenses = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Open Source Licenses",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showReadme = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "README",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Open Source Licenses") },
            text = {
                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = licensesText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) { Text("Close") }
            }
        )
    }

    if (showEula) {
        AlertDialog(
            onDismissRequest = { showEula = false },
            title = { Text("End User License Agreement") },
            text = {
                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = eulaText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showEula = false }) { Text("Close") }
            }
        )
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("Privacy Policy") },
            text = {
                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = privacyText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacy = false }) { Text("Close") }
            }
        )
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("License") },
            text = {
                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = licenseText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) { Text("Close") }
            }
        )
    }

    if (showReadme) {
        AlertDialog(
            onDismissRequest = { showReadme = false },
            title = { Text("README") },
            text = {
                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = readmeText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showReadme = false }) { Text("Close") }
            }
        )
    }
}
