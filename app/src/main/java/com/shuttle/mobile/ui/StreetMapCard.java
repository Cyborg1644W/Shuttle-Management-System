package com.shuttle.mobile.ui;

import android.content.Context;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.shuttle.common.ShiftTime;
import com.shuttle.mobile.BuildConfig;
import com.shuttle.mobile.R;
import com.shuttle.mobile.gps.LiveMapUiState;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Common street tiles, controls, trail geometry and lifecycle for driver and operator cards. */
abstract class StreetMapCard extends FrameLayout {
    static final double STREET_ZOOM = 18;
    final MapView streets;
    final TextView badge;
    boolean resumed, destroyed;
    private final ConnectivityManager network;
    private final GestureDetector taps;
    private float downX, downY;
    private boolean gestureReleased;

    StreetMapCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        File base = new File(context.getCacheDir(), "osm");
        Configuration.getInstance().setOsmdroidBasePath(base);
        Configuration.getInstance().setOsmdroidTileCache(new File(base, "tiles"));
        Configuration.getInstance().setTileFileSystemCacheMaxBytes(150L << 20);
        Configuration.getInstance().setTileFileSystemCacheTrimBytes(120L << 20);
        Configuration.getInstance().setUserAgentValue("FleetFlow/" + BuildConfig.VERSION_NAME + " (" + context.getPackageName() + ")");
        inflate(context, R.layout.view_live_route, this);
        streets = findViewById(R.id.streets);
        streets.setDestroyMode(false);
        streets.setTileSource(TileSourceFactory.MAPNIK);
        streets.setTilesScaledToDpi(true);
        streets.setMultiTouchControls(true);
        streets.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        badge = findViewById(R.id.routeStatus);
        network = context.getSystemService(ConnectivityManager.class);
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { manualGesture(); return false; }
        });
        TextView credit = findViewById(R.id.routeAttribution);
        SpannableString label = new SpannableString(credit.getText());
        label.setSpan(new URLSpan("https://www.openstreetmap.org/copyright"), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        credit.setText(label);
        credit.setMovementMethod(LinkMovementMethod.getInstance());
        centerBeforeFix(null);
    }

    abstract void releaseCamera();
    void connectivityChanged(boolean hasNetwork) { }
    boolean scrollPageOnVerticalDrag() { return false; }
    boolean mapDrag;
    void finishMovement() { }

    private void manualGesture() {
        if (gestureReleased) return;
        gestureReleased = true;
        streets.getController().stopAnimation(false);
        releaseCamera();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); gestureReleased = false;
                mapDrag = !scrollPageOnVerticalDrag();
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() > 1) mapDrag = true;
                float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                if (e.getPointerCount() > 1 || Math.hypot(dx, dy) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    if (!mapDrag && dy > dx) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    }
                    mapDrag = true;
                    manualGesture();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
        }
        taps.onTouchEvent(e);
        return super.dispatchTouchEvent(e);
    }

    void centerBeforeFix(GeoPoint terminal) {
        streets.getController().setZoom(terminal == null ? 5.0 : STREET_ZOOM);
        streets.getController().setCenter(terminal == null ? new GeoPoint(12.85, 122.8) : terminal);
    }

    boolean fit(List<GeoPoint> points, int padding, boolean animate) {
        if (points.isEmpty() || streets.getWidth() <= 2*padding || streets.getHeight() <= 2*padding) return false;
        streets.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), animate, padding, STREET_ZOOM, null);
        return true;
    }

    static GeoPoint terminalPoint(JSONObject row) {
        JSONObject nested = row.optJSONObject("terminalCoordinates");
        double lat = nested == null ? row.optDouble("terminalLatitude", Double.NaN) : nested.optDouble("latitude", Double.NaN);
        double lon = nested == null ? row.optDouble("terminalLongitude", Double.NaN) : nested.optDouble("longitude", Double.NaN);
        return LiveMapUiState.valid(lat, lon) ? new GeoPoint(lat, lon) : null;
    }

    ShuttleIcon shuttleIcon() { return new ShuttleIcon(getContext()); }
    static String fixTime(long millis) { return ShiftTime.timestamp(millis, "h:mm a"); }
    static String relativeTime(long millis, long now) {
        long seconds = Math.max(0, now - millis) / 1000;
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return seconds / 60 + "m ago";
        if (seconds < 86400) return seconds / 3600 + "h ago";
        return ShiftTime.timestamp(millis, "MMM d") + " at " + fixTime(millis);
    }

    void showBadge(String text) {
        if (!text.contentEquals(badge.getText())) badge.setText(text);
        badge.setVisibility(text.isEmpty() ? GONE : VISIBLE);
    }

    final class Trail {
        final ArrayList<GeoPoint> points = new ArrayList<>();
        private final ArrayList<Polyline> segments = new ArrayList<>();
        private int count, color;

        Trail(int color) { this.color = color; }

        void append(double lat, double lon) {
            GeoPoint last = points.isEmpty() ? null : points.get(points.size()-1);
            if (last != null && last.getLatitude() == lat && last.getLongitude() == lon) return;
            if (segments.isEmpty() || count >= 128) {
                Polyline line = new Polyline(streets);
                line.setInfoWindow(null);
                line.getOutlinePaint().setColor(color);
                line.getOutlinePaint().setStrokeWidth(dp(4));
                line.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
                streets.getOverlays().add(0, line);
                segments.add(line);
                count = 0;
                if (last != null) line.addPoint(last);
            }
            GeoPoint next = new GeoPoint(lat, lon);
            segments.get(segments.size()-1).addPoint(next);
            points.add(next);
            count++;
        }

        void color(int value) {
            if (color == value) return;
            color = value;
            for (Polyline line : segments) line.getOutlinePaint().setColor(value);
        }

        void clear() {
            for (Polyline line : segments) {
                streets.getOverlays().remove(line);
                line.onDetach(streets);
            }
            segments.clear(); points.clear(); count = 0;
        }
    }

    private final ConnectivityManager.NetworkCallback connectivity = new ConnectivityManager.NetworkCallback() {
        @Override public void onLost(Network n) { post(StreetMapCard.this::updateNetwork); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) { post(StreetMapCard.this::updateNetwork); }
    };

    private void updateNetwork() {
        if (!resumed) return;
        NetworkCapabilities caps = network.getNetworkCapabilities(network.getActiveNetwork());
        boolean online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        streets.setUseDataConnection(online);
        findViewById(R.id.routeOffline).setVisibility(online ? GONE : VISIBLE);
        connectivityChanged(caps != null);
        streets.invalidate();
    }

    public void resume() {
        if (resumed || destroyed) return;
        resumed = true; streets.onResume();
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
        if (destroyed) return;
        pause(); destroyed = true; streets.onDetach();
    }
    int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
