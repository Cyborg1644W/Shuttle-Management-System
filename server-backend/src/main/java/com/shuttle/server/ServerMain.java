package com.shuttle.server;

public class ServerMain {
    public static void main(String[] args) {
        try {
            ShuttleServer server = new ShuttleServer();
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "server-shutdown"));

            if (Repositories.workers().count() == 0) {
                System.out.println("No accounts yet. Register the first one from the app.");
            }
        } catch (Exception e) {
            Log.error("Failed to start server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
