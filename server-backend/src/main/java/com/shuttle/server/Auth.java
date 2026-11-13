package com.shuttle.server;

import com.shuttle.common.Worker;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Auth {

    private static final Map<String, String> activeSessions = new ConcurrentHashMap<>();

    public static String authenticateUser(String email, String rawPassword) {
        if (email == null || rawPassword == null || email.trim().isEmpty()) {
            return null;
        }

        Optional<Worker> found = Repositories.workers().findByEmail(email.trim());
        if (!found.isPresent()) {
            return null;
        }
        Worker worker = found.get();

        if (!Crypto.verifyPassword(rawPassword, worker.getPasswordHash())) {
            return null;
        }
        String employment = worker.getEmploymentStatus();
        if (employment != null && !employment.equalsIgnoreCase("ACTIVE")) {
            throw new ApiException(403, "This account is " + employment.toLowerCase(Locale.ROOT)
                    + ". Please contact an operator.");
        }

        String sessionToken = UUID.randomUUID().toString();
        activeSessions.put(sessionToken, worker.getEmployeeID());
        return sessionToken;
    }

    public static boolean validateSessionToken(String token) {
        return getWorkerByToken(token) != null;
    }

    public static boolean hasPermission(String token, String requiredRole) {
        Worker worker = getWorkerByToken(token);
        if (worker == null) {
            return false;
        }
        if (requiredRole == null || requiredRole.trim().isEmpty()) {
            return true;
        }
        String userRole = worker.getRole() != null ? worker.getRole().toString() : "";
        return userRole.equalsIgnoreCase(requiredRole.trim());
    }

    public static Worker getWorkerByToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        String employeeId = activeSessions.get(token);
        if (employeeId == null) {
            return null;
        }
        Optional<Worker> worker = Repositories.workers().findById(employeeId);
        if (!worker.isPresent()) {
            activeSessions.remove(token);
            return null;
        }
        return worker.get();
    }

    public static void logout(String token) {
        if (token != null) {
            activeSessions.remove(token);
        }
    }

    public static void endSessionsFor(String employeeId) {
        activeSessions.values().removeIf(id -> id.equals(employeeId));
    }

    public static void replaceEmployeeId(String oldId, String newId) {
        activeSessions.replaceAll((token, id) -> id.equals(oldId) ? newId : id);
    }
}
