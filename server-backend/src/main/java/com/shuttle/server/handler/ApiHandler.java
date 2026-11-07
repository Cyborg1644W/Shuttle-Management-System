package com.shuttle.server.handler;

import com.sun.net.httpserver.HttpExchange;
import com.shuttle.server.ApiException;
import com.shuttle.server.Auth;
import com.shuttle.server.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
public class ApiHandler {

    public static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Sends {"error": "..."} with the message properly escaped. */
    public static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        sendResponse(exchange, statusCode, Json.stringify(Json.obj("error", message)));
    }

    public static String extractToken(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        return authHeader;
    }

    public static void requireAuth(String token) {
        if (token == null || !Auth.validateSessionToken(token)) {
            throw new ApiException(401, "Unauthorized: Invalid or missing session token");
        }
    }
}
