package com.shuttle.mobile.client;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.shuttle.common.CsvUtil;

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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ApiClient {

    public static final String BASE_URL = ServerConnection.DEFAULT_URL;

    public static void initialize(Context context) {
        ServerConnection.initialize(context);
    }

    public static String getBaseUrl() {
        return ServerConnection.getBaseUrl();
    }

    private static final int TIMEOUT_MS = 8000;

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    private static ApiClient instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String authToken; // optional

    private ApiClient() { }

    public static synchronized ApiClient getInstance() {
        if (instance == null) instance = new ApiClient();
        return instance;
    }

    public void setAuthToken(String token) {
        this.authToken = token;
    }

    public static String buildLocationJson(String shuttleId, double lat, double lng,
                                           float speed, long timestamp) {
        return buildLocationJson(shuttleId, lat, lng, speed, timestamp, null, Float.NaN);
    }

    public static String buildLocationJson(String shuttleId, double lat, double lng,
                                           float speed, long timestamp, String shiftId, float bearing) {
        try {
            JSONObject o = new JSONObject();
            o.put("shuttleId", shuttleId);
            o.put("latitude", lat);
            o.put("longitude", lng);
            o.put("speed", speed);
            o.put("timestamp", timestamp);
            if (shiftId != null) o.put("shiftId", shiftId);
            if (Float.isFinite(bearing)) o.put("bearing", bearing);
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public void sendLocation(String shuttleId, double lat, double lng, float speed,
                             long timestamp, Callback<Boolean> callback) {
        final String json = buildLocationJson(shuttleId, lat, lng, speed, timestamp);
        executor.execute(() -> {
            boolean ok = postLocationBlocking(json);
            mainHandler.post(() -> {
                if (callback == null) return;
                if (ok) callback.onSuccess(true);
                else callback.onError("Failed to send location");
            });
        });
    }

    public boolean postLocationBlocking(String json) {
        String shuttleId;
        try {
            shuttleId = new JSONObject(json).getString("shuttleId");
        } catch (JSONException e) {
            return true; // corrupt entry: report "handled" so it gets dropped from the queue
        }
        return postLocationCsv("/shuttles/" + encodePathPart(shuttleId) + "/location", json);
    }

    private static String encodePathPart(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }

    public void getShuttles(Callback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                String body = get("/shuttles");
                JSONArray arr = csvRows(body);
                mainHandler.post(() -> callback.onSuccess(arr));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "Network error" : e.getMessage();
                mainHandler.post(() -> callback.onError(msg));
            }
        });
    }

    public void getDashboard(Callback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                String body = get("/dashboard");
                JSONArray rows = csvRows(body);
                if (rows.length() != 1) throw new IOException("The server sent an unexpected reply.");
                JSONObject obj = rows.getJSONObject(0);
                mainHandler.post(() -> callback.onSuccess(obj));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "Network error" : e.getMessage();
                mainHandler.post(() -> callback.onError(msg));
            }
        });
    }

    public void getWorkers(Callback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                String body = get("/workers");
                JSONArray arr = csvRows(body);
                mainHandler.post(() -> callback.onSuccess(arr));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "Network error" : e.getMessage();
                mainHandler.post(() -> callback.onError(msg));
            }
        });
    }

    public void getShifts(Callback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                String body = get("/shift");
                JSONArray arr = csvRows(body);
                mainHandler.post(() -> callback.onSuccess(arr));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "Network error" : e.getMessage();
                mainHandler.post(() -> callback.onError(msg));
            }
        });
    }

    private boolean postLocationCsv(String path, String json) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/csv; charset=utf-8");
            byte[] data = csvBody(new JSONObject(json)).getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return true;
            }
            return code >= 400 && code < 500
                    && code != 401 && code != 404 && code != 408 && code != 429;
        } catch (IOException | JSONException e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String get(String path) throws IOException {
        HttpURLConnection conn = null;
        try {
            String baseUrl = getBaseUrl();
            ServerReadiness.ensureReady(baseUrl);
            if (!baseUrl.equals(getBaseUrl())) {
                throw new IOException("The server address changed. Please try again.");
            }
            conn = open(baseUrl, path, "GET");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String message = "Server returned " + code;
                try {
                    JSONArray rows = csvRows(readAll(conn.getErrorStream()));
                    if (rows.length() > 0) {
                        message = rows.getJSONObject(0).optString("error", message);
                    }
                } catch (JSONException ignored) {
                }
                throw new IOException(message);
            }
            return readAll(conn.getInputStream());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        return open(getBaseUrl(), path, method);
    }

    private HttpURLConnection open(String baseUrl, String path, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "text/csv");
        if (authToken != null) conn.setRequestProperty("Authorization", "Bearer " + authToken);
        return conn;
    }

    static String csvBody(JSONObject body) throws JSONException {
        List<String> header = new ArrayList<>();
        List<String> values = new ArrayList<>();
        Iterator<String> keys = body.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = body.get(key);
            header.add(key);
            values.add(value == JSONObject.NULL ? "" : String.valueOf(value));
        }
        return CsvUtil.toRow(header.toArray(new String[0])) + "\n"
                + CsvUtil.toRow(values.toArray(new String[0])) + "\n";
    }

    static JSONArray csvRows(String text) throws JSONException {
        JSONArray result = new JSONArray();
        if (text == null || text.trim().isEmpty()) return result;
        List<String[]> rows = new ArrayList<>();
        StringBuilder record = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                record.append(c);
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    record.append(text.charAt(++i));
                } else {
                    quoted = !quoted;
                }
            } else if (c == '\n' && !quoted) {
                rows.add(CsvUtil.parseCsvLine(record.toString()));
                record.setLength(0);
            } else if (c != '\r' || quoted) {
                record.append(c);
            }
        }
        if (quoted) throw new JSONException("Unclosed CSV quote");
        if (record.length() > 0) rows.add(CsvUtil.parseCsvLine(record.toString()));
        if (rows.isEmpty()) return result;
        String[] header = rows.get(0);
        for (int i = 1; i < rows.size(); i++) {
            String[] cells = rows.get(i);
            if (cells.length == 1 && cells[0].isEmpty()) continue;
            if (cells.length != header.length) throw new JSONException("Invalid CSV columns");
            JSONObject row = new JSONObject();
            for (int j = 0; j < header.length; j++) row.put(header[j], cells[j]);
            result.put(row);
        }
        if (rows.size() == 1) throw new JSONException("Expected CSV data");
        return result;
    }

    private String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
