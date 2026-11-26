package com.shuttle.mobile.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shuttle.mobile.R;
import com.shuttle.mobile.client.ApiClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;

public class OperatorActivity extends AppCompatActivity {

    private static class WorkerItem {
        String name;
        String empId;
        String status;
        boolean operator;
        String value1;
        String value2;
        String phone;

        WorkerItem(String name, String empId, String status, boolean operator,
                   String value1, String value2, String phone) {
            this.name = name;
            this.empId = empId;
            this.status = status;
            this.operator = operator;
            this.value1 = value1;
            this.value2 = value2;
            this.phone = phone;
        }

        String role() {
            return operator ? "Terminal Operator" : "Driver";
        }

        String label1() {
            return operator ? "Station" : "Vehicle";
        }

        String label2() {
            return operator ? "Shift Time" : "Terminal";
        }

        boolean isOnline() {
            return status.equals("ON SHIFT") || status.equals("ACTIVE");
        }
    }

    private final ArrayList<WorkerItem> workers = new ArrayList<>();
    private String currentFilter = "ALL";
    private FleetConnectionUi.Membership fleetMembership;
    private boolean workersLoaded;
    private String workerError;
    private TextView btnFleetConnection;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable staffRefresh = new Runnable() {
        @Override public void run() {
            if (fleetMembership != null && fleetMembership.joined) refreshStaff();
            notificationBadge.refresh();
            refreshHandler.postDelayed(this, 10000);
        }
    };

    private LinearLayout listContainer;
    private EditText etSearch;
    private TextView chipAll;
    private TextView chipDrivers;
    private TextView chipOperators;
    private ImageView tvHeaderAvatar;
    private NotificationBadge notificationBadge;

    private static final int TAB_WORKERS = 0;
    private static final int TAB_SCHEDULE = 1;
    private static final int TAB_DASHBOARD = 2;
    private static final int TAB_FLEET = 3;

