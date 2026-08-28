package p2p.broadcaster

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
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

    init {
        viewModelScope.launch { refresh() }
        viewModelScope.launch {
            broadcastDao.changeFlow.collect { refresh() }
        }
    }

    private suspend fun refresh() {
        _broadcasts.value = broadcastDao.getAll().filter { it.role == Role.ORIGINATOR }
    }

    fun importAndBroadcast(uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            val fileSize = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex >= 0) cursor.getLong(sizeIndex) else null
            }
            if (fileSize != null && fileSize > MAX_FILE_SIZE) {
                val msg = "File too large: ${fileSize / 1024 / 1024}MB (max ${MAX_FILE_SIZE / 1024 / 1024}MB)"
                EventLog.log("app", msg)
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            val fileId = UUID.randomUUID().toString()
            val keyPair = cryptoService.generateEd25519KeyPair()
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
            val hashHex = cryptoService.sha256Hex(fileBytes)
            val hashStr = Base64.getEncoder().encodeToString(cryptoService.sha256(fileBytes))
            val msg = cryptoService.buildSignatureMessage(fileId, version, hashHex)
            val signature = cryptoService.sign(msg, keyPair.private)
            val signatureStr = Base64.getEncoder().encodeToString(signature)
            broadcastDao.upsert(
                BroadcastEntity(
                    fileId, fileName, mimeType, file.absolutePath, hashStr,
                    fileBytes.size.toLong(), version, publicKeyStr, alias, signatureStr,
                    Role.ORIGINATOR, System.currentTimeMillis(), System.currentTimeMillis()
                )
            )
        }
    }

    fun deleteBroadcast(broadcast: BroadcastEntity) {
        viewModelScope.launch {
            syncEngine?.stopAdvertisingForFile(broadcast.fileId)
            fileService.deleteAll(broadcast.fileId)
            broadcastDao.delete(broadcast.fileId)
        }
    }

    fun updateBroadcast(broadcast: BroadcastEntity, uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            EventLog.log("adv", "updateBroadcast: starting for \"${broadcast.fileName}\" (current v${broadcast.version})")
            val alias = broadcast.privateKeyAlias
            if (alias == null) {
                EventLog.log("adv", "updateBroadcast: CANCELLED - no private key alias (relay broadcast)")
                return@launch
            }
            val fileSize = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex >= 0) cursor.getLong(sizeIndex) else null
            }
            if (fileSize != null && fileSize > MAX_FILE_SIZE) {
                val msg = "File too large: ${fileSize / 1024 / 1024}MB (max ${MAX_FILE_SIZE / 1024 / 1024}MB)"
                EventLog.log("adv", "updateBroadcast: CANCELLED - $msg")
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            EventLog.log("adv", "updateBroadcast: alias=$alias")
            val newVersion = broadcast.version + 1
            EventLog.log("adv", "updateBroadcast: new version will be v$newVersion")
            val inputStream = context.contentResolver.openInputStream(uri) ?: run {
                EventLog.log("adv", "updateBroadcast: CANCELLED - cannot open input stream")
                return@launch
            }
            val fileBytes = inputStream.readBytes()
            inputStream.close()
            val newFileName = fileService.getFileName(uri)
            EventLog.log("adv", "updateBroadcast: read ${fileBytes.size} bytes from new file (name=\"$newFileName\")")
            val vDir = fileService.getVersionDir(broadcast.fileId, newVersion); vDir.mkdirs()
            val file = fileService.getFile(broadcast.fileId, newVersion); file.writeBytes(fileBytes)
            EventLog.log("adv", "updateBroadcast: wrote file to ${file.absolutePath}")
            val hashHex = cryptoService.sha256Hex(fileBytes)
            val hashStr = Base64.getEncoder().encodeToString(cryptoService.sha256(fileBytes))
            val msg = cryptoService.buildSignatureMessage(broadcast.fileId, newVersion, hashHex)
            val privateKey = cryptoService.getPrivateKey(alias)
            if (privateKey == null) {
                EventLog.log("adv", "updateBroadcast: CANCELLED - private key not found for alias $alias")
                return@launch
            }
            val signature = cryptoService.sign(msg, privateKey)
            val signatureStr = Base64.getEncoder().encodeToString(signature)
            EventLog.log("adv", "updateBroadcast: stopping old advertisement")
            syncEngine?.stopAdvertisingForFile(broadcast.fileId)
            val effectiveFileName = newFileName.takeIf { it.isNotBlank() && it != "unknown" } ?: broadcast.fileName
            if (effectiveFileName != broadcast.fileName) {
                EventLog.log("adv", "updateBroadcast: filename changed \"${broadcast.fileName}\" -> \"$effectiveFileName\"")
            }
            EventLog.log("adv", "updateBroadcast: updating DB version to v$newVersion")
            broadcastDao.updateVersion(broadcast.fileId, newVersion, hashStr, signatureStr, file.absolutePath, fileBytes.size.toLong(), System.currentTimeMillis(), effectiveFileName)
            EventLog.log("adv", "updateBroadcast: DB updated, evicting old versions")
            fileService.evictOldVersions(broadcast.fileId, newVersion)
            EventLog.log("adv", "updateBroadcast: DONE - \"$effectiveFileName\" updated to v$newVersion")
        }
    }
}

