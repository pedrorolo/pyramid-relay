package p2p.broadcaster

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import p2p.broadcaster.EventLog

class BleForegroundService : Service() {

    companion object {
        private const val TAG = "BleFgService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ble_foreground"
    }

    private var syncEngine: SyncEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "p2p.broadcaster:ble").apply {
            acquire()
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
        wakeLock?.let { if (it.isHeld) it.release() }
        syncEngine?.stop()
        super.onDestroy()
        Log.d(TAG, "BLE foreground service stopped")
    }
}
