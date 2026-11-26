package com.shuttle.mobile.gps;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps the current or most recently ended shift per server/account, independently of telemetry uploads. */
public final class RouteRepository {
    private static final ExecutorService disk = Executors.newSingleThreadExecutor();
    private static RouteRepository current;
    private static Database database;

    public static synchronized RouteRepository open(Context context, String owner) {
        if (database == null) database = new Database(context.getApplicationContext());
        if (current == null || !owner.equals(current.owner)) current = new RouteRepository(owner);
        return current;
    }

    private final String owner;
    private final MutableLiveData<LiveMapUiState> updates = new MutableLiveData<>();
    // All reads and mutations of state happen on the same serial executor.
    private LiveMapUiState state = LiveMapUiState.EMPTY;

    private RouteRepository(String owner) {
        this.owner = owner;
        disk.execute(this::restore);
    }

    public LiveData<LiveMapUiState> updates() { return updates; }

    private void restore() {
        SQLiteDatabase db = database.getReadableDatabase();
        try (Cursor trip = db.rawQuery("SELECT id, shuttle, start, end, pending, lat, lon FROM trip WHERE owner=?",
                new String[]{owner})) {
            if (trip.moveToFirst()) {
                state = new LiveMapUiState(trip.getString(0), trip.getString(1), trip.getLong(2), 0, false, null,
                        trip.isNull(5) ? Double.NaN : trip.getDouble(5), trip.isNull(6) ? Double.NaN : trip.getDouble(6));
                try (Cursor c = db.rawQuery("SELECT lat, lon, time, speed, bearing FROM fix WHERE owner=? ORDER BY time",
                        new String[]{owner})) {
                    while (c.moveToNext()) state = state.append(c.getDouble(0), c.getDouble(1), c.getLong(2),
                            c.getFloat(3), c.isNull(4) ? Float.NaN : c.getFloat(4));
                }
                if (trip.getLong(3) > 0) state = state.endAt(trip.getLong(3), trip.getInt(4) != 0);
            }
        }
        updates.postValue(state);
    }

    public void begin(String id, String shuttle, long start, double terminalLat, double terminalLon) {
        if (id.isEmpty() || start <= 0) return;
        disk.execute(() -> {
            if (state.pendingUpload || (id.equals(state.shiftId) && start == state.start)) return;
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("fix", "owner=?", new String[]{owner});
                db.execSQL("INSERT OR REPLACE INTO trip(owner,id,shuttle,start,end,pending,lat,lon) VALUES (?,?,?,?,0,0,?,?)",
                        new Object[]{owner, id, shuttle, start, Double.isNaN(terminalLat) ? null : terminalLat,
                                Double.isNaN(terminalLon) ? null : terminalLon});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            state = new LiveMapUiState(id, shuttle, start, 0, false, null, terminalLat, terminalLon);
            updates.postValue(state);
        });
    }

    public void record(String id, double lat, double lon, long time, float speed, float bearing, Runnable accepted) {
        disk.execute(() -> {
            if (!id.equals(state.shiftId)) return;
            LiveMapUiState next = state.append(lat, lon, time, speed, bearing);
            if (next == state) return;
            ContentValues values = new ContentValues();
            values.put("owner", owner);
            values.put("lat", lat);
            values.put("lon", lon);
            values.put("time", time);
            values.put("speed", next.last.speed);
            if (Float.isFinite(bearing)) values.put("bearing", bearing);
            database.getWritableDatabase().insertOrThrow("fix", null, values);
            state = next;
            updates.postValue(state);
            accepted.run();
        });
    }

    /** End, including offline, or acknowledge an already persisted end after server confirmation. */
    public void finish(String id, long end, boolean pending) {
        disk.execute(() -> {
            if (!id.equals(state.shiftId) || (!state.active() && (!state.pendingUpload || pending))) return;
            LiveMapUiState next = state.endAt(state.active() ? end : state.end, pending);
            database.getWritableDatabase().execSQL("UPDATE trip SET end=?, pending=? WHERE owner=?",
                    new Object[]{next.end, pending ? 1 : 0, owner});
            state = next;
            updates.postValue(state);
        });
    }

    private static final class Database extends SQLiteOpenHelper {
        Database(Context context) {
            super(context, new File(context.getNoBackupFilesDir(), "routes.db").getPath(), null, 1);
        }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE trip(owner TEXT PRIMARY KEY, id TEXT, shuttle TEXT, start INTEGER, end INTEGER, pending INTEGER, lat REAL, lon REAL)");
            db.execSQL("CREATE TABLE fix(owner TEXT, lat REAL, lon REAL, time INTEGER, speed REAL, bearing REAL, PRIMARY KEY(owner,time))");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }
    }
}
