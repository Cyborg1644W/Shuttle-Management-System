package com.shuttle.mobile.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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

/**
 * Styled dialogs for Worker Management: Shift Log, Reassign, and Add/Edit Worker.
 * Self-contained: you pass the data in and get a callback out.
 * Also has helpers for sorting workers and showing the ON BREAK outline.
 */
public final class WorkerDialogs {

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

    // 1) Shift log
    /**
     * @param stats {value1, label1, value2, label2}  - the two big numbers
     * @param rows  {label1, value1, label2, value2, label3, value3} - the three detail rows
     * @param row2IconRes icon for the 2nd row (e.g. R.drawable.ic_pin or R.drawable.ic_vehicle)
     */
    public static void showShiftLog(Context c, String name, String role, String status,
                                    int row2IconRes, String[] stats, String[] rows) {
        View v = inflate(c, R.layout.dialog_shift_log);
        AlertDialog d = build(c, v);

        ((TextView) v.findViewById(R.id.tvSlAvatar)).setText(initials(name));
        ((TextView) v.findViewById(R.id.tvSlName)).setText(name);
        ((TextView) v.findViewById(R.id.tvSlRole)).setText(role);
        styleStatus(c, (TextView) v.findViewById(R.id.tvSlStatus), status);

        ((TextView) v.findViewById(R.id.tvSlStat1)).setText(stats[0]);
        ((TextView) v.findViewById(R.id.tvSlStat1Label)).setText(stats[1]);
        ((TextView) v.findViewById(R.id.tvSlStat2)).setText(stats[2]);
        ((TextView) v.findViewById(R.id.tvSlStat2Label)).setText(stats[3]);

        ((TextView) v.findViewById(R.id.tvSlRow1Label)).setText(rows[0]);
        ((TextView) v.findViewById(R.id.tvSlRow1)).setText(rows[1]);
        ((TextView) v.findViewById(R.id.tvSlRow2Label)).setText(rows[2]);
        ((TextView) v.findViewById(R.id.tvSlRow2)).setText(rows[3]);
        ((TextView) v.findViewById(R.id.tvSlRow3Label)).setText(rows[4]);
        ((TextView) v.findViewById(R.id.tvSlRow3)).setText(rows[5]);
        ((ImageView) v.findViewById(R.id.ivSlRow2Icon)).setImageResource(row2IconRes);

        v.findViewById(R.id.btnSlClose).setOnClickListener(x -> d.dismiss());
        v.findViewById(R.id.ivSlClose).setOnClickListener(x -> d.dismiss());
        d.show();
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

    private static void styleStatus(Context c, TextView badge, String status) {
        int bg;
        int dot;
        int text;
        if (isBreak(status)) {
            bg = R.color.badge_break_bg;
            dot = R.color.break_dot;
            text = R.color.break_text;
        } else if (statusRank(status) == 0) {
            bg = R.color.badge_on_bg;
            dot = R.color.brand_600;
            text = R.color.brand_600;
        } else {
            bg = R.color.badge_off_bg;
            dot = R.color.text_secondary;
            text = R.color.text_secondary;
        }

        SpannableString s = new SpannableString("● " + status.toUpperCase(Locale.ROOT));
        s.setSpan(new ForegroundColorSpan(ContextCompat.getColor(c, dot)),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(ContextCompat.getColor(c, text)),
                1, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        badge.setText(s);
        badge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(c, bg)));
    }

    static String initials(String name) {
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && i < 2; i++) {
            if (!parts[i].isEmpty()) sb.append(parts[i].substring(0, 1).toUpperCase(Locale.ROOT));
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }
}