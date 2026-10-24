package com.shuttle.server;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class ServerConfig {
    private static final String CONFIG_FILE = "config.properties";

    // Defaults in case config.properties is missing
    private static int port = 8080;
    private static String workersCsv = "sample-data/import/workers.csv";
    private static String shuttlesCsv = "sample-data/import/shuttles.csv";

    static {
        loadConfig();
    }

    private static void loadConfig() {
        try (InputStream input = ServerConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (input != null) {
                Properties props = new Properties();
                props.load(input);

                port = Integer.parseInt(props.getProperty("server.port", String.valueOf(port)));
                workersCsv = props.getProperty("csv.workers", workersCsv);
                shuttlesCsv = props.getProperty("csv.shuttles", shuttlesCsv);
            }
        } catch (Exception e) {
            System.out.println("[INFO] Using default system configurations.");
        }
    }

    // Getters for server startup & repositories
    public static int getPort() {
        return port;
    }

    public static Path getWorkersCsvPath() {
        return Paths.get(workersCsv);
    }

    public static Path getShuttlesCsvPath() {
        return Paths.get(shuttlesCsv);
    }
}