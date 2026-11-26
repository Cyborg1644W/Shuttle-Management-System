package com.shuttle.server.service;

import com.shuttle.common.Driver;
import com.shuttle.common.Operator;
import com.shuttle.common.Role;
import com.shuttle.common.ShiftRecord;
import com.shuttle.common.ValidationUtil;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Auth;
import com.shuttle.server.Crypto;
import com.shuttle.server.Csv;
import com.shuttle.server.Log;
import com.shuttle.server.Repositories;
import com.shuttle.server.repository.WorkerRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.shuttle.server.Csv.has;
import static com.shuttle.server.Csv.str;

public class WorkerService {

    public static final String PENDING_HASH = "PENDING";
    private static final String PENDING_DOMAIN = "@fleetflow.invalid";


    public static Worker get(String employeeId) {
        return Repositories.workers().findById(employeeId == null ? "" : employeeId.trim())
                .orElseThrow(() -> new ApiException(404, "Worker not found"));
    }

    public static List<Map<String, Object>> listAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Worker w : Repositories.workers().getAllWorkers()) {
            out.add(toPublic(w));
        }
        return out;
    }

    public static Map<String, Object> toPublic(Worker w) {
        boolean pending = PENDING_HASH.equals(w.getPasswordHash());
        String email = w.getEmail() == null || w.getEmail().endsWith(PENDING_DOMAIN) ? "" : w.getEmail();
        Map<String, Object> m = Csv.obj(
                "employeeId", w.getEmployeeID(),
                "fullName", w.getFullName(),
                "email", email,
                "phone", w.getPhone(),
                "phoneDisplay", formatPhone(w.getPhone()),
                "role", w.getRole().name(),
                "employmentStatus", w.getEmploymentStatus(),
                "shiftStatus", w.getShiftStatus(),
                "pending", pending);
        if (w instanceof Driver) {
            Driver d = (Driver) w;
            m.put("shuttleId", nz(d.getAssignedShuttleId()));
            m.put("terminal", TerminalService.getState(w).get("terminalName"));
            m.put("activeShiftId", nz(d.getActiveShiftId()));
            m.put("licenseNumber", nz(d.getLicenseNumber()));
            m.put("value1", nz(d.getAssignedShuttleId()));
            m.put("value2", TerminalService.getState(w).get("terminalName"));
        } else {
            Operator o = (Operator) w;
            m.put("zone", TerminalService.getState(w).get("terminalName"));
            m.put("shiftTime", nz(o.getShiftTime()));
            m.put("value1", TerminalService.getState(w).get("terminalName"));
            m.put("value2", nz(o.getShiftTime()));
        }
        return m;
    }

    public static Worker createPending(String fullName, String phone, Role role, String requestedId) throws IOException {
        synchronized (Repositories.LOCK) {
            String name = requireName(fullName);
            String normalizedPhone = normalizePhone(phone);
            String id = requestedId == null || requestedId.trim().isEmpty()
                    ? Repositories.workers().nextEmployeeId()
                    : cleanEmployeeId(requestedId);
            if (Repositories.workers().existsById(id)) {
                throw new ApiException(409, "This employee ID is already used");
            }

            Worker w = blank(role);
            w.setEmployeeID(id);
            w.setFullName(name);
            w.setEmail(id.toLowerCase(Locale.ROOT) + PENDING_DOMAIN);
            w.setPasswordHash(PENDING_HASH);
            w.setPhone(normalizedPhone);
            Repositories.workers().saveWorker(w);
            Log.audit("Worker added: " + id + " (" + role + ")");
            return w;
        }
    }

    public static Worker register(Role role, String fullName, String email, String phone, String password,
                                  String licenseNumber, String requestedId) throws IOException {
        synchronized (Repositories.LOCK) {
            String name = requireName(fullName);
            String normalizedPhone = normalizePhone(phone);
            if (normalizedPhone.isEmpty()) {
                throw new ApiException(400, "Please enter your mobile number");
            }
            String cleanEmail = email == null ? "" : email.trim();
            if (!ValidationUtil.isValidEmail(cleanEmail)) {
                throw new ApiException(400, "Enter a valid email address");
            }
            if (password == null || password.length() < 6) {
                throw new ApiException(400, "Password must be at least 6 characters");
            }
            if (Repositories.workers().existsByEmail(cleanEmail)) {
                throw new ApiException(409, "This email is already registered");
            }

            // Account registration is independent of operator-created staff records.
            // Joining a terminal requires accepting its invitation, never just matching a phone number.
            String id = requestedId == null || requestedId.trim().isEmpty()
                    ? Repositories.workers().nextEmployeeId()
                    : cleanEmployeeId(requestedId);
            if (Repositories.workers().existsById(id)) {
                throw new ApiException(409, "This employee ID is already used");
            }
            if (isPhoneTaken(normalizedPhone)) {
                throw new ApiException(409, "This mobile number is already registered");
            }
            Worker w = blank(role);
            w.setEmployeeID(id);
            w.setFullName(name);
            w.setEmail(cleanEmail);
            w.setPasswordHash(Crypto.hashPassword(password));
            w.setPhone(normalizedPhone);
            if (w instanceof Driver && licenseNumber != null && !licenseNumber.trim().isEmpty()) {
                ((Driver) w).setLicenseNumber(licenseNumber.trim());
            }
            Repositories.workers().saveWorker(w);
            return w;
        }
    }

    private static boolean isPhoneTaken(String phone) {
        for (Worker w : Repositories.workers().getAllWorkers()) {
            if (!PENDING_HASH.equals(w.getPasswordHash()) && phone.equals(w.getPhone())) {
                return true;
            }
        }
        return false;
    }

    public static Worker update(String employeeId, Map<String, Object> body, boolean byOperator) throws IOException {
        synchronized (Repositories.LOCK) {
            Worker w = get(employeeId);
            String id = w.getEmployeeID();

            if (has(body, "employeeId") && !str(body, "employeeId").trim().equals(id)) {
                throw new ApiException(400, "Employee IDs cannot be changed after account creation");
            }

            if (has(body, "fullName")) {
                w.setFullName(requireName(str(body, "fullName")));
            }
            if (has(body, "phone")) {
                w.setPhone(normalizePhone(str(body, "phone")));
            }
            if (has(body, "email")) {
                String email = str(body, "email").trim();
                if (!ValidationUtil.isValidEmail(email)) {
                    throw new ApiException(400, "Enter a valid email address");
                }
                w.setEmail(email);
            }
            Repositories.workers().saveWorker(w);
            return w;
        }
    }

    public static Worker assign(String employeeId, Map<String, Object> body) throws IOException {
        synchronized (Repositories.LOCK) {
            Worker w = get(employeeId);

            if (w instanceof Driver) {
                Driver d = (Driver) w;
                String shuttleKey = has(body, "shuttleId") ? "shuttleId" : has(body, "value1") ? "value1" : null;
                String terminalKey = has(body, "terminal") ? "terminal" : has(body, "value2") ? "value2" : null;

                if (shuttleKey != null) {
                    String raw = str(body, shuttleKey).trim();
                    String shuttleId = raw.isEmpty() || raw.equalsIgnoreCase("Unassigned") ? null : raw;
                    if (shuttleId != null) {
                        shuttleId = Repositories.shuttles().findById(shuttleId)
                                .orElseThrow(() -> new ApiException(400, "Unknown shuttle: " + raw)).getShuttleId();
                    }
                    boolean changed = shuttleId == null ? d.getAssignedShuttleId() != null
                            : !shuttleId.equals(d.getAssignedShuttleId());
                    if (changed && d.getActiveShiftId() != null) {
                        throw new ApiException(409, "End the driver's shift before changing the vehicle");
                    }
                    if (changed && shuttleId != null) {
                        var notices = new java.util.ArrayList<>(d.getVehicleNotices());
                        notices.add(new Driver.VehicleNotice(java.util.UUID.randomUUID().toString(),
                                shuttleId, System.currentTimeMillis(), false));
                        d.setVehicleNotices(notices);
                    }
                    d.setAssignedShuttleId(shuttleId);
                }
                String name = String.valueOf(TerminalService.getState(w).get("terminalName"));
                d.setAssignedTerminal(name.isEmpty() ? null : name);
                if (terminalKey != null) {
                    if (!str(body, terminalKey).trim().equals(name)) {
                        throw new ApiException(400, "Drivers switch terminals by accepting an invitation");
                    }
                    d.setAssignedTerminal(name);
                }
            } else {
                Operator o = (Operator) w;
                String zoneKey = has(body, "zone") ? "zone" : has(body, "value1") ? "value1" : null;
                String timeKey = has(body, "shiftTime") ? "shiftTime" : has(body, "value2") ? "value2" : null;
                if (zoneKey != null) {
                    String raw = str(body, zoneKey).trim();
                    o.setTerminalZone(raw.isEmpty() || raw.equalsIgnoreCase("Unassigned") ? null : raw);
                }
                if (timeKey != null) {
                    String raw = str(body, timeKey).trim();
                    o.setShiftTime(raw.isEmpty() || raw.equalsIgnoreCase("Not set") ? null : raw);
                }
            }
            Repositories.workers().saveWorker(w);
            return w;
        }
    }

    public static Worker setStatus(String employeeId, String rawStatus) throws IOException {
        synchronized (Repositories.LOCK) {
            Worker w = get(employeeId);
            String status = normalizeStatus(rawStatus);

            if (w instanceof Driver) {
                boolean hasShift = ((Driver) w).getActiveShiftId() != null;
                if (status.equals(WorkerRepository.SHIFT_OFF) && hasShift) {
                    throw new ApiException(409, "End the shift to go off duty");
                }
                if (!status.equals(WorkerRepository.SHIFT_OFF) && !hasShift) {
                    throw new ApiException(409, "Start a shift first");
                }
            }
            w.setShiftStatus(status);
            Repositories.workers().saveWorker(w);
            return w;
        }
    }

    public static void remove(String employeeId) throws IOException {
        synchronized (Repositories.LOCK) {
            Worker w = get(employeeId);
            String id = w.getEmployeeID();
            if (w instanceof Driver && ((Driver) w).getActiveShiftId() != null) {
                ShiftService.forceClose(id, "TIMED_OUT");
            }
            Auth.endSessionsFor(id);
            Repositories.schedule().removeEmployee(id);
            Repositories.workers().deleteWorker(id);
            Log.audit("Worker removed: " + id);
        }
    }


    public static String normalizeStatus(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        switch (s) {
            case "ON_SHIFT":
            case "ACTIVE":
                return WorkerRepository.SHIFT_ON;
            case "ON_BREAK":
                return WorkerRepository.SHIFT_BREAK;
            case "OFF_SHIFT":
            case "OFF_DUTY":
                return WorkerRepository.SHIFT_OFF;
            default:
                throw new ApiException(400, "Status must be ON_SHIFT, ON_BREAK or OFF_SHIFT");
        }
    }

    public static String normalizePhone(String raw) {
        String p = raw == null ? "" : raw.replaceAll("[^0-9+]", "");
        if (p.isEmpty()) {
            return "";
        }
        if (p.startsWith("09") && p.length() == 11) {
            p = "+63" + p.substring(1);
        } else if (p.length() == 10 && p.startsWith("9")) {
            p = "+63" + p;
        } else if (p.startsWith("63") && p.length() == 12) {
            p = "+" + p;
        }
        if (!p.matches("\\+?[0-9]{9,15}")) {
            throw new ApiException(400, "Enter a valid mobile number");
        }
        return p;
    }

    public static String formatPhone(String phone) {
        if (phone != null && phone.matches("\\+63[0-9]{10}")) {
            return "+63 " + phone.substring(3, 6) + " " + phone.substring(6, 9) + " " + phone.substring(9);
        }
        return phone == null ? "" : phone;
    }

    private static Worker blank(Role role) {
        return role == Role.OPERATOR ? new Operator() : new Driver();
    }

    private static String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() < 2) {
            throw new ApiException(400, "Please enter the person's full name");
        }
        return n;
    }

    private static String cleanEmployeeId(String raw) {
        String id = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (id.isEmpty() || id.indexOf(' ') >= 0 || id.contains(",") || id.contains(";")) {
            throw new ApiException(400, "Employee ID can't be empty or contain spaces");
        }
        return id;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
