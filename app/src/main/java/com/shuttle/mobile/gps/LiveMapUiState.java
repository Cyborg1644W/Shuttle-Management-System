package com.shuttle.mobile.gps;

import com.shuttle.common.GpsCoordinate;
import com.shuttle.common.ValidationUtil;

import java.util.ArrayDeque;
import java.util.Deque;

/** Immutable snapshots shared by the GPS service and the driver screen. */
public final class LiveMapUiState {
    public enum Phase { PRE_SHIFT, WAITING_FOR_GPS, TRACKING, WEAK_SIGNAL, ENDED }
    public static final long STALE_MS = 30_000;
    public static final LiveMapUiState EMPTY = new LiveMapUiState("", "", 0, 0, false, null);

    public final String shiftId, shuttleId;
    public final long start, end;
    public final double terminalLat, terminalLon;
    public final boolean pendingUpload;
    public final Fix last;

    LiveMapUiState(String id, String shuttle, long start, long end, boolean pending, Fix last) {
        this(id, shuttle, start, end, pending, last, Double.NaN, Double.NaN);
    }

    LiveMapUiState(String id, String shuttle, long start, long end, boolean pending, Fix last,
                   double terminalLat, double terminalLon) {
        this.shiftId = id;
        this.shuttleId = shuttle;
        this.start = start;
        this.end = end;
        this.pendingUpload = pending;
        this.last = last;
        this.terminalLat = terminalLat;
        this.terminalLon = terminalLon;
    }

    public boolean active() { return start > 0 && end == 0; }

    public Phase phase(long now) {
        if (start == 0) return Phase.PRE_SHIFT;
        if (end != 0) return Phase.ENDED;
        if (last == null) return Phase.WAITING_FOR_GPS;
        return now - last.time >= STALE_MS ? Phase.WEAK_SIGNAL : Phase.TRACKING;
    }

    public static boolean valid(double lat, double lon) {
        return ValidationUtil.isValidLatitude(lat) && ValidationUtil.isValidLongitude(lon);
    }

    LiveMapUiState append(double lat, double lon, long time, float speed, float bearing) {
        if (!active() || !ValidationUtil.isTrackingFix(lat, lon) || time < start || (last != null && time <= last.time)) return this;
        return new LiveMapUiState(shiftId, shuttleId, start, end, pendingUpload,
                new Fix(last, lat, lon, time, speed, bearing), terminalLat, terminalLon);
    }

    LiveMapUiState endAt(long time, boolean pending) {
        return new LiveMapUiState(shiftId, shuttleId, start, Math.max(start, time), pending, last, terminalLat, terminalLon);
    }

    /** O(new fixes) even when LiveData coalesces multiple updates while the UI is paused. */
    public Deque<Fix> fixesAfter(Fix rendered) {
        ArrayDeque<Fix> fixes = new ArrayDeque<>();
        for (Fix p = last; p != rendered && p != null; p = p.previous) fixes.addFirst(p);
        return fixes;
    }

    /** Existing server format. Consecutive identical coordinates describe the same geometry. */
    public String routeString() {
        StringBuilder route = new StringBuilder();
        for (Fix p : fixesAfter(null)) {
            if (p.previous != null && p.lat == p.previous.lat && p.lon == p.previous.lon) continue;
            if (route.length() > 0) route.append(';');
            route.append(p.lat).append(',').append(p.lon);
        }
        return route.toString();
    }

    public static final class Fix {
        final Fix previous;
        private final Fix distanceAnchor;
        public final double lat, lon, distanceKm;
        public final long time;
        public final float speed, bearing;

        Fix(Fix prev, double lat, double lon, long time, float speed, float bearing) {
            this.previous = prev;
            this.lat = lat;
            this.lon = lon;
            this.time = time;
            this.speed = Float.isFinite(speed) ? Math.max(0, speed) : 0;
            this.bearing = bearing;
            if (prev == null) {
                distanceAnchor = this;
                distanceKm = 0;
                return;
            }
            Fix anchor = prev.distanceAnchor;
            double step = new GpsCoordinate(anchor.lat, anchor.lon, anchor.time)
                    .distanceTo(new GpsCoordinate(lat, lon, time)) / 1000;
            // Keep every fix in the geometry. Count movement from the last reliable anchor so
            // stationary jitter adds no mileage and several small steps can still accumulate.
            boolean moved = step >= 0.005 && step <= (time - anchor.time) * 150.0 / 3_600_000;
            distanceAnchor = moved ? this : anchor;
            distanceKm = prev.distanceKm + (moved ? step : 0);
        }
    }
}
