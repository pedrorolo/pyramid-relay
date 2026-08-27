package p2p.broadcaster

import android.content.Intent
import android.net.Uri
import p2p.broadcaster.EventLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SubscriptionsViewModel(
    private val subscriptionDao: SubscriptionDao,
    private val broadcastDao: BroadcastDao,
    private val fileService: FileService,
    private val cryptoService: CryptoService
) : ViewModel() {
    private val _subscriptions = MutableStateFlow<List<SubscriptionEntity>>(emptyList())
    val subscriptions: StateFlow<List<SubscriptionEntity>> = _subscriptions.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
        viewModelScope.launch { subscriptionDao.changeFlow.collect { refresh() } }
    }

    private suspend fun refresh() {
        _subscriptions.value = subscriptionDao.getAll()
    }

    fun addSubscription(fileId: String, publicKeyBase64: String, name: String?) {
        viewModelScope.launch {
            try {
                // Validate before persisting: a malformed key would poison every
                // later discovery pass (and previously crashed the app).
                cryptoService.publicKeyFromBase64(publicKeyBase64)
            } catch (e: Exception) {
                EventLog.log("sub", "Rejected subscription ${fileId.takeLast(8)} - invalid public key (${e.message})")
                return@launch
            }
            try {
                subscriptionDao.upsert(
                    SubscriptionEntity(
                        fileId, publicKeyBase64, name, null, null,
                        System.currentTimeMillis(), null, null, null
                    )
                )
                EventLog.log("sub", "Subscribed to \"${name ?: fileId.takeLast(8)}\" - listening for new versions")
            } catch (e: Exception) {
                EventLog.log("sub", "Failed to subscribe to ${fileId.takeLast(8)}: ${e.message}")
            }
        }
    }

    fun deleteSubscription(subscription: SubscriptionEntity) {
        viewModelScope.launch {
            fileService.deleteAll(subscription.fileId)
            subscriptionDao.delete(subscription.fileId)
            broadcastDao.delete(subscription.fileId)
        }
    }
}

@Composable
fun SubscriptionsScreen(initialFileId: String? = null, initialPk: String? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as P2PBroadcasterApp
    val viewModel = remember { SubscriptionsViewModel(app.subscriptionDao, app.broadcastDao, app.fileService, app.cryptoService) }
    val subscriptions by viewModel.subscriptions.collectAsState()
    var showPasteDialog by remember { mutableStateOf(false) }
    var showQrScan by remember { mutableStateOf(false) }

    if (initialFileId != null && initialPk != null) {
        var added by remember { mutableStateOf(false) }
        if (!added) { viewModel.addSubscription(initialFileId, initialPk, null); added = true }
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showQrScan = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("Scan QR")
                }
                OutlinedButton(onClick = { showPasteDialog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("Paste Link")
                }
            }
            if (subscriptions.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No subscriptions yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Scan a QR code or paste a link to subscribe", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(subscriptions, key = { it.fileId }) { subscription ->
                        SubscriptionRow(
                            subscription = subscription,
                            onDelete = { viewModel.deleteSubscription(subscription) }
                        )
                    }
                }
            }
        }
    }

    if (showPasteDialog) {
        PasteLinkDialog(
            onDismiss = { showPasteDialog = false },
            onConfirm = { fileId, pk, name ->
                viewModel.addSubscription(fileId, pk, name)
                showPasteDialog = false
            }
        )
    }
    if (showQrScan) {
        QrScanDialog(
            onDismiss = { showQrScan = false },
            onScanned = { fileId, pk, name ->
                viewModel.addSubscription(fileId, pk, name)
                showQrScan = false
            }
        )
    }
}

@Composable
fun SubscriptionRow(subscription: SubscriptionEntity, onDelete: () -> Unit) {
    val context = LocalContext.current
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destUri ->
        if (destUri != null && subscription.localUri != null) {
            try {
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    java.io.File(subscription.localUri).inputStream().use { it.copyTo(out) }
                }
                EventLog.log("app", "File saved to ${destUri.lastPathSegment}")
            } catch (e: Exception) {
                EventLog.log("app", "Failed to save file: ${e.message}")
            }
        }
    }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(subscription.fileName ?: subscription.fileId.take(8), style = MaterialTheme.typography.titleMedium)
            val versionText = if (subscription.localVersion != null) {
                "Local: v${subscription.localVersion}" +
                    (subscription.lastSeenVersion?.let { " | Seen: v$it" } ?: "")
            } else {
                "Not fetched" +
                    (subscription.lastSeenVersion?.let { " | Seen: v$it" } ?: "")
            }
            Text(versionText, style = MaterialTheme.typography.bodySmall)
            val status = when {
                subscription.localVersion == null && subscription.lastSeenVersion == null -> "Listening"
                subscription.localVersion == null -> "Downloading"
                else -> "Ready"
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                if (subscription.localUri != null) {
                    IconButton(onClick = {
                        val file = java.io.File(subscription.localUri)
                        if (file.exists()) {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.fromFile(file), "application/octet-stream")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        }
                    }) { Icon(Icons.Default.Visibility, contentDescription = "Open") }
                }
                if (subscription.localVersion != null) {
                    IconButton(onClick = {
                        saveLauncher.launch(subscription.fileName ?: "file.bin")
                    }) { Icon(Icons.Default.FileDownload, contentDescription = "Save") }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
        }
    }
}

@Composable
fun PasteLinkDialog(onDismiss: () -> Unit, onConfirm: (fileId: String, pk: String, name: String?) -> Unit) {
    var linkText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste Link") },
        text = {
            OutlinedTextField(
                value = linkText,
                onValueChange = { linkText = it; error = null },
                label = { Text("p2pbroadcaster://subscribe?...") },
                modifier = Modifier.fillMaxWidth(),
                isError = error != null,
                supportingText = error?.let { { Text(it) } }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val uri = Uri.parse(linkText)
                    val fileId = uri.getQueryParameter("fileId") ?: throw Exception("Missing fileId")
                    val pk = uri.getQueryParameter("pk") ?: throw Exception("Missing pk")
                    val name = uri.getQueryParameter("name")
                    onConfirm(fileId, pk, name)
                } catch (e: Exception) {
                    error = "Invalid link format"
                    EventLog.log("app", "Invalid paste link: ${e.message}")
                }
            }) { Text("Subscribe") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
