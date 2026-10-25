package com.shuttle.mobile.ui;

/**
 * Stand-in staff record. Replace with the Worker Management version if your teammate has one.
 * For a driver: value1 = assigned vehicle, value2 = assigned terminal.
 */
public class StaffMember {
    public String name;
    public boolean operator;   // true = operator, false = driver
    public String value1;      // vehicle (driver) or station (operator)
    public String value2;      // terminal (driver) or shift time (operator)
    public String status;      // "ON SHIFT" / "OFF DUTY" / ...
    public String empId = "";
    public String phone = "";

    public StaffMember(String name, boolean operator, String value1, String value2, String status) {
        this.name = name;
        this.operator = operator;
        this.value1 = value1;
        this.value2 = value2;
        this.status = status;
    }

    public StaffMember(String name, boolean operator, String value1, String value2, String status,
                       String empId, String phone) {
        this(name, operator, value1, value2, status);
        this.empId = empId == null ? "" : empId;
        this.phone = phone == null ? "" : phone;
    }
}