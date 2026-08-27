package p2p.broadcaster

import java.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SyncEngine(
    private val broadcastDao: BroadcastDao,
    private val subscriptionDao: SubscriptionDao,
    private val cryptoService: CryptoService,
    private val fileService: FileService,
    private val bleCentralService: BleCentralService,
    private val blePeripheralService: BlePeripheralService,
    private val wifiDirectService: WifiDirectService,
    private val notificationService: NotificationService,
    testScope: CoroutineScope? = null
) {
    companion object {
        private const val TAG = "SyncEngine"
        private const val DEDUP_TTL_MS = 30_000L
        private const val PROBE_COOLDOWN_MS = 5 * 60_000L
    }

    private val scope = testScope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var engineJob: Job? = null
    private val dedupCache = ConcurrentHashMap<String, Long>()
    // After successfully learning a peer's file/version info via a GATT meta read,
    // do not probe that device again for PROBE_COOLDOWN_MS.
    private val lastProbeAt = ConcurrentHashMap<String, Long>()
    var onFileReceived: ((fileId: String, version: Int) -> Unit)? = null

    fun start() {
        if (engineJob?.isActive == true) return
        engineJob = scope.launch {
            Log.d(TAG, "SyncEngine started")
            EventLog.log("sync", "Engine started - scanning, advertising and transfer server active")
            blePeripheralService.setMetaPayloadProvider { fileId -> buildMetaPayload(fileId) }
            // GATT streaming: serves bytes of the file we advertise.
            blePeripheralService.setServeFileLoader { fileId, version ->
                val file = fileService.getFile(fileId, version)
                if (file.exists()) file.readBytes() else null
            }
            bleCentralService.onDeviceDiscovered = { address, serviceData ->
                scope.launch { handleDiscoveredDevice(address, serviceData) }
            }
            wifiDirectService.onRequestFile = { fileId, version, output ->
                val file = fileService.getFile(fileId, version)
                if (!file.exists()) throw IllegalStateException("Requested file not available: $fileId v$version")
                file.inputStream().use { it.copyTo(output) }
            }
            try { blePeripheralService.startGattServer() } catch (e: Exception) { Log.e(TAG, "startGattServer failed", e); EventLog.log("ble", "startGattServer failed: ${e.message}") }
            launch {
                while (true) {
                    broadcastDao.changeFlow.collect {
                        val broadcasts = broadcastDao.getAll()
                        for (b in broadcasts) {
                            try { startAdvertising(b) } catch (e: Exception) { Log.e(TAG, "startAdvertising failed", e); EventLog.log("ble", "startAdvertising failed: ${e.message}") }
                        }
                    }
                }
            }
            try { bleCentralService.startScan() } catch (e: Exception) { Log.e(TAG, "startScan failed", e); EventLog.log("ble", "startScan failed: ${e.message}") }
            wifiDirectService.initialize()
            try { wifiDirectService.startServer() } catch (e: Exception) { Log.e(TAG, "wifi startServer failed", e); EventLog.log("wifi", "startServer failed: ${e.message}") }
        }
    }

    fun stop() {
        engineJob?.cancel(); engineJob = null
        bleCentralService.stopScan()
        blePeripheralService.stopAllAdvertising()
    }

    suspend fun buildMetaPayload(fileId: String): BleMetaPayload? {
        val broadcast = broadcastDao.getById(fileId) ?: return null
        val fileIdBytes = uuidToBytes(fileId) ?: return null
        val pubKeyBytes = cryptoService.rawPublicKey(cryptoService.publicKeyFromBase64(broadcast.publicKey))
        val sigBytes = Base64.getDecoder().decode(broadcast.signature)
        val hashBytes = Base64.getDecoder().decode(broadcast.fileHash)
        return BleMetaPayload(fileIdBytes, broadcast.version, pubKeyBytes, sigBytes, hashBytes, broadcast.fileSize)
    }

    fun startAdvertising(broadcast: BroadcastEntity) {
        val serviceData = ByteArray(14)
        cryptoService.fileIdHash(broadcast.fileId).copyInto(serviceData, 0)
        serviceData[6] = ((broadcast.version ushr 24) and 0xFF).toByte()
        serviceData[7] = ((broadcast.version ushr 16) and 0xFF).toByte()
        serviceData[8] = ((broadcast.version ushr 8) and 0xFF).toByte()
        serviceData[9] = (broadcast.version and 0xFF).toByte()
        cryptoService.keyId(broadcast.publicKey).copyInto(serviceData, 10)
        blePeripheralService.startAdvertising(broadcast.fileId, serviceData)
        wifiDirectService.setDeviceTag(cryptoService.keyId(broadcast.publicKey))
        EventLog.log("adv", "Advertising \"${broadcast.fileName}\" v${broadcast.version} (${broadcast.fileSize}B)")
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
        EventLog.log("scan", "Heard advertisement v$version from ${deviceAddress.takeLast(5)}")
        val dedupKey = "${fileIdHash.joinToString("") { "%02x".format(it) }}:$version"
        val now = System.currentTimeMillis()
        dedupCache[dedupKey]?.let { if (now - it < DEDUP_TTL_MS) { EventLog.log("scan", "Dedupe: skipping duplicate adv (seen ${now - it}ms ago)"); return false } }
        dedupCache[dedupKey] = now

        // Per-device probe cooldown: once we have learned a peer's file/version info,
        // don't connect to it again for PROBE_COOLDOWN_MS.
        lastProbeAt[deviceAddress]?.let {
            if (now - it < PROBE_COOLDOWN_MS) {
                EventLog.log("scan", "Probe cooldown: ${deviceAddress.takeLast(5)} probed ${"%.1f".format((now - it) / 1000.0)}s ago (< ${PROBE_COOLDOWN_MS / 60000}min) - skipping")
                return false
            }
        }

        val broadcasts = broadcastDao.getAll()
        // Detect hearing our own advertisements (same-chip echo / relay loops)
        val selfMatch = broadcasts.firstOrNull { b -> cryptoService.fileIdHash(b.fileId).contentEquals(fileIdHash) }
        val broadcast = selfMatch
        if (broadcast != null) {
            EventLog.log("scan", "Adv hash matches MY OWN broadcast \"${broadcast.fileName}\" (local v${broadcast.version}, adv v$version, device=$deviceAddress)")
            if (version <= broadcast.version) {
                EventLog.log("scan", "\"${broadcast.fileName}\" already at v${broadcast.version} - adv v$version not newer, skipped")
                return false
            }
            EventLog.log("scan", "Matched relay copy \"${broadcast.fileName}\" v${broadcast.version} -> fetching v$version")
            return fetchAndUpdateBroadcast(broadcast, version, deviceAddress)
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
        try {
            val metaPayload = bleCentralService.readMeta(deviceAddress) ?: run {
                EventLog.log("sync", "No meta payload from ${deviceAddress.takeLast(5)} - aborting relay update"); return false
            }
            lastProbeAt[deviceAddress] = System.currentTimeMillis()
            val pubKey = cryptoService.publicKeyFromRaw(metaPayload.publicKey)
            val hashHex = metaPayload.fileHash.joinToString("") { "%02x".format(it) }
            val msg = cryptoService.buildSignatureMessage(broadcast.fileId, newVersion, hashHex)
            if (!cryptoService.verify(msg, metaPayload.signature, pubKey)) {
                EventLog.log("sync", "Signature check FAILED for relay update ${broadcast.fileId}")
                Log.e(TAG, "Sig verify failed ${broadcast.fileId}"); return false
            }
            EventLog.log("gatt", "Meta verified for ${broadcast.fileName} v$newVersion")
            val tmpFile = fileService.getTmpFile(broadcast.fileId, newVersion); tmpFile.parentFile?.mkdirs()
            EventLog.log("ble", "Streaming ${broadcast.fileId} v$newVersion (${metaPayload.fileSize}B) over GATT")
            val transferred = try { bleCentralService.fetchFile(deviceAddress, newVersion, metaPayload.fileSize, tmpFile.outputStream()) } catch (e: Exception) {
                EventLog.log("ble", "fetchFile error: ${e.message}"); false
            }
            if (!transferred) { tmpFile.delete(); EventLog.log("wifi", "Transfer failed for ${broadcast.fileId}"); return false }
            val receivedHash = cryptoService.sha256Hex(tmpFile.readBytes())
            if (receivedHash != hashHex) {
                tmpFile.delete(); EventLog.log("sync", "Hash mismatch after transfer of ${broadcast.fileId} - discarded")
                return false
            }
            val internalFile = fileService.getFile(broadcast.fileId, newVersion); tmpFile.renameTo(internalFile)
            val privateKey = cryptoService.getPrivateKey(broadcast.privateKeyAlias!!)
                ?: throw IllegalStateException("Private key not found for ${broadcast.fileId}")
            val newSignature = Base64.getEncoder().encodeToString(cryptoService.sign(cryptoService.buildSignatureMessage(broadcast.fileId, newVersion, receivedHash), privateKey))
            broadcastDao.updateVersion(broadcast.fileId, newVersion, Base64.getEncoder().encodeToString(metaPayload.fileHash), newSignature, internalFile.absolutePath, metaPayload.fileSize, System.currentTimeMillis())
            fileService.evictOldVersions(broadcast.fileId, newVersion)
            EventLog.log("sync", "Relay copy updated: ${broadcast.fileName} v${broadcast.version} -> v$newVersion")
            notificationService.showUpdateNotification(broadcast.fileName, broadcast.fileId, broadcast.version, newVersion)
            blePeripheralService.stopAdvertising(broadcast.fileId)
            broadcastDao.getById(broadcast.fileId)?.let { startAdvertising(it) }
            return true
        } catch (e: Exception) { Log.e(TAG, "Error fetching update ${broadcast.fileId}", e); EventLog.log("sync", "Error fetching update ${broadcast.fileId}: ${e.message}"); return false }
    }

    /** Subscriber receiving a new version of a subscribed file. */
    private suspend fun fetchAndUpdateSubscription(subscription: SubscriptionEntity, newVersion: Int, deviceAddress: String): Boolean {
        try {
            val metaPayload = bleCentralService.readMeta(deviceAddress) ?: run {
                EventLog.log("sync", "No meta payload from ${deviceAddress.takeLast(5)} - aborting fetch"); return false
            }
            lastProbeAt[deviceAddress] = System.currentTimeMillis()
            val pubKey = cryptoService.publicKeyFromRaw(metaPayload.publicKey)
            // The key embedded in the GATT payload must be the very key this
            // subscription was created with (QR scan), otherwise reject.
            if (!cryptoService.rawPublicKey(pubKey).contentEquals(cryptoService.rawPublicKey(cryptoService.publicKeyFromBase64(subscription.publicKey)))) {
                EventLog.log("sync", "SECURITY: publisher key mismatch for ${subscription.fileId} - rejected")
                Log.e(TAG, "Publisher key mismatch ${subscription.fileId}"); return false
            }
            val hashHex = metaPayload.fileHash.joinToString("") { "%02x".format(it) }
            val msg = cryptoService.buildSignatureMessage(subscription.fileId, newVersion, hashHex)
            if (!cryptoService.verify(msg, metaPayload.signature, pubKey)) {
                EventLog.log("sync", "Signature check FAILED for ${subscription.fileName ?: subscription.fileId}")
                return false
            }
            EventLog.log("gatt", "Meta verified for \"${subscription.fileName ?: subscription.fileId}\" v$newVersion")
            val tmpFile = fileService.getTmpFile(subscription.fileId, newVersion); tmpFile.parentFile?.mkdirs()
            EventLog.log("ble", "Streaming ${subscription.fileId} v$newVersion (${metaPayload.fileSize}B) over GATT")
            val transferred = try { bleCentralService.fetchFile(deviceAddress, newVersion, metaPayload.fileSize, tmpFile.outputStream()) } catch (e: Exception) {
                EventLog.log("ble", "fetchFile error: ${e.message}"); false
            }
            if (!transferred) { tmpFile.delete(); EventLog.log("wifi", "Transfer failed for ${subscription.fileId}"); return false }
            val receivedHash = cryptoService.sha256Hex(tmpFile.readBytes())
            if (receivedHash != hashHex) {
                tmpFile.delete(); EventLog.log("sync", "Hash mismatch after transfer of ${subscription.fileId} - discarded")
                return false
            }
            val internalFile = fileService.getFile(subscription.fileId, newVersion); tmpFile.renameTo(internalFile)
            subscriptionDao.updateReceived(subscription.fileId, newVersion, internalFile.absolutePath, newVersion, System.currentTimeMillis())
            fileService.evictOldVersions(subscription.fileId, newVersion)
            EventLog.log("sync", "Received \"${subscription.fileName ?: subscription.fileId}\" v$newVersion (${metaPayload.fileSize}B)")
            val shouldNotify = subscription.lastNotifiedVersion == null || subscription.lastNotifiedVersion < newVersion
            if (shouldNotify) {
                notificationService.showUpdateNotification(subscription.fileName ?: "File", subscription.fileId, subscription.localVersion ?: 0, newVersion)
                subscriptionDao.updateLastNotified(subscription.fileId, newVersion)
            }
            // Become a relay: register as broadcast and advertise the same triple.
            val relayPayload = ByteArray(14)
            cryptoService.fileIdHash(subscription.fileId).copyInto(relayPayload, 0)
            relayPayload[6] = ((newVersion ushr 24) and 0xFF).toByte(); relayPayload[7] = ((newVersion ushr 16) and 0xFF).toByte()
            relayPayload[8] = ((newVersion ushr 8) and 0xFF).toByte(); relayPayload[9] = (newVersion and 0xFF).toByte()
            cryptoService.keyId(subscription.publicKey).copyInto(relayPayload, 10)
            broadcastDao.upsert(BroadcastEntity(subscription.fileId, subscription.fileName ?: "File", "application/octet-stream", internalFile.absolutePath, Base64.getEncoder().encodeToString(metaPayload.fileHash), metaPayload.fileSize, newVersion, subscription.publicKey, null, Base64.getEncoder().encodeToString(metaPayload.signature), Role.RELAY, subscription.subscribedAt, System.currentTimeMillis()))
            blePeripheralService.startAdvertising(subscription.fileId, relayPayload)
            EventLog.log("adv", "Relaying \"${subscription.fileName ?: subscription.fileId}\" v$newVersion")
            onFileReceived?.invoke(subscription.fileId, newVersion)
            return true
        } catch (e: Exception) { Log.e(TAG, "Error fetching sub update ${subscription.fileId}", e); EventLog.log("sync", "Error fetching sub update ${subscription.fileId}: ${e.message}"); return false }
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
