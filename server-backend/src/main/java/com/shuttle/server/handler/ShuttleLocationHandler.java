package com.shuttle.server.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.shuttle.server.ApiException;
import com.shuttle.server.service.TrackingService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShuttleLocationHandler implements HttpHandler {

    private static final Pattern PATH = Pattern.compile("^/api/shuttles/([^/]+)/location/?$");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                throw new ApiException(405, "Method Not Allowed");
            }

            Matcher m = PATH.matcher(exchange.getRequestURI().getPath());
            if (!m.matches()) {
                throw new ApiException(404, "Endpoint Not Found");
            }
            String shuttleId = m.group(1);

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String lat = number(body, "latitude");
            String lng = number(body, "longitude");
            if (lat == null || lng == null) {
                throw new ApiException(400, "latitude and longitude are required");
            }

            TrackingService.updateLocation(shuttleId, lat + "," + lng);
            ApiHandler.sendResponse(exchange, 200, "{\"status\": \"Location updated\"}");
        } catch (ApiException e) {
            ApiHandler.sendResponse(exchange, e.getStatusCode(), "{\"error\": \"" + e.getMessage() + "\"}");
        }
    }

    private static String number(String json, String key) {
        Matcher m = Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }
}