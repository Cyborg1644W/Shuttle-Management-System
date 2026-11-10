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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shift_summary);

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
                if (isFinishing()) {
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

        PureJavaMapCanvas map = findViewById(R.id.mapSummary);
        ArrayList<double[]> route = FleetApi.parseRoute(FleetApi.text(s, "route"));
        map.setTraveled(route);
        if (!route.isEmpty()) {
            map.setCurrent(route.get(route.size() - 1));
        }
    }
}