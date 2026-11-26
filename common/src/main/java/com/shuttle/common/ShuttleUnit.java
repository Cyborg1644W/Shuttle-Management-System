package com.shuttle.common;

public class ShuttleUnit {

    private String shuttleId;
    private String plateNumber;
    private int capacity;
    private String vehicleModel = "";
    private String currentStatus = "AVAILABLE";  // AVAILABLE / IN_SERVICE / MAINTENANCE / OFFLINE

    public ShuttleUnit() {
    }

    public ShuttleUnit(String shuttleID, String plateNumber, int capacity, boolean available) {
        this.shuttleId = shuttleID;
        this.plateNumber = plateNumber;
        this.capacity = capacity;
        this.currentStatus = available ? "AVAILABLE" : "OFFLINE";
    }

    public String toCsvRow() {
        return CsvUtil.toRow(shuttleId, plateNumber, String.valueOf(capacity), currentStatus);
    }

    public String getShuttleId() { return shuttleId; }
    public void setShuttleId(String shuttleId) { this.shuttleId = shuttleId; }

    public String getPlateNumber() { return plateNumber; }
    public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }

    public String getVehicleModel() { return vehicleModel; }
    public void setVehicleModel(String vehicleModel) { this.vehicleModel = vehicleModel; }

    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }

    public String getCurrentStatus() { return currentStatus; }
    public void setCurrentStatus(String currentStatus) { this.currentStatus = currentStatus; }

    // Older names for the same things
    public String getShuttleID() { return shuttleId; }
    public void setShuttleID(String shuttleID) { this.shuttleId = shuttleID; }
    public boolean isAvailable() { return "AVAILABLE".equals(currentStatus); }
    public void setAvailable(boolean available) { this.currentStatus = available ? "AVAILABLE" : "OFFLINE"; }
}
