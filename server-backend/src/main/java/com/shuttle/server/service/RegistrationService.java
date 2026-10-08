package com.shuttle.server.service;

import com.shuttle.server.ApiException;
import com.shuttle.server.Crypto;
import com.shuttle.server.Log;

public class RegistrationService {

    public static void registerUser(String inviteCode, String email, String password) {
        if (email == null || password == null || password.length() < 6) {
            throw new ApiException(400, "Invalid email or password too short");
        }

        String role = InviteService.consumeInviteCode(inviteCode);
        String hashedPassword = Crypto.hashPassword(password);

        Log.audit("Registered new " + role + ": " + email);
    }
}