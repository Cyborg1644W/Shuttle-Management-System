package com.shuttle.server.storage;

import com.shuttle.common.Driver;
import com.shuttle.common.Role;
import com.shuttle.common.Worker;
import com.shuttle.server.ServerConfig;
import com.shuttle.server.repository.ScheduleRepository;
import com.shuttle.server.repository.DatedShiftRepository;
import com.shuttle.server.repository.ShiftRepository;
import com.shuttle.server.repository.ShuttleRepository;
import com.shuttle.server.repository.TerminalRepository;
import com.shuttle.server.repository.WorkerRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit, create-only migration tool. Never starts the server or replaces existing cloud data. */
public final class StorageAdmin {
    private StorageAdmin() { }

    public static void main(String[] args) {
        try {
            if (args.length != 2 || (!args[0].equals("validate") && !args[0].equals("import"))) {
                throw new IllegalArgumentException("Usage: StorageAdmin validate|import <csv-directory>");
            }
            Path dir = Path.of(args[1]).toAbsolutePath().normalize();
            Map<String, String> tables = validate(dir);
            if (args[0].equals("validate")) {
                System.out.println("Validated " + tables.size() + " CSV tables; no files changed.");
                return;
            }
            if (!ServerConfig.isR2()) throw new IllegalArgumentException("Import requires STORAGE_MODE=r2 and secure R2 environment settings");
            ObjectStore store = new R2ObjectClient(ServerConfig.getR2Endpoint(), ServerConfig.getR2Bucket(),
                    ServerConfig.getR2ObjectKey(), ServerConfig.getR2AccessKeyId(), ServerConfig.getR2SecretAccessKey());
            if (store.get() != null) throw new IOException("Cloud state already exists; import refused without changing it");
            byte[] snapshot = CsvStorage.encode(tables);
            store.put(snapshot, null); // If-None-Match protects against another deployment initializing first.
            ObjectStore.Versioned verified = store.get();
            if (verified == null || !java.util.Arrays.equals(snapshot, verified.data())) {
                throw new IOException("Import could not be verified; inspect cloud state before repeating");
            }
            System.out.println("Validated CSV snapshot uploaded and verified. No existing cloud object was replaced.");
        } catch (Throwable failure) {
            // Validators can include private row contents in error messages. Keep CLI output free of data/secrets.
            System.err.println("Storage operation failed (" + failure.getClass().getSimpleName()
                    + "). Check the CSV schemas, references, configuration and whether cloud state already exists.");
            System.exit(1);
        }
    }

    private static Map<String, String> validate(Path dir) throws IOException {
        Map<String, String> tables = new LinkedHashMap<>();
        for (String name : CsvStorage.TABLES) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) throw new IOException("All five CSV files are required");
            if (Files.size(file) > 32 * 1024 * 1024) throw new IOException("CSV exceeds prototype limit");
            tables.put(name, Files.readString(file, StandardCharsets.UTF_8));
        }
        WorkerRepository workers = new WorkerRepository(dir.resolve("workers.txt"));
        ShuttleRepository shuttles = new ShuttleRepository(dir.resolve("shuttles.txt"));
        ShiftRepository shifts = new ShiftRepository(dir.resolve("shifts.txt"));
        ScheduleRepository schedule = new ScheduleRepository(dir.resolve("schedule.txt"));
        TerminalRepository terminals = new TerminalRepository(dir.resolve("terminals.txt"));
        Path datedPath = dir.resolve(DatedShiftRepository.FILE_NAME);
        if (Files.exists(datedPath)) {
            if (!Files.isRegularFile(datedPath) || Files.size(datedPath) > 32 * 1024 * 1024) {
                throw new IOException("Invalid dated shift file");
            }
            new DatedShiftRepository(datedPath);
            tables.put(DatedShiftRepository.FILE_NAME, Files.readString(datedPath, StandardCharsets.UTF_8));
        }
        Map<String, TerminalRepository.Entry> owners = new LinkedHashMap<>();
        for (TerminalRepository.Entry e : terminals.all()) {
            if (e.type.equals("TERMINAL")) {
                Worker operator = workers.findById(e.id).orElseThrow(() -> new IOException("Missing terminal operator"));
                if (operator.getRole() != Role.OPERATOR || !e.id.equals(e.terminalId) || e.name.isBlank()) {
                    throw new IOException("Invalid terminal owner");
                }
                owners.put(e.id, e);
            }
        }
        for (TerminalRepository.Entry e : terminals.all()) {
            if (!owners.containsKey(e.terminalId)) throw new IOException("Orphaned terminal record");
            if (e.type.equals("MEMBER") && !(workers.findById(e.id).orElse(null) instanceof Driver)) {
                throw new IOException("Missing terminal driver");
            }
            if (e.type.equals("INVITE") && !e.usedBy.isEmpty() && !workers.existsById(e.usedBy)) {
                throw new IOException("Missing invitation recipient");
            }
        }
        for (Worker worker : workers.getAllWorkers()) {
            if (!(worker instanceof Driver driver)) continue;
            if (driver.getAssignedShuttleId() != null && !shuttles.existsById(driver.getAssignedShuttleId())) {
                throw new IOException("Missing assigned shuttle");
            }
            if (driver.getActiveShiftId() != null) {
                var active = shifts.findById(driver.getActiveShiftId()).orElseThrow(() -> new IOException("Missing active shift"));
                if (!active.isActive() || !active.getDriverId().equals(driver.getEmployeeID())) {
                    throw new IOException("Invalid active shift reference");
                }
            }
            for (TerminalRepository.Entry member : terminals.all()) {
                if (!member.type.equals("MEMBER") || !member.id.equals(driver.getEmployeeID())
                        || driver.getAssignedShuttleId() == null) continue;
                boolean owned = terminals.all().stream().anyMatch(e -> e.type.equals("SHUTTLE")
                        && e.id.equals(driver.getAssignedShuttleId()) && e.terminalId.equals(member.terminalId));
                if (!owned) throw new IOException("Assigned shuttle belongs to another terminal");
            }
        }
        for (var shift : shifts.getAllShifts()) {
            if (!workers.existsById(shift.getDriverId())) throw new IOException("Missing shift driver");
        }
        for (var entry : schedule.getAll()) {
            if (!workers.existsById(entry.employeeId)) throw new IOException("Missing schedule worker");
        }
        // Force the same expanded-size check as the runtime before any cloud write.
        CsvStorage.encode(tables);
        return tables;
    }
}
