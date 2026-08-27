package p2p.broadcaster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

class NotificationService(private val context: Context) {

    companion object {
        private const val TAG = "NotificationService"
        const val CHANNEL_ID = "p2p_updates"
        const val CHANNEL_NAME = "File Updates"
        private const val FG_CHANNEL_ID = "ble_foreground"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init { createNotificationChannel() }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Notifications for file updates from peers"
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
        val fgChannel = NotificationChannel(FG_CHANNEL_ID, "BLE Background", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(fgChannel)
    }

    fun createForegroundNotification(): Notification? {
        return Notification.Builder(context, FG_CHANNEL_ID)
            .setContentTitle("P2P Broadcaster")
            .setContentText("Scanning for nearby files...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    fun showUpdateNotification(fileName: String, fileId: String, oldVersion: Int, newVersion: Int) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("file_id", fileId); putExtra("version", newVersion)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, fileId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompatBuilder(context).build(
            CHANNEL_ID, "File updated", "\"$fileName\" v$oldVersion -> v$newVersion",
            android.R.drawable.ic_dialog_info, pendingIntent
        )
        notificationManager.notify(fileId.hashCode(), notification)
        Log.d(TAG, "Notification shown for $fileId v$newVersion")
    }

    fun requestPermission(): Boolean = notificationManager.areNotificationsEnabled()
}

private class NotificationCompatBuilder(private val context: Context) {
    fun build(channelId: String, title: String, text: String, icon: Int, pendingIntent: android.app.PendingIntent): Notification {
        return Notification.Builder(context, channelId)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setPriority(Notification.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .build()
    }
}
