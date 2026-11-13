package com.shuttle.mobile.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.shuttle.mobile.R;
import com.shuttle.mobile.client.ApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Fleet {

    private static class Driver {
        final String name;
        final String empId;
        final String phone;
        final String unit;
        final boolean onShift;
        int minutes;

        Driver(String name, String empId, String phone, String unit,
               boolean onShift, int minutes) {
            this.name = name;
            this.empId = empId;
            this.phone = phone;
            this.unit = unit;
            this.onShift = onShift;
            this.minutes = minutes;
        }
    }

    private final Context context;
    private final List<Driver> drivers = new ArrayList<>();

    private LinearLayout list;
    private TextView tvCount;
    private TextView tvEmpty;
    private TextView chipAll;
    private TextView chipOn;
    private TextView chipOff;

    private String query = "";
    private int filter = 0;

    private Fleet(Context context) {
        this.context = context;
    }

    public static View create(Context context, ViewGroup parent) {
        return new Fleet(context).build(parent);
    }

    private void loadDrivers() {
        final CharSequence emptyText = tvEmpty.getText();
        tvEmpty.setText("Loading drivers...");

        ApiClient.getInstance().getWorkers(new ApiClient.Callback<JSONArray>() {
            @Override
            public void onSuccess(JSONArray result) {
                drivers.clear();
                for (int i = 0; i < result.length(); i++) {
                    JSONObject w = result.optJSONObject(i);
                    if (w == null || !"DRIVER".equalsIgnoreCase(w.optString("role"))) {
                        continue;
                    }
                    String shuttle = w.optString("shuttleId");
                    drivers.add(new Driver(
                            w.optString("fullName"),
                            w.optString("employeeId"),
                            w.optString("phoneDisplay"),
                            shuttle.isEmpty() ? "Unassigned" : shuttle,
                            "ON_SHIFT".equals(w.optString("shiftStatus")),
                            -1));
                }
                tvEmpty.setText(emptyText);
                render();
                loadShifts();
            }

            @Override
            public void onError(String message) {
                tvEmpty.setText("Could not load drivers: " + message);
                render();
            }
        });
    }

    private void loadShifts() {
        ApiClient.getInstance().getShifts(new ApiClient.Callback<JSONArray>() {
            @Override
            public void onSuccess(JSONArray result) {
                // The server lists shifts newest first, so the first one found for a
                // driver is their current shift (if running) or their last one.
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < result.length(); i++) {
                    JSONObject s = result.optJSONObject(i);
                    if (s == null) {
                        continue;
                    }
                    String driverId = s.optString("driverId");
                    if (!seen.add(driverId)) {
                        continue;
                    }
                    for (Driver d : drivers) {
                        if (d.empId.equals(driverId)) {
                            d.minutes = (int) (s.optLong("durationMs") / 60000L);
                            break;
                        }
                    }
                }
                render();
            }

            @Override
            public void onError(String message) {
                // Keep the drivers on screen; durations simply stay as "--".
            }
        });
    }

    private View build(ViewGroup parent) {
        View root = LayoutInflater.from(context).inflate(R.layout.activity_operator_fleet, parent, false);

        list = root.findViewById(R.id.fleetList);
        tvCount = root.findViewById(R.id.tvFleetCount);
        tvEmpty = root.findViewById(R.id.tvFleetEmpty);
        chipAll = root.findViewById(R.id.chipAll);
        chipOn = root.findViewById(R.id.chipOn);
        chipOff = root.findViewById(R.id.chipOff);
        EditText etSearch = root.findViewById(R.id.etFleetSearch);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                render();
            }
        });

        chipAll.setOnClickListener(v -> {
            filter = 0;
            render();
        });
        chipOn.setOnClickListener(v -> {
            filter = 1;
            render();
        });
        chipOff.setOnClickListener(v -> {
            filter = 2;
            render();
        });

        render();
        loadDrivers();
        return root;
    }

    private void render() {
        list.removeAllViews();
        String q = query.toLowerCase(Locale.ROOT).trim();
        int shown = 0;

        for (Driver d : drivers) {
            if (filter == 1 && !d.onShift) continue;
            if (filter == 2 && d.onShift) continue;
            if (!q.isEmpty()
                    && !d.name.toLowerCase(Locale.ROOT).contains(q)
                    && !d.empId.toLowerCase(Locale.ROOT).contains(q)
                    && !d.unit.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            list.addView(buildItem(d));
            shown++;
        }

        tvCount.setText(shown + (shown == 1 ? " driver" : " drivers"));
        tvEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);

        styleChip(chipAll, filter == 0);
        styleChip(chipOn, filter == 1);
        styleChip(chipOff, filter == 2);
    }

    private View buildItem(Driver d) {
        View item = LayoutInflater.from(context).inflate(R.layout.item_fleet_driver, list, false);

        ((TextView) item.findViewById(R.id.tvAvatar)).setText(initials(d.name));
        ((TextView) item.findViewById(R.id.tvName)).setText(d.name);
        ((TextView) item.findViewById(R.id.tvMeta)).setText(d.empId + " \u00B7 " + d.phone);
        ((TextView) item.findViewById(R.id.tvUnit)).setText(d.unit);
        ((TextView) item.findViewById(R.id.tvDurationLabel))
                .setText(d.onShift ? "Current shift" : "Last shift");
        ((TextView) item.findViewById(R.id.tvDuration)).setText(d.minutes < 0 ? "--" : formatDuration(d.minutes));

        TextView status = item.findViewById(R.id.tvStatus);
        status.setText(d.onShift ? "On shift" : "Off shift");
        int main = ContextCompat.getColor(context, d.onShift ? R.color.ff_active : R.color.ff_inactive);
        int soft = ContextCompat.getColor(context, d.onShift ? R.color.ff_active_soft : R.color.ff_inactive_soft);
        status.setTextColor(main);
        status.setBackgroundTintList(ColorStateList.valueOf(soft));

        return item;
    }

    private void styleChip(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.tab_selected : R.drawable.tab_unselected);
        chip.setTextColor(ContextCompat.getColor(context,
                selected ? R.color.white : R.color.text_secondary));
    }

    private static String initials(String name) {
        String[] parts = name.trim().split("\\s+");
        String first = parts[0].substring(0, 1);
        String second = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (first + second).toUpperCase(Locale.ROOT);
    }

    private static String formatDuration(int totalMinutes) {
        int h = totalMinutes / 60;
        int m = totalMinutes % 60;
        return h + "h " + String.format(Locale.ROOT, "%02d", m) + "m";
    }
}