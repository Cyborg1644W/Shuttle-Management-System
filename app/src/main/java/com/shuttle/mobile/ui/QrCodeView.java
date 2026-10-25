package com.shuttle.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.shuttle.mobile.R;

import java.util.Random;

// A QR-looking pattern for the demo pass. It is NOT a real QR code.
public class QrCodeView extends View {

    private static final int N = 25;
    private String seed = "FLEETFLOW";

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paper = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public QrCodeView(Context context) {
        super(context);
        init(context);
    }

    public QrCodeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(ContextCompat.getColor(context, R.color.brand_600));
        paper.setStyle(Paint.Style.FILL);
        paper.setColor(Color.WHITE);
    }

    public void setSeed(String seed) {
        this.seed = seed;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float size = Math.min(getWidth(), getHeight());
        float cell = size / N;
        float left = (getWidth() - size) / 2f;
        float top = (getHeight() - size) / 2f;

        Random random = new Random(seed.hashCode());

        // Data dots (soft rounded squares)
        for (int row = 0; row < N; row++) {
            for (int col = 0; col < N; col++) {
                if (nearFinder(row, col)) {
                    continue;
                }
                boolean on = random.nextBoolean();
                if (!on || inLogo(row, col)) {
                    continue;
                }
                float inset = cell * 0.07f;
                rect.set(left + col * cell + inset, top + row * cell + inset,
                        left + (col + 1) * cell - inset, top + (row + 1) * cell - inset);
                canvas.drawRoundRect(rect, cell * 0.32f, cell * 0.32f, ink);
            }
        }

        // The three corner markers
        drawFinder(canvas, left, top, cell, 0, 0);
        drawFinder(canvas, left, top, cell, 0, N - 7);
        drawFinder(canvas, left, top, cell, N - 7, 0);

        // Center badge
        float mid = N / 2f;
        drawRounded(canvas, left, top, cell, mid - 2.7f, mid - 2.7f, 5.4f, 1.4f, paper);
        drawRounded(canvas, left, top, cell, mid - 1.9f, mid - 1.9f, 3.8f, 1.1f, ink);
        drawRounded(canvas, left, top, cell, mid - 0.8f, mid - 0.8f, 1.6f, 0.8f, paper);
    }

    // Rounded ring, then a rounded block in the middle
    private void drawFinder(Canvas canvas, float left, float top, float cell, int row, int col) {
        drawRounded(canvas, left, top, cell, col, row, 7f, 2f, ink);
        drawRounded(canvas, left, top, cell, col + 1f, row + 1f, 5f, 1.3f, paper);
        drawRounded(canvas, left, top, cell, col + 2f, row + 2f, 3f, 1f, ink);
    }

    // x, y, sizeCells and radiusCells are all in cell units
    private void drawRounded(Canvas canvas, float left, float top, float cell,
                             float x, float y, float sizeCells, float radiusCells, Paint paint) {
        rect.set(left + x * cell, top + y * cell,
                left + (x + sizeCells) * cell, top + (y + sizeCells) * cell);
        canvas.drawRoundRect(rect, radiusCells * cell, radiusCells * cell, paint);
    }

    // Empty space in the middle for the badge
    private boolean inLogo(int r, int c) {
        return r >= 9 && r <= 15 && c >= 9 && c <= 15;
    }

    // The corner markers plus one cell of empty space around them
    private boolean nearFinder(int r, int c) {
        return (r < 8 && c < 8) || (r < 8 && c >= N - 8) || (r >= N - 8 && c < 8);
    }
}