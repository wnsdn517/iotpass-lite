package dev.hoduj.iotpasslite

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** One honest warning: with Bluetooth off the watcher can do nothing, so say so. */
object Notifications {

    private const val CHANNEL_ID = "iotpass_lite_status"
    private const val NOTIF_ID_BLUETOOTH_OFF = 9001

    fun showBluetoothOff(context: Context) {
        // Optional feature: silently skip when the user has not granted notifications.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.notif_bt_off_title))
            .setContentText(context.getString(R.string.notif_bt_off_text))
            .setContentIntent(contentIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID_BLUETOOTH_OFF, notification)
    }

    fun clearBluetoothOff(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID_BLUETOOTH_OFF)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }
}
