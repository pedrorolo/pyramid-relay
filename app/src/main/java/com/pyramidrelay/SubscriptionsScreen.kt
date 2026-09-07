package com.pyramidrelay

import android.content.Intent
import android.media.ThumbnailUtils
import android.net.Uri
import android.provider.MediaStore
import java.util.Base64
import kotlin.math.pow
import kotlin.math.sqrt
import com.pyramidrelay.EventLog
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
import androidx.compose.material.icons.filled.Visibility
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
    val streamingProgress: StateFlow<Map<String, Float>>
        get() = syncEngine?.streamingProgress ?: MutableStateFlow(
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
        syncEngine?.updateFullRotationInterval()
    }

    fun addSubscription(fileId: String, publicKeyBase64: String, relayName: String?, fileName: String? = null) {
        viewModelScope.launch {
            try {
                val publicKey = cryptoService.publicKeyFromBase64(publicKeyBase64)
                cryptoService.storeRecipientKey(publicKey, cryptoService.generateRsaKeyPair())
            } catch (e: Exception) {
                EventLog.log("sub", "Rejected subscription ${fileId.takeLast(8)} - invalid public key (${e.message})")
                return@launch
            }
            try {
                subscriptionDao.upsert(
                    SubscriptionEntity(
                        fileId, publicKeyBase64, fileName, relayName, null, null,
                        System.currentTimeMillis(), null, null, null
                    )
                )
                syncEngine?.clearDiscoveryStateForFile(fileId)
                syncEngine?.updateFullRotationInterval()
                EventLog.log("sub", "Subscribed to \"${relayName ?: fileName ?: fileId.takeLast(8)}\" - listening for new versions")
            } catch (e: Exception) {
                EventLog.log("sub", "Failed to subscribe to ${fileId.takeLast(8)}: ${e.message}")
            }
        }
    }

    fun deleteSubscription(subscription: SubscriptionEntity) {
        viewModelScope.launch {
            EventLog.log(
                "sub",
                "Deleting subscription \"${subscription.relayName ?: subscription.fileId.takeLast(8)}\" (local v${subscription.localVersion})"
            )
            syncEngine?.cancelTransfer(subscription.fileId)
            syncEngine?.clearDiscoveryStateForFile(subscription.fileId)
            syncEngine?.stopAdvertisingForFile(subscription.fileId)
            fileService.deleteAll(subscription.fileId)
            subscriptionDao.delete(subscription.fileId)
            broadcastDao.delete(subscription.fileId)
            syncEngine?.updateFullRotationInterval()
            EventLog.log(
                "sub",
                "Deleted subscription ${subscription.fileId.takeLast(8)} - relay stopped, files removed"
            )
        }
    }
}

