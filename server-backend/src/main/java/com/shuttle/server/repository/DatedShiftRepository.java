package com.shuttle.server.repository;

import com.shuttle.server.io.CsvTable;
import com.shuttle.server.storage.CsvStorage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Planned assignments, separate from weekly rules and recorded shifts. */
public final class DatedShiftRepository {
    public static final String FILE_NAME = "dated-shifts.csv";
    private static final List<String> HEADER = List.of("id", "terminalId", "employeeId", "shuttleId",
            "shiftDate", "startTime", "endTime", "notesBase64");

    public record Assignment(String id, String terminalId, String employeeId, String shuttleId,
                             LocalDate shiftDate, LocalTime startTime, LocalTime endTime, String notesBase64) { }

    private final Path path;
    private final List<Assignment> assignments = new ArrayList<>();

    public DatedShiftRepository(Path path) throws IOException {
        this.path = path;
        if (!CsvStorage.exists(path)) return;
        CsvTable table = CsvTable.load(path, HEADER);
        Set<String> ids = new HashSet<>();
        for (CsvTable.Row row : table.getRows()) {
            try {
                Assignment entry = new Assignment(row.get("id"), row.get("terminalId"), row.get("employeeId"),
                        row.get("shuttleId"), LocalDate.parse(row.get("shiftDate")),
                        LocalTime.parse(row.get("startTime")), LocalTime.parse(row.get("endTime")),
                        row.get("notesBase64"));
                if (entry.id().isEmpty() || !ids.add(entry.id()) || entry.terminalId().isEmpty()
                        || entry.employeeId().isEmpty() || entry.shuttleId().isEmpty()
                        || !entry.endTime().isAfter(entry.startTime())) {
                    throw new IllegalArgumentException("Invalid scheduled assignment");
                }
                assignments.add(entry);
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid dated-shifts.csv row " + row.lineNumber(), invalid);
            }
        }
    }

    public List<Assignment> all() {
        return List.copyOf(assignments);
    }

    /** Call under the shared repository lock so availability and saving form one operation. */
    public void add(Assignment entry) throws IOException {
        CsvTable table = new CsvTable(HEADER);
        for (Assignment saved : assignments) append(table, saved);
        append(table, entry);
        table.save(path);
        assignments.add(entry);
    }

    private static void append(CsvTable table, Assignment entry) {
        table.addRow(entry.id(), entry.terminalId(), entry.employeeId(), entry.shuttleId(),
                entry.shiftDate().toString(), entry.startTime().toString(), entry.endTime().toString(),
                entry.notesBase64());
    }
}
