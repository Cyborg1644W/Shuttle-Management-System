package com.shuttle.server;

public class ServerMain {
    public static void main(String[] args) {
        try {
            ShuttleServer server = new ShuttleServer();
            server.start();
        } catch (Exception e) {
            Log.error("Failed to start server: " + e.getMessage());
        }
    }
}