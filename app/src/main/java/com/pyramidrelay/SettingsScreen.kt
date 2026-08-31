package com.pyramidrelay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
    var relayNonSubscribed by remember { mutableStateOf(app.settingsStore.relayNonSubscribed) }
    var showPersistentNotification by remember { mutableStateOf(app.settingsStore.showPersistentNotification) }

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
                checked = relayNonSubscribed,
                onCheckedChange = { newValue ->
                    relayNonSubscribed = newValue
                    app.settingsStore.relayNonSubscribed = newValue
                }
            )
            Text(
                text = "Relay non-subscribed files",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
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
    }
}
