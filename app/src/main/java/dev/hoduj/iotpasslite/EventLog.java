package dev.hoduj.iotpasslite;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** A local, on-device record of every step the watcher takes — from the moment a reader is first
 *  seen, through advertising the phone number, to whether it actually succeeded. Nothing here
 *  ever leaves the phone; it exists purely so you can open the app after walking through a door
 *  and see exactly what happened (or didn't), instead of just trusting a silent background
 *  process. Capped at MAX_ROWS so it can't grow forever. */
final class EventLog {

    enum Type {
        SCAN_ON,
        SCAN_OFF,
        DOOR_OPEN,
        ADVERTISE_FAIL,
        REARMED,
        REARM_FAILED,
        BLUETOOTH_OFF,
        BLUETOOTH_ON
    }

    static final class Entry {
        final long timestampMillis;
        final Type type;
        final String doorMac;
        final String detail;

        Entry(long timestampMillis, Type type, String doorMac, String detail) {
            this.timestampMillis = timestampMillis;
            this.type = type;
            this.doorMac = doorMac;
            this.detail = detail;
        }
    }

    private static final int MAX_ROWS = 200;
    private static final int PRUNE_EVERY = 20;
    private static int insertsSincePrune;

    private EventLog() {
    }

    static synchronized void log(Context context, Type type, String doorMac, String detail) {
        SQLiteDatabase db = Db.get(context).getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(Db.COL_TIME, System.currentTimeMillis());
        values.put(Db.COL_TYPE, type.name());
        values.put(Db.COL_DOOR_MAC, doorMac);
        values.put(Db.COL_DETAIL, detail);
        db.insert(Db.TABLE, null, values);
        if (++insertsSincePrune < PRUNE_EVERY) {
            return;
        }
        insertsSincePrune = 0;
        db.execSQL("DELETE FROM " + Db.TABLE + " WHERE " + Db.COL_ID + " NOT IN "
                + "(SELECT " + Db.COL_ID + " FROM " + Db.TABLE
                + " ORDER BY " + Db.COL_ID + " DESC LIMIT " + MAX_ROWS + ")");
    }

    static List<Entry> recent(Context context) {
        List<Entry> entries = new ArrayList<>();
        SQLiteDatabase db = Db.get(context).getReadableDatabase();
        try (Cursor cursor = db.query(Db.TABLE, null, null, null, null, null,
                Db.COL_ID + " DESC")) {
            while (cursor.moveToNext()) {
                Type type;
                try {
                    type = Type.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(Db.COL_TYPE)));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                entries.add(new Entry(
                        cursor.getLong(cursor.getColumnIndexOrThrow(Db.COL_TIME)),
                        type,
                        cursor.getString(cursor.getColumnIndexOrThrow(Db.COL_DOOR_MAC)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Db.COL_DETAIL))));
            }
        }
        return entries;
    }

    static void clear(Context context) {
        Db.get(context).getWritableDatabase().execSQL("DELETE FROM " + Db.TABLE);
    }

    private static final class Db extends SQLiteOpenHelper {
        static final String TABLE = "event_log";
        static final String COL_ID = "_id";
        static final String COL_TIME = "time_millis";
        static final String COL_TYPE = "type";
        static final String COL_DOOR_MAC = "door_mac";
        static final String COL_DETAIL = "detail";

        private static Db instance;

        static synchronized Db get(Context context) {
            if (instance == null) {
                instance = new Db(context.getApplicationContext());
            }
            return instance;
        }

        private Db(Context context) {
            super(context, "event_log.db", null, 2);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " ("
                    + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_TIME + " INTEGER NOT NULL, "
                    + COL_TYPE + " TEXT NOT NULL, "
                    + COL_DOOR_MAC + " TEXT, "
                    + COL_DETAIL + " TEXT)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE);
            onCreate(db);
        }
    }
}
