package com.shuttle.server.service;

import com.shuttle.common.Role;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Log;

import java.io.IOException;
import java.util.Locale;

public class RegistrationService {

    public static Worker registerUser(String role, String fullName, String email, String phone,
                                      String password, String licenseNumber, String employeeId) throws IOException {
        Role parsed;
        String r = role == null || role.trim().isEmpty() ? "DRIVER" : role.trim().toUpperCase(Locale.ROOT);
        try {
            parsed = Role.valueOf(r);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Role must be DRIVER or OPERATOR");
        }
        Worker worker = WorkerService.register(parsed, fullName, email, phone, password, licenseNumber, employeeId);
        Log.audit("Registered new " + parsed + ": " + worker.getEmail() + " (" + worker.getEmployeeID() + ")");
        return worker;
    }
}
