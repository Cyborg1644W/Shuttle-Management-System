package com.shuttle.mobile.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.GestureDetector;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.provider.Settings;

import androidx.core.location.LocationManagerCompat;

import com.shuttle.mobile.BuildConfig;
import com.shuttle.mobile.R;
import com.shuttle.mobile.gps.LiveMapUiState;
import com.shuttle.mobile.gps.LiveMapUiState.Fix;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class LiveRouteMap extends FrameLayout {
    private static final double STREET_ZOOM = 16.0;
    private static final int SEGMENT_SIZE = 128;
    private final MapView streets;
    private final Marker shuttle;
    private final TextView badge;
    private final ConnectivityManager network;
    private final GestureDetector taps;
    private final ArrayList<GeoPoint> points = new ArrayList<>();
    private Polyline segment;
    private int segmentSize;
    private boolean following = true, summary, fitPending, resumed, destroyed;
    private float downX, downY;
    private GeoPoint terminal, target;
    private Fix rendered;
    private long shiftStart;
    private ValueAnimator movement;

    private final ConnectivityManager.NetworkCallback connectivity = new ConnectivityManager.NetworkCallback() {
        @Override public void onLost(Network n) { post(LiveRouteMap.this::updateNetwork); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            post(LiveRouteMap.this::updateNetwork);
        }
    };

    public LiveRouteMap(Context context, AttributeSet attrs) {
        super(context, attrs);
        // Configure storage before inflating the osmdroid MapView.
        File base = new File(context.getCacheDir(), "osm");
        Configuration.getInstance().setOsmdroidBasePath(base);
        Configuration.getInstance().setOsmdroidTileCache(new File(base, "tiles"));
        Configuration.getInstance().setTileFileSystemCacheMaxBytes(150L << 20);
        Configuration.getInstance().setTileFileSystemCacheTrimBytes(120L << 20);
        Configuration.getInstance().setUserAgentValue("FleetFlow/" + BuildConfig.VERSION_NAME + " (" + context.getPackageName() + ")");
        inflate(context, R.layout.view_live_route, this);
        network = context.getSystemService(ConnectivityManager.class);
        streets = findViewById(R.id.streets);
        streets.setDestroyMode(false); // The owning Activity releases the map exactly once in onDestroy.
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                releaseCamera();
                return false;
            }
        });
        streets.setTileSource(TileSourceFactory.MAPNIK);
        streets.setMultiTouchControls(true);
        streets.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        shuttle = new Marker(streets);
        shuttle.setIcon(context.getDrawable(R.drawable.ic_route_shuttle));
        shuttle.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        shuttle.setInfoWindow(null);
        shuttle.setFlat(true);
        shuttle.setEnabled(false);
        streets.getOverlays().add(shuttle);
        badge = findViewById(R.id.routeStatus);
        findViewById(R.id.recenterRoute).setOnClickListener(v -> {
            following = true;
            if (summary) fitRoute();
            else if (target != null) streets.getController().animateTo(target,
                    Math.max(streets.getZoomLevelDouble(), STREET_ZOOM), 450L);
            else centerBeforeFix();
            updateControl();
        });
        TextView credit = findViewById(R.id.routeAttribution);
        SpannableString label = new SpannableString(credit.getText());
        label.setSpan(new URLSpan("https://www.openstreetmap.org/copyright"), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        credit.setText(label);
        credit.setMovementMethod(LinkMovementMethod.getInstance());
        streets.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (summary && following && (fitPending || r-l != or-ol || b-t != ob-ot)) fitRoute();
        });
        centerBeforeFix();
    }

    // Watch real touch gestures before MapView consumes them. Programmatic camera moves keep follow enabled.
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        taps.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (following && (e.getPointerCount() > 1 || Math.hypot(e.getX()-downX, e.getY()-downY)
                        > ViewConfiguration.get(getContext()).getScaledTouchSlop())) {
                    releaseCamera();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
        }
        return super.dispatchTouchEvent(e);
    }

    private void releaseCamera() {
        following = false;
        streets.getController().stopAnimation(false);
        updateControl();
    }

    public void setTerminal(GeoPoint assigned) {
        if (java.util.Objects.equals(terminal, assigned)) return;
        terminal = assigned;
        if (target == null && following) centerBeforeFix();
    }

    private void centerBeforeFix() {
        streets.getController().setZoom(terminal == null ? 5.0 : STREET_ZOOM);
        streets.getController().setCenter(terminal == null ? new GeoPoint(12.85, 122.8) : terminal);
    }

    public void render(LiveMapUiState state, boolean permission, Runnable requestPermission) {
        if (destroyed) return;
        if (shiftStart != state.start) {
            resetTrail();
            shiftStart = state.start;
        }
        boolean ended = state.phase(System.currentTimeMillis()) == LiveMapUiState.Phase.ENDED;
        boolean justEnded = ended && !summary;
        summary = ended;
        if (rendered != state.last) {
            boolean first = rendered == null;
            for (Fix fix : state.fixesAfter(rendered)) appendPoint(fix.lat, fix.lon);
            rendered = state.last;
            moveMarker(new GeoPoint(rendered.lat, rendered.lon), rendered.bearing, !first && !ended);
            if (!ended && following) {
                if (first) streets.getController().setZoom(STREET_ZOOM);
                streets.getController().animateTo(target);
            }
        }
        if (justEnded) {
            following = true;
            finishMovement();
            fitRoute();
        }
        String status = "";
        switch (state.phase(System.currentTimeMillis())) {
            case WAITING_FOR_GPS: status = getContext().getString(R.string.route_waiting); break;
            case WEAK_SIGNAL:
                String time = android.text.format.DateFormat.getTimeFormat(getContext()).format(new Date(rendered.time));
                status = getContext().getString(R.string.route_last_updated, time);
                break;
            case ENDED:
                if (points.isEmpty()) status = getContext().getString(R.string.route_empty_summary);
                break;
            default: break;
        }
        boolean needsPermission = state.active() && !permission;
        boolean locationOff = state.active() && permission && !LocationManagerCompat.isLocationEnabled(
                getContext().getSystemService(LocationManager.class));
        if (needsPermission) status = getContext().getString(R.string.route_permission);
        if (locationOff) {
            String action = getContext().getString(R.string.route_enable_location);
            status = status.isEmpty() ? action : getContext().getString(R.string.route_status_action, status, action);
        }
        showBadge(status);
        if (needsPermission) badge.setOnClickListener(v -> requestPermission.run());
        else if (locationOff) badge.setOnClickListener(v -> getContext().startActivity(
                new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        else badge.setOnClickListener(null);
        badge.setClickable(needsPermission || locationOff);
        updateControl();
    }

    void showSummary(List<double[]> route) {
        resetTrail();
        summary = true;
        for (double[] p : route) if (LiveMapUiState.valid(p[0], p[1])) appendPoint(p[0], p[1]);
        if (!points.isEmpty()) moveMarker(points.get(points.size()-1), Float.NaN, false);
        showBadge(points.isEmpty() ? getContext().getString(R.string.route_empty_summary) : "");
        fitRoute();
    }

    private void showBadge(String text) {
        if (!text.contentEquals(badge.getText())) badge.setText(text);
        badge.setVisibility(text.isEmpty() ? GONE : VISIBLE);
    }

    private void resetTrail() {
        finishMovement();
        while (streets.getOverlays().size() > 1) {
            streets.getOverlays().remove(0).onDetach(streets);
        }
        points.clear();
        segment = null;
        rendered = null;
        target = null;
        summary = fitPending = false;
        following = true;
        shuttle.setEnabled(false);
        shuttle.setRotation(0);
        centerBeforeFix();
        streets.invalidate();
    }

    private void appendPoint(double lat, double lon) {
        GeoPoint previous = points.isEmpty() ? null : points.get(points.size()-1);
        if (previous != null && previous.getLatitude() == lat && previous.getLongitude() == lon) return;
        GeoPoint next = new GeoPoint(lat, lon);
        if (segment == null || segmentSize >= SEGMENT_SIZE) {
            segment = new Polyline(streets);
            segment.setInfoWindow(null);
            segment.getOutlinePaint().setColor(getContext().getColor(R.color.brand_600));
            segment.getOutlinePaint().setStrokeWidth(dp(4));
            segment.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
            streets.getOverlays().add(0, segment);
            segmentSize = 0;
            if (previous != null) segment.addPoint(previous);
        }
        segment.addPoint(next);
        segmentSize++;
        points.add(next);
    }

    private void moveMarker(GeoPoint next, float bearing, boolean animate) {
        if (movement != null) {
            movement.cancel();
            movement = null;
        }
        GeoPoint from = shuttle.getPosition();
        float angle = shuttle.getRotation();
        float delta = Float.isFinite(bearing) ? (float) Math.IEEEremainder(-bearing - angle, 360) : 0;
        target = next;
        shuttle.setEnabled(true);
        if (animate && resumed) {
            double longitudeDelta = Math.IEEEremainder(next.getLongitude() - from.getLongitude(), 360);
            movement = ValueAnimator.ofFloat(0f, 1f);
            movement.setDuration(550);
            movement.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                shuttle.setPosition(new GeoPoint(from.getLatitude() + (next.getLatitude()-from.getLatitude())*f,
                        Math.IEEEremainder(from.getLongitude() + longitudeDelta*f, 360)));
                shuttle.setRotation((angle + f*delta) % 360);
                streets.invalidate();
            });
            movement.start();
        } else {
            shuttle.setPosition(next);
            shuttle.setRotation((angle + delta) % 360);
            streets.invalidate();
        }
    }

    private void finishMovement() {
        if (movement != null) { movement.end(); movement = null; }
    }

    private void fitRoute() {
        if (points.isEmpty()) return;
        fitPending = streets.getWidth() <= 2*dp(32) || streets.getHeight() <= 2*dp(32);
        if (!fitPending) streets.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, dp(32), STREET_ZOOM, null);
    }

    private void updateControl() {
        findViewById(R.id.recenterRoute).setVisibility(following || (target == null && terminal == null) ? GONE : VISIBLE);
    }

    private void updateNetwork() {
        if (!resumed) return;
        NetworkCapabilities caps = network.getNetworkCapabilities(network.getActiveNetwork());
        boolean online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        streets.setUseDataConnection(online);
        findViewById(R.id.routeOffline).setVisibility(online ? GONE : VISIBLE);
        streets.invalidate();
    }

    public void resume() {
        if (resumed || destroyed) return;
        resumed = true;
        streets.onResume();
        network.registerDefaultNetworkCallback(connectivity);
        updateNetwork();
    }

    public void pause() {
        if (!resumed) return;
        resumed = false;
        network.unregisterNetworkCallback(connectivity);
        finishMovement();
        streets.onPause();
    }

    public void destroy() {
        pause();
        destroyed = true;
        streets.onDetach();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
