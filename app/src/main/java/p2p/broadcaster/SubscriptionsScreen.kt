package p2p.broadcaster

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import java.util.Base64
import kotlin.math.pow
import kotlin.math.sqrt
import p2p.broadcaster.EventLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
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
    val downloadingFileIds: StateFlow<Set<String>>
        get() = syncEngine?.downloadingFileIds ?: MutableStateFlow(emptySet())
    val activeStreamingFileIds: StateFlow<Set<String>>
        get() = syncEngine?.activeStreamingFileIds ?: MutableStateFlow(
            emptySet()
        )
    val downloadProgress: StateFlow<Map<String, Float>>
        get() = syncEngine?.downloadProgress ?: MutableStateFlow(
            emptyMap()
        )
    val currentAdvertisingFileId: StateFlow<String?>
        get() = syncEngine?.currentAdvertisingFileId ?: MutableStateFlow(null)

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
            EventLog.log(
                "sub",
                "Deleting subscription \"${subscription.fileName ?: subscription.fileId.takeLast(8)}\" (local v${subscription.localVersion})"
            )
            syncEngine?.cancelTransfer(subscription.fileId)
            syncEngine?.stopAdvertisingForFile(subscription.fileId)
            fileService.deleteAll(subscription.fileId)
            subscriptionDao.delete(subscription.fileId)
            broadcastDao.delete(subscription.fileId)
            EventLog.log(
                "sub",
                "Deleted subscription ${subscription.fileId.takeLast(8)} - relay stopped, files removed"
            )
        }
    }
}

