package dev.hoduj.iotpasslite;

import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The BLE handshake the building's door readers actually speak, recovered by decompiling the
 *  original IoTPass V2 app (dev.iotpass.cnslink.kr.co.iotpass v1.4.2):
 *
 *  1. The reader continuously advertises a 128-bit service UUID identifying it as an IoTPass
 *     door (READER_SERVICE_UUID below).
 *  2. When a phone wants in, it advertises its OWN service UUID (PHONE_SERVICE_UUID) with the
 *     phone number as manufacturer data under manufacturer ID 0x00E0. The phone number is the
 *     entire access credential — there is no challenge/response and nothing is encrypted over
 *     the air.
 *  3. The reader recognizes that phone number (it was registered with the building's server
 *     ahead of time, out of band) and unlocks the door.
 *
 *  None of this touches a server: it is a pure local BLE exchange between phone and reader. */
final class DoorProtocol {

    /** Service UUID the door reader advertises. Used as our scan filter. */
    static final UUID READER_SERVICE_UUID = UUID.fromString("0003CDD0-0000-1000-8000-00805f9b0133");

    /** Service UUID the phone advertises back to identify itself as an IoTPass credential. */
    private static final ParcelUuid PHONE_SERVICE_UUID =
            ParcelUuid.fromString("736F7320-0000-1000-8000-00805F9B34FB");

    /** Manufacturer ID (0x00E0) the reader expects the phone number under. */
    private static final int MANUFACTURER_ID = 0x00E0;

    private DoorProtocol() {
    }

    static List<ScanFilter> scanFilters(List<String> doorMacAddresses) {
        List<ScanFilter> filters = new ArrayList<>(doorMacAddresses.size());
        for (String mac : doorMacAddresses) {
            filters.add(new ScanFilter.Builder()
                    .setDeviceAddress(mac)
                    .setServiceUuid(new ParcelUuid(READER_SERVICE_UUID))
                    .build());
        }
        return filters;
    }

    /** For the "nearby readers" discovery screen: any IoTPass reader in range, not just ones
     *  already saved. This is the same service UUID any BLE scanner app could see — it's a
     *  public advertisement, not a credential. */
    static List<ScanFilter> discoveryScanFilters() {
        return java.util.Collections.singletonList(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(READER_SERVICE_UUID))
                .build());
    }

    /** The always-on background watcher. LOW_POWER has the lowest controller duty cycle; the
     *  reader may be noticed a few seconds later, which is fine for something running 24/7. */
    static ScanSettings scanSettings() {
        return new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build();
    }

    /** The "주변 출입문 스캔" discovery screen only runs for the few seconds it's on screen, so
     *  it's worth spending LOW_LATENCY there for a snappier result — unlike scanSettings() above,
     *  this cost is bounded and user-initiated. */
    static ScanSettings foregroundDiscoverySettings() {
        return new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
    }

    static AdvertiseSettings advertiseSettings() {
        return new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(0)
                .build();
    }

    static AdvertiseData advertiseData(String phoneNumber) {
        byte[] phoneBytes = phoneNumber.getBytes(StandardCharsets.UTF_8);
        return new AdvertiseData.Builder()
                .addServiceUuid(PHONE_SERVICE_UUID)
                .setIncludeDeviceName(false)
                .addManufacturerData(MANUFACTURER_ID, phoneBytes)
                .build();
    }
}
