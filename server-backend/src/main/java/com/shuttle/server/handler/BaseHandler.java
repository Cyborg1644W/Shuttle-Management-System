package com.shuttle.server.handler;

import com.shuttle.common.Role;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Auth;
import com.shuttle.server.Csv;
import com.shuttle.server.Log;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseHandler implements HttpHandler {

    public static final class Response {
        final int status;
        final Object body;

        Response(int status, Object body) {
            this.status = status;
            this.body = body;
        }
    }

    public static Response ok(Object body) {
        return new Response(200, body);
    }

    public static Response created(Object body) {
        return new Response(201, body);
    }

    public static final class Request {
        public final HttpExchange exchange;
        public final String method;
        public final String[] path;
        public final Map<String, String> query;
        public final Map<String, Object> body;
        public final List<Map<String, Object>> rows;
        public final String token;
        private Worker worker;

        Request(HttpExchange exchange, String method, String[] path, Map<String, String> query,
                List<Map<String, Object>> rows, String token) {
            this.exchange = exchange;
            this.method = method;
            this.path = path;
            this.query = query;
            this.rows = rows;
            this.body = rows.isEmpty() ? Collections.<String, Object>emptyMap() : rows.get(0);
            this.token = token;
        }

        public boolean is(String m) {
            return method.equalsIgnoreCase(m);
        }

        public String seg(int i) {
            return i < path.length ? path[i] : null;
        }

        public String header(String name) {
            return exchange.getRequestHeaders().getFirst(name);
        }

        public Worker worker() {
            if (worker == null) {
                worker = Auth.getWorkerByToken(token);
                if (worker == null) {
                    throw new ApiException(401, "Unauthorized: Invalid or missing session token");
                }
            }
            return worker;
        }

        public boolean isOperator() {
            return worker().getRole() == Role.OPERATOR;
        }

        public void requireOperator() {
            if (!isOperator()) {
                throw new ApiException(403, "Operators only");
            }
        }

        public boolean isSelf(String employeeId) {
            return employeeId != null && employeeId.equalsIgnoreCase(worker().getEmployeeID());
        }
    }

    protected boolean isPublic(Request r) {
        return false;
    }

    protected abstract Response process(Request r) throws Exception;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        int status;
        Object body;
        try {
            Request r = readRequest(exchange);
            if (!isPublic(r)) {
                ApiHandler.requireAuth(r.token);
            }
            Response resp = process(r);
            status = resp.status;
            body = resp.body;
        } catch (ApiException e) {
            status = e.getStatusCode();
            body = Csv.obj("error", e.getMessage());
        } catch (IllegalArgumentException e) {
            status = 400;
            body = Csv.obj("error", e.getMessage());
        } catch (IllegalStateException e) {
            status = 409;
            body = Csv.obj("error", e.getMessage());
        } catch (Exception e) {
            Log.error("Request failed: " + exchange.getRequestMethod() + " "
                    + exchange.getRequestURI().getPath() + " - " + e);
            status = 500;
            body = Csv.obj("error", "Server error");
        }
        ApiHandler.sendResponse(exchange, status, body == null ? "" : Csv.toCsv(body));
    }

    private static Request readRequest(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();

        List<String> parts = new ArrayList<>();
        for (String p : ex.getRequestURI().getPath().split("/")) {
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        if (!parts.isEmpty() && parts.get(0).equals("api")) {
            parts.remove(0);
        }

        List<Map<String, Object>> rows = Collections.emptyList();
        if (method.equalsIgnoreCase("POST") || method.equalsIgnoreCase("PUT")
                || method.equalsIgnoreCase("PATCH") || method.equalsIgnoreCase("DELETE")) {
            String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!raw.trim().isEmpty()) {
                try {
                    rows = Csv.parseRows(raw);
                } catch (IllegalArgumentException e) {
                    throw new ApiException(400, "Invalid request: " + e.getMessage());
                }
            }
        }
        return new Request(ex, method, parts.toArray(new String[0]), parseQuery(ex.getRequestURI().getRawQuery()),
                rows, ApiHandler.extractToken(ex));
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            try {
                String k = java.net.URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
                String v = eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                out.put(k, v);
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
