package com.shuttle.server.io;

import com.shuttle.server.repository.InviteRepository;
import com.shuttle.server.repository.ShiftRepository;
import com.shuttle.server.repository.ShuttleRepository;
import com.shuttle.server.repository.WorkerRepository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

public class ExportService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private final WorkerRepository workers;
    private final ShiftRepository shifts;
    private final InviteRepository invites;
    private final ShuttleRepository shuttles;

    public ExportService(WorkerRepository workers, ShiftRepository shifts,
                         InviteRepository invites, ShuttleRepository shuttles) {
        this.workers = Objects.requireNonNull(workers, "workers");
        this.shifts = Objects.requireNonNull(shifts, "shifts");
        this.invites = Objects.requireNonNull(invites, "invites");
        this.shuttles = Objects.requireNonNull(shuttles, "shuttles");
    }


    public Path exportAll(Path exportRoot) throws IOException {
        Files.createDirectories(exportRoot);

        String baseName = "backup-" + LocalDateTime.now().format(STAMP);
        Path finalDir = uniqueDir(exportRoot, baseName);
        Path partialDir = exportRoot.resolve(finalDir.getFileName() + ".partial");
        Files.createDirectories(partialDir);

        try {

            CsvTable workersTable = workers.toTable();
            CsvTable shuttlesTable = shuttles.toTable();
            CsvTable shiftsTable = shifts.toTable();
            CsvTable invitesTable = invites.toTable();

            workersTable.save(partialDir.resolve(WorkerRepository.FILE_NAME));
            shuttlesTable.save(partialDir.resolve(ShuttleRepository.FILE_NAME));
            shiftsTable.save(partialDir.resolve(ShiftRepository.FILE_NAME));
            invitesTable.save(partialDir.resolve(InviteRepository.FILE_NAME));

            String manifest = "Shuttle Management System backup\n"
                    + "created=" + LocalDateTime.now() + "\n"
                    + WorkerRepository.FILE_NAME + "=" + workersTable.size() + " rows\n"
                    + ShuttleRepository.FILE_NAME + "=" + shuttlesTable.size() + " rows\n"
                    + ShiftRepository.FILE_NAME + "=" + shiftsTable.size() + " rows\n"
                    + InviteRepository.FILE_NAME + "=" + invitesTable.size() + " rows\n";
            AtomicFileWriter.writeAtomically(partialDir.resolve("MANIFEST.txt"), manifest);

            removeLockFiles(partialDir);
            publish(partialDir, finalDir);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(partialDir);
            throw e;
        }
        return finalDir;
    }

    public void exportWorkers(Path target) throws IOException {
        workers.toTable().save(target);
    }

    public void exportShuttles(Path target) throws IOException {
        shuttles.toTable().save(target);
    }

    public void exportShifts(Path target) throws IOException {
        shifts.toTable().save(target);
    }

    private static Path uniqueDir(Path root, String baseName) {
        Path candidate = root.resolve(baseName);
        int n = 2;
        while (Files.exists(candidate) || Files.exists(root.resolve(candidate.getFileName() + ".partial"))) {
            candidate = root.resolve(baseName + "-" + n++);
        }
        return candidate;
    }

    private static void removeLockFiles(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (p.getFileName().toString().endsWith(".lock")) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static void publish(Path partialDir, Path finalDir) throws IOException {
        try {
            Files.move(partialDir, finalDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(partialDir, finalDir);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {

                }
            });
        } catch (IOException ignored) {

        }
    }
}
