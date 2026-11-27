package com.shuttle.server.service;

import com.shuttle.server.ApiException;
import com.shuttle.server.Log;
import com.shuttle.server.Repositories;
import com.shuttle.common.ShiftRecord;
import com.shuttle.common.ValidationUtil;
import com.shuttle.server.Csv;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TrackingService {

    public static final class Position {
        public final String shiftId;
        public final double bearing;
        final Position previous;
        public final String shuttleId;
        public final double latitude;
        public final double longitude;
        public final double speedKmh;
        public final long deviceTime;   // when the phone says it measured this
        public final long receivedAt;   // when the server got it

        Position(String shuttleId, double latitude, double longitude, double speedKmh, long deviceTime, long receivedAt,
                 String shiftId, double bearing, Position previous) {
            this.shiftId = shiftId;
            this.bearing = bearing;
            this.previous = previous;
            this.shuttleId = shuttleId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.speedKmh = speedKmh;
            this.deviceTime = deviceTime;
            this.receivedAt = receivedAt;
        }
    }

    private static final double MAX_SPEED_KMH = 160.0;
    private static final long MAX_FUTURE_MS = 5 * 60 * 1000L;

    private static final Map<String, Position> positions = new ConcurrentHashMap<>();

    public static synchronized void updateLocation(String shuttleId, double lat, double lng,
                                                   double speedMps, long deviceTimeMillis) {
        accept(shuttleId, lat, lng, speedMps, deviceTimeMillis, "", Double.NaN);
    }

    public static boolean report(String shuttleId, Map<String, Object> body) {
        double lat = number(body, "latitude", Double.NaN);
        double lon = number(body, "longitude", Double.NaN);
        if (!ValidationUtil.isTrackingFix(lat, lon)) throw new ApiException(400, "Invalid GPS coordinates");
        long time = 0;
        if (body.containsKey("timestamp")) {
            try {
                time = Long.parseLong(Csv.str(body, "timestamp").trim());
            } catch (NumberFormatException invalid) {
                throw new ApiException(400, "Invalid GPS timestamp");
            }
            if (time <= 0) throw new ApiException(400, "Invalid GPS timestamp");
        }
        return report(shuttleId, lat, lon, number(body, "speed", 0), time,
                Csv.str(body, "shiftId"), number(body, "bearing", Double.NaN));
    }

    private static double number(Map<String, Object> body, String key, double fallback) {
        if (!Csv.filled(body, key)) return fallback;
        try {
            double number = Double.parseDouble(Csv.str(body, key));
            if (Double.isFinite(number)) return number;
        } catch (NumberFormatException ignored) {
            // Return the same client error for malformed and non-finite values.
        }
        throw new ApiException(400, "Invalid GPS " + key);
    }

    public static boolean report(String shuttleId, double lat, double lon, double speed, long time,
                              String reportedShift, double bearing) {
        String shiftId = "";
        if (!reportedShift.isEmpty()) {
            ShiftRecord shift = Repositories.shifts().findById(reportedShift).orElse(null);
            if (shift == null || shift.getEndTime() != 0 || !shuttleId.equals(shift.getShuttleId())
                    || (time > 0 && time < shift.getStartTime())) return false;
            shiftId = reportedShift;
        } else {
            for (ShiftRecord shift : Repositories.shifts().findAllActive()) {
                if (shuttleId.equals(shift.getShuttleId())) {
                    if (time > 0 && time < shift.getStartTime()) return false;
                    shiftId = shift.getShiftId();
                    break;
                }
            }
        }
        return accept(shuttleId, lat, lon, speed, time, shiftId, bearing);
    }

    private static synchronized boolean accept(String shuttleId, double lat, double lng, double speedMps,
                                            long deviceTimeMillis, String shiftId, double bearing) {
        if (shuttleId == null || shuttleId.trim().isEmpty()) {
            throw new ApiException(400, "shuttleId is required");
        }
        if (!ValidationUtil.isTrackingFix(lat, lng)) {
            throw new ApiException(400, "Invalid coordinates");
        }

        long now = System.currentTimeMillis();
        long time = deviceTimeMillis > 0 ? deviceTimeMillis : now;
        if (time > now + MAX_FUTURE_MS) {
            throw new ApiException(400, "Timestamp is in the future");
        }

        Position previous = positions.get(shuttleId);
        if (previous != null && previous.shiftId.equals(shiftId)) {
            if (time <= previous.deviceTime) {
                return false;
            }
            double seconds = (time - previous.deviceTime) / 1000.0;
            double distanceKm = distanceKm(previous.latitude, previous.longitude, lat, lng);
            double speedKmh = distanceKm / seconds * 3600;

            if (seconds >= 2 && speedKmh > MAX_SPEED_KMH) {
                Log.info("Rejected spoofed location ping for shuttle: " + shuttleId + " (Speed: " + Math.round(speedKmh) + " km/h)");
                throw new ApiException(400, "Location update rejected: Velocity anomaly detected");
            }
        }
        Position prior = previous != null && !shiftId.isEmpty() && shiftId.equals(previous.shiftId) ? previous : null;
        if (!(bearing >= 0 && bearing < 360)) bearing = Double.NaN;
        if (Double.isNaN(bearing) && prior != null) {
            double d = Math.toRadians(lng - prior.longitude);
            double a = Math.toRadians(prior.latitude), b = Math.toRadians(lat);
            bearing = lat == prior.latitude && lng == prior.longitude ? prior.bearing :
                    (Math.toDegrees(Math.atan2(Math.sin(d)*Math.cos(b),
                            Math.cos(a)*Math.sin(b)-Math.sin(a)*Math.cos(b)*Math.cos(d))) + 360) % 360;
        }
        positions.put(shuttleId, new Position(shuttleId, lat, lng, Math.max(0, speedMps) * 3.6, time, now,
                shiftId, bearing, prior));
        return true;
    }

    /** Release completed geometry while keeping the last position available to existing fleet callers. */
    public static synchronized void finishShift(String shuttleId, String shiftId) {
        Position p = positions.get(shuttleId);
        if (p != null && shiftId.equals(p.shiftId)) positions.put(shuttleId, new Position(shuttleId, p.latitude,
                p.longitude, p.speedKmh, p.deviceTime, p.receivedAt, "", p.bearing, null));
    }

    public static String trailAfter(Position latest, long after) {
        java.util.ArrayDeque<Position> fixes = new java.util.ArrayDeque<>();
        for (Position p = latest; p != null && p.deviceTime > after; p = p.previous) fixes.addFirst(p);
        StringBuilder trail = new StringBuilder();
        for (Position p : fixes) {
            if (trail.length() > 0) trail.append(';');
            trail.append(p.deviceTime).append(',').append(p.latitude).append(',').append(p.longitude);
        }
        return trail.toString();
    }

    public static void updateLocation(String shuttleId, String coordinates) {
        if (shuttleId == null || coordinates == null || !coordinates.contains(",")) {
            throw new ApiException(400, "Invalid coordinate format");
        }
        String[] xy = coordinates.split(",");
        try {
            updateLocation(shuttleId, Double.parseDouble(xy[0].trim()), Double.parseDouble(xy[1].trim()), 0, 0);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Malformed coordinate values");
        }
    }

    public static Position get(String shuttleId) {
        return positions.get(shuttleId);
    }

    public static String getLocation(String shuttleId) {
        Position p = positions.get(shuttleId);
        return p == null ? "0.0,0.0" : p.latitude + "," + p.longitude;
    }

    public static String getLatestLocation(String shuttleId) {
        return getLocation(shuttleId);
    }

    public static Map<String, Position> snapshot() {
        return new HashMap<>(positions);
    }

    public static void restore(Map<String, Position> snapshot) {
        positions.clear();
        positions.putAll(snapshot);
    }

    public static Map<String, String> getAllActiveLocations() {
        Map<String, String> out = new HashMap<>();
        for (Position p : positions.values()) {
            out.put(p.shuttleId, p.latitude + "," + p.longitude);
        }
        return out;
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double latRad1 = Math.toRadians(lat1);
        double latRad2 = Math.toRadians(lat2);
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(latRad1) * Math.cos(latRad2)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371.0 * c;
    }
}
