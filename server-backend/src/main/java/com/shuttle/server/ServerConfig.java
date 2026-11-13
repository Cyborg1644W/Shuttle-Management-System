package com.shuttle.server;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class ServerConfig {
    private static final String CONFIG_FILE = "config.properties";

    // Defaults in case config.properties is missing
    private static int port = 8080;
    private static String host = "0.0.0.0";
    private static String workersCsv = "sample-data/import/workers.csv";
    private static String shuttlesCsv = "sample-data/import/shuttles.csv";
    private static String shiftsCsv = null;     // default: next to workers.csv
    private static String scheduleCsv = null;   // default: next to workers.csv

    static {
        loadConfig();
    }

    private static void loadConfig() {
        try (InputStream input = ServerConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (input != null) {
                Properties props = new Properties();
                props.load(input);

                port = Integer.parseInt(props.getProperty("server.port", String.valueOf(port)));
                host = props.getProperty("server.host", host);
                workersCsv = props.getProperty("csv.workers", workersCsv);
                shuttlesCsv = props.getProperty("csv.shuttles", shuttlesCsv);
                shiftsCsv = props.getProperty("csv.shifts", shiftsCsv);
                scheduleCsv = props.getProperty("csv.schedule", scheduleCsv);
            }
        } catch (Exception e) {
            System.out.println("[INFO] Using default system configurations.");
        }
    }

    // Getters for server startup & repositories
    public static String getHost() {
        return firstNonBlank(System.getProperty("shuttle.server.host"), System.getenv("SHUTTLE_SERVER_HOST"), host);
    }

    public static int getPort() {
        String configuredPort = firstNonBlank(System.getProperty("shuttle.server.port"), System.getenv("SHUTTLE_SERVER_PORT"));
        if (configuredPort == null) {
            return port;
        }
        try {
            int parsedPort = Integer.parseInt(configuredPort);
            if (parsedPort < 1 || parsedPort > 65535) {
                throw new NumberFormatException("port outside valid range");
            }
            return parsedPort;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Server port must be between 1 and 65535: " + configuredPort, e);
        }
    }

    public static Path getWorkersCsvPath() {
        return Paths.get(workersCsv);
    }

    public static Path getShuttlesCsvPath() {
        return Paths.get(shuttlesCsv);
    }

    public static Path getShiftsCsvPath() {
        return shiftsCsv != null ? Paths.get(shiftsCsv) : sibling("shifts.csv");
    }

    public static Path getScheduleCsvPath() {
        return scheduleCsv != null ? Paths.get(scheduleCsv) : sibling("schedule.csv");
    }

    private static Path sibling(String name) {
        Path parent = getWorkersCsvPath().getParent();
        return parent == null ? Paths.get(name) : parent.resolve(name);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
