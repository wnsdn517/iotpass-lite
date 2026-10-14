package dev.hoduj.iotpasslite

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Local BLE handshake with the door reader, recovered from the official app (v1.4.2):
 *  the reader advertises READER_SERVICE_UUID; the phone answers by advertising PHONE_SERVICE_UUID with
 *  the phone number as manufacturer data under 0x00E0. No server is involved. */
object DoorProtocol {

    /** Service UUID the door reader advertises. Used as the scan filter. */
    val READER_SERVICE_UUID: UUID = UUID.fromString("0003CDD0-0000-1000-8000-00805f9b0133")

    val PHONE_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("736F7320-0000-1000-8000-00805F9B34FB")
    const val MANUFACTURER_ID = 0x00E0
    private const val OPEN_FLAG: Byte = 0x31

    fun scanFilters(doorMacs: List<String>): List<ScanFilter> = doorMacs.map { mac ->
        ScanFilter.Builder()
            .setDeviceAddress(mac)
            .setServiceUuid(ParcelUuid(READER_SERVICE_UUID))
            .build()
    }

    /** Any IoTPass reader in range (for the nearby-scan screen). A public advertisement, not a credential. */
    fun discoveryScanFilters(): List<ScanFilter> = listOf(
        ScanFilter.Builder().setServiceUuid(ParcelUuid(READER_SERVICE_UUID)).build()
    )

    /** Background watcher: lowest duty cycle, a few seconds of latency is fine. */
    fun scanSettings(): ScanSettings =
        ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()

    /** Short, user-initiated discovery screen: favour responsiveness. */
    fun foregroundDiscoverySettings(): ScanSettings =
        ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

    /** The reader sets its open flag ('1', 0x31) in the manufacturer data while the door is open: byte 8 of the
     *  long form, or byte 2 of the 3-byte form. Matches the official app's check. */
    fun isOpenSignal(record: ScanRecord?): Boolean {
        val sparse = record?.manufacturerSpecificData ?: return false
        for (i in 0 until sparse.size()) {
            val bytes = sparse.valueAt(i) ?: continue
            if (bytes.size >= 9 && bytes[8] == OPEN_FLAG) return true
            if (bytes.size == 3 && bytes[2] == OPEN_FLAG) return true
        }
        return false
    }

    fun advertiseSettings(): AdvertiseSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(false)
        .setTimeout(0)
        .build()

    fun advertiseData(phoneNumber: String): AdvertiseData = AdvertiseData.Builder()
        .addServiceUuid(PHONE_SERVICE_UUID)
        .setIncludeDeviceName(false)
        .addManufacturerData(MANUFACTURER_ID, phoneNumber.toByteArray(StandardCharsets.UTF_8))
        .build()
}
