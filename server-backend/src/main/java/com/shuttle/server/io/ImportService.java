package com.shuttle.server.io;

import com.shuttle.common.ShuttleUnit;
import com.shuttle.common.Worker;
import com.shuttle.server.repository.ShuttleRepository;
import com.shuttle.server.repository.WorkerRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class ImportService {

    public static final class ImportResult {
        private final String source;
        private int rowsRead;
        private int imported;
        private int skippedExisting;
        private final List<String> errors = new ArrayList<>();

        private ImportResult(String source) {
            this.source = source;
        }

        public String getSource() { return source; }
        public int getRowsRead() { return rowsRead; }
        public int getImported() { return imported; }
        public int getSkippedExisting() { return skippedExisting; }
        public List<String> getErrors() { return Collections.unmodifiableList(errors); }
        public boolean hasErrors() { return !errors.isEmpty(); }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s: read=%d imported=%d skipped(existing)=%d errors=%d",
                    source, rowsRead, imported, skippedExisting, errors.size());
        }
    }

    private final WorkerRepository workers;
    private final ShuttleRepository shuttles;

    public ImportService(WorkerRepository workers, ShuttleRepository shuttles) {
        this.workers = Objects.requireNonNull(workers, "workers");
        this.shuttles = Objects.requireNonNull(shuttles, "shuttles");
    }


    public List<ImportResult> bootstrapIfEmpty(Path importDir) throws IOException {
        List<ImportResult> results = new ArrayList<>();
        Path workersFile = importDir.resolve(WorkerRepository.FILE_NAME);
        Path shuttlesFile = importDir.resolve(ShuttleRepository.FILE_NAME);
        if (workers.count() == 0 && Files.exists(workersFile)) {
            results.add(importWorkers(workersFile));
        }
        if (shuttles.count() == 0 && Files.exists(shuttlesFile)) {
            results.add(importShuttles(shuttlesFile));
        }
        return results;
    }

    public List<ImportResult> importAll(Path importDir) throws IOException {
        List<ImportResult> results = new ArrayList<>();
        results.add(importWorkers(importDir.resolve(WorkerRepository.FILE_NAME)));
        results.add(importShuttles(importDir.resolve(ShuttleRepository.FILE_NAME)));
        return results;
    }

    public ImportResult importWorkers(Path csvFile) throws IOException {
        ImportResult result = new ImportResult(csvFile.getFileName().toString());
        CsvTable table = CsvTable.load(csvFile, WorkerRepository.HEADER); // header problems -> exception

        List<Worker> toSave = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> seenEmails = new HashSet<>();

        for (CsvTable.Row row : table.getRows()) {
            result.rowsRead++;
            Worker w;
            try {
                w = WorkerRepository.fromRow(row);
            } catch (IllegalArgumentException e) {
                result.errors.add("line " + row.lineNumber() + ": " + e.getMessage());
                continue;
            }
            String email = w.getEmail().toLowerCase(Locale.ROOT);
            if (workers.existsById(w.getEmployeeID()) || workers.existsByEmail(w.getEmail())) {
                result.skippedExisting++;
            } else if (!seenIds.add(w.getEmployeeID()) || !seenEmails.add(email)) {
                result.errors.add("line " + row.lineNumber() + ": duplicate employeeID or email inside the file");
            } else {
                toSave.add(w);
            }
        }
        if (!toSave.isEmpty()) {
            workers.saveAll(toSave);
            result.imported = toSave.size();
        }
        return result;
    }

    public ImportResult importShuttles(Path csvFile) throws IOException {
        ImportResult result = new ImportResult(csvFile.getFileName().toString());
        CsvTable table = CsvTable.load(csvFile, ShuttleRepository.HEADER);

        List<ShuttleUnit> toSave = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> seenPlates = new HashSet<>();

        for (CsvTable.Row row : table.getRows()) {
            result.rowsRead++;
            ShuttleUnit s;
            try {
                s = ShuttleRepository.fromRow(row);
            } catch (IllegalArgumentException e) {
                result.errors.add("line " + row.lineNumber() + ": " + e.getMessage());
                continue;
            }
            String plate = s.getPlateNumber().toLowerCase(Locale.ROOT);
            if (shuttles.existsById(s.getShuttleId()) || shuttles.findByPlateNumber(s.getPlateNumber()).isPresent()) {
                result.skippedExisting++;
            } else if (!seenIds.add(s.getShuttleId()) || !seenPlates.add(plate)) {
                result.errors.add("line " + row.lineNumber() + ": duplicate shuttleId or plateNumber inside the file");
            } else {
                toSave.add(s);
            }
        }
        if (!toSave.isEmpty()) {
            shuttles.saveAll(toSave);
            result.imported = toSave.size();
        }
        return result;
    }
}
