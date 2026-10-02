package dev.hoduj.iotpasslite;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

/** A single, honest warning: if Bluetooth is off, the watcher cannot do anything at all — no
 *  scanning, no advertising — so silently doing nothing would be worse than saying so. */
final class Notifications {

    private static final String CHANNEL_ID = "iotpass_lite_status";
    private static final int NOTIF_ID_BLUETOOTH_OFF = 9001;

    private Notifications() {
    }

    static void showBluetoothOff(Context context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return; // Optional feature — silently skip rather than crash on strict API 33+ checks.
        }
        ensureChannel(context);
        Intent openApp = new Intent(context, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, 0, openApp, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(context.getString(R.string.notif_bt_off_title))
                .setContentText(context.getString(R.string.notif_bt_off_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIF_ID_BLUETOOTH_OFF, notification);
        }
    }

    static void clearBluetoothOff(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIF_ID_BLUETOOTH_OFF);
        }
    }

    private static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        manager.createNotificationChannel(channel);
    }
}