@Composable
fun BroadcastsScreen(
    onShareQr: (fileId: String, pk: String, name: String, version: Int) -> Unit = { _, _, _, _ -> }
) {
    val context = LocalContext.current
    val app = context.applicationContext as P2PBroadcasterApp
    val viewModel = remember { BroadcastsViewModel(app.broadcastDao, app.cryptoService, app.fileService, app.syncEngine) }
    val broadcasts by viewModel.broadcasts.collectAsState()
    val activeStreamingFileIds by viewModel.relayingFileIds.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val streamingProgress by viewModel.streamingProgress.collectAsState()
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.importAndBroadcast(it, context) }
    }
    var updateTarget by remember { mutableStateOf<BroadcastEntity?>(null) }
    var showUpdateConfirm by remember { mutableStateOf<BroadcastEntity?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<BroadcastEntity?>(null) }
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
                    fun saveAndOpen() {
                        try {
                            val source = app.fileService.getFile(broadcast.fileId, broadcast.version)
                            if (!source.isFile || source.length() == 0L) return
                            val fileName = broadcast.fileName
                            val resolver = context.contentResolver
                            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID), "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(fileName), null)?.use { c ->
                                if (c.moveToFirst()) {
                                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                                    resolver.delete(MediaStore.Downloads.getContentUri("external"), "${MediaStore.Downloads._ID}=?", arrayOf(id.toString()))
                                }
                            }
                            val values = android.content.ContentValues().apply {
                                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            }
                            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                            EventLog.log("app", "Saved \"$fileName\" to Downloads (${source.length()}B)")
                            try {
                                val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, resolver.getType(uri) ?: "application/octet-stream")
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
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        val filePath = remember(broadcast.fileId, broadcast.version) {
                            app.fileService.getFile(broadcast.fileId, broadcast.version).absolutePath
                        }
                        val isStreaming = activeStreamingFileIds.contains(broadcast.fileId)
                        val statusText = if (isStreaming) "Relaying" else "Advertising"
                        // Log status for debugging
                        android.util.Log.d("BroadcastsScreen", "Status for ${broadcast.fileName}: $statusText (isStreaming=$isStreaming)")

                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(broadcast.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.clickable { saveAndOpen() })
                                Text("v${broadcast.version} | ${formatSize(broadcast.fileSize)}", style = MaterialTheme.typography.bodySmall)
                                Text(statusText, style = MaterialTheme.typography.bodySmall, color = if (isStreaming) androidx.compose.ui.graphics.Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary)
                                if (isStreaming) {
                                    val relayProgress = streamingProgress[broadcast.fileId] ?: 0f
                                    if (relayProgress >= 0.99f || relayProgress == 0f) {
                                        // Indeterminate: waiting for receiver or no data yet
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                    } else {
                                        LinearProgressIndicator(
                                            progress = { relayProgress },
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                    IconButton(onClick = {
                                        val pkBytes = try { Base64.getDecoder().decode(broadcast.publicKey) } catch (e: Exception) { Base64.getUrlDecoder().decode(broadcast.publicKey) }
                                        val pkUrl = Base64.getUrlEncoder().withoutPadding().encodeToString(pkBytes)
                                        val nameEnc = java.net.URLEncoder.encode(broadcast.fileName, "UTF-8")
                                        val link = "p2pbroadcaster://subscribe?fileId=${broadcast.fileId}&pk=$pkUrl&name=$nameEnc&v=${broadcast.version}"
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, link)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share link"))
                                    }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Share, contentDescription = "Share Link", modifier = Modifier.size(20.dp)) }
                                    IconButton(onClick = {
                                        val pkBytes = try { Base64.getDecoder().decode(broadcast.publicKey) } catch (e: Exception) { Base64.getUrlDecoder().decode(broadcast.publicKey) }
                                        val pkUrl = Base64.getUrlEncoder().withoutPadding().encodeToString(pkBytes)
                                        onShareQr(broadcast.fileId, pkUrl, broadcast.fileName, broadcast.version)
                                    }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.QrCode, contentDescription = "Share QR", modifier = Modifier.size(20.dp)) }
                                    IconButton(onClick = { showUpdateConfirm = broadcast }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Refresh, contentDescription = "Update", modifier = Modifier.size(20.dp)) }
                                    IconButton(onClick = { showDeleteConfirm = broadcast }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp)) }
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
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
    if (bytes < 1024 * 1024 * 1024) return "${bytes / (1024 * 1024)} MB"
    return "${bytes / (1024 * 1024 * 1024)} GB"
}
