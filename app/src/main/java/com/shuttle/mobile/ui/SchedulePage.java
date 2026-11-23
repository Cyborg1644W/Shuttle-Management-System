package com.shuttle.mobile.ui;

import android.app.Activity;
import android.content.Context;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.shuttle.common.Role;
import com.shuttle.mobile.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Shows dated assignments and existing weekly shifts on the same calendar. */
public final class SchedulePage {
    private static final String[] TIMES = {"06:00 – 14:00", "14:00 – 22:00", "22:00 – 06:00"};
    private final Context context;
    private final View root;
    private final LinearLayout layoutDays, layoutSchedule;
    private final TextView date, summary, emptyTitle, emptyDescription, loadError;
    private final View empty;
    private final Calendar selected = ScheduleDates.today();
    private final List<JSONObject> entries = new ArrayList<>();
    private final List<JSONObject> assignments = new ArrayList<>();
    private boolean loading, loaded, saving, editing, monthMode, loadingDated;
    private String failure, datedFailure, datedRange;
    private int datedRequest;

    private SchedulePage(Context context, View root) {
        this.context = context;
        this.root = root;
        layoutDays = root.findViewById(R.id.layoutDays);
        layoutSchedule = root.findViewById(R.id.layoutSchedule);
        date = root.findViewById(R.id.tvSchDate);
        summary = root.findViewById(R.id.tvSchSummary);
        empty = root.findViewById(R.id.scheduleEmptyState);
        emptyTitle = empty.findViewById(R.id.tvEmptyTitle);
        emptyDescription = empty.findViewById(R.id.tvEmptyDescription);
        loadError = root.findViewById(R.id.tvScheduleLoadError);
        loadError.setOnClickListener(v -> load());
        ((android.widget.ImageView) empty.findViewById(R.id.ivEmptyState)).setImageResource(R.drawable.ic_nav_schedule);
    }

    public static void bind(Context context, View root) {
        SchedulePage page = new SchedulePage(context, root);
        root.setTag(page);
        View add = root.findViewById(R.id.btnScheduleAdd);
        add.setVisibility(Session.getRole() == Role.OPERATOR ? View.VISIBLE : View.GONE);
        add.setOnClickListener(v -> page.add());
        root.findViewById(R.id.btnScheduleWeek).setOnClickListener(v -> page.mode(false));
        root.findViewById(R.id.btnScheduleMonth).setOnClickListener(v -> page.mode(true));
        root.findViewById(R.id.btnSchedulePrevious).setOnClickListener(v -> page.move(-7));
        root.findViewById(R.id.btnScheduleNext).setOnClickListener(v -> page.move(7));
        page.load();
    }

    public static void refresh(View root) {
        if (root != null && root.getTag() instanceof SchedulePage page) page.load();
    }

    private void add() {
        if (Session.getRole() != Role.OPERATOR) return;
        AddShiftForm.show(context, selected, saved -> {
            selected.setTimeInMillis(saved.getTimeInMillis());
            loadDated(true);
            Snackbar confirmation = Snackbar.make(root,
                    "Shift added successfully.\nThe shift has been scheduled.", Snackbar.LENGTH_LONG);
            confirmation.setTextMaxLines(2);
            confirmation.setBackgroundTint(ContextCompat.getColor(context, R.color.badge_on_bg));
            confirmation.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
            confirmation.setActionTextColor(ContextCompat.getColor(context, R.color.primary));
            View scheduleTab = root.getRootView().findViewById(R.id.navSchedule);
            if (scheduleTab != null) confirmation.setAnchorView(scheduleTab);
            confirmation.setAction("Dismiss", v -> confirmation.dismiss());
            confirmation.show();
        });
    }

    private boolean active() {
        return Session.isSignedIn() && (!(context instanceof Activity activity)
                || (!activity.isFinishing() && !activity.isDestroyed()));
    }

