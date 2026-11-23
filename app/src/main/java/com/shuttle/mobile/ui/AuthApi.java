package com.shuttle.mobile.ui;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.shuttle.common.Role;
import com.shuttle.mobile.client.ApiClient;
import com.shuttle.mobile.client.ServerReadiness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AuthApi {

    public interface LoginCallback {
        void onSuccess(Role role);

        void onError(int status, String message);
    }

    public interface RegisterCallback {
        void onSuccess(Role role);

        void onError(int status, String message);
    }

    private static final String TAG = "AuthApi";
    private static final String LOGIN_PATH = "/auth/login";
    private static final String REGISTER_PATH = "/auth/register";

    private static final int TIMEOUT_MS = 8000;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final class Result {
        final int code;
        final String text;

        Result(int code, String text) {
            this.code = code;
            this.text = text;
        }

        boolean good() {
            return code >= 200 && code < 300;
        }
    }

    private AuthApi() {
    }

    public static void login(String email, String password, LoginCallback callback) {
        doLogin("email", email, password, callback);
    }

    public static void loginWithPhone(String phone, String password, LoginCallback callback) {
        doLogin("phone", phone, password, callback);
    }

    private static void doLogin(String idKey, String idValue, String password,
                                LoginCallback callback) {
        final String baseUrl = ApiClient.getBaseUrl();
        executor.execute(() -> {
            try {
                Map<String, String> body = new LinkedHashMap<>();
                body.put(idKey, idValue);
                body.put("password", password);

                Result r = send(baseUrl, LOGIN_PATH, body);
                if (!r.good()) {
                    final int failCode = r.code;
                    final String message = errorMessage(r.text, r.code);
                    mainHandler.post(() -> callback.onError(failCode, message));
                    return;
                }

                Map<String, String> user = firstRow(r.text);
                String token = csvGet(user, "token");
                if (token.isEmpty()) {
                    mainHandler.post(() -> callback.onError(0, "The server sent an unexpected reply."));
                    return;
                }
                Role role = Role.valueOf(csvGet(user, "role").toUpperCase());

                final Role finalRole = role;
                mainHandler.post(() -> {
                    if (!baseUrl.equals(ApiClient.getBaseUrl())) {
                        callback.onError(0, "The server address changed. Sign in using the selected server.");
                        return;
                    }
                    Session.start(token,
                            csvGet(user, "employeeId"),
                            csvGet(user, "fullName"),
                            csvGet(user, "email"),
                            csvGet(user, "phone"),
                            finalRole);
                    ApiClient.getInstance().setAuthToken(token);
                    callback.onSuccess(finalRole);
                });
            } catch (IOException e) {
                Log.e(TAG, "Network error: " + e, e);
                final String message = e.getMessage() == null
                        ? "Cannot reach the server. Check your connection and server address." : e.getMessage();
                mainHandler.post(() -> callback.onError(0, message));
            } catch (IllegalArgumentException e) {
                mainHandler.post(() -> callback.onError(0,
                        "The server sent an unexpected reply."));
            }
        });
    }

    public static void register(Role chosenRole, String name, String email, String phone,
                                String password, RegisterCallback callback) {
        final String baseUrl = ApiClient.getBaseUrl();
        executor.execute(() -> {
            try {
                Map<String, String> body = new LinkedHashMap<>();
                body.put("role", chosenRole.name());
                body.put("name", name);
                body.put("email", email);
                body.put("phone", phone);
                body.put("password", password);

                Result r = send(baseUrl, REGISTER_PATH, body);
                if (!r.good()) {
                    final int failCode = r.code;
                    final String message = errorMessage(r.text, r.code);
                    mainHandler.post(() -> callback.onError(failCode, message));
                    return;
                }

                String roleText = csvGet(firstRow(r.text), "role");
                Role parsed = chosenRole;
                if (!roleText.isEmpty()) {
                    try {
                        parsed = Role.valueOf(roleText.toUpperCase());
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                final Role role = parsed;
                mainHandler.post(() -> callback.onSuccess(role));
            } catch (IOException e) {
                Log.e(TAG, "Network error: " + e, e);
                final String message = e.getMessage() == null
                        ? "Cannot reach the server. Check your connection and server address." : e.getMessage();
                mainHandler.post(() -> callback.onError(0, message));
            }
        });
    }

    private static Map<String, String> firstRow(String text) {
        List<Map<String, String>> rows = parseCsv(text);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Empty reply");
        }
        return rows.get(0);
    }

    private static Result send(String baseUrl, String path, Map<String, String> row) throws IOException {
        HttpURLConnection conn = null;
        try {
            ServerReadiness.ensureReady(baseUrl);
            if (!baseUrl.equals(ApiClient.getBaseUrl())) {
                throw new IOException("The server address changed. Please try again.");
            }
            byte[] data = buildCsv(row).getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/csv; charset=utf-8");
            conn.setRequestProperty("Accept", "text/csv");
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }

            int code = conn.getResponseCode();
            boolean good = code >= 200 && code < 300;
            String text = readAll(good ? conn.getInputStream() : conn.getErrorStream());
            if (!baseUrl.equals(ApiClient.getBaseUrl())) {
                throw new IOException("The server address changed. Check the previous server before repeating this request.");
            }
            return new Result(code, text);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String errorMessage(String text, int code) {
        Log.e(TAG, "Server returned " + code + ": " + text);
        List<Map<String, String>> rows = parseCsv(text);
        if (!rows.isEmpty()) {
            String message = csvGet(rows.get(0), "error");
            if (!message.isEmpty()) {
                return message;
            }
        }
        return "Server returned " + code;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    static String buildCsv(Map<String, String> row) {
        StringBuilder header = new StringBuilder();
        StringBuilder values = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (!first) {
                header.append(',');
                values.append(',');
            }
            header.append(csvEscape(entry.getKey()));
            values.append(csvEscape(entry.getValue()));
            first = false;
        }
        return header + "\n" + values + "\n";
    }

    static List<Map<String, String>> parseCsv(String text) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return rows;
        }

        List<List<String>> lines = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                cells.add(cell.toString());
                cell.setLength(0);
                lines.add(cells);
                cells = new ArrayList<>();
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !cells.isEmpty()) {
            cells.add(cell.toString());
            lines.add(cells);
        }

        if (lines.size() < 2) {
            return rows;
        }

        List<String> header = lines.get(0);
        for (int i = 1; i < lines.size(); i++) {
            List<String> line = lines.get(i);
            if (line.size() == 1 && line.get(0).isEmpty()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < header.size(); j++) {
                row.put(header.get(j), j < line.size() ? line.get(j) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    static String csvGet(Map<String, String> row, String... keys) {
        for (String key : keys) {
            String value = row.get(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}