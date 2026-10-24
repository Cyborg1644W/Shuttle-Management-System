package com.shuttle.common;

public class GpsCoordinate {
    private static final double EARTH_RADIUS_METERS = 6371000.0;

    private final double latitude;
    private final double longitude;
    private final long timestampMillis;

    public GpsCoordinate(double latitude, double longitude, long timestampMillis) {
        if (!ValidationUtil.isValidLatitude(latitude) || !ValidationUtil.isValidLongitude(longitude)) {
            throw new IllegalArgumentException("Invalid GPS coordinate: " + latitude + ", " + longitude);
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestampMillis = timestampMillis;
    }

    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public long getTimestampMillis() { return timestampMillis; }
    public double distanceTo(GpsCoordinate other) {
        double lat1 = Math.toRadians(latitude);
        double lat2 = Math.toRadians(other.latitude);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(other.longitude - longitude);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public String toCsvRow() {
        return CsvUtil.toRow(String.valueOf(latitude), String.valueOf(longitude),
                String.valueOf(timestampMillis));
    }
}