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
        const val STREAM_IDLE_TIMEOUT_MS = 20_000L
        const val MAX_FETCH_ATTEMPTS = 3
        private const val MAX_DISCOVERY_RETRIES = 5
        // Flow-control window: number of chunks the central grants the peripheral
        // at a time. Bounds in-flight data to link capacity (8 * 512B = 4KB).
        private const val FLOW_WINDOW = 8
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val scanner: BluetoothLeScanner? get() = bluetoothManager.adapter?.bluetoothLeScanner
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private var lastNoServiceDataLogAt = 0L
    private val activeGattConnections = java.util.concurrent.ConcurrentHashMap<String, BluetoothGatt>()
    private val metaLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    var onDeviceDiscovered: ((deviceAddress: String, serviceData: ByteArray, metaPayload: BleMetaPayload?) -> Unit)? = null
    var onCongestionDetected: ((deviceAddress: String) -> Unit)? = null

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
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setLegacy(false)
            .setPhy(android.bluetooth.BluetoothDevice.PHY_LE_1M)
            .build()
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
                val meta = BleMetaPayload.fromBytes(data)
                scope.launch { onDeviceDiscovered?.invoke(result.device.address, data, meta) }
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
        var metaNotFound = false
        val attemptNo = 1
        val deferred = CompletableDeferred<BleMetaPayload?>()
        var gattRef: BluetoothGatt? = null
        val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    lastStatus = status
                    if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        EventLog.log("ble", "GATT connected to ${deviceAddress.takeLast(5)} (attempt $attemptNo)")
                        // NOTE: refresh() is a hidden/private Android API and is intentionally
                        // NOT called. Discovery relies on the normal stack only.
                        if (!gatt.requestMtu(517)) gatt.discoverServices()
                    } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                        EventLog.log("ble", "GATT disconnected from ${deviceAddress.takeLast(5)} (attempt $attemptNo, status=$status)")
                        gattRef = null
                        gatt.disconnect(); gatt.close()
                        onCongestionDetected?.invoke(deviceAddress)
                        deferred.complete(null)
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    negotiateConnectionParams(gatt)
                    gatt.discoverServices()
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) { EventLog.log("ble", "Service discovery FAILED for ${deviceAddress.takeLast(5)} (status=$status)"); onCongestionDetected?.invoke(deviceAddress); deferred.complete(null); return }
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
                    if (metaChar != null) gatt.readCharacteristic(metaChar) else { EventLog.log("ble", "META characteristic not found on ${deviceAddress.takeLast(5)}"); metaNotFound = true; gatt.disconnect(); gatt.close(); onCongestionDetected?.invoke(deviceAddress); deferred.complete(null) }
                }
                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    if (characteristic.uuid == STREAM_UUID) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val metaChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(META_UUID)
                            if (metaChar != null) gatt.readCharacteristic(metaChar) else { metaNotFound = true; gatt.disconnect(); gatt.close(); onCongestionDetected?.invoke(deviceAddress); deferred.complete(null) }
                        } else {
                            EventLog.log("ble", "SELECT rejected ($status) for ${deviceAddress.takeLast(5)}")
                            onCongestionDetected?.invoke(deviceAddress); deferred.complete(null); gatt.disconnect(); gatt.close()
                        }
                    }
                }
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == META_UUID)
                        deferred.complete(BleMetaPayload.fromBytes(value))
                    else { EventLog.log("ble", "Characteristic read FAILED for ${deviceAddress.takeLast(5)} (status=$status)"); deferred.complete(null); onCongestionDetected?.invoke(deviceAddress) }
                    gatt.disconnect(); gatt.close()
                }
            }
            val gatt = device.connectGatt(context, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            EventLog.log("ble", "GATT connect returned null for ${deviceAddress.takeLast(5)} (attempt $attemptNo)")
            deferred.complete(null)
        } else {
            gattRef = gatt
        }
        EventLog.log("ble", "GATT connect to ${deviceAddress.takeLast(5)} for meta read (attempt $attemptNo)")
        try {
            val result = try { withTimeout(45_000L) { deferred.await() } } catch (e: Exception) { Log.e(TAG, "Timeout reading meta from $deviceAddress", e); EventLog.log("ble", "Meta read TIMED OUT from ${deviceAddress.takeLast(5)} (attempt $attemptNo)"); null }
            if (metaNotFound) { EventLog.log("ble", "META not found - aborting immediately"); return null }
            if (result != null) return result
        } finally {
            gattRef?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
            gattRef = null
        }
        EventLog.log("ble", "Meta read FAILED for ${deviceAddress.takeLast(5)} (last status=$lastStatus)")
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
     * Downloads a file from a peripheral over GATT: connects, negotiates MTU and a
     * high-throughput link, writes "PULL v<version> [<offset>]" to the STREAM
     * characteristic, then receives chunks via notifications. Credit-based flow
     * control: the central grants the peripheral a small window of chunks and
     * replenishes it as each chunk is actually received, so the peripheral never
     * overflows the link.
     *
     * Retries RESUME: bytes received by earlier attempts are kept in a cumulative
     * buffer and the retry asks the peripheral to continue from that offset, so a
     * stall at 90% costs one handshake, not the whole file. If the peer ignores
     * the offset (old build) and restarts from zero, the merge below detects the
     * overlap and adopts the fresh full copy instead of corrupting the buffer.
     */
    @SuppressLint("MissingPermission")
    suspend fun fetchFile(deviceAddress: String, version: Int, expectedSize: Long, output: java.io.OutputStream, onProgress: ((Long, Long) -> Unit)? = null, fileIdHash: ByteArray? = null): Boolean {
        val shared = java.io.ByteArrayOutputStream()
        for (attempt in 0 until MAX_FETCH_ATTEMPTS) {
            val offset = shared.size().toLong()
            if (offset >= expectedSize) break
            val isLast = attempt == MAX_FETCH_ATTEMPTS - 1
            if (attempt > 0) {
                EventLog.log("ble", "fetchFile: resuming from $offset/$expectedSize B (attempt ${attempt + 1}/$MAX_FETCH_ATTEMPTS) for ${deviceAddress.takeLast(5)}")
                kotlinx.coroutines.delay(300)
            }
            val attemptOk = attemptFetch(deviceAddress, version, expectedSize, offset, shared, onProgress, fileIdHash, isLast)
            if (attemptOk && shared.size().toLong() == expectedSize) {
                if (attempt > 0) EventLog.log("ble", "fetchFile: succeeded on attempt ${attempt + 1} for ${deviceAddress.takeLast(5)}")
                break
            }
        }
        if (shared.size().toLong() != expectedSize) {
            EventLog.log("ble", "fetchFile INCOMPLETE after $MAX_FETCH_ATTEMPTS attempts (${shared.size()}/$expectedSize B)")
            return false
        }
        return try {
            output.write(shared.toByteArray())
            output.flush()
            true
        } catch (e: Exception) {
            EventLog.log("ble", "fetchFile: final flush failed: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission", "DiscouragedPrivateApi", "BlockedPrivateApi")
    private suspend fun attemptFetch(
        deviceAddress: String,
        version: Int,
        expectedSize: Long,
        offset: Long,
        shared: java.io.ByteArrayOutputStream,
        onProgress: ((Long, Long) -> Unit)?,
        fileIdHash: ByteArray?,
        isLastAttempt: Boolean
    ): Boolean {
        val device = bluetoothManager.adapter?.getRemoteDevice(deviceAddress) ?: run {
            EventLog.log("ble", "attemptFetch: adapter or device unavailable for ${deviceAddress.takeLast(5)}")
            return false
        }
        val deferred = CompletableDeferred<Boolean>()
        // Bytes collected by THIS attempt only. Merged into `shared` exactly once
        // (see mergeOnce) so a retry resumes from `offset` instead of zero.
        val chunk = java.io.ByteArrayOutputStream()
        val merged = java.util.concurrent.atomic.AtomicBoolean(false)
        fun mergeOnce() {
            if (!merged.compareAndSet(false, true)) return
            val n = chunk.size()
            if (n == 0) return
            val rem = expectedSize - shared.size().toLong()
            when {
                n.toLong() > rem && n.toLong() == expectedSize -> {
                    // Peer ignored our resume offset (old build) and streamed the
                    // whole file from zero — adopt it as a fresh full download.
                    EventLog.log("ble", "fetchFile: peer restarted from 0, adopting full ${n}B buffer")
                    shared.reset()
                    shared.write(chunk.toByteArray())
                }
                n.toLong() > rem -> {
                    // Overlapping partial from an offset-ignoring peer — unusable.
                    EventLog.log("ble", "fetchFile: discarding ${n}B overlapping chunk (peer ignored offset)")
                }
                else -> shared.write(chunk.toByteArray())
            }
        }
        var gattRef: BluetoothGatt? = null
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    // Clear any stale cached service handles so subsequent writes
                    // resolve to valid characteristics (fixes GATT_INVALID_HANDLE / status=1).
                    // refresh() is unreliable on Samsung, so give it time to take effect
                    // before requesting MTU / discovering services.
                    scope.launch {
                        try {
                            // refresh() is a hidden/private Android API — intentionally NOT
                            // called. The 800ms settle gives the stack time post-connect.
                            // refreshGatt(gatt)
                            kotlinx.coroutines.delay(800)
                            if (!gatt.requestMtu(517)) gatt.discoverServices()
                        } catch (_: Exception) {}
                    }
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    EventLog.log("ble", "fetchFile: disconnected with ${chunk.size()}B this attempt (${shared.size()}B kept, status=$status)")
                    if (!deferred.isCompleted) {
                        deferred.complete(false)
                    }
                    activeGattConnections.remove(deviceAddress)
                    gatt.disconnect(); gatt.close()
                    // Only treat as congestion on the final attempt so transient retry
                    // failures don't escalate the backoff unnecessarily.
                    if ((status == 147 || status == 133) && isLastAttempt) onCongestionDetected?.invoke(deviceAddress)
                }
            }
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) { negotiateConnectionParams(gatt); gatt.discoverServices() }
            private var selectDone = false
            private var cccDone = false
            private var discoveryRetries = 0
            private var streamCharRef: BluetoothGattCharacteristic? = null
            private var receivedChunks = 0
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    EventLog.log("ble", "fetchFile: service discovery FAILED (status=$status)")
                    if (discoveryRetries < MAX_DISCOVERY_RETRIES) {
                        discoveryRetries++
                        EventLog.log("ble", "fetchFile: re-discovering services (attempt $discoveryRetries/$MAX_DISCOVERY_RETRIES)")
                        scope.launch { delay(500); try { gatt.discoverServices() } catch (_: Exception) {} }
                    } else deferred.complete(false)
                    return
                }
                val streamChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(STREAM_UUID)
                streamCharRef = streamChar
                if (streamChar == null) {
                    EventLog.log("ble", "fetchFile: STREAM characteristic not found")
                    if (discoveryRetries < MAX_DISCOVERY_RETRIES) {
                        discoveryRetries++
                        EventLog.log("ble", "fetchFile: STREAM missing, re-discovering (attempt $discoveryRetries/$MAX_DISCOVERY_RETRIES)")
                        scope.launch { delay(500); try { gatt.discoverServices() } catch (_: Exception) {} }
                    } else deferred.complete(false)
                    return
                }
                if (fileIdHash != null) {
                    val hexHash = fileIdHash.joinToString("") { "%02x".format(it) }
                    val selectResult = gatt.writeCharacteristic(streamChar, "SELECT $hexHash".toByteArray(Charsets.UTF_8), android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    if (selectResult != BluetoothGatt.GATT_SUCCESS) { EventLog.log("ble", "fetchFile: SELECT write failed ($selectResult)"); deferred.complete(false); return }
                } else {
                    selectDone = true
                }
            }
            private var pullSent = false
            private var pullResponsePending = false
            private var creditTopupJob: kotlinx.coroutines.Job? = null
            private fun grantCredits(gatt: BluetoothGatt) {
                try { streamCharRef?.let { sc -> gatt.writeCharacteristic(sc, "CRED $FLOW_WINDOW".toByteArray(Charsets.UTF_8), android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) } } catch (_: Exception) { }
            }
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (characteristic.uuid != STREAM_UUID) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    EventLog.log("ble", "fetchFile: write rejected (status=$status)")
                    deferred.complete(false); gatt.disconnect(); gatt.close(); gattRef = null; return
                }
                if (!pullSent) {
                    // This is the SELECT response (or first write). Now enable notifications.
                    EventLog.log("ble", "fetchFile: SELECT accepted, enabling notifications")
                    if (!gatt.setCharacteristicNotification(characteristic, true)) {
                        EventLog.log("ble", "fetchFile: notification subscribe failed"); deferred.complete(false); return
                    }
                    val ccc = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    if (ccc == null) { EventLog.log("ble", "fetchFile: CCC descriptor missing"); deferred.complete(false); return }
                    if (gatt.writeDescriptor(ccc, android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothGatt.GATT_SUCCESS) {
                        EventLog.log("ble", "fetchFile: CCC write failed to submit"); deferred.complete(false)
                    }
                } else if (pullResponsePending) {
                    pullResponsePending = false
                    // This is the PULL response. Start first-chunk timeout.
                    EventLog.log("ble", "fetchFile: PULL accepted, waiting for data")
                    // Grant the initial flow-control window now the stream is armed,
                    // so the peripheral can begin sending (avoids a back-to-back write
                    // with PULL, which makes writeCharacteristic report a spurious 201).
                    grantCredits(gatt)
                    // Continuously top up credits on a timer. The peripheral only sends
                    // what the link can carry (notifyCharacteristicChanged returns false
                    // when the controller queue is full, and the peripheral retries), so
                    // over-granting is safe. The timer guarantees the peripheral never
                    // starves if a notification (and thus the count-based top-up) is lost
                    // mid-stream, which previously deadlocked the transfer.
                    creditTopupJob = scope.launch {
                        while (!deferred.isCompleted) {
                            delay(200)
                            if (deferred.isCompleted) break
                            grantCredits(gatt)
                        }
                    }
                    scope.launch {
                        delay(STREAM_IDLE_TIMEOUT_MS)
                        if (chunk.size() == 0 && !deferred.isCompleted) {
                            EventLog.log("ble", "fetchFile: first chunk timeout (${STREAM_IDLE_TIMEOUT_MS / 1000}s) — aborting")
                            deferred.complete(false)
                            gatt.disconnect(); gatt.close(); gattRef = null
                        }
                    }
                    resetInactivityTimer(gatt)
                } else {
                    // Unexpected write callback (e.g. a NO_RESPONSE CRED ack that
                    // some stacks still report) — ignore it, do not treat as PULL.
                    return
                }
            }
            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: android.bluetooth.BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid != UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) return
                if (status != BluetoothGatt.GATT_SUCCESS) { EventLog.log("ble", "fetchFile: CCC write rejected ($status)"); deferred.complete(false); return }
                EventLog.log("ble", "fetchFile: CCC subscribed, sending PULL")
                val streamChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(STREAM_UUID) ?: run { deferred.complete(false); return }
                pullSent = true
                pullResponsePending = true
                // NOTE: writeCharacteristic()'s return value is an unreliable status on
                // several stacks (it spuriously returns 201 "busy" here even though
                // onCharacteristicWrite later reports SUCCESS). Only the callback is
                // authoritative, so we must NOT abort on a non-zero return.
                val pullCmd = if (offset > 0) "PULL v$version $offset" else "PULL v$version"
                if (offset > 0) EventLog.log("ble", "fetchFile: requesting resume from $offset/$expectedSize B")
                val submit = gatt.writeCharacteristic(streamChar, pullCmd.toByteArray(Charsets.UTF_8), android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (submit != BluetoothGatt.GATT_SUCCESS) { EventLog.log("ble", "fetchFile: PULL write returned $submit (non-fatal, awaiting onCharacteristicWrite)") }
            }
            private var inactivityJob: kotlinx.coroutines.Job? = null
            private fun resetInactivityTimer(gatt: BluetoothGatt) {
                inactivityJob?.cancel()
                inactivityJob = scope.launch {
                    delay(STREAM_IDLE_TIMEOUT_MS)
                    if (!deferred.isCompleted) {
                        EventLog.log("ble", "fetchFile: inactivity timeout (${STREAM_IDLE_TIMEOUT_MS / 1000}s) — aborting")
                        deferred.complete(false)
                        gatt.disconnect(); gatt.close(); gattRef = null
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid != STREAM_UUID || deferred.isCompleted) return
                resetInactivityTimer(gatt)
                chunk.write(value)
                val got = offset + chunk.size()
                onProgress?.invoke(got, expectedSize)
                // Flow control: replenish the peripheral's credits as we actually
                // receive chunks, so it only sends as fast as the link delivers.
                receivedChunks++
                if (receivedChunks % FLOW_WINDOW == 0) {
                    scope.launch { try { streamCharRef?.let { sc -> gatt.writeCharacteristic(sc, "CRED $FLOW_WINDOW".toByteArray(Charsets.UTF_8), android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) } } catch (_: Exception) {} }
                }
                EventLog.log("ble", "Progress: $got/$expectedSize B for ${gatt.device?.address?.takeLast(5)}")
                if ((got - value.size) / 40_720 != got / 40_720 || got == expectedSize)
                    EventLog.log("ble", "Downloading... $got/$expectedSize B")
                if (value.isEmpty() || chunk.size().toLong() >= expectedSize - offset || chunk.size().toLong() == expectedSize) {
                    inactivityJob?.cancel()
                    creditTopupJob?.cancel()
                    mergeOnce()
                    val complete = shared.size().toLong() == expectedSize
                    EventLog.log("ble", "fetchFile: attempt collected ${chunk.size()}B (kept ${shared.size()}/$expectedSize B)")
                    deferred.complete(complete)
                    activeGattConnections.remove(deviceAddress)
                    gatt.disconnect(); gatt.close(); gattRef = null
                }
            }
        }
        device.connectGatt(context, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE).also { gattRef = it; activeGattConnections[deviceAddress] = it }
            ?: run { EventLog.log("ble", "fetchFile: connectGatt returned null for ${deviceAddress.takeLast(5)}"); return false }
        EventLog.log("ble", "GATT fetchFile v$version ($expectedSize B from $offset) from ${deviceAddress.takeLast(5)}")
        // 60s handshake + worst-case 5 KB/s transfer budget on the REMAINING bytes
        // (resilient to poor connections; shrinks as resume progress grows)
        val remaining = (expectedSize - offset).coerceAtLeast(0L)
        val timeoutMs = 60_000L + remaining * 1000L / 5_000L
        val ok = try {
            try { withTimeout(timeoutMs) { deferred.await() } } catch (e: Exception) {
                EventLog.log("ble", "fetchFile TIMED OUT after ${timeoutMs / 1000}s (${chunk.size()}B this attempt, ${shared.size()}/$expectedSize B kept) from ${deviceAddress.takeLast(5)}")
                false
            }
        } finally {
            gattRef?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
            gattRef = null
            activeGattConnections.remove(deviceAddress)
            // Preserve this attempt's partial bytes so the next retry resumes.
            mergeOnce()
        }
        EventLog.log("ble", "fetchFile ${if (ok && shared.size().toLong() == expectedSize) "COMPLETE" else "INCOMPLETE"} (${shared.size()}/$expectedSize B)")
        return ok && shared.size().toLong() == expectedSize
    }

    /**
     * Negotiates a high-throughput link for bulk transfer: a short connection
     * interval (CONNECTION_PRIORITY_HIGH ~ 7.5-25ms) and 2M PHY when supported.
     * Both are best-effort; failures are non-fatal and the link simply runs at
     * its default parameters.
     */
    @SuppressLint("MissingPermission")
    private fun negotiateConnectionParams(gatt: BluetoothGatt) {
        try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}
        try { gatt.setPreferredPhy(android.bluetooth.BluetoothDevice.PHY_LE_2M, android.bluetooth.BluetoothDevice.PHY_LE_2M, android.bluetooth.BluetoothDevice.PHY_OPTION_NO_PREFERRED) } catch (_: Exception) {}
    }

    // refresh() is a hidden/private Android API and is intentionally NOT invoked.
    // Kept as a no-op so historical call sites read clearly; stale-handle healing
    // is handled by the peripheral's periodic GATT-server restart instead.
    @SuppressLint("DiscouragedPrivateApi", "BlockedPrivateApi")
    private fun refreshGatt(gatt: BluetoothGatt): Boolean = false
}
