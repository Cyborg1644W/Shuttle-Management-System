package com.shuttle.mobile.client;

import android.os.Handler;
import android.os.Looper;

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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Talks to the shuttle backend using plain HttpURLConnection.
 *
 * Driver side:    sendLocation(...)  -> POST /shuttles/{id}/location
 * Passenger side: getShuttles(...)   -> GET  /shuttles
 *
 * Network calls never run on the main thread. Callbacks are delivered
 * on the main thread so you can update the UI directly.
 */
public class ApiClient {

    // 10.0.2.2 = your computer's localhost when using the Android emulator.
    // On a real phone, use your computer's LAN IP, e.g. http://192.168.1.10:8080/api
    public static final String BASE_URL = "http://10.0.2.2:8080/api";

    private static final int TIMEOUT_MS = 8000;

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    private static ApiClient instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String authToken; // optional

    private ApiClient() { }

    public static synchronized ApiClient getInstance() {
        if (instance == null) instance = new ApiClient();
        return instance;
    }

    public void setAuthToken(String token) {
        this.authToken = token;
    }

    // ------------------------------------------------------------------
    // Driver: send GPS location
    // ------------------------------------------------------------------

    /** Builds the JSON body used for a location update (also what gets queued offline). */
    public static String buildLocationJson(String shuttleId, double lat, double lng,
                                           float speed, long timestamp) {
        try {
            JSONObject o = new JSONObject();
            o.put("shuttleId", shuttleId);
            o.put("latitude", lat);
            o.put("longitude", lng);
            o.put("speed", speed);
            o.put("timestamp", timestamp);
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** Async version, result delivered on main thread. */
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

    /**
     * Blocking version. Call ONLY from a background thread.
     * Used by GpsTrackerService and by the offline queue flush.
     * Returns true if the server accepted it (HTTP 2xx).
     */
    public boolean postLocationBlocking(String json) {
        String shuttleId;
        try {
            shuttleId = new JSONObject(json).getString("shuttleId");
        } catch (JSONException e) {
            return true; // corrupt entry: report "handled" so it gets dropped from the queue
        }
        return postJson("/shuttles/" + shuttleId + "/location", json);
    }

    // ------------------------------------------------------------------
    // Passenger: get shuttles
    // ------------------------------------------------------------------

    public void getShuttles(Callback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                String body = get("/shuttles");
                JSONArray arr = new JSONArray(body);
                mainHandler.post(() -> callback.onSuccess(arr));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "Network error" : e.getMessage();
                mainHandler.post(() -> callback.onError(msg));
            }
        });
    }

    // ------------------------------------------------------------------
    // Low-level HTTP helpers
    // ------------------------------------------------------------------

    private boolean postJson(String path, String json) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String get(String path) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "GET");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("Server returned " + code);
            return readAll(conn.getInputStream());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        if (authToken != null) conn.setRequestProperty("Authorization", "Bearer " + authToken);
        return conn;
    }

    private String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}