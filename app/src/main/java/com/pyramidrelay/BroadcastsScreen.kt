package com.pyramidrelay

import android.content.Intent
import android.net.Uri
import java.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.util.UUID
import kotlin.math.pow
import kotlin.math.sqrt

class BroadcastsViewModel(
    private val broadcastDao: BroadcastDao,
    private val cryptoService: CryptoService,
    private val fileService: FileService,
    private val syncEngine: SyncEngine? = null
) : ViewModel() {
    private val _broadcasts = MutableStateFlow<List<BroadcastEntity>>(emptyList())
    val broadcasts: StateFlow<List<BroadcastEntity>> = _broadcasts.asStateFlow()
    val relayingFileIds: StateFlow<Set<String>> get() = syncEngine?.activeStreamingFileIds ?: MutableStateFlow(emptySet())
    val downloadProgress: StateFlow<Map<String, Float>> get() = syncEngine?.downloadProgress ?: MutableStateFlow(emptyMap())
    val streamingProgress: StateFlow<Map<String, Float>> get() = syncEngine?.streamingProgress ?: MutableStateFlow(emptyMap())
    val currentAdvertisingFileId: StateFlow<String?> get() = syncEngine?.currentAdvertisingFileId ?: MutableStateFlow(null)
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
        viewModelScope.launch {
            broadcastDao.changeFlow.collect { refresh() }
        }
    }

    private suspend fun refresh() {
        _broadcasts.value = broadcastDao.getAll().filter { it.role == Role.ORIGINATOR }
        syncEngine?.updateFullRotationInterval()
    }

    fun importAndBroadcast(uri: Uri, context: android.content.Context, relayName: String?) {
        viewModelScope.launch {
            val fileId = UUID.randomUUID().toString()
            val keyPair = cryptoService.generateRsaKeyPair()
            val publicKeyStr = cryptoService.publicKeyToBase64(keyPair.public)
            val alias = "sk_$fileId"
            cryptoService.storeKeyPair(alias, keyPair)
            val fileName = fileService.getFileName(uri)
            val mimeType = fileService.getMimeType(uri)
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch
            val fileBytes = inputStream.readBytes()
            inputStream.close()
            val version = 1
            val vDir = fileService.getVersionDir(fileId, version); vDir.mkdirs()
            val file = fileService.getFile(fileId, version); file.writeBytes(fileBytes)
            val compressedFile = fileService.getCompressedFile(fileId, version)
            if (compressedFile.length() > SyncEngine.MAX_FILE_SIZE) {
                file.delete(); compressedFile.delete(); vDir.deleteRecursively()
                EventLog.log("adv", "Broadcast rejected: compressed ${compressedFile.length()}B exceeds ${SyncEngine.MAX_FILE_SIZE}B limit")
                _error.value = "Compressed+encrypted file too large (max ${SyncEngine.MAX_FILE_SIZE / 1024 / 1024} MB)"
                return@launch
            }
            val hashStr = Base64.getEncoder().encodeToString(cryptoService.sha256(fileBytes))
            val signatureStr = ""
            broadcastDao.upsert(
                BroadcastEntity(
                    fileId, fileName, relayName?.ifBlank { null }, mimeType, file.absolutePath, hashStr,
                    fileBytes.size.toLong(), compressedFile.length(), version, publicKeyStr, alias, signatureStr,
                    Role.ORIGINATOR, System.currentTimeMillis(), System.currentTimeMillis()
                )
            )
            syncEngine?.updateFullRotationInterval()
        }
    }

    fun deleteBroadcast(broadcast: BroadcastEntity) {
        viewModelScope.launch {
            syncEngine?.cancelTransfer(broadcast.fileId)
            syncEngine?.stopAdvertisingForFile(broadcast.fileId)
            fileService.deleteAll(broadcast.fileId)
            broadcastDao.delete(broadcast.fileId)
            syncEngine?.updateFullRotationInterval()
        }
    }

    fun updateBroadcast(broadcast: BroadcastEntity, uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            val inputStream = context.contentResolver.openInputStream(uri) ?: run {
                EventLog.log("adv", "updateBroadcast: CANCELLED - cannot open input stream")
                return@launch
            }
            val fileBytes = inputStream.readBytes()
            inputStream.close()
            val newFileName = fileService.getFileName(uri)
            createNewVersion(broadcast, fileBytes, newFileName)
        }
    }

    fun editBroadcastContent(broadcast: BroadcastEntity, content: String) {
        viewModelScope.launch {
            createNewVersion(broadcast, content.toByteArray(), broadcast.fileName)
        }
    }

    private suspend fun createNewVersion(broadcast: BroadcastEntity, fileBytes: ByteArray, newFileName: String) {
        EventLog.log("adv", "updateBroadcast: starting for \"${broadcast.fileName}\" (current v${broadcast.version})")
        val alias = broadcast.privateKeyAlias
        if (alias == null) {
            EventLog.log("adv", "updateBroadcast: CANCELLED - no private key alias (relay broadcast)")
            return
        }
        val newVersion = broadcast.version + 1
        EventLog.log("adv", "updateBroadcast: new version will be v$newVersion")
        EventLog.log("adv", "updateBroadcast: writing ${fileBytes.size} bytes (name=\"$newFileName\")")
        val vDir = fileService.getVersionDir(broadcast.fileId, newVersion); vDir.mkdirs()
        val file = fileService.getFile(broadcast.fileId, newVersion); file.writeBytes(fileBytes)
        val hashStr = Base64.getEncoder().encodeToString(cryptoService.sha256(fileBytes))
        val privateKey = cryptoService.getPrivateKey(alias)
        if (privateKey == null) {
            EventLog.log("adv", "updateBroadcast: CANCELLED - private key not found for alias $alias")
            return
        }
        val signatureStr = ""
        syncEngine?.stopAdvertisingForFile(broadcast.fileId)
        val effectiveFileName = newFileName.takeIf { it.isNotBlank() && it != "unknown" } ?: broadcast.fileName
        broadcastDao.updateVersion(broadcast.fileId, newVersion, hashStr, signatureStr, file.absolutePath, fileBytes.size.toLong(), fileBytes.size.toLong(), System.currentTimeMillis(), effectiveFileName)
        fileService.evictOldVersions(broadcast.fileId, newVersion)
        EventLog.log("adv", "updateBroadcast: DONE - \"$effectiveFileName\" updated to v$newVersion")
    }

    fun clearError() { _error.value = null }
}

