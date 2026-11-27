package com.shuttle.mobile.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shuttle.common.ShiftTime;
import com.shuttle.mobile.R;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.shuttle.mobile.ui.FleetStatusMapUiState.*;

/** Home's map and rows share the same retained state and one polling subscription. */
final class FleetStatusPanel {
    private final OperatorActivity activity;
    private final View page;
    private final TextView loadState;
    private final View retry;
    private final FleetMonitorViewModel model;
    private final FleetStatusMap map;
    private final LinearLayout list;
    private final View completed;
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final Handler clock = new Handler(Looper.getMainLooper());
    private boolean visible;
    private int historyRequest;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            long now = System.currentTimeMillis();
            map.tick(now);
            for (Row row : rows.values()) row.status(now);
            clock.postDelayed(this, 1000);
        }
    };

    FleetStatusPanel(OperatorActivity activity, View page) {
        this.activity = activity;
        this.page = page;
        loadState = page.findViewById(R.id.tvDashboardLoadState);
        retry = page.findViewById(R.id.btnDashboardRetry);
        map = page.findViewById(R.id.fleetStatusMap);
        list = page.findViewById(R.id.fleetStatusUnits);
        completed = page.findViewById(R.id.fleetCompleted);
        model = new ViewModelProvider(activity).get(FleetMonitorViewModel.class);
        map.bind(model, page);
        retry.setOnClickListener(v -> model.retry());
        model.updates().observe(activity, s -> { if (visible) render(s); });
        completed.setOnClickListener(v -> inspectTrips());
    }

    void visible(boolean show) {
        if (show == visible) return;
        visible = show;
        if (show) {
            map.resume();
            model.visible(true);
            render(model.updates().getValue());
            tick.run();
        } else {
            historyRequest++;
            completed.setEnabled(true);
            clock.removeCallbacks(tick);
            model.visible(false);
            map.pause();
        }
    }
    void destroy() { visible(false); map.destroy(); }

    private void render(FleetStatusMapUiState state) {
        map.render(state);
        Dashboard.render(page, state);
        String status = "";
        if (state.connection == Connection.ERROR) status = activity.getString(R.string.fleet_error_status, state.error);
        else if (state.connection == Connection.OFFLINE) status = activity.getString(R.string.fleet_offline_status);
        else if (state.connection == Connection.RECONNECTING) status = activity.getString(R.string.fleet_reconnecting);
        if (!status.contentEquals(loadState.getText())) loadState.setText(status);
        loadState.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        retry.setVisibility(state.connection == Connection.ERROR || state.connection == Connection.RECONNECTING
                ? View.VISIBLE : View.GONE);
        Iterator<Map.Entry<String,Row>> iterator = rows.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String,Row> entry = iterator.next();
            if (!state.units.containsKey(entry.getKey())) {
                list.removeView(entry.getValue().view);
                iterator.remove();
            }
        }
        for (Unit u : state.units.values()) {
            Row row = rows.get(u.shift);
            if (row == null) { row = new Row(u.shift); rows.put(u.shift, row); }
            row.bind(u, u.shift.equals(state.selected));
        }
    }

    private final class Row {
        final TextView view = new TextView(activity);
        Unit unit;
        String identity;
        boolean selected;
        Row(String shift) {
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(map.dp(12), map.dp(6), map.dp(8), map.dp(6));
            view.setMinHeight(map.dp(48));
            view.setTextSize(14);
            view.setTextColor(activity.getColor(R.color.text_primary));
            view.setFocusable(true);
            view.setOnClickListener(v -> map.select(shift));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = map.dp(4);
            list.addView(view, params);
        }
        void bind(Unit next, boolean focus) {
            if (unit == null || unit.color != next.color || selected != focus) {
                GradientDrawable background = new GradientDrawable();
                background.setCornerRadius(map.dp(8));
                background.setColor(focus ? ((next.color & 0xffffff) | 0x20000000) : Color.WHITE);
                background.setStroke(map.dp(focus ? 3 : 1), next.color);
                view.setBackground(background);
                view.setSelected(focus);
            }
            identity = next.plate.isEmpty() || next.id.equals(next.plate) ? next.id
                    : activity.getString(R.string.fleet_unit_plate, next.id, next.plate);
            unit = next;
            selected = focus;
            status(System.currentTimeMillis());
        }
        void status(long now) {
            String line = identity + "\n" + map.details(unit, now);
            if (!line.contentEquals(view.getText())) view.setText(line);
        }
    }

    private void inspectTrips() {
        int request = ++historyRequest;
        completed.setEnabled(false);
        FleetApi.get("/shift?summary=true", new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject response) {
                if (request != historyRequest || !visible) return;
                completed.setEnabled(true);
                JSONArray trips = response.optJSONArray("shifts");
                ArrayList<String> labels = new ArrayList<>(), ids = new ArrayList<>();
                for (int i = 0; trips != null && i < trips.length(); i++) {
                    JSONObject t = trips.optJSONObject(i);
                    if (t == null || t.optLong("endTime") <= 0) continue;
                    ids.add(t.optString("shiftId"));
                    labels.add(t.optString("shuttleId") + " · " + ShiftTime.timestamp(t.optLong("endTime"), "MMM d, h:mm a"));
                }
                MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(activity).setTitle(R.string.fleet_completed);
                if (ids.isEmpty()) dialog.setMessage(R.string.fleet_no_completed);
                else dialog.setItems(labels.toArray(new String[0]), (d, which) -> activity.startActivity(
                        new Intent(activity, ShiftSummaryActivity.class).putExtra("shiftId", ids.get(which))));
                dialog.setNegativeButton(android.R.string.cancel, null).show();
            }
            @Override public void onError(int status, String message) {
                if (request != historyRequest || !visible) return;
                completed.setEnabled(true);
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
