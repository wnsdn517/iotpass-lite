package dev.hoduj.iotpasslite

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Registers/unregisters the system-level (PendingIntent) BLE scan for the door readers. The scan runs in
 *  the Bluetooth controller and survives process death; RearmReceiver re-registers it after reboot,
 *  app update, or Bluetooth toggle. */
object DoorScanner {

    private const val TAG = "IoTPassLite"
    private const val REQUEST_CODE = 4200

    fun start(context: Context, doorMacs: List<String>): Boolean {
        val scanner = leScanner(context) ?: return false
        if (doorMacs.isEmpty() || !hasScanPermission(context)) return false
        return try {
            scanner.startScan(DoorProtocol.scanFilters(doorMacs), DoorProtocol.scanSettings(), pendingIntent(context))
            Log.i(TAG, "scan registered for $doorMacs")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "startScan denied", e)
            false
        }
    }

    fun stop(context: Context) {
        val scanner = leScanner(context) ?: return
        if (!hasScanPermission(context)) return
        try {
            scanner.stopScan(pendingIntent(context))
            Log.i(TAG, "scan unregistered")
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan denied", e)
        }
    }

    private fun hasScanPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun leScanner(context: Context): BluetoothLeScanner? {
        val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return if (adapter.isEnabled) adapter.bluetoothLeScanner else null
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DoorScanReceiver::class.java)
        // Mutable is required on API 31+ so the Bluetooth stack can attach the scan-result extras.
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}
