package com.shuttle.mobile.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// TEMPORARY: saves shifts on this phone only.
// Replace with the backend (ShiftRepository) when it's connected.
public class ShiftStore {

    private static final String PREFS = "fleetflow_shifts";
    private static final String KEY_LIST = "list";
    private static final String KEY_ACTIVE = "active";

    public static class Entry {
        public long start;
        public long end;
        public double distanceKm;
        public int stops;
        public String vehicle;
        public String terminal;
        public String route;

        public Entry(long start, long end, double distanceKm, int stops,
                     String vehicle, String terminal, String route) {
            this.start = start;
            this.end = end;
            this.distanceKm = distanceKm;
            this.stops = stops;
            this.vehicle = vehicle;
            this.terminal = terminal;
            this.route = route;
        }

        public long durationMs() {
            return end - start;
        }

        public double avgSpeedKmh() {
            double hours = durationMs() / 3600000.0;
            return hours > 0 ? distanceKm / hours : 0;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- Active shift (0 = no shift running) ----------

    public static long getActiveStart(Context context) {
        return prefs(context).getLong(KEY_ACTIVE, 0);
    }

    public static void setActiveStart(Context context, long start) {
        prefs(context).edit().putLong(KEY_ACTIVE, start).apply();
    }

    // ---------- Finished shifts (oldest first) ----------

    public static ArrayList<Entry> load(Context context) {
        ArrayList<Entry> list = new ArrayList<>();
        String json = prefs(context).getString(KEY_LIST, null);
        if (json == null) {
            return list;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                list.add(new Entry(
                        o.getLong("start"),
                        o.getLong("end"),
                        o.getDouble("distanceKm"),
                        o.getInt("stops"),
                        o.getString("vehicle"),
                        o.getString("terminal"),
                        o.getString("route")));
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return list;
    }

    public static void add(Context context, Entry entry) {
        ArrayList<Entry> list = load(context);
        list.add(entry);
        while (list.size() > 50) {
            list.remove(0);
        }

        JSONArray array = new JSONArray();
        try {
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("start", e.start);
                o.put("end", e.end);
                o.put("distanceKm", e.distanceKm);
                o.put("stops", e.stops);
                o.put("vehicle", e.vehicle);
                o.put("terminal", e.terminal);
                o.put("route", e.route);
                array.put(o);
            }
        } catch (Exception ex) {
            return;
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply();
    }

    public static Entry find(Context context, long start) {
        for (Entry e : load(context)) {
            if (e.start == start) {
                return e;
            }
        }
        return null;
    }

    // ---------- Helpers ----------

    public static String routeToString(List<double[]> points) {
        StringBuilder sb = new StringBuilder();
        for (double[] p : points) {
            if (sb.length() > 0) {
                sb.append(";");
            }
            sb.append(String.format(Locale.US, "%.5f,%.5f", p[0], p[1]));
        }
        return sb.toString();
    }

    public static ArrayList<double[]> parseRoute(String route) {
        ArrayList<double[]> points = new ArrayList<>();
        if (route == null || route.isEmpty()) {
            return points;
        }
        for (String part : route.split(";")) {
            String[] xy = part.split(",");
            if (xy.length == 2) {
                try {
                    points.add(new double[]{Double.parseDouble(xy[0]), Double.parseDouble(xy[1])});
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return points;
    }

    public static String formatDuration(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }
}