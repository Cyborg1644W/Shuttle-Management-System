package com.shuttle.server;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Log {
    private static final String LOG_FILE = "server.log";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ThreadLocal<java.util.List<String[]>> pending = new ThreadLocal<>();

    static void beginTransaction() { pending.set(new java.util.ArrayList<>()); }

    static synchronized void commitTransaction() {
        java.util.List<String[]> messages = pending.get();
        pending.remove();
        if (messages != null) for (String[] message : messages) writeLog(message[0], message[1]);
    }

    static void rollbackTransaction() { pending.remove(); }

    public static synchronized void info(String message) {
        writeLog("INFO", message);
    }

    public static synchronized void error(String message) {
        writeLog("ERROR", message);
    }

    public static synchronized void audit(String action) {
        writeLog("AUDIT", action);
    }

    private static void writeLog(String level, String message) {
        if (pending.get() != null && !level.equals("ERROR")) {
            pending.get().add(new String[]{level, message});
            return;
        }
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String formattedMessage = String.format("[%s] [%s] %s", timestamp, level, message);

        // Print to standard console
        System.out.println(formattedMessage);

        // Append to immutable log file
        try (PrintWriter writer = new PrintWriter(new FileWriter(LOG_FILE, true))) {
            writer.println(formattedMessage);
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        }
    }
}
