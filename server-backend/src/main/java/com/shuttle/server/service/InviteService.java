package com.shuttle.server.service;

import com.shuttle.common.Role;
import com.shuttle.server.ApiException;
import com.shuttle.server.Log;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InviteService {
    private static final Map<String, Invite> validInvites = new ConcurrentHashMap<>();
    private static final long EXPIRATION_MS = 48 * 60 * 60 * 1000L; // 48 hours

    public static final class Invite {
        public final String role;
        public final String employeeId;
        final long createdAt;

        Invite(String role, String employeeId, long createdAt) {
            this.role = role;
            this.employeeId = employeeId;
            this.createdAt = createdAt;
        }
    }

    public static String createInvite(String role) {
        return createInvite(role, null);
    }

    public static String createInvite(String role, String employeeId) {
        String normalized = role == null || role.trim().isEmpty() ? "DRIVER" : role.trim().toUpperCase(Locale.ROOT);
        try {
            Role.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Role must be DRIVER or OPERATOR");
        }
        String code = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        validInvites.put(code, new Invite(normalized, employeeId, System.currentTimeMillis()));
        Log.audit("Created invite code: " + code + " for role: " + normalized
                + (employeeId != null ? " (worker " + employeeId + ")" : ""));
        return code;
    }

    public static Invite peek(String code) {
        String key = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        Invite record = validInvites.get(key);
        if (record == null) {
            throw new ApiException(403, "Invalid or expired invite code");
        }
        if (System.currentTimeMillis() - record.createdAt > EXPIRATION_MS) {
            validInvites.remove(key);
            throw new ApiException(403, "Invite code has expired");
        }
        return record;
    }

    public static Invite consume(String code) {
        Invite record = peek(code);
        validInvites.remove(code.trim().toUpperCase(Locale.ROOT));
        return record;
    }

    public static String consumeInviteCode(String code) {
        return consume(code).role;
    }
}
