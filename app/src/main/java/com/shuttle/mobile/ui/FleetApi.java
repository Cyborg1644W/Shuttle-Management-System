package com.shuttle.mobile.ui;

import android.os.Handler;
import android.os.Looper;

import com.shuttle.mobile.client.ApiClient;
import com.shuttle.mobile.client.ServerReadiness;

import org.json.JSONArray;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
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
        final String baseUrl = ApiClient.getBaseUrl();

        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                ServerReadiness.ensureReady(baseUrl);
                if (!baseUrl.equals(ApiClient.getBaseUrl())) {
                    throw new IOException("The server address changed. Please try again.");
                }
                conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestProperty("Accept", "text/csv");
                if (token != null) {
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                }

                if (method.equals("POST") || method.equals("PUT")) {
                    byte[] data = csvBody(body).getBytes(StandardCharsets.UTF_8);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "text/csv; charset=utf-8");
                    conn.setFixedLengthStreamingMode(data.length);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(data);
                    }
                }

                int code = conn.getResponseCode();
                boolean good = code >= 200 && code < 300;
                String text = readAll(good ? conn.getInputStream() : conn.getErrorStream());

                if (good) {
                    final JSONObject json = response(path, method, text);
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
                final String message = e.getMessage() == null
                        ? "Cannot reach the server. Check your connection and server address." : e.getMessage();
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onError(0, message);
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

    // Preserve the JSONObject API used by screens while speaking CSV on the wire.
    static String csvBody(JSONObject body) throws JSONException {
        if (body == null || body.length() == 0) {
            return "";
        }
        Map<String, String> row = new LinkedHashMap<>();
        Iterator<String> keys = body.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = body.get(key);
            if (value instanceof JSONObject || value instanceof JSONArray) {
                throw new JSONException("CSV requests must contain scalar fields");
            }
            row.put(key, value == JSONObject.NULL ? "" : String.valueOf(value));
        }
        return AuthApi.buildCsv(row);
    }

    static JSONObject response(String path, String method, String text) throws JSONException {
        List<Map<String, String>> rows = AuthApi.parseCsv(text);
        JSONArray array = new JSONArray();
        for (Map<String, String> row : rows) {
            array.put(new JSONObject(row));
        }
        String route = path.split("\\?", 2)[0];
        if ("GET".equals(method) && "/shift/active".equals(route)) {
            JSONObject reply = new JSONObject();
            reply.put("active", array.length() > 0);
            if (array.length() > 0) {
                reply.put("shift", array.getJSONObject(0));
            }
            return reply;
        }
        String listKey = null;
        if ("GET".equals(method)) {
            switch (route) {
                case "/workers": listKey = "workers"; break;
                case "/shuttles": listKey = "shuttles"; break;
                case "/shift": listKey = "shifts"; break;
                case "/schedule": listKey = "schedule"; break;
                case "/live": listKey = "shuttles"; break;
                default: break;
            }
        }
        if (listKey != null) {
            return new JSONObject().put(listKey, array);
        }
        if (array.length() == 0) {
            if (!text.trim().isEmpty()) {
                throw new JSONException("Expected a CSV row");
            }
            return new JSONObject();
        }
        return array.getJSONObject(0);
    }

    private static String errorMessage(String text, int code) {
        List<Map<String, String>> rows = AuthApi.parseCsv(text);
        if (!rows.isEmpty()) {
            String message = rows.get(0).get("error");
            if (message != null && !message.isEmpty()) {
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
}