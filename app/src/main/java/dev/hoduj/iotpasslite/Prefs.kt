package dev.hoduj.iotpasslite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persisted state: the doors from the server list, the phone number used as the access key, whether the
 *  watcher runs, and the overlay style. Also keeps in-memory visit bookkeeping so one visit sends once. */
object Prefs {

    private const val FILE = "iotpass_lite"
    private const val KEY_DOORS = "doors_json"
    private const val KEY_PHONE_NUMBER = "phone_number"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_OVERLAY_STYLE = "overlay_style"

    private const val KEY_THEME = "theme"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_PHONE_MANUAL = "phone_manual"
    private const val KEY_ONBOARDED = "onboarded"

    const val OVERLAY_STYLE_TOP_ATTACHED = "top_attached"
    const val OVERLAY_STYLE_FLOATING = "floating"

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    const val LANG_SYSTEM = "system"
    const val LANG_EN = "en"
    const val LANG_KO = "ko"

    private val presence = HashMap<String, LongArray>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Doors from the server list, in list order. */
    fun getDoors(context: Context): List<ServerDoor> {
        val raw = prefs(context).getString(KEY_DOORS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            ServerDoor(
                mac = o.getString("mac"),
                name = o.getString("name"),
                group = o.getString("group"),
                elevator = o.getBoolean("elevator"),
            )
        }
    }

    fun setDoors(context: Context, doors: List<ServerDoor>) {
        val array = JSONArray()
        doors.forEach { d ->
            array.put(
                JSONObject()
                    .put("mac", d.mac)
                    .put("name", d.name)
                    .put("group", d.group)
                    .put("elevator", d.elevator)
            )
        }
        prefs(context).edit().putString(KEY_DOORS, array.toString()).apply()
    }

    fun getDoorMacs(context: Context): List<String> = getDoors(context).map { it.mac }

    fun getPhoneNumber(context: Context): String = prefs(context).getString(KEY_PHONE_NUMBER, "") ?: ""

    /** Saved as soon as it is typed, so it survives a restart without turning the watcher on. */
    fun setPhoneNumber(context: Context, phoneNumber: String) {
        prefs(context).edit().putString(KEY_PHONE_NUMBER, phoneNumber.trim()).apply()
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun save(context: Context, phoneNumber: String, enabled: Boolean) {
        prefs(context).edit()
            .putString(KEY_PHONE_NUMBER, phoneNumber)
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getOverlayStyle(context: Context): String =
        prefs(context).getString(KEY_OVERLAY_STYLE, OVERLAY_STYLE_TOP_ATTACHED) ?: OVERLAY_STYLE_TOP_ATTACHED

    fun setOverlayStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_OVERLAY_STYLE, style).apply()
    }

    fun isOverlayTopAttached(context: Context): Boolean =
        getOverlayStyle(context) == OVERLAY_STYLE_TOP_ATTACHED

    /** "system", "light" or "dark". */
    fun getTheme(context: Context): String = prefs(context).getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM

    fun setTheme(context: Context, theme: String) {
        prefs(context).edit().putString(KEY_THEME, theme).apply()
    }

    /** "system", "en" or "ko". "system" follows the device language. */
    fun getLanguage(context: Context): String = prefs(context).getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM

    fun setLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, language).apply()
    }

    /** True when the phone number was typed by hand in settings instead of read from the device. */
    fun isPhoneManual(context: Context): Boolean = prefs(context).getBoolean(KEY_PHONE_MANUAL, false)

    fun setPhoneManual(context: Context, manual: Boolean) {
        prefs(context).edit().putBoolean(KEY_PHONE_MANUAL, manual).apply()
    }

    fun isOnboarded(context: Context): Boolean = prefs(context).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    /** One send per visit: a visit ends once the door has not been seen for [leaveGapMs]. While still
     *  nearby, re-send at most once per [retryMs]. Memory only; a process restart costs at most one extra send. */
    @Synchronized
    fun shouldSend(doorMac: String, leaveGapMs: Long, retryMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val st = presence.getOrPut(doorMac) { longArrayOf(0L, 0L) } // {lastSeen, lastSent}
        val newVisit = now - st[0] >= leaveGapMs
        val retry = now - st[1] >= retryMs
        st[0] = now
        if (newVisit || retry) {
            st[1] = now
            return true
        }
        return false
    }
}
