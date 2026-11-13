package com.shuttle.common;

import java.util.ArrayList;
import java.util.List;

public class Operator extends Worker {

    private String terminalZone;    // the station the operator is posted at
    private String shiftTime;       // e.g. "06:00 - 14:00"
    private int permissionsMask;
    private List<String> managedShuttleIds = new ArrayList<>();

    public Operator() {
        super(Role.OPERATOR);
    }

    public Operator(String employeeID, String fullName, String email, String passwordHash,
                    String assignedZone) {
        super(employeeID, fullName, email, passwordHash, Role.OPERATOR);
        this.terminalZone = assignedZone;
    }

    @Override
    public String getRoleSpecificInfo() {
        return "Operator - zone " + terminalZone + ", manages " + managedShuttleIds.size() + " shuttle(s)";
    }

    @Override
    public String toCsvRow() {
        return super.toCsvRow() + ","
                + CsvUtil.toRow(terminalZone, shiftTime, String.valueOf(permissionsMask),
                String.join(";", managedShuttleIds));
    }

    public String getTerminalZone() { return terminalZone; }
    public void setTerminalZone(String terminalZone) { this.terminalZone = terminalZone; }

    // Older name for the same thing
    public String getAssignedZone() { return terminalZone; }
    public void setAssignedZone(String assignedZone) { this.terminalZone = assignedZone; }

    public String getShiftTime() { return shiftTime; }
    public void setShiftTime(String shiftTime) { this.shiftTime = shiftTime; }

    public int getPermissionsMask() { return permissionsMask; }
    public void setPermissionsMask(int permissionsMask) { this.permissionsMask = permissionsMask; }

    public List<String> getManagedShuttleIds() { return managedShuttleIds; }
    public void setManagedShuttleIds(List<String> ids) {
        this.managedShuttleIds = ids == null ? new ArrayList<String>() : new ArrayList<String>(ids);
    }
    public void addManagedShuttleId(String shuttleId) { managedShuttleIds.add(shuttleId); }
}
