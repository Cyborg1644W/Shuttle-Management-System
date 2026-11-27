package com.shuttle.mobile.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;
import com.shuttle.mobile.R;
import com.shuttle.mobile.gps.LiveMapUiState;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Marker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static com.shuttle.mobile.ui.FleetStatusMapUiState.*;

public final class FleetStatusMap extends StreetMapCard {
    private final Marker terminalPin;
    final Map<String, Vehicle> vehicles = new LinkedHashMap<>();
    private android.view.View inspector;
    private TextView inspectorUnit, assignment, signal;
    private FleetMonitorViewModel model;
    private FleetStatusMapUiState state = new FleetStatusMapUiState();
    private List<GeoPoint> framed = Collections.emptyList();
    private GeoPoint terminal;
    private boolean framedOnce;
    private boolean drawingTrail;
    private final Runnable drawTrail = this::drawTrailFrame;

    final class Vehicle {
        Unit unit;
        final Trail trail;
        final java.util.ArrayDeque<Point> pending = new java.util.ArrayDeque<>();
        final GeoPoint drawingPosition = new GeoPoint(0.0, 0.0);
        GeoPoint position, from;
        long moveStarted;
        Signal signal = Signal.WAITING;
        final android.graphics.RectF hit = new android.graphics.RectF();
        final android.graphics.Point anchor = new android.graphics.Point();
        Vehicle(Unit unit) { this.unit = unit; trail = new Trail(unit.color); }
    }