@Composable
fun BroadcastsScreen(
    onShareQr: (fileId: String, pk: String, relayName: String?, fileName: String?, version: Int) -> Unit = { _, _, _, _, _ -> }
) {
    val context = LocalContext.current
    val app = context.applicationContext as P2PBroadcasterApp
    val viewModel = remember { BroadcastsViewModel(app.broadcastDao, app.cryptoService, app.fileService, app.syncEngine) }
    val broadcasts by viewModel.broadcasts.collectAsState()
    val activeStreamingFileIds by viewModel.relayingFileIds.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val streamingProgress by viewModel.streamingProgress.collectAsState()
    val currentAdvertisingFileId by viewModel.currentAdvertisingFileId.collectAsState()
    val bluetoothAvailable by app.syncEngine.isBluetoothAvailable.collectAsState()
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var showRelayNameDialog by remember { mutableStateOf(false) }
    var showSizeError by remember { mutableStateOf(false) }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            val size = cursor?.use { c ->
                val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIdx >= 0 && c.moveToFirst()) c.getLong(sizeIdx) else 0L
            } ?: 0L
            if (size > SyncEngine.MAX_FILE_SIZE) {
                showSizeError = true
            } else {
                pendingUri = uri
                showRelayNameDialog = true
            }
        }
    }
    val error by viewModel.error.collectAsState()
    LaunchedEffect(error) { if (error != null) showSizeError = true }
    if (showSizeError) {
        val errorMsg = error ?: "Maximum compressed+encrypted file size is ${SyncEngine.MAX_FILE_SIZE / 1024 / 1024} MB."
        AlertDialog(
            onDismissRequest = { showSizeError = false; viewModel.clearError() },
            title = { Text("File too large") },
            text = { Text(errorMsg) },
            confirmButton = { TextButton(onClick = { showSizeError = false; viewModel.clearError() }) { Text("OK") } }
        )
    }
    var updateTarget by remember { mutableStateOf<BroadcastEntity?>(null) }
    var showUpdateConfirm by remember { mutableStateOf<BroadcastEntity?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<BroadcastEntity?>(null) }
    var markdownFile by remember { mutableStateOf<Pair<String, String>?>(null) }
    var editTarget by remember { mutableStateOf<BroadcastEntity?>(null) }
    val updateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && updateTarget != null) {
            viewModel.updateBroadcast(updateTarget!!, uri, context)
            updateTarget = null
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { pickLauncher.launch(arrayOf("*/*")) }) {
                Icon(Icons.Default.Add, contentDescription = "Broadcast")
            }
        }
    ) { padding ->
        val clipboardManager = LocalClipboardManager.current
        if (broadcasts.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No broadcasts yet", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Tap + to pick a file and start broadcasting", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(broadcasts, key = { it.fileId }) { broadcast ->
                    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
                        uri?.let { saveUri ->
                            val source = app.fileService.getFile(broadcast.fileId, broadcast.version)
                            if (!source.isFile || source.length() == 0L) return@rememberLauncherForActivityResult
                            try {
                                context.contentResolver.openOutputStream(saveUri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                                EventLog.log("app", "Saved \"${broadcast.fileName}\" to $saveUri (${source.length()}B)")
                                // Auto-open the saved file
                                try {
                                    val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(saveUri, context.contentResolver.getType(saveUri) ?: "application/octet-stream")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(openIntent)
                                } catch (e: Exception) {
                                    EventLog.log("app", "No app to open file: ${e.message}")
                                }
                            } catch (e: Exception) {
                                EventLog.log("app", "Failed to save/open: ${e.message}")
                            }
                        }
                    }
                    fun saveAndOpen() {
                        val source = app.fileService.getFile(broadcast.fileId, broadcast.version)
                        if (!source.isFile || source.length() == 0L) return
                        if (getViewerFor(broadcast.fileName) != null) {
                            markdownFile = Pair(broadcast.fileName, source.absolutePath)
                        } else {
                            saveLauncher.launch(broadcast.fileName)
                        }
                    }
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        val filePath = remember(broadcast.fileId, broadcast.version) {
                            app.fileService.getFile(broadcast.fileId, broadcast.version).absolutePath
                        }
                        val isStreaming = activeStreamingFileIds.contains(broadcast.fileId)
                        val isAdvertising = currentAdvertisingFileId == broadcast.fileId && !isStreaming
                         val statusText = when {
                             !bluetoothAvailable -> "Offline"
                             isStreaming -> "Relaying"
                            isAdvertising -> "Advertising"
                            else -> "Idle"
                        }
                        // Log status for debugging
                        android.util.Log.d("BroadcastsScreen", "Status for ${broadcast.fileName}: $statusText (isStreaming=$isStreaming, isAdvertising=$isAdvertising)")

                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                broadcast.relayName?.let { Text("#$it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                                Text(broadcast.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.clickable { saveAndOpen() })
                                val compressedText = if (broadcast.compressedSize in 1 until broadcast.fileSize) " (compressed ${formatSize(broadcast.compressedSize)})" else ""
                                Text("v${broadcast.version} | ${formatSize(broadcast.fileSize)}$compressedText", style = MaterialTheme.typography.bodySmall)
                                 Text(statusText, style = MaterialTheme.typography.bodySmall, color = if (!bluetoothAvailable) androidx.compose.ui.graphics.Color(0xFFE53935) else if (isStreaming || isAdvertising) androidx.compose.ui.graphics.Color(0xFF4CAF50) else androidx.compose.ui.graphics.Color(0xFF9E9E9E))
                                if (isStreaming) {
                                    val relayProgress = streamingProgress[broadcast.fileId] ?: 0f
                                    if (relayProgress >= 0.99f || relayProgress == 0f) {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                    } else {
                                        LinearProgressIndicator(
                                            progress = { relayProgress },
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                        )
                                    }
                                 }
                                Spacer(modifier = Modifier.height(4.dp))
                                 val fileExt = broadcast.fileName.substringAfterLast('.', "").lowercase()
                                 val hasViewer = getViewerFor(broadcast.fileName) != null
                                 Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                     IconButton(onClick = {
                                         val pkBytes = try { Base64.getDecoder().decode(broadcast.publicKey) } catch (e: Exception) { Base64.getUrlDecoder().decode(broadcast.publicKey) }
                                         val pkUrl = Base64.getUrlEncoder().withoutPadding().encodeToString(pkBytes)
                                         onShareQr(broadcast.fileId, pkUrl, broadcast.relayName, broadcast.fileName, broadcast.version)
                                     }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.QrCode, contentDescription = "Share QR", modifier = Modifier.size(20.dp)) }
                                     IconButton(onClick = {
                                         val pkBytes = try { Base64.getDecoder().decode(broadcast.publicKey) } catch (e: Exception) { Base64.getUrlDecoder().decode(broadcast.publicKey) }
                                         val pkUrl = Base64.getUrlEncoder().withoutPadding().encodeToString(pkBytes)
                                          val relayParam = broadcast.relayName?.takeIf { it.isNotBlank() }?.let { "relayName=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""
                                          val fileParam = broadcast.fileName?.takeIf { it.isNotBlank() }?.let { "fileName=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""
                                         val params = listOf(relayParam, fileParam).filter { it.isNotEmpty() }.joinToString("&")
                                         val link = "pyramidrelay://subscribe?fileId=${broadcast.fileId}&pk=$pkUrl${if (params.isNotEmpty()) "&$params" else ""}&v=${broadcast.version}"
                                         val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                             type = "text/plain"
                                             putExtra(Intent.EXTRA_TEXT, link)
                                         }
                                         context.startActivity(Intent.createChooser(shareIntent, "Share link"))
                                     }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Share, contentDescription = "Share Link", modifier = Modifier.size(20.dp)) }
                                     IconButton(onClick = { showUpdateConfirm = broadcast }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Refresh, contentDescription = "Update", modifier = Modifier.size(20.dp)) }
                                     IconButton(onClick = { showDeleteConfirm = broadcast }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp)) }
                                     if (hasViewer) {
                                         val source = app.fileService.getFile(broadcast.fileId, broadcast.version)
                                         if (source.isFile && source.length() > 0L) {
                                             IconButton(onClick = { markdownFile = Pair(broadcast.fileName, source.absolutePath) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Visibility, contentDescription = "View", modifier = Modifier.size(20.dp)) }
                                         }
                                     }
                                     if (fileExt == "md" || fileExt == "txt") {
                                         IconButton(onClick = { editTarget = broadcast }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp)) }
                                     }
                                 }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            val isImage = remember(broadcast.fileId) {
                                val ext = broadcast.fileName.substringAfterLast('.', "").lowercase()
                                ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
                            }
                            val previewModifier = if (isImage) Modifier.size(72.dp) else Modifier.width(72.dp).fillMaxHeight()
                            FilePreview(filePath = filePath, fileName = broadcast.fileName, modifier = previewModifier, onClick = { saveAndOpen() })
                        }
                    }
                }
            }
        }
    }

    showUpdateConfirm?.let { broadcast ->
        AlertDialog(
            onDismissRequest = { showUpdateConfirm = null },
            title = { Text("Update broadcast?") },
            text = { Text("Replace \"${broadcast.fileName}\" with a new file? This will increment the version to v${broadcast.version + 1}.") },
            confirmButton = {
                TextButton(onClick = {
                    showUpdateConfirm = null
                    updateTarget = broadcast
                    updateLauncher.launch(arrayOf("*/*"))
                }) { Text("Update") }
            },
            dismissButton = { TextButton(onClick = { showUpdateConfirm = null }) { Text("Cancel") } }
        )
    }

    showDeleteConfirm?.let { broadcast ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete broadcast?") },
            text = { Text("Permanently delete \"${broadcast.fileName}\"? This will stop advertising and remove all local files.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = null
                    viewModel.deleteBroadcast(broadcast)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") } }
        )
    }

    markdownFile?.let { (title, path) ->
        FileViewerDialog(title = title, filePath = path, onDismiss = { markdownFile = null })
    }

    editTarget?.let { broadcast ->
        val isMarkdown = broadcast.fileName.substringAfterLast('.', "").lowercase() == "md"
        var editText by remember {
            mutableStateOf(
                try {
                    app.fileService.getFile(broadcast.fileId, broadcast.version).readText()
                } catch (e: Exception) {
                    ""
                }
            )
        }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Edit ${broadcast.fileName}") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp),
                    maxLines = Int.MAX_VALUE
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.editBroadcastContent(broadcast, editText)
                    editTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showRelayNameDialog) {
        var relayNameText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRelayNameDialog = false },
            title = { Text("Relay name") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(4.dp))
                    OutlinedTextField(
                        value = relayNameText,
                        onValueChange = { relayNameText = it },
                        label = { Text("e.g. my-relay") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showRelayNameDialog = false
                        pendingUri?.let { viewModel.importAndBroadcast(it, context, null) }
                        pendingUri = null
                    }) { Text("Skip") }
                    TextButton(onClick = {
                        showRelayNameDialog = false
                        pendingUri?.let { viewModel.importAndBroadcast(it, context, relayNameText) }
                        pendingUri = null
                    }) { Text("Continue") }
                }
            }
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
    if (bytes < 1024 * 1024 * 1024) return "${bytes / (1024 * 1024)} MB"
    return "${bytes / (1024 * 1024 * 1024)} GB"
}
