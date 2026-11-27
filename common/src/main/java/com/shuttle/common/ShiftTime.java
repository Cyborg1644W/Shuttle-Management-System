package com.shuttle.common;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Shared clock labels for FleetFlow. The server still receives HH:mm. */
public final class ShiftTime {
    private ShiftTime() { }

    public static String display(int minutes) {
        int hour = minutes / 60;
        return String.format(Locale.US, "%d:%02d %s", hour % 12 == 0 ? 12 : hour % 12,
                minutes % 60, hour < 12 ? "AM" : "PM");
    }

    public static String display(String time) {
        if (time == null || !time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) return "Unavailable";
        int hour = Integer.parseInt(time.substring(0, 2));
        int minute = Integer.parseInt(time.substring(3));
        return display(hour * 60 + minute);
    }

    public static String wire(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    public static String timestamp(long millis, String pattern) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern, Locale.US);
        formatter.setTimeZone(TimeZone.getTimeZone("Asia/Manila"));
        return formatter.format(new Date(millis));
    }
}
