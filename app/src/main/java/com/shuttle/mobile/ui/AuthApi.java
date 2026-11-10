package com.shuttle.mobile.ui;

import android.os.Handler;
import android.os.Looper;

import com.shuttle.common.Role;
import com.shuttle.mobile.client.ApiClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", email);
                body.put("password", password);

                Result r = send("/auth/login", body);
                if (!r.good()) {
                    final int failCode = r.code;
                    final String message = errorMessage(r.text, r.code);
                    mainHandler.post(() -> callback.onError(failCode, message));
                    return;
                }

                JSONObject root = new JSONObject(r.text);
                String token = root.getString("token");
                JSONObject user = root.getJSONObject("user");
                Role role = Role.valueOf(user.optString("role", "DRIVER").toUpperCase());

                Session.start(token,
                        user.optString("employeeId", ""),
                        user.optString("fullName", ""),
                        user.optString("email", ""),
                        user.optString("phone", ""),
                        role);
                ApiClient.getInstance().setAuthToken(token);

                final Role finalRole = role;
                mainHandler.post(() -> callback.onSuccess(finalRole));
            } catch (IOException e) {
                mainHandler.post(() -> callback.onError(0,
                        "Cannot reach the server. Check that it is running."));
            } catch (JSONException | IllegalArgumentException e) {
                mainHandler.post(() -> callback.onError(0,
                        "The server sent an unexpected reply."));
            }
        });
    }

    public static void register(String inviteCode, String name, String email, String phone,
                                String password, RegisterCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("inviteCode", inviteCode);
                body.put("name", name);
                body.put("email", email);
                body.put("phone", phone);
                body.put("password", password);

                Result r = send("/invite/register", body);
                if (!r.good()) {
                    final int failCode = r.code;
                    final String message = errorMessage(r.text, r.code);
                    mainHandler.post(() -> callback.onError(failCode, message));
                    return;
                }

                JSONObject root = new JSONObject(r.text);
                final Role role = Role.valueOf(root.optString("role", "DRIVER").toUpperCase());
                mainHandler.post(() -> callback.onSuccess(role));
            } catch (IOException e) {
                mainHandler.post(() -> callback.onError(0,
                        "Cannot reach the server. Check that it is running."));
            } catch (JSONException | IllegalArgumentException e) {
                mainHandler.post(() -> callback.onError(0,
                        "The server sent an unexpected reply."));
            }
        });
    }

    private static Result send(String path, JSONObject body) throws IOException {
        HttpURLConnection conn = null;
        try {
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(ApiClient.BASE_URL + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }

            int code = conn.getResponseCode();
            boolean good = code >= 200 && code < 300;
            String text = readAll(good ? conn.getInputStream() : conn.getErrorStream());
            return new Result(code, text);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String errorMessage(String text, int code) {
        try {
            String message = new JSONObject(text).optString("error", "");
            if (!message.isEmpty()) {
                return message;
            }
        } catch (JSONException ignored) {
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
                sb.append(line);
            }
        }
        return sb.toString();
    }
}