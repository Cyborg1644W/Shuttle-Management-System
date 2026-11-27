package com.shuttle.mobile.ui;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.shuttle.mobile.R;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class ShiftSummaryActivity extends AppCompatActivity {
    private LiveRouteMap map;
    /** Compact trip details retain access to the existing full route summary. */
    public static void showDetails(android.app.Activity activity, String shiftId) {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(activity);
        android.widget.LinearLayout content = WorkerDialogs.detailContent(activity);
        int pad = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        TextView title = WorkerDialogs.detailText(activity, "Trip Details", 18, true);
        content.addView(title);
        TextView state = WorkerDialogs.detailText(activity, "Loading trip…", 12, false);
        content.addView(state);
        android.widget.LinearLayout rows = WorkerDialogs.detailContent(activity);
        content.addView(rows);
        com.google.android.material.button.MaterialButton route =
                new com.google.android.material.button.MaterialButton(activity);
        route.setText("View recorded route");
        route.setVisibility(android.view.View.GONE);
        content.addView(route, new android.widget.LinearLayout.LayoutParams(-1, -2));
        com.google.android.material.button.MaterialButton done =
                new com.google.android.material.button.MaterialButton(activity);
        done.setText("Done");
        content.addView(done, new android.widget.LinearLayout.LayoutParams(-1, -2));
        done.setOnClickListener(v -> sheet.dismiss());
        route.setOnClickListener(v -> {
            sheet.dismiss();
            activity.startActivity(new android.content.Intent(activity, ShiftSummaryActivity.class)
                    .putExtra("shiftId", shiftId));
        });
        sheet.setContentView(content);
        sheet.show();
        String token = Session.getToken();
        FleetApi.get("/shift/" + FleetApi.encode(shiftId), new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject trip) {
                if (activity.isFinishing() || activity.isDestroyed() || !sheet.isShowing()
                        || !java.util.Objects.equals(token, Session.getToken())) return;
                long start = trip.optLong("startTime");
                long end = trip.optLong("endTime");
                SimpleDateFormat date = new SimpleDateFormat("EEE, MMM d", Locale.getDefault());
                SimpleDateFormat time = new SimpleDateFormat("h:mm a", Locale.getDefault());
                String status = FleetApi.text(trip, "status").replace('_', ' ');
                state.setText(date.format(new Date(start)) + (status.isEmpty() ? "" : " · " + status));
                WorkerDialogs.informationRow(activity, rows, "Vehicle", trip.optString("shuttleId"));
                WorkerDialogs.informationRow(activity, rows, "Terminal", trip.optString("terminal"));
                WorkerDialogs.informationRow(activity, rows, "Shift", time.format(new Date(start)) + " – "
                        + (end > 0 ? time.format(new Date(end)) : "In progress"));
                WorkerDialogs.informationRow(activity, rows, "Duration",
                        FleetApi.formatDuration(trip.optLong("durationMs", Math.max(0, end - start))));
                WorkerDialogs.informationRow(activity, rows, "Distance",
                        trip.has("distanceKm") ? trip.optString("distanceKm") + " km" : "Not recorded");
                WorkerDialogs.informationRow(activity, rows, "Stops",
                        trip.has("stops") ? trip.optString("stops") : "Not recorded");
                route.setVisibility(android.view.View.VISIBLE);
                route.setEnabled(!FleetApi.text(trip, "route").isEmpty());
            }
            @Override public void onError(int status, String message) {
                if (!activity.isFinishing() && sheet.isShowing()) state.setText(message);
            }
        });
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shift_summary);
        map = findViewById(R.id.mapSummary);

        findViewById(R.id.ivBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnDone).setOnClickListener(v -> finish());

        String shiftId = getIntent().getStringExtra("shiftId");
        if (shiftId == null || shiftId.isEmpty()) {
            Toast.makeText(this, "Shift not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        FleetApi.get("/shift/" + FleetApi.encode(shiftId), new FleetApi.Callback() {
            @Override
            public void onSuccess(JSONObject json) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                showShift(json);
            }

            @Override
            public void onError(int status, String message) {
                if (isFinishing()) {
                    return;
                }
                Toast.makeText(ShiftSummaryActivity.this, message, Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    private void showShift(JSONObject s) {
        long start = s.optLong("startTime", 0);
        long end = s.optLong("endTime", 0);
        long durationMs = s.optLong("durationMs", Math.max(0, end - start));

        SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault());
        SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());

        String vehicle = FleetApi.text(s, "shuttleId");
        String terminal = FleetApi.text(s, "terminal");

        ((TextView) findViewById(R.id.tvDuration)).setText(FleetApi.formatDuration(durationMs));
        ((TextView) findViewById(R.id.tvDate)).setText(dateFmt.format(new Date(start)));
        ((TextView) findViewById(R.id.tvSumDistance)).setText(
                String.format(Locale.US, "%.1f", s.optDouble("distanceKm", 0)));
        ((TextView) findViewById(R.id.tvSumStops)).setText(String.valueOf(s.optInt("stops", 0)));
        ((TextView) findViewById(R.id.tvSumSpeed)).setText(
                String.format(Locale.US, "%.0f", s.optDouble("avgSpeedKmh", 0)));
        ((TextView) findViewById(R.id.tvSumVehicle)).setText(vehicle.isEmpty() ? "-" : vehicle);
        ((TextView) findViewById(R.id.tvSumTerminal)).setText(terminal.isEmpty() ? "-" : terminal);
        ((TextView) findViewById(R.id.tvSumStart)).setText(timeFmt.format(new Date(start)));
        ((TextView) findViewById(R.id.tvSumEnd)).setText(end > 0 ? timeFmt.format(new Date(end)) : "-");

        ArrayList<double[]> route = FleetApi.parseRoute(FleetApi.text(s, "route"));
        map.showSummary(route);
    }

    @Override protected void onResume() { super.onResume(); map.resume(); }
    @Override protected void onPause() { map.pause(); super.onPause(); }
    @Override protected void onDestroy() { map.destroy(); super.onDestroy(); }
}
