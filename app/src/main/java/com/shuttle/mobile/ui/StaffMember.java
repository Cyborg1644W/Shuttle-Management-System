package com.shuttle.mobile.ui;

/**
 * TEMPORARY stand-in. Replace with the Worker Management version if your teammate has one.
 * For a driver: value1 = assigned vehicle, value2 = assigned terminal.
 */
public class StaffMember {
    public String name;
    public boolean operator;   // true = operator, false = driver
    public String value1;      // vehicle
    public String value2;      // terminal
    public String status;      // "ON SHIFT" / "OFF DUTY"

    public StaffMember(String name, boolean operator, String value1, String value2, String status) {
        this.name = name;
        this.operator = operator;
        this.value1 = value1;
        this.value2 = value2;
        this.status = status;
    }
}