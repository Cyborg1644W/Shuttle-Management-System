package com.shuttle.server.service;

import com.shuttle.server.ApiException;
import com.shuttle.server.Log;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TrackingService {
    private static final Map<String, String> driverLocations = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastPingTimes = new ConcurrentHashMap<>();

    public static void updateLocation(String driverId, String coordinates) {
        if (driverId == null || coordinates == null || !coordinates.contains(",")) {
            throw new ApiException(400, "Invalid coordinate format");
        }

        long currentTime = System.currentTimeMillis();

        // Check velocity spikes if a previous ping exists
        if (driverLocations.containsKey(driverId) && lastPingTimes.containsKey(driverId)) {
            String[] oldCoords = driverLocations.get(driverId).split(",");
            String[] newCoords = coordinates.split(",");

            try {
                double lat1 = Double.parseDouble(oldCoords[0].trim());
                double lon1 = Double.parseDouble(oldCoords[1].trim());
                double lat2 = Double.parseDouble(newCoords[0].trim());
                double lon2 = Double.parseDouble(newCoords[1].trim());

                long timeDeltaSeconds = (currentTime - lastPingTimes.get(driverId)) / 1000;
                if (timeDeltaSeconds > 0) {
                    double distanceKm = calculateDistanceKm(lat1, lon1, lat2, lon2);
                    double speedKmh = (distanceKm / timeDeltaSeconds) * 3600;

                    // Reject impossible teleportation jumps (speed > 160 km/h)
                    if (speedKmh > 160.0) {
                        Log.info("Rejected spoofed location ping for driver: " + driverId + " (Speed: " + speedKmh + " km/h)");
                        throw new ApiException(400, "Location update rejected: Velocity anomaly detected");
                    }
                }
            } catch (NumberFormatException e) {
                throw new ApiException(400, "Malformed coordinate values");
            }
        }

        driverLocations.put(driverId, coordinates);
        lastPingTimes.put(driverId, currentTime);
    }

    public static String getLocation(String driverId) {
        return driverLocations.getOrDefault(driverId, "0.0,0.0");
    }

    public static String getLatestLocation(String driverId) {
        return getLocation(driverId);
    }

    public static Map<String, String> getAllActiveLocations() {
        return driverLocations;
    }

    private static double calculateDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        double latRad1 = Math.toRadians(lat1);
        double latRad2 = Math.toRadians(lat2);
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(latRad1) * Math.cos(latRad2)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371.0 * c; // Earth radius in km
    }
}