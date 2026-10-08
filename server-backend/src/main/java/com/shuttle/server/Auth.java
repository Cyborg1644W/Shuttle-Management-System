package com.shuttle.server;

import com.shuttle.common.Worker;
import com.shuttle.server.repository.WorkerRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Auth {

    // Thread-safe map tracking active session tokens mapped to logged-in Worker instances
    private static final Map<String, Worker> activeSessions = new ConcurrentHashMap<>();

    /**
     * Authenticates user credentials against workers.csv repository.
     * Generates and returns a session token upon successful validation.
     */
    public static String authenticateUser(String email, String rawPassword) {
        if (email == null || rawPassword == null || email.trim().isEmpty()) {
            return null;
        }

        // Load workers from CSV repository
        WorkerRepository workerRepository = new WorkerRepository();
        List<Worker> workers = workerRepository.loadAllWorkers();

        for (Worker worker : workers) {
            if (worker.getEmail() != null && worker.getEmail().equalsIgnoreCase(email.trim())) {
                // Cross-reference raw password with pre-hashed credential via Crypto utility
                if (Crypto.verifyPassword(rawPassword, worker.getPasswordHash())) {
                    String sessionToken = UUID.randomUUID().toString();
                    activeSessions.put(sessionToken, worker);
                    return sessionToken;
                }
                break; // Matching user found, but password verification failed
            }
        }

        return null; // Invalid credentials or user not found
    }

    /**
     * Validates whether a provided session token exists and is active in memory.
     */
    public static boolean validateSessionToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        return activeSessions.containsKey(token);
    }

    /**
     * Enforces Role-Based Access Control (RBAC) permissions.
     */
    public static boolean hasPermission(String token, String requiredRole) {
        if (!validateSessionToken(token)) {
            return false;
        }

        Worker worker = activeSessions.get(token);
        if (worker == null) {
            return false;
        }

        if (requiredRole == null || requiredRole.trim().isEmpty()) {
            return true;
        }

        String userRole = worker.getRole() != null ? worker.getRole().toString() : "";

        // Admin override or exact role match (case-insensitive)
        return userRole.equalsIgnoreCase("ADMIN") || userRole.equalsIgnoreCase(requiredRole.trim());
    }

    /**
     * Retrieves the active Worker entity bound to a valid session token.
     */
    public static Worker getWorkerByToken(String token) {
        if (!validateSessionToken(token)) {
            return null;
        }
        return activeSessions.get(token);
    }

    /**
     * Terminates an active session and removes the token from memory.
     */
    public static void logout(String token) {
        if (token != null) {
            activeSessions.remove(token);
        }
    }
}