package com.shuttle.mobile.ui;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shuttle.mobile.R;
import com.shuttle.mobile.client.ApiClient;
import com.shuttle.mobile.gps.GpsTrackerService;
import com.shuttle.mobile.gps.LiveMapUiState;
import com.shuttle.mobile.gps.RouteRepository;
import org.osmdroid.util.GeoPoint;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class DriverActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;

    private String driverName = "Driver";
    private String employeeId = "-";
    private String email = "";
    private String vehicle = "Unassigned";
    private String terminal = "Unassigned";
    private boolean busy = false;
    private FleetConnectionUi.Membership fleetMembership;
    private MaterialButton btnTerminal;

    private View pageHome;
    private View pageTrips;
    private View pageSchedule;
    private View layoutHeaderTitle;
    private View viewDot;
    private TextView tvHello;
    private TextView tvStatusBadge;
    private TextView tvTimer;
    private TextView tvStatusHint;
    private TextView tvVehicle;
    private TextView tvTerminal;
    private TextView tvStatDistance;
    private TextView tvStatStops;
    private TextView tvStatSpeed;
    private MaterialButton btnShift;
    private LiveRouteMap mapView;
    private RouteRepository routes;
    private LiveMapUiState trip = LiveMapUiState.EMPTY;
    private String owner, gpsShift = "";
    private GeoPoint shiftTerminal, membershipTerminal;
    private boolean routeLoaded, resumed, requestOnStart, uploading, pollingShift;
    private int shiftRequest;
    private long nextUploadAttempt;
    private View tripsEmpty;
    private TextView tripsEmptyTitle, tripsEmptyDescription;
    private LinearLayout listTrips;


    private ImageView ivNavHome;
    private ImageView ivNavTrips;
    private TextView tvNavHome;
    private TextView tvNavTrips;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int currentTab = 0;
    private final Runnable connectionRefresh = new Runnable() {
        @Override public void run() {
            if (Session.isSignedIn()) {
                refreshMembership(false);
                syncFromServer();
            }
            handler.postDelayed(this, 10000);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateLive();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Session.isSignedIn()) {
            signOut();
            return;
        }

        setContentView(R.layout.activity_driver);

        if (Session.getName() != null && !Session.getName().isEmpty()) {
            driverName = Session.getName();
        }
        if (Session.getEmployeeId() != null && !Session.getEmployeeId().isEmpty()) {
            employeeId = Session.getEmployeeId();
        }
        if (Session.getEmail() != null && Session.getEmail().contains("@")) {
            email = Session.getEmail();
        }

        pageHome = findViewById(R.id.pageHome);
        pageTrips = findViewById(R.id.pageTrips);
        pageSchedule = findViewById(R.id.pageDriverSchedule);
        SchedulePage.bind(this, pageSchedule);
        layoutHeaderTitle = findViewById(R.id.layoutHeaderTitle);
        viewDot = findViewById(R.id.viewDot);
        tvHello = findViewById(R.id.tvHello);
        tvStatusBadge = findViewById(R.id.tvStatusBadge);
        tvTimer = findViewById(R.id.tvTimer);
        tvStatusHint = findViewById(R.id.tvStatusHint);
        tvVehicle = findViewById(R.id.tvVehicle);
        tvTerminal = findViewById(R.id.tvTerminal);
        tvStatDistance = findViewById(R.id.tvStatDistance);
        tvStatStops = findViewById(R.id.tvStatStops);
        tvStatSpeed = findViewById(R.id.tvStatSpeed);
        btnShift = findViewById(R.id.btnShift);
        btnTerminal = findViewById(R.id.btnTerminal);
        btnTerminal.setOnClickListener(v -> showTerminalConnection());
        mapView = findViewById(R.id.mapView);
        tripsEmpty = findViewById(R.id.tripsEmptyState);
        tripsEmptyTitle = tripsEmpty.findViewById(R.id.tvEmptyTitle);
        tripsEmptyDescription = tripsEmpty.findViewById(R.id.tvEmptyDescription);
        ((ImageView) tripsEmpty.findViewById(R.id.ivEmptyState)).setImageResource(R.drawable.ic_vehicle);
        listTrips = findViewById(R.id.listTrips);


        ivNavHome = findViewById(R.id.ivNavHome);
        ivNavTrips = findViewById(R.id.ivNavTrips);
        tvNavHome = findViewById(R.id.tvNavHome);
        tvNavTrips = findViewById(R.id.tvNavTrips);

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        TextView tvGreeting = findViewById(R.id.tvGreeting);
        if (hour < 12) {
            tvGreeting.setText("Good morning");
        } else if (hour < 18) {
            tvGreeting.setText("Good afternoon");
        } else {
            tvGreeting.setText("Good evening");
        }

        fillProfile();


        findViewById(R.id.navHome).setOnClickListener(v -> setTab(0));
        findViewById(R.id.navDriverSchedule).setOnClickListener(v -> setTab(2));
        findViewById(R.id.navTrips).setOnClickListener(v -> setTab(1));
        findViewById(R.id.flBell).setOnClickListener(v -> showNotifications());
        viewDot.setVisibility(View.GONE);
        findViewById(R.id.btnDriverProfile).setOnClickListener(v -> showAccountMenu());
        findViewById(R.id.btnJoinTerminal).setOnClickListener(v -> showTerminalConnection());
        btnShift.setOnClickListener(v -> onShiftButton());


        owner = ApiClient.getBaseUrl() + "\n" + employeeId;
        routes = RouteRepository.open(this, owner);
        routes.updates().observe(this, this::onRouteChanged);
        setTab(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Session.isSignedIn() && tvHello != null) {
            driverName = Session.getName() == null ? "" : Session.getName();
            email = Session.getEmail() == null ? "" : Session.getEmail();
            fillProfile();
        }
        if (!Session.isSignedIn()) {
            return;
        }
        resumed = true;
        if (currentTab == 0) mapView.resume();
        ensureTracking();
        refreshShiftUi();
        refreshMembership();
        syncFromServer();
        handler.removeCallbacks(connectionRefresh);
        handler.postDelayed(connectionRefresh, 10000);
        if (currentTab == 1) {
            renderTrips();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        gpsShift = "";
        if (mapView != null) mapView.pause();
        super.onPause();
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(connectionRefresh);
    }

    @Override
    protected void onDestroy() {
        if (mapView != null) mapView.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (currentTab != 0) {
            setTab(0);
        } else {
            super.onBackPressed();
        }
    }

    private void handleError(int status, String message) {
        if (status == 401) {
            Toast.makeText(this, "Your session ended. Please sign in again.", Toast.LENGTH_LONG).show();
            signOut();
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        refreshShiftUi();
    }

    private boolean isFleetJoined() {
        return fleetMembership != null && fleetMembership.joined;
    }

    private void showTerminalConnection() {
        if (fleetMembership == null) {
            refreshMembership();
            Toast.makeText(this, "Checking your terminal connection…", Toast.LENGTH_SHORT).show();
            return;
        }
        FleetConnectionUi.showDriver(this, fleetMembership, () -> {
            refreshMembership();
            syncFromServer();
        });
    }

    private void refreshMembership() {
        refreshMembership(true);
    }

    private void refreshMembership(boolean reportErrors) {
        FleetApi.get("/fleet", new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject json) {
                if (isFinishing() || isDestroyed()) return;
                fleetMembership = new FleetConnectionUi.Membership(json);
                membershipTerminal = isFleetJoined() ? terminalPoint(json) : null;
                mapView.setTerminal(trip.active() && shiftTerminal != null ? shiftTerminal : membershipTerminal);
                terminal = isFleetJoined() ? fleetMembership.terminalName : "No terminal yet";
                btnTerminal.setText(isFleetJoined() ? "View Terminal →" : "Join terminal");
                refreshShiftUi();

            }
            @Override public void onError(int status, String message) {
                if (isFinishing() || isDestroyed()) return;
                btnTerminal.setText("Check terminal connection");
                refreshShiftUi();
                if (reportErrors || status == 401) handleError(status, message);
            }
        });
    }

    private void syncFromServer() {
        String id = Session.getEmployeeId();
        if (id == null || id.isEmpty()) {
            return;
        }

        FleetApi.get("/workers/" + FleetApi.encode(id), new FleetApi.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                if (isFinishing()) {
                    return;
                }
                JSONObject w = json.optJSONObject("worker");
                if (w == null) {
                    w = json;
                }
                String v = FleetApi.text(w, "shuttleId");
                String t = FleetApi.text(w, "terminal");
                vehicle = v.isEmpty() ? "Unassigned" : v;
                terminal = isFleetJoined() ? fleetMembership.terminalName
                        : (t.isEmpty() ? "No terminal yet" : t);
                refreshShiftUi();
            }

            @Override
            public void onError(int status, String message) {
                if (isFinishing()) {
                    return;
                }
                if (status == 401) {
                    handleError(status, message);
                }
            }
        });

        if (!routeLoaded || busy) return;
        if (trip.pendingUpload) {
            uploadEndedShift();
            return;
        }
        if (pollingShift) return;
        pollingShift = true;
        int request = shiftRequest;
        FleetApi.get("/shift/active", new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject json) {
                pollingShift = false;
                if (isFinishing() || isDestroyed() || request != shiftRequest) return;
                JSONObject active = json.optJSONObject("shift");
                if (active != null) applyServerShift(active);
                else if (trip.active()) routes.finish(trip.shiftId, System.currentTimeMillis(), false);
            }
            @Override public void onError(int status, String message) {
                pollingShift = false;
                if (!isFinishing() && !isDestroyed() && request == shiftRequest && status == 401) handleError(status, message);
            }
        });
    }

    private static GeoPoint terminalPoint(JSONObject row) {
        JSONObject coords = row.optJSONObject("terminalCoordinates");
        double lat = coords == null ? row.optDouble("terminalLatitude", Double.NaN) : coords.optDouble("latitude", Double.NaN);
        double lon = coords == null ? row.optDouble("terminalLongitude", Double.NaN) : coords.optDouble("longitude", Double.NaN);
        return LiveMapUiState.valid(lat, lon) ? new GeoPoint(lat, lon) : null;
    }

    private boolean applyServerShift(JSONObject shift) {
        String id = FleetApi.text(shift, "shiftId");
        long start = shift.optLong("startTime", 0);
        String unit = FleetApi.text(shift, "shuttleId");
        if (id.isEmpty() || start <= 0 || unit.isEmpty()) return false;
        GeoPoint assigned = terminalPoint(shift);
        boolean newShift = !id.equals(trip.shiftId) || start != trip.start;
        if (newShift || assigned != null) shiftTerminal = assigned;
        GeoPoint center = shiftTerminal != null ? shiftTerminal : membershipTerminal;
        mapView.setTerminal(center);
        if (newShift) routes.begin(id, unit, start, center == null ? Double.NaN : center.getLatitude(),
                center == null ? Double.NaN : center.getLongitude());
        return true;
    }

    private void onRouteChanged(LiveMapUiState next) {
        if (isFinishing() || isDestroyed()) return;
        boolean firstLoad = !routeLoaded;
        boolean endedHere = trip.active() && next.pendingUpload;
        boolean changed = firstLoad || next.start != trip.start || next.end != trip.end
                || next.pendingUpload != trip.pendingUpload;
        routeLoaded = true;
        trip = next;
        if (changed) {
            busy = false;
            if (next.active()) {
                vehicle = next.shuttleId;
                if (LiveMapUiState.valid(next.terminalLat, next.terminalLon)) {
                    shiftTerminal = new GeoPoint(next.terminalLat, next.terminalLon);
                    mapView.setTerminal(shiftTerminal);
                }
            } else {
                gpsShift = "";
                shiftTerminal = null;
            }
            ensureTracking();
            refreshShiftUi();
            if (endedHere && resumed) {
                com.google.android.material.snackbar.Snackbar.make(pageHome, "Shift ended. Route saved on this phone.", 5000)
                        .setAction("View route", v -> mapView.requestRectangleOnScreen(
                                new android.graphics.Rect(0, 0, mapView.getWidth(), mapView.getHeight()))).show();
            }
            if (next.pendingUpload) uploadEndedShift();
            else if (firstLoad && resumed) syncFromServer();
        } else updateLive();
    }

    private void ensureTracking() {
        if (!resumed || !trip.active()) return;
        if (GpsTrackerService.hasPermission(this)) {
            requestOnStart = false;
            if (gpsShift.equals(trip.shiftId)) return;
            Intent service = new Intent(this, GpsTrackerService.class)
                    .putExtra(GpsTrackerService.EXTRA_OWNER, owner)
                    .putExtra(GpsTrackerService.EXTRA_SHIFT, trip.shiftId)
                    .putExtra(GpsTrackerService.EXTRA_SHUTTLE_ID, trip.shuttleId);
            try {
                ContextCompat.startForegroundService(this, service);
                gpsShift = trip.shiftId;
            } catch (IllegalStateException | SecurityException e) {
                Toast.makeText(this, "Location tracking could not start.", Toast.LENGTH_SHORT).show();
            }
        } else if (requestOnStart) {
            requestOnStart = false;
            if (!permissionNeedsSettings()) requestLocationPermission();
        }
    }

    private boolean permissionNeedsSettings() {
        return getPreferences(MODE_PRIVATE).getBoolean("askedForLocation", false)
                && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                && !GpsTrackerService.hasPermission(this);
    }

    private void requestLocationPermission() {
        if (!trip.active() || !resumed) return;
        if (permissionNeedsSettings()) {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
            return;
        }
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != LOCATION_PERMISSION_REQUEST_CODE) return;
        if (results.length > 0) getPreferences(MODE_PRIVATE).edit().putBoolean("askedForLocation", true).apply();
        ensureTracking();
        updateLive();
    }

    private void setTab(int tab) {
        currentTab = tab;
        pageHome.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        pageTrips.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        pageSchedule.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        if (resumed) {
            if (tab == 0) {
                mapView.resume();
                updateLive();
            } else mapView.pause();
        }
        if (tab == 2) SchedulePage.refresh(pageSchedule);

        layoutHeaderTitle.setVisibility(View.VISIBLE);

        styleNav(ivNavHome, tvNavHome, tab == 0);
        styleNav(ivNavTrips, tvNavTrips, tab == 1);
        styleNav(findViewById(R.id.ivNavDriverSchedule), findViewById(R.id.tvNavDriverSchedule), tab == 2);

        if (tab == 1) {
            renderTrips();
        }
    }

    private void styleNav(ImageView icon, TextView label, boolean selected) {
        int color = ContextCompat.getColor(this, selected ? R.color.brand_600 : R.color.text_secondary);
        icon.setImageTintList(ColorStateList.valueOf(color));
        label.setTextColor(color);
        if (selected) {
            icon.setBackgroundResource(R.drawable.chip_bg);
        } else {
            icon.setBackground(null);
        }
    }

    private void fillProfile() {
        String name = driverName == null ? "" : driverName.trim();
        String first = name.isEmpty() ? "Driver" : name.split("\\s+")[0];
        tvHello.setText(new java.text.SimpleDateFormat("'As of' EEEE, MMM d, yyyy",
                java.util.Locale.getDefault()).format(new java.util.Date()));
        ((TextView) findViewById(R.id.tvGreeting)).setText(getString(R.string.welcome_name, first));
    }

    private void showNotifications() {
        viewDot.setVisibility(View.GONE);
        startActivity(new Intent(this, AccountActivity.class).putExtra("notifications", true));
    }

    private void showAccountMenu() {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, findViewById(R.id.btnDriverProfile));
        menu.getMenu().add("My Profile");
        menu.getMenu().add("Sign out");
        menu.setOnMenuItemClickListener(item -> {
            if ("My Profile".contentEquals(item.getTitle())) startActivity(new Intent(this, AccountActivity.class));
            else AccountActivity.confirmSignOut(this, this::signOut);
            return true;
        });
        menu.show();
    }

    private void refreshShiftUi() {
        boolean active = trip.active();

        styleBadge(tvStatusBadge, active ? "ON SHIFT" : "OFF DUTY");
        tvStatusHint.setText(active ? "Shift in progress" : isFleetJoined()
                ? "Ready to start your shift" : fleetMembership == null
                ? "Checking your terminal connection…" : "Join your operator's terminal to start a shift");
        boolean assigned = !vehicle.isEmpty() && !"Unassigned".equalsIgnoreCase(vehicle);
        btnShift.setEnabled(routeLoaded && !busy && !trip.pendingUpload && (active || (isFleetJoined() && assigned)));
        btnShift.setVisibility(active || isFleetJoined() ? View.VISIBLE : View.GONE);
        btnTerminal.setVisibility(isFleetJoined() || fleetMembership == null ? View.VISIBLE : View.GONE);
        findViewById(R.id.layoutDriverNoTerminal).setVisibility(fleetMembership != null && !isFleetJoined() ? View.VISIBLE : View.GONE);
        tvTimer.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active && isFleetJoined() && !assigned) tvStatusHint.setText("Your operator must assign a vehicle before you can start.");
        boolean ended = trip.end > 0;
        ((TextView) findViewById(R.id.routeTimeLabel)).setText(ended ? R.string.route_duration : R.string.route_stops);
        ((TextView) findViewById(R.id.routeSpeedLabel)).setText(ended ? R.string.route_avg_speed : R.string.route_speed);
        ((TextView) findViewById(R.id.routeTitle)).setText(ended ? R.string.route_summary_title : R.string.route_live_title);
        findViewById(R.id.routePending).setVisibility(trip.pendingUpload ? View.VISIBLE : View.GONE);
        findViewById(R.id.layoutDriverLiveStats).setVisibility(active || ended ? View.VISIBLE : View.GONE);

        if (active) {
            btnShift.setText("□ End Shift");
            btnShift.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.danger)));
        } else {
            btnShift.setText("▷ Start Shift");
            btnShift.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.brand_600)));
        }

        tvVehicle.setText(vehicle);
        tvTerminal.setText(terminal);

        handler.removeCallbacks(ticker);
        if (active && resumed) {
            handler.post(ticker);
        } else {
            updateLive();
        }
    }

    private void onShiftButton() {
        if (busy) {
            return;
        }

        if (!trip.active()) {
            if (!isFleetJoined()) {
                showTerminalConnection();
                return;
            }
            startShift();
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("End shift?")
                    .setMessage("This ends your shift and saves it to your trips.")
                    .setPositiveButton("End Shift", (dialog, which) -> endShift())
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private void startShift() {
        ++shiftRequest;
        setBusy(true);

        FleetApi.post("/shift/start", null, new FleetApi.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                if (isFinishing() || isDestroyed()) return;
                if (!applyServerShift(json)) {
                    setBusy(false);
                    syncFromServer();
                    handleError(0, "Could not read the new shift. Checking its status.");
                    return;
                }
                requestOnStart = true;
                Toast.makeText(DriverActivity.this, "Shift started", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(int status, String message) {
                if (isFinishing()) {
                    return;
                }
                setBusy(false);
                handleError(status, message);
            }
        });
    }

    private void endShift() {
        if (!trip.active() || busy) return;
        ++shiftRequest;
        setBusy(true);
        routes.finish(trip.shiftId, System.currentTimeMillis(), true);
    }

    private void uploadEndedShift() {
        if (uploading || !resumed || !trip.pendingUpload || android.os.SystemClock.elapsedRealtime() < nextUploadAttempt) return;
        uploading = true;
        LiveMapUiState completed = trip;
        // The trip is immutable. Build potentially large CSV geometry off the UI thread.
        new Thread(() -> {
            JSONObject body = new JSONObject();
            try {
                body.put("shiftId", completed.shiftId);
                body.put("endTime", completed.end);
                body.put("distanceKm", completed.last == null ? 0 : completed.last.distanceKm);
                body.put("stops", 0);
                body.put("route", completed.routeString());
            } catch (JSONException invalid) {
                throw new IllegalStateException(invalid);
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !Session.isSignedIn()
                        || !owner.equals(ApiClient.getBaseUrl() + "\n" + Session.getEmployeeId())) return;
                FleetApi.post("/shift/end", body, new FleetApi.Callback() {
                    @Override public void onSuccess(JSONObject reply) {
                        uploading = false;
                        if (!completed.shiftId.equals(FleetApi.text(reply, "shiftId"))) return;
                        routes.finish(completed.shiftId, completed.end, false);
                    }
                    @Override public void onError(int status, String message) {
                        uploading = false;
                        nextUploadAttempt = android.os.SystemClock.elapsedRealtime() + 10_000;
                        if (!isFinishing() && !isDestroyed() && status > 0 && status < 500) handleError(status, message);
                    }
                });
            });
        }, "trip-summary").start();
    }

    private void updateLive() {
        long elapsed = trip.start == 0 ? 0 : (trip.active() ? System.currentTimeMillis() : trip.end) - trip.start;
        tvTimer.setText(FleetApi.formatDuration(elapsed));
        double distanceKm = trip.last == null ? 0 : trip.last.distanceKm;
        tvStatDistance.setText(String.format(Locale.US, "%.1f km", distanceKm));
        tvStatStops.setText(trip.end > 0 ? FleetApi.formatDuration(elapsed) : "-");
        if (trip.end > 0 && elapsed > 0) {
            tvStatSpeed.setText(String.format(Locale.US, "%.0f km/h", distanceKm * 3_600_000 / elapsed));
        } else if (trip.phase(System.currentTimeMillis()) == LiveMapUiState.Phase.TRACKING) {
            tvStatSpeed.setText(String.format(Locale.US, "%.0f km/h", trip.last.speed * 3.6));
        } else {
            tvStatSpeed.setText("—");
        }
        if (currentTab == 0) mapView.render(trip, GpsTrackerService.hasPermission(this), this::requestLocationPermission);
    }

    private void renderTrips() {
        if (listTrips.getChildCount() == 0) {
            showTripsState("Loading recent trips…", "", false);
        }
        FleetApi.get("/shift?summary=true", new FleetApi.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                if (isFinishing()) {
                    return;
                }
                showTrips(json.optJSONArray("shifts"));
            }

            @Override
            public void onError(int status, String message) {
                if (isFinishing()) {
                    return;
                }
                showTripsState("Could not load recent trips", message + " · Tap to retry", true);
                handleError(status, message);
            }
        });
    }

    private void showTrips(JSONArray shifts) {
        listTrips.removeAllViews();

        SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, MMM d", Locale.getDefault());
        SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());

        int shown = 0;
        int count = shifts == null ? 0 : shifts.length();

        for (int i = 0; i < count; i++) {
            final JSONObject e = shifts.optJSONObject(i);
            if (e == null || e.optLong("endTime", 0) <= 0) {
                continue;
            }

            long start = e.optLong("startTime", 0);
            long end = e.optLong("endTime", 0);
            final String shiftId = FleetApi.text(e, "shiftId");

            View row = LayoutInflater.from(this).inflate(R.layout.item_trip_row, listTrips, false);

            ((TextView) row.findViewById(R.id.tvTripDate)).setText(dateFmt.format(new Date(start)));
            ((TextView) row.findViewById(R.id.tvTripTime)).setText(
                    timeFmt.format(new Date(start)) + " - " + timeFmt.format(new Date(end)));
            TextView tripVehicle = row.findViewById(R.id.tvTripVehicle);
            String shuttleId = FleetApi.text(e, "shuttleId");
            tripVehicle.setText(shuttleId.isEmpty() ? "Vehicle not recorded" : "Vehicle · " + shuttleId);
            ((TextView) row.findViewById(R.id.tvTripDuration)).setText(
                    FleetApi.formatDuration(e.optLong("durationMs", end - start)));
            ((TextView) row.findViewById(R.id.tvTripDistance)).setText(
                    String.format(Locale.US, "%.1f km", e.optDouble("distanceKm", 0)));

            row.setOnClickListener(v -> ShiftSummaryActivity.showDetails(this, shiftId));

            listTrips.addView(row);
            shown++;
        }

        if (shown == 0) showTripsState("No recent trips yet", "Your completed trips will appear here.", false);
        else tripsEmpty.setVisibility(View.GONE);
    }

    private void showTripsState(String title, String description, boolean retry) {
        tripsEmpty.setVisibility(View.VISIBLE);
        tripsEmptyTitle.setText(title);
        tripsEmptyDescription.setText(description);
        tripsEmptyDescription.setVisibility(description.isEmpty() ? View.GONE : View.VISIBLE);
        tripsEmpty.setOnClickListener(retry ? v -> renderTrips() : null);
        tripsEmpty.setClickable(retry);
    }

    private void signOut() {
        FleetApi.post("/auth/logout", null, null);
        stopService(new Intent(this, GpsTrackerService.class));
        Session.clear();
        ApiClient.getInstance().setAuthToken(null);

        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    private void styleBadge(TextView badge, String status) {
        int bg;
        int dot;
        int text;

        if (status.equals("OFF DUTY")) {
            bg = R.color.badge_off_bg;
            dot = R.color.text_secondary;
            text = R.color.text_secondary;
        } else {
            bg = R.color.badge_on_bg;
            dot = R.color.brand_600;
            text = R.color.brand_600;
        }

        SpannableString s = new SpannableString("● " + status);
        s.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, dot)),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, text)),
                1, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        badge.setText(s);
        badge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, bg)));
    }

    private String initials(String name) {
        String[] parts = name.trim().split("\\s+");
        String result = "";
        for (int i = 0; i < parts.length && i < 2; i++) {
            if (!parts[i].isEmpty()) {
                result += parts[i].substring(0, 1).toUpperCase();
            }
        }
        return result.isEmpty() ? "D" : result;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
