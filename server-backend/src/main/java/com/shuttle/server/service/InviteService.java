package com.shuttle.server.service;

import com.shuttle.server.ApiException;
import com.shuttle.server.Log;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InviteService {
    private static final Map<String, InviteRecord> validInvites = new ConcurrentHashMap<>();
    private static final long EXPIRATION_MS = 48 * 60 * 60 * 1000L; // 48 hours

    private static class InviteRecord {
        final String role;
        final long createdAt;

        InviteRecord(String role, long createdAt) {
            this.role = role;
            this.createdAt = createdAt;
        }
    }

    public static String createInvite(String role) {
        String code = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        validInvites.put(code, new InviteRecord(role != null ? role : "DRIVER", System.currentTimeMillis()));
        Log.audit("Created invite code: " + code + " for role: " + role);
        return code;
    }

    public static String consumeInviteCode(String code) {
        if (code == null || !validInvites.containsKey(code)) {
            throw new ApiException(403, "Invalid or expired invite code");
        }

        InviteRecord record = validInvites.get(code);
        if (System.currentTimeMillis() - record.createdAt > EXPIRATION_MS) {
            validInvites.remove(code);
            throw new ApiException(403, "Invite code has expired");
        }

        validInvites.remove(code);
        return record.role;
    }
}