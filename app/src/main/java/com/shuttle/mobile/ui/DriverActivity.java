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

    private View pageHome;
    private View pageTrips;
    private View pageProfile;
    private View layoutHeaderTitle;
    private View viewDot;
    private TextView tvPageLabel;
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

    private TextView tvProfileAvatar;
    private TextView tvProfileName;
    private TextView tvProfileRole;
    private TextView tvProfileEmail;
    private TextView tvProfilePhone;
    private TextView tvGateSub;
    private SwitchMaterial switchGate;

    private ImageView ivNavHome;
    private ImageView ivNavTrips;
    private ImageView ivNavProfile;
    private TextView tvNavHome;
    private TextView tvNavTrips;
    private TextView tvNavProfile;

    private final ArrayList<double[]> liveTrail = new ArrayList<>();
    private double liveDistanceKm = 0;
    private double liveSpeedKmh = 0;
    private long lastPointTime = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int currentTab = 0;

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
        pageProfile = findViewById(R.id.pageProfile);
        layoutHeaderTitle = findViewById(R.id.layoutHeaderTitle);
        viewDot = findViewById(R.id.viewDot);
        tvPageLabel = findViewById(R.id.tvPageLabel);
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

        tvProfileAvatar = findViewById(R.id.tvProfileAvatar);
        tvProfileName = findViewById(R.id.tvProfileName);
        tvProfileRole = findViewById(R.id.tvProfileRole);
        tvProfileEmail = findViewById(R.id.tvProfileEmail);
        tvProfilePhone = findViewById(R.id.tvProfilePhone);
        tvGateSub = findViewById(R.id.tvGateSub);
        switchGate = findViewById(R.id.switchGate);

        ivNavHome = findViewById(R.id.ivNavHome);
        ivNavTrips = findViewById(R.id.ivNavTrips);
        ivNavProfile = findViewById(R.id.ivNavProfile);
        tvNavHome = findViewById(R.id.tvNavHome);
        tvNavTrips = findViewById(R.id.tvNavTrips);
        tvNavProfile = findViewById(R.id.tvNavProfile);

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

        final SharedPreferences settings = getSharedPreferences("fleetflow_settings", Context.MODE_PRIVATE);
        switchGate.setChecked(settings.getBoolean("gate_alerts", true));
        updateGateText();
        switchGate.setOnCheckedChangeListener((button, checked) -> {
            settings.edit().putBoolean("gate_alerts", checked).apply();
            updateGateText();
        });

        findViewById(R.id.navHome).setOnClickListener(v -> setTab(0));
        findViewById(R.id.navTrips).setOnClickListener(v -> setTab(1));
        findViewById(R.id.navProfile).setOnClickListener(v -> setTab(2));
        findViewById(R.id.flBell).setOnClickListener(v -> showNotifications());
        btnShift.setOnClickListener(v -> onShiftButton());

        findViewById(R.id.btnEditProfile).setOnClickListener(v -> showEditName());
        findViewById(R.id.btnManagePass).setOnClickListener(v -> showManagePass());
        findViewById(R.id.cardPass).setOnClickListener(v -> showPassCode());
        findViewById(R.id.rowEmergency).setOnClickListener(v -> showEmergency());
        findViewById(R.id.rowLegal).setOnClickListener(v -> showLegal());
        findViewById(R.id.btnSignOut).setOnClickListener(v -> signOut());

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
        if (!Session.isSignedIn()) {
            return;
        }
        refreshShiftUi();
        syncFromServer();
        if (currentTab == 1) {
            renderTrips();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
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
        btnShift.setEnabled(!value);
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
                terminal = t.isEmpty() ? "Unassigned" : t;
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
        }
    }

    private void setTab(int tab) {
        currentTab = tab;
        pageHome.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        pageTrips.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        pageProfile.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);

        layoutHeaderTitle.setVisibility(tab == 2 ? View.GONE : View.VISIBLE);

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

    private void fillProfile() {
        String first = driverName.trim().split("\\s+")[0];
        tvHello.setText("Hi, " + first);

        tvProfileAvatar.setText(initials(driverName));
        tvProfileName.setText(driverName);
        tvProfileRole.setText("Driver • " + employeeId);

        tvProfileEmail.setText(email.isEmpty() ? "No email on file" : email);
        tvProfilePhone.setText(findPhone());
    }

    private String findPhone() {
        String p = Session.getPhone();
        if (p == null || p.isEmpty()) {
            return "No phone on file";
        }
        if (p.matches("\\+63[0-9]{10}")) {
            return "+63 " + p.substring(3, 6) + " " + p.substring(6, 9) + " " + p.substring(9);
        }
        return p;
    }

    private void updateGateText() {
        if (switchGate.isChecked()) {
            tvGateSub.setText("Active • live notifications");
            tvGateSub.setTextColor(ContextCompat.getColor(this, R.color.brand_600));
        } else {
            tvGateSub.setText("Paused • notifications off");
            tvGateSub.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    private void showEditName() {
        final EditText input = new EditText(this);
        input.setText(driverName);
        input.setSelection(input.getText().length());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setBackgroundResource(R.drawable.input_bg);
        int pad = dp(16);
        input.setPadding(pad, 0, pad, 0);

        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(52)));

        new MaterialAlertDialogBuilder(this)
                .setTitle("Edit name")
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    final String newName = input.getText().toString().trim();
                    if (newName.length() < 2) {
                        Toast.makeText(this, "Please enter your full name", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    JSONObject body = new JSONObject();
                    try {
                        body.put("fullName", newName);
                    } catch (JSONException e) {
                        return;
                    }

                    FleetApi.put("/workers/" + FleetApi.encode(Session.getEmployeeId()), body,
                            new FleetApi.Callback() {
                                @Override
                                public void onSuccess(JSONObject json) {
                                    if (isFinishing()) {
                                        return;
                                    }
                                    Session.setName(newName);
                                    driverName = newName;
                                    fillProfile();
                                    Toast.makeText(DriverActivity.this, "Profile updated",
                                            Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onError(int status, String message) {
                                    if (isFinishing()) {
                                        return;
                                    }
                                    handleError(status, message);
                                }
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showManagePass() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Payment & Transit Passes")
                .setMessage("Terminal Gate FastPass\nStatus: Active\n\n(No payment methods yet. Coming soon.)")
                .setPositiveButton("Close", null)
                .show();
    }

    private void showPassCode() {
        int brand = ContextCompat.getColor(this, R.color.brand_600);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(4));

        TextView pill = new TextView(this);
        pill.setText("● ACTIVE");
        pill.setTextColor(brand);
        pill.setTextSize(11);
        pill.setTypeface(null, Typeface.BOLD);
        pill.setLetterSpacing(0.08f);
        pill.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(ContextCompat.getColor(this, R.color.badge_on_bg));
        pillBg.setCornerRadius(dp(20));
        pill.setBackground(pillBg);
        box.addView(pill, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Terminal Gate FastPass");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(14);
        box.addView(title, titleParams);

        TextView who = new TextView(this);
        who.setText(driverName + " • " + employeeId);
        who.setTextSize(14);
        who.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        who.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams whoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        whoParams.topMargin = dp(2);
        box.addView(who, whoParams);

        FrameLayout card = new FrameLayout(this);
        card.setBackgroundResource(R.drawable.pass_tile_bg);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        QrCodeView qr = new QrCodeView(this);
        qr.setSeed(driverName + employeeId);
        card.addView(qr, new FrameLayout.LayoutParams(dp(200), dp(200)));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(20);
        box.addView(card, cardParams);

        TextView note = new TextView(this);
        note.setText("Demo code only. It can't be scanned at a gate yet.");
        note.setGravity(Gravity.CENTER);
        note.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        note.setTextSize(12);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(16);
        box.addView(note, noteParams);

        new MaterialAlertDialogBuilder(this)
                .setView(box)
                .setPositiveButton("Done", null)
                .show();
    }

    private void showEmergency() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Emergency Share Location")
                .setMessage("Send your live location to dispatch right now?")
                .setPositiveButton("Share", (dialog, which) ->
                        Toast.makeText(this, "Location shared with dispatch (demo)", Toast.LENGTH_LONG).show())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLegal() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("FleetFlow Terms of Carriage v4.2")
                .setMessage("Legal, transit rules and privacy information goes here.\n\n(Placeholder text.)")
                .setPositiveButton("Close", null)
                .show();
    }

    private void showNotifications() {
        viewDot.setVisibility(View.GONE);

        int brand = ContextCompat.getColor(this, R.color.brand_600);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(4));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Notifications");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView pill = new TextView(this);
        pill.setText("3 new");
        pill.setTextColor(brand);
        pill.setTextSize(11);
        pill.setTypeface(null, Typeface.BOLD);
        pill.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(ContextCompat.getColor(this, R.color.badge_on_bg));
        pillBg.setCornerRadius(dp(20));
        pill.setBackground(pillBg);
        header.addView(pill, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        box.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        addNotificationRow(box, R.drawable.ic_clock,
                "Shift change at 14:00", "Schedule update");
        addNotificationRow(box, R.drawable.ic_pin,
                "Terminal 3 Bay A: gate delay of 5 min", "Terminal alert");
        addNotificationRow(box, R.drawable.ic_vehicle,
                "Your vehicle inspection is due this week", "Maintenance reminder");

        new MaterialAlertDialogBuilder(this)
                .setView(box)
                .setPositiveButton("Done", null)
                .show();
    }

    private void addNotificationRow(LinearLayout parent, int iconRes, String text, String label) {
        boolean first = parent.getChildCount() == 1;

        if (!first) {
            View line = new View(this);
            line.setBackgroundColor(ContextCompat.getColor(this, R.color.border));
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            lineParams.topMargin = dp(12);
            parent.addView(line, lineParams);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setBackgroundResource(R.drawable.icon_circle_bg);
        icon.setImageResource(iconRes);
        icon.setColorFilter(ContextCompat.getColor(this, R.color.brand_600));
        icon.setPadding(dp(11), dp(11), dp(11), dp(11));
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView tvText = new TextView(this);
        tvText.setText(text);
        tvText.setTextSize(14);
        tvText.setTypeface(null, Typeface.BOLD);
        tvText.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        texts.addView(tvText);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(12);
        tvLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(2);
        texts.addView(tvLabel, labelParams);

        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.leftMargin = dp(14);
        row.addView(texts, textsParams);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = first ? dp(20) : dp(12);
        parent.addView(row, rowParams);
    }

    private void refreshShiftUi() {
        boolean active = activeStart > 0;

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

                String shiftId = FleetApi.text(json, "shiftId");
                if (!shiftId.isEmpty()) {
                    Intent intent = new Intent(DriverActivity.this, ShiftSummaryActivity.class);
                    intent.putExtra("shiftId", shiftId);
                    startActivity(intent);
                }
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
            tvStatStops.setText("0");
            tvStatSpeed.setText("0");
            mapView.setTraveled(new ArrayList<double[]>());
            mapView.setCurrent(null);
            return;
        }

        long elapsed = Math.max(0, System.currentTimeMillis() - activeStart);

        tvTimer.setText(FleetApi.formatDuration(elapsed));
        tvStatDistance.setText(String.format(Locale.US, "%.1f", liveDistanceKm));
        tvStatStops.setText("0");
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
            ((TextView) row.findViewById(R.id.tvTripDuration)).setText(
                    FleetApi.formatDuration(e.optLong("durationMs", end - start)));
            ((TextView) row.findViewById(R.id.tvTripDistance)).setText(
                    String.format(Locale.US, "%.1f km", e.optDouble("distanceKm", 0)));

            row.setOnClickListener(v -> {
                Intent intent = new Intent(this, ShiftSummaryActivity.class);
                intent.putExtra("shiftId", shiftId);
                startActivity(intent);
            });

            listTrips.addView(row);
            shown++;
        }

        tvTripsEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
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