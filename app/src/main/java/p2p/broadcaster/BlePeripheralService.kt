package p2p.broadcaster

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
import p2p.broadcaster.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

class BlePeripheralService(private val context: Context) {
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
    // The fileId whose meta the single META characteristic serves.
    private var servicedFileId: String? = null
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

    fun setServeFileLoader(loader: (fileId: String, version: Int) -> ByteArray?) { serveFileLoaderField = loader }

    fun setMetaPayloadProvider(provider: suspend (String) -> BleMetaPayload?) {
        metaPayloadProvider = provider
    }

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
                    device?.address?.let { streams.remove(it); subscribedCentrals.remove(it) }
                }
            }
            override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
                val char = characteristic ?: return
                val dev = device?.address?.takeLast(5) ?: "?"
                if (char.uuid == META_UUID) {
                    val fileId = servicedFileId
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
                    val info = "P2P Broadcaster v1.0".toByteArray()
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
                            servicedFileId = matchFileId
                            EventLog.log("ble", "SELECT $hexHash -> ${matchFileId.takeLast(8)}")
                            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        } else {
                            EventLog.log("ble", "SELECT $hexHash - no match")
                            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        }
                        return
                    }
                    val version = Regex("PULL v(\\d+)").find(cmd)?.groupValues?.get(1)?.toIntOrNull()
                    val fileId = servicedFileId
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
                    EventLog.log("ble", "Stream armed: v$version (${data.size}B) -> ${device.address.takeLast(5)}")
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
            } finally { pushing.remove(address) }
        }.start()
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
            servicedFileId = fileId
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
        servicedFileId = fileId
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
                Log.d(TAG, "Advertising: ${fileId.takeLast(8)} v${serviceData[6].toInt() and 0xFF}")
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
        if (rotationRunning || advertisingFiles.size <= 1) return
        rotationRunning = true
        rotationThread = Thread({
            while (rotationRunning && advertisingFiles.size > 0) {
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
                if (advertisingFiles.isEmpty()) break
                currentAdIndex = (currentAdIndex + 1) % advertisingFiles.size
                startAdWithCurrentFile()
            }
            rotationRunning = false
        }, "adv-rotation").also { it.isDaemon = true; it.start() }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising(fileId: String) {
        val removed = advertisingFiles.removeAll { it.first == fileId }
        if (!removed) return
        fileHashToFileId.entries.removeAll { it.value == fileId }
        if (advertisingFiles.isEmpty()) {
            // No more files to advertise — stop the BLE ad.
            rotationRunning = false
            rotationThread?.interrupt()
            rotationThread = null
            currentAdCallback?.let { cb ->
                try { advertiser?.stopAdvertising(cb) } catch (_: Exception) {}
                currentAdCallback = null
            }
            servicedFileId = null
            EventLog.log("ble", "Advertising stopped (no files)")
        } else {
            // If we were advertising the removed file, switch to the next one.
            if (currentAdIndex >= advertisingFiles.size) currentAdIndex = 0
            startAdWithCurrentFile()
            EventLog.log("ble", "Advertising removed $fileId (remaining=${advertisingFiles.size})")
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
        servicedFileId = null
    }

    @SuppressLint("MissingPermission")
    fun stopGattServer() {
        try { gattServer?.close() } catch (e: Exception) { EventLog.log("ble", "Error closing GATT server: ${e.message}") }
        gattServer = null
    }

    fun destroy() { stopAllAdvertising(); stopGattServer() }
}
