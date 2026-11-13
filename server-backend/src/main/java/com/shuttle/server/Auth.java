package com.shuttle.server;

import com.shuttle.common.Worker;
import com.shuttle.server.service.WorkerService;

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
        return startSession(Repositories.workers().findByEmail(email.trim()), rawPassword);
    }

    public static String authenticateByPhone(String phone, String rawPassword) {
        if (phone == null || rawPassword == null || phone.trim().isEmpty()) {
            return null;
        }
        String normalized;
        try {
            normalized = WorkerService.normalizePhone(phone);
        } catch (ApiException e) {
            return null; // not a usable number, so it can't match anyone
        }
        return startSession(findByPhone(normalized), rawPassword);
    }

    private static Optional<Worker> findByPhone(String phone) {
        for (Worker w : Repositories.workers().getAllWorkers()) {
            if (phone.equals(w.getPhone()) && !WorkerService.PENDING_HASH.equals(w.getPasswordHash())) {
                return Optional.of(w);
            }
        }
        return Optional.empty();
    }

    private static String startSession(Optional<Worker> found, String rawPassword) {
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