package com.shuttle.mobile.gps;

import android.os.Handler;
import android.os.Looper;

public class SimulatedGpsSource {

    public interface Listener {
        void onLocation(double latitude, double longitude, float speedMps);
    }

    private static final double[][] ROUTE = {
            {14.6500, 120.9600},
            {14.6520, 120.9650},
            {14.6560, 120.9680},
            {14.6590, 120.9640},
            {14.6560, 120.9590},
    };

    private static final int STEPS_PER_SEGMENT = 10;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long intervalMs;
    private Listener listener;
    private boolean running = false;

    private int segment = 0;
    private int step = 0;

    public SimulatedGpsSource(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public void start(Listener listener) {
        if (running) return;
        this.listener = listener;
        this.running = true;
        handler.post(tick);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    public boolean isRunning() {
        return running;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;

            double[] a = ROUTE[segment];
            double[] b = ROUTE[(segment + 1) % ROUTE.length];
            double t = (double) step / STEPS_PER_SEGMENT;

            double lat = a[0] + (b[0] - a[0]) * t;
            double lng = a[1] + (b[1] - a[1]) * t;
            float speed = 8f + (float) (Math.random() * 4);

            if (listener != null) listener.onLocation(lat, lng, speed);

            step++;
            if (step >= STEPS_PER_SEGMENT) {
                step = 0;
                segment = (segment + 1) % ROUTE.length;
            }
            handler.postDelayed(this, intervalMs);
        }
    };
}