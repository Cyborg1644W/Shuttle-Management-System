package com.shuttle.server.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.shuttle.server.ApiException;
import com.shuttle.server.service.TrackingService;
import java.io.IOException;

public class LocationHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String token = ApiHandler.extractToken(exchange);
            ApiHandler.requireAuth(token);

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String coords = exchange.getRequestHeaders().getFirst("X-Coordinates");
                String locationToSave = (coords != null) ? coords : "14.4445, 121.0012";
                TrackingService.updateLocation("shuttle-1", locationToSave);
                ApiHandler.sendResponse(exchange, 200, "{\"status\": \"Location updated\"}");
            } else {
                throw new ApiException(405, "Method Not Allowed");
            }
        } catch (ApiException e) {
            ApiHandler.sendResponse(exchange, e.getStatusCode(), "{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}