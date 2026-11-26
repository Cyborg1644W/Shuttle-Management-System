package com.shuttle.common;

public final class ShiftTimeTest {
    public static void main(String[] args) {
        check("12:00 AM", ShiftTime.display("00:00"));
        check("12:00 PM", ShiftTime.display("12:00"));
        check("10:22 AM", ShiftTime.display("10:22"));
        check("10:22 PM", ShiftTime.display("22:22"));
        check("10:00 PM – 6:00 AM", ShiftTime.display("22:00") + " – " + ShiftTime.display("06:00"));
        check("00:00", ShiftTime.wire(0));
        check("22:22", ShiftTime.wire(22 * 60 + 22));
        check("Unavailable", ShiftTime.display("bad"));
        System.out.println("Shift time display and unchanged wire formatting passed.");
    }

    private static void check(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
