package com.shuttle.mobile.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.util.AttributeSet;
import android.provider.Settings;

import androidx.core.location.LocationManagerCompat;

import com.shuttle.mobile.R;
import com.shuttle.mobile.gps.LiveMapUiState;
import com.shuttle.mobile.gps.LiveMapUiState.Fix;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Marker;
import java.util.List;

public final class LiveRouteMap extends StreetMapCard {
    private final Marker shuttle;
    private final ShuttleIcon icon;
    private final Trail trail;
    private boolean following = true, summary, fitPending;
    private GeoPoint terminal, target;
    private Fix rendered;
    private long shiftStart;
    private ValueAnimator movement;

    public LiveRouteMap(Context context, AttributeSet attrs) {
        super(context, attrs);
        trail = new Trail(context.getColor(R.color.brand_600));
        shuttle = new Marker(streets);
        icon = shuttleIcon();
        shuttle.setIcon(icon);
        shuttle.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        shuttle.setInfoWindow(null);
        shuttle.setFlat(true);
        shuttle.setEnabled(false);
        streets.getOverlays().add(shuttle);
        findViewById(R.id.recenterRoute).setOnClickListener(v -> {
            following = true;
            if (summary) fitRoute();
            else if (target != null) streets.getController().animateTo(target, STREET_ZOOM, 450L);
            else centerBeforeFix(terminal);
            updateControl();
        });
        streets.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (summary && following && (fitPending || r-l != or-ol || b-t != ob-ot)) fitRoute();
        });
        centerBeforeFix(terminal);
    }

    @Override void releaseCamera() {
        following = false;
        updateControl();
    }

    public void setTerminal(GeoPoint assigned) {
        if (java.util.Objects.equals(terminal, assigned)) return;
        terminal = assigned;
        if (target == null && following) centerBeforeFix(terminal);
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
            for (Fix fix : state.fixesAfter(rendered)) trail.append(fix.lat, fix.lon);
            rendered = state.last;
            moveMarker(new GeoPoint(rendered.lat, rendered.lon), rendered.bearing, !first && !ended);
            if (!ended && following) {
                if (first) streets.getController().animateTo(target, STREET_ZOOM, 450L);
                else streets.getController().animateTo(target);
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
                String time = fixTime(rendered.time);
                status = getContext().getString(R.string.route_last_updated, time);
                break;
            case ENDED:
                if (trail.points.isEmpty()) status = getContext().getString(R.string.route_empty_summary);
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
        for (double[] p : route) if (com.shuttle.common.ValidationUtil.isTrackingFix(p[0], p[1])) trail.append(p[0], p[1]);
        if (!trail.points.isEmpty()) moveMarker(trail.points.get(trail.points.size()-1), Float.NaN, false);
        showBadge(trail.points.isEmpty() ? getContext().getString(R.string.route_empty_summary) : "");
        fitRoute();
    }

    private void resetTrail() {
        finishMovement();
        trail.clear();
        rendered = null;
        target = null;
        summary = fitPending = false;
        following = true;
        shuttle.setEnabled(false);
        icon.heading(0);
        centerBeforeFix(terminal);
        streets.invalidate();
    }

    private void moveMarker(GeoPoint next, float bearing, boolean animate) {
        if (movement != null) {
            movement.cancel();
            movement = null;
        }
        GeoPoint from = shuttle.getPosition();
        float angle = icon.heading();
        float delta = Float.isFinite(bearing) ? (float) Math.IEEEremainder(bearing - angle, 360) : 0;
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
                icon.heading((angle + f*delta) % 360);
                streets.invalidate();
            });
            movement.start();
        } else {
            shuttle.setPosition(next);
            icon.heading((angle + delta) % 360);
            streets.invalidate();
        }
    }

    @Override void finishMovement() {
        if (movement != null) { movement.end(); movement = null; }
    }

    private void fitRoute() {
        if (trail.points.isEmpty()) return;
        fitPending = !fit(trail.points, dp(32), false);
    }

    private void updateControl() {
        findViewById(R.id.recenterRoute).setVisibility(following || (target == null && terminal == null) ? GONE : VISIBLE);
    }

}
