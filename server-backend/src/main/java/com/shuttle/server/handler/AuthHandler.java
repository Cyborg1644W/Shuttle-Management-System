package com.shuttle.server.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.shuttle.server.ApiException;
import com.shuttle.server.Auth;
import java.io.IOException;

public class AuthHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String email = exchange.getRequestHeaders().getFirst("X-Email");
                String password = exchange.getRequestHeaders().getFirst("X-Password");

                String token = Auth.authenticateUser(email != null ? email : "mock@email.com",
                        password != null ? password : "mockPassword");
                if (token == null) throw new ApiException(401, "Invalid credentials");

                ApiHandler.sendResponse(exchange, 200, "{\"token\": \"" + token + "\"}");
            } else {
                throw new ApiException(405, "Method Not Allowed");
            }
        } catch (ApiException e) {
            ApiHandler.sendResponse(exchange, e.getStatusCode(), "{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}