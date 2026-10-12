package dev.hoduj.iotpasslite

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/** Advertises the phone number once for ~1 s: the local BLE "open" handshake. Used by DoorScanReceiver on a
 *  sighting and by the manual 문 열기 button. */
object DoorOpener {

    private const val TAG = "IoTPassLite"
    private const val ADVERTISE_DURATION_MS = 1000L

    /** Returns false (and does not call [onFinished]) if advertising could not start at all. */
    fun send(
        context: Context,
        phoneNumber: String,
        doorMac: String,
        detail: String,
        onFinished: () -> Unit,
    ): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "device does not support BLE advertising")
            return false
        }

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "advertising phone number to door reader")
                EventLog.log(context, EventLog.Type.DOOR_OPEN, doorMac, detail)
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertise failed, error $errorCode")
                EventLog.log(context, EventLog.Type.ADVERTISE_FAIL, doorMac, "error=$errorCode")
            }
        }

        try {
            advertiser.startAdvertising(
                DoorProtocol.advertiseSettings(),
                DoorProtocol.advertiseData(phoneNumber),
                callback,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "startAdvertising denied", e)
            EventLog.log(context, EventLog.Type.ADVERTISE_FAIL, doorMac, "permission denied")
            return false
        }

        SendOverlay.show(context)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                advertiser.stopAdvertising(callback)
            } catch (e: SecurityException) {
                Log.w(TAG, "stopAdvertising denied", e)
            } finally {
                onFinished()
            }
        }, ADVERTISE_DURATION_MS)
        return true
    }
}
