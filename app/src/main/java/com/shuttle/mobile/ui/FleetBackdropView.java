package com.shuttle.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** Resolution-independent decoration used by the Figma authentication screens. */
public final class FleetBackdropView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();

    public FleetBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if ("splash".equals(getTag())) {
            paint.setColor(Color.rgb(225, 245, 249));
            canvas.drawOval(-w * .32f, h * .03f, w * .35f, h * .29f, paint);
            canvas.drawOval(w * .72f, h * .23f, w * 1.12f, h * .40f, paint);
        }
        float dy = Math.min(86 * getResources().getDisplayMetrics().density, h * .13f);
        wave.reset();
        wave.moveTo(0, h - dy);
        wave.cubicTo(w * .3f, h + dy * .3f, w * .56f, h - dy * 1.7f, w, h - dy * .5f);
        wave.lineTo(w, h);
        wave.lineTo(0, h);
        wave.close();
        paint.setColor(Color.rgb(217, 243, 248));
        canvas.drawPath(wave, paint);
        wave.reset();
        wave.moveTo(0, h - dy * .25f);
        wave.cubicTo(w * .34f, h - dy * 1.4f, w * .62f, h - dy * .3f, w, h - dy * .6f);
        wave.lineTo(w, h);
        wave.lineTo(0, h);
        wave.close();
        paint.setColor(Color.rgb(153, 226, 239));
        canvas.drawPath(wave, paint);
    }
}
