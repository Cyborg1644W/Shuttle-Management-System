package com.shuttle.server.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.shuttle.server.ApiException;
import com.shuttle.server.service.TrackingService;
import java.io.IOException;

public class LiveHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String token = ApiHandler.extractToken(exchange);
            ApiHandler.requireAuth(token);

            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String location = TrackingService.getLatestLocation("shuttle-1");
                ApiHandler.sendResponse(exchange, 200, "{\"live_location\": \"" + location + "\"}");
            } else {
                throw new ApiException(405, "Method Not Allowed");
            }
        } catch (ApiException e) {
            ApiHandler.sendResponse(exchange, e.getStatusCode(), "{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}