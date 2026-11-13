package com.shuttle.server.service;

import com.shuttle.common.Driver;
import com.shuttle.common.Operator;
import com.shuttle.common.ShiftRecord;
import com.shuttle.common.ShuttleUnit;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Csv;
import com.shuttle.server.Repositories;
import com.shuttle.server.repository.ScheduleRepository;
import com.shuttle.server.repository.ShuttleRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.shuttle.server.Csv.str;

public class ReportService {

    public static Map<String, Object> dashboard() {
        int active = 0;
        int inactive = 0;
        int maintenance = 0;
        List<ShuttleUnit> shuttles = Repositories.shuttles().getAllShuttles();
        for (ShuttleUnit s : shuttles) {
            String st = s.getCurrentStatus();
            if (ShuttleRepository.STATUS_IN_SERVICE.equals(st)) {
                active++;
            } else if (ShuttleRepository.STATUS_MAINTENANCE.equals(st)) {
                maintenance++;
            } else {
                inactive++;
            }
        }

        int onDuty = 0;
        int drivers = 0;
        int driversActive = 0;
        int operators = 0;
        List<Worker> workers = Repositories.workers().getAllWorkers();
        for (Worker w : workers) {
            boolean online = WorkerService.normalizeStatus(w.getShiftStatus()).equals("ON_SHIFT");
            if (online) {
                onDuty++;
            }
            if (w instanceof Driver) {
                drivers++;
                if (online) {
                    driversActive++;
                }
            } else {
                operators++;
            }
        }

        int finished = 0;
        for (ShiftRecord s : Repositories.shifts().getAllShifts()) {
            if (!s.isActive()) {
                finished++;
            }
        }

        return Csv.obj(
                "totalShifts", finished,
                "activeShifts", Repositories.shifts().findAllActive().size(),
                "totalUnits", shuttles.size(),
                "activeUnits", active,
                "inactiveUnits", inactive,
                "maintenanceUnits", maintenance,
                "staffTotal", workers.size(),
                "staffOnDuty", onDuty,
                "drivers", drivers,
                "driversActive", driversActive,
                "operators", operators,
                "standby", workers.size() - onDuty);
    }

    public static List<Map<String, Object>> schedule() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ScheduleRepository.Entry e : Repositories.schedule().getAll()) {
            Optional<Worker> found = Repositories.workers().findById(e.employeeId);
            if (!found.isPresent()) {
                continue;
            }
            Worker w = found.get();
            String place = w instanceof Driver
                    ? nz(((Driver) w).getAssignedTerminal())
                    : nz(((Operator) w).getTerminalZone());
            out.add(Csv.obj("employeeId", w.getEmployeeID(), "name", w.getFullName(),
                    "role", w.getRole().name(), "place", place, "shift", e.shift, "days", e.days));
        }
        return out;
    }

    public static List<Map<String, Object>> replaceSchedule(List<Map<String, Object>> items) throws IOException {
        synchronized (Repositories.LOCK) {
            List<ScheduleRepository.Entry> fresh = new ArrayList<>();
            for (Map<String, Object> m : items) {
                String id = str(m, "employeeId").trim();
                if (!Repositories.workers().existsById(id)) {
                    throw new ApiException(400, "Unknown employee: " + id);
                }
                fresh.add(new ScheduleRepository.Entry(id, (int) Csv.lng(m, "shift"), str(m, "days")));
            }
            Repositories.schedule().replaceAll(fresh);
            return schedule();
        }
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
