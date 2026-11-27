package com.shuttle.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import com.shuttle.mobile.R;

/** Draws the north-facing shuttle at a GPS bearing. Labels can be drawn separately to stay upright. */
final class ShuttleIcon extends Drawable {
    private final Drawable glyph;
    private final ColorFilter grey;
    private float heading;
    private boolean muted;
    ShuttleIcon(Context context) {
        glyph = context.getDrawable(R.drawable.ic_route_shuttle).mutate();
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0);
        grey = new ColorMatrixColorFilter(matrix);
    }
    float heading() { return heading; }
    void heading(float value) { heading = Float.isFinite(value) ? value : 0; }
    void muted(boolean value) {
        if (muted != value) { muted = value; glyph.setColorFilter(value ? grey : null); }
    }
    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int save = canvas.save();
        canvas.rotate(heading, bounds.exactCenterX(), bounds.exactCenterY());
        glyph.draw(canvas);
        canvas.restoreToCount(save);
    }
    @Override protected void onBoundsChange(Rect bounds) { glyph.setBounds(bounds); }
    @Override public int getIntrinsicWidth() { return glyph.getIntrinsicWidth(); }
    @Override public int getIntrinsicHeight() { return glyph.getIntrinsicHeight(); }
    @Override public void setAlpha(int alpha) { glyph.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { glyph.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
