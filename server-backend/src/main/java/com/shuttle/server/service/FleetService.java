package com.shuttle.server.service;

import com.shuttle.common.ShiftRecord;
import com.shuttle.common.ShuttleUnit;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Csv;
import com.shuttle.server.Log;
import com.shuttle.server.Repositories;
import com.shuttle.server.repository.ShuttleRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.shuttle.server.Csv.str;
public class FleetService {

    public static List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ShuttleUnit s : Repositories.shuttles().getAllShuttles()) {
            out.add(toRow(s));
        }
        return out;
    }

    public static Map<String, Object> get(String shuttleId) {
        return toRow(find(shuttleId));
    }

    public static Map<String, Object> toRow(ShuttleUnit s) {
        String driverId = "";
        String driverName = "";
        for (ShiftRecord shift : Repositories.shifts().findAllActive()) {
            if (shift.getShuttleId().equals(s.getShuttleId())) {
                driverId = shift.getDriverId();
                driverName = Repositories.workers().findById(driverId).map(Worker::getFullName).orElse("");
            }
        }
        Map<String, Object> m = Csv.obj(
                "shuttleId", s.getShuttleId(),
                "plateNumber", s.getPlateNumber(),
                "capacity", s.getCapacity(),
                "vehicleModel", s.getVehicleModel(),
                "status", s.getCurrentStatus(),
                "driverId", driverId,
                "driverName", driverName);

        // Position columns are empty until the shuttle has reported a position
        TrackingService.Position p = TrackingService.get(s.getShuttleId());
        m.put("latitude", p == null ? "" : p.latitude);
        m.put("longitude", p == null ? "" : p.longitude);
        m.put("speedKmh", p == null ? "" : p.speedKmh);
        m.put("positionTime", p == null ? "" : p.deviceTime);
        m.put("ageSeconds", p == null ? "" : Math.max(0, (System.currentTimeMillis() - p.receivedAt) / 1000));
        return m;
    }

    public static ShuttleUnit add(Map<String, Object> body) throws IOException {
        synchronized (Repositories.LOCK) {
            String id = str(body, "shuttleId").trim();
            String plate = str(body, "plateNumber").trim();
            long capacity = Csv.lng(body, "capacity");
            String model = str(body, "vehicleModel").trim();
            if (id.isEmpty() || id.indexOf(' ') >= 0) {
                throw new ApiException(400, "shuttleId can't be empty or contain spaces");
            }
            if (plate.isEmpty()) {
                throw new ApiException(400, "plateNumber is required");
            }
            if (model.length() > 80 || model.chars().anyMatch(Character::isISOControl)
                    || (body.containsKey("vehicleModel") && model.isBlank())) {
                throw new ApiException(400, "Enter a vehicle model of up to 80 characters");
            }
            if (capacity < 0 || capacity > Integer.MAX_VALUE || (capacity == 0 && model.isBlank())) {
                throw new ApiException(400, "Enter a vehicle model or a positive seating capacity");
            }
            if (Repositories.shuttles().existsById(id)) {
                throw new ApiException(409, "This shuttle ID already exists");
            }
            ShuttleUnit s = new ShuttleUnit();
            s.setShuttleId(id);
            s.setPlateNumber(plate);
            s.setCapacity((int) capacity);
            s.setVehicleModel(model);
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
