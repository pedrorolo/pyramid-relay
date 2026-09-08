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
        // NOTE: QUICKBOOT_POWERON is not an SDK constant (some OEMs broadcast
        // the raw "android.intent.action.QUICKBOOT_POWERON" string instead).
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return
        // Restart only the relay service after boot. Never launch the UI from
        // the background (background activity-start restriction). The service
        // always runs as a user-perceptible foreground service.
        Log.d(TAG, "Boot completed, starting BLE relay service")
        EventLog.log("app", "Device booted — starting Pyramid Relay relay service")
        try {
            ContextCompat.startForegroundService(context, Intent(context, BleForegroundService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start on boot", e)
            EventLog.log("app", "Failed to start on boot: ${e.message}")
        }
    }
}
