package dev.hoduj.iotpasslite

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** On-device history, never sent anywhere. Two tables:
 *  - event_log: watcher start/stop, door-open signals, server requests.
 *  - presence:  spans during which a reader was detected (merged while sightings keep arriving).
 *  Everything older than [RETENTION_MS] is pruned. */
object EventLog {

    enum class Type {
        SCAN_ON, SCAN_OFF, DOOR_OPEN, ADVERTISE_FAIL, REARMED, REARM_FAILED, BLUETOOTH_OFF, BLUETOOTH_ON, SERVER
    }

    data class Entry(val timestampMillis: Long, val type: Type, val doorMac: String?, val detail: String?)

    /** A stretch during which one reader kept being detected. */
    data class PresenceSpan(val doorMac: String, val startMillis: Long, val endMillis: Long)

    const val RETENTION_MS = 8L * 24 * 60 * 60 * 1000
    private const val EVENT_TABLE = "event_log"
    private const val PRESENCE_TABLE = "presence"
    private const val COL_ID = "_id"
    private const val COL_TIME = "time_millis"
    private const val COL_TYPE = "type"
    private const val COL_DOOR_MAC = "door_mac"
    private const val COL_DETAIL = "detail"
    private const val COL_START = "start_millis"
    private const val COL_END = "end_millis"
    private const val PRUNE_EVERY = 50

    /** Sightings closer together than this belong to the same presence span. */
    private const val PRESENCE_MERGE_MS = 60_000L

    private var insertsSincePrune = 0
    private var helper: Helper? = null

    private fun db(context: Context): Helper = helper ?: synchronized(this) {
        helper ?: Helper(context.applicationContext).also { helper = it }
    }

    @Synchronized
    fun log(context: Context, type: Type, doorMac: String?, detail: String?) {
        val database = db(context).writableDatabase
        val values = ContentValues().apply {
            put(COL_TIME, System.currentTimeMillis())
            put(COL_TYPE, type.name)
            put(COL_DOOR_MAC, doorMac)
            put(COL_DETAIL, detail)
        }
        database.insert(EVENT_TABLE, null, values)
        maybePrune(database)
    }

    /** Extends the open presence span for [doorMac], or starts a new one after a quiet gap. */
    @Synchronized
    fun touchPresence(context: Context, doorMac: String, now: Long = System.currentTimeMillis()) {
        val database = db(context).writableDatabase
        val mac = doorMac.uppercase()
        val open = database.query(
            PRESENCE_TABLE, arrayOf(COL_ID), "$COL_DOOR_MAC = ? AND $COL_END >= ?",
            arrayOf(mac, (now - PRESENCE_MERGE_MS).toString()), null, null, "$COL_END DESC", "1",
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (open != null) {
            database.execSQL("UPDATE $PRESENCE_TABLE SET $COL_END = ? WHERE $COL_ID = ?", arrayOf(now, open))
        } else {
            val values = ContentValues().apply {
                put(COL_DOOR_MAC, mac)
                put(COL_START, now)
                put(COL_END, now)
            }
            database.insert(PRESENCE_TABLE, null, values)
        }
        maybePrune(database)
    }

    /** Events since [sinceMillis], newest first. */
    fun events(context: Context, sinceMillis: Long = 0L): List<Entry> {
        val out = ArrayList<Entry>()
        db(context).readableDatabase.query(
            EVENT_TABLE, null, "$COL_TIME >= ?", arrayOf(sinceMillis.toString()), null, null, "$COL_ID DESC",
        ).use { c ->
            while (c.moveToNext()) {
                val type = runCatching { Type.valueOf(c.getString(c.getColumnIndexOrThrow(COL_TYPE))) }
                    .getOrNull() ?: continue
                out += Entry(
                    timestampMillis = c.getLong(c.getColumnIndexOrThrow(COL_TIME)),
                    type = type,
                    doorMac = c.getString(c.getColumnIndexOrThrow(COL_DOOR_MAC)),
                    detail = c.getString(c.getColumnIndexOrThrow(COL_DETAIL)),
                )
            }
        }
        return out
    }

    /** Presence spans that overlap [sinceMillis] onward, oldest first. */
    fun presence(context: Context, sinceMillis: Long): List<PresenceSpan> {
        val out = ArrayList<PresenceSpan>()
        db(context).readableDatabase.query(
            PRESENCE_TABLE, null, "$COL_END >= ?", arrayOf(sinceMillis.toString()), null, null, "$COL_START ASC",
        ).use { c ->
            while (c.moveToNext()) {
                out += PresenceSpan(
                    doorMac = c.getString(c.getColumnIndexOrThrow(COL_DOOR_MAC)),
                    startMillis = c.getLong(c.getColumnIndexOrThrow(COL_START)),
                    endMillis = c.getLong(c.getColumnIndexOrThrow(COL_END)),
                )
            }
        }
        return out
    }

    fun clear(context: Context) {
        val database = db(context).writableDatabase
        database.execSQL("DELETE FROM $EVENT_TABLE")
        database.execSQL("DELETE FROM $PRESENCE_TABLE")
    }

    private fun maybePrune(database: SQLiteDatabase) {
        if (++insertsSincePrune < PRUNE_EVERY) return
        insertsSincePrune = 0
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        database.execSQL("DELETE FROM $EVENT_TABLE WHERE $COL_TIME < ?", arrayOf(cutoff))
        database.execSQL("DELETE FROM $PRESENCE_TABLE WHERE $COL_END < ?", arrayOf(cutoff))
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "event_log.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            createEventTable(db)
            createPresenceTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("DROP TABLE IF EXISTS $EVENT_TABLE")
                createEventTable(db)
            }
            if (oldVersion < 3) {
                db.execSQL("DROP TABLE IF EXISTS $PRESENCE_TABLE")
                createPresenceTable(db)
            }
        }

        private fun createEventTable(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $EVENT_TABLE (" +
                    "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COL_TIME INTEGER NOT NULL, " +
                    "$COL_TYPE TEXT NOT NULL, " +
                    "$COL_DOOR_MAC TEXT, " +
                    "$COL_DETAIL TEXT)"
            )
        }

        private fun createPresenceTable(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $PRESENCE_TABLE (" +
                    "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COL_DOOR_MAC TEXT NOT NULL, " +
                    "$COL_START INTEGER NOT NULL, " +
                    "$COL_END INTEGER NOT NULL)"
            )
        }
    }
}
