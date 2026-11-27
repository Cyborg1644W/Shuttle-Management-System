package com.shuttle.mobile.ui;

import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.shuttle.mobile.ui.FleetStatusMapUiState.*;

/** Poll only while Home is visible. A failed poll never acts as an empty fleet snapshot. */
public final class FleetMonitorViewModel extends ViewModel {
    private final MutableLiveData<FleetStatusMapUiState> state = new MutableLiveData<>(new FleetStatusMapUiState());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::fetch;
    private boolean visible, online, fetching;
    private int generation, failures;
    private long nextFeedProbe;
    private FleetApi.Call inFlight;
    private java.util.concurrent.Future<?> decoding;
    double manualLat, manualLon, manualZoom;

    LiveData<FleetStatusMapUiState> updates() { return state; }
    private FleetStatusMapUiState current() { return state.getValue(); }

    void visible(boolean value) {
        if (visible == value) return;
        visible = value;
        restart();
    }
    void network(boolean value) {
        if (value == online && (value || current().connection == Connection.OFFLINE)) return;
        online = value;
        state.setValue(current().connection(value ? (current().loaded ? Connection.RECONNECTING : Connection.LOADING)
                : Connection.OFFLINE));
        restart();
    }
    void retry() {
        if (visible && online && !fetching) {
            main.removeCallbacks(poll);
            fetch();
        }
    }
    void select(String id) { state.setValue(current().camera(Camera.FOLLOW, id)); }
    void overview() { state.setValue(current().camera(Camera.OVERVIEW, null)); }
    void manual() {
        if (current().camera != Camera.MANUAL) state.setValue(current().camera(Camera.MANUAL, current().selected));
    }
    private void restart() {
        generation++;
        if (inFlight != null) inFlight.cancel();
        if (decoding != null) decoding.cancel(true);
        inFlight = null;
        decoding = null;
        failures = 0;
        fetching = false;
        main.removeCallbacks(poll);
        if (visible && online) fetch();
    }
    private void fetch() {
        if (fetching || !visible || !online) return;
        fetching = true;
        int request = generation;
        FleetStatusMapUiState prior = current();
        if (nextFeedProbe != 0 && System.nanoTime() < nextFeedProbe) {
            fetchLegacy(request, prior);
            return;
        }
        StringBuilder path = new StringBuilder("/live/fleet");
        for (Unit u : prior.units.values()) {
            if (u.last == null) continue;
            if (path.length() > 6000) break; // Remaining shifts receive a full, deduplicated delta.
            path.append(path.indexOf("?") < 0 ? '?' : '&').append(FleetApi.encode(u.shift)).append('=').append(u.last.time);
        }
        inFlight = FleetApi.poll(path.toString(), new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject json) {
                if (request != generation) return;
                JSONArray fleet = json.optJSONArray("fleet");
                JSONObject first = fleet == null ? null : fleet.optJSONObject(0);
                if (fleet != null && (fleet.length() == 0 || (first != null && !first.has("type")
                        && first.has("shuttleId") && first.has("positionTime")))) {
                    useLegacy(request, prior);
                    return;
                }
                nextFeedProbe = 0;
                decode(request, prior, json);
            }
            @Override public void onError(int status, String message) {
                if (request != generation) return;
                if (status == 404 || status == 405) useLegacy(request, prior);
                else failed(request, status, message);
            }
        });
    }

    private void useLegacy(int request, FleetStatusMapUiState prior) {
        nextFeedProbe = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
        fetchLegacy(request, prior);
    }

    private void fetchLegacy(int request, FleetStatusMapUiState prior) {
        LegacyFleetSnapshot legacy = new LegacyFleetSnapshot();
        FleetApi.Callback sequence = new FleetApi.Callback() {
            int index;
            @Override public void onSuccess(JSONObject reply) {
                if (request != generation) return;
                legacy.replies[index++] = reply;
                if (index < LegacyFleetSnapshot.PATHS.length) {
                    inFlight = FleetApi.poll(LegacyFleetSnapshot.PATHS[index], this);
                    return;
                }
                decoding = decoder.submit(() -> {
                    try {
                        JSONObject snapshot = legacy.snapshot();
                        main.post(() -> { if (request == generation) decode(request, prior, snapshot); });
                    } catch (org.json.JSONException invalid) {
                        main.post(() -> failed(request, -1, "Fleet records could not be matched. Refreshing."));
                    }
                });
            }
            @Override public void onError(int status, String message) { failed(request, status, message); }
        };
        inFlight = FleetApi.poll(LegacyFleetSnapshot.PATHS[0], sequence);
    }

    private void decode(int request, FleetStatusMapUiState prior, JSONObject json) {
        decoding = decoder.submit(() -> {
            try {
                JSONArray rows = json.getJSONArray("fleet");
                JSONObject header = rows.getJSONObject(0);
                if (!"fleet".equals(header.getString("type"))) throw new IllegalArgumentException("Fleet header missing");
                if (header.has("activeShifts") && header.getInt("activeShifts") != rows.length() - 1) {
                    throw new IllegalArgumentException("Incomplete fleet snapshot");
                }
                String scope = FleetApi.text(header, "terminalId");
                GeoPoint terminal = StreetMapCard.terminalPoint(header);
                Map<String,Unit> units = units(rows, scope.equals(prior.terminalId)
                        ? prior.units : java.util.Collections.emptyMap());
                Totals totals = header.has("totalUnits") ? new Totals(header.getInt("totalUnits"),
                        header.getInt("operatingUnits"), header.getInt("availableUnits"), header.getInt("driversActive")) : null;
                main.post(() -> {
                    if (request != generation) return;
                    state.setValue(current().snapshot(units, scope, FleetApi.text(header, "terminal"),
                            terminal == null ? Double.NaN : terminal.getLatitude(),
                            terminal == null ? Double.NaN : terminal.getLongitude(), totals));
                    failures = 0;
                    schedule();
                });
            } catch (RuntimeException | org.json.JSONException invalid) {
                main.post(() -> failed(request, -1, "The fleet snapshot is incomplete. Refreshing."));
            }
        });
    }

    private static Map<String,Unit> units(JSONArray rows, Map<String,Unit> prior) throws org.json.JSONException {
        Map<String,Unit> result = new LinkedHashMap<>();
        Set<Integer> colors = new HashSet<>();
        for (int i = 1; i < rows.length(); i++) {
            Unit old = prior.get(rows.getJSONObject(i).getString("shiftId"));
            if (old != null) colors.add(old.color);
        }
        for (int i = 1; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String shift = row.getString("shiftId"), id = row.getString("shuttleId");
            if (!"unit".equals(row.getString("type")) || shift.isEmpty() || id.isEmpty() || result.containsKey(shift))
                throw new IllegalArgumentException("Invalid active shift");
            Unit old = prior.get(shift);
            Point point = old == null ? null : old.last;
            point = Point.appendTrail(point, FleetApi.text(row, "trail"));
            int color = old == null ? assignColor(id, colors) : old.color;
            colors.add(color);
            long reported = row.optLong("positionTime", 0);
            boolean latest = point != null && reported == point.time;
            double speed = old == null ? 0 : old.speed, bearing = old == null ? Double.NaN : old.bearing;
            String plate = FleetApi.text(row, "plateNumber");
            String driver = FleetApi.text(row, "driverName");
            String terminal = FleetApi.text(row, "terminal");
            if (latest) {
                speed = row.optDouble("speedKmh", 0);
                bearing = row.optDouble("bearing", Double.NaN);
            }
            if (old != null && old.last == point && old.id.equals(id) && old.plate.equals(plate)
                    && old.driver.equals(driver) && old.terminal.equals(terminal)
                    && Double.compare(old.speed, speed) == 0 && Double.compare(old.bearing, bearing) == 0) {
                result.put(shift, old);
            } else {
                result.put(shift, new Unit(shift, id, plate, driver, terminal, color, point, speed, bearing));
            }
        }
        return result.equals(prior) ? prior : result;
    }
    private void failed(int request, int status, String message) {
        if (request != generation) return;
        FleetStatusMapUiState retained = status == 401 || status == 403 ? new FleetStatusMapUiState() : current();
        failures = Math.min(3, failures + 1);
        Connection next = status == 0 || status == 408 || status == 429 || status >= 500
                ? Connection.RECONNECTING : Connection.ERROR;
        state.setValue(retained.connection(online ? next : Connection.OFFLINE, message));
        schedule();
    }
    private void schedule() {
        fetching = false;
        main.removeCallbacks(poll);
        inFlight = null;
        decoding = null;
        if (visible && online) main.postDelayed(poll, Math.min(30_000, 5_000L << failures));
    }
    @Override protected void onCleared() {
        visible(false); generation++; decoder.shutdownNow();
    }
}
