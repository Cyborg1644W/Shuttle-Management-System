package com.shuttle.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Driver extends Worker {
    public record VehicleNotice(String id, String shuttleId, long createdAt, boolean read) { }

    private List<VehicleNotice> vehicleNotices = Collections.emptyList();

    private String licenseNumber;
    private String licenseExpirationDate;
    private String assignedShuttleId;   // the vehicle the operator assigned
    private String assignedTerminal;    // the terminal / bay the operator assigned
    private String activeShiftId;
    private GpsCoordinate lastLocation;

    public Driver() {
        super(Role.DRIVER);
    }

    public Driver(String employeeID, String fullName, String email, String passwordHash,
                  String licenseNumber, String licenseExpirationDate) {
        super(employeeID, fullName, email, passwordHash, Role.DRIVER);
        this.licenseNumber = licenseNumber;
        this.licenseExpirationDate = licenseExpirationDate;
    }

    @Override
    public String getRoleSpecificInfo() {
        return "Driver - license " + licenseNumber + ", shuttle "
                + (assignedShuttleId == null ? "unassigned" : assignedShuttleId);
    }

    @Override
    public String toCsvRow() {
        String lat = lastLocation == null ? "" : String.valueOf(lastLocation.getLatitude());
        String lon = lastLocation == null ? "" : String.valueOf(lastLocation.getLongitude());
        return super.toCsvRow() + ","
                + CsvUtil.toRow(licenseNumber, licenseExpirationDate, assignedShuttleId,
                assignedTerminal, activeShiftId, lat, lon);
    }

    public String getLicenseNumber() { return licenseNumber; }
    public void setLicenseNumber(String licenseNumber) { this.licenseNumber = licenseNumber; }

    public String getLicenseExpirationDate() { return licenseExpirationDate; }
    public void setLicenseExpirationDate(String licenseExpirationDate) { this.licenseExpirationDate = licenseExpirationDate; }

    public String getAssignedShuttleId() { return assignedShuttleId; }
    public void setAssignedShuttleId(String assignedShuttleId) { this.assignedShuttleId = assignedShuttleId; }

    public String getAssignedTerminal() { return assignedTerminal; }
    public void setAssignedTerminal(String assignedTerminal) { this.assignedTerminal = assignedTerminal; }

    public String getActiveShiftId() { return activeShiftId; }
    public void setActiveShiftId(String activeShiftId) { this.activeShiftId = activeShiftId; }

    public List<VehicleNotice> getVehicleNotices() { return vehicleNotices; }

    public void setVehicleNotices(List<VehicleNotice> vehicleNotices) {
        this.vehicleNotices = Collections.unmodifiableList(new ArrayList<>(vehicleNotices));
    }

    public GpsCoordinate getLastLocation() { return lastLocation; }
    public void setLastLocation(GpsCoordinate lastLocation) { this.lastLocation = lastLocation; }
}
