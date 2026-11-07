package com.shuttle.server.service;

import com.shuttle.common.ShiftRecord;
import com.shuttle.common.ShuttleUnit;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Json;
import com.shuttle.server.Log;
import com.shuttle.server.Repositories;
import com.shuttle.server.repository.ShuttleRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.shuttle.server.Json.str;

public class FleetService {

    public static List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ShuttleUnit s : Repositories.shuttles().getAllShuttles()) {
            out.add(toJson(s));
        }
        return out;
    }

    public static Map<String, Object> get(String shuttleId) {
        return toJson(find(shuttleId));
    }

    public static Map<String, Object> toJson(ShuttleUnit s) {
        String driverId = "";
        String driverName = "";
        for (ShiftRecord shift : Repositories.shifts().findAllActive()) {
            if (shift.getShuttleId().equals(s.getShuttleId())) {
                driverId = shift.getDriverId();
                driverName = Repositories.workers().findById(driverId).map(Worker::getFullName).orElse("");
            }
        }
        Map<String, Object> m = Json.obj(
                "shuttleId", s.getShuttleId(),
                "plateNumber", s.getPlateNumber(),
                "capacity", s.getCapacity(),
                "status", s.getCurrentStatus(),
                "driverId", driverId,
                "driverName", driverName);

        TrackingService.Position p = TrackingService.get(s.getShuttleId());
        if (p == null) {
            m.put("position", null);
        } else {
            m.put("position", Json.obj(
                    "latitude", p.latitude,
                    "longitude", p.longitude,
                    "speedKmh", p.speedKmh,
                    "timestamp", p.deviceTime,
                    "ageSeconds", Math.max(0, (System.currentTimeMillis() - p.receivedAt) / 1000)));
        }
        return m;
    }

    public static ShuttleUnit add(Map<String, Object> body) throws IOException {
        synchronized (Repositories.LOCK) {
            String id = str(body, "shuttleId").trim();
            String plate = str(body, "plateNumber").trim();
            int capacity = (int) Json.lng(body, "capacity");
            if (id.isEmpty() || id.indexOf(' ') >= 0) {
                throw new ApiException(400, "shuttleId can't be empty or contain spaces");
            }
            if (plate.isEmpty()) {
                throw new ApiException(400, "plateNumber is required");
            }
            if (capacity <= 0) {
                throw new ApiException(400, "capacity must be greater than 0");
            }
            if (Repositories.shuttles().existsById(id)) {
                throw new ApiException(409, "This shuttle ID already exists");
            }
            ShuttleUnit s = new ShuttleUnit();
            s.setShuttleId(id);
            s.setPlateNumber(plate);
            s.setCapacity(capacity);
            s.setCurrentStatus(ShuttleRepository.STATUS_AVAILABLE);
            Repositories.shuttles().saveShuttle(s);
            Log.audit("Shuttle added: " + id);
            return s;
        }
    }

    public static ShuttleUnit setStatus(String shuttleId, String rawStatus) throws IOException {
        synchronized (Repositories.LOCK) {
            ShuttleUnit s = find(shuttleId);
            String status = rawStatus == null ? "" : rawStatus.trim().toUpperCase(Locale.ROOT);
            if (!status.equals(ShuttleRepository.STATUS_AVAILABLE)
                    && !status.equals(ShuttleRepository.STATUS_MAINTENANCE)
                    && !status.equals(ShuttleRepository.STATUS_OFFLINE)) {
                throw new ApiException(400, "Status must be AVAILABLE, MAINTENANCE or OFFLINE");
            }
            if (ShuttleRepository.STATUS_IN_SERVICE.equals(s.getCurrentStatus())) {
                throw new ApiException(409, "This shuttle is in service. End the driver's shift first.");
            }
            return Repositories.shuttles().updateStatus(s.getShuttleId(), status).orElseThrow(
                    () -> new ApiException(404, "Shuttle not found"));
        }
    }

    public static void remove(String shuttleId) throws IOException {
        synchronized (Repositories.LOCK) {
            ShuttleUnit s = find(shuttleId);
            if (ShuttleRepository.STATUS_IN_SERVICE.equals(s.getCurrentStatus())) {
                throw new ApiException(409, "This shuttle is in service");
            }
            for (Worker w : Repositories.workers().getAllWorkers()) {
                if (w instanceof com.shuttle.common.Driver
                        && s.getShuttleId().equals(((com.shuttle.common.Driver) w).getAssignedShuttleId())) {
                    throw new ApiException(409, "A driver is still assigned to this shuttle");
                }
            }
            Repositories.shuttles().deleteShuttle(s.getShuttleId());
            Log.audit("Shuttle removed: " + s.getShuttleId());
        }
    }

    private static ShuttleUnit find(String shuttleId) {
        return Repositories.shuttles().findById(shuttleId == null ? "" : shuttleId)
                .orElseThrow(() -> new ApiException(404, "Shuttle not found"));
    }
}
