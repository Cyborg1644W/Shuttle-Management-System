package com.shuttle.server.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.shuttle.server.ApiException;
import com.shuttle.server.service.ShiftService;
import java.io.IOException;

public class ShiftHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String token = ApiHandler.extractToken(exchange);
            ApiHandler.requireAuth(token);

            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String shiftData = ShiftService.getWorkerShift("worker-123");
                ApiHandler.sendResponse(exchange, 200, "{\"shift\": \"" + shiftData + "\"}");
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String shiftId = ShiftService.clockIn("worker-123");
                ApiHandler.sendResponse(exchange, 200, "{\"shiftId\": \"" + shiftId + "\"}");
            } else {
                throw new ApiException(405, "Method Not Allowed");
            }
        } catch (ApiException e) {
            ApiHandler.sendResponse(exchange, e.getStatusCode(), "{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}