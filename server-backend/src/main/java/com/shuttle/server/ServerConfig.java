package com.shuttle.server;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.net.URI;

public class ServerConfig {
    private static final String CONFIG_FILE = "config.properties";

    // Defaults in case config.properties is missing
    private static int port = 8080;
    private static String host = "0.0.0.0";
    private static String workersCsv = "sample-data/import/workers.csv";
    private static String shuttlesCsv = "sample-data/import/shuttles.csv";
    private static String shiftsCsv = null;     // default: next to workers.csv
    private static String scheduleCsv = null;   // default: next to workers.csv
    private static String storageMode;

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
            throw new IllegalArgumentException("Invalid config.properties", e);
        }
        storageMode = setting("STORAGE_MODE", "local");
        if (!storageMode.equals("local") && !storageMode.equals("r2")) {
            throw new IllegalArgumentException("STORAGE_MODE must be local or r2");
        }
        String configuredPort = System.getenv("PORT");
        if (configuredPort != null && !configuredPort.isBlank()) port = Integer.parseInt(configuredPort);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("PORT must be between 1 and 65535");
        String dataDir = System.getenv("DATA_DIR");
        if (isR2() || (dataDir != null && !dataDir.isBlank())) {
            Path base = Paths.get(setting("DATA_DIR", "data"));
            workersCsv = base.resolve("workers.csv").toString();
            shuttlesCsv = base.resolve("shuttles.csv").toString();
            shiftsCsv = base.resolve("shifts.csv").toString();
            scheduleCsv = base.resolve("schedule.csv").toString();
        }
        if (isR2()) {
            getR2Endpoint(); getR2Bucket(); getR2AccessKeyId(); getR2SecretAccessKey();
        }
    }

    public static boolean isR2() { return "r2".equals(storageMode); }
    public static URI getR2Endpoint() { return URI.create(required("R2_ENDPOINT")); }
    public static String getR2Bucket() { return required("R2_BUCKET"); }
    public static String getR2ObjectKey() { return setting("R2_OBJECT_KEY", "shuttle-state.zip"); }
    public static String getR2AccessKeyId() { return required("R2_ACCESS_KEY_ID"); }
    public static String getR2SecretAccessKey() { return required("R2_SECRET_ACCESS_KEY"); }
    public static boolean allowInsecureR2Localhost() { return "true".equals(System.getenv("R2_ALLOW_HTTP_LOCALHOST")); }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable: " + name);
        return value;
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
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
