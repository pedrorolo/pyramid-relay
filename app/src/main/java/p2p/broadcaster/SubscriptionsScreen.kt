package p2p.broadcaster

import android.content.Intent
import android.net.Uri
import java.util.Base64
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
import androidx.compose.material.icons.filled.QrCode
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
import androidx.compose.ui.platform.LocalClipboardManager
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
    private val cryptoService: CryptoService,
    private val syncEngine: SyncEngine? = null
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
                // A fresh subscription must hear the next advertisement of this
                // file immediately: stale dedup/probe-cooldown state from a
                // previous subscription of the same file would swallow it.
                syncEngine?.clearDiscoveryStateForFile(fileId)
                EventLog.log("sub", "Subscribed to \"${name ?: fileId.takeLast(8)}\" - listening for new versions")
            } catch (e: Exception) {
                EventLog.log("sub", "Failed to subscribe to ${fileId.takeLast(8)}: ${e.message}")
            }
        }
    }

    fun deleteSubscription(subscription: SubscriptionEntity) {
        viewModelScope.launch {
            EventLog.log("sub", "Deleting subscription \"${subscription.fileName ?: subscription.fileId.takeLast(8)}\" (local v${subscription.localVersion})")
            syncEngine?.stopAdvertisingForFile(subscription.fileId)
            fileService.deleteAll(subscription.fileId)
            subscriptionDao.delete(subscription.fileId)
            broadcastDao.delete(subscription.fileId)
            EventLog.log("sub", "Deleted subscription ${subscription.fileId.takeLast(8)} - relay stopped, files removed")
        }
    }
}

@Composable
fun SubscriptionsScreen(initialFileId: String? = null, initialPk: String? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as P2PBroadcasterApp
    val viewModel = remember { SubscriptionsViewModel(app.subscriptionDao, app.broadcastDao, app.fileService, app.cryptoService, app.syncEngine) }
    val subscriptions by viewModel.subscriptions.collectAsState()
    var showPasteDialog by remember { mutableStateOf(false) }
    var showQrScan by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<SubscriptionEntity?>(null) }

    if (initialFileId != null && initialPk != null) {
        var added by remember { mutableStateOf(false) }
        if (!added) { viewModel.addSubscription(initialFileId, initialPk, null); added = true }
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (subscriptions.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No subscriptions yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Scan a QR code or paste a link to subscribe", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(subscriptions, key = { it.fileId }) { subscription ->
                        SubscriptionRow(
                            subscription = subscription,
                            onDelete = { showDeleteConfirm = subscription }
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPasteDialog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("Paste Link")
                }
                Button(onClick = { showQrScan = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("Scan QR")
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

    showDeleteConfirm?.let { subscription ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete subscription?") },
            text = { Text("Permanently delete \"${subscription.fileName ?: subscription.fileId.takeLast(8)}\"? This will stop relaying and remove all local files.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = null
                    viewModel.deleteSubscription(subscription)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun SubscriptionRow(subscription: SubscriptionEntity, onDelete: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as P2PBroadcasterApp
    var showQr by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destUri ->
        if (destUri != null && subscription.localVersion != null) {
            try {
                val source = app.fileService.getFile(subscription.fileId, subscription.localVersion)
                EventLog.log("app", "Saving subscription ${subscription.fileId} v${subscription.localVersion} from ${source.absolutePath} (${source.length()}B)")
                require(source.isFile && source.length() > 0) { "Downloaded file is missing or empty" }
                val output = context.contentResolver.openOutputStream(destUri)
                    ?: error("Cannot open destination")
                output.use { out -> source.inputStream().use { it.copyTo(out) } }
                EventLog.log("app", "File saved to ${destUri.lastPathSegment}")
            } catch (e: Exception) {
                EventLog.log("app", "Failed to save file: ${e.message}")
            }
        }
    }
    if (showQr) {
        QrDisplayDialog(
            fileId = subscription.fileId,
            pk = subscription.publicKey,
            name = subscription.fileName ?: "",
            version = subscription.localVersion ?: subscription.lastSeenVersion ?: 1,
            onDismiss = { showQr = false }
        )
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
                val clipboardManager = LocalClipboardManager.current
                IconButton(onClick = { showQr = true }) { Icon(Icons.Default.QrCode, contentDescription = "Share QR") }
                IconButton(onClick = {
                    val pkBytes = try {
                        Base64.getDecoder().decode(subscription.publicKey)
                    } catch (e: Exception) {
                        Base64.getUrlDecoder().decode(subscription.publicKey)
                    }
                    val pkUrl = Base64.getUrlEncoder().withoutPadding().encodeToString(pkBytes)
                    val nameEnc = java.net.URLEncoder.encode(subscription.fileName ?: "", "UTF-8")
                    val link = "p2pbroadcaster://subscribe?fileId=${subscription.fileId}&pk=$pkUrl&name=$nameEnc&v=${subscription.localVersion ?: subscription.lastSeenVersion ?: 1}"
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(link))
                    EventLog.log("app", "Link copied to clipboard")
                }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy Link") }
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