@Composable
fun SubscriptionsScreen(initialFileId: String? = null, initialPk: String? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as P2PBroadcasterApp
    val viewModel = remember {
        SubscriptionsViewModel(
            app.subscriptionDao,
            app.broadcastDao,
            app.fileService,
            app.cryptoService,
            app.syncEngine
        )
    }
    val subscriptions by viewModel.subscriptions.collectAsState()
    val downloadingFileIds by viewModel.downloadingFileIds.collectAsState()
    val activeStreamingFileIds by viewModel.activeStreamingFileIds.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val currentAdvertisingFileId by viewModel.currentAdvertisingFileId.collectAsState()
    var showPasteDialog by remember { mutableStateOf(false) }
    var showQrScan by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<SubscriptionEntity?>(null) }

    if (initialFileId != null && initialPk != null) {
        var added by remember { mutableStateOf(false) }
        if (!added) {
            viewModel.addSubscription(initialFileId, initialPk, null); added = true
        }
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
                            isDownloading = downloadingFileIds.contains(subscription.fileId),
                            isStreaming = activeStreamingFileIds.contains(subscription.fileId),
                            isAdvertising = currentAdvertisingFileId == subscription.fileId,
                            progress = downloadProgress[subscription.fileId] ?: 0f,
                            onDelete = { showDeleteConfirm = subscription }
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPasteDialog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null)
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
fun SubscriptionRow(
    subscription: SubscriptionEntity,
    isDownloading: Boolean = false,
    isStreaming: Boolean = false,
    isAdvertising: Boolean = false,
    progress: Float = 0f,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as P2PBroadcasterApp
    var showQr by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (subscription.localVersion == null) return@rememberLauncherForActivityResult
        val source = app.fileService.getFile(subscription.fileId, subscription.localVersion)
        if (!source.isFile || source.length() == 0L) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
            EventLog.log("app", "Saved \"${subscription.fileName}\" to selected location (${source.length()}B)")
            // Auto-open the saved file
            try {
                val openIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, context.contentResolver.getType(uri) ?: "application/octet-stream")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(openIntent)
            } catch (e: Exception) {
                EventLog.log("app", "No app to open file: ${e.message}")
            }
        } catch (e: Exception) {
            EventLog.log("app", "Failed to save file: ${e.message}")
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
        val versionText = if (subscription.localVersion != null) {
            "Local: v${subscription.localVersion}" + (subscription.lastSeenVersion?.let { " | Seen: v$it" } ?: "")
        } else {
            (subscription.lastSeenVersion?.let { "Seen: v$it" } ?: "")
        }
        val versionColor =
            if (subscription.localVersion == null) androidx.compose.ui.graphics.Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface
        val status = when {
            isDownloading -> "Downloading"
            isStreaming -> "Relaying"
            isAdvertising && subscription.localVersion != null -> "Advertising"
            subscription.localVersion == null -> "Searching"
            else -> "Scanning for updates"
        }
        // Log status for debugging
        android.util.Log.d(
            "SubscriptionsScreen",
            "Status for ${subscription.fileName ?: subscription.fileId.take(8)}: $status (isDownloading=$isDownloading, isStreaming=$isStreaming, isAdvertising=$isAdvertising)"
        )
        val filePath = if (subscription.localVersion != null) remember(subscription.fileId, subscription.localVersion) {
            app.fileService.getFile(subscription.fileId, subscription.localVersion!!).absolutePath
        } else ""
        val saveOpen: (() -> Unit)? = if (subscription.localVersion != null) {
            {
                saveLauncher.launch(subscription.fileName ?: "file.bin")
            }
        } else null

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    subscription.fileName ?: subscription.fileId.take(8),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = if (saveOpen != null) Modifier.clickable { saveOpen() } else Modifier
                )
                Text(versionText, style = MaterialTheme.typography.bodySmall, color = versionColor)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (status) {
                        "Downloading", "Relaying", "Advertising" -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
                        "Searching" -> androidx.compose.ui.graphics.Color(0xFFE53935)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (status == "Downloading" || status == "Relaying") {
                    if (progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                } else if (status == "Advertising") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    IconButton(
                        onClick = { showQr = true },
                        modifier = Modifier.size(36.dp)
                    ) { Icon(Icons.Default.QrCode, contentDescription = "Share QR", modifier = Modifier.size(20.dp)) }
                    IconButton(onClick = {
                        val pkBytes = try {
                            Base64.getDecoder().decode(subscription.publicKey)
                        } catch (e: Exception) {
                            Base64.getUrlDecoder().decode(subscription.publicKey)
                        }
                        val pkUrl = Base64.getUrlEncoder().withoutPadding().encodeToString(pkBytes)
                        val nameEnc = java.net.URLEncoder.encode(subscription.fileName ?: "", "UTF-8")
                        val link =
                            "p2pbroadcaster://subscribe?fileId=${subscription.fileId}&pk=$pkUrl&name=$nameEnc&v=${subscription.localVersion ?: subscription.lastSeenVersion ?: 1}"
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, link)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share link"))
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share Link",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (subscription.localVersion != null) {
                        IconButton(
                            onClick = { saveLauncher.launch(subscription.fileName ?: "file.bin") },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = "Save",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            if (subscription.localVersion != null) {
                Spacer(modifier = Modifier.width(12.dp))
                val isImage = remember(subscription.fileId) {
                    val ext = subscription.fileName?.substringAfterLast('.', "")?.lowercase() ?: ""
                    ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
                }
                val previewModifier = if (isImage) Modifier.size(72.dp) else Modifier.width(72.dp).fillMaxHeight()
                FilePreview(
                    filePath = filePath,
                    fileName = subscription.fileName,
                    modifier = previewModifier,
                    onClick = saveOpen
                )
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

@Composable
fun FilePreview(filePath: String, fileName: String?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val clickModifier = if (onClick != null) modifier.clickable { onClick() } else modifier
    val isImage = remember(filePath) {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: ""
        ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    }
    if (isImage) {
        val bitmap = remember(filePath) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(filePath, opts)
            } catch (_: Exception) {
                null
            }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = fileName,
                contentScale = ContentScale.Crop,
                modifier = clickModifier.clip(RoundedCornerShape(6.dp))
            )
        } else {
            FilePlaceholder(clickModifier)
        }
    } else {
        FilePlaceholder(clickModifier)
    }
}

@Composable
private fun FilePlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            Icons.Default.InsertDriveFile,
            contentDescription = "File",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(32.dp)
        )
    }
}
