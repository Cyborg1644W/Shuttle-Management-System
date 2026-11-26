package com.shuttle.server.repository;

import com.shuttle.server.io.CsvTable;
import com.shuttle.server.storage.CsvStorage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Planned assignments and their notification state are written together. */
public final class DatedShiftRepository {
    public static final String FILE_NAME = "dated-shifts.txt";
    private static final List<String> HEADER = List.of("id", "terminalId", "employeeId", "shuttleId",
            "shiftDate", "startTime", "endTime", "notesBase64", "notifiedAt", "readAt", "removed");
    private static final List<String> REQUIRED_HEADER = HEADER.subList(0, 8);

    public record Assignment(String id, String terminalId, String employeeId, String shuttleId,
                             LocalDate shiftDate, LocalTime startTime, LocalTime endTime, String notesBase64) { }

    public record StoredAssignment(Assignment assignment, long notifiedAt, long readAt, boolean removed) { }

    private final Path path;
    private Map<String, StoredAssignment> assignments = new LinkedHashMap<>();

    public DatedShiftRepository(Path path) throws IOException {
        this.path = path;
        if (!CsvStorage.exists(path)) return;
        for (CsvTable.Row row : CsvTable.load(path, REQUIRED_HEADER).getRows()) {
            try {
                Assignment entry = new Assignment(row.get("id"), row.get("terminalId"), row.get("employeeId"),
                        row.get("shuttleId"), LocalDate.parse(row.get("shiftDate")),
                        LocalTime.parse(row.get("startTime")), LocalTime.parse(row.get("endTime")),
                        row.get("notesBase64"));
                if (entry.id().isEmpty() || assignments.containsKey(entry.id()) || entry.terminalId().isEmpty()
                        || entry.employeeId().isEmpty() || entry.shuttleId().isEmpty()
                        || !entry.endTime().isAfter(entry.startTime())) {
                    throw new IllegalArgumentException("Invalid scheduled assignment");
                }
                assignments.put(entry.id(), new StoredAssignment(entry,
                        Long.parseLong(row.getOptional("notifiedAt", "0")),
                        Long.parseLong(row.getOptional("readAt", "0")),
                        Boolean.parseBoolean(row.getOptional("removed", "false"))));
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid dated-shifts.txt row " + row.lineNumber(), invalid);
            }
        }
    }

    public List<Assignment> all() {
        return assignments.values().stream().filter(entry -> !entry.removed()).map(StoredAssignment::assignment).toList();
    }

    public StoredAssignment find(String id) {
        return assignments.get(id);
    }

    public List<StoredAssignment> notifications(String employeeId) {
        return assignments.values().stream().filter(entry -> entry.notifiedAt() > 0
                && employeeId.equals(entry.assignment().employeeId())).toList();
    }

    /** Called under the shared repository lock, including validation and response serialization. */
    public void add(Assignment entry) throws IOException {
        store(new StoredAssignment(entry, System.currentTimeMillis(), 0, false));
    }

    public void remove(StoredAssignment entry) throws IOException {
        if (!entry.removed()) store(new StoredAssignment(entry.assignment(), entry.notifiedAt(), entry.readAt(), true));
    }

    public void markRead(StoredAssignment entry) throws IOException {
        if (entry.readAt() == 0) {
            store(new StoredAssignment(entry.assignment(), entry.notifiedAt(), System.currentTimeMillis(), entry.removed()));
        }
    }

    private void store(StoredAssignment updated) throws IOException {
        Map<String, StoredAssignment> next = new LinkedHashMap<>(assignments);
        next.put(updated.assignment().id(), updated);
        CsvTable table = new CsvTable(HEADER);
        for (StoredAssignment row : next.values()) {
            Assignment entry = row.assignment();
            table.addRow(entry.id(), entry.terminalId(), entry.employeeId(), entry.shuttleId(),
                    entry.shiftDate().toString(), entry.startTime().toString(), entry.endTime().toString(),
                    entry.notesBase64(), String.valueOf(row.notifiedAt()), String.valueOf(row.readAt()),
                    String.valueOf(row.removed()));
        }
        table.save(path);
        assignments = next;
    }
}
