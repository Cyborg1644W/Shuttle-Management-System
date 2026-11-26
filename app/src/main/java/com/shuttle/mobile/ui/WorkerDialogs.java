package com.shuttle.mobile.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shuttle.mobile.R;

import java.util.Locale;

public final class WorkerDialogs {
    public static LinearLayout detailContent(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        return content;
    }

    public static TextView detailText(Context context, String text, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    public static LinearLayout detailSection(Context context, LinearLayout parent, String title) {
        int padding = Math.round(16 * context.getResources().getDisplayMetrics().density);
        if (title != null && !title.isEmpty()) {
            TextView heading = detailText(context, title, 13, true);
            heading.setPadding(0, padding, 0, padding / 2);
            parent.addView(heading);
        }
        LinearLayout card = detailContent(context);
        card.setPadding(padding, padding / 2, padding, padding / 2);
        card.setBackgroundResource(R.drawable.bg_ff_card);
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    public static TextView informationRow(Context context, LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int padding = Math.round(12 * context.getResources().getDisplayMetrics().density);
        row.setPadding(0, padding, 0, padding);
        TextView caption = detailText(context, label, 11, false);
        caption.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        row.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        TextView text = detailText(context, value == null || value.isEmpty() ? "Not provided" : value, 12, false);
        text.setGravity(android.view.Gravity.END);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1.5f));
        parent.addView(row);
        return text;
    }
    /** Presents an existing form/detail view as a native page without another Activity. */
    public static android.app.Dialog showDetailPage(Context context, String title, View content) {
        android.app.Dialog dialog = new android.app.Dialog(context, R.style.Theme_Shuttle);
        View page = LayoutInflater.from(context).inflate(R.layout.view_detail_page, null);
        ((TextView) page.findViewById(R.id.tvDetailTitle)).setText(title);
        ((android.widget.FrameLayout) page.findViewById(R.id.detailPageContent)).addView(content);
        page.findViewById(R.id.btnDetailBack).setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(page);
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
        return dialog;
    }

    private WorkerDialogs() { }

    public interface OnPick {
        void onPick(String value);
    }

    // Status helpers
    /** 0 = active / on shift, 1 = on break, 2 = off duty (goes to the bottom). */
    public static int statusRank(String status) {
        String s = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (s.equals("ACTIVE") || s.equals("ON SHIFT")) return 0;
        if (s.equals("ON BREAK")) return 1;
        return 2;
    }

    public static boolean isBreak(String status) {
        return statusRank(status) == 1;
    }

    // 2) Reassign (station or vehicle)
    public static void showReassign(Context c, String title, String subtitle, int iconRes,
                                    String[] options, String current, OnPick listener) {
        View v = inflate(c, R.layout.dialog_reassign_station);
        AlertDialog d = build(c, v);

        ((TextView) v.findViewById(R.id.tvRsTitle)).setText(title);
        ((TextView) v.findViewById(R.id.tvRsSubtitle)).setText(subtitle);
        LinearLayout list = v.findViewById(R.id.listOptions);

        for (String option : options) {
            View row = LayoutInflater.from(c).inflate(R.layout.item_station_option, list, false);
            TextView tv = row.findViewById(R.id.tvOptionName);
            tv.setText(option);
            ((ImageView) row.findViewById(R.id.ivOptionIcon)).setImageResource(iconRes);

            boolean selected = option.equalsIgnoreCase(current);
            row.findViewById(R.id.ivOptionCheck).setVisibility(selected ? View.VISIBLE : View.GONE);
            if (selected) {
                tv.setTextColor(ContextCompat.getColor(c, R.color.brand_600));
                tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
            }

            row.setOnClickListener(x -> {
                d.dismiss();
                if (listener != null) listener.onPick(option);
            });
            list.addView(row);
        }

        v.findViewById(R.id.btnRsCancel).setOnClickListener(x -> d.dismiss());
        d.show();
    }

    // Helpers

    private static View inflate(Context c, int layout) {
        return LayoutInflater.from(c).inflate(layout, null);
    }

    private static AlertDialog build(Context c, View content) {
        AlertDialog d = new MaterialAlertDialogBuilder(c).setView(content).create();
        if (d.getWindow() != null) {
            int margin = (int) (16 * c.getResources().getDisplayMetrics().density);
            d.getWindow().setBackgroundDrawable(
                    new InsetDrawable(new ColorDrawable(Color.TRANSPARENT), margin));
        }
        return d;
    }

}
