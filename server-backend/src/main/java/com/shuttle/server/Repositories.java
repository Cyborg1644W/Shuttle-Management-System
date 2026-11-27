package com.shuttle.server;

import com.shuttle.server.repository.ScheduleRepository;
import com.shuttle.server.repository.DatedShiftRepository;
import com.shuttle.server.repository.ShiftRepository;
import com.shuttle.server.repository.ShuttleRepository;
import com.shuttle.server.repository.WorkerRepository;
import com.shuttle.server.repository.TerminalRepository;
import com.shuttle.server.storage.CsvStorage;
import com.shuttle.server.service.TrackingService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


public final class Repositories {

    public static final Object LOCK = new Object();

    private static WorkerRepository workers;
    private static ShiftRepository shifts;
    private static ShuttleRepository shuttles;
    private static ScheduleRepository schedule;
    private static DatedShiftRepository datedShifts;
    private static TerminalRepository terminals;
    private static boolean initialized;
    private static boolean reloadRequired;

    private Repositories() {
    }

    public static void init() throws IOException {
        synchronized (LOCK) {
            if (initialized) return;
            CsvStorage.configure();
            CsvStorage.refresh();
            CsvStorage.begin();
            try {
                reload();
                CsvStorage.commit();
                initialized = true;
            } catch (IOException | RuntimeException failure) {
                CsvStorage.rollback();
                throw failure;
            }
        }
    }

    private static void reload() throws IOException {
        createParent(ServerConfig.getWorkersCsvPath());
        createParent(ServerConfig.getShuttlesCsvPath());
        createParent(ServerConfig.getShiftsCsvPath());
        createParent(ServerConfig.getScheduleCsvPath());

        WorkerRepository nextWorkers = new WorkerRepository(ServerConfig.getWorkersCsvPath());
        ShuttleRepository nextShuttles = new ShuttleRepository(ServerConfig.getShuttlesCsvPath());
        ShiftRepository nextShifts = new ShiftRepository(ServerConfig.getShiftsCsvPath());
        ScheduleRepository nextSchedule = new ScheduleRepository(ServerConfig.getScheduleCsvPath());
        DatedShiftRepository nextDated = new DatedShiftRepository(ServerConfig.getDatedShiftsCsvPath());
        TerminalRepository nextTerminals = new TerminalRepository(ServerConfig.getWorkersCsvPath().resolveSibling("terminals.txt"));
        workers = nextWorkers;
        shuttles = nextShuttles;
        shifts = nextShifts;
        schedule = nextSchedule;
        datedShifts = nextDated;
        terminals = nextTerminals;
    }

    /** Cloud writes are staged until all validation, mutations and response serialization succeed. */
    public static <T> T transact(java.util.concurrent.Callable<T> operation) throws Exception {
        synchronized (LOCK) {
            if (CsvStorage.refresh()) reloadRequired = true;
            CsvStorage.begin();
            java.util.Map<String, String> sessions = Auth.snapshotSessions();
            java.util.Map<String, TrackingService.Position> positions = TrackingService.snapshot();
            Log.beginTransaction();
            boolean committingWrites = false;
            try {
                if (reloadRequired) {
                    reload();
                    reloadRequired = false;
                }
                T result = operation.call();
                committingWrites = CsvStorage.hasWrites();
                CsvStorage.commit();
                Log.commitTransaction();
                return result;
            } catch (Exception failure) {
                reloadRequired |= committingWrites || CsvStorage.hasWrites();
                CsvStorage.rollback();
                Auth.restoreSessions(sessions);
                TrackingService.restore(positions);
                Log.rollbackTransaction();
                // The next request recovers any interrupted local commit and reloads the last
                // committed tables before authentication or any other read can observe these changes.
                throw failure;
            }
        }
    }

    public static WorkerRepository workers() {
        return require(workers);
    }

    public static ShiftRepository shifts() {
        return require(shifts);
    }

    public static ShuttleRepository shuttles() {
        return require(shuttles);
    }

    public static ScheduleRepository schedule() {
        return require(schedule);
    }

    public static DatedShiftRepository datedShifts() {
        return require(datedShifts);
    }

    public static TerminalRepository terminals() {
        return require(terminals);
    }

    private static <T> T require(T repo) {
        if (repo == null) {
            throw new IllegalStateException("Repositories.init() was not called");
        }
        return repo;
    }

    private static void createParent(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
