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
        } catch (Throwable e) {
            // Configuration errors can occur in static initialization. Never advertise a ready server.
            Log.error("Server startup failed (" + e.getClass().getSimpleName() + "). Check storage configuration and access.");
            System.exit(1);
        }
    }
}
