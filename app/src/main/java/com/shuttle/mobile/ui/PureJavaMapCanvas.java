package com.shuttle.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.shuttle.mobile.R;

import java.util.ArrayList;
import java.util.List;

// A tiny map drawn by hand: no Google Maps, no map SDK.
// Turns latitude/longitude into x/y pixels and draws the route on a Canvas.
public class PureJavaMapCanvas extends View {

    private final ArrayList<double[]> planned = new ArrayList<>();   // dashed guide route
    private final ArrayList<double[]> traveled = new ArrayList<>();  // solid driven route
    private double[] current = null;                                  // vehicle marker

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plannedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint travelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path clipPath = new Path();
    private final Path routePath = new Path();

    private float density;

    // projection (set every time the map is drawn)
    private double minLng;
    private double maxLat;
    private double cosLat;
    private float scale;
    private float offsetX;
    private float offsetY;

    public PureJavaMapCanvas(Context context) {
        super(context);
        init(context);
    }

    public PureJavaMapCanvas(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public PureJavaMapCanvas(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        density = getResources().getDisplayMetrics().density;

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(ContextCompat.getColor(context, R.color.surface_subtle));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1 * density);
        gridPaint.setColor(ContextCompat.getColor(context, R.color.border));

        plannedPaint.setStyle(Paint.Style.STROKE);
        plannedPaint.setStrokeWidth(3 * density);
        plannedPaint.setStrokeCap(Paint.Cap.ROUND);
        plannedPaint.setStrokeJoin(Paint.Join.ROUND);
        plannedPaint.setColor(ContextCompat.getColor(context, R.color.text_tertiary));
        plannedPaint.setPathEffect(new DashPathEffect(new float[]{10 * density, 8 * density}, 0));

        travelPaint.setStyle(Paint.Style.STROKE);
        travelPaint.setStrokeWidth(4 * density);
        travelPaint.setStrokeCap(Paint.Cap.ROUND);
        travelPaint.setStrokeJoin(Paint.Join.ROUND);
        travelPaint.setColor(ContextCompat.getColor(context, R.color.brand_600));

        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);

        haloPaint.setStyle(Paint.Style.FILL);
        haloPaint.setColor(ContextCompat.getColor(context, R.color.brand_600));
        haloPaint.setAlpha(50);

        textPaint.setColor(ContextCompat.getColor(context, R.color.text_secondary));
        textPaint.setTextSize(13 * density);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void addLiveLocationUpdate(double lat, double lng) {
        double[] location = new double[]{lat, lng};
        current = location;
        traveled.add(location);
        invalidate();
    }

    // ---------- What to draw ----------

    public void setPlanned(List<double[]> points) {
        planned.clear();
        planned.addAll(points);
        invalidate();
    }

    public void setTraveled(List<double[]> points) {
        traveled.clear();
        traveled.addAll(points);
        invalidate();
    }

    // The vehicle marker. Pass null to hide it.
    public void setCurrent(double[] point) {
        current = point;
        invalidate();
    }

    // ---------- Drawing ----------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float radius = 16 * density;

        // Rounded corners
        clipPath.reset();
        clipPath.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);

        canvas.drawRect(0, 0, w, h, bgPaint);

        // Grid
        float step = 40 * density;
        for (float x = step; x < w; x += step) {
            canvas.drawLine(x, 0, x, h, gridPaint);
        }
        for (float y = step; y < h; y += step) {
            canvas.drawLine(0, y, w, y, gridPaint);
        }

        if (!computeProjection(w, h)) {
            canvas.drawText("No route yet", w / 2, h / 2, textPaint);
            canvas.restore();
            return;
        }

        drawLine(canvas, planned, plannedPaint);
        drawLine(canvas, traveled, travelPaint);

        // Start marker: white dot with a green ring
        double[] start = null;
        if (!planned.isEmpty()) {
            start = planned.get(0);
        } else if (!traveled.isEmpty()) {
            start = traveled.get(0);
        }
        if (start != null) {
            fillPaint.setColor(ContextCompat.getColor(getContext(), R.color.white));
            strokePaint.setColor(ContextCompat.getColor(getContext(), R.color.brand_600));
            strokePaint.setStrokeWidth(3 * density);
            canvas.drawCircle(px(start[1]), py(start[0]), 6 * density, fillPaint);
            canvas.drawCircle(px(start[1]), py(start[0]), 6 * density, strokePaint);
        }

        // Vehicle marker
        if (current != null) {
            float cx = px(current[1]);
            float cy = py(current[0]);
            canvas.drawCircle(cx, cy, 16 * density, haloPaint);

            fillPaint.setColor(ContextCompat.getColor(getContext(), R.color.brand_600));
            canvas.drawCircle(cx, cy, 8 * density, fillPaint);

            strokePaint.setColor(ContextCompat.getColor(getContext(), R.color.white));
            strokePaint.setStrokeWidth(3 * density);
            canvas.drawCircle(cx, cy, 8 * density, strokePaint);
        }

        canvas.restore();
    }

    private void drawLine(Canvas canvas, List<double[]> points, Paint paint) {
        if (points.size() < 2) {
            return;
        }
        routePath.reset();
        for (int i = 0; i < points.size(); i++) {
            float x = px(points.get(i)[1]);
            float y = py(points.get(i)[0]);
            if (i == 0) {
                routePath.moveTo(x, y);
            } else {
                routePath.lineTo(x, y);
            }
        }
        canvas.drawPath(routePath, paint);
    }

    private float px(double lng) {
        return offsetX + (float) ((lng - minLng) * cosLat * scale);
    }

    private float py(double lat) {
        return offsetY + (float) ((maxLat - lat) * scale);
    }

    private boolean computeProjection(float w, float h) {
        double mnLat = 90;
        double mxLat = -90;
        double mnLng = 180;
        double mxLng = -180;
        boolean any = false;

        for (double[] p : planned) {
            mnLat = Math.min(mnLat, p[0]);
            mxLat = Math.max(mxLat, p[0]);
            mnLng = Math.min(mnLng, p[1]);
            mxLng = Math.max(mxLng, p[1]);
            any = true;
        }
        for (double[] p : traveled) {
            mnLat = Math.min(mnLat, p[0]);
            mxLat = Math.max(mxLat, p[0]);
            mnLng = Math.min(mnLng, p[1]);
            mxLng = Math.max(mxLng, p[1]);
            any = true;
        }
        if (current != null) {
            mnLat = Math.min(mnLat, current[0]);
            mxLat = Math.max(mxLat, current[0]);
            mnLng = Math.min(mnLng, current[1]);
            mxLng = Math.max(mxLng, current[1]);
            any = true;
        }
        if (!any || w <= 0 || h <= 0) {
            return false;
        }

        double latSpan = Math.max(mxLat - mnLat, 0.002);
        double lngSpan = Math.max(mxLng - mnLng, 0.002);
        double centerLat = (mnLat + mxLat) / 2;
        double centerLng = (mnLng + mxLng) / 2;

        maxLat = centerLat + latSpan / 2;
        minLng = centerLng - lngSpan / 2;
        cosLat = Math.cos(Math.toRadians(centerLat));

        double spanX = lngSpan * cosLat;
        float pad = 28 * density;

        float scaleX = (float) ((w - 2 * pad) / spanX);
        float scaleY = (float) ((h - 2 * pad) / latSpan);
        scale = Math.min(scaleX, scaleY);

        offsetX = (float) ((w - spanX * scale) / 2);
        offsetY = (float) ((h - latSpan * scale) / 2);
        return true;
    }
}