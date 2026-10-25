package com.shuttle.mobile.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
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
import com.shuttle.mobile.gps.GpsTrackerService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;


public class DriverActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;

    // TODO: PLACEHOLDERS. These were missing from the file. If ROUTE and STEP_MS
    // already exist in another class, delete these two and import/qualify them instead.
    private static final long STEP_MS = 3000;
    private static final double[][] ROUTE = {
            {14.5995, 120.9842},
            {14.6010, 120.9870},
            {14.6040, 120.9880},
            {14.6055, 120.9850},
            {14.6035, 120.9820},
            {14.6010, 120.9825}
    };

    private String userId = "";
    private String driverName = "Driver";
    private String employeeId = "-";
    private String email = "-";

    private View pageHome;
    private View pageTrips;
    private View pageProfile;
    private TextView tvPageLabel;
    private TextView tvHeaderAvatar;
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
    private TextView tvTripsEmpty;
    private LinearLayout listTrips;

    private ImageView ivNavHome;
    private ImageView ivNavTrips;
    private ImageView ivNavProfile;
    private TextView tvNavHome;
    private TextView tvNavTrips;
    private TextView tvNavProfile;

    private final ArrayList<double[]> plannedRoute = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

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
        setContentView(R.layout.activity_driver);

        String id = getIntent().getStringExtra("user");
        userId = id == null ? "" : id;

        AccountStore accounts = new AccountStore(this);
        String savedName = accounts.getName(userId);
        String savedEmp = accounts.getEmployeeId(userId);
        String savedEmail = accounts.getEmail(userId);
        if (savedName != null) {
            driverName = savedName;
        }
        if (savedEmp != null) {
            employeeId = savedEmp;
        }
        if (savedEmail != null) {
            email = savedEmail;
        }

        pageHome = findViewById(R.id.pageHome);
        pageTrips = findViewById(R.id.pageTrips);
        pageProfile = findViewById(R.id.pageProfile);
        tvPageLabel = findViewById(R.id.tvPageLabel);
        tvHeaderAvatar = findViewById(R.id.tvHeaderAvatar);
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
        mapView = findViewById(R.id.mapView);
        tvTripsEmpty = findViewById(R.id.tvTripsEmpty);
        listTrips = findViewById(R.id.listTrips);

        ivNavHome = findViewById(R.id.ivNavHome);
        ivNavTrips = findViewById(R.id.ivNavTrips);
        ivNavProfile = findViewById(R.id.ivNavProfile);
        tvNavHome = findViewById(R.id.tvNavHome);
        tvNavTrips = findViewById(R.id.tvNavTrips);
        tvNavProfile = findViewById(R.id.tvNavProfile);

        // The route is a closed loop, so draw it back to the start
        for (double[] p : ROUTE) {
            plannedRoute.add(p);
        }
        plannedRoute.add(ROUTE[0]);
        mapView.setPlanned(plannedRoute);

        // Greeting
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        TextView tvGreeting = findViewById(R.id.tvGreeting);
        if (hour < 12) {
            tvGreeting.setText("Good morning");
        } else if (hour < 18) {
            tvGreeting.setText("Good afternoon");
        } else {
            tvGreeting.setText("Good evening");
        }
        tvHello.setText("Hi, " + driverName.split("\\s+")[0]);
        tvHeaderAvatar.setText(initials(driverName));

        // Profile page
        ((TextView) findViewById(R.id.tvProfileAvatar)).setText(initials(driverName));
        ((TextView) findViewById(R.id.tvProfileName)).setText(driverName);
        ((TextView) findViewById(R.id.tvProfileEmail)).setText("Email: " + (email.contains("@") ? email : "-"));
        ((TextView) findViewById(R.id.tvProfilePhone)).setText(
                "Phone: " + (userId.startsWith("+") ? userId : "-"));
        ((TextView) findViewById(R.id.tvProfileEmp)).setText("Employee ID: " + employeeId);

        // Clicks
        findViewById(R.id.navHome).setOnClickListener(v -> setTab(0));
        findViewById(R.id.navTrips).setOnClickListener(v -> setTab(1));
        findViewById(R.id.navProfile).setOnClickListener(v -> setTab(2));
        tvHeaderAvatar.setOnClickListener(v -> setTab(2));
        btnShift.setOnClickListener(v -> onShiftButton());
        findViewById(R.id.btnSignOut).setOnClickListener(v -> signOut());

        setTab(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShiftUi();
        if (currentTab == 1) {
            renderTrips();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    private int currentTab = 0;

    @Override
    public void onBackPressed() {
        if (currentTab != 0) {
            setTab(0);
        } else {
            super.onBackPressed();
        }
    }

    // ---------- Live location broadcast ----------

    private boolean receiverRegistered = false;

    private final BroadcastReceiver locationReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null ||
                            !"com.shuttle.mobile.LOCATION_UPDATE".equals(intent.getAction())) {
                        return;
                    }

                    double lat = intent.getDoubleExtra("latitude", 0.0);
                    double lng = intent.getDoubleExtra("longitude", 0.0);

                    if (mapView != null) {
                        mapView.addLiveLocationUpdate(lat, lng);
                    }
                }
            };

    @Override
    protected void onStart() {
        super.onStart();

        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter("com.shuttle.mobile.LOCATION_UPDATE");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(locationReceiver, filter);
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

    // ---------- GPS permission and tracking ----------

    private void requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED) {

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
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startGpsTracking();
        }
    }

    private void startGpsTracking() {
        Intent serviceIntent = new Intent(this, GpsTrackerService.class);

        serviceIntent.putExtra(GpsTrackerService.EXTRA_SHUTTLE_ID, getVehicle());
        serviceIntent.putExtra(GpsTrackerService.EXTRA_SIMULATE, false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    // ---------- Tabs ----------

    private void setTab(int tab) {
        currentTab = tab;
        pageHome.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        pageTrips.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        pageProfile.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);

        String[] labels = {"Home", "Trips", "Profile"};
        tvPageLabel.setText(labels[tab]);

        styleNav(ivNavHome, tvNavHome, tab == 0);
        styleNav(ivNavTrips, tvNavTrips, tab == 1);
        styleNav(ivNavProfile, tvNavProfile, tab == 2);

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

    // ---------- Shift ----------

    private void refreshShiftUi() {
        long start = ShiftStore.getActiveStart(this);
        boolean active = start > 0;

        styleBadge(tvStatusBadge, active ? "ON SHIFT" : "OFF DUTY");
        tvStatusHint.setText(active ? "Shift in progress" : "Ready to start your shift");

        if (active) {
            btnShift.setText("End Shift");
            btnShift.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.danger)));
        } else {
            btnShift.setText("Start Shift");
            btnShift.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.brand_600)));
        }

        tvVehicle.setText(getVehicle());
        tvTerminal.setText(getTerminal());

        handler.removeCallbacks(ticker);
        if (active) {
            handler.post(ticker);
        } else {
            updateLive();
        }
    }

    // Vehicle and terminal come from what the Operator assigned on Worker Management
    private String getVehicle() {
        StaffMember m = WorkerStore.findByName(this, driverName);
        return (m != null && !m.operator) ? m.value1 : "Unassigned";
    }

    private String getTerminal() {
        StaffMember m = WorkerStore.findByName(this, driverName);
        return (m != null && !m.operator) ? m.value2 : "Unassigned";
    }

    private void onShiftButton() {
        long start = ShiftStore.getActiveStart(this);

        if (start <= 0) {
            // Asks for location permission first, then starts the GPS service
            requestLocationPermission();
            ShiftStore.setActiveStart(this, System.currentTimeMillis());
            WorkerStore.setStatusByName(this, driverName, "ON SHIFT");
            refreshShiftUi();
            Toast.makeText(this, "Shift started", Toast.LENGTH_SHORT).show();
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("End shift?")
                    .setMessage("This ends your shift and saves it to your trips.")
                    .setPositiveButton("End Shift", (dialog, which) -> endShift())
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private void endShift() {
        long start = ShiftStore.getActiveStart(this);

        // Always stop GPS tracking when a shift ends
        stopService(new Intent(this, GpsTrackerService.class));

        if (start <= 0) {
            return;
        }
        long end = System.currentTimeMillis();
        int step = (int) ((end - start) / STEP_MS);

        // Keep at most about 80 points of the route
        ArrayList<double[]> points = new ArrayList<>();
        int stride = step / 80 + 1;
        for (int i = 0; i <= step; i += stride) {
            points.add(pointAt(i));
        }
        points.add(pointAt(step));

        ShiftStore.Entry entry = new ShiftStore.Entry(
                start, end,
                distanceKmUpTo(step),
                step / 6,
                getVehicle(),
                getTerminal(),
                ShiftStore.routeToString(points));
        ShiftStore.add(this, entry);

        ShiftStore.setActiveStart(this, 0);
        WorkerStore.setStatusByName(this, driverName, "OFF DUTY");
        refreshShiftUi();

        Intent intent = new Intent(this, ShiftSummaryActivity.class);
        intent.putExtra("startTime", start);
        startActivity(intent);
    }

    // ---------- Live numbers and map ----------

    private void updateLive() {
        long start = ShiftStore.getActiveStart(this);

        if (start <= 0) {
            tvTimer.setText("00:00:00");
            tvStatDistance.setText("0.0");
            tvStatStops.setText("0");
            tvStatSpeed.setText("0");
            mapView.setTraveled(new ArrayList<double[]>());
            mapView.setCurrent(ROUTE[0]);
            return;
        }

        long elapsed = Math.max(0, System.currentTimeMillis() - start);
        int step = (int) (elapsed / STEP_MS);
        double distance = distanceKmUpTo(step);
        double hours = elapsed / 3600000.0;
        double speed = hours > 0 ? distance / hours : 0;

        tvTimer.setText(ShiftStore.formatDuration(elapsed));
        tvStatDistance.setText(String.format(Locale.US, "%.1f", distance));
        tvStatStops.setText(String.valueOf(step / 6));
        tvStatSpeed.setText(String.format(Locale.US, "%.0f", speed));

        // Draw the last 40 points of the trip
        ArrayList<double[]> trail = new ArrayList<>();
        for (int i = Math.max(0, step - 40); i <= step; i++) {
            trail.add(pointAt(i));
        }
        mapView.setTraveled(trail);
        mapView.setCurrent(pointAt(step));
    }

    private static double[] pointAt(int i) {
        return ROUTE[i % ROUTE.length];
    }

    private static double distanceKmUpTo(int steps) {
        double total = 0;
        for (int k = 1; k <= steps; k++) {
            total += haversineKm(pointAt(k - 1), pointAt(k));
        }
        return total;
    }

    // Distance between two GPS points in km
    private static double haversineKm(double[] a, double[] b) {
        double r = 6371.0;
        double dLat = Math.toRadians(b[0] - a[0]);
        double dLng = Math.toRadians(b[1] - a[1]);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a[0])) * Math.cos(Math.toRadians(b[0]))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }

    // ---------- Trips ----------

    private void renderTrips() {
        listTrips.removeAllViews();
        ArrayList<ShiftStore.Entry> shifts = ShiftStore.load(this);

        tvTripsEmpty.setVisibility(shifts.isEmpty() ? View.VISIBLE : View.GONE);

        SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, MMM d", Locale.getDefault());
        SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());

        // Newest first
        for (int i = shifts.size() - 1; i >= 0; i--) {
            final ShiftStore.Entry e = shifts.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_trip_row, listTrips, false);

            ((TextView) row.findViewById(R.id.tvTripDate)).setText(dateFmt.format(new Date(e.start)));
            ((TextView) row.findViewById(R.id.tvTripTime)).setText(
                    timeFmt.format(new Date(e.start)) + " - " + timeFmt.format(new Date(e.end)));
            ((TextView) row.findViewById(R.id.tvTripDuration)).setText(ShiftStore.formatDuration(e.durationMs()));
            ((TextView) row.findViewById(R.id.tvTripDistance)).setText(
                    String.format(Locale.US, "%.1f km", e.distanceKm));

            row.setOnClickListener(v -> {
                Intent intent = new Intent(this, ShiftSummaryActivity.class);
                intent.putExtra("startTime", e.start);
                startActivity(intent);
            });

            listTrips.addView(row);
        }
    }

    // ---------- Sign out and helpers ----------

    private void signOut() {
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
}