    private View pageWorkers;
    private View pageSchedule;
    private View pageDashboard;
    private View pageFleet;
    private ImageView ivNavWorkers;
    private ImageView ivNavSchedule;
    private TextView tvNavWorkers;
    private TextView tvNavSchedule;
    private View navDashboard;
    private View navFleet;
    private int currentTab = TAB_DASHBOARD;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.isSignedIn()) {
            signOut();
            return;
        }
        setContentView(R.layout.activity_operator);

        listContainer = findViewById(R.id.listContainer);
        btnFleetConnection = findViewById(R.id.btnFleetConnection);
        btnFleetConnection.setOnClickListener(v -> showFleetConnection());
        etSearch = findViewById(R.id.etSearch);
        chipAll = findViewById(R.id.chipAll);
        chipDrivers = findViewById(R.id.chipDrivers);
        chipOperators = findViewById(R.id.chipOperators);
        tvHeaderAvatar = findViewById(R.id.tvHeaderAvatar);
        notificationBadge = new NotificationBadge(findViewById(R.id.viewDot));
        pageWorkers = findViewById(R.id.pageWorkers);
        pageSchedule = findViewById(R.id.pageSchedule);
        ivNavWorkers = findViewById(R.id.ivNavWorkers);
        ivNavSchedule = findViewById(R.id.ivNavSchedule);
        tvNavWorkers = findViewById(R.id.tvNavWorkers);
        tvNavSchedule = findViewById(R.id.tvNavSchedule);
        navDashboard = findViewById(R.id.navDashboard);
        navFleet = findViewById(R.id.navFleet);


        chipAll.setOnClickListener(v -> selectFilter("ALL"));
        chipDrivers.setOnClickListener(v -> selectFilter("ON"));
        chipOperators.setOnClickListener(v -> selectFilter("OFF"));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderList();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        findViewById(R.id.flBell).setOnClickListener(v -> showNotifications());
        tvHeaderAvatar.setOnClickListener(v -> showAccountMenu());

        navDashboard.setOnClickListener(v -> showTab(TAB_DASHBOARD));
        navFleet.setOnClickListener(v -> showTab(TAB_FLEET));
        findViewById(R.id.navWorkers).setOnClickListener(v -> showTab(TAB_WORKERS));
        findViewById(R.id.navSchedule).setOnClickListener(v -> showTab(TAB_SCHEDULE));

        SchedulePage.bind(this, pageSchedule);

        updateSummary();
        selectFilter("ALL");
        showTab(TAB_DASHBOARD);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Session.isSignedIn() || btnFleetConnection == null) return;
        tvHeaderAvatar.setContentDescription("Account menu for " + Session.getName());
        if (pageDashboard != null && currentTab == TAB_DASHBOARD) Dashboard.refresh(pageDashboard);
        refreshFleetConnection();
        notificationBadge.refresh();
        refreshHandler.removeCallbacks(staffRefresh);
        refreshHandler.postDelayed(staffRefresh, 10000);
    }

    @Override
    protected void onPause() {
        if (notificationBadge != null) notificationBadge.pause();
        refreshHandler.removeCallbacks(staffRefresh);
        super.onPause();
    }

    private void showFleetConnection() {
        if (fleetMembership == null) {
            refreshFleetConnection();
            Toast.makeText(this, "Checking your terminal connection…", Toast.LENGTH_SHORT).show();
            return;
        }
        FleetConnectionUi.showOperator(this, fleetMembership, this::refreshFleetConnection);
    }

    private void refreshFleetConnection() {
        FleetApi.get("/fleet", new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject json) {
                if (isFinishing() || isDestroyed()) return;
                fleetMembership = new FleetConnectionUi.Membership(json);
                if (fleetMembership.joined) {
                    refreshStaff();
                } else {
                    workers.clear();
                    workersLoaded = true;
                    workerError = null;
                    updateSummary();
                    renderList();
                }
            }
            @Override public void onError(int status, String message) {
                if (isFinishing() || isDestroyed()) return;
                handleServerError(status, message);
            }
        });
    }

    private void refreshStaff() {
        FleetApi.get("/workers", new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject json) {
                if (isFinishing() || isDestroyed()) return;
                JSONArray rows = json.optJSONArray("workers");
                if (rows == null) {
                    handleServerError(0, "The server sent an unexpected staff list.");
                    return;
                }
                workers.clear();
                workersLoaded = true;
                workerError = null;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null || java.util.Objects.equals(Session.getEmployeeId(),
                            FleetApi.text(row, "employeeId"))) continue;
                    String status = FleetApi.text(row, "shiftStatus").replace('_', ' ');
                    if (status.equals("OFF SHIFT")) status = "OFF DUTY";
                    boolean operator = "OPERATOR".equals(FleetApi.text(row, "role"));
                    String value1 = FleetApi.text(row, "value1");
                    String value2 = FleetApi.text(row, "value2");
                    if (fleetMembership != null && fleetMembership.joined) {
                        if (operator) value1 = fleetMembership.terminalName;
                        else value2 = fleetMembership.terminalName;
                    }
                    workers.add(new WorkerItem(FleetApi.text(row, "fullName"),
                            FleetApi.text(row, "employeeId"), status, operator,
                            value1.isEmpty() ? "Unassigned" : value1,
                            value2.isEmpty() ? "Unassigned" : value2,
                            FleetApi.text(row, "phoneDisplay")));
                }
                updateSummary();
                renderList();
            }
            @Override public void onError(int status, String message) {
                if (isFinishing() || isDestroyed()) return;
                handleServerError(status, message);
            }
        });
    }

    private void handleServerError(int status, String message) {
        if (status == 401) {
            Toast.makeText(this, "Your session ended. Please sign in again.", Toast.LENGTH_LONG).show();
            signOut();
        } else {
            workerError = message;
            renderList();
        }
    }

    @Override
    public void onBackPressed() {
        if (currentTab != TAB_DASHBOARD) {
            showTab(TAB_DASHBOARD);
        } else {
            super.onBackPressed();
        }
    }

    private void showTab(int tab) {
        currentTab = tab;
        boolean dashboardCreated = false;

        if (tab == TAB_DASHBOARD && pageDashboard == null) {
            ViewGroup parent = (ViewGroup) pageSchedule.getParent();
            pageDashboard = addPage(Dashboard.create(this, parent));
            pageDashboard.findViewById(R.id.btnDashboardViewFleet).setOnClickListener(v -> showTab(TAB_FLEET));
            dashboardCreated = true;
        }
        boolean fleetCreated = false;
        if (tab == TAB_FLEET && pageFleet == null) {
            ViewGroup parent = (ViewGroup) pageSchedule.getParent();
            pageFleet = addPage(Fleet.create(this, parent));
            fleetCreated = true;
        }

        pageWorkers.setVisibility(tab == TAB_WORKERS ? View.VISIBLE : View.GONE);
        pageSchedule.setVisibility(tab == TAB_SCHEDULE ? View.VISIBLE : View.GONE);
        if (pageDashboard != null) {
            pageDashboard.setVisibility(tab == TAB_DASHBOARD ? View.VISIBLE : View.GONE);
        }
        if (pageFleet != null) {
            pageFleet.setVisibility(tab == TAB_FLEET ? View.VISIBLE : View.GONE);
        }



        styleNav(ivNavWorkers, tvNavWorkers, tab == TAB_WORKERS);
        styleNav(ivNavSchedule, tvNavSchedule, tab == TAB_SCHEDULE);
        styleNavGroup(navDashboard, tab == TAB_DASHBOARD);
        styleNavGroup(navFleet, tab == TAB_FLEET);
        if (tab == TAB_DASHBOARD && !dashboardCreated) Dashboard.refresh(pageDashboard);
        if (tab == TAB_FLEET && !fleetCreated) Fleet.refresh(pageFleet);
        if (tab == TAB_SCHEDULE) SchedulePage.refresh(pageSchedule);
    }

    private View addPage(View page) {
        ViewGroup parent = (ViewGroup) pageSchedule.getParent();
        page.setVisibility(View.GONE);
        parent.addView(page, parent.indexOfChild(pageSchedule) + 1,
                copyParams(pageSchedule.getLayoutParams()));
        return page;
    }

    private ViewGroup.LayoutParams copyParams(ViewGroup.LayoutParams p) {
        if (p instanceof LinearLayout.LayoutParams) {
            return new LinearLayout.LayoutParams((LinearLayout.LayoutParams) p);
        }
        if (p instanceof FrameLayout.LayoutParams) {
            return new FrameLayout.LayoutParams((FrameLayout.LayoutParams) p);
        }
        if (p instanceof RelativeLayout.LayoutParams) {
            return new RelativeLayout.LayoutParams((RelativeLayout.LayoutParams) p);
        }
        return p;
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

    private void styleNavGroup(View nav, boolean selected) {
        if (!(nav instanceof ViewGroup)) {
            return;
        }
        int color = ContextCompat.getColor(this, selected ? R.color.brand_600 : R.color.text_secondary);
        ViewGroup group = (ViewGroup) nav;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ImageView) {
                ImageView icon = (ImageView) child;
                icon.setImageTintList(ColorStateList.valueOf(color));
                if (selected) {
                    icon.setBackgroundResource(R.drawable.chip_bg);
                } else {
                    icon.setBackground(null);
                }
            } else if (child instanceof TextView) {
                ((TextView) child).setTextColor(color);
            } else if (child instanceof ViewGroup) {
                styleNavGroup(child, selected);
            }
        }
    }


    private void updateSummary() {
        int total = 0;
        int onDuty = 0;
        for (WorkerItem worker : workers) {
            total++;
            if (worker.isOnline()) onDuty++;
        }
        chipAll.setText("All (" + total + ")");
        chipDrivers.setText("On Duty (" + onDuty + ")");
        chipOperators.setText("Off Duty (" + (total - onDuty) + ")");
    }

    private void selectFilter(String filter) {
        currentFilter = filter;
        styleChip(chipAll, filter.equals("ALL"));
        styleChip(chipDrivers, filter.equals("ON"));
        styleChip(chipOperators, filter.equals("OFF"));
        renderList();
    }

    private void styleChip(TextView chip, boolean selected) {
        if (selected) {
            chip.setBackgroundResource(R.drawable.tab_selected);
            chip.setTextColor(ContextCompat.getColor(this, R.color.white));
        } else {
            chip.setBackgroundResource(R.drawable.tab_unselected);
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    private boolean matches(WorkerItem w) {
        if (currentFilter.equals("ON") && !w.isOnline()) return false;
        if (currentFilter.equals("OFF") && w.isOnline()) return false;
        String query = etSearch.getText().toString().trim().toLowerCase();
        if (query.isEmpty()) {
            return true;
        }
        return w.name.toLowerCase().contains(query) || w.empId.toLowerCase().contains(query);
    }

    private void renderList() {
        listContainer.removeAllViews();
        int shown = 0;

        ArrayList<WorkerItem> sorted = new ArrayList<>(workers);
        Collections.sort(sorted, (a, b) ->
                Integer.compare(WorkerDialogs.statusRank(a.status), WorkerDialogs.statusRank(b.status)));

        for (final WorkerItem w : sorted) {
            if (!matches(w)) {
                continue;
            }
            shown++;

            View card = LayoutInflater.from(this).inflate(R.layout.item_worker_card, listContainer, false);

            ((TextView) card.findViewById(R.id.tvName)).setText(w.name);
            ((TextView) card.findViewById(R.id.tvMeta)).setText(w.empId + " · " + w.role());
            ((TextView) card.findViewById(R.id.tvValue1)).setText(w.value1);
            styleBadge((TextView) card.findViewById(R.id.tvBadge), w.status);
            card.setOnClickListener(v -> showWorkerDetails(w));
            listContainer.addView(card);
        }

        if (shown == 0 || workerError != null) {
            View empty = LayoutInflater.from(this).inflate(R.layout.view_empty_state, listContainer, false);
            ((ImageView) empty.findViewById(R.id.ivEmptyState)).setImageResource(R.drawable.ic_nav_workers);
            TextView title = empty.findViewById(R.id.tvEmptyTitle);
            TextView description = empty.findViewById(R.id.tvEmptyDescription);
            if (workerError != null) {
                title.setText("Could not load workers");
                description.setText(workerError + " · Tap to retry");
                empty.setOnClickListener(v -> refreshFleetConnection());
            } else if (!workersLoaded) {
                title.setText("Loading workers…");
                description.setVisibility(View.GONE);
            } else if (workers.isEmpty()) {
                title.setText("No workers yet");
                description.setText(fleetMembership != null && fleetMembership.joined
                        ? "Invite a worker to get started." : "Create a terminal to invite workers.");
            } else {
                title.setText("No matching workers");
                description.setText("Try a different search or duty filter.");
            }
            listContainer.addView(empty);
        }
    }

    private void showWorkerDetails(final WorkerItem worker) {
        LinearLayout content = WorkerDialogs.detailContent(this);
        LinearLayout identity = WorkerDialogs.detailSection(this, content, "");
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.ic_person);
        avatar.setBackgroundResource(R.drawable.avatar_header_bg);
        int size = Math.round(64 * getResources().getDisplayMetrics().density);
        int inset = Math.round(18 * getResources().getDisplayMetrics().density);
        avatar.setPadding(inset, inset, inset, inset);
        LinearLayout.LayoutParams avatarLayout = new LinearLayout.LayoutParams(size, size);
        avatarLayout.gravity = Gravity.CENTER_HORIZONTAL;
        identity.addView(avatar, avatarLayout);
        TextView name = WorkerDialogs.detailText(this, worker.name, 18, true);
        name.setGravity(Gravity.CENTER);
        identity.addView(name);
        TextView meta = WorkerDialogs.detailText(this, worker.role() + " · " + worker.empId, 11, false);
        meta.setGravity(Gravity.CENTER);
        identity.addView(meta);
        LinearLayout information = WorkerDialogs.detailSection(this, content, "Personal Information");
        WorkerDialogs.informationRow(this, information, "Worker ID", worker.empId);
        WorkerDialogs.informationRow(this, information, "Full Name", worker.name);
        WorkerDialogs.informationRow(this, information, "Mobile Number", worker.phone);
        LinearLayout assignment = WorkerDialogs.detailSection(this, content,
                worker.operator ? "Terminal assignment" : "Assigned vehicle");
        WorkerDialogs.informationRow(this, assignment, worker.label1(), worker.value1);
        WorkerDialogs.informationRow(this, assignment, worker.label2(), worker.value2);
        WorkerDialogs.informationRow(this, assignment, "Duty status", worker.status.replace('_', ' '));
        int buttonGap = Math.round(16 * getResources().getDisplayMetrics().density);
        com.google.android.material.button.MaterialButton call =
                new com.google.android.material.button.MaterialButton(this);
        call.setText("Call Worker");
        call.setAllCaps(false);
        call.setEnabled(!worker.phone.isEmpty());
        LinearLayout.LayoutParams callLayout = new LinearLayout.LayoutParams(-1, -2);
        callLayout.topMargin = buttonGap;
        content.addView(call, callLayout);
        call.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + worker.phone))));
        com.google.android.material.button.MaterialButton actions =
                new com.google.android.material.button.MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        actions.setText("More Actions");
        actions.setAllCaps(false);
        content.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        actions.setOnClickListener(v -> openSheet(worker));
        WorkerDialogs.showDetailPage(this, "Worker Details", content);
    }

    private void openSheet(final WorkerItem w) {
        final BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_worker, null);

        TextView tvSheetAvatar = sheet.findViewById(R.id.tvSheetAvatar);
        tvSheetAvatar.setText(initials(w.name));
        tvSheetAvatar.setBackgroundResource(w.isOnline() ? R.drawable.avatar_ring_bg
                : WorkerDialogs.isBreak(w.status) ? R.drawable.avatar_ring_break
                  : R.drawable.icon_circle_bg);

        ((TextView) sheet.findViewById(R.id.tvSheetName)).setText(w.name);
        ((TextView) sheet.findViewById(R.id.tvSheetMeta)).setText(w.empId + " • " + w.role());
        styleBadge((TextView) sheet.findViewById(R.id.tvSheetBadge), w.status);

        sheet.findViewById(R.id.btnSheetClose).setOnClickListener(v -> dialog.dismiss());
        sheet.findViewById(R.id.btnDismiss).setOnClickListener(v -> dialog.dismiss());

        sheet.findViewById(R.id.rowLog).setOnClickListener(v -> {
            dialog.dismiss();
            showShiftLog(w);
        });

        sheet.findViewById(R.id.rowReassign).setOnClickListener(v -> {
            dialog.dismiss();
            showReassign(w);
        });

        sheet.findViewById(R.id.rowDeactivate).setVisibility(w.operator ? View.GONE : View.VISIBLE);
        sheet.findViewById(R.id.rowDeactivate).setOnClickListener(v -> {
            dialog.dismiss();
            confirmRemove(w);
        });

        dialog.setContentView(sheet);

        ((View) sheet.getParent()).setBackgroundColor(Color.TRANSPARENT);

        dialog.show();
    }

    private void showShiftLog(WorkerItem worker) {
        LinearLayout content = WorkerDialogs.detailContent(this);
        TextView state = WorkerDialogs.detailText(this, "Loading recorded shifts…", 13, false);
        content.addView(state);
        android.app.Dialog page = WorkerDialogs.showDetailPage(this, "Shift Log · " + worker.name, content);
        FleetApi.get("/shift", new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject reply) {
                if (isFinishing() || isDestroyed() || !page.isShowing()) return;
                JSONArray shifts = reply.optJSONArray("shifts");
                if (shifts == null) { state.setText("The shift list could not be read."); return; }
                content.removeAllViews();
                int shown = 0;
                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("EEE, MMM d · h:mm a", java.util.Locale.getDefault());
                for (int i = 0; i < shifts.length(); i++) {
                    JSONObject shift = shifts.optJSONObject(i);
                    if (shift == null || !worker.empId.equals(shift.optString("driverId"))) continue;
                    LinearLayout card = WorkerDialogs.detailSection(OperatorActivity.this, content,
                            format.format(new java.util.Date(shift.optLong("startTime"))));
                    WorkerDialogs.informationRow(OperatorActivity.this, card, "Vehicle", shift.optString("shuttleId"));
                    WorkerDialogs.informationRow(OperatorActivity.this, card, "Duration",
                            FleetApi.formatDuration(shift.optLong("durationMs")));
                    WorkerDialogs.informationRow(OperatorActivity.this, card, "Distance",
                            shift.has("distanceKm") ? shift.optString("distanceKm") + " km" : "Not recorded");
                    shown++;
                }
                if (shown == 0) {
                    state.setText("No recorded shifts for this worker.");
                    content.addView(state);
                }
            }
            @Override public void onError(int status, String message) {
                if (!isFinishing() && page.isShowing()) state.setText(message);
            }
        });
    }

    private void showReassign(final WorkerItem w) {
        if (w.operator) {
            Toast.makeText(this, "This operator manages the connected terminal.", Toast.LENGTH_LONG).show();
            return;
        }
        FleetApi.get("/shuttles", new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject json) {
                if (isFinishing() || isDestroyed()) return;
                JSONArray shuttles = json.optJSONArray("shuttles");
                if (shuttles == null) {
                    handleServerError(0, "The server sent an unexpected vehicle list.");
                    return;
                }
                ArrayList<String> options = new ArrayList<>();
                options.add("Unassigned");
                for (int i = 0; i < shuttles.length(); i++) {
                    String id = FleetApi.text(shuttles.optJSONObject(i), "shuttleId");
                    if (!id.isEmpty()) options.add(id);
                }
                WorkerDialogs.showReassign(OperatorActivity.this, "Assign vehicle",
                        "Choose a vehicle from your terminal for " + w.name,
                        R.drawable.ic_vehicle, options.toArray(new String[0]), w.value1, picked -> {
                            boolean unassign = picked.equals("Unassigned");
                            JSONObject body = new JSONObject();
                            try { body.put("shuttleId", unassign ? "" : picked); }
                            catch (JSONException impossible) { throw new AssertionError(impossible); }
                            FleetApi.put("/workers/" + FleetApi.encode(w.empId) + "/assign", body,
                                    staffAction(unassign ? "Vehicle unassigned" : "Vehicle assignment updated"));
                        });
            }
            @Override public void onError(int status, String message) {
                if (isFinishing() || isDestroyed()) return;
                handleServerError(status, message);
            }
        });
    }

    private void confirmRemove(final WorkerItem w) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Remove from terminal?")
                .setMessage("Disconnect " + w.name + " from your terminal? Their account stays available so they can join another terminal.")
                .setPositiveButton("Remove", (dialog, which) -> FleetApi.delete(
                        "/workers/" + FleetApi.encode(w.empId), staffAction("Driver removed from terminal")))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private FleetApi.Callback staffAction(String success) {
        return new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject json) {
                if (isFinishing() || isDestroyed()) return;
                refreshStaff();
                Toast.makeText(OperatorActivity.this, success, Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(int status, String message) {
                if (isFinishing() || isDestroyed()) return;
                handleServerError(status, message);
                if (status != 401) Toast.makeText(OperatorActivity.this, message, Toast.LENGTH_LONG).show();
            }
        };
    }

    private void showNotifications() {
        startActivity(new Intent(this, AccountActivity.class).putExtra("notifications", true));
    }

    private void showAccountMenu() {
        PopupMenu menu = new PopupMenu(this, tvHeaderAvatar);
        menu.getMenu().add("My Profile");
        menu.getMenu().add("Sign out");

        menu.setOnMenuItemClickListener(item -> {
            if (item.getTitle().toString().equals("Sign out")) {
                AccountActivity.confirmSignOut(this, this::signOut);
            } else {
                startActivity(new Intent(this, AccountActivity.class));
            }
            return true;
        });

        menu.show();
    }

    private void signOut() {
        refreshHandler.removeCallbacks(staffRefresh);
        FleetApi.post("/auth/logout", null, null);
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
        } else if (status.equals("ON BREAK")) {
            bg = R.color.badge_break_bg;
            dot = R.color.break_dot;
            text = R.color.break_text;
        } else {
            bg = R.color.badge_on_bg;
            dot = R.color.brand_600;
            text = R.color.brand_600;
        }

        SpannableString s = new SpannableString("● " + (status.equals("OFF DUTY") ? "Off Duty" : status.equals("ON BREAK") ? "On Break" : "On Duty"));
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
            result += parts[i].substring(0, 1).toUpperCase();
        }
        return result;
    }

    private void comingSoon(String what) {
        Toast.makeText(this, what + " coming soon", Toast.LENGTH_SHORT).show();
    }
}
