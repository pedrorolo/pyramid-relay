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

class BlePeripheralService(private val context: Context, private val transferSemaphore: kotlinx.coroutines.sync.Semaphore = kotlinx.coroutines.sync.Semaphore(1)) {
    companion object {
        private const val TAG = "BlePeripheral"
    private val SERVICE_UUID = UUID.fromString(APP_SERVICE_UUID)
    private val META_UUID = UUID.fromString(META_CHAR_UUID)
    private val INFO_UUID = UUID.fromString(INFO_CHAR_UUID)
    private val STREAM_UUID = UUID.fromString(STREAM_CHAR_UUID)
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    // Single BLE advertising set that rotates through files every ~3s.
    // Samsung stacks silently break when many concurrent advertising sets are used;
    // one rotating set avoids the issue entirely.
    private val advertisingFiles = CopyOnWriteArrayList<Pair<String, ByteArray>>() // (fileId, serviceData)
    @Volatile private var currentAdIndex = 0
    @Volatile private var rotationRunning = false
    private var rotationThread: Thread? = null
    private var currentAdCallback: AdvertiseCallback? = null
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
    private val subscribedCentrals = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val _activeStreamingFileIds = MutableStateFlow<Set<String>>(emptySet())
    val activeStreamingFileIds: StateFlow<Set<String>> = _activeStreamingFileIds.asStateFlow()
    private val streamToFileId = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val _streamingProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val streamingProgress: StateFlow<Map<String, Float>> = _streamingProgress.asStateFlow()
    private val _currentAdvertisingFileId = MutableStateFlow<String?>(null)
    val currentAdvertisingFileId: StateFlow<String?> = _currentAdvertisingFileId.asStateFlow()

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
    var onCongestionDetected: (() -> Unit)? = null

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
        val streamChar = BluetoothGattCharacteristic(STREAM_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
        streamChar.addDescriptor(BluetoothGattDescriptor(CCC_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(streamChar)
        gattServer = bluetoothManager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                val addr = device?.address?.takeLast(5) ?: "?"
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED)
                    EventLog.log("ble", "Broadcaster: central $addr CONNECTED (status=$status)")
                else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    EventLog.log("ble", "Broadcaster: central $addr DISCONNECTED (status=$status)")
                    if (status == 147) onCongestionDetected?.invoke()
                    device?.address?.let { addr ->
                        val fId = streamToFileId.remove(addr)
                        // Don't clear selectedFileByCentral here — the central may reconnect
                // for fetchFile and needs the selection to persist across connections.
                        if (fId != null) {
                            _activeStreamingFileIds.value = _activeStreamingFileIds.value - fId
                            _streamingProgress.value = _streamingProgress.value - fId
                            EventLog.log("ble", "Stream ended for ${fId.takeLast(8)} (disconnect) [active streams: ${_activeStreamingFileIds.value.size}]")
                        }
                        streams.remove(addr); subscribedCentrals.remove(addr)
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
                }
            }
            override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
                val char = characteristic ?: return
                val device = device ?: return
                if (char.uuid == STREAM_UUID) {
                    val cmd = value?.toString(Charsets.UTF_8) ?: ""
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
                    val version = Regex("PULL v(\\d+)").find(cmd)?.groupValues?.get(1)?.toIntOrNull()
                    val fileId = selectedFileByCentral[device.address]
                    if (version == null || fileId == null) {
                        EventLog.log("ble", "Bad stream request \"${cmd.take(40)}\" from ${device.address.takeLast(5)}")
                        if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        return
                    }
                    val data = if (fileId != null) serveFileLoaderField?.invoke(fileId, version) else null
                    if (data == null) {
                        EventLog.log("ble", "No file to stream (v$version) to ${device.address.takeLast(5)}")
                        if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        return
                    }
                    streams[device.address] = Stream(data, 0)
                    streamToFileId[device.address] = fileId
                    _activeStreamingFileIds.value = _activeStreamingFileIds.value + fileId
                    EventLog.log("ble", "Stream armed: v$version (${data.size}B) -> ${device.address.takeLast(5)} [active streams: ${_activeStreamingFileIds.value.size}]")
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
        })
        gattServer?.addService(service)
        Log.d(TAG, "GATT server started")
    }

