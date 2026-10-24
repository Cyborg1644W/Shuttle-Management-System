package com.shuttle.server.handler;

import com.sun.net.httpserver.HttpExchange;
import com.shuttle.server.ApiException;
import com.shuttle.server.Auth;
import java.io.IOException;
import java.io.OutputStream;

public class ApiHandler {

    public static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static String extractToken(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return authHeader;
    }

    public static void requireAuth(String token) {
        if (token == null || !Auth.validateSessionToken(token)) {
            throw new ApiException(401, "Unauthorized: Invalid or missing session token");
        }
    }
}