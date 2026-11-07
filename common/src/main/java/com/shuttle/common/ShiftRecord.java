package com.shuttle.common;

public class ShiftRecord {

    private String shiftId;
    private String driverId;
    private String shuttleId;
    private long startTime;
    private long endTime;
    private String status = "ACTIVE";
    private double distanceKm;
    private int stops;
    private String terminal = "";
    private String route = "";

    public ShiftRecord() {
    }

    public ShiftRecord(String shiftID, String employeeID, String shuttleID,
                       long startTimeMillis, long endTimeMillis) {
        this.shiftId = shiftID;
        this.driverId = employeeID;
        this.shuttleId = shuttleID;
        this.startTime = startTimeMillis;
        this.endTime = endTimeMillis;
        this.status = endTimeMillis == 0 ? "ACTIVE" : "COMPLETED";
    }

    public boolean isActive() {
        return endTime == 0;
    }

    public void end(long endTimeMillis) {
        this.endTime = endTimeMillis;
    }

    public long getDurationMillis(long nowMillis) {
        long end = isActive() ? nowMillis : endTime;
        return Math.max(0, end - startTime);
    }

    public String toCsvRow() {
        return CsvUtil.toRow(shiftId, driverId, shuttleId, String.valueOf(startTime),
                endTime == 0 ? "" : String.valueOf(endTime), status,
                String.valueOf(distanceKm), String.valueOf(stops), terminal, route);
    }

    public String getShiftId() { return shiftId; }
    public void setShiftId(String shiftId) { this.shiftId = shiftId; }

    public String getDriverId() { return driverId; }
    public void setDriverId(String driverId) { this.driverId = driverId; }

    public String getShuttleId() { return shuttleId; }
    public void setShuttleId(String shuttleId) { this.shuttleId = shuttleId; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getEndTime() { return endTime; }
    public void setEndTime(long endTime) { this.endTime = endTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public double getDistanceKm() { return distanceKm; }
    public void setDistanceKm(double distanceKm) { this.distanceKm = distanceKm; }

    public int getStops() { return stops; }
    public void setStops(int stops) { this.stops = stops; }

    public String getTerminal() { return terminal; }
    public void setTerminal(String terminal) { this.terminal = terminal == null ? "" : terminal; }

    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route == null ? "" : route; }

    // Older names for the same things
    public String getShiftID() { return shiftId; }
    public String getEmployeeID() { return driverId; }
    public String getShuttleID() { return shuttleId; }
    public long getStartTimeMillis() { return startTime; }
    public long getEndTimeMillis() { return endTime; }
}