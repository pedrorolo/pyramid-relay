package com.pyramidrelay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class BleCentralService(private val context: Context) {
    companion object {
        private const val TAG = "BleCentral"
        private val SERVICE_UUID = UUID.fromString(APP_SERVICE_UUID)
        private val META_UUID = UUID.fromString(META_CHAR_UUID)
        private val STREAM_UUID = UUID.fromString(STREAM_CHAR_UUID)
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val scanner: BluetoothLeScanner? get() = bluetoothManager.adapter?.bluetoothLeScanner
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private var lastNoServiceDataLogAt = 0L
    private val activeGattConnections = java.util.concurrent.ConcurrentHashMap<String, BluetoothGatt>()
    private val metaLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    var onDeviceDiscovered: ((deviceAddress: String, serviceData: ByteArray) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanJob?.isActive == true) return
        val s = scanner ?: run {
            Log.e(TAG, "BLE scanner not available")
            EventLog.log("ble", "BLE scanner not available (Bluetooth off or missing)")
            return
        }
        // No hardware ScanFilter: some OEM stacks silently drop legacy
        // advertisements that carry 16-bit-UUID service data when filtered by
        // setServiceUuid. Match on service data in code instead - and every
        // non-matching packet is visible to diagnostics.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build()
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
                if (data == null) {
                    // Packets from other apps/phones arrive too; only log occasionally.
                    val now = System.currentTimeMillis()
                    if (now - lastNoServiceDataLogAt > 5_000) {
                        lastNoServiceDataLogAt = now
                        EventLog.log("ble", "Scan result without our service data (other BLE traffic)")
                    }
                    return
                }
                scope.launch { onDeviceDiscovered?.invoke(result.device.address, data) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                EventLog.log("ble", "Scan failed: $errorCode")
            }
        }
        try {
            s.startScan(emptyList(), settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan", e)
            EventLog.log("ble", "Failed to start scan: ${e.message} (check BLUETOOTH_SCAN permission)")
            scanCallback = null
            return
        }
        scanJob = scope.launch { Log.d(TAG, "BLE scan started") }
        EventLog.log("ble", "BLE scan started")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try { scanCallback?.let { scanner?.stopScan(it) } } catch (e: Exception) { EventLog.log("ble", "Error stopping scan: ${e.message}") }
        scanCallback = null; scanJob?.cancel(); scanJob = null
        EventLog.log("ble", "BLE scan stopped")
    }

    @SuppressLint("MissingPermission")
    suspend fun readMeta(deviceAddress: String, fileIdHash: ByteArray? = null): BleMetaPayload? {
        return metaLocks.getOrPut(deviceAddress) { kotlinx.coroutines.sync.Mutex() }.withLock {
            readMetaLocked(deviceAddress, fileIdHash)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readMetaLocked(deviceAddress: String, fileIdHash: ByteArray? = null): BleMetaPayload? {
        val device = bluetoothManager.adapter?.getRemoteDevice(deviceAddress) ?: run {
            EventLog.log("ble", "readMeta: adapter or device unavailable for ${deviceAddress.takeLast(5)}")
            return null
        }
        val hexHash = fileIdHash?.joinToString("") { "%02x".format(it) }
        // Samsung BLE connections frequently fail on the first attempt (status !=
        // GATT_SUCCESS). Retry a few times before giving up.
        var lastStatus = -1
        val retryDelayMs = 1500L
        repeat(5) { attempt ->
            val attemptNo = attempt + 1
            val deferred = CompletableDeferred<BleMetaPayload?>()
            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    lastStatus = status
                    if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        EventLog.log("ble", "GATT connected to ${deviceAddress.takeLast(5)} (attempt $attemptNo)")
                        try {
                            val refresh = gatt.javaClass.getMethod("refresh")
                            refresh.invoke(gatt)
                        } catch (_: Exception) {}
                        if (!gatt.requestMtu(512)) gatt.discoverServices()
                    } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                        EventLog.log("ble", "GATT disconnected from ${deviceAddress.takeLast(5)} (attempt $attemptNo, status=$status)")
                        deferred.complete(null); gatt.close()
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    gatt.discoverServices()
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) { EventLog.log("ble", "Service discovery FAILED for ${deviceAddress.takeLast(5)} (status=$status)"); deferred.complete(null); return }
                    if (hexHash != null) {
                        val streamChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(STREAM_UUID)
                        if (streamChar != null) {
                            val submit = gatt.writeCharacteristic(streamChar, "SELECT $hexHash".toByteArray(Charsets.UTF_8), android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                            if (submit != BluetoothGatt.GATT_SUCCESS) {
                                EventLog.log("ble", "SELECT write failed ($submit), reading META directly")
                            }
                        }
                    }
                    val metaChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(META_UUID)
                    if (metaChar != null) gatt.readCharacteristic(metaChar) else { EventLog.log("ble", "META characteristic not found on ${deviceAddress.takeLast(5)}"); deferred.complete(null) }
                }
                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    if (characteristic.uuid == STREAM_UUID) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val metaChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(META_UUID)
                            if (metaChar != null) gatt.readCharacteristic(metaChar) else { deferred.complete(null) }
                        } else {
                            EventLog.log("ble", "SELECT rejected ($status) for ${deviceAddress.takeLast(5)}")
                            deferred.complete(null); gatt.disconnect(); gatt.close()
                        }
                    }
                }
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == META_UUID)
                        deferred.complete(BleMetaPayload.fromBytes(value))
                    else { EventLog.log("ble", "Characteristic read FAILED for ${deviceAddress.takeLast(5)} (status=$status)"); deferred.complete(null) }
                    gatt.disconnect(); gatt.close()
                }
            }
            val gatt = device.connectGatt(context, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                EventLog.log("ble", "GATT connect returned null for ${deviceAddress.takeLast(5)} (attempt $attemptNo)")
                deferred.complete(null)
            }
            EventLog.log("ble", "GATT connect to ${deviceAddress.takeLast(5)} for meta read (attempt $attemptNo)")
            val result = try { withTimeout(90_000L) { deferred.await() } } catch (e: Exception) { Log.e(TAG, "Timeout reading meta from $deviceAddress", e); EventLog.log("ble", "Meta read TIMED OUT from ${deviceAddress.takeLast(5)} (attempt $attemptNo)"); null }
            if (result != null) return result
            if (attempt < 4) kotlinx.coroutines.delay(retryDelayMs)
        }
        EventLog.log("ble", "Meta read FAILED after 5 attempts for ${deviceAddress.takeLast(5)} (last status=$lastStatus)")
        return null
    }

    fun destroy() { stopScan(); scope.cancel() }

    fun disconnectDevice(deviceAddress: String) {
        activeGattConnections.remove(deviceAddress)?.let {
            try {
                it.disconnect()
                it.close()
                EventLog.log("ble", "Disconnected from ${deviceAddress.takeLast(5)}")
            } catch (e: Exception) {
                EventLog.log("ble", "Error disconnecting from ${deviceAddress.takeLast(5)}: ${e.message}")
            }
        }
    }

    fun disconnectAll() {
        activeGattConnections.keys.toList().forEach { disconnectDevice(it) }
    }

    /**
     * Downloads a file from a peripheral over GATT: connects, negotiates MTU,
     * writes "PULL v<version>" to the STREAM characteristic, then reads chunks
     * sequentially (each read returns the next slice; an empty read = done).
     * Natural backpressure - the central requests every chunk itself.
     */
    @SuppressLint("MissingPermission")
    suspend fun fetchFile(deviceAddress: String, version: Int, expectedSize: Long, output: java.io.OutputStream, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        val device = bluetoothManager.adapter?.getRemoteDevice(deviceAddress) ?: run {
            EventLog.log("ble", "fetchFile: adapter or device unavailable for ${deviceAddress.takeLast(5)}")
            return false
        }
        val deferred = CompletableDeferred<Boolean>()
        val buffer = java.io.ByteArrayOutputStream()
        var gattRef: BluetoothGatt? = null
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    if (!gatt.requestMtu(517)) gatt.discoverServices()
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    EventLog.log("ble", "fetchFile: disconnected with ${buffer.size()}B received")
                    if (!deferred.isCompleted) {
                        // Write whatever we received to the output
                        try { 
                            output.write(buffer.toByteArray()); 
                            output.flush()
                            EventLog.log("ble", "fetchFile: flushed ${buffer.size()}B to output on disconnect")
                        } catch (e: Exception) { 
                            EventLog.log("ble", "fetchFile: flush on disconnect failed: ${e.message}") 
                        }
                        deferred.complete(false)
                    }
                    activeGattConnections.remove(deviceAddress)
                    gatt.close()
                }
            }
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) { gatt.discoverServices() }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) { EventLog.log("ble", "fetchFile: service discovery FAILED (status=$status)"); deferred.complete(false); return }
                val streamChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(STREAM_UUID)
                if (streamChar == null) { EventLog.log("ble", "fetchFile: STREAM characteristic not found"); deferred.complete(false); return }
                // Subscribe to notifications first, then write PULL
                if (!gatt.setCharacteristicNotification(streamChar, true)) {
                    EventLog.log("ble", "fetchFile: notification subscribe failed"); deferred.complete(false); return
                }
                val ccc = streamChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (ccc == null) { EventLog.log("ble", "fetchFile: CCC descriptor missing"); deferred.complete(false); return }
                if (gatt.writeDescriptor(ccc, android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothGatt.GATT_SUCCESS) {
                    EventLog.log("ble", "fetchFile: CCC write failed to submit"); deferred.complete(false)
                }
            }
            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: android.bluetooth.BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid != UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) return
                if (status != BluetoothGatt.GATT_SUCCESS) { EventLog.log("ble", "fetchFile: CCC write rejected ($status)"); deferred.complete(false); return }
                EventLog.log("ble", "fetchFile: CCC subscribed, sending PULL")
                val streamChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(STREAM_UUID) ?: run { deferred.complete(false); return }
                val submit = gatt.writeCharacteristic(streamChar, "PULL v$version".toByteArray(Charsets.UTF_8), android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (submit != BluetoothGatt.GATT_SUCCESS) { EventLog.log("ble", "fetchFile: PULL write failed to submit ($submit)"); deferred.complete(false) }
            }
            private var inactivityJob: kotlinx.coroutines.Job? = null
            private fun resetInactivityTimer(gatt: BluetoothGatt) {
                inactivityJob?.cancel()
                inactivityJob = scope.launch {
                    delay(30_000L)
                    if (!deferred.isCompleted) {
                        EventLog.log("ble", "fetchFile: inactivity timeout (30s) — aborting")
                        deferred.complete(false)
                        gatt.disconnect()
                    }
                }
            }
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (characteristic.uuid == STREAM_UUID) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        EventLog.log("ble", "fetchFile: PULL write rejected (status=$status)")
                        deferred.complete(false)
                    } else {
                        // Start first-chunk timeout: if no data arrives in 15s, abort
                        scope.launch {
                            delay(15_000L)
                            if (buffer.size() == 0 && !deferred.isCompleted) {
                                EventLog.log("ble", "fetchFile: first chunk timeout (15s) — aborting")
                                deferred.complete(false)
                                gatt.disconnect()
                            }
                        }
                        resetInactivityTimer(gatt)
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid != STREAM_UUID || deferred.isCompleted) return
                resetInactivityTimer(gatt)
                buffer.write(value)
                val got = buffer.size()
                onProgress?.invoke(got.toLong(), expectedSize)
                if ((got - value.size) / 40_720 != got / 40_720 || got.toLong() == expectedSize)
                    EventLog.log("ble", "Downloading... $got/$expectedSize B")
                if (value.isEmpty() || got >= expectedSize) {
                    inactivityJob?.cancel()
                    EventLog.log("ble", "fetchFile: writing ${buffer.size()}B to output stream on thread ${Thread.currentThread().name}")
                    try { 
                        output.write(buffer.toByteArray()); 
                        output.flush()
                        EventLog.log("ble", "fetchFile: write complete, output stream class: ${output.javaClass.simpleName}")
                    } catch (e: Exception) { 
                        EventLog.log("ble", "fetchFile: flush failed: ${e.message}"); 
                        deferred.complete(false); 
                        return 
                    }
                      deferred.complete(true)
                    activeGattConnections.remove(deviceAddress)
                    gatt.disconnect(); gatt.close(); gattRef = null
                }
            }
        }
        device.connectGatt(context, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE).also { gattRef = it; activeGattConnections[deviceAddress] = it }
            ?: run { EventLog.log("ble", "fetchFile: connectGatt returned null for ${deviceAddress.takeLast(5)}"); return false }
        EventLog.log("ble", "GATT fetchFile v$version ($expectedSize B) from ${deviceAddress.takeLast(5)}")
        // 60s handshake + worst-case 5 KB/s transfer budget (resilient to poor connections)
        val timeoutMs = 60_000L + expectedSize * 1000L / 5_000L
        val ok = try { withTimeout(timeoutMs) { deferred.await() } } catch (e: Exception) {
            EventLog.log("ble", "fetchFile TIMED OUT after ${timeoutMs / 1000}s (${buffer.size()}/$expectedSize B) from ${deviceAddress.takeLast(5)}")
            false
        }
        gattRef?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        EventLog.log("ble", "fetchFile ${if (ok && buffer.size().toLong() == expectedSize) "COMPLETE" else "INCOMPLETE"} (${buffer.size()}/$expectedSize B)")
        return ok && buffer.size().toLong() == expectedSize
    }
}
