package p2p.broadcaster

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import p2p.broadcaster.EventLog

class BleForegroundService : Service() {

    companion object {
        private const val TAG = "BleFgService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ble_foreground"
    }

    private var syncEngine: SyncEngine? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as P2PBroadcasterApp
        syncEngine = app.syncEngine
        try {
            NotificationService(this).createForegroundNotification()?.let {
                startForeground(NOTIFICATION_ID, it, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
            EventLog.log("ble", "Failed to start foreground service: ${e.message}")
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
        syncEngine?.stop()
        super.onDestroy()
        Log.d(TAG, "BLE foreground service stopped")
    }
}
