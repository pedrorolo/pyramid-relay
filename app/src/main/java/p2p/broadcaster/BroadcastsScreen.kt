package p2p.broadcaster

import android.net.Uri
import java.util.Base64
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import java.util.UUID

class BroadcastsViewModel(
    private val broadcastDao: BroadcastDao,
    private val cryptoService: CryptoService,
    private val fileService: FileService
) : ViewModel() {
    private val _broadcasts = MutableStateFlow<List<BroadcastEntity>>(emptyList())
    val broadcasts: StateFlow<List<BroadcastEntity>> = _broadcasts.asStateFlow()

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
            fileService.deleteAll(broadcast.fileId)
            broadcastDao.delete(broadcast.fileId)
        }
    }
}

@Composable
fun BroadcastsScreen(
    onShareQr: (fileId: String, pk: String, name: String, version: Int) -> Unit = { _, _, _, _ -> }
) {
    val context = LocalContext.current
    val app = context.applicationContext as P2PBroadcasterApp
    val viewModel = remember { BroadcastsViewModel(app.broadcastDao, app.cryptoService, app.fileService) }
    val broadcasts by viewModel.broadcasts.collectAsState()
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.importAndBroadcast(it, context) }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { pickLauncher.launch(arrayOf("*/*")) }) {
                Icon(Icons.Default.Add, contentDescription = "Broadcast")
            }
        }
    ) { padding ->
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
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(broadcast.fileName, style = MaterialTheme.typography.titleMedium)
                            Text("v${broadcast.version} | ${formatSize(broadcast.fileSize)}", style = MaterialTheme.typography.bodySmall)
                            Text("Advertising", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row {
                                IconButton(onClick = {
                                    val pkBytes = try {
                                        Base64.getDecoder().decode(broadcast.publicKey)
                                    } catch (e: Exception) {
                                        Base64.getUrlDecoder().decode(broadcast.publicKey)
                                    }
                                    val pkUrl = Base64.getUrlEncoder().withoutPadding().encodeToString(pkBytes)
                                    onShareQr(broadcast.fileId, pkUrl, broadcast.fileName, broadcast.version)
                                }) { Icon(Icons.Default.QrCode, contentDescription = "Share QR") }
                                IconButton(onClick = { viewModel.deleteBroadcast(broadcast) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
    if (bytes < 1024 * 1024 * 1024) return "${bytes / (1024 * 1024)} MB"
    return "${bytes / (1024 * 1024 * 1024)} GB"
}
