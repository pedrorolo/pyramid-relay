package com.pyramidrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "Boot completed, starting BLE service")
        EventLog.log("app", "Device booted — starting Pyramid Relay service")
        try {
            ContextCompat.startForegroundService(context, Intent(context, BleForegroundService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service on boot", e)
            EventLog.log("app", "Failed to start service on boot: ${e.message}")
        }
    }
}
