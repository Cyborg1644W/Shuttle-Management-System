package com.shuttle.common;
import java.util.ArrayList;
import java.util.List;

public class Operator extends Worker {

    private String assignedZone;
    private final List<String> managedShuttleIds = new ArrayList<>();

    public Operator(String employeeID, String fullName, String email, String passwordHash,
                    String assignedZone) {
        super(employeeID, fullName, email, passwordHash, Role.OPERATOR);
        this.assignedZone = assignedZone;
    }

    @Override
    public String getRoleSpecificInfo() {
        return "Operator - zone " + assignedZone + ", manages " + managedShuttleIds.size() + " shuttle(s)";
    }

    @Override
    public String toCsvRow() {
        return super.toCsvRow() + ","
                + CsvUtil.toRow(assignedZone, String.join(";", managedShuttleIds));
    }

    public String getAssignedZone() { return assignedZone; }
    public void setAssignedZone(String assignedZone) { this.assignedZone = assignedZone; }

    public List<String> getManagedShuttleIds() { return managedShuttleIds; }
    public void addManagedShuttleId(String shuttleId) { managedShuttleIds.add(shuttleId); }
}