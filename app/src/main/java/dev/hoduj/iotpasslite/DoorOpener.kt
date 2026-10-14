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

    /** Phone numbers are personal; the RAW log keeps only the last four digits. */
    private fun maskPhone(phone: String): String =
        if (phone.length <= 4) "****" else "***" + phone.takeLast(4)

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
                EventLog.raw(context, EventLog.Category.BLE, "advertise started mac=$doorMac $settingsInEffect")
                // Advertising is not proof the door opened; only a confirmed server reply is logged as DOOR_OPEN.
                EventLog.log(context, EventLog.Type.ADVERTISED, doorMac, detail)
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertise failed, error $errorCode")
                EventLog.raw(context, EventLog.Category.BLE, "advertise failed mac=$doorMac error=$errorCode")
                EventLog.log(context, EventLog.Type.ADVERTISE_FAIL, doorMac, "error=$errorCode")
            }
        }

        val payload = DoorProtocol.advertiseData(phoneNumber)
        EventLog.raw(
            context, EventLog.Category.BLE,
            "advertise request mac=$doorMac uuid=${DoorProtocol.PHONE_SERVICE_UUID} " +
                "mfr=0x%04X phone=${maskPhone(phoneNumber)}".format(DoorProtocol.MANUFACTURER_ID),
        )
        try {
            advertiser.startAdvertising(DoorProtocol.advertiseSettings(), payload, callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "startAdvertising denied", e)
            EventLog.raw(context, EventLog.Category.BLE, "advertise denied mac=$doorMac")
            EventLog.log(context, EventLog.Type.ADVERTISE_FAIL, doorMac, "permission denied")
            return false
        }

        SendOverlay.show(context)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                advertiser.stopAdvertising(callback)
                EventLog.raw(context, EventLog.Category.BLE, "advertise stopped mac=$doorMac")
            } catch (e: SecurityException) {
                Log.w(TAG, "stopAdvertising denied", e)
            } finally {
                onFinished()
            }
        }, ADVERTISE_DURATION_MS)
        return true
    }
}
