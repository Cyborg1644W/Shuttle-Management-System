package com.shuttle.server.service;

import com.shuttle.common.Role;
import com.shuttle.common.ShuttleUnit;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Csv;
import com.shuttle.server.Repositories;
import com.shuttle.server.repository.DatedShiftRepository.Assignment;
import com.shuttle.server.repository.ScheduleRepository;
import com.shuttle.server.repository.ShuttleRepository;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.shuttle.server.Csv.str;

public final class DatedShiftService {
    private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Manila");
    private static final LocalTime MORNING = LocalTime.of(6, 0);
    private static final LocalTime AFTERNOON = LocalTime.of(14, 0);
    private static final LocalTime NIGHT = LocalTime.of(22, 0);

    private DatedShiftService() { }

    public static List<Map<String, Object>> list(Worker actor, String fromValue, String toValue) {
        LocalDate from = date(fromValue);
        LocalDate to = date(toValue);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 62) {
            throw new ApiException(400, "Select a valid schedule range of up to 63 days");
        }
        String terminal = TerminalService.terminalId(actor);
        List<Map<String, Object>> result = new ArrayList<>();
        if (terminal.isEmpty()) return result;
        Repositories.datedShifts().all().stream()
                .filter(entry -> terminal.equals(entry.terminalId()))
                .filter(entry -> actor.getRole() == Role.OPERATOR || actor.getEmployeeID().equals(entry.employeeId()))
                .filter(entry -> !entry.shiftDate().isBefore(from) && !entry.shiftDate().isAfter(to))
                .sorted(Comparator.comparing(Assignment::shiftDate).thenComparing(Assignment::startTime))
                .forEach(entry -> result.add(toRow(entry)));
        return result;
    }

    public static Map<String, Object> create(Worker actor, Map<String, Object> body) throws IOException {
        TerminalService.requireJoined(actor);
        String terminal = TerminalService.terminalId(actor);
        Worker driver = WorkerService.get(str(body, "employeeId"));
        TerminalService.requireWorker(actor, driver.getEmployeeID());
        if (driver.getRole() != Role.DRIVER || WorkerService.PENDING_HASH.equals(driver.getPasswordHash())) {
            throw new ApiException(400, "Select a registered driver from your terminal");
        }
        String requestedShuttle = str(body, "shuttleId").trim();
        ShuttleUnit shuttle = Repositories.shuttles().findById(requestedShuttle)
                .orElseThrow(() -> new ApiException(404, "Shuttle unit no longer exists"));
        String shuttleId = shuttle.getShuttleId();
        TerminalService.requireShuttle(actor, shuttleId);
        if (ShuttleRepository.STATUS_MAINTENANCE.equals(shuttle.getCurrentStatus())
                || ShuttleRepository.STATUS_OFFLINE.equals(shuttle.getCurrentStatus())) {
            throw new ApiException(409, "This shuttle is unavailable. Select another unit.");
        }

        LocalDate day = date(str(body, "shiftDate"));
        if (day.isBefore(LocalDate.now(SCHEDULE_ZONE))) {
            throw new ApiException(400, "Choose today or a future date in Philippine time");
        }
        LocalTime start = time(str(body, "startTime"));
        LocalTime end = time(str(body, "endTime"));
        if (!end.isAfter(start)) throw new ApiException(400, "End Time must be after Start Time on the same date");

        String driverId = driver.getEmployeeID();
        String notes = normalizedNotes(str(body, "notesBase64"));
        String requestId = str(body, "id").trim();
        String id;
        try {
            id = requestId.isEmpty() ? UUID.randomUUID().toString() : UUID.fromString(requestId).toString();
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(400, "Invalid shift request ID");
        }
        Assignment assignment = new Assignment(id, terminal, driverId, shuttleId, day, start, end, notes);
        List<Assignment> saved = Repositories.datedShifts().all();
        for (Assignment entry : saved) {
            if (entry.id().equals(id)) {
                if (entry.equals(assignment)) return toRow(entry);
                throw new ApiException(409, "This request ID already belongs to a different shift");
            }
        }
        for (Assignment entry : saved) {
            if (!day.equals(entry.shiftDate()) || !overlaps(start, end, entry.startTime(), entry.endTime())) continue;
            if (driverId.equals(entry.employeeId())) {
                throw new ApiException(409, "This worker already has a shift during that time");
            }
            if (shuttleId.equals(entry.shuttleId())) {
                throw new ApiException(409, "This shuttle already has a shift during that time");
            }
        }
        if (overlapsWeeklyRule(driverId, day, start, end)) {
            throw new ApiException(409, "This worker has a weekly shift during that time");
        }
        Repositories.datedShifts().add(assignment);
        return toRow(assignment);
    }

    private static boolean overlapsWeeklyRule(String driverId, LocalDate date, LocalTime start, LocalTime end) {
        for (ScheduleRepository.Entry rule : Repositories.schedule().getAll()) {
            if (driverId.equals(rule.employeeId) && overlapsRule(rule, date, start, end)) return true;
        }
        return false;
    }

    static void rejectDatedConflict(ScheduleRepository.Entry rule, List<Assignment> assignments) {
        if (rule.shift < 0 || rule.shift > 2 || !rule.days.matches("[01]{7}")) {
            throw new ApiException(400, "Use a valid weekly shift and seven weekday flags");
        }
        LocalDate today = LocalDate.now(SCHEDULE_ZONE);
        for (Assignment entry : assignments) {
            if (rule.employeeId.equals(entry.employeeId()) && !entry.shiftDate().isBefore(today)
                    && overlapsRule(rule, entry.shiftDate(), entry.startTime(), entry.endTime())) {
                throw new ApiException(409, "This weekly shift conflicts with a dated assignment");
            }
        }
    }

    private static boolean overlapsRule(ScheduleRepository.Entry rule, LocalDate date, LocalTime start, LocalTime end) {
        int weekday = date.getDayOfWeek().getValue() - 1;
        int previous = (weekday + 6) % 7;
        if (rule.shift == 2 && rule.days.charAt(previous) == '1' && start.isBefore(MORNING)) return true;
        if (rule.days.charAt(weekday) != '1') return false;
        return switch (rule.shift) {
            case 0 -> overlaps(start, end, MORNING, AFTERNOON);
            case 1 -> overlaps(start, end, AFTERNOON, NIGHT);
            case 2 -> end.isAfter(NIGHT);
            default -> false;
        };
    }

    private static boolean overlaps(LocalTime start, LocalTime end, LocalTime otherStart, LocalTime otherEnd) {
        return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }

    private static LocalDate date(String value) {
        try {
            if (value != null && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return LocalDate.parse(value);
        } catch (DateTimeParseException invalid) {
            // Use the same error for malformed and impossible dates.
        }
        throw new ApiException(400, "Enter a valid shift date");
    }

    private static LocalTime time(String value) {
        try {
            if (value.matches("[0-9]{2}:[0-9]{2}")) return LocalTime.parse(value);
        } catch (DateTimeParseException invalid) {
            // Use the same error for malformed and impossible times.
        }
        throw new ApiException(400, "Select valid start and end times");
    }

    /** CSV uses one line per record. Encode the notes so line breaks survive round trips. */
    private static String normalizedNotes(String encoded) {
        if (encoded.length() > 5400) throw new ApiException(400, "Notes must be 1,000 characters or fewer");
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            String notes = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                    .replace("\r\n", "\n").replace('\r', '\n');
            if (notes.length() > 1000) throw new ApiException(400, "Notes must be 1,000 characters or fewer");
            return Base64.getEncoder().encodeToString(notes.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw new ApiException(400, "The notes could not be read");
        }
    }

    private static Map<String, Object> toRow(Assignment entry) {
        Worker driver = Repositories.workers().findById(entry.employeeId()).orElse(null);
        // A worker who moved terminals can still have a historical row in the old terminal.
        String name = driver != null && entry.terminalId().equals(TerminalService.terminalId(driver))
                ? driver.getFullName() : "";
        return Csv.obj("id", entry.id(), "employeeId", entry.employeeId(), "name", name,
                "shuttleId", entry.shuttleId(), "shiftDate", entry.shiftDate(), "startTime", entry.startTime(),
                "endTime", entry.endTime(), "notesBase64", entry.notesBase64(),
                "status", entry.shiftDate().isBefore(LocalDate.now(SCHEDULE_ZONE)) ? "Past assignment" : "Scheduled");
    }
}
