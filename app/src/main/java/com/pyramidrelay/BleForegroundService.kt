package com.pyramidrelay

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.pyramidrelay.EventLog

class BleForegroundService : Service() {

    companion object {
        private const val TAG = "BleFgService"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHECK_INTERVAL_MS = 30_000L
    }

    private var syncEngine: SyncEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var persistentNotification: Boolean = true
    private val notificationService by lazy { NotificationService(this) }
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private val handler = Handler(Looper.getMainLooper())
    private val notificationChecker = object : Runnable {
        override fun run() {
            if (!isNotificationActive()) {
                EventLog.log("ble", "Persistent notification was cleared — re-posting")
                rePostNotification()
            }
            handler.postDelayed(this, NOTIFICATION_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as P2PBroadcasterApp
        syncEngine = app.syncEngine
        persistentNotification = app.settingsStore.showPersistentNotification
        rePostNotification()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.pyramidrelay:ble").apply {
            acquire()
        }
        if (persistentNotification) {
            handler.post(notificationChecker)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            syncEngine?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sync engine", e)
            EventLog.log("app", "Failed to start sync engine: ${e.message}")
        }
        Log.d(TAG, "BLE foreground service started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(notificationChecker)
        wakeLock?.let { if (it.isHeld) it.release() }
        syncEngine?.stop()
        super.onDestroy()
        Log.d(TAG, "BLE foreground service stopped")
    }

    private fun isNotificationActive(): Boolean {
        val notifications = notificationManager.activeNotifications
        return notifications.any { it.id == NOTIFICATION_ID }
    }

    private fun rePostNotification() {
        try {
            notificationService.createForegroundNotification(persistentNotification)?.let {
                startForeground(NOTIFICATION_ID, it, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-post foreground notification", e)
        }
    }
}
