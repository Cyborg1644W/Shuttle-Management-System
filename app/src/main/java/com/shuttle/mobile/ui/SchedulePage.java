package com.shuttle.mobile.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.shuttle.mobile.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Fills the Schedule tab (page_schedule.xml): a week strip, and the staff scheduled
 * for the selected day grouped into Morning / Afternoon / Night shifts.
 *
 * Uses SAMPLE data until the backend is connected - replace DATA with real schedules later.
 *
 * Usage:  SchedulePage.bind(this, findViewById(R.id.pageSchedule));
 */
public final class SchedulePage {

    private static final String[] DAY_NAMES = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final String[] SHIFT_TITLES = {"Morning", "Afternoon", "Night"};
    private static final String[] SHIFT_TIMES = {"06:00 - 14:00", "14:00 - 22:00", "22:00 - 06:00"};

    private static class Entry {
        final String name;
        final String role;
        final String place;
        final int shift;
        final String days;

        Entry(String name, String role, String place, int shift, String days) {
            this.name = name;
            this.role = role;
            this.place = place;
            this.shift = shift;
            this.days = days;
        }
    }

    private static final Entry[] DATA = {
            new Entry("Yuri Esplana", "Terminal Operator", "PITX Central Bay", 0, "1111100"),
            new Entry("Marco Santos", "Driver", "Terminal 1 Bay C", 0, "1010110"),
            new Entry("Lea Ramos", "Terminal Operator", "Ayala Bay B", 1, "1111110"),
            new Entry("Paolo Reyes", "Driver", "Terminal 2 Bay A", 1, "0110011"),
            new Entry("Carla Mendoza", "Terminal Operator", "One Ayala Hub", 2, "1000111"),
            new Entry("Jun Villanueva", "Driver", "Terminal 1 Bay C", 2, "0111101"),
    };

    private final Context context;
    private final LinearLayout layoutDays;
    private final LinearLayout layoutSchedule;
    private final TextView tvDate;
    private final TextView tvSummary;
    private final TextView tvEmpty;
    private final View[] chips = new View[7];
    private final Calendar[] dates = new Calendar[7];
    private int selected;

    private SchedulePage(Context context, View root) {
        this.context = context;
        layoutDays = root.findViewById(R.id.layoutDays);
        layoutSchedule = root.findViewById(R.id.layoutSchedule);
        tvDate = root.findViewById(R.id.tvSchDate);
        tvSummary = root.findViewById(R.id.tvSchSummary);
        tvEmpty = root.findViewById(R.id.tvSchEmpty);
    }

    public static void bind(Context context, View pageRoot) {
        new SchedulePage(context, pageRoot).init();
    }

    private void init() {
        // Monday of the current week
        Calendar monday = Calendar.getInstance();
        int todayIndex = (monday.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Mon=0 ... Sun=6
        monday.add(Calendar.DATE, -todayIndex);

        layoutDays.removeAllViews();
        for (int i = 0; i < 7; i++) {
            Calendar day = (Calendar) monday.clone();
            day.add(Calendar.DATE, i);
            dates[i] = day;

            View chip = LayoutInflater.from(context).inflate(R.layout.item_schedule_day, layoutDays, false);
            ((TextView) chip.findViewById(R.id.tvDayName)).setText(DAY_NAMES[i]);
            ((TextView) chip.findViewById(R.id.tvDayNum)).setText(
                    String.valueOf(day.get(Calendar.DAY_OF_MONTH)));
            final int index = i;
            chip.setOnClickListener(v -> select(index));
            chips[i] = chip;
            layoutDays.addView(chip);
        }
        select(todayIndex);
    }

    private void select(int index) {
        selected = index;

        for (int i = 0; i < 7; i++) {
            boolean on = i == selected;
            chips[i].setBackgroundResource(on ? R.drawable.day_chip_on : R.drawable.day_chip_off);
            ((TextView) chips[i].findViewById(R.id.tvDayName)).setTextColor(ContextCompat.getColor(
                    context, on ? R.color.white : R.color.text_secondary));
            ((TextView) chips[i].findViewById(R.id.tvDayNum)).setTextColor(ContextCompat.getColor(
                    context, on ? R.color.white : R.color.text_primary));
        }

        SimpleDateFormat fmt = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault());
        tvDate.setText(fmt.format(new Date(dates[selected].getTimeInMillis())));
        render();
    }

    private void render() {
        layoutSchedule.removeAllViews();
        int total = 0;
        int shifts = 0;

        for (int s = 0; s < 3; s++) {
            int count = 0;
            for (Entry e : DATA) {
                if (e.shift == s && e.days.charAt(selected) == '1') count++;
            }
            if (count == 0) continue;
            shifts++;
            total += count;

            View header = LayoutInflater.from(context)
                    .inflate(R.layout.item_schedule_header, layoutSchedule, false);
            ((TextView) header.findViewById(R.id.tvShiftTitle)).setText(SHIFT_TITLES[s]);
            ((TextView) header.findViewById(R.id.tvShiftTime)).setText(SHIFT_TIMES[s]);
            ((TextView) header.findViewById(R.id.tvShiftCount)).setText(count + " on duty");
            layoutSchedule.addView(header);

            for (Entry e : DATA) {
                if (e.shift != s || e.days.charAt(selected) != '1') continue;
                View row = LayoutInflater.from(context)
                        .inflate(R.layout.item_schedule_row, layoutSchedule, false);
                ((TextView) row.findViewById(R.id.tvSchAvatar)).setText(WorkerDialogs.initials(e.name));
                ((TextView) row.findViewById(R.id.tvSchName)).setText(e.name);
                ((TextView) row.findViewById(R.id.tvSchSub)).setText(e.role + " • " + e.place);
                ((TextView) row.findViewById(R.id.tvSchTime)).setText(SHIFT_TIMES[s].replace(" ", ""));
                layoutSchedule.addView(row);
            }
        }

        tvSummary.setText(total + (total == 1 ? " person" : " people") + " scheduled • "
                + shifts + (shifts == 1 ? " shift" : " shifts"));
        tvEmpty.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
    }
}