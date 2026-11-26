package com.shuttle.mobile.gps;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.Context;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Observer;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import com.shuttle.mobile.client.ApiClient;
import com.shuttle.mobile.client.OfflineQueueManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GpsTrackerService extends Service {

    public static final String EXTRA_SHUTTLE_ID = "shuttle_id";
    public static final String EXTRA_OWNER = "route_owner";
    public static final String EXTRA_SHIFT = "route_shift";

    private static final String TAG = "GpsTrackerService";
    private static final String CHANNEL_ID = "gps_tracker_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long INTERVAL_MS = 5000;

    private String shuttleId, shiftId, owner;
    private RouteRepository routes;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable retryGps = this::startRealGps;
    private final Observer<LiveMapUiState> shiftObserver = state -> {
        if (!state.active() || !state.shiftId.equals(shiftId)) stopSelf();
        else if (locationCallback == null) startRealGps();
    };
    private OfflineQueueManager queue;
    private ApiClient api;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        ApiClient.initialize(this);
        queue = new OfflineQueueManager(this);
        api = ApiClient.getInstance();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !hasPermission(this) || intent.getStringExtra(EXTRA_OWNER) == null
                || intent.getStringExtra(EXTRA_SHIFT) == null || intent.getStringExtra(EXTRA_SHUTTLE_ID) == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String nextOwner = intent.getStringExtra(EXTRA_OWNER);
        String nextShift = intent.getStringExtra(EXTRA_SHIFT);
        shuttleId = intent.getStringExtra(EXTRA_SHUTTLE_ID);
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (IllegalStateException | SecurityException denied) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!nextOwner.equals(owner) || !nextShift.equals(shiftId)) {
            detach();
            owner = nextOwner;
            shiftId = nextShift;
            routes = RouteRepository.open(this, owner);
            routes.updates().observeForever(shiftObserver);
        }
        return START_REDELIVER_INTENT;
    }

    public static boolean hasPermission(Context context) {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startRealGps() {
        if (locationCallback != null) return;
        if (!hasPermission(this)) {
            stopSelf();
            return;
        }

        LocationRequest locationRequest =
                new LocationRequest.Builder(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        INTERVAL_MS
                )
                        .setMaxUpdateAgeMillis(0)
                        .setMinUpdateIntervalMillis(2000)
                        .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null || this != locationCallback) {
                    return;
                }

                for (Location location : result.getLocations()) {
                    double lat = location.getLatitude(), lon = location.getLongitude();
                    long time = location.getTime();
                    float speed = location.hasSpeed() ? location.getSpeed() : 0f;
                    String unit = shuttleId;
                    routes.record(shiftId, lat, lon, time, speed,
                            location.hasBearing() ? location.getBearing() : Float.NaN,
                            () -> upload(ApiClient.buildLocationJson(unit, lat, lon, speed, time)));
                }
            }
        };

        LocationCallback requested = locationCallback;
        try {
            fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
            ).addOnFailureListener(error -> {
                if (locationCallback != requested) return;
                Log.w(TAG, "Could not subscribe to location", error);
                locationCallback = null;
                main.postDelayed(retryGps, 15_000);
            });
        } catch (SecurityException denied) {
            stopSelf();
        }
    }

    // --- send / queue logic ---

    private synchronized void upload(String json) {
        if (worker.isShutdown()) return;
        worker.execute(() -> {
            if (!queue.isEmpty()) {
                int sent = queue.flush(api::postLocationBlocking);
                Log.d(TAG, "Flushed " + sent + " queued update(s)");
            }

            boolean sentSuccessfully =
                    queue.isEmpty() && api.postLocationBlocking(json);

            if (!sentSuccessfully) {
                queue.enqueue(json);
                Log.d(TAG, "Location queued for later upload.");
            }
        });
    }

    // --- lifecycle ---

    private void detach() {
        main.removeCallbacks(retryGps);
        if (routes != null) routes.updates().removeObserver(shiftObserver);
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
    }

    @Override
    public synchronized void onDestroy() {
        detach();
        worker.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- notification ---

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Shuttle GPS Tracking", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Shuttle tracking active")
                .setContentText("Sending location for " + shuttleId)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }
}
