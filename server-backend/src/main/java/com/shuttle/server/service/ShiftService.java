package com.shuttle.server.service;

import com.shuttle.common.Driver;
import com.shuttle.common.Role;
import com.shuttle.common.ShiftRecord;
import com.shuttle.common.ShuttleUnit;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Json;
import com.shuttle.server.Log;
import com.shuttle.server.Repositories;
import com.shuttle.server.repository.ShiftRepository;
import com.shuttle.server.repository.ShuttleRepository;
import com.shuttle.server.repository.WorkerRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ShiftService {

    private static final int MAX_ROUTE_CHARS = 20_000;

    public static ShiftRecord start(Worker worker) throws IOException {
        synchronized (Repositories.LOCK) {
            if (worker.getRole() != Role.DRIVER) {
                throw new ApiException(403, "Only drivers can start a shift");
            }
            Driver driver = (Driver) Repositories.workers().findById(worker.getEmployeeID())
                    .orElseThrow(() -> new ApiException(404, "Worker not found"));

            if (driver.getActiveShiftId() != null
                    || Repositories.shifts().findActiveByDriverId(driver.getEmployeeID()).isPresent()) {
                throw new ApiException(409, "You already have a shift running");
            }
            String shuttleId = driver.getAssignedShuttleId();
            if (shuttleId == null || shuttleId.isEmpty()) {
                throw new ApiException(409, "No vehicle assigned yet. Ask an operator to assign one.");
            }
            ShuttleUnit shuttle = Repositories.shuttles().findById(shuttleId)
                    .orElseThrow(() -> new ApiException(409, "Your assigned vehicle no longer exists"));
            if (ShuttleRepository.STATUS_MAINTENANCE.equals(shuttle.getCurrentStatus())
                    || ShuttleRepository.STATUS_OFFLINE.equals(shuttle.getCurrentStatus())) {
                throw new ApiException(409, shuttleId + " is " + shuttle.getCurrentStatus().toLowerCase() + ". Ask an operator for another vehicle.");
            }
            for (ShiftRecord other : Repositories.shifts().findAllActive()) {
                if (other.getShuttleId().equals(shuttleId)) {
                    throw new ApiException(409, shuttleId + " is already in use by another driver");
                }
            }

            ShiftRecord shift = new ShiftRecord();
            shift.setShiftId(Repositories.shifts().nextShiftId());
            shift.setDriverId(driver.getEmployeeID());
            shift.setShuttleId(shuttleId);
            shift.setStartTime(System.currentTimeMillis());
            shift.setEndTime(0);
            shift.setStatus(ShiftRepository.STATUS_ACTIVE);
            shift.setTerminal(driver.getAssignedTerminal());
            Repositories.shifts().saveShift(shift);

            driver.setActiveShiftId(shift.getShiftId());
            driver.setShiftStatus(WorkerRepository.SHIFT_ON);
            Repositories.workers().saveWorker(driver);

            Repositories.shuttles().updateStatus(shuttleId, ShuttleRepository.STATUS_IN_SERVICE);
            Log.info("Driver " + driver.getEmployeeID() + " started " + shift.getShiftId() + " on " + shuttleId);
            return shift;
        }
    }

    public static ShiftRecord end(Worker worker, double distanceKm, int stops, String route) throws IOException {
        synchronized (Repositories.LOCK) {
            ShiftRecord active = Repositories.shifts().findActiveByDriverId(worker.getEmployeeID())
                    .orElseThrow(() -> new ApiException(409, "You have no shift running"));
            String safeRoute = route == null ? "" : route.length() > MAX_ROUTE_CHARS ? "" : route;
            ShiftRecord done = close(active, ShiftRepository.STATUS_COMPLETED,
                    Math.max(0, distanceKm), Math.max(0, stops), safeRoute);
            Log.info("Driver " + worker.getEmployeeID() + " ended " + done.getShiftId());
            return done;
        }
    }

    public static void forceClose(String driverId, String finalStatus) throws IOException {
        synchronized (Repositories.LOCK) {
            Optional<ShiftRecord> active = Repositories.shifts().findActiveByDriverId(driverId);
            if (active.isPresent()) {
                close(active.get(), finalStatus, 0, 0, null);
            }
        }
    }

    private static ShiftRecord close(ShiftRecord active, String finalStatus,
                                     double distanceKm, int stops, String route) throws IOException {
        long end = Math.max(System.currentTimeMillis(), active.getStartTime());
        ShiftRecord closed = Repositories.shifts()
                .closeShift(active.getShiftId(), end, finalStatus, distanceKm, stops, route)
                .orElseThrow(() -> new ApiException(409, "That shift is already closed"));

        Optional<Worker> worker = Repositories.workers().findById(closed.getDriverId());
        if (worker.isPresent() && worker.get() instanceof Driver) {
            Driver d = (Driver) worker.get();
            d.setActiveShiftId(null);
            d.setShiftStatus(WorkerRepository.SHIFT_OFF);
            Repositories.workers().saveWorker(d);
        }
        Optional<ShuttleUnit> shuttle = Repositories.shuttles().findById(closed.getShuttleId());
        if (shuttle.isPresent() && ShuttleRepository.STATUS_IN_SERVICE.equals(shuttle.get().getCurrentStatus())) {
            Repositories.shuttles().updateStatus(closed.getShuttleId(), ShuttleRepository.STATUS_AVAILABLE);
        }
        return closed;
    }

    public static Optional<ShiftRecord> active(String driverId) {
        return Repositories.shifts().findActiveByDriverId(driverId);
    }

    public static ShiftRecord get(String shiftId) {
        return Repositories.shifts().findById(shiftId == null ? "" : shiftId)
                .orElseThrow(() -> new ApiException(404, "Shift not found"));
    }

    public static List<ShiftRecord> history(String driverId) {
        if (driverId != null && !driverId.isEmpty()) {
            return Repositories.shifts().findByDriverId(driverId);
        }
        List<ShiftRecord> all = Repositories.shifts().getAllShifts();
        all.sort(Comparator.comparingLong(ShiftRecord::getStartTime).reversed());
        return all;
    }

    public static List<Map<String, Object>> toJson(List<ShiftRecord> shifts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ShiftRecord s : shifts) {
            out.add(toJson(s));
        }
        return out;
    }

    public static Map<String, Object> toJson(ShiftRecord s) {
        long now = System.currentTimeMillis();
        long duration = s.getDurationMillis(now);
        double hours = duration / 3600000.0;
        String driverName = Repositories.workers().findById(s.getDriverId())
                .map(Worker::getFullName).orElse("");
        return Json.obj(
                "shiftId", s.getShiftId(),
                "driverId", s.getDriverId(),
                "driverName", driverName,
                "shuttleId", s.getShuttleId(),
                "terminal", s.getTerminal(),
                "startTime", s.getStartTime(),
                "endTime", s.getEndTime(),
                "status", s.getStatus(),
                "distanceKm", s.getDistanceKm(),
                "stops", s.getStops(),
                "durationMs", duration,
                "avgSpeedKmh", hours > 0 ? s.getDistanceKm() / hours : 0,
                "route", s.getRoute());
    }

    public static String getWorkerShift(String workerId) {
        return active(workerId).map(ShiftRecord::getShiftId).orElse("No active shift");
    }

    public static boolean isClockedIn(String workerId) {
        return workerId != null && active(workerId).isPresent();
    }
}
