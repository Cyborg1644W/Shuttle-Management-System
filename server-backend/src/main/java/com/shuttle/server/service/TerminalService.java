package com.shuttle.server.service;

import com.shuttle.common.Driver;
import com.shuttle.common.Role;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Csv;
import com.shuttle.server.Log;
import com.shuttle.server.Repositories;
import com.shuttle.server.repository.TerminalRepository.Entry;
import com.shuttle.server.repository.WorkerRepository;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TerminalService {
    private static final long INVITE_LIFETIME_MS = 48L * 60 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private TerminalService() { }

    private static Entry find(String type, String id) {
        for (Entry e : Repositories.terminals().all()) {
            if (e.type.equals(type) && e.id.equals(id)) return e;
        }
        return null;
    }

    public static String terminalId(Worker worker) {
        synchronized (Repositories.LOCK) {
            Entry e = find(worker.getRole() == Role.OPERATOR ? "TERMINAL" : "MEMBER", worker.getEmployeeID());
            return e != null && find("TERMINAL", e.terminalId) != null ? e.terminalId : "";
        }
    }

    public static Map<String, Object> getState(Worker worker) {
        synchronized (Repositories.LOCK) {
            String id = terminalId(worker);
            return id.isEmpty()
                    ? Csv.obj("joined", false, "terminalId", "", "terminalName", "", "operatorName", "")
                    : terminalInfo(id);
        }
    }

    private static Map<String, Object> terminalInfo(String id) {
        Entry terminal = find("TERMINAL", id);
        if (terminal == null) throw new ApiException(404, "Terminal no longer exists");
        return Csv.obj("joined", true, "terminalId", terminal.id, "terminalName", terminal.name,
                "operatorName", WorkerService.get(terminal.id).getFullName());
    }

    public static Map<String, Object> create(Worker operator, String rawName) throws IOException {
        synchronized (Repositories.LOCK) {
            requireOperator(operator);
            if (!terminalId(operator).isEmpty()) throw new ApiException(409, "You already manage a terminal");
            String name = rawName == null ? "" : rawName.trim();
            if (name.length() < 2 || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) {
                throw new ApiException(400, "Enter a terminal name between 2 and 80 characters");
            }
            List<Entry> entries = Repositories.terminals().all();
            entries.add(new Entry("TERMINAL", operator.getEmployeeID(), operator.getEmployeeID(), name, 0, ""));
            Repositories.terminals().replaceAll(entries);
            Log.audit("Terminal created by " + operator.getEmployeeID());
            return getState(operator);
        }
    }

    public static Map<String, Object> invite(Worker operator) throws IOException {
        synchronized (Repositories.LOCK) {
            requireOperator(operator);
            requireJoined(operator);
            String code;
            do {
                StringBuilder value = new StringBuilder();
                for (int i = 0; i < 12; i++) value.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
                code = value.toString();
            } while (find("INVITE", code) != null);
            long expiresAt = System.currentTimeMillis() + INVITE_LIFETIME_MS;
            List<Entry> entries = Repositories.terminals().all();
            entries.removeIf(e -> e.type.equals("INVITE") && e.expiresAt <= System.currentTimeMillis());
            entries.add(new Entry("INVITE", code, terminalId(operator), "", expiresAt, ""));
            Repositories.terminals().replaceAll(entries);
            Map<String, Object> row = getState(operator);
            row.put("inviteCode", code);
            row.put("expiresAt", expiresAt);
            Log.audit("Fleet invitation created by " + operator.getEmployeeID());
            return row;
        }
    }

    private static Entry usableInvite(String rawCode) {
        String code = rawCode == null ? "" : rawCode.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        Entry invite = find("INVITE", code);
        if (invite == null || !invite.usedBy.isEmpty() || invite.expiresAt <= System.currentTimeMillis()) {
            throw new ApiException(403, "This invite code is invalid, expired or already used. Ask the operator for a new one.");
        }
        return invite;
    }

    public static Map<String, Object> preview(Worker driver, String code) {
        synchronized (Repositories.LOCK) {
            requireDriver(driver);
            return terminalInfo(usableInvite(code).terminalId);
        }
    }

    public static Map<String, Object> join(Worker driver, String code, boolean switchConfirmed) throws IOException {
        synchronized (Repositories.LOCK) {
            requireDriver(driver);
            Entry invite = usableInvite(code);
            terminalInfo(invite.terminalId); // reject orphaned invitations before changing anything
            String oldTerminal = terminalId(driver);
            if (oldTerminal.equals(invite.terminalId)) throw new ApiException(409, "You already belong to this terminal");
            requireOffShift(driver.getEmployeeID());
            if (!oldTerminal.isEmpty() && !switchConfirmed) {
                throw new ApiException(409, "Confirm leaving your current terminal before switching");
            }
            // Clear old assignments first: if a subsequent disk write fails, stale vehicles cannot be used.
            clearAssignments(driver.getEmployeeID());
            List<Entry> entries = Repositories.terminals().all();
            entries.removeIf(e -> e.type.equals("MEMBER") && e.id.equals(driver.getEmployeeID()));
            entries.removeIf(e -> e.type.equals("INVITE") && e.id.equals(invite.id));
            entries.add(new Entry("MEMBER", driver.getEmployeeID(), invite.terminalId, "", 0, ""));
            entries.add(new Entry("INVITE", invite.id, invite.terminalId, "", invite.expiresAt, driver.getEmployeeID()));
            Repositories.terminals().replaceAll(entries);
            Log.audit("Driver " + driver.getEmployeeID() + " joined terminal " + invite.terminalId);
            return getState(driver);
        }
    }

    public static boolean canAccessWorker(Worker actor, String employeeId) {
        synchronized (Repositories.LOCK) {
            if (actor.getEmployeeID().equals(employeeId)) return true;
            Worker target = Repositories.workers().findById(employeeId == null ? "" : employeeId).orElse(null);
            String terminal = terminalId(actor);
            return target != null && !terminal.isEmpty() && terminal.equals(terminalId(target));
        }
    }

    public static void requireWorker(Worker actor, String id) {
        if (!canAccessWorker(actor, id)) throw new ApiException(403, "This worker does not belong to your terminal");
    }

    public static boolean canAccessShuttle(Worker actor, String shuttleId) {
        synchronized (Repositories.LOCK) {
            Entry e = find("SHUTTLE", shuttleId);
            String terminal = terminalId(actor);
            return e != null && !terminal.isEmpty() && terminal.equals(e.terminalId);
        }
    }

    public static void requireShuttle(Worker actor, String id) {
        if (!canAccessShuttle(actor, id)) throw new ApiException(403, "This shuttle does not belong to your terminal");
    }

    public static void requireJoined(Worker actor) {
        if (terminalId(actor).isEmpty()) throw new ApiException(409,
                actor.getRole() == Role.DRIVER ? "Join a terminal using an invite code first" : "Create your terminal first");
    }

    public static void attachWorker(Worker operator, String employeeId) throws IOException {
        synchronized (Repositories.LOCK) {
            requireOperator(operator);
            requireJoined(operator);
            Worker worker = WorkerService.get(employeeId);
            requireDriver(worker);
            if (!terminalId(worker).isEmpty()) throw new ApiException(409, "This driver already belongs to a terminal");
            List<Entry> entries = Repositories.terminals().all();
            entries.add(new Entry("MEMBER", employeeId, terminalId(operator), "", 0, ""));
            Repositories.terminals().replaceAll(entries);
        }
    }

    /** A deleted ID remains reserved so its positions and shift history never cross terminal boundaries. */
    public static void requireShuttleCreationAllowed(Worker operator, String shuttleId) {
        synchronized (Repositories.LOCK) {
            requireOperator(operator);
            requireJoined(operator);
            Entry existing = find("SHUTTLE", shuttleId);
            if (existing != null && !existing.terminalId.equals(terminalId(operator))) {
                throw new ApiException(409, "This shuttle ID is reserved by another terminal");
            }
        }
    }

    public static void attachShuttle(Worker operator, String shuttleId) throws IOException {
        synchronized (Repositories.LOCK) {
            requireShuttleCreationAllowed(operator, shuttleId);
            if (find("SHUTTLE", shuttleId) != null) {
                return; // the same terminal may restore one of its deleted shuttle IDs
            }
            List<Entry> entries = Repositories.terminals().all();
            entries.add(new Entry("SHUTTLE", shuttleId, terminalId(operator), "", 0, ""));
            Repositories.terminals().replaceAll(entries);
        }
    }

    public static void detachShuttle(Worker operator, String shuttleId) {
        synchronized (Repositories.LOCK) {
            requireOperator(operator);
            requireShuttle(operator, shuttleId);
            // Keep the ownership reservation after the vehicle row is deleted. Tracking positions
            // and historical shifts still reference this ID, so another terminal cannot reuse it.
        }
    }

    public static void detachWorker(Worker operator, String employeeId) throws IOException {
        synchronized (Repositories.LOCK) {
            requireOperator(operator);
            requireWorker(operator, employeeId);
            requireDriver(WorkerService.get(employeeId));
            requireOffShift(employeeId);
            clearAssignments(employeeId);
            List<Entry> entries = Repositories.terminals().all();
            entries.removeIf(e -> e.type.equals("MEMBER") && e.id.equals(employeeId));
            Repositories.terminals().replaceAll(entries);
            Log.audit("Driver " + employeeId + " removed from terminal by " + operator.getEmployeeID());
        }
    }

    private static void requireOffShift(String employeeId) {
        Driver driver = (Driver) WorkerService.get(employeeId);
        if (driver.getActiveShiftId() != null || Repositories.shifts().findAllActive().stream()
                .anyMatch(s -> s.getDriverId().equals(employeeId))) {
            throw new ApiException(409, "Finish your active shift before changing terminals");
        }
    }

    private static void clearAssignments(String employeeId) throws IOException {
        Driver driver = (Driver) WorkerService.get(employeeId);
        driver.setAssignedShuttleId(null);
        driver.setAssignedTerminal(null);
        driver.setShiftStatus(WorkerRepository.SHIFT_OFF);
        Repositories.workers().saveWorker(driver);
        Repositories.schedule().removeEmployee(employeeId);
    }

    private static void requireOperator(Worker worker) {
        if (worker.getRole() != Role.OPERATOR) throw new ApiException(403, "Operators only");
    }

    private static void requireDriver(Worker worker) {
        if (!(worker instanceof Driver)) throw new ApiException(403, "Only driver accounts can join a terminal");
    }
}
