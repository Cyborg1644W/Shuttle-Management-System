package com.shuttle.server.service;

import com.shuttle.server.ApiException;
import com.shuttle.server.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TrackingService {

    public static final class Position {
        public final String shuttleId;
        public final double latitude;
        public final double longitude;
        public final double speedKmh;
        public final long deviceTime;   // when the phone says it measured this
        public final long receivedAt;   // when the server got it

        Position(String shuttleId, double latitude, double longitude, double speedKmh, long deviceTime, long receivedAt) {
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
        if (shuttleId == null || shuttleId.trim().isEmpty()) {
            throw new ApiException(400, "shuttleId is required");
        }
        if (Double.isNaN(lat) || Double.isNaN(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new ApiException(400, "Invalid coordinates");
        }

        long now = System.currentTimeMillis();
        long time = deviceTimeMillis > 0 ? deviceTimeMillis : now;
        if (time > now + MAX_FUTURE_MS) {
            throw new ApiException(400, "Timestamp is in the future");
        }

        Position previous = positions.get(shuttleId);
        if (previous != null) {
            if (time <= previous.deviceTime) {
                return;
            }
            double seconds = (time - previous.deviceTime) / 1000.0;
            double distanceKm = distanceKm(previous.latitude, previous.longitude, lat, lng);
            double speedKmh = distanceKm / seconds * 3600;

            if (seconds >= 2 && speedKmh > MAX_SPEED_KMH) {
                Log.info("Rejected spoofed location ping for shuttle: " + shuttleId + " (Speed: " + Math.round(speedKmh) + " km/h)");
                throw new ApiException(400, "Location update rejected: Velocity anomaly detected");
            }
        }
        positions.put(shuttleId, new Position(shuttleId, lat, lng, Math.max(0, speedMps) * 3.6, time, now));
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
