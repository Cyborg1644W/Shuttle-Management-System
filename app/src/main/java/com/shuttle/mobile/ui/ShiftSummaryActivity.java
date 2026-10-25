package com.shuttle.mobile.ui;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.shuttle.mobile.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class ShiftSummaryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shift_summary);

        long start = getIntent().getLongExtra("startTime", 0);
        ShiftStore.Entry e = ShiftStore.find(this, start);

        if (e == null) {
            Toast.makeText(this, "Shift not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault());
        SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());

        ((TextView) findViewById(R.id.tvDuration)).setText(ShiftStore.formatDuration(e.durationMs()));
        ((TextView) findViewById(R.id.tvDate)).setText(dateFmt.format(new Date(e.start)));
        ((TextView) findViewById(R.id.tvSumDistance)).setText(String.format(Locale.US, "%.1f", e.distanceKm));
        ((TextView) findViewById(R.id.tvSumStops)).setText(String.valueOf(e.stops));
        ((TextView) findViewById(R.id.tvSumSpeed)).setText(String.format(Locale.US, "%.0f", e.avgSpeedKmh()));
        ((TextView) findViewById(R.id.tvSumVehicle)).setText(e.vehicle);
        ((TextView) findViewById(R.id.tvSumTerminal)).setText(e.terminal);
        ((TextView) findViewById(R.id.tvSumStart)).setText(timeFmt.format(new Date(e.start)));
        ((TextView) findViewById(R.id.tvSumEnd)).setText(timeFmt.format(new Date(e.end)));

        // The route that was driven
        PureJavaMapCanvas map = findViewById(R.id.mapSummary);
        ArrayList<double[]> route = ShiftStore.parseRoute(e.route);
        map.setTraveled(route);
        if (!route.isEmpty()) {
            map.setCurrent(route.get(route.size() - 1));
        }

        findViewById(R.id.ivBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnDone).setOnClickListener(v -> finish());
    }
}