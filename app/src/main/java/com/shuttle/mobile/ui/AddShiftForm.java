package com.shuttle.mobile.ui;

import android.app.Activity;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.text.format.DateFormat;
import android.util.Base64;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.shuttle.mobile.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

final class AddShiftForm {
    private record Option(String id, String name, String detail) { }

    private final Context context;
    private final Calendar seed;
    private final Consumer<Calendar> onSaved;
    private final View form;
    private final TextView error;
    private final EditText notes;
    private final View add;
    private final Dialog dialog;
    private final String token = Session.getToken();
    private Calendar date;
    private Option worker;
    private Option shuttle;
    private int start = -1;
    private int end = -1;
    private boolean saving;
    private String requestId = UUID.randomUUID().toString();

    static void show(Context context, Calendar seed, Consumer<Calendar> onSaved) {
        new AddShiftForm(context, seed, onSaved);
    }

    private AddShiftForm(Context context, Calendar seed, Consumer<Calendar> onSaved) {
        this.context = context;
        this.seed = (Calendar) seed.clone();
        this.onSaved = onSaved;
        form = LayoutInflater.from(context).inflate(R.layout.form_add_dated_shift, null);
        error = form.findViewById(R.id.tvAddShiftError);
        notes = form.findViewById(R.id.etShiftNotes);
        add = form.findViewById(R.id.btnSaveDatedShift);
        form.findViewById(R.id.btnShiftDate).setOnClickListener(v -> pickDate());
        form.findViewById(R.id.btnShiftWorker).setOnClickListener(v -> pickOption(true));
        form.findViewById(R.id.btnShiftShuttle).setOnClickListener(v -> pickOption(false));
        form.findViewById(R.id.btnShiftStart).setOnClickListener(v -> pickTime(true));
        form.findViewById(R.id.btnShiftEnd).setOnClickListener(v -> pickTime(false));
        FieldUi.onChange(notes, () -> changed());
        add.setOnClickListener(v -> save());
        dialog = WorkerDialogs.showDetailPage(context, "Add Shift", form);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void pickDate() {
        Picker picker = new Picker("Select Date", "Confirm Date");
        View calendar = LayoutInflater.from(context).inflate(R.layout.view_shift_date_picker, picker.body, false);
        picker.body.addView(calendar);
        Calendar today = ScheduleDates.today();
        Calendar first = date == null ? seed : date;
        if (first.before(today)) first = today;
        Calendar month = (Calendar) first.clone();
        month.set(Calendar.DAY_OF_MONTH, 1);
        Calendar[] pending = {date};
        TextView caption = calendar.findViewById(R.id.tvShiftPickerMonth);
        View prev = calendar.findViewById(R.id.btnShiftPreviousMonth);
        GridLayout grid = calendar.findViewById(R.id.gridShiftPickerDates);
        Runnable draw = new Runnable() {
            @Override public void run() {
                caption.setText(ScheduleDates.label(month, "MMMM yyyy"));
                Calendar before = (Calendar) month.clone();
                before.add(Calendar.MONTH, -1);
                before.set(Calendar.DAY_OF_MONTH, before.getActualMaximum(Calendar.DAY_OF_MONTH));
                prev.setEnabled(!before.before(today));
                prev.setAlpha(prev.isEnabled() ? 1f : 0.4f);
                picker.setConfirmEnabled(pending[0] != null);
                ScheduleDates.monthGrid(grid, month, pending[0], today, day -> false, day -> {
                    pending[0] = day;
                    run();
                });
            }
        };
        prev.setOnClickListener(v -> { month.add(Calendar.MONTH, -1); draw.run(); });
        calendar.findViewById(R.id.btnShiftNextMonth).setOnClickListener(v -> {
            month.add(Calendar.MONTH, 1);
            draw.run();
        });
        picker.confirm.setOnClickListener(v -> {
            date = pending[0];
            chosen(R.id.btnShiftDate, ScheduleDates.label(date, "EEE, MMM d, yyyy"));
            picker.sheet.dismiss();
        });
        draw.run();
        picker.show();
    }

    private void pickOption(boolean people) {
        Picker picker = new Picker(people ? "Select Worker" : "Select Shuttle Unit",
                people ? "Confirm Worker" : "Confirm Shuttle");
        Option[] pending = {people ? worker : shuttle};
        List<Option> options = new ArrayList<>();
        picker.search.setHint(people ? "Search by name or ID" : "Search by unit or plate");
        picker.search.setVisibility(View.VISIBLE);
        picker.setConfirmEnabled(false);
        picker.confirm.setOnClickListener(v -> {
            Option selected = pending[0];
            if (selected == null) return;
            if (people) worker = selected; else shuttle = selected;
            chosen(people ? R.id.btnShiftWorker : R.id.btnShiftShuttle, selected.name());
            picker.sheet.dismiss();
        });
        picker.state(people ? "Loading workers…" : "Loading shuttle units…", "");
        picker.show();
        String key = people ? "workers" : "shuttles";
        FleetApi.get("/" + key, new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject result) {
                if (!active() || !picker.sheet.isShowing()) return;
                JSONArray rows = result.optJSONArray(key);
                if (rows == null) {
                    picker.state("Could not load choices", "Close this panel and try again.");
                    return;
                }
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    if (people) {
                        if (!"DRIVER".equals(row.optString("role")) || row.optBoolean("pending")) continue;
                        String id = FleetApi.text(row, "employeeId");
                        if (!id.isEmpty()) options.add(new Option(id, row.optString("fullName"), id));
                    } else {
                        String status = FleetApi.text(row, "status");
                        if ("MAINTENANCE".equals(status) || "OFFLINE".equals(status)) continue;
                        String id = FleetApi.text(row, "shuttleId");
                        String detail = FleetApi.text(row, "plateNumber");
                        String model = FleetApi.text(row, "vehicleModel");
                        if (!model.isEmpty()) detail += " · " + model;
                        if (!id.isEmpty()) options.add(new Option(id, "Shuttle " + id, detail));
                    }
                }
                options.sort(java.util.Comparator.comparing(Option::name, String.CASE_INSENSITIVE_ORDER));
                if (pending[0] != null) {
                    String previous = pending[0].id();
                    pending[0] = options.stream().filter(item -> item.id().equals(previous)).findFirst().orElse(null);
                }
                Runnable draw = new Runnable() {
                    @Override public void run() {
                        picker.body.removeAllViews();
                        String query = picker.search.getText().toString().trim().toLowerCase(Locale.ROOT);
                        int shown = 0;
                        for (Option item : options) {
                            String terms = (item.id() + " " + item.name() + " " + item.detail()).toLowerCase(Locale.ROOT);
                            if (!terms.contains(query)) continue;
                            shown++;
                            boolean selected = item.equals(pending[0]);
                            LinearLayout row = new LinearLayout(context);
                            row.setOrientation(LinearLayout.VERTICAL);
                            row.setBackgroundResource(selected ? R.drawable.icon_square_bg : R.drawable.bg_ff_card);
                            int inset = ScheduleDates.dp(context, 12);
                            row.setPadding(inset, inset, inset, inset);
                            row.addView(WorkerDialogs.detailText(context, item.name() + (selected ? "  ✓" : ""), 14, true));
                            row.addView(WorkerDialogs.detailText(context, item.detail(), 12, false));
                            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                            params.bottomMargin = ScheduleDates.dp(context, 6);
                            picker.body.addView(row, params);
                            row.setSelected(selected);
                            row.setFocusable(true);
                            row.setOnClickListener(v -> { pending[0] = item; run(); });
                        }
                        if (shown == 0) {
                            if (!options.isEmpty()) picker.state("No matching results", "Try a different search.");
                            else if (people) picker.state("No available workers yet", "Add a worker to start managing your personnel");
                            else if (rows.length() == 0) picker.state("No shuttle units yet", "Add a shuttle unit to get started.");
                            else picker.state("No available shuttle units", "Units in maintenance or offline cannot be scheduled.");
                        }
                        picker.setConfirmEnabled(pending[0] != null);
                    }
                };
                FieldUi.onChange(picker.search, draw);
                draw.run();
            }

            @Override public void onError(int status, String message) {
                if (active() && picker.sheet.isShowing()) picker.state("Could not load choices", message);
            }
        });
    }

    private void pickTime(boolean from) {
        int value = from ? start : end;
        Calendar now = Calendar.getInstance();
        int hour = value < 0 ? now.get(Calendar.HOUR_OF_DAY) : value / 60;
        int minute = value < 0 ? now.get(Calendar.MINUTE) : value % 60;
        TimePickerDialog picker = new TimePickerDialog(context, (v, h, m) -> {
            if (from) start = h * 60 + m; else end = h * 60 + m;
            chosen(from ? R.id.btnShiftStart : R.id.btnShiftEnd, time(h * 60 + m));
        }, hour, minute, DateFormat.is24HourFormat(context));
        picker.setTitle(from ? "Start Time" : "End Time");
        picker.show();
    }

    private void chosen(int viewId, String value) {
        TextView field = form.findViewById(viewId);
        field.setText(value);
        field.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        changed();
    }

    private void changed() {
        requestId = UUID.randomUUID().toString();
        error.setVisibility(View.GONE);
    }

    private void save() {
        if (saving) return;
        if (date == null) { showError("Select a shift date."); return; }
        if (worker == null) { showError("Select a worker."); return; }
        if (shuttle == null) { showError("Select a shuttle unit."); return; }
        if (start < 0 || end < 0) { showError("Select start and end times."); return; }
        if (end <= start) { showError("End Time must be after Start Time on the same date."); return; }

        JSONObject data = new JSONObject();
        try {
            data.put("id", requestId).put("shiftDate", ScheduleDates.iso(date))
                    .put("employeeId", worker.id()).put("shuttleId", shuttle.id())
                    .put("startTime", time(start)).put("endTime", time(end))
                    .put("notesBase64", Base64.encodeToString(notes.getText().toString()
                            .getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        } catch (JSONException invalid) {
            showError("Could not prepare this shift. Check the fields and try again.");
            return;
        }
        setSaving(true);
        error.setVisibility(View.GONE);
        FleetApi.post("/scheduled-shifts", data, new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject result) {
                if (!active()) return;
                if (!requestId.equals(result.optString("id"))) {
                    setSaving(false);
                    showError("The server could not confirm this save. Refresh the schedule before retrying.");
                    return;
                }
                Calendar savedDay = (Calendar) date.clone();
                dialog.dismiss();
                onSaved.accept(savedDay);
            }

            @Override public void onError(int status, String message) {
                if (!active()) return;
                setSaving(false);
                showError(status == 404 && "Endpoint Not Found".equalsIgnoreCase(message)
                        ? "Update and restart your Java server to add dated shifts." : message);
            }
        });
    }

    private boolean active() {
        return dialog.isShowing() && Objects.equals(token, Session.getToken())
                && (!(context instanceof Activity activity) || (!activity.isFinishing() && !activity.isDestroyed()));
    }

    private void setSaving(boolean busy) {
        saving = busy;
        dialog.setCancelable(!busy);
        dialog.findViewById(R.id.btnDetailBack).setEnabled(!busy);
        ((TextView) add).setText(busy ? "Saving…" : "Add Shift");
        for (int id : new int[]{R.id.btnShiftDate, R.id.btnShiftWorker, R.id.btnShiftShuttle,
                R.id.btnShiftStart, R.id.btnShiftEnd, R.id.etShiftNotes, R.id.btnSaveDatedShift}) {
            form.findViewById(id).setEnabled(!busy);
        }
    }

    private void showError(String text) {
        error.setText(text);
        error.setVisibility(View.VISIBLE);
    }

    private static String time(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private final class Picker {
        final BottomSheetDialog sheet;
        final LinearLayout body;
        final EditText search;
        final TextView confirm;

        Picker(String title, String button) {
            View content = LayoutInflater.from(context).inflate(R.layout.sheet_shift_picker, null);
            sheet = new BottomSheetDialog(context);
            sheet.setContentView(content);
            ((TextView) content.findViewById(R.id.tvShiftPickerTitle)).setText(title);
            body = content.findViewById(R.id.shiftPickerBody);
            search = content.findViewById(R.id.etShiftPickerSearch);
            confirm = content.findViewById(R.id.btnConfirmShiftPicker);
            confirm.setText(button);
            content.findViewById(R.id.btnCloseShiftPicker).setOnClickListener(v -> sheet.dismiss());
        }

        void state(String title, String message) {
            body.removeAllViews();
            TextView heading = WorkerDialogs.detailText(context, title, 15, true);
            heading.setGravity(Gravity.CENTER);
            int spacing = ScheduleDates.dp(context, 12);
            heading.setPadding(0, spacing, 0, spacing / 2);
            body.addView(heading, new LinearLayout.LayoutParams(-1, -2));
            if (!message.isEmpty()) {
                TextView detail = WorkerDialogs.detailText(context, message, 13, false);
                detail.setGravity(Gravity.CENTER);
                detail.setPadding(0, 0, 0, spacing);
                body.addView(detail, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        void setConfirmEnabled(boolean enabled) {
            confirm.setEnabled(enabled);
            confirm.setAlpha(enabled ? 1f : 0.45f);
        }

        void show() {
            sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            sheet.show();
            if (sheet.getWindow() != null) {
                sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }
    }
}
