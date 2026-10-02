package dev.hoduj.iotpasslite;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** All persisted state lives here: the doors to watch for, the phone number to advertise as the
 *  access key, and whether the watcher should be running; also tracks (in memory) whether a
 *  door was already handled this visit so we don't re-advertise on every scan hit. Doors are a list, not a
 *  single hardcoded value, since this account may be authorized for more than one reader. */
final class Prefs {

    private static final String FILE = "iotpass_lite";
    private static final String KEY_DOOR_MACS = "door_macs";
    private static final String KEY_PHONE_NUMBER = "phone_number";
    private static final String KEY_ENABLED = "enabled";

    private Prefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static List<String> getDoorMacs(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_DOOR_MACS, null);
        return stored == null ? new ArrayList<>() : new ArrayList<>(stored);
    }

    /** One MAC address per line, for the settings screen's multi-line text field. */
    static String getDoorMacsAsText(Context context) {
        return String.join("\n", getDoorMacs(context));
    }

    /** Parses one-MAC-per-line text (blank lines ignored, duplicates collapsed, case-normalized)
     *  into a door list. */
    static List<String> parseDoorMacsText(String text) {
        Set<String> macs = new LinkedHashSet<>();
        for (String line : text.split("\n")) {
            String mac = line.trim().toUpperCase();
            if (!mac.isEmpty()) {
                macs.add(mac);
            }
        }
        return new ArrayList<>(macs);
    }

    static String getPhoneNumber(Context context) {
        return prefs(context).getString(KEY_PHONE_NUMBER, "");
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static void save(Context context, List<String> doorMacs, String phoneNumber, boolean enabled) {
        prefs(context).edit()
                .putStringSet(KEY_DOOR_MACS, new LinkedHashSet<>(doorMacs))
                .putString(KEY_PHONE_NUMBER, phoneNumber)
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    private static final java.util.Map<String, long[]> PRESENCE = new java.util.HashMap<>();

    /** Decides whether a sighting of this door should trigger a send. One send per "visit":
     *  a visit ends once the door hasn't been seen for leaveGapMs; while still nearby, it
     *  re-sends at most once per retryMs (in case the first try didn't open the door).
     *  Kept in memory only (a scan hit can arrive many times a second); if the process dies,
     *  the worst case is one extra send. */
    static synchronized boolean shouldSend(String doorMac, long leaveGapMs, long retryMs) {
        long now = System.currentTimeMillis();
        long[] st = PRESENCE.get(doorMac); // {lastSeen, lastSent}
        if (st == null) {
            st = new long[]{0L, 0L};
            PRESENCE.put(doorMac, st);
        }
        boolean newVisit = now - st[0] >= leaveGapMs;
        boolean retry = now - st[1] >= retryMs;
        st[0] = now;
        if (newVisit || retry) {
            st[1] = now;
            return true;
        }
        return false;
    }
}
