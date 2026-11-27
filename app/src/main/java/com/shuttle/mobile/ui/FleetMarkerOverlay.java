package com.shuttle.mobile.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shuttle.mobile.R;
import org.osmdroid.views.MapView;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import com.shuttle.mobile.ui.FleetStatusMap.Vehicle;
import static com.shuttle.mobile.ui.FleetStatusMapUiState.Signal;

/** Screen-space labels stay upright. Leader lines keep displaced labels attached to their true GPS position. */
final class FleetMarkerOverlay extends Overlay {
    private final FleetStatusMap card;
    private final ShuttleIcon bus;
    private final Drawable unorientedBus;
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Vehicle> visible = new ArrayList<>();
    private final int moving, idle = 0xff986300, stale = 0xff596573;

    FleetMarkerOverlay(FleetStatusMap card) {
        this.card = card;
        moving = card.getContext().getColor(R.color.brand_600);
        bus = card.shuttleIcon();
        unorientedBus = card.getContext().getDrawable(R.drawable.ic_vehicle).mutate();
        unorientedBus.setTint(Color.WHITE);
        pen.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
    }

    @Override public void draw(Canvas canvas, Projection projection) {
        visible.clear();
        Vehicle focus = card.vehicles.get(card.selected());
        if (focus != null) place(focus, projection, true);
        for (Vehicle v : card.vehicles.values()) if (v != focus) place(v, projection, false);
        for (Vehicle v : visible) {
            pen.setStyle(Paint.Style.FILL); pen.setColor(v.unit.color); pen.setStrokeWidth(card.dp(2));
            canvas.drawLine(v.anchor.x, v.anchor.y, v.hit.centerX(), v.hit.centerY(), pen);
            canvas.drawCircle(v.anchor.x, v.anchor.y, card.dp(3), pen);
        }
        for (Vehicle v : visible) if (v != focus) drawBadge(canvas, v, false);
        if (focus != null && !focus.hit.isEmpty()) drawBadge(canvas, focus, true);
    }

    private void place(Vehicle v, Projection projection, boolean focus) {
        v.hit.setEmpty();
        if (v.position == null) return;
        GeoPoint point = v.position;
        if (v.from != null) {
            float fraction = (android.os.SystemClock.uptimeMillis() - v.moveStarted) / 450f;
            if (card.resumed && fraction < 1f) {
                double delta = Math.IEEEremainder(point.getLongitude() - v.from.getLongitude(), 360);
                v.drawingPosition.setCoords(v.from.getLatitude() + (point.getLatitude()-v.from.getLatitude()) * fraction,
                        Math.IEEEremainder(v.from.getLongitude() + delta * fraction, 360));
                point = v.drawingPosition;
                card.streets.postInvalidateOnAnimation();
            } else v.from = null;
        }
        projection.toPixels(point, v.anchor);
        int w = projection.getWidth(), h = projection.getHeight();
        if (v.anchor.x < 0 || v.anchor.y < 0 || v.anchor.x > w || v.anchor.y > h) return;
        pen.setTextSize(card.dp(focus ? 15 : 13));
        float width = Math.min(w, card.dp(46) + Math.min(pen.measureText(v.unit.id), card.dp(106)));
        float height = card.dp(focus ? 35 : 30);
        float left = Math.max(0, Math.min(w-width, v.anchor.x-width/2));
        float top = Math.max(0, Math.min(h-height, v.anchor.y-height-card.dp(5)));
        // Try alternating rows above and below the pin, then neighboring columns.
        for (int col = 0; col < 3; col++) {
            float x = Math.max(0, Math.min(w-width, left + (col == 1 ? -1 : col == 2 ? 1 : 0)*(width+card.dp(4))));
            for (int i = 0; i < 11; i++) {
                int row = (i % 2 == 0 ? -1 : 1) * ((i+1)/2);
                float y = top + row*(height+card.dp(4));
                if (y < 0 || y+height > h) continue;
                v.hit.set(x, y, x+width, y+height);
                boolean free = true;
                for (Vehicle other : visible) if (RectF.intersects(other.hit, v.hit)) { free = false; break; }
                if (free) { visible.add(v); return; }
            }
        }
        v.hit.set(left, top, left+width, top+height);
        visible.add(v);
    }

    private void drawBadge(Canvas c, Vehicle v, boolean focus) {
        RectF r = v.hit;
        Signal signal = v.signal;
        pen.setStyle(Paint.Style.FILL);
        pen.setColor(signal == Signal.STALE ? 0xffe3e6e9 : Color.WHITE);
        c.drawRoundRect(r, r.height()/2, r.height()/2, pen);
        pen.setColor(v.unit.color); pen.setStyle(Paint.Style.STROKE); pen.setStrokeWidth(card.dp(focus ? 4 : 2));
        c.drawRoundRect(r, r.height()/2, r.height()/2, pen);
        pen.setStyle(Paint.Style.FILL);
        int radius = card.dp(focus ? 13 : 11);
        float cx = r.left + radius + card.dp(4), cy = r.centerY();
        pen.setColor(signal == Signal.STALE ? stale : signal == Signal.MOVING ? moving : idle);
        c.drawCircle(cx, cy, radius+card.dp(2), pen);
        c.save();
        c.translate(cx, cy);
        if (Double.isFinite(v.unit.bearing)) {
            bus.heading((float)v.unit.bearing);
            bus.setBounds(-radius, -radius, radius, radius);
            bus.muted(signal != Signal.MOVING);
            bus.draw(c);
        } else {
            int size = radius-card.dp(2);
            unorientedBus.setBounds(-size, -size, size, size);
            unorientedBus.draw(c);
        }
        c.restore();
        pen.setColor(signal == Signal.STALE ? stale : 0xff192a36);
        pen.setTextSize(card.dp(focus ? 15 : 13));
        float textX = cx+radius+card.dp(4);
        float baseline = cy - (pen.ascent()+pen.descent())/2;
        String label = v.unit.id;
        float space = r.right-textX-card.dp(signal == Signal.STALE ? 16 : 7);
        if (pen.measureText(label) > space) {
            int end = pen.breakText(label, true, Math.max(0, space-pen.measureText("…")), null);
            label = label.substring(0, end) + "…";
        }
        c.drawText(label, textX, baseline, pen);
        if (signal == Signal.STALE) {
            float x = r.right-card.dp(10);
            pen.setColor(stale); c.drawCircle(x, cy, card.dp(7), pen);
            pen.setColor(Color.WHITE); pen.setTextSize(card.dp(11));
            c.drawText("!", x-pen.measureText("!")/2, cy-(pen.ascent()+pen.descent())/2, pen);
        }
    }

    @Override public boolean onSingleTapConfirmed(MotionEvent event, MapView map) {
        ArrayList<Vehicle> hits = new ArrayList<>();
        for (Vehicle v : visible) if (v.hit.contains(event.getX(), event.getY())) hits.add(v);
        if (hits.isEmpty()) {
            for (Vehicle v : visible) if (Math.hypot(event.getX()-v.anchor.x, event.getY()-v.anchor.y) <= card.dp(12))
                hits.add(v);
        }
        if (hits.isEmpty()) return false;
        if (hits.size() == 1) card.select(hits.get(0).unit.shift);
        else {
            String[] names = new String[hits.size()];
            for (int i = 0; i < names.length; i++) names[i] = hits.get(i).unit.id;
            new MaterialAlertDialogBuilder(card.getContext()).setTitle(R.string.fleet_in_location)
                    .setItems(names, (dialog, which) -> card.select(hits.get(which).unit.shift)).show();
        }
        return true;
    }
}
