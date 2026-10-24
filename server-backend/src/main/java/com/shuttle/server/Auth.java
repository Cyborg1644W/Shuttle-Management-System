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

    public static boolean validateSessionToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        return activeSessions.containsKey(token);
    }

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


    public static Worker getWorkerByToken(String token) {
        if (!validateSessionToken(token)) {
            return null;
        }
        return activeSessions.get(token);
    }

    public static void logout(String token) {
        if (token != null) {
            activeSessions.remove(token);
        }
    }
}