    private void load() {
        loadDated(true);
        if (loading || saving || editing) return;
        loading = true;
        failure = null;
        draw();
        String token = Session.getToken();
        FleetApi.get("/schedule", new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject result) {
                if (!active() || !Objects.equals(token, Session.getToken())) return;
                loading = false;
                JSONArray rows = result.optJSONArray("schedule");
                if (rows == null) failure = "Could not read weekly shifts.";
                else {
                    replace(entries, rows);
                    loaded = true;
                }
                draw();
            }

            @Override public void onError(int status, String message) {
                if (!active() || !Objects.equals(token, Session.getToken())) return;
                loading = false;
                failure = message;
                draw();
            }
        });
    }

    private void loadDated(boolean force) {
        Calendar first = (Calendar) selected.clone();
        if (monthMode) first.set(Calendar.DAY_OF_MONTH, 1);
        else first = ScheduleDates.offset(first, -ScheduleDates.weekday(first));
        Calendar last = ScheduleDates.offset(first,
                monthMode ? first.getActualMaximum(Calendar.DAY_OF_MONTH) - 1 : 6);
        String range = "?from=" + ScheduleDates.iso(first) + "&to=" + ScheduleDates.iso(last);
        if (range.equals(datedRange) && !force) { draw(); return; }
        if (!range.equals(datedRange)) assignments.clear();
        datedRange = range;
        int request = ++datedRequest;
        String token = Session.getToken();
        loadingDated = true;
        datedFailure = null;
        draw();
        FleetApi.get("/scheduled-shifts" + range, new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject result) {
                if (stale(request, token)) return;
                loadingDated = false;
                JSONArray rows = result.optJSONArray("assignments");
                if (rows == null) datedFailure = "Could not read dated shifts.";
                else replace(assignments, rows);
                draw();
            }

            @Override public void onError(int status, String message) {
                if (stale(request, token)) return;
                loadingDated = false;
                datedFailure = status == 404
                        ? "Update and restart your Java server to load dated shifts." : message;
                draw();
            }
        });
    }

    private boolean stale(int request, String token) {
        return request != datedRequest || !active() || !Objects.equals(token, Session.getToken());
    }

    private static void replace(List<JSONObject> target, JSONArray source) {
        target.clear();
        for (int i = 0; i < source.length(); i++) {
            JSONObject row = source.optJSONObject(i);
            if (row != null) target.add(row);
        }
    }

    private void mode(boolean month) {
        monthMode = month;
        loadDated(false);
    }

    private void move(int days) {
        if (monthMode) {
            int day = selected.get(Calendar.DAY_OF_MONTH);
            selected.set(Calendar.DAY_OF_MONTH, 1);
            selected.add(Calendar.MONTH, days < 0 ? -1 : 1);
            selected.set(Calendar.DAY_OF_MONTH, Math.min(day, selected.getActualMaximum(Calendar.DAY_OF_MONTH)));
        } else selected.add(Calendar.DATE, days);
        loadDated(false);
    }

    private void pick(Calendar day) {
        selected.setTimeInMillis(day.getTimeInMillis());
        loadDated(false);
    }

    private void draw() {
        GridLayout monthGrid = root.findViewById(R.id.calendarScheduleMonth);
        monthGrid.setVisibility(monthMode ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.layoutScheduleWeek).setVisibility(monthMode ? View.GONE : View.VISIBLE);
        for (int id : new int[]{R.id.btnScheduleWeek, R.id.btnScheduleMonth}) {
            TextView tab = root.findViewById(id);
            boolean on = (id == R.id.btnScheduleMonth) == monthMode;
            tab.setSelected(on);
            tab.setBackgroundResource(on ? R.drawable.tab_selected : R.drawable.tab_unselected);
            tab.setTextColor(ContextCompat.getColor(context, on ? R.color.white : R.color.text_secondary));
        }
        TextView period = root.findViewById(R.id.tvSchedulePeriod);
        if (monthMode) {
            period.setText(ScheduleDates.label(selected, "MMMM yyyy"));
            ScheduleDates.monthGrid(monthGrid, selected, selected, null, this::hasShift, this::pick);
        } else {
            Calendar monday = ScheduleDates.offset(selected, -ScheduleDates.weekday(selected));
            period.setText(ScheduleDates.label(monday, "MMM d") + " – "
                    + ScheduleDates.label(ScheduleDates.offset(monday, 6), "MMM d, yyyy"));
            layoutDays.removeAllViews();
            for (int i = 0; i < 7; i++) {
                Calendar day = ScheduleDates.offset(monday, i);
                LinearLayout chip = (LinearLayout) LayoutInflater.from(context)
                        .inflate(R.layout.item_schedule_day, layoutDays, false);
                TextView label = chip.findViewById(R.id.tvDayName);
                TextView number = chip.findViewById(R.id.tvDayNum);
                label.setText(ScheduleDates.DAYS[i]);
                number.setText(String.valueOf(day.get(Calendar.DAY_OF_MONTH)));
                boolean on = i == ScheduleDates.weekday(selected);
                boolean marked = hasShift(day);
                chip.setSelected(on);
                chip.setBackgroundResource(on ? R.drawable.day_chip_on : R.drawable.day_chip_off);
                label.setTextColor(ContextCompat.getColor(context, on ? R.color.white : R.color.text_secondary));
                number.setTextColor(ContextCompat.getColor(context, on ? R.color.white : R.color.text_primary));
                chip.addView(ScheduleDates.marker(context, on, marked), new LinearLayout.LayoutParams(dp(4), dp(4)));
                chip.setContentDescription(ScheduleDates.label(day, "EEEE, MMMM d, yyyy")
                        + (marked ? ", scheduled" : ""));
                chip.setOnClickListener(v -> pick(day));
                layoutDays.addView(chip);
            }
        }
        render();
    }

    private boolean hasShift(Calendar day) {
        String iso = ScheduleDates.iso(day);
        for (JSONObject row : assignments) if (iso.equals(row.optString("shiftDate"))) return true;
        for (JSONObject row : entries) if (hasWeeklyShift(row, day)) return true;
        return false;
    }

    private static boolean hasWeeklyShift(JSONObject row, Calendar day) {
        String days = row.optString("days");
        return days.length() == 7 && days.charAt(ScheduleDates.weekday(day)) == '1';
    }

    private void render() {
        date.setText(ScheduleDates.label(selected, "EEEE, MMMM d"));
        layoutSchedule.removeAllViews();
        String iso = ScheduleDates.iso(selected);
        for (JSONObject row : assignments) {
            if (!iso.equals(row.optString("shiftDate"))) continue;
            View card = scheduleCard(row,
                    "Shuttle " + row.optString("shuttleId") + " · " + row.optString("employeeId"),
                    row.optString("startTime") + " – " + row.optString("endTime"),
                    row.optString("status", "Scheduled"));
            String notes = FleetApi.text(row, "notesBase64");
            if (!notes.isEmpty()) {
                TextView text = card.findViewById(R.id.tvSchNotes);
                try {
                    text.setText(new String(Base64.decode(notes, Base64.DEFAULT), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException invalid) {
                    text.setText("Notes could not be read.");
                }
                card.findViewById(R.id.layoutShiftNotes).setVisibility(View.VISIBLE);
            }
            layoutSchedule.addView(card);
        }
        for (JSONObject row : entries) {
            int shift = row.optInt("shift", -1);
            if (shift < 0 || shift >= TIMES.length || !hasWeeklyShift(row, selected)) continue;
            View card = scheduleCard(row, row.optString("role") + " · " + row.optString("place"),
                    TIMES[shift], "Weekly shift");
            if (Session.getRole() == Role.OPERATOR) card.setOnClickListener(v -> editWeekly(row));
            layoutSchedule.addView(card);
        }

        int total = layoutSchedule.getChildCount();
        summary.setText(total == 1 ? "1 shift" : total + " shifts");
        summary.setVisibility(total > 0 ? View.VISIBLE : View.GONE);
        String message = datedFailure != null ? datedFailure : failure;
        loadError.setText(message == null ? "" : message + " · Tap to retry");
        loadError.setVisibility(message == null ? View.GONE : View.VISIBLE);
        empty.setVisibility(total == 0 && message == null ? View.VISIBLE : View.GONE);
        boolean waiting = loading || loadingDated;
        emptyTitle.setText(waiting ? "Loading schedule…" : "No shifts scheduled");
        emptyDescription.setText(waiting ? "" : "There are no assignments for this day.");
        emptyDescription.setVisibility(waiting ? View.GONE : View.VISIBLE);
    }

    private View scheduleCard(JSONObject row, String subtitle, String time, String status) {
        View card = LayoutInflater.from(context).inflate(R.layout.item_schedule_row, layoutSchedule, false);
        String name = FleetApi.text(row, "name");
        ((TextView) card.findViewById(R.id.tvSchName)).setText(name.isEmpty() ? row.optString("employeeId") : name);
        ((TextView) card.findViewById(R.id.tvSchSub)).setText(subtitle);
        ((TextView) card.findViewById(R.id.tvSchTime)).setText(time);
        ((TextView) card.findViewById(R.id.tvSchStatus)).setText(status);
        return card;
    }

    private void editWeekly(JSONObject existing) {
        if (!loaded || loading || saving || editing || Session.getRole() != Role.OPERATOR) return;
        editing = true;
        String token = Session.getToken();
        FleetApi.get("/workers", new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject result) {
                if (!active() || !java.util.Objects.equals(token, Session.getToken())) return;
                JSONArray rows = result.optJSONArray("workers");
                if (rows == null || rows.length() == 0) {
                    editing = false;
                    Toast.makeText(context, "Invite workers to your terminal first.", Toast.LENGTH_LONG).show();
                    return;
                }
                form(existing, rows);
            }
            @Override public void onError(int status, String message) {
                editing = false;
                if (active()) Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void form(JSONObject existing, JSONArray workers) {
        List<JSONObject> people = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int selectedWorker = -1;
        for (int i = 0; i < workers.length(); i++) {
            JSONObject worker = workers.optJSONObject(i);
            if (worker == null) continue;
            if (existing != null && existing.optString("employeeId").equals(worker.optString("employeeId"))) selectedWorker = people.size();
            people.add(worker);
            names.add(worker.optString("fullName") + " · " + worker.optString("employeeId"));
        }
        if (people.isEmpty()) { editing = false; return; }
        if (existing != null && selectedWorker < 0) {
            editing = false;
            Toast.makeText(context, "This worker is no longer available. Refresh the schedule.", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(WorkerDialogs.detailText(context, "Edit this existing weekly shift.", 12, false));
        label(box, "Worker");
        Spinner worker = spinner(box, names.toArray(new String[0]));
        worker.setSelection(Math.max(0, selectedWorker));
        label(box, "Shift");
        Spinner shift = spinner(box, new String[] {"Morning · " + TIMES[0], "Afternoon · " + TIMES[1], "Night · " + TIMES[2]});
        shift.setSelection(Math.max(0, Math.min(2, existing.optInt("shift"))));
        label(box, "Repeats every week on");
        LinearLayout daysRow = new LinearLayout(context);
        daysRow.setOrientation(LinearLayout.VERTICAL);
        CheckBox[] checks = new CheckBox[7];
        for (int i = 0; i < 7; i++) {
            CheckBox check = new CheckBox(context);
            check.setText(ScheduleDates.DAYS[i]);
            check.setContentDescription(ScheduleDates.DAYS[i]);
            check.setTextSize(14);
            check.setButtonTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.primary)));
            check.setPadding(0, 0, 0, 0);
            check.setChecked(existing.optString("days").length() == 7 && existing.optString("days").charAt(i) == '1');
            checks[i] = check;
            daysRow.addView(check, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        box.addView(daysRow);
        TextView error = new TextView(context);
        error.setTextColor(ContextCompat.getColor(context, R.color.error));
        box.addView(error);
        com.google.android.material.button.MaterialButton save = new com.google.android.material.button.MaterialButton(context);
        save.setText("Save Changes");
        box.addView(save, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView cancel = WorkerDialogs.detailText(context, "Cancel", 13, false);
        cancel.setGravity(android.view.Gravity.CENTER);
        box.addView(cancel, new LinearLayout.LayoutParams(-1, dp(48)));
        android.app.Dialog dialog = WorkerDialogs.showDetailPage(context,
                "Edit Weekly Shift", box);
        dialog.setOnDismissListener(d -> editing = false);
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(button -> {
            StringBuilder bits = new StringBuilder();
            for (CheckBox check : checks) bits.append(check.isChecked() ? '1' : '0');
            if (bits.indexOf("1") < 0) { error.setText("Select at least one day."); return; }
            List<Map<String, String>> updated = new ArrayList<>();
            for (JSONObject entry : entries) {
                if (entry != existing) updated.add(csvRow(entry.optString("employeeId"), entry.optInt("shift"), entry.optString("days")));
            }
            updated.add(csvRow(people.get(worker.getSelectedItemPosition()).optString("employeeId"),
                    shift.getSelectedItemPosition(), bits.toString()));
            saving = true;
            dialog.setCancelable(false);
            save.setEnabled(false);
            dialog.findViewById(R.id.btnDetailBack).setEnabled(false);
            cancel.setEnabled(false);
            render();
            String token = Session.getToken();
            FleetApi.putTable("/schedule", updated, new FleetApi.Callback() {
                @Override public void onSuccess(JSONObject result) {
                    if (!active() || !java.util.Objects.equals(token, Session.getToken())) return;
                    saving = false;
                    dialog.dismiss();
                    Toast.makeText(context, "Schedule updated", Toast.LENGTH_SHORT).show();
                    load();
                }
                @Override public void onError(int status, String message) {
                    if (!active() || !java.util.Objects.equals(token, Session.getToken())) return;
                    saving = false;
                    dialog.setCancelable(true);
                    save.setEnabled(true);
                    dialog.findViewById(R.id.btnDetailBack).setEnabled(true);
                    cancel.setEnabled(true);
                    error.setText(message);
                    render();
                }
            });
        });
    }

    private static Map<String, String> csvRow(String id, int shift, String days) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("employeeId", id);
        row.put("shift", String.valueOf(shift));
        row.put("days", days);
        return row;
    }
    private void label(LinearLayout box, String title) {
        TextView label = new TextView(context);
        label.setText(title.toUpperCase(Locale.ROOT));
        label.setTextSize(10);
        label.setPadding(0, dp(12), 0, dp(4));
        label.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        box.addView(label);
    }
    private Spinner spinner(LinearLayout box, String[] options) {
        Spinner spinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackgroundResource(R.drawable.input_bg);
        spinner.setPadding(dp(12), 0, dp(12), 0);
        box.addView(spinner, new LinearLayout.LayoutParams(-1, dp(48)));
        return spinner;
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