    @SuppressLint("MissingPermission")
    fun restartGattServer() {
        val connectedDevices = bluetoothManager.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT_SERVER)
        EventLog.log("ble", "restartGattServer called (${connectedDevices.size} connected devices: ${connectedDevices.map { it.address?.takeLast(5) }})")
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
    }

    private fun connectedDevice(address: String): android.bluetooth.BluetoothDevice? =
        bluetoothManager.adapter?.getRemoteDevice(address)

    private fun streamCharacteristic(): BluetoothGattCharacteristic? =
        gattServer?.getService(SERVICE_UUID)?.getCharacteristic(STREAM_UUID)

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
                runBlocking { transferSemaphore.acquire() }
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
                while (true) {
                    val stream = streams[address] ?: break
                    if (stream.pos >= stream.data.size) break
                    val device = connectedDevice(address) ?: break
                    val end = minOf(stream.pos + 512, stream.data.size)
                    val chunk = stream.data.copyOfRange(stream.pos, end)
                    stream.pos = end
                    // Update progress - cap at 99% until stream is confirmed complete
                    val fId = streamToFileId[address]
                    if (fId != null) {
                        val progress = (end.toFloat() / stream.data.size.toFloat()).coerceIn(0f, 0.99f)
                        _streamingProgress.value = _streamingProgress.value + (fId to progress)
                    }
                    if (end / 40_720 != logged / 40_720 || end >= stream.data.size) { logged = end; EventLog.log("ble", "Streaming ${end}/${stream.data.size}B to ${address.takeLast(5)}") }
                    if (!char.setValue(chunk)) {
                        EventLog.log("ble", "setValue FAILED for ${address.takeLast(5)}")
                        break
                    }
                    if (!server.notifyCharacteristicChanged(device, char, false)) {
                        EventLog.log("ble", "notifyCharacteristicChanged FAILED for ${address.takeLast(5)}")
                        break
                    }
                    Thread.sleep(10) // give Samsung BLE stack time to process
                }
            } finally {
                transferSemaphore.release()
                // Transfer ended - notify callback to resume advertising/scanning
                EventLog.log("ble", "pushStreamTo finally: invoking onTransferEnd")
                onTransferEnd?.invoke()
                EventLog.log("ble", "pushStreamTo finally: onTransferEnd done, invoking onUploadEnd")
                onUploadEnd?.invoke(address)
                pushing.remove(address)
                val fId = streamToFileId.remove(address)
                if (fId != null) {
                    // Set to 100% before clearing so UI shows completion
                    _streamingProgress.value = _streamingProgress.value + (fId to 1f)
                    _activeStreamingFileIds.value = _activeStreamingFileIds.value - fId
                    _streamingProgress.value = _streamingProgress.value - fId
                    EventLog.log("ble", "Stream ended for ${fId.takeLast(8)} (complete) [active streams: ${_activeStreamingFileIds.value.size}]")
                }
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
    fun startAdvertising(fileId: String, serviceData: ByteArray) {
        // Already tracking this file — skip (idempotent).
        if (advertisingFiles.any { it.first == fileId }) return
        val adv = adapter?.bluetoothLeAdvertiser ?: run {
            EventLog.log("ble", "BLE advertiser not available")
            return
        }
        advertiser = adv
        advertisingFiles.add(fileId to serviceData)
        // Track fileIdHash -> fileId so SELECT works.
        val hashHex = serviceData.copyOfRange(0, 6).joinToString("") { "%02x".format(it) }
        fileHashToFileId[hashHex] = fileId
        EventLog.log("ble", "Advertising queued: $fileId (total=${advertisingFiles.size})")
        if (advertisingFiles.size == 1) {
            startAdWithCurrentFile()
        }
        startRotationIfNeeded()
    }

    /** Start or restart the single BLE advertising set with the current file. */
    @SuppressLint("MissingPermission")
    private fun startAdWithCurrentFile() {
        if (advertisingFiles.isEmpty()) return
        val adv = advertiser ?: return
        // Stop any existing ad first.
        currentAdCallback?.let { try { adv.stopAdvertising(it) } catch (_: Exception) {} }
        val (fileId, serviceData) = advertisingFiles[currentAdIndex % advertisingFiles.size]
        _currentAdvertisingFileId.value = fileId
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), serviceData)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                val v = ((serviceData[6].toInt() and 0xFF) shl 24) or ((serviceData[7].toInt() and 0xFF) shl 16) or ((serviceData[8].toInt() and 0xFF) shl 8) or (serviceData[9].toInt() and 0xFF)
                Log.d(TAG, "Advertising started: ${fileId.takeLast(8)} v$v")
                EventLog.log("ble", "Advertising ${fileId.takeLast(8)} v$v (${advertisingFiles.size} files queued)")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed: $errorCode")
                EventLog.log("ble", "Advertising start failed: $errorCode")
            }
        }
        currentAdCallback = callback
        try { adv.startAdvertising(settings, data, callback) } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising", e)
            EventLog.log("ble", "Failed to start advertising: ${e.message}")
        }
    }



    private fun startRotationIfNeeded() {
        if (rotationRunning) return
        if (advertisingFiles.size <= 1) {
            EventLog.log("ble", "Rotation skipped (${advertisingFiles.size} file(s) — static advertising)")
            return
        }
        val intervalMs = 10_000L
        rotationRunning = true
        EventLog.log("ble", "Rotation thread starting (${advertisingFiles.size} files, ${intervalMs / 1000}s interval)")
        rotationThread = Thread({
            while (rotationRunning && advertisingFiles.size > 1) {
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
                if (advertisingFiles.size <= 1) break
                // Skip rotation while any file is being actively relayed
                if (_activeStreamingFileIds.value.isNotEmpty()) {
                    EventLog.log("ble", "Rotation skipped (${_activeStreamingFileIds.value.size} active stream(s))")
                    continue
                }
                val prev = advertisingFiles[currentAdIndex % advertisingFiles.size].first.takeLast(8)
                currentAdIndex = (currentAdIndex + 1) % advertisingFiles.size
                val next = advertisingFiles[currentAdIndex % advertisingFiles.size].first.takeLast(8)
                EventLog.log("ble", "Rotation: $prev -> $next (${advertisingFiles.size} files)")
                startAdWithCurrentFile()
            }
            rotationRunning = false
            EventLog.log("ble", "Rotation thread stopped")
        }, "adv-rotation").also { it.isDaemon = true; it.start() }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising(fileId: String) {
        val removed = advertisingFiles.removeAll { it.first == fileId }
        if (!removed) return
        fileHashToFileId.entries.removeAll { it.value == fileId }
        EventLog.log("ble", "stopAdvertising ${fileId.takeLast(8)} (remaining=${advertisingFiles.size})")
        if (advertisingFiles.isEmpty()) {
            // No more files to advertise — stop the BLE ad.
            rotationRunning = false
            rotationThread?.interrupt()
            rotationThread = null
            currentAdCallback?.let { cb ->
                try { advertiser?.stopAdvertising(cb) } catch (_: Exception) {}
                currentAdCallback = null
            }
            selectedFileByCentral.clear()
            EventLog.log("ble", "Advertising stopped (no files)")
        } else if (advertisingFiles.size == 1) {
            // Down to one file — stop rotation, switch to static advertising.
            rotationRunning = false
            rotationThread?.interrupt()
            rotationThread = null
            currentAdIndex = 0
            startAdWithCurrentFile()
            EventLog.log("ble", "Advertising down to 1 file — rotation stopped, static mode")
        } else {
            // Multiple files remain — if we were advertising the removed file, switch to next.
            if (currentAdIndex >= advertisingFiles.size) currentAdIndex = 0
            startAdWithCurrentFile()
            EventLog.log("ble", "Advertising switched to next file (${advertisingFiles.size} remaining)")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAllAdvertising() {
        rotationRunning = false
        rotationThread?.interrupt()
        rotationThread = null
        advertisingFiles.clear()
        currentAdCallback?.let { cb ->
            try { advertiser?.stopAdvertising(cb) } catch (_: Exception) {}
            currentAdCallback = null
        }
        fileHashToFileId.clear()
        selectedFileByCentral.clear()
        _currentAdvertisingFileId.value = null
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
