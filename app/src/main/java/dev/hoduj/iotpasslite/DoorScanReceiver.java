package dev.hoduj.iotpasslite;

import android.Manifest;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/** Fired by the Bluetooth stack (via the PendingIntent registered in DoorScanner) whenever the
 *  configured door reader is seen. Advertises the saved phone number for ~1 second, which is
 *  the entire "open the door" handshake — see DoorProtocol for how that was reverse engineered. */
public class DoorScanReceiver extends BroadcastReceiver {

    private static final String TAG = "IoTPassLite";
    private static final long ADVERTISE_DURATION_MS = 1000L;
    /** A visit ends once the door has not been seen for this long. */
    private static final long LEAVE_GAP_MS = 15_000L;
    /** While still nearby, send again at most this often (in case the first try failed). */
    private static final long RETRY_MS = 60_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.isEnabled(context)) {
            return;
        }
        String phoneNumber = Prefs.getPhoneNumber(context);
        if (phoneNumber.isEmpty()) {
            return;
        }
        List<String> knownDoors = Prefs.getDoorMacs(context);
        ScanResult matched = firstKnownDoorMatch(intent, knownDoors);
        if (matched == null) {
            return;
        }
        String matchedDoor = matched.getDevice().getAddress();
        if (!Prefs.shouldSend(matchedDoor, LEAVE_GAP_MS, RETRY_MS)) {
            return;
        }

        PendingResult pendingResult = goAsync();
        boolean started = advertise(context, phoneNumber, matchedDoor, matched.getRssi(), pendingResult);
        if (!started) {
            pendingResult.finish();
        }
    }

    /** The scan filters already restrict results to configured doors, but a batched callback can
     *  report more than one result at once (e.g. two readers seen in the same cycle) — this
     *  double-checks against the saved list and returns the first one that's actually ours. */
    @SuppressWarnings("deprecation") // getParcelableArrayListExtra(String) is fine pre-API 33;
    // minSdk 31 means we can't unconditionally use the typed overload yet.
    private ScanResult firstKnownDoorMatch(Intent intent, List<String> knownDoors) {
        ArrayList<ScanResult> results = intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT);
        if (results == null) {
            return null;
        }
        for (ScanResult result : results) {
            String address = result.getDevice().getAddress();
            for (String known : knownDoors) {
                if (known.equalsIgnoreCase(address)) {
                    return result;
                }
            }
        }
        return null;
    }

    private boolean advertise(Context context, String phoneNumber, String doorMac, int rssi,
                              PendingResult pendingResult) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager == null || manager.getAdapter() == null) {
            return false;
        }
        BluetoothLeAdvertiser advertiser = manager.getAdapter().getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.w(TAG, "device does not support BLE advertising");
            return false;
        }

        AdvertiseCallback callback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                Log.i(TAG, "advertising phone number to door reader");
                EventLog.log(context, EventLog.Type.DOOR_OPEN, doorMac, "rssi=" + rssi + "dBm");
            }

            @Override
            public void onStartFailure(int errorCode) {
                Log.w(TAG, "advertise failed, error " + errorCode);
                EventLog.log(context, EventLog.Type.ADVERTISE_FAIL, doorMac, "error=" + errorCode);
            }
        };

        try {
            advertiser.startAdvertising(
                    DoorProtocol.advertiseSettings(),
                    DoorProtocol.advertiseData(phoneNumber),
                    callback);
        } catch (SecurityException e) {
            Log.w(TAG, "startAdvertising denied", e);
            EventLog.log(context, EventLog.Type.ADVERTISE_FAIL, doorMac, "permission denied");
            return false;
        }

        SendOverlay.show(context);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                advertiser.stopAdvertising(callback);
            } catch (SecurityException e) {
                Log.w(TAG, "stopAdvertising denied", e);
            } finally {
                pendingResult.finish();
            }
        }, ADVERTISE_DURATION_MS);
        return true;
    }
}
