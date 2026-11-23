package com.shuttle.mobile.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.shuttle.mobile.R;
import com.shuttle.mobile.client.ApiClient;
import com.shuttle.mobile.gps.GpsTrackerService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class DriverActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private static final String ACTION_LOCATION_UPDATE = "com.shuttle.mobile.LOCATION_UPDATE";

    private String driverName = "Driver";
    private String employeeId = "-";
    private String email = "";
    private String vehicle = "Unassigned";
    private String terminal = "Unassigned";
    private long activeStart = 0;
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
    private PureJavaMapCanvas mapView;
    private View tripsEmpty;
    private TextView tripsEmptyTitle, tripsEmptyDescription;
    private LinearLayout listTrips;


    private ImageView ivNavHome;
    private ImageView ivNavTrips;
    private TextView tvNavHome;
    private TextView tvNavTrips;

    private final ArrayList<double[]> liveTrail = new ArrayList<>();
    private double liveDistanceKm = 0;
    private double liveSpeedKmh = 0;
    private long lastPointTime = 0;

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

    private boolean receiverRegistered = false;

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_LOCATION_UPDATE.equals(intent.getAction())) {
                return;
            }
            double lat = intent.getDoubleExtra("latitude", 0.0);
            double lng = intent.getDoubleExtra("longitude", 0.0);
            float speedMs = intent.getFloatExtra("speed", 0f);
            onLivePosition(lat, lng, speedMs);
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


        setTab(0);
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(ACTION_LOCATION_UPDATE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ContextCompat.registerReceiver(this, locationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            }

            receiverRegistered = true;
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(locationReceiver);
            receiverRegistered = false;
        }
        super.onStop();
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
        super.onPause();
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(connectionRefresh);
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

        FleetApi.get("/shift/active", new FleetApi.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                if (isFinishing()) {
                    return;
                }
                if (json.optBoolean("active", false)) {
                    JSONObject s = json.optJSONObject("shift");
                    if (s != null) {
                        activeStart = s.optLong("startTime", 0);
                        String v = FleetApi.text(s, "shuttleId");
                        String t = FleetApi.text(s, "terminal");
                        if (!v.isEmpty()) {
                            vehicle = v;
                        }
                        if (!t.isEmpty()) {
                            terminal = t;
                        }
                    }
                } else if (activeStart > 0) {
                    activeStart = 0;
                    stopService(new Intent(DriverActivity.this, GpsTrackerService.class));
                }
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
    }

    private void requestLocationPermission() {
        boolean fineGranted = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!fineGranted && !coarseGranted) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST_CODE
            );
        } else {
            startGpsTracking();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            boolean granted = false;
            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                }
            }
            if (granted) {
                startGpsTracking();
            } else {
                Toast.makeText(this, "Location permission is needed to track your route",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startGpsTracking() {
        Intent serviceIntent = new Intent(this, GpsTrackerService.class);
        serviceIntent.putExtra(GpsTrackerService.EXTRA_SHUTTLE_ID, vehicle);
        serviceIntent.putExtra(GpsTrackerService.EXTRA_SIMULATE, false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void onLivePosition(double lat, double lng, float speedMs) {
        if (activeStart <= 0) {
            return;
        }

        double[] p = new double[]{lat, lng};
        long now = System.currentTimeMillis();

        liveSpeedKmh = Math.min(speedMs * 3.6, 150);

        if (!liveTrail.isEmpty()) {
            double km = haversineKm(liveTrail.get(liveTrail.size() - 1), p);
            double hours = (now - lastPointTime) / 3600000.0;

            if (km < 0.005) {
                lastPointTime = now;
                if (mapView != null) {
                    mapView.setCurrent(p);
                }
                return;
            }

            boolean jump = hours > 0 && km / hours > 150;
            if (!jump) {
                liveDistanceKm += km;
            }
        }

        lastPointTime = now;
        liveTrail.add(p);

        if (mapView != null) {
            mapView.setTraveled(liveTrail);
            mapView.setCurrent(p);
            mapView.setVisibility(View.VISIBLE);
            findViewById(R.id.tvDriverRouteEmpty).setVisibility(View.GONE);
        }
    }

    private void setTab(int tab) {
        currentTab = tab;
        pageHome.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        pageTrips.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        pageSchedule.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
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
        boolean active = activeStart > 0;

        styleBadge(tvStatusBadge, active ? "ON SHIFT" : "OFF DUTY");
        tvStatusHint.setText(active ? "Shift in progress" : isFleetJoined()
                ? "Ready to start your shift" : fleetMembership == null
                ? "Checking your terminal connection…" : "Join your operator's terminal to start a shift");
        boolean assigned = !vehicle.isEmpty() && !"Unassigned".equalsIgnoreCase(vehicle);
        btnShift.setEnabled(!busy && (active || (isFleetJoined() && assigned)));
        btnShift.setVisibility(active || isFleetJoined() ? View.VISIBLE : View.GONE);
        btnTerminal.setVisibility(isFleetJoined() || fleetMembership == null ? View.VISIBLE : View.GONE);
        findViewById(R.id.layoutDriverNoTerminal).setVisibility(fleetMembership != null && !isFleetJoined() ? View.VISIBLE : View.GONE);
        tvTimer.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active && isFleetJoined() && !assigned) tvStatusHint.setText("Your operator must assign a vehicle before you can start.");
        mapView.setVisibility(liveTrail.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.tvDriverRouteEmpty).setVisibility(liveTrail.isEmpty() ? View.VISIBLE : View.GONE);
        findViewById(R.id.layoutDriverLiveStats).setVisibility(active || !liveTrail.isEmpty() ? View.VISIBLE : View.GONE);

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
        if (active) {
            handler.post(ticker);
        } else {
            updateLive();
        }
    }

    private void onShiftButton() {
        if (busy) {
            return;
        }

        if (activeStart <= 0) {
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
        setBusy(true);

        FleetApi.post("/shift/start", null, new FleetApi.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                if (isFinishing()) {
                    return;
                }
                setBusy(false);

                activeStart = json.optLong("startTime", System.currentTimeMillis());
                String v = FleetApi.text(json, "shuttleId");
                String t = FleetApi.text(json, "terminal");
                if (!v.isEmpty()) {
                    vehicle = v;
                }
                if (!t.isEmpty()) {
                    terminal = t;
                }

                liveTrail.clear();
                liveDistanceKm = 0;
                liveSpeedKmh = 0;
                lastPointTime = 0;

                requestLocationPermission();
                refreshShiftUi();
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
        if (activeStart <= 0) {
            return;
        }

        ArrayList<double[]> points = new ArrayList<>();
        int stride = liveTrail.size() / 80 + 1;
        for (int i = 0; i < liveTrail.size(); i += stride) {
            points.add(liveTrail.get(i));
        }
        if (!liveTrail.isEmpty()) {
            points.add(liveTrail.get(liveTrail.size() - 1));
        }

        JSONObject body = new JSONObject();
        try {
            body.put("distanceKm", liveDistanceKm);
            body.put("stops", 0);
            body.put("route", FleetApi.routeToString(points));
        } catch (JSONException e) {
            return;
        }

        setBusy(true);

        FleetApi.post("/shift/end", body, new FleetApi.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                if (isFinishing()) {
                    return;
                }
                setBusy(false);

                stopService(new Intent(DriverActivity.this, GpsTrackerService.class));
                activeStart = 0;
                refreshShiftUi();

                com.google.android.material.snackbar.Snackbar.make(pageHome,
                        "Shift ended. Trip saved. View it in Fleet.", 5000).show();
            }

            @Override
            public void onError(int status, String message) {
                if (isFinishing()) {
                    return;
                }
                setBusy(false);

                if (status == 409) {
                    stopService(new Intent(DriverActivity.this, GpsTrackerService.class));
                    activeStart = 0;
                    refreshShiftUi();
                }
                handleError(status, message);
            }
        });
    }

    private void updateLive() {
        if (activeStart <= 0) {
            tvTimer.setText("00:00:00");
            tvStatDistance.setText("0.0");
            tvStatStops.setText("—");
            tvStatSpeed.setText("0");
            mapView.setTraveled(new ArrayList<double[]>());
            mapView.setCurrent(null);
            return;
        }

        long elapsed = Math.max(0, System.currentTimeMillis() - activeStart);

        tvTimer.setText(FleetApi.formatDuration(elapsed));
        tvStatDistance.setText(String.format(Locale.US, "%.1f", liveDistanceKm));
        tvStatStops.setText("—");
        tvStatSpeed.setText(String.format(Locale.US, "%.0f", liveSpeedKmh));

        if (!liveTrail.isEmpty()) {
            mapView.setTraveled(liveTrail);
            mapView.setCurrent(liveTrail.get(liveTrail.size() - 1));
        }
    }

    private static double haversineKm(double[] a, double[] b) {
        double r = 6371.0;
        double dLat = Math.toRadians(b[0] - a[0]);
        double dLng = Math.toRadians(b[1] - a[1]);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a[0])) * Math.cos(Math.toRadians(b[0]))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }

    private void renderTrips() {
        if (listTrips.getChildCount() == 0) {
            showTripsState("Loading recent trips…", "", false);
        }
        FleetApi.get("/shift", new FleetApi.Callback() {
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
