package com.shuttle.mobile.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.shuttle.mobile.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class ScheduleDates {
    static final String[] DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final TimeZone ZONE = TimeZone.getTimeZone("Asia/Manila");

    private ScheduleDates() { }

    static Calendar today() {
        Calendar day = Calendar.getInstance(ZONE);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        return day;
    }

    static String iso(Calendar day) { return format(day, "yyyy-MM-dd", Locale.US); }

    static String label(Calendar day, String pattern) { return format(day, pattern, Locale.getDefault()); }

    private static String format(Calendar day, String pattern, Locale locale) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern, locale);
        formatter.setTimeZone(ZONE);
        return formatter.format(day.getTime());
    }

    static int weekday(Calendar day) { return (day.get(Calendar.DAY_OF_WEEK) + 5) % 7; }

    static Calendar offset(Calendar date, int days) {
        Calendar result = (Calendar) date.clone();
        result.add(Calendar.DATE, days);
        return result;
    }

    static void monthGrid(GridLayout grid, Calendar month, Calendar selected, Calendar minimum,
                          Predicate<Calendar> hasShift, Consumer<Calendar> select) {
        grid.removeAllViews();
        Context context = grid.getContext();
        Calendar first = (Calendar) month.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int offset = weekday(first);
        int days = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        for (int cell = 0; cell < 7 + offset + days; cell++) {
            TextView label = WorkerDialogs.detailText(context, "", 12, false);
            label.setGravity(Gravity.CENTER);
            View item = label;
            if (cell < 7) {
                label.setText(DAYS[cell].substring(0, 1));
            } else if (cell >= 7 + offset) {
                Calendar day = offset(first, cell - 7 - offset);
                boolean enabled = minimum == null || !day.before(minimum);
                boolean on = selected != null && iso(day).equals(iso(selected));
                boolean marked = hasShift.test(day);
                LinearLayout tile = new LinearLayout(context);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setBackgroundResource(on ? R.drawable.day_chip_on : R.drawable.day_chip_off);
                tile.setContentDescription(label(day, "EEEE, MMMM d, yyyy") + (marked ? ", scheduled" : ""));
                tile.setSelected(on);
                tile.setEnabled(enabled);
                if (enabled) {
                    tile.setOnClickListener(v -> select.accept(day));
                    tile.setFocusable(true);
                } else {
                    tile.setAlpha(0.45f);
                }
                label.setText(String.valueOf(day.get(Calendar.DAY_OF_MONTH)));
                if (on) label.setTextColor(ContextCompat.getColor(context, R.color.white));
                tile.addView(label, new LinearLayout.LayoutParams(-1, -2));
                View dot = marker(context, on, marked);
                tile.addView(dot, new LinearLayout.LayoutParams(dp(context, 4), dp(context, 4)));
                item = tile;
            }
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(GridLayout.spec(cell / 7),
                    GridLayout.spec(cell % 7, 1f));
            params.width = 0;
            params.height = dp(context, cell < 7 ? 28 : 38);
            int margin = dp(context, 2);
            params.setMargins(margin, margin, margin, margin);
            grid.addView(item, params);
        }
    }

    static View marker(Context context, boolean selected, boolean marked) {
        View dot = new View(context);
        dot.setBackgroundResource(R.drawable.dot_online);
        dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context,
                selected ? R.color.white : R.color.primary)));
        dot.setVisibility(marked ? View.VISIBLE : View.INVISIBLE);
        return dot;
    }

    static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
