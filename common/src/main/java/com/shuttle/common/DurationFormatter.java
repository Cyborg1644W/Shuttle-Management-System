package com.shuttle.common;

public final class DurationFormatter {

    private DurationFormatter() {
    }

    public static String formatShort(long millis) {
        long totalMinutes = Math.max(0, millis) / 60000;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours == 0) {
            return minutes + "m";
        }
        return String.format("%dh %02dm", hours, minutes);
    }

    public static String formatClock(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}