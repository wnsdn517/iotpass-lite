package dev.hoduj.iotpasslite;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/** The system-level BLE scan survives our process dying, but a few things do clear it outright:
 *  a reboot, this app being updated, and — the one that actually happens most in daily life —
 *  the user turning Bluetooth off and back on (flight mode, a stuck connection, etc. resets the
 *  whole Bluetooth stack, taking every registered scan down with it). This receiver re-arms the
 *  watcher for all three, so "여는 게 켜져 있다" stays true without having to reopen the app. */
public class RearmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }

        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_OFF) {
                onBluetoothOff(context);
                return;
            }
            if (state != BluetoothAdapter.STATE_ON) {
                return; // STATE_TURNING_OFF / STATE_TURNING_ON: nothing to do yet.
            }
            Notifications.clearBluetoothOff(context);
            rearm(context, action);
            return;
        }

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            rearm(context, action);
        }
    }

    private void onBluetoothOff(Context context) {
        EventLog.log(context, EventLog.Type.BLUETOOTH_OFF, null, null);
        if (Prefs.isEnabled(context)) {
            Notifications.showBluetoothOff(context);
        }
    }

    private void rearm(Context context, String reason) {
        if (!Prefs.isEnabled(context)) {
            return;
        }
        List<String> doorMacs = Prefs.getDoorMacs(context);
        if (doorMacs.isEmpty()) {
            return;
        }
        boolean started = DoorScanner.start(context, doorMacs);
        EventLog.log(context, started ? EventLog.Type.REARMED : EventLog.Type.REARM_FAILED,
                null, "reason=" + reason);
    }
}
