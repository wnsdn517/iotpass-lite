package dev.hoduj.iotpasslite

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the watcher after reboot, app update, and Bluetooth being turned back on (which clears every
 *  registered scan). */
class RearmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (BluetoothAdapter.ACTION_STATE_CHANGED == action) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF -> {
                    EventLog.log(context, EventLog.Type.BLUETOOTH_OFF, null, null)
                    if (Prefs.isEnabled(context)) Notifications.showBluetoothOff(context)
                }
                BluetoothAdapter.STATE_ON -> {
                    Notifications.clearBluetoothOff(context)
                    rearm(context, action)
                }
            }
            return
        }

        if (Intent.ACTION_BOOT_COMPLETED == action || Intent.ACTION_MY_PACKAGE_REPLACED == action) {
            rearm(context, action)
        }
    }

    private fun rearm(context: Context, reason: String) {
        if (!Prefs.isEnabled(context) || Prefs.getDoorMacs(context).isEmpty()) return
        try {
            DoorScanner.start(context, Prefs.getDoorMacs(context))
            EventLog.log(context, EventLog.Type.REARMED, null, "reason=$reason")
        } catch (e: Exception) {
            EventLog.log(context, EventLog.Type.REARM_FAILED, null, "reason=$reason error=${e.message}")
        }
    }
}