@Composable
fun SubscriptionsScreen(
    initialFileId: String? = null,
    initialPk: String? = null,
    initialRelayName: String? = null,
    initialFileName: String? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as PyramidRelayApp
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
    val streamingProgress by viewModel.streamingProgress.collectAsState()
    val currentAdvertisingFileId by viewModel.currentAdvertisingFileId.collectAsState()
    EventLog.log("app", "SubscriptionsScreen: downloadingFileIds=$downloadingFileIds, downloadProgress=$downloadProgress")
    var showPasteDialog by remember { mutableStateOf(false) }
    var showQrScan by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<SubscriptionEntity?>(null) }

    if (initialFileId != null && initialPk != null) {
        var added by remember { mutableStateOf(false) }
        if (!added) {
            viewModel.addSubscription(initialFileId, initialPk, initialRelayName, initialFileName)
            added = true
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
                            progress = if (activeStreamingFileIds.contains(subscription.fileId)) streamingProgress[subscription.fileId] ?: 0f else downloadProgress[subscription.fileId] ?: 0f,
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
            onConfirm = { fileId, pk, relayName, fileName ->
                viewModel.addSubscription(fileId, pk, relayName, fileName)
                showPasteDialog = false
            }
        )
    }
    if (showQrScan) {
        QrScanDialog(
            onDismiss = { showQrScan = false },
            onScanned = { fileId, pk, relayName, fileName ->
                viewModel.addSubscription(fileId, pk, relayName, fileName)
                showQrScan = false
            }
        )
    }

    showDeleteConfirm?.let { subscription ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete subscription?") },
            text = { Text("Permanently delete \"${subscription.relayName?.takeIf { it.isNotBlank() } ?: subscription.fileName ?: subscription.fileId.takeLast(8)}\"? This will stop relaying and remove all local files.") },
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
    val app = context.applicationContext as PyramidRelayApp
    val bluetoothAvailable by app.syncEngine.isBluetoothAvailable.collectAsState()
    var showQr by remember { mutableStateOf(false) }
    var markdownFile by remember { mutableStateOf<Pair<String, String>?>(null) }

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
            relayName = subscription.relayName,
            fileName = subscription.fileName,
            version = subscription.localVersion ?: subscription.lastSeenVersion ?: 1,
            onDismiss = { showQr = false }
        )
    }
    markdownFile?.let { (title, path) ->
        FileViewerDialog(title = title, filePath = path, onDismiss = { markdownFile = null })
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
            !bluetoothAvailable -> "Offline"
            isDownloading && progress > 0f -> "Downloading"
            isDownloading -> "Handshaking"
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
        val localVersion = subscription.localVersion
        val saveOpen: (() -> Unit)? = if (localVersion != null) {
            val fileName = subscription.fileName ?: "file.bin"
            val source = app.fileService.getFile(subscription.fileId, localVersion)
            if (source.isFile && source.length() > 0L) {
                if (getViewerFor(fileName) != null) {
                    { markdownFile = Pair(fileName, source.absolutePath) }
                } else {
                    { saveLauncher.launch(fileName) }
                }
            } else null
        } else null

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                subscription.relayName?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "#$it",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
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
                         "Offline" -> androidx.compose.ui.graphics.Color(0xFFE53935)
                        "Downloading", "Relaying", "Advertising" -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
                        "Handshaking" -> androidx.compose.ui.graphics.Color(0xFFFF9800)
                        "Searching" -> androidx.compose.ui.graphics.Color(0xFFE53935)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (status == "Handshaking" || status == "Downloading" || status == "Relaying") {
                    if (status == "Downloading" && progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
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
                        val relayParam = subscription.relayName?.takeIf { it.isNotBlank() }?.let { "relayName=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""
                        val fileParam = subscription.fileName?.takeIf { it.isNotBlank() }?.let { "fileName=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""
                        val params = listOf(relayParam, fileParam).filter { it.isNotEmpty() }.joinToString("&")
                        val link =
                            "pyramidrelay://subscribe?fileId=${subscription.fileId}&pk=$pkUrl${if (params.isNotEmpty()) "&$params" else ""}&v=${subscription.localVersion ?: subscription.lastSeenVersion ?: 1}"
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
                    val subFileExt = subscription.fileName?.substringAfterLast('.', "")?.lowercase() ?: ""
                    val subHasViewer = subFileExt == "md" || subFileExt == "txt" || subFileExt == "html"
                    if (subHasViewer && subscription.localVersion != null) {
                        val subSource = app.fileService.getFile(subscription.fileId, subscription.localVersion!!)
                        if (subSource.isFile && subSource.length() > 0L) {
                            IconButton(onClick = { markdownFile = Pair(subscription.fileName ?: subscription.fileId.take(8), subSource.absolutePath) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Visibility, contentDescription = "View", modifier = Modifier.size(20.dp)) }
                        }
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
fun PasteLinkDialog(onDismiss: () -> Unit, onConfirm: (fileId: String, pk: String, relayName: String?, fileName: String?) -> Unit) {
    var linkText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste Link") },
        text = {
            OutlinedTextField(
                value = linkText,
                onValueChange = { linkText = it; error = null },
                label = { Text("pyramidrelay://subscribe?...") },
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
                    val relayName = uri.getQueryParameter("relayName")
                    val fileName = uri.getQueryParameter("fileName")
                    onConfirm(fileId, pk, relayName, fileName)
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
    val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: ""
    val isImage = ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    val isVideo = ext in listOf("mp4", "mkv", "webm", "3gp", "mov")
    val isDoc = ext in listOf("txt", "md", "markdown", "text", "html", "htm", "log", "csv", "json", "xml")
    val bitmap = remember(filePath) {
        when {
            isImage -> generateFileThumbnail(filePath, false)
            isVideo -> generateFileThumbnail(filePath, true)
            isDoc -> generateFileThumbnail(filePath, false)
            else -> null
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
        FilePlaceholder(fileName, clickModifier)
    }
}

private fun generateFileThumbnail(filePath: String, isVideo: Boolean): android.graphics.Bitmap? {
    return try {
        val kind = if (isVideo) MediaStore.Video.Thumbnails.MINI_KIND else MediaStore.Images.Thumbnails.MINI_KIND
        if (isVideo) ThumbnailUtils.createVideoThumbnail(filePath, kind)
        else ThumbnailUtils.createImageThumbnail(filePath, kind)
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun FilePlaceholder(fileName: String?, modifier: Modifier = Modifier) {
    val ext = (fileName?.substringAfterLast('.', "") ?: "").lowercase().takeIf { it.isNotBlank() } ?: ""
    val showExt = ext in listOf("txt", "md", "html")
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (showExt) {
            Text(
                ".$ext",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        } else {
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = "File",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
