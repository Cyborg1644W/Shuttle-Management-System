package com.shuttle.server;

import java.util.concurrent.ConcurrentHashMap;

public class RateLimiter {
    // Maps a client's IP address to the timestamp of their last request
    private static final ConcurrentHashMap<String, Long> clientRequests = new ConcurrentHashMap<>();

    // Minimum time allowed between requests (e.g., 500 milliseconds)
    private static final long THROTTLE_DELAY_MS = 500;

    /**
     * Checks if the incoming request should be allowed or blocked.
     *
     * @param ipAddress The IP address of the client making the request.
     * @return true if allowed, false if they are requesting too fast.
     */
    public static boolean isAllowed(String ipAddress) {
        long currentTime = System.currentTimeMillis();
        long lastRequestTime = clientRequests.getOrDefault(ipAddress, 0L);

        // If the time since the last request is too short, block it
        if (currentTime - lastRequestTime < THROTTLE_DELAY_MS) {
            return false;
        }

        // Otherwise, update the timestamp and allow the request
        clientRequests.put(ipAddress, currentTime);
        return true;
    }
}