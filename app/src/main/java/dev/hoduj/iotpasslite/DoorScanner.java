package dev.hoduj.iotpasslite;

import android.Manifest;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.List;

/** Registers/unregisters the system-level (PendingIntent) BLE scan that watches for the door
 *  reader. Because this scan is offloaded to the Bluetooth controller and delivered via an
 *  explicit PendingIntent broadcast, it keeps running even after our process is killed, and
 *  survives app-switcher swipes without a foreground service. It does NOT survive a reboot, an
 *  app update, or Bluetooth being toggled off — RearmReceiver re-registers it for all three. */
final class DoorScanner {

    private static final String TAG = "IoTPassLite";
    private static final int REQUEST_CODE = 4200;

    private DoorScanner() {
    }

    static boolean start(Context context, List<String> doorMacs) {
        BluetoothLeScanner scanner = leScanner(context);
        if (scanner == null || doorMacs == null || doorMacs.isEmpty()) {
            return false;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            scanner.startScan(
                    DoorProtocol.scanFilters(doorMacs),
                    DoorProtocol.scanSettings(),
                    pendingIntent(context));
            Log.i(TAG, "scan registered for " + doorMacs);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "startScan denied", e);
            return false;
        }
    }

    static void stop(Context context) {
        BluetoothLeScanner scanner = leScanner(context);
        if (scanner == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            scanner.stopScan(pendingIntent(context));
            Log.i(TAG, "scan unregistered");
        } catch (SecurityException e) {
            Log.w(TAG, "stopScan denied", e);
        }
    }

    private static BluetoothLeScanner leScanner(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager == null) {
            return null;
        }
        BluetoothAdapter adapter = manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return null;
        }
        return adapter.getBluetoothLeScanner();
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, DoorScanReceiver.class);
        // Mutable is required on API 31+ so the Bluetooth stack can attach the scan-result
        // extras before firing this PendingIntent.
        return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }
}
