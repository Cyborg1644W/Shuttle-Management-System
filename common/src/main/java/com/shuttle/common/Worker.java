package com.shuttle.common;

public abstract class Worker {

    private String employeeID;
    private String fullName;
    private String email;
    private String passwordHash;
    private String phone = "";
    private String employmentStatus = "ACTIVE";
    private String shiftStatus = "OFF_SHIFT";
    private final Role role;

    protected Worker(Role role) {
        this.role = role;
    }

    protected Worker(String employeeID, String fullName, String email, String passwordHash, Role role) {
        this.employeeID = employeeID;
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public abstract String getRoleSpecificInfo();

    public String toCsvRow() {
        return CsvUtil.toRow(employeeID, fullName, email, passwordHash, role.name(),
                employmentStatus, shiftStatus, phone);
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

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone == null ? "" : phone; }

    public String getEmploymentStatus() { return employmentStatus; }
    public void setEmploymentStatus(String employmentStatus) { this.employmentStatus = employmentStatus; }

    public String getShiftStatus() { return shiftStatus; }
    public void setShiftStatus(String shiftStatus) { this.shiftStatus = shiftStatus; }
}
