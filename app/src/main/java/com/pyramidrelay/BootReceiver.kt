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
        Log.d(TAG, "Boot completed, starting BLE service and app UI")
        EventLog.log("app", "Device booted — starting Pyramid Relay service and UI")
        try {
            val serviceIntent = Intent(context, BleForegroundService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
            val mainIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(mainIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start on boot", e)
            EventLog.log("app", "Failed to start on boot: ${e.message}")
        }
    }
}
