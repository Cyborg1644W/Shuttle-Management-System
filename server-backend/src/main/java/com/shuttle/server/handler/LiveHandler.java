package com.shuttle.server.handler;

import com.shuttle.common.ShuttleUnit;
import com.shuttle.common.ShiftRecord;
import com.shuttle.common.Worker;
import com.shuttle.common.Driver;
import com.shuttle.server.service.WorkerService;
import com.shuttle.server.repository.WorkerRepository;
import com.shuttle.server.Csv;
import com.shuttle.server.ApiException;
import com.shuttle.server.Repositories;
import com.shuttle.server.service.TerminalService;
import com.shuttle.server.repository.ShuttleRepository;
import com.shuttle.server.service.FleetService;
import com.shuttle.server.service.TrackingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public class LiveHandler extends BaseHandler {

    @Override
    protected Response process(Request r) throws Exception {
        synchronized (Repositories.LOCK) {
            return processScoped(r);
        }
    }

    private Response processScoped(Request r) throws Exception {
        if (!"live".equals(r.seg(0)) || (r.seg(1) != null && !"fleet".equals(r.seg(1))) || r.seg(2) != null) {
            throw new ApiException(404, "Endpoint Not Found");
        }
        if (!r.is("GET")) {
            throw new ApiException(405, "Method Not Allowed");
        }
        if ("fleet".equals(r.seg(1))) {
            r.requireOperator();
            return ok(monitor(r.worker(), r.query));
        }
        boolean all = "true".equalsIgnoreCase(r.query.get("all"));

        List<Map<String, Object>> out = new ArrayList<>();
        for (ShuttleUnit s : Repositories.shuttles().getAllShuttles()) {
            if (!TerminalService.canAccessShuttle(r.worker(), s.getShuttleId())) {
                continue;
            }
            boolean inService = ShuttleRepository.STATUS_IN_SERVICE.equals(s.getCurrentStatus());
            if (TrackingService.get(s.getShuttleId()) != null && (all || inService)) {
                out.add(FleetService.toRow(s));
            }
        }
        return ok(out);
    }

    /** The first row identifies an authoritative snapshot even if every shift has ended. */
    static List<Map<String, Object>> monitor(Worker operator, Map<String, String> cursors) {
        Map<String, Object> terminal = TerminalService.getState(operator);
        Map<String, Object> header = Csv.obj("type", "fleet", "terminalId", terminal.get("terminalId"),
                "terminal", terminal.get("terminalName"), "terminalLatitude", terminal.get("terminalLatitude"),
                "terminalLongitude", terminal.get("terminalLongitude"), "shiftId", "", "shuttleId", "",
                "plateNumber", "", "driverName", "", "positionTime", "", "speedKmh", "", "bearing", "", "trail", "");
        int total = 0, available = 0, operating = 0;
        for (ShuttleUnit unit : Repositories.shuttles().getAllShuttles()) {
            if (!TerminalService.canAccessShuttle(operator, unit.getShuttleId())) continue;
            total++;
            if (ShuttleRepository.STATUS_AVAILABLE.equals(unit.getCurrentStatus())) available++;
            if (ShuttleRepository.STATUS_IN_SERVICE.equals(unit.getCurrentStatus())) operating++;
        }
        int onDuty = 0;
        for (Worker worker : Repositories.workers().getAllWorkers()) {
            if (worker instanceof Driver && TerminalService.canAccessWorker(operator, worker.getEmployeeID())
                    && WorkerRepository.SHIFT_ON.equals(WorkerService.normalizeStatus(worker.getShiftStatus()))) onDuty++;
        }
        header.put("driversActive", onDuty);
        header.put("totalUnits", total);
        header.put("availableUnits", available);
        header.put("operatingUnits", operating);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(header);
        for (ShiftRecord shift : Repositories.shifts().findAllActive()) {
            String id = shift.getShuttleId();
            if (!TerminalService.canAccessShuttle(operator, id)
                    || !TerminalService.canAccessWorker(operator, shift.getDriverId())) continue;
            Map<String, Object> row = new LinkedHashMap<>(header);
            row.put("type", "unit");
            row.put("shiftId", shift.getShiftId());
            row.put("shuttleId", id);
            row.put("plateNumber", Repositories.shuttles().findById(id).map(ShuttleUnit::getPlateNumber).orElse(""));
            row.put("driverName", Repositories.workers().findById(shift.getDriverId()).map(Worker::getFullName).orElse(""));
            row.put("terminal", shift.getTerminal());
            TrackingService.Position fix = TrackingService.get(id);
            long after;
            try { after = Long.parseLong(cursors.getOrDefault(shift.getShiftId(), "0")); }
            catch (NumberFormatException e) { throw new ApiException(400, "Invalid telemetry timestamp"); }
            if (after < 0) throw new ApiException(400, "Invalid telemetry timestamp");
            if (fix != null && shift.getShiftId().equals(fix.shiftId)) {
                row.put("positionTime", fix.deviceTime);
                row.put("speedKmh", fix.speedKmh);
                row.put("bearing", Double.isFinite(fix.bearing) ? fix.bearing : "");
                row.put("trail", TrackingService.trailAfter(fix, after));
            }
            rows.add(row);
        }
        header.put("activeShifts", rows.size() - 1);
        return rows;
    }
}
