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
    // One independent advertiser per broadcast: a legacy 31B packet holds exactly
    // one service-data entry, so files can never share a single advertisement.
    private val activeAdvertisements = java.util.concurrent.ConcurrentHashMap<String, AdvertiseCallback>()
    // The fileId whose meta the single META characteristic serves. The GATT server
    // has one fixed META char (152B), so it can only serve one file's meta; track the
    // most recently advertised file. (Most deployments broadcast one file at a time.)
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
        if (activeAdvertisements.containsKey(fileId)) return
        val adv = adapter?.bluetoothLeAdvertiser ?: run {
            EventLog.log("ble", "BLE advertiser not available")
            return
        }
        advertiser = adv
        servicedFileId = fileId
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
        // 21B on the wire: 3B flags + 18B service-data AD structure
        // (2B len/type + 2B UUID16 + 14B payload).
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), serviceData)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { Log.d(TAG, "Advertising started for $fileId") }
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed: $errorCode")
                EventLog.log("ble", "Advertising start failed: $errorCode")
            }
        }
        activeAdvertisements[fileId] = callback
        // Track fileIdHash -> fileId so SELECT works.
        val hashHex = serviceData.copyOfRange(0, 6).joinToString("") { "%02x".format(it) }
        fileHashToFileId[hashHex] = fileId
        try { adv.startAdvertising(settings, data, callback) } catch (e: Exception) {
            activeAdvertisements.remove(fileId)
            fileHashToFileId.remove(hashHex)
            Log.e(TAG, "Failed to start advertising", e)
            EventLog.log("ble", "Failed to start advertising: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising(fileId: String) {
        activeAdvertisements.remove(fileId)?.let { callback ->
            try { advertiser?.stopAdvertising(callback) } catch (e: Exception) { EventLog.log("ble", "Error stopping advertising: ${e.message}") }
        }
        fileHashToFileId.entries.removeAll { it.value == fileId }
    }

    @SuppressLint("MissingPermission")
    fun stopAllAdvertising() {
        val callbacks = activeAdvertisements.values.toList()
        activeAdvertisements.clear()
        for (callback in callbacks) {
            try { advertiser?.stopAdvertising(callback) } catch (e: Exception) { EventLog.log("ble", "Error stopping advertising: ${e.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopGattServer() {
        try { gattServer?.close() } catch (e: Exception) { EventLog.log("ble", "Error closing GATT server: ${e.message}") }
        gattServer = null
    }

    fun destroy() { stopAllAdvertising(); stopGattServer() }
}
