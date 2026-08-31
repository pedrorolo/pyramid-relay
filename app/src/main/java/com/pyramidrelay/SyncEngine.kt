package com.pyramidrelay

import java.util.Base64
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class SyncEngine(
    private val context: Context,
    private val broadcastDao: BroadcastDao,
    private val subscriptionDao: SubscriptionDao,
    private val cryptoService: CryptoService,
    private val fileService: FileService,
    private val bleCentralService: BleCentralService,
    private val blePeripheralService: BlePeripheralService,
    private val notificationService: NotificationService,
    private val transferSemaphore: kotlinx.coroutines.sync.Semaphore = kotlinx.coroutines.sync.Semaphore(1),
    testScope: CoroutineScope? = null
) {
    companion object {
        private const val TAG = "SyncEngine"
        private const val DEDUP_TTL_MS = 300_000L
        private const val PROBE_COOLDOWN_MS = 300_000L
        const val MAX_FILE_SIZE = 5L * 1024 * 1024
    }

    private val scope = testScope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var engineJob: Job? = null
    private val dedupCache = ConcurrentHashMap<String, Long>()
    // After successfully learning a peer's file/version info via a GATT meta read,
    // do not probe that device again for PROBE_COOLDOWN_MS.
    private val lastProbeAt = ConcurrentHashMap<String, Long>()
    private val advertisedFiles = ConcurrentHashMap.newKeySet<String>()
    private val peerLocks = ConcurrentHashMap<String, Mutex>()
    private val activeDownloadPeers = ConcurrentHashMap.newKeySet<String>()
    private val activeUploadPeers = ConcurrentHashMap.newKeySet<String>()
    var onFileReceived: ((fileId: String, version: Int) -> Unit)? = null

    private fun peerLock(address: String): Mutex = peerLocks.getOrPut(address) { Mutex() }

    private val _downloadingFileIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingFileIds: StateFlow<Set<String>> = _downloadingFileIds.asStateFlow()
    private val activeDownloadJobs = ConcurrentHashMap<String, Job>()
    private val downloadingFileDeviceMap = ConcurrentHashMap<String, String>()
    private val downloadMutex = Mutex()
    private val downloadSemaphore = Semaphore(1) // Only one download at a time
    private val uploadSemaphore = Semaphore(1) // Only one upload at a time
    private val downloadRetryCount = ConcurrentHashMap<String, Int>()
    private val maxRetries = 3

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    val activeStreamingFileIds: StateFlow<Set<String>> get() = blePeripheralService.activeStreamingFileIds
    val streamingProgress: StateFlow<Map<String, Float>> get() = blePeripheralService.streamingProgress
    val currentAdvertisingFileId: StateFlow<String?> get() = blePeripheralService.currentAdvertisingFileId

    private val _isBluetoothAvailable = MutableStateFlow(
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter?.isEnabled == true
    )
    val isBluetoothAvailable: StateFlow<Boolean> = _isBluetoothAvailable.asStateFlow()

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    _isBluetoothAvailable.value = false
                    EventLog.log("sync", "Bluetooth OFF - cancelling transfers and stopping BLE")
                    activeDownloadJobs.keys.toList().forEach { cancelTransfer(it) }
                    bleCentralService.stopScan()
                    blePeripheralService.stopAllAdvertising()
                }
                BluetoothAdapter.STATE_ON -> {
                    _isBluetoothAvailable.value = true
                    EventLog.log("sync", "Bluetooth ON - restarting BLE operations")
                    scope.launch {
                        try {
                            blePeripheralService.startGattServer()
                            bleCentralService.startScan()
                            broadcastDao.getAll().forEach { b ->
                                try { startAdvertising(b) } catch (e: Exception) {
                                    EventLog.log("ble", "restart advertising failed for ${b.fileId.takeLast(8)}: ${e.message}")
                                }
                            }
                            EventLog.log("sync", "BLE operations restarted after Bluetooth ON")
                        } catch (e: Exception) {
                            EventLog.log("sync", "Failed to restart BLE after Bluetooth ON: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    fun start() {
        if (engineJob?.isActive == true) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        androidx.core.content.ContextCompat.registerReceiver(context, bluetoothStateReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        engineJob = scope.launch {
            Log.d(TAG, "SyncEngine started")
            EventLog.log("sync", "Engine started - scanning, advertising and transfer server active")
            blePeripheralService.setMetaPayloadProvider { fileId -> buildMetaPayload(fileId) }
            // GATT streaming serves the encrypted envelope, which relays can forward unchanged.
            blePeripheralService.setServeFileLoader { fileId, version ->
                kotlinx.coroutines.runBlocking {
                try {
                    val broadcast = broadcastDao.getById(fileId)
                    if (broadcast == null) {
                        null
                    } else {
                        val encrypted = File(fileService.getVersionDir(fileId, version), "file.encrypted")
                        if (encrypted.exists()) {
                            EventLog.log("ble", "Serving persisted encrypted envelope for ${fileId.takeLast(8)} v$version (${encrypted.length()}B)")
                        } else {
                            val compressed = fileService.getCompressedFile(fileId, version).readBytes()
                            val privateKey = broadcast.privateKeyAlias?.let { cryptoService.getPrivateKey(it) }
                                ?: throw IllegalStateException("No private key for originator payload")
                            encrypted.writeBytes(cryptoService.encryptCompressed(compressed, privateKey))
                            EventLog.log("ble", "Created encrypted envelope for originator ${fileId.takeLast(8)} v$version (${encrypted.length()}B)")
                        }
                        encrypted.readBytes()
                    }
                } catch (e: Exception) {
                    EventLog.log("ble", "Failed to get compressed file for streaming: ${e.message}")
                    null
                }
                }
            }
            bleCentralService.onDeviceDiscovered = { address, serviceData ->
                scope.launch { handleDiscoveredDevice(address, serviceData) }
            }
            blePeripheralService.isPeerTransferAllowed = { address -> !activeDownloadPeers.contains(address) }
            blePeripheralService.onUploadStart = { address -> activeUploadPeers.add(address) }
            blePeripheralService.onUploadEnd = { address -> activeUploadPeers.remove(address) }
            blePeripheralService.onTransferStart = { stopAdvertisingAndScanning() }
            blePeripheralService.onTransferEnd = {
                EventLog.log("sync", "onTransferEnd fired, resuming advertising/scanning")
                resumeAdvertisingAndScanning()
            }
            blePeripheralService.onStreamArmed = { stopAdvertisingAndScanning() }
            // Retry GATT server if initial attempt fails (permissions may not be ready yet after fresh install)
            for (attempt in 1..5) {
                try { blePeripheralService.startGattServer(); break } catch (e: Exception) {
                    Log.e(TAG, "startGattServer failed (attempt $attempt/5)", e)
                    EventLog.log("ble", "startGattServer failed (attempt $attempt/5): ${e.message}")
                    kotlinx.coroutines.delay(2000L)
                }
            }
            // Initial load: advertise all existing broadcasts and listen for subscriptions
            try {
                val broadcasts = broadcastDao.getAll()
                for (b in broadcasts) {
                    try {
                        startAdvertising(b)
                    } catch (e: Exception) { Log.e(TAG, "startAdvertising failed", e); EventLog.log("ble", "startAdvertising failed: ${e.message}") }
                }
                EventLog.log("adv", "Loaded ${broadcasts.size} existing broadcasts")
            } catch (e: Exception) { Log.e(TAG, "Initial broadcast load failed", e) }
            // Watch for future changes
            launch {
                while (true) {
                    broadcastDao.changeFlow.collect {
                        val broadcasts = broadcastDao.getAll()
                        val currentIds = broadcasts.map { it.fileId }.toSet()
                        // Stop advertising for deleted broadcasts
                        for (id in advertisedFiles.toList()) {
                            if (id !in currentIds) {
                                try { blePeripheralService.stopAdvertising(id) } catch (_: Exception) {}
                                advertisedFiles.remove(id)
                                EventLog.log("adv", "Stopped advertising deleted broadcast $id")
                            }
                        }
                        // Start advertising for current broadcasts (skip those already advertised)
                        for (b in broadcasts) {
                            if (b.fileId !in advertisedFiles) {
                                try { startAdvertising(b) } catch (e: Exception) { Log.e(TAG, "startAdvertising failed", e); EventLog.log("ble", "startAdvertising failed: ${e.message}") }
                            }
                        }
                    }
                }
            }
            // Retry scan if initial attempt fails (permissions may not be ready yet after fresh install)
            for (attempt in 1..5) {
                try { bleCentralService.startScan(); break } catch (e: Exception) {
                    Log.e(TAG, "startScan failed (attempt $attempt/5)", e)
                    EventLog.log("ble", "startScan failed (attempt $attempt/5): ${e.message}")
                    kotlinx.coroutines.delay(2000L)
                }
            }
            // Periodic scan restart to fix Samsung BLE stack dropping service data
            launch {
                while (true) {
                    kotlinx.coroutines.delay(300_000L)
                    if (_downloadingFileIds.value.isNotEmpty() || activeUploadPeers.isNotEmpty()) {
                        EventLog.log("ble", "Periodic scan restart skipped (transfer in progress)")
                        continue
                    }
                    try {
                        bleCentralService.stopScan()
                        kotlinx.coroutines.delay(500L)
                        bleCentralService.startScan()
                        EventLog.log("ble", "Scan restarted (periodic)")
                    } catch (e: Exception) {
                        EventLog.log("ble", "Periodic scan restart failed: ${e.message}")
                    }
                }
            }
            // Periodic GATT server restart to fix META characteristic not found
            launch {
                while (true) {
                    kotlinx.coroutines.delay(600_000L)
                    if (_downloadingFileIds.value.isNotEmpty() || activeUploadPeers.isNotEmpty()) {
                        EventLog.log("ble", "Periodic GATT server restart skipped (transfer in progress)")
                        continue
                    }
                    try {
                        blePeripheralService.restartGattServer()
                        EventLog.log("ble", "GATT server restarted (periodic)")
                    } catch (e: Exception) {
                        EventLog.log("ble", "Periodic GATT server restart failed: ${e.message}")
                    }
                }
            }
        }
    }

    fun stop() {
        engineJob?.cancel(); engineJob = null
        try { context.unregisterReceiver(bluetoothStateReceiver) } catch (_: Exception) {}
        bleCentralService.stopScan()
        blePeripheralService.stopAllAdvertising()
    }

    fun stopAdvertisingAndScanning() {
        bleCentralService.stopScan()
        blePeripheralService.stopAllAdvertising()
        EventLog.log("sync", "Stopped advertising and scanning for transfer")
    }

    fun resumeAdvertisingAndScanning() {
        EventLog.log("sync", "resumeAdvertisingAndScanning called, scope active=${scope.coroutineContext[kotlinx.coroutines.Job]?.isActive}")
        scope.launch {
            try {
                blePeripheralService.restartGattServer()
                bleCentralService.startScan()
                val broadcasts = broadcastDao.getAll()
                for (b in broadcasts) {
                    try {
                        startAdvertising(b)
                    } catch (e: Exception) {
                        EventLog.log("ble", "resumeAdvertising failed: ${e.message}")
                    }
                }
                EventLog.log("sync", "Resumed advertising and scanning after transfer")
            } catch (e: Exception) {
                EventLog.log("sync", "resumeAdvertisingAndScanning failed: ${e.message}")
            }
        }
    }

    suspend fun buildMetaPayload(fileId: String): BleMetaPayload? {
        val broadcast = broadcastDao.getById(fileId) ?: return null
        val fileIdBytes = uuidToBytes(fileId) ?: return null
        val hashBytes = Base64.getDecoder().decode(broadcast.fileHash)
        val encrypted = File(fileService.getVersionDir(fileId, broadcast.version), "file.encrypted")
        if (!encrypted.exists()) {
            val privateKey = broadcast.privateKeyAlias?.let { cryptoService.getPrivateKey(it) }
                ?: return null
            val compressed = fileService.getCompressedFile(fileId, broadcast.version).readBytes()
            encrypted.parentFile?.mkdirs()
            encrypted.writeBytes(cryptoService.encryptCompressed(compressed, privateKey))
        }
        return BleMetaPayload(fileIdBytes, broadcast.version, hashBytes, encrypted.length(), broadcast.fileName)
    }

    suspend fun startAdvertising(broadcast: BroadcastEntity) {
        val serviceData = ByteArray(14)
        cryptoService.fileIdHash(broadcast.fileId).copyInto(serviceData, 0)
        serviceData[6] = ((broadcast.version ushr 24) and 0xFF).toByte()
        serviceData[7] = ((broadcast.version ushr 16) and 0xFF).toByte()
        serviceData[8] = ((broadcast.version ushr 8) and 0xFF).toByte()
        serviceData[9] = (broadcast.version and 0xFF).toByte()
        cryptoService.keyId(broadcast.publicKey).copyInto(serviceData, 10)
        // Ensure compressed file is cached before advertising
        if (broadcast.compressedSize == 0L || broadcast.compressedSize == broadcast.fileSize) {
            try {
                val compressedSize = fileService.getCompressedSize(broadcast.fileId, broadcast.version)
                broadcastDao.updateVersion(broadcast.fileId, broadcast.version, broadcast.fileHash, broadcast.signature, broadcast.internalUri, broadcast.fileSize, compressedSize, System.currentTimeMillis())
                EventLog.log("adv", "Compressed ${broadcast.fileId.takeLast(8)}: ${broadcast.fileSize}B -> ${compressedSize}B")
            } catch (e: Exception) {
                EventLog.log("adv", "Compression failed for ${broadcast.fileId.takeLast(8)}: ${e.message}")
            }
        }
        blePeripheralService.startAdvertising(broadcast.fileId, serviceData)
        advertisedFiles.add(broadcast.fileId)
        EventLog.log("adv", "Advertising \"${broadcast.fileName}\" v${broadcast.version} (${broadcast.fileSize}B, compressed ${broadcast.compressedSize}B)")
    }

    /**
     * Handles one BLE discovery event.
     * Returns true iff the advertisement was recognized and the receive pipeline
     * completed successfully (meta verified AND transfer accepted).
     */
    internal suspend fun handleDiscoveredDevice(deviceAddress: String, serviceData: ByteArray): Boolean {
        if (serviceData.size < 14) { EventLog.log("scan", "Advertisement too short (${serviceData.size}B) - dropped"); return false }
        val fileIdHash = serviceData.copyOfRange(0, 6)
        val version = ((serviceData[6].toInt() and 0xFF) shl 24) or ((serviceData[7].toInt() and 0xFF) shl 16) or ((serviceData[8].toInt() and 0xFF) shl 8) or (serviceData[9].toInt() and 0xFF)
        val keyId = serviceData.copyOfRange(10, 14)
        val dedupKey = "${fileIdHash.joinToString("") { "%02x".format(it) }}:$version"
        val now = System.currentTimeMillis()
        dedupCache[dedupKey]?.let { if (now - it < DEDUP_TTL_MS) { return false } }
        dedupCache[dedupKey] = now

        // Per-device+file probe cooldown: once we have learned a peer's file/version
        // info, don't probe that device again for the same file for PROBE_COOLDOWN_MS.
        val probeKey = "$deviceAddress:$dedupKey"
        lastProbeAt[probeKey]?.let {
            if (now - it < PROBE_COOLDOWN_MS) {
                EventLog.log("scan", "Probe cooldown: ${deviceAddress.takeLast(5)} probed for file ${dedupKey.take(12)}... ${"%.1f".format((now - it) / 1000.0)}s ago (< ${PROBE_COOLDOWN_MS / 60000}min) - skipping")
                return false
            }
        }

        val broadcasts = broadcastDao.getAll()
        val selfMatch = broadcasts.firstOrNull { b -> cryptoService.fileIdHash(b.fileId).contentEquals(fileIdHash) }
        if (selfMatch != null) {
            val isSameKey = cryptoService.keyId(selfMatch.publicKey).contentEquals(keyId)
            if (isSameKey) {
                if (version <= selfMatch.version) {
                    // Same file + same key + not newer: check if we have a subscription too.
                    val sub = subscriptionDao.getById(selfMatch.fileId)
                    if (sub != null && (sub.localVersion ?: 0) < version) {
                        EventLog.log("scan", "\"${selfMatch.fileName}\" self-match but subscription needs v$version (local v${sub.localVersion})")
                        return fetchAndUpdateSubscription(sub, version, deviceAddress)
                    }
                    EventLog.log("scan", "\"${selfMatch.fileName}\" already at v${selfMatch.version} - adv v$version not newer, skipped")
                    return false
                }
                // Relay update: someone relayed a newer version we don't have yet
                EventLog.log("scan", "Matched relay copy \"${selfMatch.fileName}\" v${selfMatch.version} -> fetching v$version")
                return fetchAndUpdateBroadcast(selfMatch, version, deviceAddress)
            } else {
                // The advertisement contains only a 4-byte key ID. Never turn it
                // into a public key: the full key must come from the QR/link.
                // A local broadcast with a different key is not enough to trust
                // the peer's envelope.
                EventLog.log("scan", "Ignoring \"${selfMatch.fileName}\" advertisement with unknown key ID")
                val subscription = subscriptionDao.getById(selfMatch.fileId)
                if (subscription == null || !cryptoService.keyId(subscription.publicKey).contentEquals(keyId)) {
                    return false
                }
                if (version <= (subscription.localVersion ?: 0)) {
                    EventLog.log("scan", "\"${subscription.fileName ?: subscription.fileId}\" already at v${subscription.localVersion} - adv v$version not newer, skipped")
                    return false
                }
                return fetchAndUpdateSubscription(subscription, version, deviceAddress)
            }
        }
        val subscriptions = subscriptionDao.getAll()
        val subscription = subscriptions.firstOrNull { s ->
            cryptoService.fileIdHash(s.fileId).contentEquals(fileIdHash) && cryptoService.keyId(s.publicKey).contentEquals(keyId)
        }
        if (subscription != null) {
            val localVer = subscription.localVersion
            if (localVer != null && version <= localVer) {
                EventLog.log("scan", "\"${subscription.fileName ?: subscription.fileId}\" already at v$localVer - adv v$version not newer, skipped")
                return false
            }
            EventLog.log("scan", "Matched subscription \"${subscription.fileName ?: subscription.fileId}\" -> fetching v$version")
            return fetchAndUpdateSubscription(subscription, version, deviceAddress)
        }
        // Diagnostic detail: show why nothing matched
        val knownIds = (broadcasts.map { cryptoService.fileIdHash(it.fileId) } + subscriptions.map { cryptoService.fileIdHash(it.fileId) })
            .joinToString(",") { it.joinToString("") { b -> "%02x".format(b) }.take(12) }
        val knownKeys = subscriptions.joinToString(",") { cryptoService.keyId(it.publicKey).joinToString("") { b -> "%02x".format(b) }.take(8) }
        EventLog.log("scan", "Adv hash=${fileIdHash.joinToString("") { b -> "%02x".format(b) }.take(12)} keyId=${keyId.joinToString("") { b -> "%02x".format(b) }.take(8)} | mine: ids=[$knownIds] keys=[$knownKeys] - NO MATCH")
        return false
    }

    /** Relay keeping its own copy current: verify meta, pull bytes, re-sign, update DB. */
    private suspend fun fetchAndUpdateBroadcast(broadcast: BroadcastEntity, newVersion: Int, deviceAddress: String): Boolean {
        // Prevent multiple concurrent downloads for the same fileId - atomic check-and-add
        downloadMutex.withLock {
            if (_downloadingFileIds.value.contains(broadcast.fileId)) {
                EventLog.log("sync", "Download already in progress for \"${broadcast.fileName}\" - skipping duplicate")
                return false
            }
        }
        // Wait if there's an active upload to this peer
        while (activeUploadPeers.contains(deviceAddress)) {
            kotlinx.coroutines.delay(100)
        }
        // Only one transfer (upload OR download) at a time, and only one transfer per peer
        transferSemaphore.withPermit {
        downloadSemaphore.withPermit {
        peerLock(deviceAddress).withLock {
            _downloadingFileIds.value = _downloadingFileIds.value + broadcast.fileId
            activeDownloadPeers.add(deviceAddress)
            activeDownloadJobs[broadcast.fileId] = currentCoroutineContext()[Job]!!
            downloadingFileDeviceMap[broadcast.fileId] = deviceAddress
            stopAdvertisingAndScanning()
            EventLog.log("sync", "Download started for \"${broadcast.fileName}\" v$newVersion from ${deviceAddress.takeLast(5)}")
        try {
            val fileIdHash = cryptoService.fileIdHash(broadcast.fileId)
            val metaPayload = bleCentralService.readMeta(deviceAddress, fileIdHash) ?: run {
                EventLog.log("sync", "No meta payload from ${deviceAddress.takeLast(5)} - aborting relay update"); return false
            }
            if (metaPayload.fileSize > MAX_FILE_SIZE) {
                EventLog.log("sync", "Relay file too large (compressed+encrypted ${metaPayload.fileSize}B > ${MAX_FILE_SIZE}B) - aborting")
                return false
            }
            // Use the public key from the broadcast entity (originator), not from META
            val hashHex = metaPayload.fileHash.joinToString("") { "%02x".format(it) }
            val tmpFile = fileService.getTmpFile(broadcast.fileId, newVersion)
            tmpFile.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Cannot create temporary download directory") }
            EventLog.log("ble", "Streaming ${broadcast.fileId} v$newVersion (${metaPayload.fileSize}B compressed) over GATT")
            val transferred = try {
                tmpFile.outputStream().use { output ->
                    bleCentralService.fetchFile(deviceAddress, newVersion, metaPayload.fileSize, output) { got, total ->
                        val progress = (got.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        _downloadProgress.value = _downloadProgress.value + (broadcast.fileId to progress)
                    }
                }
            } catch (e: Exception) {
                EventLog.log("ble", "fetchFile error: ${e.message}"); false
            }
            if (!transferred) { tmpFile.delete(); val dedupKey = "${cryptoService.fileIdHash(broadcast.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"; dedupCache.remove(dedupKey); EventLog.log("wifi", "Transfer failed for ${broadcast.fileId}"); return false }
            if (!tmpFile.isFile || tmpFile.length() != metaPayload.fileSize) {
                EventLog.log("sync", "Downloaded file size mismatch for ${broadcast.fileId}: ${tmpFile.length()}/${metaPayload.fileSize}B")
                tmpFile.delete(); val dedupKey = "${cryptoService.fileIdHash(broadcast.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"; dedupCache.remove(dedupKey); return false
            }
            val encrypted = tmpFile.readBytes()
            val compressed = try {
                cryptoService.decryptCompressed(encrypted, cryptoService.publicKeyFromBase64(broadcast.publicKey))
            } catch (e: Exception) { tmpFile.delete(); EventLog.log("sync", "Decrypt failed: ${e.message}"); val dedupKey = "${cryptoService.fileIdHash(broadcast.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"; dedupCache.remove(dedupKey); return false }
            tmpFile.writeBytes(compressed)
            val internalFile = fileService.getFile(broadcast.fileId, newVersion)
            try {
                fileService.decompressFile(tmpFile, internalFile)
                tmpFile.delete()
            } catch (e: Exception) {
                EventLog.log("sync", "Decompression failed for ${broadcast.fileId}: ${e.message}")
                tmpFile.delete(); internalFile.delete(); val dedupKey = "${cryptoService.fileIdHash(broadcast.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"; dedupCache.remove(dedupKey); return false
            }
            val receivedHash = cryptoService.sha256Hex(internalFile.readBytes())
            if (receivedHash != hashHex) {
                internalFile.delete(); EventLog.log("sync", "Hash mismatch after transfer of ${broadcast.fileId} - discarded"); val dedupKey = "${cryptoService.fileIdHash(broadcast.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"; dedupCache.remove(dedupKey); return false
            }
            // Success - write probe cooldown only after successful transfer
            lastProbeAt["$deviceAddress:${cryptoService.fileIdHash(broadcast.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"] = System.currentTimeMillis()
            broadcastDao.updateVersion(broadcast.fileId, newVersion, Base64.getEncoder().encodeToString(metaPayload.fileHash), "", internalFile.absolutePath, internalFile.length(), encrypted.size.toLong(), System.currentTimeMillis(), metaPayload.fileName.takeIf { it.isNotBlank() } ?: broadcast.fileName)
            subscriptionDao.updateReceived(broadcast.fileId, newVersion, internalFile.absolutePath, newVersion, System.currentTimeMillis(), metaPayload.fileName.takeIf { it.isNotBlank() } ?: broadcast.fileName)
            EventLog.log("sync", "Subscription record updated to v$newVersion for ${broadcast.fileId}")
            fileService.evictOldVersions(broadcast.fileId, newVersion)
            EventLog.log("sync", "Relay copy updated: ${broadcast.fileName} v${broadcast.version} -> v$newVersion")
            notificationService.showUpdateNotification(broadcast.fileName, broadcast.fileId, broadcast.version, newVersion)
            blePeripheralService.stopAdvertising(broadcast.fileId)
            broadcastDao.getById(broadcast.fileId)?.let { startAdvertising(it) }
            return true
        } catch (e: Exception) { Log.e(TAG, "Error fetching update ${broadcast.fileId}", e); EventLog.log("sync", "Error fetching update ${broadcast.fileId}: ${e.message}"); return false }
        finally {
            activeDownloadPeers.remove(deviceAddress)
            _downloadingFileIds.value = _downloadingFileIds.value - broadcast.fileId
            _downloadProgress.value = _downloadProgress.value - broadcast.fileId
            downloadingFileDeviceMap.remove(broadcast.fileId)
            activeDownloadJobs.remove(broadcast.fileId)
            resumeAdvertisingAndScanning()
            EventLog.log("sync", "Download finished for \"${broadcast.fileName}\"")
        }
        } // peerLock
        } // transferSemaphore
        }
    }

    /** Subscriber receiving a new version of a subscribed file. */
    private suspend fun fetchAndUpdateSubscription(subscription: SubscriptionEntity, newVersion: Int, deviceAddress: String): Boolean {
        // Prevent multiple concurrent downloads for the same fileId - atomic check-and-add
        downloadMutex.withLock {
            if (_downloadingFileIds.value.contains(subscription.fileId)) {
                EventLog.log("sync", "Download already in progress for \"${subscription.fileName ?: subscription.fileId}\" - skipping duplicate")
                return false
            }
            // Check retry limit
            val retries = downloadRetryCount.getOrDefault(subscription.fileId, 0)
            if (retries >= maxRetries) {
                EventLog.log("sync", "Max retries ($maxRetries) reached for \"${subscription.fileName ?: subscription.fileId}\" - giving up")
                return false
            }
        }
        // Wait if there's an active upload to this peer
        while (activeUploadPeers.contains(deviceAddress)) {
            kotlinx.coroutines.delay(100)
        }
        // Only one download at a time, and only one transfer per peer
        downloadSemaphore.withPermit {
        peerLock(deviceAddress).withLock {
            _downloadingFileIds.value = _downloadingFileIds.value + subscription.fileId
            activeDownloadPeers.add(deviceAddress)
            activeDownloadJobs[subscription.fileId] = currentCoroutineContext()[Job]!!
            downloadingFileDeviceMap[subscription.fileId] = deviceAddress
            stopAdvertisingAndScanning()
            EventLog.log("sync", "Download started for \"${subscription.fileName ?: subscription.fileId}\" v$newVersion from ${deviceAddress.takeLast(5)}")
        var downloadResult = false
        try {
            withTimeout(3_600_000L) {
            val fileIdHash = cryptoService.fileIdHash(subscription.fileId)
            val metaPayload = bleCentralService.readMeta(deviceAddress, fileIdHash) ?: run {
                EventLog.log("sync", "No meta payload from ${deviceAddress.takeLast(5)} - aborting fetch"); return@withTimeout
            }
            if (metaPayload.fileSize > MAX_FILE_SIZE) {
                EventLog.log("sync", "File too large (compressed+encrypted ${metaPayload.fileSize}B > ${MAX_FILE_SIZE}B) - aborting")
                return@withTimeout
            }
            // Use the public key from the subscription (obtained from QR code/link), not from META
            val hashHex = metaPayload.fileHash.joinToString("") { "%02x".format(it) }
            EventLog.log("gatt", "Meta verified for \"${subscription.fileName ?: subscription.fileId}\" v$newVersion")
            val tmpFile = fileService.getTmpFile(subscription.fileId, newVersion)
            tmpFile.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Cannot create temporary download directory") }
            EventLog.log("ble", "Streaming ${subscription.fileId} v$newVersion (${metaPayload.fileSize}B compressed) over GATT, tmpFile=${tmpFile.absolutePath}")
            val transferred = try {
                tmpFile.outputStream().use { output ->
                    EventLog.log("ble", "Output stream opened: ${output.javaClass.name}")
                    bleCentralService.fetchFile(deviceAddress, newVersion, metaPayload.fileSize, output) { got, total ->
                        val progress = (got.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        _downloadProgress.value = _downloadProgress.value + (subscription.fileId to progress)
                    }
                }
            } catch (e: Exception) {
                EventLog.log("ble", "fetchFile error: ${e.message}"); false
            }
            EventLog.log("ble", "After fetchFile: transferred=$transferred, tmpFile.exists()=${tmpFile.exists()}, tmpFile.length()=${tmpFile.length()}")
            if (!transferred) {
                tmpFile.delete()
                downloadRetryCount[subscription.fileId] = downloadRetryCount.getOrDefault(subscription.fileId, 0) + 1
                val dedupKey = "${cryptoService.fileIdHash(subscription.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"
                dedupCache.remove(dedupKey)
                EventLog.log("sync", "Transfer failed for ${subscription.fileId} (retry ${downloadRetryCount[subscription.fileId]}/$maxRetries)"); return@withTimeout
            }
            if (!tmpFile.isFile || tmpFile.length() != metaPayload.fileSize) {
                EventLog.log("sync", "Downloaded file size mismatch for ${subscription.fileId}: ${tmpFile.length()}/${metaPayload.fileSize}B")
                tmpFile.delete()
                downloadRetryCount[subscription.fileId] = downloadRetryCount.getOrDefault(subscription.fileId, 0) + 1
                val dedupKey = "${cryptoService.fileIdHash(subscription.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"
                dedupCache.remove(dedupKey)
                EventLog.log("sync", "Transfer failed for ${subscription.fileId} (retry ${downloadRetryCount[subscription.fileId]}/$maxRetries)")
                return@withTimeout
            }
            val encrypted = tmpFile.readBytes()
            val persistedEnvelope = File(fileService.getVersionDir(subscription.fileId, newVersion), "file.encrypted")
            persistedEnvelope.parentFile?.mkdirs()
            persistedEnvelope.writeBytes(encrypted)
            EventLog.log("sync", "Persisted original encrypted envelope ${persistedEnvelope.absolutePath} (${persistedEnvelope.length()}B)")
            val compressed = try {
                cryptoService.decryptCompressed(encrypted, cryptoService.publicKeyFromBase64(subscription.publicKey))
            } catch (e: Exception) { tmpFile.delete(); EventLog.log("sync", "Decrypt failed: ${e.message}"); val dedupKey = "${cryptoService.fileIdHash(subscription.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"; dedupCache.remove(dedupKey); return@withTimeout }
            tmpFile.writeBytes(compressed)
            val internalFile = fileService.getFile(subscription.fileId, newVersion)
            try {
                fileService.decompressFile(tmpFile, internalFile)
                tmpFile.delete()
            } catch (e: Exception) {
                EventLog.log("sync", "Decompression failed for ${subscription.fileId}: ${e.message}")
                tmpFile.delete(); internalFile.delete()
                downloadRetryCount[subscription.fileId] = downloadRetryCount.getOrDefault(subscription.fileId, 0) + 1
                EventLog.log("sync", "Transfer failed for ${subscription.fileId} (retry ${downloadRetryCount[subscription.fileId]}/$maxRetries)")
                return@withTimeout
            }
            val receivedHash = cryptoService.sha256Hex(internalFile.readBytes())
            if (receivedHash != hashHex) {
                internalFile.delete()
                downloadRetryCount[subscription.fileId] = downloadRetryCount.getOrDefault(subscription.fileId, 0) + 1
                EventLog.log("sync", "Hash mismatch after transfer of ${subscription.fileId} - discarded (retry ${downloadRetryCount[subscription.fileId]}/$maxRetries)")
                return@withTimeout
            }
            // Success - clear retry count and write probe cooldown
            downloadRetryCount.remove(subscription.fileId)
            val probeKey = "$deviceAddress:${cryptoService.fileIdHash(subscription.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"
            lastProbeAt[probeKey] = System.currentTimeMillis()
            EventLog.log("sync", "Committed ${internalFile.absolutePath} (${internalFile.length()}B) for subscription v$newVersion")
            val resolvedFileName = metaPayload.fileName.takeIf { it.isNotBlank() } ?: subscription.fileName
            if (metaPayload.fileName.isNotBlank() && metaPayload.fileName != subscription.fileName) {
                EventLog.log("sync", "Filename updated for subscription ${subscription.fileId}: \"${subscription.fileName ?: ""}\" -> \"${metaPayload.fileName}\"")
            }
            subscriptionDao.updateReceived(subscription.fileId, newVersion, internalFile.absolutePath, newVersion, System.currentTimeMillis(), resolvedFileName)
            val persisted = subscriptionDao.getById(subscription.fileId)
            if (persisted == null || persisted.localVersion != newVersion) {
                // Diagnostic only: updateReceived either commits or throws, but a
                // stale read here would explain a reverted UI version.
                EventLog.log("sync", "WARNING: subscription row not at v$newVersion after update (found v${persisted?.localVersion})")
            }
            fileService.evictOldVersions(subscription.fileId, newVersion)
            EventLog.log("sync", "Old versions evicted; current file remains ${internalFile.length()}B")
            val displayName = resolvedFileName ?: subscription.fileId
            if (subscription.localVersion == null) {
                EventLog.log("sync", "File \"$displayName\" downloaded!")
            } else {
                EventLog.log("sync", "File \"$displayName\" updated from version ${subscription.localVersion} to version $newVersion!")
            }
            val shouldNotify = subscription.lastNotifiedVersion == null || subscription.lastNotifiedVersion < newVersion
            if (shouldNotify) {
                notificationService.showUpdateNotification(resolvedFileName ?: "File", subscription.fileId, subscription.localVersion ?: 0, newVersion)
                subscriptionDao.updateLastNotified(subscription.fileId, newVersion)
            }
            // Become a relay: register as broadcast and advertise the same triple.
            val relayPayload = ByteArray(14)
            cryptoService.fileIdHash(subscription.fileId).copyInto(relayPayload, 0)
            relayPayload[6] = ((newVersion ushr 24) and 0xFF).toByte(); relayPayload[7] = ((newVersion ushr 16) and 0xFF).toByte()
            relayPayload[8] = ((newVersion ushr 8) and 0xFF).toByte(); relayPayload[9] = (newVersion and 0xFF).toByte()
            cryptoService.keyId(subscription.publicKey).copyInto(relayPayload, 10)
            broadcastDao.upsert(BroadcastEntity(subscription.fileId, resolvedFileName ?: "File", subscription.relayName ?: subscription.fileId.take(8), "application/octet-stream", internalFile.absolutePath, Base64.getEncoder().encodeToString(metaPayload.fileHash), internalFile.length(), metaPayload.fileSize, newVersion, subscription.publicKey, null, "", Role.RELAY, subscription.subscribedAt, System.currentTimeMillis()))
            blePeripheralService.startAdvertising(subscription.fileId, relayPayload)
            EventLog.log("adv", "Relaying \"${subscription.fileName ?: subscription.fileId}\" v$newVersion")
            onFileReceived?.invoke(subscription.fileId, newVersion)
            downloadResult = true
            }
        } catch (e: TimeoutCancellationException) {
            EventLog.log("sync", "Download timed out for ${subscription.fileId} after 10min (retry ${downloadRetryCount.getOrDefault(subscription.fileId, 0) + 1}/$maxRetries)")
            downloadRetryCount[subscription.fileId] = downloadRetryCount.getOrDefault(subscription.fileId, 0) + 1
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching sub update ${subscription.fileId}", e)
            EventLog.log("sync", "Error fetching sub update ${subscription.fileId}: ${e.message}")
        }
        if (!downloadResult) {
            _downloadingFileIds.value = _downloadingFileIds.value - subscription.fileId
            _downloadProgress.value = _downloadProgress.value - subscription.fileId
            downloadingFileDeviceMap.remove(subscription.fileId)
            activeDownloadJobs.remove(subscription.fileId)
            resumeAdvertisingAndScanning()
            return false
        }
        activeDownloadPeers.remove(deviceAddress)
        _downloadingFileIds.value = _downloadingFileIds.value - subscription.fileId
        _downloadProgress.value = _downloadProgress.value - subscription.fileId
        downloadingFileDeviceMap.remove(subscription.fileId)
        activeDownloadJobs.remove(subscription.fileId)
        resumeAdvertisingAndScanning()
        return true
        } // peerLock
        }
    }

    fun stopAdvertisingForFile(fileId: String) {
        try { blePeripheralService.stopAdvertising(fileId) } catch (_: Exception) {}
        advertisedFiles.remove(fileId)
        clearDiscoveryStateForFile(fileId)
        EventLog.log("adv", "Stopped relaying ${fileId.takeLast(8)} and reset its discovery state")
    }

    fun cancelTransfer(fileId: String) {
        activeDownloadJobs.remove(fileId)?.let {
            it.cancel()
            EventLog.log("sync", "Cancelled active download for ${fileId.takeLast(8)}")
        }
        // Disconnect from the device to notify the sender that the transfer was cancelled
        downloadingFileDeviceMap.remove(fileId)?.let { deviceAddress ->
            bleCentralService.disconnectDevice(deviceAddress)
            EventLog.log("sync", "Disconnected from ${deviceAddress.takeLast(5)} for cancelled transfer ${fileId.takeLast(8)}")
        }
        blePeripheralService.stopStreaming(fileId)
        resumeAdvertisingAndScanning()
    }

    /**
     * Clears dedup entries and probe cooldowns so the next advertisement of
     * [fileId] is processed immediately. Required for delete -> re-subscribe:
     * without this, a stale dedup key (30s) or probe cooldown (5min) silently
     * swallows the advertisement and nothing is fetched.
     */
    fun clearDiscoveryStateForFile(fileId: String) {
        val hashPrefix = cryptoService.fileIdHash(fileId).joinToString("") { "%02x".format(it) }
        val removedDedup = dedupCache.keys.count { it.startsWith(hashPrefix) }
        dedupCache.keys.removeAll { it.startsWith(hashPrefix) }
        lastProbeAt.clear()
        EventLog.log("scan", "Discovery state reset for ${fileId.takeLast(8)} (cleared $removedDedup dedup entries + all probe cooldowns)")
    }

    private fun uuidToBytes(fileId: String): ByteArray? {
        return try {
            val uuid = UUID.fromString(fileId)
            val msb = uuid.mostSignificantBits; val lsb = uuid.leastSignificantBits
            ByteArray(16).also { bytes -> for (i in 0..7) { bytes[i] = ((msb ushr (8 * (7 - i))) and 0xFF).toByte(); bytes[8 + i] = ((lsb ushr (8 * (7 - i))) and 0xFF).toByte() } }
        } catch (e: Exception) { EventLog.log("app", "Invalid fileId '$fileId' (not a UUID)"); null }
    }

    fun destroy() { stop(); scope.cancel() }
}
