package com.shuttle.mobile.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shuttle.mobile.R;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Terminal-scoped vehicle list using the existing shuttle endpoints. */
public final class Fleet {
    private final Context context;
    private final List<JSONObject> units = new ArrayList<>();
    private View root;
    private LinearLayout list;
    private View empty;
    private TextView emptyTitle, emptyDescription;
    private final TextView[] chips = new TextView[3];
    private final List<JSONObject> workers = new ArrayList<>();
    private boolean assignmentsLoaded;
    private String query = "";
    private int filter;
    private boolean loading;
    private String failure;

    private Fleet(Context context) { this.context = context; }

    public static View create(Context context, ViewGroup parent) {
        Fleet page = new Fleet(context);
        page.root = LayoutInflater.from(context).inflate(R.layout.activity_operator_fleet, parent, false);
        page.root.setTag(page);
        page.list = page.root.findViewById(R.id.fleetList);
        page.empty = page.root.findViewById(R.id.fleetEmptyState);
        page.emptyTitle = page.empty.findViewById(R.id.tvEmptyTitle);
        page.emptyDescription = page.empty.findViewById(R.id.tvEmptyDescription);
        ((ImageView) page.empty.findViewById(R.id.ivEmptyState)).setImageResource(R.drawable.ic_vehicle);
        page.chips[0] = page.root.findViewById(R.id.chipAll);
        page.chips[1] = page.root.findViewById(R.id.chipOn);
        page.chips[2] = page.root.findViewById(R.id.chipOff);
        for (int i = 0; i < page.chips.length; i++) {
            final int index = i;
            page.chips[i].setOnClickListener(v -> { page.filter = index; page.render(); });
        }
        ((EditText) page.root.findViewById(R.id.etFleetSearch)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) { page.query = text.toString(); page.render(); }
        });
        page.root.findViewById(R.id.btnFleetAddUnit).setOnClickListener(v -> page.addUnit());

        page.load();
        return page.root;
    }

    public static void refresh(View root) {
        if (root != null && root.getTag() instanceof Fleet) ((Fleet) root.getTag()).load();
    }

    private boolean active() {
        return Session.isSignedIn() && (!(context instanceof Activity)
                || (!((Activity) context).isFinishing() && !((Activity) context).isDestroyed()));
    }

    private void load() {
        if (loading) return;
        loading = true;
        failure = null;
        render();
        String token = Session.getToken();
        FleetApi.get("/shuttles", new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject result) {
                if (!active() || !java.util.Objects.equals(token, Session.getToken())) return;
                loading = false;
                JSONArray rows = result.optJSONArray("shuttles");
                if (rows == null) { failure = "Unexpected fleet reply. Tap to retry."; render(); return; }
                units.clear();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null) units.add(row);
                }
                render();
                loadAssignments();
            }
            @Override public void onError(int status, String message) {
                if (!active() || !java.util.Objects.equals(token, Session.getToken())) return;
                loading = false;
                failure = message + " · Tap to retry";
                render();
            }
        });
    }

    private void render() {
        list.removeAllViews();
        String q = query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (JSONObject unit : units) {
            String status = unit.optString("status");
            if (filter == 1 && !"IN_SERVICE".equals(status)) continue;
            if (filter == 2 && !"AVAILABLE".equals(status)) continue;
            String searchable = unit.optString("shuttleId") + " " + unit.optString("plateNumber")
                    + " " + assignedName(unit);
            if (!searchable.toLowerCase(Locale.ROOT).contains(q)) continue;
            View card = LayoutInflater.from(context).inflate(R.layout.item_fleet_unit, list, false);
            ((TextView) card.findViewById(R.id.tvUnitName)).setText(shuttleTitle(unit));
            ((TextView) card.findViewById(R.id.tvUnitPlate)).setText(
                    unit.optString("plateNumber"));
            ((TextView) card.findViewById(R.id.tvUnitDriver)).setText(
                    assignedName(unit));
            TextView badge = card.findViewById(R.id.tvUnitStatus);
            badge.setText(statusLabel(status));
            badge.setTextColor(ContextCompat.getColor(context,
                    "IN_SERVICE".equals(status) ? R.color.success : R.color.primary));
            card.setOnClickListener(v -> details(unit));
            list.addView(card);
            shown++;
        }
        empty.setVisibility(shown == 0 || failure != null ? View.VISIBLE : View.GONE);
        if (failure != null) {
            emptyTitle.setText("Could not load fleet units");
            emptyDescription.setText(failure);
            empty.setOnClickListener(v -> load());
        } else {
            empty.setOnClickListener(null);
            empty.setClickable(false);
            emptyTitle.setText(loading ? "Loading fleet…"
                    : units.isEmpty() ? "No shuttle units yet" : "No matching shuttle units");
            emptyDescription.setText(loading ? "" : units.isEmpty()
                    ? "Add a shuttle unit to get started." : "Try a different search or filter.");
        }
        emptyDescription.setVisibility(emptyDescription.length() == 0 ? View.GONE : View.VISIBLE);
        chips[0].setText("All (" + units.size() + ")");
        chips[1].setText("In Service (" + statusCount("IN_SERVICE") + ")");
        chips[2].setText("Available (" + statusCount("AVAILABLE") + ")");
        for (int i = 0; i < chips.length; i++) {
            chips[i].setBackgroundResource(filter == i ? R.drawable.tab_selected : R.drawable.tab_unselected);
            chips[i].setTextColor(ContextCompat.getColor(context,
                    filter == i ? R.color.white : R.color.text_secondary));
        }
    }

    private int statusCount(String status) {
        int total = 0;
        for (JSONObject unit : units) if (status.equals(unit.optString("status"))) total++;
        return total;
    }

    private void loadAssignments() {
        String token = Session.getToken();
        assignmentsLoaded = false;
        workers.clear();
        FleetApi.get("/workers", new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject reply) {
                if (!active() || !java.util.Objects.equals(token, Session.getToken())) return;
                JSONArray rows = reply.optJSONArray("workers");
                if (rows == null) return;
                workers.clear();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null) workers.add(row);
                }
                assignmentsLoaded = true;
                render();
            }
            @Override public void onError(int status, String message) {
                // A failed assignment lookup must not erase the fleet or invent an unassigned state.
                if (active() && java.util.Objects.equals(token, Session.getToken())) render();
            }
        });
    }

    private List<JSONObject> assignedWorkers(JSONObject unit) {
        List<JSONObject> assigned = new ArrayList<>();
        String id = unit.optString("shuttleId");
        for (JSONObject worker : workers) {
            if (!"DRIVER".equals(FleetApi.text(worker, "role"))) continue;
            // The CSV list has a shared column layout. A driver's value1 is always its assigned unit.
            String assignedId = value(worker, "shuttleId", FleetApi.text(worker, "value1"));
            if (id.equals(assignedId)) assigned.add(worker);
        }
        return assigned;
    }

    private String assignedName(JSONObject unit) {
        String activeDriver = FleetApi.text(unit, "driverName");
        if (!activeDriver.isEmpty()) return activeDriver;
        List<String> names = new ArrayList<>();
        for (JSONObject worker : assignedWorkers(unit)) {
            String name = FleetApi.text(worker, "fullName");
            if (!name.isEmpty()) names.add(name);
        }
        if (!names.isEmpty()) return android.text.TextUtils.join(", ", names);
        return assignmentsLoaded ? "No driver assigned" : "Assignment unavailable";
    }

    private static String shuttleTitle(JSONObject unit) {
        String id = unit.optString("shuttleId");
        return id.startsWith("#") ? "Shuttle " + id : "Shuttle #" + id;
    }

    private void shuttleIcon(LinearLayout parent) {
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_vehicle);
        icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary)));
        icon.setBackgroundResource(R.drawable.icon_square_bg);
        icon.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(60), dp(60));
        size.gravity = Gravity.CENTER_HORIZONTAL;
        size.bottomMargin = dp(12);
        parent.addView(icon, size);
    }

    private void details(JSONObject unit) {
        LinearLayout content = WorkerDialogs.detailContent(context);
        LinearLayout identity = WorkerDialogs.detailSection(context, content, "");
        identity.setPadding(dp(16), dp(20), dp(16), dp(16));
        shuttleIcon(identity);
        TextView name = WorkerDialogs.detailText(context, shuttleTitle(unit), 20, true);
        name.setGravity(Gravity.CENTER);
        identity.addView(name);
        TextView plate = WorkerDialogs.detailText(context, unit.optString("plateNumber"), 13, false);
        plate.setGravity(Gravity.CENTER);
        identity.addView(plate);
        String statusCode = unit.optString("status");
        boolean inService = "IN_SERVICE".equals(statusCode);
        TextView badge = WorkerDialogs.detailText(context, statusLabel(statusCode), 12, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackgroundResource(R.drawable.chip_bg);
        badge.setTextColor(ContextCompat.getColor(context, inService ? R.color.success : R.color.primary));
        badge.setPadding(dp(12), dp(5), dp(12), dp(5));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
        badgeParams.gravity = Gravity.CENTER_HORIZONTAL;
        badgeParams.topMargin = dp(8);
        identity.addView(badge, badgeParams);

        LinearLayout information = WorkerDialogs.detailSection(context, content, "Vehicle Information");
        WorkerDialogs.informationRow(context, information, "Unit Number", unit.optString("shuttleId"));
        WorkerDialogs.informationRow(context, information, "Plate Number", unit.optString("plateNumber"));
        WorkerDialogs.informationRow(context, information, "Vehicle Model", "Not recorded");
        LinearLayout assignment = WorkerDialogs.detailSection(context, content, "Assigned Driver");
        List<JSONObject> assigned = assignedWorkers(unit);

        android.app.Dialog page = WorkerDialogs.showDetailPage(context, "Shuttle Details", content);
        if (inService) {
            TextView hint = WorkerDialogs.detailText(context, "Status follows the active shift.", 12, false);
            hint.setGravity(Gravity.CENTER);
            identity.addView(hint);
        } else {
            TextView change = WorkerDialogs.detailText(context, "Change status", 13, true);
            change.setMinHeight(dp(48));
            change.setGravity(Gravity.CENTER);
            change.setTextColor(ContextCompat.getColor(context, R.color.primary));
            change.setOnClickListener(v -> chooseStatus(unit, page));
            identity.addView(change, new LinearLayout.LayoutParams(-1, -2));
        }

        if (assigned.isEmpty()) {
            String driver = FleetApi.text(unit, "driverName");
            if (!driver.isEmpty()) {
                WorkerDialogs.informationRow(context, assignment, "Driver", driver);
                WorkerDialogs.informationRow(context, assignment, "Duty status", "On Duty");
            } else {
                if (assignmentsLoaded) {
                    ImageView icon = new ImageView(context);
                    icon.setImageResource(R.drawable.ic_person);
                    icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary)));
                    icon.setBackgroundResource(R.drawable.empty_icon_bg);
                    icon.setPadding(dp(12), dp(12), dp(12), dp(12));
                    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(48), dp(48));
                    size.gravity = Gravity.CENTER_HORIZONTAL;
                    size.topMargin = dp(8);
                    assignment.addView(icon, size);
                }
                TextView state = WorkerDialogs.detailText(context, assignedName(unit), 13, true);
                state.setGravity(Gravity.CENTER);
                state.setPadding(0, dp(10), 0, dp(12));
                assignment.addView(state);
            }
            return;
        }
        for (JSONObject worker : assigned) {
            WorkerDialogs.informationRow(context, assignment, "Driver", worker.optString("fullName"));
            String duty = FleetApi.text(worker, "shiftStatus");
            WorkerDialogs.informationRow(context, assignment, "Duty status",
                    "ON_SHIFT".equals(duty) ? "On Duty" : "ON_BREAK".equals(duty)
                            ? "On Break" : "OFF_SHIFT".equals(duty) ? "Off Duty" : "Unavailable");
            String driverId = FleetApi.text(worker, "employeeId");
            if (!driverId.isEmpty() && ("ON_SHIFT".equals(duty) || "ON_BREAK".equals(duty))) {
                TextView shiftTime = WorkerDialogs.informationRow(context, assignment, "Shift start", "Loading…");
                FleetApi.get("/shift/active?driverId=" + FleetApi.encode(driverId), new FleetApi.Callback() {
                    @Override public void onSuccess(JSONObject result) {
                        if (!active() || !page.isShowing()) return;
                        JSONObject shift = result.optJSONObject("shift");
                        long start = shift == null ? 0 : shift.optLong("startTime");
                        shiftTime.setText(start > 0 ? java.text.DateFormat.getDateTimeInstance()
                                .format(new java.util.Date(start)) : "Unavailable");
                    }

                    @Override public void onError(int status, String message) {
                        if (active() && page.isShowing()) shiftTime.setText("Unavailable");
                    }
                });
            }
        }
    }

    private void chooseStatus(JSONObject unit, android.app.Dialog page) {
        String[] codes = {"AVAILABLE", "MAINTENANCE", "OFFLINE"};
        String[] labels = {"Available", "Maintenance", "Offline"};
        int selected = java.util.Arrays.asList(codes).indexOf(unit.optString("status"));
        final int[] choice = {Math.max(0, selected)};
        AlertDialog dialog = new MaterialAlertDialogBuilder(context).setTitle("Update Unit Status")
                .setSingleChoiceItems(labels, choice[0], (d, which) -> choice[0] = which)
                .setNegativeButton("Cancel", null).setPositiveButton("Save changes", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            JSONObject body = new JSONObject();
            try { body.put("status", codes[choice[0]]); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            FleetApi.put("/shuttles/" + FleetApi.encode(unit.optString("shuttleId")) + "/status",
                    body, mutation(dialog, page, "Unit status updated"));
        }));
        dialog.show();
    }

    private void addUnit() {
        LinearLayout form = WorkerDialogs.detailContent(context);
        shuttleIcon(form);
        EditText id = field(form, "UNIT NUMBER", InputType.TYPE_CLASS_TEXT);
        EditText plate = field(form, "PLATE NUMBER", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText capacity = field(form, "SEATING CAPACITY", InputType.TYPE_CLASS_NUMBER);
        TextView error = WorkerDialogs.detailText(context, "", 12, false);
        error.setTextColor(ContextCompat.getColor(context, R.color.error));
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(error);
        com.google.android.material.button.MaterialButton save = new com.google.android.material.button.MaterialButton(context);
        save.setText("Add Unit");
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(48));
        buttonParams.topMargin = dp(24);
        form.addView(save, buttonParams);
        android.app.Dialog page = WorkerDialogs.showDetailPage(context, "Add Fleet Unit", form);
        save.setOnClickListener(button -> {
            String unitId = id.getText().toString().trim();
            String plateNumber = plate.getText().toString().trim();
            int seats;
            if (unitId.isEmpty() || unitId.contains(" ")) { id.setError("Enter a unit number without spaces"); return; }
            if (plateNumber.isEmpty()) { plate.setError("Enter a plate number"); return; }
            try { seats = Integer.parseInt(capacity.getText().toString().trim()); }
            catch (NumberFormatException invalid) { capacity.setError("Enter a positive number"); return; }
            if (seats < 1) { capacity.setError("Enter a positive number"); return; }
            JSONObject body = new JSONObject();
            try { body.put("shuttleId", unitId).put("plateNumber", plateNumber).put("capacity", seats); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            String token = Session.getToken();
            save.setEnabled(false);
            id.setEnabled(false); plate.setEnabled(false); capacity.setEnabled(false);
            page.setCancelable(false);
            page.findViewById(R.id.btnDetailBack).setEnabled(false);
            error.setText("");
            FleetApi.post("/shuttles", body, new FleetApi.Callback() {
                @Override public void onSuccess(JSONObject result) {
                    if (!active() || !java.util.Objects.equals(token, Session.getToken())) return;
                    page.dismiss();
                    com.google.android.material.snackbar.Snackbar.make(root, "Fleet unit added successfully.", 4000).show();
                    load();
                }
                @Override public void onError(int status, String message) {
                    if (!active() || !java.util.Objects.equals(token, Session.getToken())) return;
                    save.setEnabled(true);
                    id.setEnabled(true); plate.setEnabled(true); capacity.setEnabled(true);
                    page.setCancelable(true);
                    page.findViewById(R.id.btnDetailBack).setEnabled(true);
                    error.setText(message);
                }
            });
        });
    }

    private FleetApi.Callback mutation(AlertDialog dialog, android.app.Dialog page, String success) {
        String token = Session.getToken();
        return new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject result) {
                if (!active() || !java.util.Objects.equals(token, Session.getToken())) return;
                dialog.dismiss();
                page.dismiss();
                Toast.makeText(context, success, Toast.LENGTH_SHORT).show();
                load();
            }
            @Override public void onError(int status, String message) {
                if (!active() || !java.util.Objects.equals(token, Session.getToken())) return;
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        };
    }

    private EditText field(LinearLayout form, String label, int type) {
        TextView caption = new TextView(context);
        caption.setText(label);
        caption.setTextSize(10);
        caption.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        caption.setPadding(0, dp(12), 0, dp(6));
        form.addView(caption);
        EditText field = new EditText(context);
        field.setInputType(type);
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setBackgroundResource(R.drawable.input_bg);
        field.setPadding(dp(12), 0, dp(12), 0);
        form.addView(field, new LinearLayout.LayoutParams(-1, dp(48)));
        return field;
    }

    private static String value(JSONObject row, String key, String fallback) {
        String text = FleetApi.text(row, key);
        return text.isEmpty() ? fallback : text;
    }
    private static String statusLabel(String code) {
        if (code.isEmpty()) return "Unknown";
        return code.substring(0, 1) + code.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