    public FleetStatusMap(Context context, AttributeSet attrs) {
        super(context, attrs);
        inflate(context, R.layout.view_fleet_map_controls, this);
        streets.setContentDescription(context.getString(R.string.fleet_map_description));
        ((TextView) findViewById(R.id.routeOffline)).setText(R.string.fleet_cached_tiles);
        terminalPin = new Marker(streets);
        terminalPin.setIcon(context.getDrawable(R.drawable.ic_map_terminal));
        terminalPin.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        terminalPin.setEnabled(false);
        streets.getOverlays().add(terminalPin);
        streets.getOverlays().add(new FleetMarkerOverlay(this));
        android.view.View fitButton = findViewById(R.id.fleetFitAll);
        fitButton.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            android.view.View badges = (android.view.View) badge.getParent();
            LayoutParams params = (LayoutParams) badges.getLayoutParams();
            int reserved = r - l + dp(16);
            if (params.getMarginEnd() != reserved) {
                params.setMarginEnd(reserved);
                badges.setLayoutParams(params);
            }
        });
        findViewById(R.id.fleetFitAll).setOnClickListener(v -> { if (model != null) { framedOnce = false; model.overview(); } });
        findViewById(R.id.recenterRoute).setOnClickListener(v -> select(state.selected));
        streets.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (r-l != or-ol || b-t != ob-ot) camera(true);
        });
    }

    void bind(FleetMonitorViewModel model, android.view.View page) {
        this.model = model;
        inspector = page.findViewById(R.id.fleetInspector);
        inspectorUnit = page.findViewById(R.id.fleetInspectorUnit);
        assignment = page.findViewById(R.id.fleetInspectorAssignment);
        signal = page.findViewById(R.id.fleetInspectorSignal);
    }
    @Override boolean scrollPageOnVerticalDrag() { return true; }
    private void scheduleTrail() {
        if (resumed && !drawingTrail) {
            drawingTrail = true;
            postOnAnimation(drawTrail);
        }
    }
    private void drawTrailFrame() {
        drawingTrail = false;
        long deadline = System.nanoTime() + 3_000_000;
        for (Vehicle vehicle : vehicles.values()) {
            while (!vehicle.pending.isEmpty()) {
                Point fix = vehicle.pending.removeFirst();
                vehicle.trail.append(fix.lat, fix.lon);
                if (System.nanoTime() >= deadline) {
                    streets.invalidate();
                    scheduleTrail();
                    return;
                }
            }
        }
        streets.invalidate();
    }
    @Override public void resume() {
        super.resume();
        scheduleTrail();
    }
    void select(String id) { if (model != null && id != null) { framedOnce = false; model.select(id); } }
    String selected() { return state.selected; }
    Signal signal(Unit unit) { return vehicles.get(unit.shift).signal; }
    @Override void releaseCamera() { if (model != null) model.manual(); }
    @Override void connectivityChanged(boolean online) { if (model != null) model.network(online); }
    @Override public void pause() {
        if (resumed && model != null && state.camera == Camera.MANUAL) {
            model.manualLat = streets.getMapCenter().getLatitude();
            model.manualLon = streets.getMapCenter().getLongitude();
            model.manualZoom = streets.getZoomLevelDouble();
        }
        removeCallbacks(drawTrail);
        drawingTrail = false;
        super.pause();
    }

    void render(FleetStatusMapUiState next) {
        if (destroyed) return;
        boolean changedSelection = !Objects.equals(state.selected, next.selected);
        Unit oldFocus = state.units.get(state.selected), newFocus = next.units.get(next.selected);
        boolean firstFix = oldFocus != null && oldFocus.last == null && newFocus != null && newFocus.last != null;
        boolean changedCamera = state.camera != next.camera || changedSelection || firstFix;
        boolean restoreViewport = !state.loaded && next.camera == Camera.MANUAL && model.manualZoom > 0;
        boolean changedTerminal = !state.terminal.equals(next.terminal) || Double.compare(state.terminalLat, next.terminalLat) != 0
                || Double.compare(state.terminalLon, next.terminalLon) != 0;
        boolean changedUnits = state.units != next.units;
        state = next;
        if (changedUnits) {
            Iterator<Map.Entry<String,Vehicle>> iter = vehicles.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<String,Vehicle> entry = iter.next();
                if (!next.units.containsKey(entry.getKey())) {
                    entry.getValue().trail.clear();
                    iter.remove();
                }
            }
            for (Unit u : next.units.values()) {
                Vehicle v = vehicles.get(u.shift);
                Point rendered = v == null ? null : v.unit.last;
                if (v == null) { v = new Vehicle(u); vehicles.put(u.shift, v); }
                if (u.last != null && rendered != u.last) {
                    v.pending.addAll(u.last.after(rendered));
                    scheduleTrail();
                    v.from = v.position;
                    v.moveStarted = android.os.SystemClock.uptimeMillis();
                    v.position = new GeoPoint(u.last.lat, u.last.lon);
                }
                v.unit = u;
            }
        }
        if (changedTerminal) {
            terminal = LiveMapUiState.valid(next.terminalLat, next.terminalLon)
                    ? new GeoPoint(next.terminalLat, next.terminalLon) : null;
            terminalPin.setEnabled(terminal != null);
            if (terminal != null) { terminalPin.setPosition(terminal); terminalPin.setTitle(next.terminal); }
            else terminalPin.closeInfoWindow();
        }
        if (changedSelection || changedUnits) {
            for (Vehicle v : vehicles.values()) {
                boolean dim = next.selected != null && !next.selected.equals(v.unit.shift);
                v.trail.color(dim ? (v.unit.color & 0x00ffffff) | 0x40000000 : v.unit.color);
            }
        }
        if (restoreViewport) {
            streets.getController().setZoom(model.manualZoom);
            streets.getController().setCenter(new GeoPoint(model.manualLat, model.manualLon));
        }
        camera(changedCamera);
        int message = 0;
        if (state.connection == Connection.LOADING) message = R.string.fleet_loading;
        else if (state.connection == Connection.ONLINE && state.units.isEmpty()) message = R.string.fleet_map_empty;
        showBadge(message == 0 ? "" : getContext().getString(message));
        Unit unit = state.units.get(state.selected);
        inspector.setVisibility(unit == null ? GONE : VISIBLE);
        findViewById(R.id.recenterRoute).setVisibility(unit != null && unit.last != null ? VISIBLE : GONE);
        if (unit != null) {
            inspectorUnit.setText(getContext().getString(R.string.fleet_unit_plate, unit.id, known(unit.plate)));
            assignment.setText(getContext().getString(R.string.fleet_details, known(unit.driver), known(unit.terminal)));
        }
        tick(System.currentTimeMillis());
        if (changedUnits || changedSelection || changedTerminal) streets.invalidate();
    }

    void tick(long now) {
        boolean changed = false;
        for (Vehicle v : vehicles.values()) {
            Signal signal = state.signal(v.unit, now);
            if (signal != v.signal) { v.signal = signal; changed = true; }
        }
        Unit unit = state.units.get(state.selected);
        if (unit != null) {
            String details = details(unit, now);
            if (!details.contentEquals(signal.getText())) signal.setText(details);
        }
        if (changed) streets.invalidate();
    }

    String details(Unit unit, long now) {
        if (unit.last == null) return getContext().getString(R.string.fleet_waiting);
        String age = relativeTime(unit.last.time, now);
        Signal current = signal(unit);
        return current == Signal.STALE ? getContext().getString(R.string.fleet_stale_updated, age)
                : getContext().getString(R.string.fleet_updated, getContext().getString(label(current)), age);
    }

    static int label(Signal s) {
        switch (s) {
            case MOVING: return R.string.fleet_moving;
            case IDLE: return R.string.fleet_idle;
            case AT_TERMINAL: return R.string.fleet_at_terminal;
            case STALE: return R.string.fleet_stale;
            default: return R.string.fleet_waiting;
        }
    }
    private String known(String value) { return value.isEmpty() ? getContext().getString(R.string.fleet_unknown) : value; }

    private void camera(boolean force) {
        if (state.camera == Camera.MANUAL || streets.getWidth() == 0 || !state.loaded) return;
        ArrayList<GeoPoint> targets = new ArrayList<>();
        if (state.camera == Camera.FOLLOW) {
            Vehicle v = vehicles.get(state.selected);
            if (v != null && v.position != null) targets.add(v.position);
        } else {
            for (Vehicle v : vehicles.values()) if (v.position != null) targets.add(v.position);
            if (targets.isEmpty() && terminal != null) targets.add(terminal);
        }
        if (targets.isEmpty() && state.camera == Camera.FOLLOW) return;
        if (!force && framedOnce && targets.equals(framed)) return;
        if (targets.isEmpty()) centerBeforeFix(null);
        else if (state.camera == Camera.FOLLOW) {
            streets.getController().animateTo(targets.get(0),
                    force || !framedOnce ? STREET_ZOOM : streets.getZoomLevelDouble(), 400L);
        } else if (Collections.frequency(targets, targets.get(0)) == targets.size()) {
            streets.getController().animateTo(targets.get(0), STREET_ZOOM, 400L);
        } else if (!fit(targets, dp(48), framedOnce)) return;
        framedOnce = true;
        framed = targets;
    }
}
