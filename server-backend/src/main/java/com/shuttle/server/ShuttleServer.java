package com.shuttle.server;

import com.sun.net.httpserver.HttpServer;
import com.shuttle.server.handler.AuthHandler;
import com.shuttle.server.handler.InviteHandler;
import com.shuttle.server.handler.LiveHandler;
import com.shuttle.server.handler.LocationHandler;
import com.shuttle.server.handler.ShiftHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class ShuttleServer {
    private HttpServer server;

    public void start() throws IOException {
        int port = ServerConfig.getPort();
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(10));

        // Register core context routes[cite: 17, 18, 22]
        server.createContext("/api/auth", new AuthHandler());
        server.createContext("/api/invite", new InviteHandler());
        server.createContext("/api/shift", new ShiftHandler());
        server.createContext("/api/location", new LocationHandler());
        server.createContext("/api/live", new LiveHandler());

        server.start();
        Log.info("ShuttleServer successfully listening on http://localhost:" + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            Log.info("ShuttleServer stopped.");
        }
    }
}