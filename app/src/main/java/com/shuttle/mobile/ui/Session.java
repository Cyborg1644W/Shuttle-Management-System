package com.shuttle.mobile.ui;

import com.shuttle.common.Role;

public final class Session {

    private static String token;
    private static String employeeId;
    private static String name;
    private static String email;
    private static String phone;
    private static Role role;

    private Session() {
    }

    public static void start(String token, String employeeId, String name,
                             String email, String phone, Role role) {
        Session.token = token;
        Session.employeeId = employeeId;
        Session.name = name;
        Session.email = email;
        Session.phone = phone;
        Session.role = role;
    }

    public static void clear() {
        token = null;
        employeeId = null;
        name = null;
        email = null;
        phone = null;
        role = null;
    }

    public static String getToken() {
        return token;
    }

    public static String getEmployeeId() {
        return employeeId;
    }

    public static String getName() {
        return name;
    }

    public static void setName(String newName) {
        name = newName;
    }

    public static String getEmail() {
        return email;
    }

    public static String getPhone() {
        return phone;
    }

    public static Role getRole() {
        return role;
    }

    public static boolean isSignedIn() {
        return token != null;
    }
}