package com.shuttle.server;

import com.shuttle.server.repository.ScheduleRepository;
import com.shuttle.server.repository.ShiftRepository;
import com.shuttle.server.repository.ShuttleRepository;
import com.shuttle.server.repository.WorkerRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


public final class Repositories {


    public static final Object LOCK = new Object();

    private static WorkerRepository workers;
    private static ShiftRepository shifts;
    private static ShuttleRepository shuttles;
    private static ScheduleRepository schedule;

    private Repositories() {
    }

    public static synchronized void init() throws IOException {
        if (workers != null) {
            return;
        }
        createParent(ServerConfig.getWorkersCsvPath());
        createParent(ServerConfig.getShuttlesCsvPath());
        createParent(ServerConfig.getShiftsCsvPath());
        createParent(ServerConfig.getScheduleCsvPath());

        workers = new WorkerRepository(ServerConfig.getWorkersCsvPath());
        shuttles = new ShuttleRepository(ServerConfig.getShuttlesCsvPath());
        shifts = new ShiftRepository(ServerConfig.getShiftsCsvPath());
        schedule = new ScheduleRepository(ServerConfig.getScheduleCsvPath());
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