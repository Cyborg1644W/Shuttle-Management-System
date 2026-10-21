package com.shuttle.common;

public class ShiftRecord {

    private String shiftID;
    private String employeeID;
    private String shuttleID;
    private long startTimeMillis;
    private long endTimeMillis;

    public ShiftRecord(String shiftID, String employeeID, String shuttleID,
                       long startTimeMillis, long endTimeMillis) {
        this.shiftID = shiftID;
        this.employeeID = employeeID;
        this.shuttleID = shuttleID;
        this.startTimeMillis = startTimeMillis;
        this.endTimeMillis = endTimeMillis;
    }

    public boolean isActive() {
        return endTimeMillis == 0;
    }

    public void end(long endTimeMillis) {
        this.endTimeMillis = endTimeMillis;
    }

    public long getDurationMillis(long nowMillis) {
        long end = isActive() ? nowMillis : endTimeMillis;
        return Math.max(0, end - startTimeMillis);
    }

    public String toCsvRow() {
        return CsvUtil.toRow(shiftID, employeeID, shuttleID,
                String.valueOf(startTimeMillis), String.valueOf(endTimeMillis));
    }

    public String getShiftID() { return shiftID; }
    public String getEmployeeID() { return employeeID; }
    public String getShuttleID() { return shuttleID; }
    public long getStartTimeMillis() { return startTimeMillis; }
    public long getEndTimeMillis() { return endTimeMillis; }
}