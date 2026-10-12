package dev.hoduj.iotpasslite

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fired by the Bluetooth stack (via the PendingIntent in DoorScanner) whenever a saved reader is seen.
 *  Every sighting keeps the overlay alive; the phone number is advertised once per visit. */
class DoorScanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.isEnabled(context)) return
        val phoneNumber = Prefs.getPhoneNumber(context)
        if (phoneNumber.isEmpty()) return
        val knownDoors = Prefs.getDoorMacs(context)
        val matched = firstKnownDoorMatch(intent, knownDoors) ?: return

        val matchedDoor = matched.device.address
        DoorPresence.markSeen(matchedDoor, matched.rssi)
        if (!DoorPresence.isNearby(matchedDoor)) return
        EventLog.touchPresence(context, matchedDoor)
        SendOverlay.show(context)

        if (!Prefs.shouldSend(matchedDoor, LEAVE_GAP_MS, RETRY_MS)) return

        val pendingResult = goAsync()
        val started = DoorOpener.send(
            context, phoneNumber, matchedDoor, "rssi=${matched.rssi}dBm", pendingResult::finish,
        )
        if (!started) pendingResult.finish()
    }

    /** The scan filters already restrict results, but a batched callback can carry several results;
     *  return the first one that is actually a saved door. */
    @Suppress("DEPRECATION")
    private fun firstKnownDoorMatch(intent: Intent, knownDoors: List<String>): ScanResult? {
        val results: ArrayList<ScanResult> =
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT) ?: return null
        return results.firstOrNull { result ->
            knownDoors.any { it.equals(result.device.address, ignoreCase = true) }
        }
    }

    private companion object {
        const val LEAVE_GAP_MS = 3_000L
        const val RETRY_MS = 3_000L
    }
}

/** Presence of each reader: when it was last seen and how strong it was. Weak sightings are ignored, so a
 *  reader only counts as nearby once the signal is strong enough (less sensitive than the raw scan). */
object DoorPresence {
    /** A reader counts as nearby only at or above this RSSI (dBm). */
    const val NEAR_RSSI_DBM = -85
    private const val NEARBY_WINDOW_MS = 10_000L

    private class Seen(val timeMillis: Long, val rssi: Int)

    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, Seen>()

    fun markSeen(doorMac: String, rssi: Int) {
        if (rssi < NEAR_RSSI_DBM) return
        lastSeen[doorMac.uppercase()] = Seen(System.currentTimeMillis(), rssi)
    }

    fun isNearby(doorMac: String, now: Long = System.currentTimeMillis()): Boolean {
        val seen = lastSeen[doorMac.uppercase()] ?: return false
        return now - seen.timeMillis < NEARBY_WINDOW_MS
    }

    /** Readers seen recently, strongest first, as (MAC, RSSI). */
    fun recent(now: Long = System.currentTimeMillis()): List<Pair<String, Int>> =
        lastSeen.entries
            .filter { now - it.value.timeMillis < NEARBY_WINDOW_MS }
            .map { it.key to it.value.rssi }
            .sortedByDescending { it.second }
}
