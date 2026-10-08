package com.shuttle.server.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.shuttle.server.ApiException;
import com.shuttle.server.Auth;
import com.shuttle.server.service.InviteService;
import com.shuttle.server.service.RegistrationService;
import java.io.IOException;

public class InviteHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();

            if (path.endsWith("/generate") || path.endsWith("/generate/")) {
                String token = ApiHandler.extractToken(exchange);
                ApiHandler.requireAuth(token);

                if (!Auth.hasPermission(token, "ADMIN")) {
                    throw new ApiException(403, "Admin privileges required");
                }

                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    throw new ApiException(405, "Method Not Allowed");
                }

                String role = exchange.getRequestHeaders().getFirst("X-Role");
                String code = InviteService.createInvite(role != null ? role : "DRIVER");
                ApiHandler.sendResponse(exchange, 201, "{\"invite_code\": \"" + code + "\"}");
                return;
            }

            if (path.endsWith("/register") || path.endsWith("/register/")) {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    throw new ApiException(405, "Method Not Allowed");
                }

                String code = exchange.getRequestHeaders().getFirst("X-Invite-Code");
                String email = exchange.getRequestHeaders().getFirst("X-Email");
                String password = exchange.getRequestHeaders().getFirst("X-Password");

                RegistrationService.registerUser(code, email, password);
                ApiHandler.sendResponse(exchange, 200, "{\"status\": \"Registration Successful\"}");
                return;
            }

            throw new ApiException(404, "Endpoint Not Found");
        } catch (ApiException e) {
            ApiHandler.sendResponse(exchange, e.getStatusCode(), "{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}