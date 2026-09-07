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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val transferMutex: Mutex = Mutex(),
    testScope: CoroutineScope? = null
) {
    companion object {
        private const val TAG = "SyncEngine"
        private const val DEDUP_TTL_MS = 30_000L
        private const val PROBE_COOLDOWN_MS = 30_000L
        const val MAX_FILE_SIZE = 10L * 1024 * 1024
    }

    private val scope = testScope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var engineJob: Job? = null
    private val dedupCache = ConcurrentHashMap<String, Long>()
    // After successfully learning a peer's file/version info via a GATT meta read,
    // do not probe that device again for PROBE_COOLDOWN_MS.
    private val lastProbeAt = ConcurrentHashMap<String, Long>()
    private val advertisedFiles = ConcurrentHashMap.newKeySet<String>()
    private val advertisedWants = ConcurrentHashMap.newKeySet<String>()
    private val peerLocks = ConcurrentHashMap<String, Mutex>()
    private val activeDownloadPeers = ConcurrentHashMap.newKeySet<String>()
    private val activeUploadPeers = ConcurrentHashMap.newKeySet<String>()
    private val deviceToDeviceId = ConcurrentHashMap<String, String>()
    var onFileReceived: ((fileId: String, version: Int) -> Unit)? = null

    private fun peerLock(address: String): Mutex = peerLocks.getOrPut(address) { Mutex() }

    private val _downloadingFileIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingFileIds: StateFlow<Set<String>> = _downloadingFileIds.asStateFlow()
    private val activeDownloadJobs = ConcurrentHashMap<String, Job>()
    private val downloadingFileDeviceMap = ConcurrentHashMap<String, String>()
    private val downloadMutex = Mutex()
    private val downloadRetryCount = ConcurrentHashMap<String, Int>()
    private val maxRetries = 10

    private var fullRotationIntervalMs = BleCentralService.STREAM_IDLE_TIMEOUT_MS * 1

    internal suspend fun updateFullRotationInterval() {
        val subscriptionCount = subscriptionDao.getAll().size
        fullRotationIntervalMs = BleCentralService.STREAM_IDLE_TIMEOUT_MS * (subscriptionCount + 4) / 4
    }

    private fun fullRotationInterval(): Long = fullRotationIntervalMs

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val _uploadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val uploadProgress: StateFlow<Map<String, Float>> = _uploadProgress.asStateFlow()

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
            updateFullRotationInterval()
            Log.d(TAG, "SyncEngine started")
            EventLog.log("sync", "Engine started - scanning, advertising and transfer server active")
            blePeripheralService.setMetaPayloadProvider { fileId -> buildMetaPayload(fileId) }
            // GATT streaming serves the encrypted envelope, which relays can forward unchanged.
            blePeripheralService.setServeFileLoader { fileId, version ->
                kotlinx.coroutines.runBlocking {
                try {
                    val broadcast = broadcastDao.getById(fileId)
                    val subscription = subscriptionDao.getById(fileId)
                    if (broadcast == null && subscription == null) {
                        null
                    } else {
                        val encrypted = File(fileService.getVersionDir(fileId, version), "file.encrypted")
                        if (encrypted.exists()) {
                            EventLog.log("ble", "Serving persisted encrypted envelope for ${fileId.takeLast(8)} v$version (${encrypted.length()}B)")
                        } else {
                            val privateKey = broadcast?.privateKeyAlias?.let { cryptoService.getPrivateKey(it) }
                            if (privateKey != null) {
                                // Originator with a cached private key: build the envelope on demand.
                                val compressed = fileService.getCompressedFile(fileId, version).readBytes()
                                encrypted.writeBytes(cryptoService.encryptCompressed(compressed, privateKey))
                                EventLog.log("ble", "Created encrypted envelope for originator ${fileId.takeLast(8)} v$version (${encrypted.length()}B)")
                            } else {
                                // Relays have no private key and must forward the persisted envelope,
                                // which should have been written when the relay copy was downloaded.
                                EventLog.log("ble", "No persisted envelope and no private key to serve ${fileId.takeLast(8)} (relay without cached file.encrypted)")
                                null
                            }
                        }
                        if (encrypted.exists()) encrypted.readBytes() else null
                    }
                } catch (e: Exception) {
                    EventLog.log("ble", "Failed to get compressed file for streaming: ${e.message}")
                    null
                }
                }
            }
            bleCentralService.onDeviceDiscovered = { address, serviceData, metaPayload, isWant ->
                scope.launch { handleDiscoveredDevice(address, serviceData, metaPayload, isWant) }
            }
            bleCentralService.onCongestionDetected = { address ->
                val deviceId = deviceToDeviceId[address] ?: address
                CongestionPauses.record(deviceId, fullRotationInterval())
                cancelTransfersForDevice(address)
            }
            blePeripheralService.isPeerTransferAllowed = { address ->
                val deviceId = deviceToDeviceId[address] ?: address
                // Only one transfer at a time, in either direction. Reject an incoming
                // PULL if WE are already downloading, already uploading, or a transfer
                // mutex is held — otherwise the peripheral would accept the PULL and
                // then block the GATT thread in onUploadStart waiting for the mutex,
                // stalling the peer and violating single-transfer serialization.
                val allowed = !activeDownloadPeers.contains(address)
                    && !CongestionPauses.isCongested(deviceId)
                    && !transferMutex.isLocked
                    && activeUploadPeers.isEmpty()
                if (!allowed) {
                    EventLog.log("sync", "PULL rejected for ${address.takeLast(5)} (download=${activeDownloadPeers.isNotEmpty()}, upload=${activeUploadPeers.isNotEmpty()}, mutexLocked=${transferMutex.isLocked}, congested=${CongestionPauses.isCongested(deviceId)})")
                }
                allowed
            }
            blePeripheralService.onUploadStart = { address ->
                try { runBlocking { transferMutex.lock() } } catch (_: Exception) {}
                activeUploadPeers.add(address)
                bleCentralService.stopScan()
                blePeripheralService.stopAllAdvertising()
            }
            blePeripheralService.onUploadEnd = { address ->
                activeUploadPeers.remove(address)
                transferMutex.unlock()
                resumeAdvertisingAndScanning()
            }
            blePeripheralService.onUploadSuccess = { address ->
                val deviceId = deviceToDeviceId[address] ?: address
                CongestionPauses.reset(deviceId)
            }
            blePeripheralService.isDownloadActive = { _downloadingFileIds.value.isNotEmpty() || activeDownloadPeers.isNotEmpty() }
            blePeripheralService.isTransferActive = { transferMutex.isLocked }
            blePeripheralService.isIncomingTransferAllowed = { address ->
                !transferMutex.isLocked && !activeDownloadPeers.contains(address)
            }
            blePeripheralService.onTransferStart = { stopAdvertisingAndScanning() }
            blePeripheralService.onTransferEnd = {
                EventLog.log("sync", "onTransferEnd fired, resuming advertising/scanning")
                resumeAdvertisingAndScanning()
            }
            blePeripheralService.onStreamArmed = { stopAdvertisingAndScanning() }
            // Budget coordination: the peripheral may drop WANT ads when the device hits its
            // advertising-set limit. Drop from our bookkeeping so we can retry, and re-attempt
            // deferred WANT ads whenever a slot frees up.
            blePeripheralService.onWantDropped = { fileId -> advertisedWants.remove(fileId) }
            blePeripheralService.onAdvertisingSlotFreed = { scope.launch { reAdvertiseWants() } }
            // Incoming "push": a peer delivers a file to us because we advertised
            // "I WANT" but are not scanning (e.g. screen off). Lock the shared
            // transfer mutex so only one transfer (push or pull) happens at a time.
            blePeripheralService.onIncomingTransferStart = { address ->
                try { runBlocking { transferMutex.lock() } } catch (_: Exception) {}
                activeUploadPeers.add(address)
                stopAdvertisingAndScanning()
            }
            blePeripheralService.onIncomingFile = { address, fileIdBytes, version, keyId, size, fileHash, fileName, tempFile ->
                handleIncomingFile(address, fileIdBytes, version, keyId, size, fileHash, fileName, tempFile)
            }
            blePeripheralService.onIncomingTransferEnd = { address ->
                activeUploadPeers.remove(address)
                transferMutex.unlock()
                resumeAdvertisingAndScanning()
            }

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
            // Also advertise "I WANT" for existing subscriptions so screen-off senders can push.
            try {
                reAdvertiseWants()
            } catch (e: Exception) { Log.e(TAG, "Initial subscription WANT load failed", e) }
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
            // Watch for subscription changes: advertise "I WANT" for each subscription so
            // screen-off senders can discover and push. Stop stale WANT ads on deletion.
            launch {
                while (true) {
                    try { subscriptionDao.changeFlow.collect {
                        val subs = subscriptionDao.getAll()
                        val currentIds = subs.map { it.fileId }.toSet()
                        for (id in advertisedWants.toList()) {
                            if (id !in currentIds) {
                                try { blePeripheralService.stopWantAdvertising(id) } catch (_: Exception) {}
                                advertisedWants.remove(id)
                                EventLog.log("adv", "Stopped WANT ad for removed subscription $id")
                            }
                        }
                        reAdvertiseWants()
                    } } catch (e: Exception) { Log.e(TAG, "subscription WANT watcher failed", e) }
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
                    kotlinx.coroutines.delay(120_000L)
                    updateFullRotationInterval()
                    if (transferMutex.isLocked || _downloadingFileIds.value.isNotEmpty() || activeUploadPeers.isNotEmpty()) {
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
                    kotlinx.coroutines.delay(30_000L)
                    if (transferMutex.isLocked || blePeripheralService.activeStreamingFileIds.value.isNotEmpty() || _downloadingFileIds.value.isNotEmpty()) {
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
        if (transferMutex.isLocked) {
            EventLog.log("sync", "resumeAdvertisingAndScanning skipped (transfer in progress)")
            return
        }
        EventLog.log("sync", "resumeAdvertisingAndScanning called, scope active=${scope.coroutineContext[kotlinx.coroutines.Job]?.isActive}")
        val hasActiveTransfer = _downloadingFileIds.value.isNotEmpty() || activeUploadPeers.isNotEmpty()
        EventLog.log("sync", "resumeAdvertisingAndScanning: hasActiveTransfer=$hasActiveTransfer, downloading=${_downloadingFileIds.value.size}, uploading=${activeUploadPeers.size}, transferLocked=${transferMutex.isLocked}")
        scope.launch {
            try {
                if (hasActiveTransfer) {
                    EventLog.log("ble", "resumeAdvertisingAndScanning: skipping GATT server restart (active transfer in progress)")
                } else {
                    blePeripheralService.restartGattServer()
                    EventLog.log("ble", "resumeAdvertisingAndScanning: GATT server restarted")
                }
                bleCentralService.startScan()
                val broadcasts = broadcastDao.getAll()
                for (b in broadcasts) {
                    try {
                        startAdvertising(b)
                    } catch (e: Exception) {
                        EventLog.log("ble", "resumeAdvertising failed: ${e.message}")
                    }
                }
                // Re-advertise "I WANT" for subscriptions (stopAdvertisingAndScanning stops everything).
                val subs = runBlocking { subscriptionDao.getAll() }
                for (s in subs) {
                    if (s.fileId in advertisedWants) {
                        try { startWantAdvertising(s) } catch (e: Exception) { EventLog.log("ble", "resume WANT advertising failed: ${e.message}") }
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
            val compressedFile = fileService.getCompressedFile(fileId, broadcast.version)
            encrypted.parentFile?.mkdirs()
            cryptoService.encryptCompressedFile(compressedFile, encrypted, privateKey)
        }
        return BleMetaPayload(fileIdBytes, broadcast.version, hashBytes, encrypted.length(), broadcast.fileName, blePeripheralService.getDeviceUuidBytes())
    }

    suspend fun startAdvertising(broadcast: BroadcastEntity) {
        // A relay forwards the originator's persisted encrypted envelope unchanged.
        // If that envelope is missing on disk (e.g. a copy downloaded before envelope
        // persistence was added, or one that was evicted) the relay cannot serve the
        // file at all — it must NOT advertise it. The scan matcher repairs such copies
        // by re-fetching, after which the envelope exists and advertising resumes.
        if (broadcast.role == Role.RELAY && !File(fileService.getVersionDir(broadcast.fileId, broadcast.version), "file.encrypted").exists()) {
            EventLog.log("adv", "Relay \"${broadcast.fileName}\" v${broadcast.version} missing encrypted envelope - not advertising (will auto-repair)")
            return
        }
        val fileIdBytes = uuidToBytes(broadcast.fileId) ?: return
        val hashBytes = Base64.getDecoder().decode(broadcast.fileHash)
        val keyId = cryptoService.keyId(broadcast.publicKey)
        val encryptedFile = File(fileService.getVersionDir(broadcast.fileId, broadcast.version), "file.encrypted")
        // The served payload is the ENCRYPTED envelope, not the raw compressed file.
        // Ensure the envelope exists (originators can build it) so the advertised size
        // matches what is actually streamed; falling back to compressedSize here is
        // wrong and makes every download fail the size check (meta says N, stream is
        // the larger encrypted N+overhead).
        if (!encryptedFile.exists() && !broadcast.privateKeyAlias.isNullOrEmpty()) {
            try {
                val key = cryptoService.getPrivateKey(broadcast.privateKeyAlias)
                val compressedFile = fileService.getCompressedFile(broadcast.fileId, broadcast.version)
                if (key != null && compressedFile.exists()) {
                    encryptedFile.parentFile?.mkdirs()
                    cryptoService.encryptCompressedFile(compressedFile, encryptedFile, key)
                }
            } catch (e: Exception) {
                EventLog.log("adv", "Failed to build envelope for ${broadcast.fileId.takeLast(8)}: ${e.message}")
            }
        }
        val encryptedSize = if (encryptedFile.exists()) encryptedFile.length() else broadcast.compressedSize
        // The file name is intentionally omitted from the *advertisement*: it leaks the file's
        // name to everyone in BLE range. The real name is still served to connected peers via the
        // GATT META characteristic (buildMetaPayload) and in the push header, so receivers learn it
        // at transfer time without broadcasting it.
        val metaPayload = BleMetaPayload(fileIdBytes, broadcast.version, hashBytes, encryptedSize, "", keyId, blePeripheralService.getDeviceUuidBytes())
        val serviceData = metaPayload.toBytes()
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
        EventLog.log("adv", "Advertising \"${broadcast.fileName}\" v${broadcast.version} (${broadcast.fileSize}B, compressed ${broadcast.compressedSize}B, meta ${serviceData.size}B)")
    }

    /**
     * Advertises "I WANT <file>" for a subscription under the WANT service UUID. The
     * advertised payload mirrors a META payload but its version field is this device's
     * local version (0 if none), so a peer that has a *newer* version can discover the
     * ad and push. Advertising survives screen-off (controller offloaded), which is the
     * whole point: a screen-off recipient can still be found and pushed to.
     */
    suspend fun startWantAdvertising(subscription: SubscriptionEntity) {
        val fileIdBytes = uuidToBytes(subscription.fileId) ?: return
        val keyId = cryptoService.keyId(subscription.publicKey)
        val metaPayload = BleMetaPayload(
            fileIdBytes,
            subscription.localVersion ?: 0,
            ByteArray(32),
            0,
            // Intentionally blank: the WANT beacon only needs to signal interest in a file
            // (fileId + keyId + local version). The real file name is learned at transfer
            // time, either from the META characteristic (pull) or the push header (push), so
            // we avoid broadcasting the file name (and its content hint) over the air.
            "",
            keyId,
            blePeripheralService.getDeviceUuidBytes()
        )
        val serviceData = metaPayload.toBytes()
        val started = blePeripheralService.startWantAdvertising(subscription.fileId, serviceData)
        if (started) advertisedWants.add(subscription.fileId)
        EventLog.log("adv", "Advertising WANT \"${subscription.fileName ?: subscription.fileId.take(8)}\" (have v${subscription.localVersion ?: 0})${if (started) "" else " - SKIPPED (capacity)"}")
    }

    private suspend fun reAdvertiseWants() {
        try {
            val subs = subscriptionDao.getAll()
            for (s in subs) {
                if (s.fileId !in advertisedWants) {
                    try { startWantAdvertising(s) } catch (e: Exception) { Log.e(TAG, "startWantAdvertising failed", e); EventLog.log("ble", "startWantAdvertising failed: ${e.message}") }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "reAdvertiseWants failed", e) }
    }

    /**
     * Handles one BLE discovery event.
     * Returns true iff the advertisement was recognized and the receive pipeline
     * completed successfully (meta verified AND transfer accepted).
     */
    internal suspend fun handleDiscoveredDevice(deviceAddress: String, serviceData: ByteArray, advMeta: BleMetaPayload?, isWant: Boolean = false): Boolean {
        if (isWant) {
            return handleWantAdvertisement(deviceAddress, serviceData, advMeta)
        }
        if (serviceData.size < 14) { EventLog.log("scan", "Advertisement too short (${serviceData.size}B) - dropped"); return false }
        // Random jitter to stagger competing devices discovering the same peer
        kotlinx.coroutines.delay((Math.random() * 5_000).toLong())
        val fileIdHash = advMeta?.let { cryptoService.fileIdHash(uuidToString(it.fileId)) } ?: serviceData.copyOfRange(0, 6)
        val version = advMeta?.version ?: ((serviceData[6].toInt() and 0xFF) shl 24) or ((serviceData[7].toInt() and 0xFF) shl 16) or ((serviceData[8].toInt() and 0xFF) shl 8) or (serviceData[9].toInt() and 0xFF)
        val keyId = advMeta?.keyId ?: serviceData.copyOfRange(10, 14)
        // Only record the device UUID when we actually parsed one. A scan that
        // fails to parse the meta payload (e.g. Samsung dropping service data)
        // must NOT clobber a previously-learned UUID with the MAC address,
        // otherwise congestion pauses recorded under the UUID are never found
        // by the MAC-keyed lookup.
        advMeta?.deviceId?.joinToString("") { "%02x".format(it) }?.let {
            deviceToDeviceId[deviceAddress] = it
        }
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
                        return fetchAndUpdateSubscription(sub, version, deviceAddress, advMeta)
                    }
                    // Relay repair: a relay whose persisted envelope is missing cannot
                    // serve the file it advertises. Re-fetch the same version from this
                    // peer to recover the envelope it forwards (fetchAndUpdateBroadcast
                    // persists file.encrypted), then advertising resumes.
                    if (selfMatch.role == Role.RELAY && !File(fileService.getVersionDir(selfMatch.fileId, selfMatch.version), "file.encrypted").exists()) {
                        EventLog.log("scan", "Matched relay copy \"${selfMatch.fileName}\" v${selfMatch.version} (missing envelope) -> repairing v$version")
                        return fetchAndUpdateBroadcast(selfMatch, version, deviceAddress, advMeta)
                    }
                    EventLog.log("scan", "\"${selfMatch.fileName}\" already at v${selfMatch.version} - adv v$version not newer, skipped")
                    return false
                }
                // Relay update: someone relayed a newer version we don't have yet
                EventLog.log("scan", "Matched relay copy \"${selfMatch.fileName}\" v${selfMatch.version} -> fetching v$version")
                return fetchAndUpdateBroadcast(selfMatch, version, deviceAddress, advMeta)
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
                return fetchAndUpdateSubscription(subscription, version, deviceAddress, advMeta)
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
            return fetchAndUpdateSubscription(subscription, version, deviceAddress, advMeta)
        }
        // Diagnostic detail: show why nothing matched
        val knownIds = (broadcasts.map { cryptoService.fileIdHash(it.fileId) } + subscriptions.map { cryptoService.fileIdHash(it.fileId) })
            .joinToString(",") { it.joinToString("") { b -> "%02x".format(b) }.take(12) }
        val knownKeys = subscriptions.joinToString(",") { cryptoService.keyId(it.publicKey).joinToString("") { b -> "%02x".format(b) }.take(8) }
        EventLog.log("scan", "Adv hash=${fileIdHash.joinToString("") { b -> "%02x".format(b) }.take(12)} keyId=${keyId.joinToString("") { b -> "%02x".format(b) }.take(8)} | mine: ids=[$knownIds] keys=[$knownKeys] - NO MATCH")
        return false
    }

    /**
     * Handles a "I WANT <file>" advertisement: a peer is advertising that it wants the
     * given file. If WE have a newer version (matching keyId) we push it to them. This
     * is the screen-off receive path — the sender (which is scanning) discovers the
     * recipient's controller-offloaded WANT advertisement and initiates the transfer.
     */
    internal suspend fun handleWantAdvertisement(deviceAddress: String, serviceData: ByteArray, advMeta: BleMetaPayload?): Boolean {
        if (serviceData.size < 14) { EventLog.log("scan", "WANT advertisement too short (${serviceData.size}B) - dropped"); return false }
        kotlinx.coroutines.delay((Math.random() * 5_000).toLong())
        val fileIdHash = advMeta?.let { cryptoService.fileIdHash(uuidToString(it.fileId)) } ?: serviceData.copyOfRange(0, 6)
        val wantVersion = advMeta?.version ?: ((serviceData[6].toInt() and 0xFF) shl 24) or ((serviceData[7].toInt() and 0xFF) shl 16) or ((serviceData[8].toInt() and 0xFF) shl 8) or (serviceData[9].toInt() and 0xFF)
        val keyId = advMeta?.keyId ?: serviceData.copyOfRange(10, 14)
        advMeta?.deviceId?.joinToString("") { "%02x".format(it) }?.let { deviceToDeviceId[deviceAddress] = it }
        val hashHex = fileIdHash.joinToString("") { "%02x".format(it) }
        val dedupKey = "want:$hashHex:$wantVersion"
        val now = System.currentTimeMillis()
        dedupCache[dedupKey]?.let { if (now - it < DEDUP_TTL_MS) { return false } }
        dedupCache[dedupKey] = now

        val broadcasts = broadcastDao.getAll()
        val broadcast = broadcasts.firstOrNull { b -> cryptoService.fileIdHash(b.fileId).contentEquals(fileIdHash) }
        if (broadcast == null) { EventLog.log("scan", "WANT adv hash=$hashHex - we do not have this file, ignored"); return false }
        if (!cryptoService.keyId(broadcast.publicKey).contentEquals(keyId)) {
            EventLog.log("scan", "WANT adv hash=$hashHex - key mismatch, ignored")
            return false
        }
        if (broadcast.version <= wantVersion) {
            EventLog.log("scan", "WANT adv hash=$hashHex - our v${broadcast.version} not newer than their v$wantVersion, ignored")
            return false
        }
        EventLog.log("scan", "WANT match: we have \"${broadcast.fileName}\" v${broadcast.version}, peer wants <= v$wantVersion -> pushing")
        return pushToPeer(deviceAddress, broadcast, wantVersion, advMeta)
    }

    /** Pushes a file we have to a peer that advertised "I WANT" it. */
    private suspend fun pushToPeer(deviceAddress: String, broadcast: BroadcastEntity, wantVersion: Int, advMeta: BleMetaPayload?): Boolean {
        var deviceId = advMeta?.deviceId?.joinToString("") { "%02x".format(it) } ?: deviceToDeviceId[deviceAddress] ?: deviceAddress
        deviceToDeviceId[deviceAddress] = deviceId
        if (CongestionPauses.isCongested(deviceId)) { EventLog.log("sync", "Skipping push to ${deviceAddress.takeLast(5)} - congestion pause active"); return false }
        while (transferMutex.isLocked || activeDownloadPeers.contains(deviceAddress) || activeUploadPeers.contains(deviceAddress)) { kotlinx.coroutines.delay(100) }
        var result = false
        transferMutex.withLock {
            peerLock(deviceAddress).withLock {
                stopAdvertisingAndScanning()
                blePeripheralService.stopGattServer()
                kotlinx.coroutines.delay(2_000L)
                EventLog.log("sync", "Push starting for \"${broadcast.fileName}\" v${broadcast.version} to ${deviceAddress.takeLast(5)} (peer wants <= v$wantVersion)")
                try {
                    activeUploadPeers.add(deviceAddress)
                    val encryptedFile = java.io.File(fileService.getVersionDir(broadcast.fileId, broadcast.version), "file.encrypted")
                    if (!encryptedFile.exists()) {
                        val privateKey = broadcast.privateKeyAlias?.let { cryptoService.getPrivateKey(it) }
                        if (privateKey != null) {
                            val compressedFile = fileService.getCompressedFile(broadcast.fileId, broadcast.version)
                            encryptedFile.parentFile?.mkdirs()
                            cryptoService.encryptCompressedFile(compressedFile, encryptedFile, privateKey)
                        }
                    }
                    if (!encryptedFile.exists()) { EventLog.log("sync", "No encrypted envelope to push for ${broadcast.fileId.takeLast(8)}"); return@withLock }
                    val encrypted = encryptedFile.readBytes()
                    val fileIdBytes = uuidToBytes(broadcast.fileId) ?: return@withLock
                    val keyId = cryptoService.keyId(broadcast.publicKey)
                    val hashBytes = Base64.getDecoder().decode(broadcast.fileHash)
                    EventLog.log("ble", "Pushing ${broadcast.fileId} v${broadcast.version} (${encrypted.size}B) to ${deviceAddress.takeLast(5)}")
                    _uploadProgress.value = _uploadProgress.value + (broadcast.fileId to 0f)
                    val pushed = bleCentralService.pushFile(deviceAddress, fileIdBytes, broadcast.version, keyId, encrypted.size.toLong(), hashBytes, broadcast.fileName, encrypted) { got, total ->
                        val p = (got.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        _uploadProgress.value = _uploadProgress.value + (broadcast.fileId to p)
                    }
                    result = pushed
                    _uploadProgress.value = _uploadProgress.value - broadcast.fileId
                } catch (e: Exception) {
                    Log.e(TAG, "Error pushing ${broadcast.fileId}", e); EventLog.log("sync", "Error pushing ${broadcast.fileId}: ${e.message}")
                } finally {
                    activeUploadPeers.remove(deviceAddress)
                }
            }
        }
        resumeAdvertisingAndScanning()
        EventLog.log("sync", "Push of \"${broadcast.fileName}\" v${broadcast.version} to ${deviceAddress.takeLast(5)} ${if (result) "COMPLETE" else "FAILED"}")
        return result
    }

    /**
     * Finalizes a file pushed to us by a peer (we advertised "I WANT"). Runs synchronously
     * on the GATT server thread so the transfer mutex is held until we finish; mirrors the
     * tail of fetchAndUpdateSubscription: persist the envelope, decrypt, verify hash, store,
     * mark the subscription received, and re-advertise the copy as a relay.
     */
    private fun handleIncomingFile(address: String, fileIdBytes: ByteArray, version: Int, keyId: ByteArray, size: Long, fileHash: ByteArray, fileName: String, tempFile: java.io.File): Boolean {
        val fileId = uuidToString(fileIdBytes)
        val hashHex = fileHash.joinToString("") { "%02x".format(it) }
        try {
            val subscriptions = runBlocking { subscriptionDao.getAll() }
            val subscription = subscriptions.firstOrNull { s ->
                cryptoService.fileIdHash(s.fileId).contentEquals(cryptoService.fileIdHash(fileId)) && cryptoService.keyId(s.publicKey).contentEquals(keyId)
            }
            if (subscription == null) {
                EventLog.log("sync", "Incoming: no matching subscription for ${fileId.takeLast(8)} (keyId ${keyId.joinToString("") { "%02x".format(it) }.take(8)}) - discarding")
                tempFile.delete(); return false
            }
            if (subscription.localVersion != null && version <= subscription.localVersion) {
                EventLog.log("sync", "Incoming: already have v${subscription.localVersion} >= v$version for ${fileId.takeLast(8)} - discarding")
                tempFile.delete(); return false
            }
            if (!tempFile.isFile || tempFile.length() != size) {
                EventLog.log("sync", "Incoming: size mismatch (${tempFile.length()}/$size) - discarding")
                tempFile.delete(); return false
            }
            val resolvedName = fileName.takeIf { it.isNotBlank() } ?: subscription.fileName ?: "File"
            val encrypted = tempFile.readBytes()
            val persistedEnvelope = java.io.File(fileService.getVersionDir(fileId, version), "file.encrypted")
            persistedEnvelope.parentFile?.mkdirs()
            persistedEnvelope.writeBytes(encrypted)
            val compressed = try { cryptoService.decryptCompressed(encrypted, cryptoService.publicKeyFromBase64(subscription.publicKey)) } catch (e: Exception) { tempFile.delete(); EventLog.log("sync", "Incoming decrypt failed: ${e.message}"); return false }
            val internalFile = fileService.getFile(fileId, version)
            try { fileService.decompressFile(tempFile.also { it.writeBytes(compressed) }, internalFile); tempFile.delete() } catch (e: Exception) { internalFile.delete(); tempFile.delete(); EventLog.log("sync", "Incoming decompress failed: ${e.message}"); return false }
            val receivedHash = cryptoService.sha256Hex(internalFile.readBytes())
            if (receivedHash != hashHex) { internalFile.delete(); EventLog.log("sync", "Incoming hash mismatch - discarded"); return false }
            runBlocking {
                subscriptionDao.updateReceived(fileId, version, internalFile.absolutePath, version, System.currentTimeMillis(), resolvedName)
                fileService.evictOldVersions(fileId, version)
                if (subscription.lastNotifiedVersion == null || subscription.lastNotifiedVersion < version) {
                    notificationService.showUpdateNotification(resolvedName, fileId, subscription.localVersion ?: 0, version)
                    subscriptionDao.updateLastNotified(fileId, version)
                }
                val relayFileIdBytes = uuidToBytes(fileId) ?: return@runBlocking
                val relayKeyId = cryptoService.keyId(subscription.publicKey)
                val relayMeta = BleMetaPayload(relayFileIdBytes, version, fileHash, size, resolvedName, relayKeyId, blePeripheralService.getDeviceUuidBytes())
                broadcastDao.upsert(BroadcastEntity(fileId, resolvedName, subscription.relayName ?: fileId.take(8), "application/octet-stream", internalFile.absolutePath, Base64.getEncoder().encodeToString(fileHash), internalFile.length(), size, version, subscription.publicKey, null, "", Role.RELAY, subscription.subscribedAt, System.currentTimeMillis()))
                blePeripheralService.startAdvertising(fileId, relayMeta.toBytes())
                onFileReceived?.invoke(fileId, version)
                EventLog.log("sync", "Incoming push for \"$resolvedName\" v$version received and stored")
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming file $fileId", e); EventLog.log("sync", "Error handling incoming file $fileId: ${e.message}")
            try { tempFile.delete() } catch (_: Exception) {}
            return false
        }
    }

    /** Relay keeping its own copy current: verify meta, pull bytes, re-sign, update DB. */
    private suspend fun fetchAndUpdateBroadcast(broadcast: BroadcastEntity, newVersion: Int, deviceAddress: String, advMeta: BleMetaPayload?): Boolean {
        // Derive the device id from the META payload, not the (random/resolvable
        // private) MAC address. BLE peers rotate their MAC between scans, so a
        // MAC-keyed map is unreliable; the device UUID embedded in the payload is
        // the stable identifier. Fall back to the scan map / MAC only if no meta.
        var deviceId = advMeta?.deviceId?.joinToString("") { "%02x".format(it) }
            ?: deviceToDeviceId[deviceAddress] ?: deviceAddress
        if (CongestionPauses.isCongested(deviceId)) {
                    EventLog.log("sync", "Skipping download from ${deviceAddress.takeLast(5)} (deviceId=$deviceId) for \"${broadcast.fileName}\" - congestion pause active")
                    EventLog.log("sync", "Congestion pause active for ${deviceAddress.takeLast(5)} (deviceId=$deviceId, TTL=${CongestionPauses.remainingMs(deviceId) / 1000}s)")
            return false
        }
        // Prevent multiple concurrent downloads for the same fileId - atomic check-and-add
        downloadMutex.withLock {
            if (_downloadingFileIds.value.contains(broadcast.fileId)) {
                EventLog.log("sync", "Download already in progress for \"${broadcast.fileName}\" - skipping duplicate (current size=${_downloadingFileIds.value.size})")
                return false
            }
        }
        // Wait if there's an active transfer
        while (transferMutex.isLocked || activeUploadPeers.contains(deviceAddress)) {
            kotlinx.coroutines.delay(100)
        }
        // Only one transfer at a time, and only one transfer per peer
        var downloadResult = false
        var skippedDueToCongestion = false
        transferMutex.withLock {
            peerLock(deviceAddress).withLock {
                stopAdvertisingAndScanning()
                blePeripheralService.stopGattServer()
                kotlinx.coroutines.delay(2_000L)
                EventLog.log("sync", "Download starting for \"${broadcast.fileName}\" v$newVersion from ${deviceAddress.takeLast(5)}")
                try {
                    _downloadingFileIds.value = _downloadingFileIds.value + broadcast.fileId
                    activeDownloadPeers.add(deviceAddress)
                    activeDownloadJobs[broadcast.fileId] = currentCoroutineContext()[Job]!!
                    downloadingFileDeviceMap[broadcast.fileId] = deviceAddress
                    EventLog.log("sync", "Download started for \"${broadcast.fileName}\" v$newVersion from ${deviceAddress.takeLast(5)}")
                    kotlinx.coroutines.delay(1_000L + (Math.random() * 1_000).toLong())
                    val fileIdHash = cryptoService.fileIdHash(broadcast.fileId)
                    val metaPayload = advMeta ?: bleCentralService.readMeta(deviceAddress, fileIdHash) ?: run {
                        EventLog.log("sync", "No meta payload from ${deviceAddress.takeLast(5)} - aborting relay update"); return@withLock
                    }
                    // Reaffirm the stable device id from the authoritative META payload.
                    deviceId = metaPayload.deviceId.joinToString("") { "%02x".format(it) }
                    deviceToDeviceId[deviceAddress] = deviceId
                    if (CongestionPauses.isCongested(deviceId)) {
                        EventLog.log("sync", "Skipping download from ${deviceAddress.takeLast(5)} (deviceId=$deviceId) for \"${broadcast.fileName}\" - congestion pause active")
                        EventLog.log("sync", "Congestion pause active for ${deviceAddress.takeLast(5)} (deviceId=$deviceId, TTL=${CongestionPauses.remainingMs(deviceId) / 1000}s)")
                        skippedDueToCongestion = true
                        return@withLock
                    }
                    if (metaPayload.fileSize > MAX_FILE_SIZE) {
                        EventLog.log("sync", "Relay file too large (compressed+encrypted ${metaPayload.fileSize}B > ${MAX_FILE_SIZE}B) - aborting"); return@withLock
                    }
                    val hashHex = metaPayload.fileHash.joinToString("") { "%02x".format(it) }
                    val tmpFile = fileService.getTmpFile(broadcast.fileId, newVersion)
                    tmpFile.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Cannot create temporary download directory") }
                    EventLog.log("ble", "Streaming ${broadcast.fileId} v$newVersion (${metaPayload.fileSize}B compressed) over GATT")
                    val transferred = try {
                        tmpFile.outputStream().use { output ->
                            bleCentralService.fetchFile(deviceAddress, newVersion, metaPayload.fileSize, output, { got, total ->
                                val progress = (got.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                _downloadProgress.value = _downloadProgress.value + (broadcast.fileId to progress)
                            }, cryptoService.fileIdHash(broadcast.fileId))
                        }
                    } catch (e: Exception) { EventLog.log("ble", "fetchFile error: ${e.message}"); false }
                    if (!transferred) { tmpFile.delete(); val dedupKey = "${cryptoService.fileIdHash(broadcast.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"; dedupCache.remove(dedupKey); EventLog.log("wifi", "Transfer failed for ${broadcast.fileId}"); return@withLock }
                    if (!tmpFile.isFile || tmpFile.length() != metaPayload.fileSize) { EventLog.log("sync", "Downloaded file size mismatch for ${broadcast.fileId}: ${tmpFile.length()}/${metaPayload.fileSize}B"); tmpFile.delete(); val dedupKey = "${cryptoService.fileIdHash(broadcast.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"; dedupCache.remove(dedupKey); return@withLock }
                    val encrypted = tmpFile.readBytes()
                    // Relays forward the original encrypted envelope unchanged. Persist it so this
                    // relay can re-serve it later (the relay has no originator private key to re-encrypt).
                    val persistedEnvelope = File(fileService.getVersionDir(broadcast.fileId, newVersion), "file.encrypted")
                    persistedEnvelope.parentFile?.mkdirs()
                    persistedEnvelope.writeBytes(encrypted)
                    val compressed = try { cryptoService.decryptCompressed(encrypted, cryptoService.publicKeyFromBase64(broadcast.publicKey)) } catch (e: Exception) { tmpFile.delete(); EventLog.log("sync", "Decrypt failed: ${e.message}"); val dedupKey = "${cryptoService.fileIdHash(broadcast.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"; dedupCache.remove(dedupKey); return@withLock }
                    tmpFile.writeBytes(compressed)
                    val internalFile = fileService.getFile(broadcast.fileId, newVersion)
                    try { fileService.decompressFile(tmpFile, internalFile); tmpFile.delete() } catch (e: Exception) { EventLog.log("sync", "Decompression failed for ${broadcast.fileId}: ${e.message}"); tmpFile.delete(); internalFile.delete(); val dedupKey = "${cryptoService.fileIdHash(broadcast.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"; dedupCache.remove(dedupKey); return@withLock }
                    val receivedHash = cryptoService.sha256Hex(internalFile.readBytes())
                    if (receivedHash != hashHex) { internalFile.delete(); EventLog.log("sync", "Hash mismatch after transfer of ${broadcast.fileId} - discarded"); val dedupKey = "${cryptoService.fileIdHash(broadcast.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"; dedupCache.remove(dedupKey); return@withLock }
                    lastProbeAt["$deviceAddress:${cryptoService.fileIdHash(broadcast.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"] = System.currentTimeMillis()
                    broadcastDao.updateVersion(broadcast.fileId, newVersion, Base64.getEncoder().encodeToString(metaPayload.fileHash), "", internalFile.absolutePath, internalFile.length(), encrypted.size.toLong(), System.currentTimeMillis(), metaPayload.fileName.takeIf { it.isNotBlank() } ?: broadcast.fileName)
                    subscriptionDao.updateReceived(broadcast.fileId, newVersion, internalFile.absolutePath, newVersion, System.currentTimeMillis(), metaPayload.fileName.takeIf { it.isNotBlank() } ?: broadcast.fileName)
                    EventLog.log("sync", "Subscription record updated to v$newVersion for ${broadcast.fileId}")
                    fileService.evictOldVersions(broadcast.fileId, newVersion)
                    EventLog.log("sync", "Relay copy updated: ${broadcast.fileName} v${broadcast.version} -> v$newVersion")
                    notificationService.showUpdateNotification(broadcast.fileName, broadcast.fileId, broadcast.version, newVersion)
                    blePeripheralService.stopAdvertising(broadcast.fileId)
                    broadcastDao.getById(broadcast.fileId)?.let { startAdvertising(it) }
                    downloadResult = true
                     CongestionPauses.reset(deviceId)
                } catch (e: Exception) { Log.e(TAG, "Error fetching update ${broadcast.fileId}", e); EventLog.log("sync", "Error fetching update ${broadcast.fileId}: ${e.message}") }
                finally {
                    activeDownloadPeers.remove(deviceAddress)
                    _downloadingFileIds.value = _downloadingFileIds.value - broadcast.fileId
                    EventLog.log("sync", "Finally: Removed ${broadcast.fileId.takeLast(8)} from downloadingFileIds (now: ${_downloadingFileIds.value.size})")
                    _downloadProgress.value = _downloadProgress.value - broadcast.fileId
                    downloadingFileDeviceMap.remove(broadcast.fileId)
                    activeDownloadJobs.remove(broadcast.fileId)
                    if (!downloadResult && !skippedDueToCongestion) {
                        CongestionPauses.record(deviceId, fullRotationInterval())
                        EventLog.log("sync", "Congestion recorded for $deviceId (peer ${deviceAddress.takeLast(5)}): TTL=${fullRotationInterval() / 1000}s (download failed)")
                        EventLog.log("sync", "Stopped downloading \"${broadcast.fileName}\"")
                    } else {
                        EventLog.log("sync", "Download finished for \"${broadcast.fileName}\"")
                    }
                }
            } // peerLock
        } // transferMutex - unlocked here
        resumeAdvertisingAndScanning()
        return downloadResult
    }

    /** Subscriber receiving a new version of a subscribed file. */
    private suspend fun fetchAndUpdateSubscription(subscription: SubscriptionEntity, newVersion: Int, deviceAddress: String, advMeta: BleMetaPayload?): Boolean {
        // Derive the device id from the META payload, not the (random/resolvable
        // private) MAC address. BLE peers rotate their MAC between scans, so a
        // MAC-keyed map is unreliable; the device UUID embedded in the payload is
        // the stable identifier. Fall back to the scan map / MAC only if no meta.
        var deviceId = advMeta?.deviceId?.joinToString("") { "%02x".format(it) }
            ?: deviceToDeviceId[deviceAddress] ?: deviceAddress
        if (CongestionPauses.isCongested(deviceId)) {
                    EventLog.log("sync", "Skipping download from ${deviceAddress.takeLast(5)} (deviceId=$deviceId) for \"${subscription.fileName ?: subscription.fileId}\" - congestion pause active")
                    EventLog.log("sync", "Congestion pause active for ${deviceAddress.takeLast(5)} (deviceId=$deviceId, TTL=${CongestionPauses.remainingMs(deviceId) / 1000}s)")
            return false
        }
        downloadMutex.withLock {
            if (_downloadingFileIds.value.contains(subscription.fileId)) {
                EventLog.log("sync", "Download already in progress for \"${subscription.fileName ?: subscription.fileId}\" - skipping duplicate (current size=${_downloadingFileIds.value.size})")
                return false
            }
            val retries = downloadRetryCount.getOrDefault(subscription.fileId, 0)
            if (retries >= maxRetries) {
                EventLog.log("sync", "Max retries ($maxRetries) reached for \"${subscription.fileName ?: subscription.fileId}\" - giving up")
                return false
            }
        }
        while (activeUploadPeers.contains(deviceAddress)) {
            kotlinx.coroutines.delay(100)
        }
        var downloadResult = false
        var skippedDueToCongestion = false
        transferMutex.withLock {
            peerLock(deviceAddress).withLock {
                stopAdvertisingAndScanning()
                blePeripheralService.stopGattServer()
                kotlinx.coroutines.delay(2_000L)
                EventLog.log("sync", "Download starting for \"${subscription.fileName ?: subscription.fileId}\" v$newVersion from ${deviceAddress.takeLast(5)}")
                try {
                    _downloadingFileIds.value = _downloadingFileIds.value + subscription.fileId
                    EventLog.log("sync", "Added ${subscription.fileId.takeLast(8)} to downloadingFileIds (now: ${_downloadingFileIds.value.size})")
                    activeDownloadPeers.add(deviceAddress)
                    activeDownloadJobs[subscription.fileId] = currentCoroutineContext()[Job]!!
                    downloadingFileDeviceMap[subscription.fileId] = deviceAddress
                    EventLog.log("sync", "Download started for \"${subscription.fileName ?: subscription.fileId}\" v$newVersion from ${deviceAddress.takeLast(5)}")
                    kotlinx.coroutines.delay(1_000L + (Math.random() * 1_000).toLong())
                    val fileIdHash = cryptoService.fileIdHash(subscription.fileId)
                    var metaPayload: BleMetaPayload? = advMeta
                    if (metaPayload == null) {
                        try { metaPayload = withTimeout(45_000L) { bleCentralService.readMeta(deviceAddress, fileIdHash) } } catch (_: Exception) {}
                    }
                    if (metaPayload == null) { EventLog.log("sync", "No meta payload from ${deviceAddress.takeLast(5)} - aborting fetch"); return@withLock }
                    // Reaffirm the stable device id from the authoritative META payload.
                    deviceId = metaPayload.deviceId.joinToString("") { "%02x".format(it) }
                    deviceToDeviceId[deviceAddress] = deviceId
                    if (CongestionPauses.isCongested(deviceId)) {
                        EventLog.log("sync", "Skipping download from ${deviceAddress.takeLast(5)} (deviceId=$deviceId) for \"${subscription.fileName ?: subscription.fileId}\" - congestion pause active")
                        EventLog.log("sync", "Congestion pause active for ${deviceAddress.takeLast(5)} (deviceId=$deviceId, TTL=${CongestionPauses.remainingMs(deviceId) / 1000}s)")
                        skippedDueToCongestion = true
                        return@withLock
                    }
                    if (metaPayload.fileSize > MAX_FILE_SIZE) { EventLog.log("sync", "File too large (${metaPayload.fileSize}B > ${MAX_FILE_SIZE}B) - aborting"); return@withLock }
                    val hashHex = metaPayload.fileHash.joinToString("") { "%02x".format(it) }
                    EventLog.log("gatt", "Meta verified for \"${subscription.fileName ?: subscription.fileId}\" v$newVersion")
                    val tmpFile = fileService.getTmpFile(subscription.fileId, newVersion)
                    tmpFile.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Cannot create temp download dir") }
                    EventLog.log("ble", "Streaming ${subscription.fileId} v$newVersion (${metaPayload.fileSize}B compressed) over GATT")
                    val transferred = try {
                        tmpFile.outputStream().use { output ->
                            bleCentralService.fetchFile(deviceAddress, newVersion, metaPayload.fileSize, output, { got, total ->
                                val progress = (got.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                _downloadProgress.value = _downloadProgress.value + (subscription.fileId to progress)
                            }, cryptoService.fileIdHash(subscription.fileId))
                        }
                    } catch (e: Exception) { EventLog.log("ble", "fetchFile error: ${e.message}"); false }
                    EventLog.log("ble", "After fetchFile: transferred=$transferred, tmpFile.exists()=${tmpFile.exists()}, tmpFile.length()=${tmpFile.length()}")
                    if (!transferred) { tmpFile.delete(); downloadRetryCount[subscription.fileId] = downloadRetryCount.getOrDefault(subscription.fileId, 0) + 1; val dedupKey = "${cryptoService.fileIdHash(subscription.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"; dedupCache.remove(dedupKey); EventLog.log("sync", "Transfer failed for ${subscription.fileId} (retry ${downloadRetryCount[subscription.fileId]}/$maxRetries)"); return@withLock }
                    if (!tmpFile.isFile || tmpFile.length() != metaPayload.fileSize) { EventLog.log("sync", "Downloaded file size mismatch for ${subscription.fileId}: ${tmpFile.length()}/${metaPayload.fileSize}B"); tmpFile.delete(); downloadRetryCount[subscription.fileId] = downloadRetryCount.getOrDefault(subscription.fileId, 0) + 1; val dedupKey = "${cryptoService.fileIdHash(subscription.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"; dedupCache.remove(dedupKey); return@withLock }
                    val encrypted = tmpFile.readBytes()
                    val persistedEnvelope = File(fileService.getVersionDir(subscription.fileId, newVersion), "file.encrypted")
                    persistedEnvelope.parentFile?.mkdirs()
                    persistedEnvelope.writeBytes(encrypted)
                    val compressed = try { cryptoService.decryptCompressed(encrypted, cryptoService.publicKeyFromBase64(subscription.publicKey)) } catch (e: Exception) { tmpFile.delete(); EventLog.log("sync", "Decrypt failed: ${e.message}"); val dedupKey = "${cryptoService.fileIdHash(subscription.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"; dedupCache.remove(dedupKey); return@withLock }
                    tmpFile.writeBytes(compressed)
                    val internalFile = fileService.getFile(subscription.fileId, newVersion)
                    try { fileService.decompressFile(tmpFile, internalFile); tmpFile.delete() } catch (e: Exception) { EventLog.log("sync", "Decompression failed for ${subscription.fileId}: ${e.message}"); tmpFile.delete(); internalFile.delete(); downloadRetryCount[subscription.fileId] = downloadRetryCount.getOrDefault(subscription.fileId, 0) + 1; return@withLock }
                    val receivedHash = cryptoService.sha256Hex(internalFile.readBytes())
                    if (receivedHash != hashHex) { internalFile.delete(); downloadRetryCount[subscription.fileId] = downloadRetryCount.getOrDefault(subscription.fileId, 0) + 1; EventLog.log("sync", "Hash mismatch after transfer of ${subscription.fileId} - discarded"); return@withLock }
                    downloadRetryCount.remove(subscription.fileId)
                    val probeKey = "$deviceAddress:${cryptoService.fileIdHash(subscription.fileId).joinToString("") { "%02x".format(it) }}:$newVersion"
                    lastProbeAt[probeKey] = System.currentTimeMillis()
                    val resolvedFileName = metaPayload.fileName.takeIf { it.isNotBlank() } ?: subscription.fileName ?: "File"
                    subscriptionDao.updateReceived(subscription.fileId, newVersion, internalFile.absolutePath, newVersion, System.currentTimeMillis(), resolvedFileName)
                    fileService.evictOldVersions(subscription.fileId, newVersion)
                    if (subscription.localVersion == null) EventLog.log("sync", "File \"$resolvedFileName\" downloaded!") else EventLog.log("sync", "File \"$resolvedFileName\" updated from v${subscription.localVersion} to v$newVersion!")
                    if (subscription.lastNotifiedVersion == null || subscription.lastNotifiedVersion < newVersion) {
                        notificationService.showUpdateNotification(resolvedFileName, subscription.fileId, subscription.localVersion ?: 0, newVersion)
                        subscriptionDao.updateLastNotified(subscription.fileId, newVersion)
                    }
                    val relayFileIdBytes = uuidToBytes(subscription.fileId) ?: return@withLock
                    val relayKeyId = cryptoService.keyId(subscription.publicKey)
                    // Name is omitted from the relay *advertisement* for privacy; the stored
                    // BroadcastEntity (and the GATT META read / push header) still carry it.
                    val relayMetaPayload = BleMetaPayload(relayFileIdBytes, newVersion, metaPayload.fileHash, metaPayload.fileSize, "", relayKeyId, blePeripheralService.getDeviceUuidBytes())
                    broadcastDao.upsert(BroadcastEntity(subscription.fileId, resolvedFileName, subscription.relayName ?: subscription.fileId.take(8), "application/octet-stream", internalFile.absolutePath, Base64.getEncoder().encodeToString(metaPayload.fileHash), internalFile.length(), metaPayload.fileSize, newVersion, subscription.publicKey, null, "", Role.RELAY, subscription.subscribedAt, System.currentTimeMillis()))
                    blePeripheralService.startAdvertising(subscription.fileId, relayMetaPayload.toBytes())
                    EventLog.log("adv", "Relaying \"${subscription.fileName ?: subscription.fileId}\" v$newVersion")
                    onFileReceived?.invoke(subscription.fileId, newVersion)
                     downloadResult = true
                     CongestionPauses.reset(deviceId)
                } catch (e: Exception) { Log.e(TAG, "Error fetching sub update ${subscription.fileId}", e); EventLog.log("sync", "Error fetching sub update ${subscription.fileId}: ${e.message}") }
                finally {
                    activeDownloadPeers.remove(deviceAddress)
                    _downloadingFileIds.value = _downloadingFileIds.value - subscription.fileId
                    EventLog.log("sync", "Finally: Removed ${subscription.fileId.takeLast(8)} from downloadingFileIds (now: ${_downloadingFileIds.value.size})")
                    _downloadProgress.value = _downloadProgress.value - subscription.fileId
                    downloadingFileDeviceMap.remove(subscription.fileId)
                    activeDownloadJobs.remove(subscription.fileId)
                    if (!downloadResult && !skippedDueToCongestion) {
                        val ttl = CongestionPauses.record(deviceId, fullRotationInterval()) / 1000
                        EventLog.log("sync", "Congestion recorded for $deviceId (peer ${deviceAddress.takeLast(5)}): TTL=${ttl}s (download failed)")
                        EventLog.log("sync", "Stopped downloading \"${subscription.fileName ?: subscription.fileId}\"")
                    } else {
                        EventLog.log("sync", "Download finished for \"${subscription.fileName ?: subscription.fileId}\"")
                    }
                }
            } // peerLock
        } // transferMutex - unlocked here
        resumeAdvertisingAndScanning()
        return downloadResult
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
        _downloadingFileIds.value = _downloadingFileIds.value - fileId
        _downloadProgress.value = _downloadProgress.value - fileId
        blePeripheralService.stopStreaming(fileId)
        resumeAdvertisingAndScanning()
    }

    private fun cancelTransfersForDevice(deviceAddress: String) {
        EventLog.log("sync", "Cancelling transfers for ${deviceAddress.takeLast(5)} due to congestion")
        val fileIds = _downloadingFileIds.value.filter { downloadingFileDeviceMap[it] == deviceAddress }
        for (fileId in fileIds) {
            activeDownloadJobs.remove(fileId)?.cancel()
            downloadingFileDeviceMap.remove(fileId)
            _downloadingFileIds.value = _downloadingFileIds.value - fileId
            _downloadProgress.value = _downloadProgress.value - fileId
            bleCentralService.disconnectDevice(deviceAddress)
        }
        activeDownloadPeers.remove(deviceAddress)
        if (activeUploadPeers.remove(deviceAddress)) {
            blePeripheralService.disconnectPeer(deviceAddress)
            transferMutex.unlock()
        }
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

    private fun uuidToString(bytes: ByteArray): String {
        require(bytes.size == 16) { "UUID bytes must be 16 bytes" }
        var msb = 0L; var lsb = 0L
        for (i in 0..7) { msb = (msb shl 8) or (bytes[i].toLong() and 0xFF); lsb = (lsb shl 8) or (bytes[8 + i].toLong() and 0xFF) }
        return UUID(msb, lsb).toString()
    }

    fun destroy() { stop(); scope.cancel() }
}
