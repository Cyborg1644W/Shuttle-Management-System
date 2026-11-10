package com.shuttle.mobile.ui;

import android.os.Handler;
import android.os.Looper;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FleetApi {

    public interface Callback {
        void onSuccess(JSONObject json);

        void onError(int status, String message);
    }

    private static final int TIMEOUT_MS = 8000;
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FleetApi() {
    }

    public static void get(String path, Callback callback) {
        run("GET", path, null, callback);
    }

    public static void post(String path, JSONObject body, Callback callback) {
        run("POST", path, body, callback);
    }

    public static void put(String path, JSONObject body, Callback callback) {
        run("PUT", path, body, callback);
    }

    public static void delete(String path, Callback callback) {
        run("DELETE", path, null, callback);
    }

    private static void run(String method, String path, JSONObject body, Callback callback) {
        final String token = Session.getToken();

        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(ApiClient.BASE_URL + path).openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestProperty("Accept", "application/json");
                if (token != null) {
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                }

                if (method.equals("POST") || method.equals("PUT")) {
                    byte[] data = (body == null ? "{}" : body.toString()).getBytes(StandardCharsets.UTF_8);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setFixedLengthStreamingMode(data.length);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(data);
                    }
                }

                int code = conn.getResponseCode();
                boolean good = code >= 200 && code < 300;
                String text = readAll(good ? conn.getInputStream() : conn.getErrorStream());

                if (good) {
                    final JSONObject json = text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onSuccess(json);
                        }
                    });
                } else {
                    final int failCode = code;
                    final String message = errorMessage(text, code);
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onError(failCode, message);
                        }
                    });
                }
            } catch (IOException e) {
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onError(0, "Cannot reach the server. Check that it is running.");
                    }
                });
            } catch (JSONException e) {
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onError(0, "The server sent an unexpected reply.");
                    }
                });
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }

    public static String text(JSONObject o, String key) {
        if (o == null || o.isNull(key)) {
            return "";
        }
        String value = o.optString(key, "");
        return value.equals("null") ? "" : value;
    }

    public static String formatDuration(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    public static String routeToString(List<double[]> points) {
        StringBuilder sb = new StringBuilder();
        for (double[] p : points) {
            if (sb.length() > 0) {
                sb.append(";");
            }
            sb.append(String.format(Locale.US, "%.5f,%.5f", p[0], p[1]));
        }
        return sb.toString();
    }

    public static ArrayList<double[]> parseRoute(String route) {
        ArrayList<double[]> points = new ArrayList<>();
        if (route == null || route.isEmpty()) {
            return points;
        }
        for (String part : route.split(";")) {
            String[] xy = part.split(",");
            if (xy.length == 2) {
                try {
                    points.add(new double[]{Double.parseDouble(xy[0]), Double.parseDouble(xy[1])});
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return points;
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