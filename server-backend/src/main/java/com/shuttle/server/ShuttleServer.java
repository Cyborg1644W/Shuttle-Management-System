package com.shuttle.server;

import com.sun.net.httpserver.HttpServer;
import com.shuttle.server.handler.ApiHandler;
import com.shuttle.server.handler.AuthHandler;
import com.shuttle.server.handler.DashboardHandler;
import com.shuttle.server.handler.LiveHandler;
import com.shuttle.server.handler.LocationHandler;
import com.shuttle.server.handler.ScheduleHandler;
import com.shuttle.server.handler.ShiftHandler;
import com.shuttle.server.handler.ShuttleLocationHandler;
import com.shuttle.server.handler.WorkerHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class ShuttleServer {
    private HttpServer server;

    public void start() throws IOException {
        Repositories.init();

        int port = ServerConfig.getPort();
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(10));

        // Anything that matches no endpoint below gets a plain error instead of an HTML page
        server.createContext("/api", exchange ->
                ApiHandler.sendError(exchange, 404, "Endpoint Not Found"));
        server.createContext("/api/health", exchange ->
                ApiHandler.sendResponse(exchange, 200, "status\nok\n"));
        server.createContext("/api/auth", new AuthHandler());
        server.createContext("/api/workers", new WorkerHandler());
        server.createContext("/api/shift", new ShiftHandler());
        server.createContext("/api/location", new LocationHandler());
        server.createContext("/api/live", new LiveHandler());
        server.createContext("/api/shuttles", new ShuttleLocationHandler());
        server.createContext("/api/dashboard", new DashboardHandler());
        server.createContext("/api/schedule", new ScheduleHandler());

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
