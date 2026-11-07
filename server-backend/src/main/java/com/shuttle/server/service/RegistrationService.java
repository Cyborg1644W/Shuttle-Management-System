package com.shuttle.server.service;

import com.shuttle.common.Role;
import com.shuttle.common.Worker;
import com.shuttle.server.Log;
import com.shuttle.server.Repositories;

import java.io.IOException;

public class RegistrationService {

    public static Worker registerUser(String inviteCode, String fullName, String email, String phone,
                                      String password, String licenseNumber) throws IOException {
        synchronized (Repositories.LOCK) {
            InviteService.Invite invite = InviteService.peek(inviteCode);
            Worker worker = WorkerService.register(invite, fullName, email, phone, password, licenseNumber);
            InviteService.consume(inviteCode);
            Log.audit("Registered new " + Role.valueOf(invite.role) + ": " + worker.getEmail()
                    + " (" + worker.getEmployeeID() + ")");
            return worker;
        }
    }
}
