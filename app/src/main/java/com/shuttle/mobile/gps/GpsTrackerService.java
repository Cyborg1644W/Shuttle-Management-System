package com.shuttle.mobile.gps;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

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
    public static final String EXTRA_SIMULATE = "simulate";

    private static final String TAG = "GpsTrackerService";
    private static final String CHANNEL_ID = "gps_tracker_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long INTERVAL_MS = 5000;

    private String shuttleId = "shuttle-1";
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private OfflineQueueManager queue;
    private ApiClient api;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        queue = new OfflineQueueManager(this);
        api = ApiClient.getInstance();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String id = intent.getStringExtra(EXTRA_SHUTTLE_ID);
            if (id != null) shuttleId = id;
        }

        startForeground(NOTIFICATION_ID, buildNotification());

        // The simulated GPS source was removed (SimulatedGpsSource was missing),
        // so EXTRA_SIMULATE is ignored and real GPS is always used.
        startRealGps();
        return START_STICKY;
    }

    private void startRealGps() {
        boolean fineLocationGranted =
                ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;

        boolean coarseLocationGranted =
                ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;

        if (!fineLocationGranted && !coarseLocationGranted) {
            Log.e(TAG, "Location permission has not been granted.");
            stopSelf();
            return;
        }

        // Don't stack up duplicate callbacks if the service is started twice
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        LocationRequest locationRequest =
                new LocationRequest.Builder(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        INTERVAL_MS
                )
                        .setMinUpdateIntervalMillis(2000)
                        .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) {
                    return;
                }

                for (Location location : result.getLocations()) {
                    handleNewPosition(
                            location.getLatitude(),
                            location.getLongitude(),
                            location.getSpeed(),
                            location.getTime()
                    );
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
        );
    }

    // --- send / queue logic ---

    private void handleNewPosition(
            double lat,
            double lng,
            float speed,
            long timestamp
    ) {
        Intent intent = new Intent("com.shuttle.mobile.LOCATION_UPDATE");

        intent.setPackage(getPackageName());
        intent.putExtra("latitude", lat);
        intent.putExtra("longitude", lng);
        intent.putExtra("speed", speed);

        sendBroadcast(intent);

        final String json =
                ApiClient.buildLocationJson(
                        shuttleId,
                        lat,
                        lng,
                        speed,
                        timestamp
                );

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

    @Override
    public void onDestroy() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

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