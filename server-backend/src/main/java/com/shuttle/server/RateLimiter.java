package com.shuttle.server;

import java.util.concurrent.ConcurrentHashMap;

public class RateLimiter {
    private static final ConcurrentHashMap<String, Long> clientRequests = new ConcurrentHashMap<>();


    private static final long THROTTLE_DELAY_MS = 500; //500 ms


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