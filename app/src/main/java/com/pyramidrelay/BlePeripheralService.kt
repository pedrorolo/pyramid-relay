package com.pyramidrelay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import com.pyramidrelay.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

class BlePeripheralService(private val context: Context, private val transferMutex: Mutex = Mutex()) {
    companion object {
        const val ROTATION_INTERVAL_MS = 1_000L
        private const val TAG = "BlePeripheral"
    private const val DEVICE_UUID_PREF = "pyramid_device_uuid"
    private val SERVICE_UUID = UUID.fromString(APP_SERVICE_UUID)
    private val WANT_UUID = UUID.fromString(APP_WANT_SERVICE_UUID)
    private val META_UUID = UUID.fromString(META_CHAR_UUID)
    private val INFO_UUID = UUID.fromString(INFO_CHAR_UUID)
    private val STREAM_UUID = UUID.fromString(STREAM_CHAR_UUID)
    private val INCOMING_UUID = UUID.fromString(INCOMING_CHAR_UUID)
    private val PPCP_UUID = UUID.fromString("00002a04-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter
    private val deviceUuidBytes: ByteArray = loadOrGenerateDeviceUuid()
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    // Extended advertising: one advertising set per file, each carrying the full
    // META payload in service data. No rotation needed — all files advertise
    // simultaneously. Up to 255B per set (LE 1M PHY, non-legacy).
    private val advertisingSets = java.util.concurrent.ConcurrentHashMap<String, AdvertisingSet>()
    private val advertisingCallback = java.util.concurrent.ConcurrentHashMap<String, AdvertisingSetCallback>()
    private var maxAdvertisingSets = 0
    // A WANT (receive) ad could not be kept active (e.g. device hit its advertising-set
    // limit). The owner should drop it from its "advertised" bookkeeping so it can retry later.
    var onWantDropped: ((fileId: String) -> Unit)? = null
    // A hardware advertising slot just freed; the owner should re-attempt any deferred WANT ads.
    var onAdvertisingSlotFreed: (() -> Unit)? = null
    // Each central keeps its own selected file; multiple peers may be connected.
    private val selectedFileByCentral = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Maps 6-byte fileIdHash hex -> fileId so the central can SELECT which file to serve.
    private val fileHashToFileId = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var metaPayloadProvider: (suspend (String) -> BleMetaPayload?)? = null
    // GATT file streaming: a central subscribes to STREAM notifications, writes
    // "PULL v<version>", and the peripheral then pushes chunks as fast as the ATT
    // layer accepts them (no round-trip per chunk). Completion = total bytes.
    @Volatile private var serveFileLoaderField: ((fileId: String, version: Int) -> ByteArray?)? = null
    private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private data class Stream(val data: ByteArray, var pos: Int)
    private val streams = java.util.concurrent.ConcurrentHashMap<String, Stream>()
    // Flow-control credits per central. The central grants credits as it actually
    // receives chunks over the link, so the peripheral can never send faster than
    // the air can carry it (preventing controller-buffer overflow -> status=147/133).
    private val streamCredits = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val subscribedCentrals = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Incoming "push" transfers: a peer connects and writes a file to the INCOMING
    // characteristic (because we advertised "I WANT" but are not scanning, e.g. screen
    // off). The server assembles the encrypted envelope to a temp file and hands it to
    // SyncEngine for finalization. Header is 28B: fileId 16B + version 4B + keyId 4B + size 4B.
    private data class IncomingTransfer(val fileIdBytes: ByteArray, val version: Int, val keyId: ByteArray, val size: Long, val fileHash: ByteArray, val nameLen: Int, val rawFile: java.io.File, val out: java.io.OutputStream, var received: Long)
    private val incomingTransfers = java.util.concurrent.ConcurrentHashMap<String, IncomingTransfer>()

    private val _activeStreamingFileIds = MutableStateFlow<Set<String>>(emptySet())
    val activeStreamingFileIds: StateFlow<Set<String>> = _activeStreamingFileIds.asStateFlow()
    // Authoritative count of centrals currently connected to this GATT server.
    // Maintained here (synchronously on connection-state changes) because
    // BluetoothManager.getConnectedDevices() can lag during the handshake phase,
    // which let the periodic GATT restart slip through and tear down a connecting
    // central (causing GATT_INVALID_HANDLE / status=1 on its next write).
    private val connectedCentrals = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    // Timestamp of the last GATT connect/disconnect. Used to defer server restarts
    // past Samsung's connection-callback lag, so a restart never lands while a
    // central is mid-handshake. Kept at 0: the server MUST still restart regularly
    // when idle, because that is the only reliable way to invalidate the central's
    // stale cached service table (refresh() is ineffective on Samsung) — without it,
    // centrals discover a service missing the STREAM characteristic. The
    // connectedCentrals guard (not this) is what prevents restarts during active
    // client connections.
    private var lastConnectionActivityMs = 0L
    private val RESTART_IDLE_COOLDOWN_MS = 2_000L
    private val streamToFileId = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val _streamingProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val streamingProgress: StateFlow<Map<String, Float>> = _streamingProgress.asStateFlow()
    private val _currentAdvertisingFileId = MutableStateFlow<String?>(null)
    val currentAdvertisingFileId: StateFlow<String?> = _currentAdvertisingFileId.asStateFlow()

    private fun loadOrGenerateDeviceUuid(): ByteArray {
        val prefs = context.getSharedPreferences("pyramid_relay", Context.MODE_PRIVATE)
        val existing = prefs.getString(DEVICE_UUID_PREF, null)
        if (existing != null) {
            return try {
                val uuid = UUID.fromString(existing)
                val msb = uuid.mostSignificantBits; val lsb = uuid.leastSignificantBits
                ByteArray(16).also { bytes -> for (i in 0..7) { bytes[i] = ((msb ushr (8 * (7 - i))) and 0xFF).toByte(); bytes[8 + i] = ((lsb ushr (8 * (7 - i))) and 0xFF).toByte() } }
            } catch (_: Exception) { generateAndStore(prefs) }
        }
        return generateAndStore(prefs)
    }

    private fun generateAndStore(prefs: android.content.SharedPreferences): ByteArray {
        val uuid = UUID.randomUUID()
        prefs.edit().putString(DEVICE_UUID_PREF, uuid.toString()).apply()
        val msb = uuid.mostSignificantBits; val lsb = uuid.leastSignificantBits
        return ByteArray(16).also { bytes -> for (i in 0..7) { bytes[i] = ((msb ushr (8 * (7 - i))) and 0xFF).toByte(); bytes[8 + i] = ((lsb ushr (8 * (7 - i))) and 0xFF).toByte() } }
    }

    fun getDeviceUuidBytes(): ByteArray = deviceUuidBytes

    fun setServeFileLoader(loader: (fileId: String, version: Int) -> ByteArray?) { serveFileLoaderField = loader }

    fun setMetaPayloadProvider(provider: suspend (String) -> BleMetaPayload?) {
        metaPayloadProvider = provider
    }

    var onTransferStart: (() -> Unit)? = null
    var onStreamArmed: (() -> Unit)? = null
    var onTransferEnd: (() -> Unit)? = null
    var isPeerTransferAllowed: ((String) -> Boolean)? = null
    var onUploadStart: ((String) -> Unit)? = null
    var onUploadEnd: ((String) -> Unit)? = null
    var onUploadSuccess: ((String) -> Unit)? = null
    var onCongestionDetected: ((deviceAddress: String) -> Unit)? = null
    // Set by SyncEngine: true while this device has an active download (GATT client) stream.
    // Restarting the local GATT server can disrupt the active client connection on some stacks.
    var isDownloadActive: (() -> Boolean)? = null
    var isTransferActive: (() -> Boolean)? = null
    // Guard for INCOMING pushes: only consulted on the first (header) write. Rejects if
    // another transfer is already active, but unlike isPeerTransferAllowed it does NOT
    // check activeUploadPeers (we add the peer there ourselves once the push starts),
    // so the continued chunk writes of an established push are never self-rejected.
    var isIncomingTransferAllowed: ((String) -> Boolean)? = null
    // Incoming "push" transfers: a peer delivers a file to us via the INCOMING characteristic.
    var onIncomingFile: ((address: String, fileIdBytes: ByteArray, version: Int, keyId: ByteArray, size: Long, fileHash: ByteArray, fileName: String, tempFile: java.io.File) -> Unit)? = null
    var onIncomingTransferStart: ((String) -> Unit)? = null
    var onIncomingTransferEnd: ((String) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startGattServer() {
        if (gattServer != null) return
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        // NOTE: the second arg is how clients see it (property), the third is what
        // the local ATT table permits (permission). PERMISSION_READ is mandatory -
        // with 0 the stack rejects every read with ATT "Read Not Permitted" (0x02)
        // BEFORE our onCharacteristicReadRequest callback is invoked.
        service.addCharacteristic(BluetoothGattCharacteristic(META_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        service.addCharacteristic(BluetoothGattCharacteristic(INFO_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ))
        val streamChar = BluetoothGattCharacteristic(STREAM_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
        streamChar.addDescriptor(BluetoothGattDescriptor(CCC_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(streamChar)
        // INCOMING: writable characteristic a peer uses to PUSH a file to us. With response
        // (WRITE_TYPE_DEFAULT) so the peer gets link-layer backpressure per chunk.
        service.addCharacteristic(BluetoothGattCharacteristic(INCOMING_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        // Peripheral Preferred Connection Parameters: advertises a short connection
        // interval so centrals negotiate a high-throughput link (spec-compliant).
        service.addCharacteristic(BluetoothGattCharacteristic(PPCP_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        val serviceAdded = java.util.concurrent.CompletableFuture<Boolean>()
        gattServer = bluetoothManager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                val addr = device?.address?.takeLast(5) ?: "?"
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    device?.address?.let { connectedCentrals[it] = true }
                    lastConnectionActivityMs = System.currentTimeMillis()
                    EventLog.log("ble", "Broadcaster: central $addr CONNECTED (status=$status)")
                }
                else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    lastConnectionActivityMs = System.currentTimeMillis()
                    EventLog.log("ble", "Broadcaster: central $addr DISCONNECTED (status=$status)")
                    device?.address?.let { addr ->
                        val fId = streamToFileId.remove(addr)
                        // Don't clear selectedFileByCentral here — the central may reconnect
                // for fetchFile and needs the selection to persist across connections.
                        if (fId != null) {
                            _activeStreamingFileIds.value = _activeStreamingFileIds.value - fId
                            _streamingProgress.value = _streamingProgress.value - fId
                            EventLog.log("ble", "Stream ended for ${fId.takeLast(8)} (disconnect) [active streams: ${_activeStreamingFileIds.value.size}]")
                        }
                        streams.remove(addr); subscribedCentrals.remove(addr); streamCredits.remove(addr)
                        connectedCentrals.remove(addr)
                        // Clean up any half-received incoming "push" transfer.
                        incomingTransfers.remove(addr)?.let { tr ->
                            try { tr.out.close() } catch (_: Exception) {}
                            try { tr.rawFile.delete() } catch (_: Exception) {}
                            onIncomingTransferEnd?.invoke(addr)
                        }
                    }
                }
            }
            override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
                val char = characteristic ?: return
                val dev = device?.address?.takeLast(5) ?: "?"
                if (char.uuid == META_UUID) {
                    val fileId = device?.address?.let { selectedFileByCentral[it] }
                    val payload = if (fileId != null) runBlocking { metaPayloadProvider?.invoke(fileId) } else null
                    EventLog.log("ble", "Read META request from $dev (fileId=${fileId?.takeLast(8)}, offset=$offset, payload=${if (payload != null) "${payload.toBytes().size}B" else "NULL"})")
                    if (payload != null) {
                        val bytes = payload.toBytes()
                        val sliced = if (offset < bytes.size) bytes.copyOfRange(offset, bytes.size) else byteArrayOf()
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, sliced)
                    } else {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    }
                } else if (char.uuid == STREAM_UUID) {
                    // Chunks are pushed via notifications; a manual READ just echoes progress.
                    val key = device?.address ?: return
                    val stream = streams[key]
                    val p = stream?.pos ?: 0
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, if (stream != null && p < stream.data.size) byteArrayOf(1) else byteArrayOf(0))
                } else if (char.uuid == INFO_UUID) {
                    val info = "Pyramid Relay v1.0".toByteArray()
                    val sliced = if (offset < info.size) info.copyOfRange(offset, info.size) else byteArrayOf()
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, sliced)
                } else if (char.uuid == PPCP_UUID) {
                    val v = buildPpcpValue()
                    val sliced = if (offset < v.size) v.copyOfRange(offset, v.size) else byteArrayOf()
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, sliced)
                }
            }
            override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
                val char = characteristic ?: return
                val device = device ?: return
                if (char.uuid == INCOMING_UUID) {
                    handleIncomingWrite(device, requestId, value, responseNeeded)
                    return
                }
                if (char.uuid == STREAM_UUID) {
                    val cmd = value?.toString(Charsets.UTF_8) ?: ""
                    if (cmd.startsWith("CRED ")) {
                        val n = cmd.substringAfter("CRED ").trim().toIntOrNull() ?: 0
                        if (n > 0) {
                            val total = (streamCredits[device.address] ?: 0) + n
                            streamCredits[device.address] = total
                            EventLog.log("ble", "CRED +$n -> ${device.address.takeLast(5)} (total=$total)")
                        }
                        if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        return
                    }
                    if (cmd.startsWith("SELECT ")) {
                        val hexHash = cmd.substringAfter("SELECT ").trim()
                        val matchFileId = fileHashToFileId[hexHash]
                        if (matchFileId != null) {
                            selectedFileByCentral[device.address] = matchFileId
                            EventLog.log("ble", "SELECT $hexHash -> ${matchFileId.takeLast(8)}")
                            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        } else {
                            EventLog.log("ble", "SELECT $hexHash - no match")
                            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        }
                        return
                    }
                    val pullMatch = Regex("PULL v(\\d+)(?: (\\d+))?").find(cmd)
                    val version = pullMatch?.groupValues?.get(1)?.toIntOrNull()
                    val resumeFrom = pullMatch?.groupValues?.get(2)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    val fileId = selectedFileByCentral[device.address]
                    if (version == null || fileId == null) {
                        EventLog.log("ble", "Bad stream request \"${cmd.take(40)}\" from ${device.address.takeLast(5)}")
                        if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        return
                    }
                    if (_activeStreamingFileIds.value.contains(fileId)) {
                        EventLog.log("ble", "File ${fileId.takeLast(8)} already being streamed - rejecting PULL from ${device.address.takeLast(5)}")
                        if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        return
                    }
                    if (isPeerTransferAllowed?.invoke(device.address) == false) {
                        EventLog.log("ble", "Download in progress - rejecting PULL from ${device.address.takeLast(5)}")
                        if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        return
                    }
                    val data = if (fileId != null) serveFileLoaderField?.invoke(fileId, version) else null
                    if (data == null) {
                        EventLog.log("ble", "No file to stream (v$version) to ${device.address.takeLast(5)}")
                        if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        return
                    }
                    if (resumeFrom >= data.size) {
                        EventLog.log("ble", "PULL offset $resumeFrom beyond file size ${data.size} - rejecting for ${device.address.takeLast(5)}")
                        if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        return
                    }
                    streams[device.address] = Stream(data, resumeFrom.toInt())
                    streamToFileId[device.address] = fileId
                    // Seed a credit window for the REMAINING bytes so we can always
                    // make progress without depending on the central's CRED writes
                    // reaching us (some stacks never deliver NO_RESPONSE credits;
                    // notify()==false still provides link-layer backpressure).
                    val remainingBytes = data.size - resumeFrom.toInt()
                    val seedChunks = (remainingBytes + 511) / 512 + 16
                    streamCredits[device.address] = maxOf(streamCredits[device.address] ?: 0, seedChunks)
                    EventLog.log("ble", "Seeded $seedChunks credits for ${device.address.takeLast(5)} (resume from $resumeFrom/${data.size})")
                    _activeStreamingFileIds.value = _activeStreamingFileIds.value + fileId
                    EventLog.log("ble", "Stream armed: v$version (${data.size}B from $resumeFrom) -> ${device.address.takeLast(5)} [active streams: ${_activeStreamingFileIds.value.size}]")
                    onStreamArmed?.invoke()
                    if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    pushStreamTo(device.address)
                } else if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
            }
            override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
                val device = device ?: return
                EventLog.log("ble", "CCC write from ${device.address.takeLast(5)}: value=${value?.joinToString(",") { "%02x".format(it) }}")
                if (descriptor?.uuid == CCC_DESCRIPTOR_UUID && descriptor.characteristic?.uuid == STREAM_UUID) {
                    if (value != null && value.size == 2 && value[0] != 0.toByte()) {
                        subscribedCentrals.add(device.address)
                        EventLog.log("ble", "Central ${device.address.takeLast(5)} subscribed to notifications")
                    } else {
                        subscribedCentrals.remove(device.address)
                        EventLog.log("ble", "Central ${device.address.takeLast(5)} unsubscribed from notifications")
                    }
                }
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            override fun onServiceAdded(status: Int, svc: android.bluetooth.BluetoothGattService?) {
                if (svc?.uuid == SERVICE_UUID) {
                    EventLog.log("ble", "GATT service added (status=$status)")
                    serviceAdded.complete(status == android.bluetooth.BluetoothGatt.GATT_SUCCESS)
                }
            }
        })
        gattServer?.addService(service)
        try {
            if (!serviceAdded.get(3000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                EventLog.log("ble", "GATT service add reported failure")
            }
        } catch (e: Exception) {
            EventLog.log("ble", "GATT service add timed out: ${e.message}")
        }
        Log.d(TAG, "GATT server started")
    }

    @SuppressLint("MissingPermission")
    fun restartGattServer() {
        if (_activeStreamingFileIds.value.isNotEmpty()) {
            EventLog.log("ble", "restartGattServer skipped (active upload stream in progress)")
            return
        }
        if (isDownloadActive?.invoke() == true) {
            EventLog.log("ble", "restartGattServer skipped (active download stream in progress)")
            return
        }
        if (isTransferActive?.invoke() == true) {
            EventLog.log("ble", "restartGattServer skipped (transfer in progress)")
            return
        }
        // Defer restarts past any recent connect/disconnect. Samsung's
        // onConnectionStateChange can lag and centrals reconnect rapidly, so a
        // restart here would tear down a central that is mid-handshake or about to
        // reconnect — invalidating its STREAM handle (GATT_INVALID_HANDLE / status=1).
        val sinceActivity = System.currentTimeMillis() - lastConnectionActivityMs
        if (sinceActivity < RESTART_IDLE_COOLDOWN_MS) {
            EventLog.log("ble", "restartGattServer skipped (GATT connection activity ${sinceActivity}ms ago, cooldown ${RESTART_IDLE_COOLDOWN_MS}ms)")
            return
        }
        // A merely-connected client (including stale/stuck ones from a prior, never
        // cleaned-up transfer) must NOT block the restart. If it did, the server would
        // never restart while any central lingers connected, and centrals would stay
        // pinned to a stale cached service that is MISSING the STREAM characteristic
        // (Samsung does not honour refresh()). The restart changes the ATT handles,
        // which is exactly what forces centrals to rediscover the full service. The
        // connection-activity cooldown above already protects centrals mid-handshake;
        // an active transfer is blocked by the guards at the top of this function. Idle
        // clients are simply disconnected by the restart below (which also clears any
        // stuck client that was itself preventing restarts).
        val bluetoothClients = bluetoothManager.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT_SERVER)
        val connectedDevices = bluetoothClients
        EventLog.log("ble", "restartGattServer called (${connectedDevices.size} connected device(s))")
        Log.d(TAG, "Restarting GATT server with ${connectedDevices.size} connected devices")
        for (device in connectedDevices) {
            try {
                gattServer?.cancelConnection(device)
                EventLog.log("ble", "Disconnected ${device.address?.takeLast(5)} during GATT server restart")
            } catch (e: Exception) {
                EventLog.log("ble", "Error disconnecting ${device.address?.takeLast(5)}: ${e.message}")
            }
        }
        gattServer?.close()
        gattServer = null
        Thread.sleep(100) // Samsung devices need a delay between close and open
        startGattServer()
        // Wait for GATT server to be fully ready before allowing connections
        Thread.sleep(500)
        EventLog.log("ble", "restartGattServer: GATT server ready")
    }

    private fun connectedDevice(address: String): android.bluetooth.BluetoothDevice? =
        bluetoothManager.adapter?.getRemoteDevice(address)

    /**
     * Handles a peer writing a file to our INCOMING characteristic. The first write
     * carries a 28-byte header (fileId 16B + version 4B + keyId 4B + size 4B); if it
     * also carries trailing bytes they are treated as the first chunk. Subsequent
     * writes append until [IncomingTransfer.received] >= size, at which point the
     * reassembled file is handed to SyncEngine via [onIncomingFile].
     */
    private fun handleIncomingWrite(device: android.bluetooth.BluetoothDevice, requestId: Int, value: ByteArray?, responseNeeded: Boolean) {
        val addr = device.address
        if (value == null) { if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null); return }
        val existing = incomingTransfers[addr]
        if (existing == null) {
            if (value.size < 62) {
                EventLog.log("ble", "Incoming: header too short (${value.size}B) from ${addr.takeLast(5)}")
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            if (isIncomingTransferAllowed?.invoke(addr) == false) {
                EventLog.log("ble", "Incoming: transfer not allowed from ${addr.takeLast(5)} (busy)")
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            val fileIdBytes = value.copyOfRange(0, 16)
            val version = ((value[16].toInt() and 0xFF) shl 24) or ((value[17].toInt() and 0xFF) shl 16) or ((value[18].toInt() and 0xFF) shl 8) or (value[19].toInt() and 0xFF)
            val keyId = value.copyOfRange(20, 24)
            val size = (((value[24].toLong() and 0xFF) shl 24) or ((value[25].toLong() and 0xFF) shl 16) or ((value[26].toLong() and 0xFF) shl 8) or (value[27].toLong() and 0xFF))
            val fileHash = value.copyOfRange(28, 60)
            val nameLen = ((value[60].toInt() and 0xFF) shl 8) or (value[61].toInt() and 0xFF)
            val hex = fileIdBytes.joinToString("") { "%02x".format(it) }
            // Buffer everything (name + envelope) to a raw temp file, then split on completion.
            val rawFile = java.io.File(context.cacheDir, "incoming_raw_${hex.take(12)}_$version.bin")
            rawFile.parentFile?.mkdirs()
            val out = rawFile.outputStream()
            val tr = IncomingTransfer(fileIdBytes, version, keyId, size, fileHash, nameLen, rawFile, out, 0)
            val extra = if (value.size > 62) value.copyOfRange(62, value.size) else byteArrayOf()
            if (extra.isNotEmpty()) { out.write(extra); tr.received += extra.size }
            incomingTransfers[addr] = tr
            onIncomingTransferStart?.invoke(addr)
            EventLog.log("ble", "Incoming: header accepted from ${addr.takeLast(5)} file=${hex.take(12)} v$version size=$size nameLen=$nameLen")
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            return
        }
        try { existing.out.write(value) } catch (e: Exception) { EventLog.log("ble", "Incoming: write failed: ${e.message}"); if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null); return }
        existing.received += value.size
        val total = existing.nameLen.toLong() + existing.size
        if (existing.received >= total) {
            try { existing.out.close() } catch (_: Exception) {}
            incomingTransfers.remove(addr)
            val hex = existing.fileIdBytes.joinToString("") { "%02x".format(it) }
            try {
                val raw = existing.rawFile.readBytes()
                val name = if (existing.nameLen > 0) String(raw.copyOfRange(0, existing.nameLen), Charsets.UTF_8) else ""
                val envelope = raw.copyOfRange(existing.nameLen, (existing.nameLen + existing.size.toInt()).coerceAtMost(raw.size))
                val envelopeFile = java.io.File(context.cacheDir, "incoming_${hex.take(12)}_${existing.version}.bin")
                envelopeFile.parentFile?.mkdirs()
                envelopeFile.writeBytes(envelope)
                existing.rawFile.delete()
                EventLog.log("ble", "Incoming: complete from ${addr.takeLast(5)} (file=${hex.take(12)} v${existing.version}, ${existing.received}/$total B, name=\"$name\") -> handing off")
                onIncomingFile?.invoke(addr, existing.fileIdBytes, existing.version, existing.keyId, existing.size, existing.fileHash, name, envelopeFile)
            } catch (e: Exception) {
                EventLog.log("ble", "Incoming: failed to assemble file: ${e.message}")
            }
            onIncomingTransferEnd?.invoke(addr)
        }
        if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
    }

    private fun streamCharacteristic(): BluetoothGattCharacteristic? =
        gattServer?.getService(SERVICE_UUID)?.getCharacteristic(STREAM_UUID)

    // Peripheral Preferred Connection Parameters value (Core Spec Vol 3 Part C 12.3):
    // min interval, max interval, slave latency, supervision timeout (little-endian,
    // 1.25ms units). 6/12 -> 7.5-15ms interval, latency 0, 2s timeout.
    private fun buildPpcpValue(): ByteArray {
        val min = 6; val max = 12; val latency = 0; val timeout = 200
        return byteArrayOf(
            (min and 0xFF).toByte(), (min ushr 8).toByte(),
            (max and 0xFF).toByte(), (max ushr 8).toByte(),
            (latency and 0xFF).toByte(), (latency ushr 8).toByte(),
            (timeout and 0xFF).toByte(), (timeout ushr 8).toByte()
        )
    }

    /**
     * Pushes the pending stream for [address] via notifications.
     * Each notification triggers onNotificationSent which sends the next chunk.
     * No subscribedCentrals check — Samsung handles CCCD writes locally in the
     * BLE stack without forwarding to onDescriptorWriteRequest.
     */
    private val pushing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun pushStreamTo(address: String) {
        if (!pushing.add(address)) return
        Thread {
            try {
                // Wait until peer is not transferring to us
                while (isPeerTransferAllowed?.invoke(address) == false) {
                    Thread.sleep(100)
                }
                // Transfer actually starting - notify callback to stop advertising/scanning
                onTransferStart?.invoke()
                onUploadStart?.invoke(address)
                val server = gattServer ?: return@Thread
                val char = streamCharacteristic() ?: return@Thread
                var logged = 0
                var streamComplete = false
                while (true) {
                    val stream = streams[address] ?: break
                    val device = connectedDevice(address) ?: break
                    val pos = stream.pos
                    if (pos >= stream.data.size) { streamComplete = true; break }
                    // Credit-based flow control: only send when the central has
                    // granted a credit. This bounds in-flight data to the link's
                    // real capacity and prevents controller-buffer overflow
                    // (the root cause of status=147/133 disconnects).
                    val avail = streamCredits[address] ?: 0
                    if (avail <= 0) { Thread.sleep(5); continue }
                    val end = minOf(pos + 512, stream.data.size)
                    val chunk = stream.data.copyOfRange(pos, end)
                    // Update progress - cap at 99% until stream is confirmed complete
                    val fId = streamToFileId[address]
                    if (fId != null) {
                        val progress = (end.toFloat() / stream.data.size.toFloat()).coerceIn(0f, 0.99f)
                        _streamingProgress.value = _streamingProgress.value + (fId to progress)
                    }
                    if (end / 40_720 != logged / 40_720 || end >= stream.data.size) { logged = end; EventLog.log("ble", "Streaming ${end}/${stream.data.size}B to ${address.takeLast(5)} (credits=$avail)") }
                    if (!char.setValue(chunk)) {
                        EventLog.log("ble", "setValue FAILED for ${address.takeLast(5)}")
                        break
                    }
                    // Consume a credit and advance the cursor only after the chunk
                    // has been queued for transmission.
                    streamCredits[address] = avail - 1
                    stream.pos = end
                    if (!server.notifyCharacteristicChanged(device, char, false)) {
                        // Queue full / link not ready: refund the credit and cursor,
                        // then retry shortly instead of aborting the whole upload.
                        streamCredits[address] = (streamCredits[address] ?: 0) + 1
                        stream.pos = pos
                        EventLog.log("ble", "notifyCharacteristicChanged FAILED, retrying for ${address.takeLast(5)}")
                        Thread.sleep(20)
                        continue
                    }
                    Thread.sleep(3) // tiny pacing; credits are the real throttle
                }
                if (streamComplete) onUploadSuccess?.invoke(address)
            } finally {
                // Clear active-stream state BEFORE firing onTransferEnd so that any
                // restart triggered by it (or by resumeAdvertisingAndScanning) does not
                // run while this stream is still accounted as in-progress. Restarting the
                // GATT server mid-transfer disconnects the connected central and invalidates
                // its characteristic handles, causing GATT_INVALID_HANDLE (status=1) failures.
                pushing.remove(address)
                val fId = streamToFileId.remove(address)
                if (fId != null) {
                    // Set to 100% before clearing so UI shows completion
                    _streamingProgress.value = _streamingProgress.value + (fId to 1f)
                    _activeStreamingFileIds.value = _activeStreamingFileIds.value - fId
                    _streamingProgress.value = _streamingProgress.value - fId
                    EventLog.log("ble", "Stream ended for ${fId.takeLast(8)} (complete) [active streams: ${_activeStreamingFileIds.value.size}]")
                }
                // Transfer ended - notify callback to resume advertising/scanning
                EventLog.log("ble", "pushStreamTo finally: invoking onTransferEnd")
                onTransferEnd?.invoke()
                EventLog.log("ble", "pushStreamTo finally: onTransferEnd done, invoking onUploadEnd")
                onUploadEnd?.invoke(address)
            }
        }.start()
    }

    fun stopStreaming(fileId: String) {
        val addresses = streamToFileId.entries.filter { it.value == fileId }.map { it.key }
        for (address in addresses) {
            streams.remove(address)
            streamToFileId.remove(address)
            pushing.remove(address)
            _activeStreamingFileIds.value = _activeStreamingFileIds.value - fileId
            _streamingProgress.value = _streamingProgress.value - fileId
            // Disconnect the central to notify it that the stream was stopped
            gattServer?.cancelConnection(connectedDevice(address))
            EventLog.log("ble", "Stopped streaming ${fileId.takeLast(8)} to ${address.takeLast(5)}")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectPeer(address: String) {
        connectedDevice(address)?.let { device ->
            gattServer?.cancelConnection(device)
            EventLog.log("ble", "Disconnected peer ${address.takeLast(5)} due to congestion")
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(fileId: String, serviceData: ByteArray): Boolean {
        return startAdvertisingSetInternal(fileId, SERVICE_UUID, serviceData, true)
    }

    /** Advertises "I WANT <file>" under the WANT UUID so screen-off senders can discover and push. */
    @SuppressLint("MissingPermission")
    fun startWantAdvertising(fileId: String, serviceData: ByteArray): Boolean {
        return startAdvertisingSetInternal("want:$fileId", WANT_UUID, serviceData, false)
    }

    private fun getMaxAdvertisingSets(): Int {
        if (maxAdvertisingSets <= 0) {
            // The SDK does not reliably expose the per-device limit, so we start with a
            // conservative cap and shrink it at runtime if the controller rejects a start
            // with ADVERTISE_FAILED_TOO_MANY_ADVERTISERS.
            maxAdvertisingSets = 5
            EventLog.log("ble", "Using advertising-set cap: $maxAdvertisingSets (self-adjusting)")
        }
        return maxAdvertisingSets
    }

    private fun startAdvertisingSetInternal(tag: String, serviceUuid: UUID, serviceData: ByteArray, registerHash: Boolean): Boolean {
        if (advertisingSets.containsKey(tag)) return true
        val adv = adapter?.bluetoothLeAdvertiser ?: run {
            EventLog.log("ble", "BLE advertiser not available")
            return false
        }
        advertiser = adv
        val maxSets = getMaxAdvertisingSets()
        val isWant = tag.startsWith("want:")
        if (isWant) {
            if (advertisingCallback.size >= maxSets) {
                EventLog.log("ble", "WANT ad skipped (at capacity ${advertisingCallback.size}/$maxSets): $tag")
                onWantDropped?.invoke(tag.removePrefix("want:"))
                return false
            }
        } else {
            // Broadcasts (we HAVE a file) outrank WANT ads (we WANT a file). If we are at the
            // hardware advertising-set limit, evict the oldest WANT ad(s) to free a slot.
            while (advertisingCallback.size >= maxSets) {
                val wantTag = advertisingCallback.keys.firstOrNull { it.startsWith("want:") } ?: break
                val fid = wantTag.removePrefix("want:")
                EventLog.log("ble", "Capacity $maxSets reached; evicting WANT $wantTag to free slot for broadcast")
                val cb = advertisingCallback.remove(wantTag)
                advertisingSets.remove(wantTag)
                try { adv.stopAdvertisingSet(cb) } catch (_: Exception) {}
                onWantDropped?.invoke(fid)
            }
        }
        if (registerHash) {
            val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(tag.toByteArray(Charsets.UTF_8))
            val hashHex = sha256.copyOfRange(0, 6).joinToString("") { "%02x".format(it) }
            fileHashToFileId[hashHex] = tag
        }
        val params = AdvertisingSetParameters.Builder()
            .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .setConnectable(true)
            .setScannable(false)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_1M)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(serviceUuid), serviceData)
            .build()
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS && advertisingSet != null) {
                    advertisingSets[tag] = advertisingSet
                    EventLog.log("ble", "Extended advertising started: $tag (txPower=$txPower)")
                } else {
                    EventLog.log("ble", "Extended advertising failed: $tag (status=$status)")
                    advertisingCallback.remove(tag)
                    // If the controller is out of advertising sets, shrink our cap so we stop
                    // trying to exceed it (and free room for higher-priority broadcasts).
                    if (status == AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS) {
                        val newCap = if (advertisingCallback.size < 1) 1 else advertisingCallback.size
                        if (newCap < maxAdvertisingSets) {
                            maxAdvertisingSets = newCap
                            EventLog.log("ble", "Advertising-set cap reduced to $maxAdvertisingSets (controller rejected start)")
                        }
                    }
                    if (tag.startsWith("want:")) onWantDropped?.invoke(tag.removePrefix("want:"))
                }
            }
            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                advertisingSets.remove(tag)
                EventLog.log("ble", "Advertising stopped: $tag")
                onAdvertisingSlotFreed?.invoke()
            }
        }
        advertisingCallback[tag] = callback
        try {
            adv.startAdvertisingSet(params, data, null, null, null, callback)
            EventLog.log("ble", "Starting extended advertising: $tag (${serviceData.size}B service data)")
        } catch (e: Exception) {
            EventLog.log("ble", "Failed to start extended advertising: ${e.message}")
            advertisingCallback.remove(tag)
            if (tag.startsWith("want:")) onWantDropped?.invoke(tag.removePrefix("want:"))
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising(fileId: String) {
        val cb = advertisingCallback.remove(fileId)
        advertisingSets.remove(fileId)
        if (cb == null) return
        fileHashToFileId.entries.removeAll { it.value == fileId }
        try {
            advertiser?.stopAdvertisingSet(cb)
        } catch (e: Exception) {
            EventLog.log("ble", "Error stopping advertising for $fileId: ${e.message}")
        }
        EventLog.log("ble", "Stopped advertising: $fileId (remaining=${advertisingSets.size})")
    }

    @SuppressLint("MissingPermission")
    fun stopWantAdvertising(fileId: String) {
        val tag = "want:$fileId"
        val cb = advertisingCallback.remove(tag)
        advertisingSets.remove(tag)
        if (cb == null) return
        try {
            advertiser?.stopAdvertisingSet(cb)
        } catch (e: Exception) {
            EventLog.log("ble", "Error stopping WANT advertising for $fileId: ${e.message}")
        }
        EventLog.log("ble", "Stopped WANT advertising: $fileId (remaining=${advertisingSets.size})")
    }

    @SuppressLint("MissingPermission")
    fun stopAllAdvertising() {
        for ((fileId, cb) in advertisingCallback.entries) {
            try { advertiser?.stopAdvertisingSet(cb) } catch (_: Exception) {}
        }
        advertisingSets.clear()
        advertisingCallback.clear()
        fileHashToFileId.clear()
        selectedFileByCentral.clear()
        EventLog.log("ble", "All advertising stopped")
    }

    @SuppressLint("MissingPermission")
    fun stopGattServer() {
        val connectedDevices = bluetoothManager.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT_SERVER)
        for (device in connectedDevices) {
            try { gattServer?.cancelConnection(device) } catch (_: Exception) {}
        }
        if (connectedDevices.isNotEmpty()) {
            try { Thread.sleep(500) } catch (_: InterruptedException) {}
        }
        try { gattServer?.close() } catch (e: Exception) { EventLog.log("ble", "Error closing GATT server: ${e.message}") }
        gattServer = null
    }

    fun destroy() { stopAllAdvertising(); stopGattServer() }
}
