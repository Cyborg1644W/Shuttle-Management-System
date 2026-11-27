package com.shuttle.mobile.ui;

import com.shuttle.mobile.gps.LiveMapUiState;
import com.shuttle.common.GpsCoordinate;
import com.shuttle.common.ValidationUtil;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** One fleet snapshot drives the operator's map, inspector and list. All keys are shift IDs. */
public final class FleetStatusMapUiState {
    enum Camera { OVERVIEW, FOLLOW, MANUAL }
    enum Connection { LOADING, ONLINE, OFFLINE, RECONNECTING, ERROR }
    enum Signal { WAITING, MOVING, IDLE, AT_TERMINAL, STALE }

    final Map<String, Unit> units;
    final String terminalId, terminal, selected;
    final double terminalLat, terminalLon;
    final boolean loaded;
    final Totals totals;
    final String error;

    static final class Totals {
        final int units, operating, available, onDuty;
        Totals(int units, int operating, int available, int onDuty) {
            this.onDuty = onDuty;
            this.units = units;
            this.operating = operating;
            this.available = available;
        }
    }
    final Camera camera;
    final Connection connection;

    FleetStatusMapUiState() {
        this(Collections.emptyMap(), "", "", Double.NaN, Double.NaN, false, null, Camera.OVERVIEW, Connection.LOADING, null, "");
    }
    private FleetStatusMapUiState(Map<String, Unit> units, String terminalId, String terminal, double lat,
                                  double lon, boolean loaded, String selected, Camera camera, Connection connection, Totals totals, String error) {
        this.totals = totals;
        this.error = error;
        this.units = units; this.terminalId = terminalId; this.terminal = terminal;
        this.terminalLat = lat; this.terminalLon = lon; this.loaded = loaded;
        this.selected = selected; this.camera = camera; this.connection = connection;
    }
    FleetStatusMapUiState snapshot(Map<String, Unit> units, String scope, String name, double lat, double lon, Totals totals) {
        boolean same = scope.equals(terminalId);
        String focus = same && selected != null && units.containsKey(selected) ? selected : null;
        Camera next = (loaded && !same) || (camera == Camera.FOLLOW && focus == null) ? Camera.OVERVIEW : camera;
        return new FleetStatusMapUiState(units == this.units ? units : Collections.unmodifiableMap(units), scope, name, lat, lon,
                true, focus, next, Connection.ONLINE, totals, "");
    }
    FleetStatusMapUiState camera(Camera mode, String id) {
        if (id != null && !units.containsKey(id)) return this;
        return new FleetStatusMapUiState(units, terminalId, terminal, terminalLat, terminalLon, loaded,
                id, mode, connection, totals, error);
    }
    FleetStatusMapUiState connection(Connection next) {
        return connection(next, "");
    }
    FleetStatusMapUiState connection(Connection next, String error) {
        return new FleetStatusMapUiState(units, terminalId, terminal, terminalLat, terminalLon, loaded,
                selected, camera, next, totals, error);
    }
    Signal signal(Unit unit, long now) {
        Signal signal = unit.signal(now);
        if (signal != Signal.IDLE || !LiveMapUiState.valid(terminalLat, terminalLon)) return signal;
        GpsCoordinate here = new GpsCoordinate(unit.last.lat, unit.last.lon, unit.last.time);
        return here.distanceTo(new GpsCoordinate(terminalLat, terminalLon, unit.last.time)) <= 50
                ? Signal.AT_TERMINAL : Signal.IDLE;
    }

    static final class Unit {
        final String shift, id, plate, driver, terminal;
        final int color;
        final Point last;
        final double speed, bearing;
        Unit(String shift, String id, String plate, String driver, String terminal, int color,
             Point last, double speed, double bearing) {
            this.shift = shift; this.id = id; this.plate = plate; this.driver = driver; this.terminal = terminal;
            this.color = color; this.last = last; this.speed = speed; this.bearing = bearing;
        }
        Signal signal(long now) {
            if (last == null) return Signal.WAITING;
            if (now - last.time >= LiveMapUiState.STALE_MS) return Signal.STALE;
            return speed >= 3 ? Signal.MOVING : Signal.IDLE;
        }
    }
    static final class Point {
        final Point previous;
        final double lat, lon;
        final long time;
        private Point(Point previous, long time, double lat, double lon) {
            this.previous = previous; this.time = time; this.lat = lat; this.lon = lon;
        }
        static Point append(Point last, long time, double lat, double lon) {
            if (!ValidationUtil.isTrackingFix(lat, lon) || time <= 0 || (last != null && time <= last.time)) return last;
            return new Point(last, time, lat, lon);
        }
        static Point appendTrail(Point last, String trail) {
            if (trail == null || trail.isEmpty()) return last;
            for (String sample : trail.split(";")) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                String[] fields = sample.split(",");
                if (fields.length != 3) continue;
                try {
                    last = append(last, Long.parseLong(fields[0]), Double.parseDouble(fields[1]),
                            Double.parseDouble(fields[2]));
                } catch (NumberFormatException ignored) {
                    // An invalid sample must not hide later valid fixes or other vehicles.
                }
            }
            return last;
        }

        ArrayDeque<Point> after(Point rendered) {
            ArrayDeque<Point> delta = new ArrayDeque<>();
            for (Point p = this; p != rendered && p != null; p = p.previous) delta.addFirst(p);
            return delta;
        }
    }

    private static final int[] PALETTE = {0xff00788f, 0xff9a4811, 0xff64459b, 0xff247140,
            0xffb22860, 0xff275baa, 0xff786000, 0xff983b29, 0xff4d5d80, 0xff006754, 0xff83507e, 0xff574f38};
    static int assignColor(String id, Set<Integer> used) {
        int start = Math.floorMod(id.hashCode(), PALETTE.length);
        for (int i = 0; ; i++) {
            int base = PALETTE[(start + i) % PALETTE.length];
            // Additional shades when every base color is already visible.
            int shade = (base & 0xffffff) ^ ((i / PALETTE.length * 0x110d07) & 0x3f3f3f);
            int color = 0xff000000 | shade;
            if (!used.contains(color)) return color;
        }
    }
}
