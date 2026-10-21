package com.shuttle.common;

public abstract class Worker {

    private String employeeID;
    private String fullName;
    private String email;
    private String passwordHash;
    private String shiftStatus;
    private final Role role;

    protected Worker(String employeeID, String fullName, String email, String passwordHash, Role role) {
        this.employeeID = employeeID;
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.shiftStatus = "OFF_DUTY";
    }

    public abstract String getRoleSpecificInfo();

    public String toCsvRow() {
        return CsvUtil.toRow(employeeID, fullName, email, passwordHash, role.name(), shiftStatus);
    }

    public Role getRole() { return role; }

    public String getEmployeeID() { return employeeID; }
    public void setEmployeeID(String employeeID) { this.employeeID = employeeID; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getShiftStatus() { return shiftStatus; }
    public void setShiftStatus(String shiftStatus) { this.shiftStatus = shiftStatus; }
}