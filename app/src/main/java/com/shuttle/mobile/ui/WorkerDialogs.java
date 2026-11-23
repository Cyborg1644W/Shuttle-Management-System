package com.shuttle.mobile.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
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

    /** Return null when saved OK, or an error message to show in the dialog (it stays open). */
    public interface OnSubmit {
        String onSubmit(String name, String employeeId, String phoneDigits, boolean operator);
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

    // 3) Add / Edit worker
    /**
     * @param adding   true = "Add Worker" (shows the Driver/Operator toggle), false = "Edit Worker Profile"
     * @param phoneDigits the 10 digits after +63 (or empty)
     */
    public static void showWorkerForm(Context c, boolean adding, String name, String employeeId,
                                      String phoneDigits, OnSubmit listener) {
        View v = inflate(c, R.layout.dialog_edit_worker);
        AlertDialog d = build(c, v);

        EditText etName = v.findViewById(R.id.etEwName);
        EditText etEmp = v.findViewById(R.id.etEwEmp);
        EditText etPhone = v.findViewById(R.id.etEwPhone);
        TextView tvError = v.findViewById(R.id.tvEwError);
        TextView tvDriver = v.findViewById(R.id.tvEwDriver);
        TextView tvOperator = v.findViewById(R.id.tvEwOperator);
        final boolean[] operator = {false};

        ((TextView) v.findViewById(R.id.tvEwTitle)).setText(adding ? "Add Worker" : "Edit Worker Profile");
        ((TextView) v.findViewById(R.id.tvEwSubtitle)).setText(adding
                ? "Register a new driver or operator" : "Update this worker's details");
        ((ImageView) v.findViewById(R.id.ivEwIcon)).setImageResource(
                adding ? R.drawable.ic_plus : R.drawable.ic_edit);
        ((TextView) v.findViewById(R.id.btnEwSave)).setText(adding ? "Add" : "Save");

        v.findViewById(R.id.layoutEwRole).setVisibility(adding ? View.VISIBLE : View.GONE);
        tvDriver.setOnClickListener(x -> {
            operator[0] = false;
            styleRole(c, tvDriver, true);
            styleRole(c, tvOperator, false);
        });
        tvOperator.setOnClickListener(x -> {
            operator[0] = true;
            styleRole(c, tvDriver, false);
            styleRole(c, tvOperator, true);
        });

        etName.setText(name);
        etEmp.setText(employeeId);
        etPhone.setText(phoneDigits);

        v.findViewById(R.id.btnEwCancel).setOnClickListener(x -> d.dismiss());
        v.findViewById(R.id.btnEwSave).setOnClickListener(x -> {
            String n = etName.getText().toString().trim();
            String e = etEmp.getText().toString().trim().toUpperCase(Locale.ROOT);
            String digits = etPhone.getText().toString().replaceAll("[^0-9]", "");
            if (digits.startsWith("0")) digits = digits.substring(1);

            boolean badName = n.isEmpty();
            boolean badEmp = e.isEmpty();
            boolean badPhone = digits.length() != 10;

            setError(etName, badName);
            setError(etEmp, badEmp);
            setError(etPhone, badPhone);

            if (badName || badEmp || badPhone) {
                showError(tvError, badName ? "Please enter the worker's name."
                        : badEmp ? "Please enter an employee ID."
                          : "Enter a 10-digit mobile number (after +63).");
                return;
            }

            String problem = listener == null ? null : listener.onSubmit(n, e, digits, operator[0]);
            if (problem != null) {
                setError(etEmp, true); // e.g. "This ID is already used"
                showError(tvError, problem);
                return;
            }
            d.dismiss();
        });
        d.show();
    }

    private static void setError(EditText et, boolean bad) {
        et.setBackgroundResource(bad ? R.drawable.input_bg_error : R.drawable.input_bg);
    }

    private static void showError(TextView tv, String message) {
        tv.setText(message);
        tv.setVisibility(View.VISIBLE);
    }

    private static void styleRole(Context c, TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.tab_selected : R.drawable.tab_unselected);
        chip.setTextColor(ContextCompat.getColor(c, selected ? R.color.white : R.color.text_secondary));
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
