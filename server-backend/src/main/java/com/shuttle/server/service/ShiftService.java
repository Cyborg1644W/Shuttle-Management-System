package com.shuttle.server.service;

import com.shuttle.server.ApiException;
import com.shuttle.server.Log;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ShiftService {
    private static final Map<String, String> activeShifts = new ConcurrentHashMap<>();

    public static String clockIn(String workerId) {
        if (workerId == null || workerId.trim().isEmpty()) {
            throw new ApiException(400, "Worker ID is required");
        }
        if (activeShifts.containsKey(workerId)) {
            throw new ApiException(400, "Worker is already clocked in");
        }
        String shiftId = "SHIFT-" + System.currentTimeMillis();
        activeShifts.put(workerId, shiftId);
        Log.info("Worker " + workerId + " assigned to shift " + shiftId);
        return shiftId;
    }

    public static void clockOut(String workerId) {
        if (workerId == null || !activeShifts.containsKey(workerId)) {
            throw new ApiException(400, "Worker is not currently clocked in");
        }
        activeShifts.remove(workerId);
    }

    public static String getWorkerShift(String workerId) {
        return activeShifts.getOrDefault(workerId, "No active shift");
    }

    public static boolean isClockedIn(String workerId) {
        return workerId != null && activeShifts.containsKey(workerId);
    }
}