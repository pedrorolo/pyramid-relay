package com.pyramidrelay

import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelUuid
import android.util.Log

/**
 * Receives BLE scan results delivered via a PendingIntent (see
 * [BleCentralService.startPendingIntentScan]). This lets the OS perform background
 * scanning and wake the app on a match even when the app has been killed or is not
 * a foreground service, so peer detection survives screen-off without a persistent
 * notification. The OS instantiates the Application (running PyramidRelayApp.onCreate,
 * which initializes SyncEngine and starts scanning) before invoking this receiver,
 * so the discovery pipeline is already wired by the time we forward the result.
 */
class ScanResultReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScanResultReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BLE_SCAN_RESULT_ACTION) return
        // Scan results from a PendingIntent scan are delivered in the standard
        // BluetoothLeScanner extra. Read it directly by its documented key.
        val extraKey = "android.bluetooth.le.extra.SCAN_RESULT"
        val result: ScanResult? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(extraKey, ScanResult::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(extraKey) as? ScanResult
        }
        if (result == null) {
            Log.w(TAG, "Scan broadcast with no parseable result")
            return
        }
        val app = context.applicationContext as? PyramidRelayApp
        val central = app?.bleCentralService
        if (central == null) {
            Log.w(TAG, "App not initialized; ignoring scan result")
            return
        }
        val data = result.scanRecord?.getServiceData(ParcelUuid(java.util.UUID.fromString(APP_SERVICE_UUID)))
        val wantData = result.scanRecord?.getServiceData(ParcelUuid(java.util.UUID.fromString(APP_WANT_SERVICE_UUID)))
        if (data == null && wantData == null) return
        val isWant = data == null
        val sd = data ?: (wantData ?: return)
        val meta = BleMetaPayload.fromBytes(sd)
        // If we were restarted from a killed state (no foreground service running),
        // start the service as a regular (non-foreground) service so its wake lock
        // keeps the process alive for any resulting transfer.
        if (app.bleForegroundService == null) {
            try { context.startService(Intent(context, BleForegroundService::class.java)) } catch (_: Exception) {}
        }
        central.handleScanResult(result.device.address, sd, meta, isWant)
    }
}
