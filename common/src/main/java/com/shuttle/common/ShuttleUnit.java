package com.shuttle.common;

public class ShuttleUnit {

    private String shuttleID;
    private String plateNumber;
    private int capacity;
    private boolean available;

    public ShuttleUnit(String shuttleID, String plateNumber, int capacity, boolean available) {
        this.shuttleID = shuttleID;
        this.plateNumber = plateNumber;
        this.capacity = capacity;
        this.available = available;
    }

    public String toCsvRow() {
        return CsvUtil.toRow(shuttleID, plateNumber, String.valueOf(capacity), String.valueOf(available));
    }

    public String getShuttleID() { return shuttleID; }
    public void setShuttleID(String shuttleID) { this.shuttleID = shuttleID; }

    public String getPlateNumber() { return plateNumber; }
    public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }

    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }

    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
}