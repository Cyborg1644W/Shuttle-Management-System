package com.shuttle.mobile.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shuttle.mobile.R;

import java.util.ArrayList;
import java.util.Collections;

public class OperatorActivity extends AppCompatActivity {

    // One worker. TEMPORARY sample data: replace with real workers from the backend later.
    private static class WorkerItem {
        String name;
        String empId;
        String status;
        boolean operator;
        String value1;
        String value2;
        String phone;

        WorkerItem(String name, String empId, String status, boolean operator,
                   String value1, String value2, String phone) {
            this.name = name;
            this.empId = empId;
            this.status = status;
            this.operator = operator;
            this.value1 = value1;
            this.value2 = value2;
            this.phone = phone;
        }

        String role() {
            return operator ? "Terminal Operator" : "Driver";
        }

        String label1() {
            return operator ? "Station" : "Vehicle";
        }

        String label2() {
            return operator ? "Shift Time" : "Terminal";
        }

        // Online workers get the green ring and dot
        boolean isOnline() {
            return status.equals("ON SHIFT") || status.equals("ACTIVE");
        }
    }

    private final ArrayList<WorkerItem> workers = new ArrayList<>();
    private String currentFilter = "ALL";

    private LinearLayout listContainer;
    private EditText etSearch;
    private TextView chipAll;
    private TextView chipDrivers;
    private TextView chipOperators;
    private TextView tvSubtitle;
    private TextView tvStatDrivers;
    private TextView tvStatDriversTotal;
    private TextView tvStatOperators;
    private TextView tvStatStandby;
    private TextView tvHeaderAvatar;
    private View viewDot;

    // Tabs: Workers (default) and Schedule
    private View pageWorkers;
    private View pageSchedule;
    private View fabAdd;
    private TextView tvPageLabel;
    private ImageView ivNavWorkers;
    private ImageView ivNavSchedule;
    private TextView tvNavWorkers;
    private TextView tvNavSchedule;
    private boolean showingSchedule = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_operator);

        listContainer = findViewById(R.id.listContainer);
        etSearch = findViewById(R.id.etSearch);
        chipAll = findViewById(R.id.chipAll);
        chipDrivers = findViewById(R.id.chipDrivers);
        chipOperators = findViewById(R.id.chipOperators);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        tvStatDrivers = findViewById(R.id.tvStatDrivers);
        tvStatDriversTotal = findViewById(R.id.tvStatDriversTotal);
        tvStatOperators = findViewById(R.id.tvStatOperators);
        tvStatStandby = findViewById(R.id.tvStatStandby);
        tvHeaderAvatar = findViewById(R.id.tvHeaderAvatar);
        viewDot = findViewById(R.id.viewDot);
        pageWorkers = findViewById(R.id.pageWorkers);
        pageSchedule = findViewById(R.id.pageSchedule);
        fabAdd = findViewById(R.id.fabAdd);
        tvPageLabel = findViewById(R.id.tvPageLabel);
        ivNavWorkers = findViewById(R.id.ivNavWorkers);
        ivNavSchedule = findViewById(R.id.ivNavSchedule);
        tvNavWorkers = findViewById(R.id.tvNavWorkers);
        tvNavSchedule = findViewById(R.id.tvNavSchedule);

        loadSampleWorkers();

        chipAll.setOnClickListener(v -> selectFilter("ALL"));
        chipDrivers.setOnClickListener(v -> selectFilter("DRIVER"));
        chipOperators.setOnClickListener(v -> selectFilter("OPERATOR"));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderList();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        findViewById(R.id.flBell).setOnClickListener(v -> showNotifications());
        tvHeaderAvatar.setOnClickListener(v -> showAccountMenu());
        findViewById(R.id.fabAdd).setOnClickListener(v -> showWorkerForm(null));

        findViewById(R.id.navDashboard).setOnClickListener(v -> comingSoon("Dashboard"));
        findViewById(R.id.navFleet).setOnClickListener(v -> comingSoon("Fleet"));
        findViewById(R.id.navWorkers).setOnClickListener(v -> showTab(false));
        findViewById(R.id.navSchedule).setOnClickListener(v -> showTab(true));

        SchedulePage.bind(this, pageSchedule);

        updateSummary();
        renderList();
    }

    @Override
    public void onBackPressed() {
        if (showingSchedule) {
            showTab(false);
        } else {
            super.onBackPressed();
        }
    }

    // ---------- Tabs ----------

    private void showTab(boolean schedule) {
        showingSchedule = schedule;
        pageWorkers.setVisibility(schedule ? View.GONE : View.VISIBLE);
        pageSchedule.setVisibility(schedule ? View.VISIBLE : View.GONE);
        fabAdd.setVisibility(schedule ? View.GONE : View.VISIBLE);
        tvPageLabel.setText(schedule ? "Schedule" : "Workers");
        styleNav(ivNavWorkers, tvNavWorkers, !schedule);
        styleNav(ivNavSchedule, tvNavSchedule, schedule);
    }

    private void styleNav(ImageView icon, TextView label, boolean selected) {
        int color = ContextCompat.getColor(this, selected ? R.color.brand_600 : R.color.text_secondary);
        icon.setImageTintList(ColorStateList.valueOf(color));
        label.setTextColor(color);
        if (selected) {
            icon.setBackgroundResource(R.drawable.chip_bg);
        } else {
            icon.setBackground(null);
        }
    }

    private void loadSampleWorkers() {
        workers.add(new WorkerItem("Reindel Andrada", "EMP-0842", "ON SHIFT", false,
                "Unit SH-1024", "Terminal 3 Bay A", "+63 917 552 1092"));
        workers.add(new WorkerItem("Roxane Tabo", "EMP-0915", "ON SHIFT", false,
                "Unit SH-2088", "One Ayala Hub", "+63 920 883 4410"));
        workers.add(new WorkerItem("Yuri Esplana", "EMP-0620", "ACTIVE", true,
                "PITX Central Bay", "06:00 - 14:00", "+63 918 334 7890"));
        workers.add(new WorkerItem("Marco Santos", "EMP-0431", "OFF DUTY", false,
                "Unassigned", "Terminal 1 Bay C", "+63 921 700 1188"));
        workers.add(new WorkerItem("Lea Ramos", "EMP-0377", "ON BREAK", true,
                "Ayala Bay B", "14:00 - 22:00", "+63 915 220 9034"));
    }

    // ---------- Summary (subtitle, stat cards, chip count) ----------

    private void updateSummary() {
        int total = workers.size();
        int onDuty = 0;
        int drivers = 0;
        int driversActive = 0;
        int operators = 0;
        int standby = 0;

        for (WorkerItem w : workers) {
            if (w.isOnline()) {
                onDuty++;
            }
            if (w.operator) {
                operators++;
            } else {
                drivers++;
                if (w.isOnline()) {
                    driversActive++;
                }
            }
            if (w.status.equals("OFF DUTY") || w.status.equals("ON BREAK")) {
                standby++;
            }
        }

        // Green dot, then "34 Registered Staff • 28 On Duty"
        SpannableStringBuilder sub = new SpannableStringBuilder();
        sub.append("● ");
        sub.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.brand_600)),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sub.setSpan(new RelativeSizeSpan(0.7f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sub.append(total + " Registered Staff • " + onDuty + " On Duty");
        tvSubtitle.setText(sub);

        tvStatDrivers.setText(String.valueOf(driversActive));
        tvStatDriversTotal.setText("/" + drivers);
        tvStatOperators.setText(String.valueOf(operators));
        tvStatStandby.setText(String.valueOf(standby));
        chipAll.setText("All Roles  " + total);
    }

    // ---------- Filter and list ----------

    private void selectFilter(String filter) {
        currentFilter = filter;
        styleChip(chipAll, filter.equals("ALL"));
        styleChip(chipDrivers, filter.equals("DRIVER"));
        styleChip(chipOperators, filter.equals("OPERATOR"));
        renderList();
    }

    private void styleChip(TextView chip, boolean selected) {
        if (selected) {
            chip.setBackgroundResource(R.drawable.tab_selected);
            chip.setTextColor(ContextCompat.getColor(this, R.color.white));
        } else {
            chip.setBackgroundResource(R.drawable.tab_unselected);
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    private boolean matches(WorkerItem w) {
        if (currentFilter.equals("DRIVER") && w.operator) {
            return false;
        }
        if (currentFilter.equals("OPERATOR") && !w.operator) {
            return false;
        }

        String query = etSearch.getText().toString().trim().toLowerCase();
        if (query.isEmpty()) {
            return true;
        }
        return w.name.toLowerCase().contains(query) || w.empId.toLowerCase().contains(query);
    }

    private void renderList() {
        listContainer.removeAllViews();
        int shown = 0;

        // Active first, then on break, then off duty at the bottom (stable: same-status keeps its order)
        ArrayList<WorkerItem> sorted = new ArrayList<>(workers);
        Collections.sort(sorted, (a, b) ->
                Integer.compare(WorkerDialogs.statusRank(a.status), WorkerDialogs.statusRank(b.status)));

        for (final WorkerItem w : sorted) {
            if (!matches(w)) {
                continue;
            }
            shown++;

            View card = LayoutInflater.from(this).inflate(R.layout.item_worker_card, listContainer, false);

            TextView tvAvatar = card.findViewById(R.id.tvAvatar);
            tvAvatar.setText(initials(w.name));
            // Online: green ring and dot. On break: amber ring. Offline: plain circle.
            if (w.isOnline()) {
                tvAvatar.setBackgroundResource(R.drawable.avatar_ring_bg);
                card.findViewById(R.id.viewOnline).setVisibility(View.VISIBLE);
            } else if (WorkerDialogs.isBreak(w.status)) {
                tvAvatar.setBackgroundResource(R.drawable.avatar_ring_break);
                View dot = card.findViewById(R.id.viewOnline);
                dot.setBackgroundResource(R.drawable.dot_break);
                dot.setVisibility(View.VISIBLE);
            } else {
                tvAvatar.setBackgroundResource(R.drawable.icon_circle_bg);
                card.findViewById(R.id.viewOnline).setVisibility(View.GONE);
            }

            ((TextView) card.findViewById(R.id.tvName)).setText(w.name);
            ((TextView) card.findViewById(R.id.tvMeta)).setText(w.empId + " • " + w.role());
            styleBadge((TextView) card.findViewById(R.id.tvBadge), w.status);

            ((TextView) card.findViewById(R.id.tvLabel1)).setText(w.label1());
            ((TextView) card.findViewById(R.id.tvValue1)).setText(w.value1);
            ((TextView) card.findViewById(R.id.tvLabel2)).setText(w.label2());
            ((TextView) card.findViewById(R.id.tvValue2)).setText(w.value2);
            ((TextView) card.findViewById(R.id.tvPhone)).setText(w.phone);

            ImageView ivIcon1 = card.findViewById(R.id.ivIcon1);
            ImageView ivIcon2 = card.findViewById(R.id.ivIcon2);
            View btnAction = card.findViewById(R.id.btnAction);
            ImageView ivActionIcon = card.findViewById(R.id.ivActionIcon);
            TextView tvAction = card.findViewById(R.id.tvAction);

            if (w.operator) {
                ivIcon1.setImageResource(R.drawable.ic_pin);
                ivIcon2.setImageResource(R.drawable.ic_clock);
                btnAction.setBackgroundResource(R.drawable.btn_intercom_bg);
                ivActionIcon.setImageResource(R.drawable.ic_headset);
                ivActionIcon.setColorFilter(ContextCompat.getColor(this, R.color.intercom_text));
                tvAction.setText("Intercom");
                tvAction.setTextColor(ContextCompat.getColor(this, R.color.intercom_text));
            } else {
                ivIcon1.setImageResource(R.drawable.ic_vehicle);
                ivIcon2.setImageResource(R.drawable.ic_pin);
                btnAction.setBackgroundResource(R.drawable.btn_radio_bg);
                ivActionIcon.setImageResource(R.drawable.ic_radio);
                ivActionIcon.setColorFilter(ContextCompat.getColor(this, R.color.radio_text));
                tvAction.setText("Radio");
                tvAction.setTextColor(ContextCompat.getColor(this, R.color.radio_text));
            }

            card.setOnClickListener(v -> openSheet(w));

            card.findViewById(R.id.btnCall).setOnClickListener(v -> {
                String number = w.phone.replace(" ", "");
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number)));
            });

            btnAction.setOnClickListener(v -> showCallDialog(w));

            listContainer.addView(card);
        }

        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("No staff found.");
            empty.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            empty.setTextSize(14);
            empty.setPadding(0, 32, 0, 32);
            empty.setGravity(Gravity.CENTER);
            listContainer.addView(empty);
        }
    }

    // ---------- Bottom sheet ----------

    private void openSheet(final WorkerItem w) {
        final BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_worker, null);

        TextView tvSheetAvatar = sheet.findViewById(R.id.tvSheetAvatar);
        tvSheetAvatar.setText(initials(w.name));
        tvSheetAvatar.setBackgroundResource(w.isOnline() ? R.drawable.avatar_ring_bg
                : WorkerDialogs.isBreak(w.status) ? R.drawable.avatar_ring_break
                  : R.drawable.icon_circle_bg);

        ((TextView) sheet.findViewById(R.id.tvSheetName)).setText(w.name);
        ((TextView) sheet.findViewById(R.id.tvSheetMeta)).setText(w.empId + " • " + w.role());
        styleBadge((TextView) sheet.findViewById(R.id.tvSheetBadge), w.status);

        sheet.findViewById(R.id.btnSheetClose).setOnClickListener(v -> dialog.dismiss());
        sheet.findViewById(R.id.btnDismiss).setOnClickListener(v -> dialog.dismiss());

        sheet.findViewById(R.id.rowEdit).setOnClickListener(v -> {
            dialog.dismiss();
            showWorkerForm(w);
        });

        sheet.findViewById(R.id.rowLog).setOnClickListener(v -> {
            dialog.dismiss();
            showShiftLog(w);
        });

        sheet.findViewById(R.id.rowReassign).setOnClickListener(v -> {
            dialog.dismiss();
            showReassign(w);
        });

        sheet.findViewById(R.id.rowDeactivate).setOnClickListener(v -> {
            dialog.dismiss();
            confirmRemove(w);
        });

        dialog.setContentView(sheet);

        // Lets the rounded top corners of the sheet show
        ((View) sheet.getParent()).setBackgroundColor(Color.TRANSPARENT);

        dialog.show();
    }

    // ---------- Sheet actions ----------

    private void showShiftLog(WorkerItem w) {
        if (w.operator) {
            WorkerDialogs.showShiftLog(this, w.name, w.role(), w.status, R.drawable.ic_pin,
                    new String[]{"14", "Dispatches today", "8", "Terminal checks"},
                    new String[]{"Shift", w.value2, "Station", w.value1, "Last activity", "2 min ago"});
        } else {
            WorkerDialogs.showShiftLog(this, w.name, w.role(), w.status, R.drawable.ic_vehicle,
                    new String[]{"84 km", "Distance", "31 km/h", "Avg. speed"},
                    new String[]{"Shift start", "06:02",
                            "Vehicle", w.value1 + " • " + w.value2,
                            "Stops • last GPS ping", "12 stops • 1 min ago"});
        }
    }

    private void showReassign(final WorkerItem w) {
        final String[] options;
        String title;
        String noun;
        int icon;

        if (w.operator) {
            title = "Reassign station";
            noun = "station";
            icon = R.drawable.ic_pin;
            options = new String[]{"PITX Central Bay", "Ayala Bay B", "One Ayala Hub", "Unassigned"};
        } else {
            title = "Reassign vehicle";
            noun = "vehicle";
            icon = R.drawable.ic_vehicle;
            options = new String[]{"Unit SH-1024", "Unit SH-2088", "Unit SH-3150", "Unassigned"};
        }

        WorkerDialogs.showReassign(this, title, "Choose a new " + noun + " for " + w.name,
                icon, options, w.value1, picked -> {
                    w.value1 = picked;
                    renderList();
                    Toast.makeText(this, w.name + " reassigned to " + picked, Toast.LENGTH_SHORT).show();
                });
    }

    private void confirmRemove(final WorkerItem w) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Deactivate / Remove")
                .setMessage("Remove " + w.name + " (" + w.empId + ") from the staff list?")
                .setPositiveButton("Remove", (dialog, which) -> {
                    workers.remove(w);
                    updateSummary();
                    renderList();
                    Toast.makeText(this, w.name + " removed", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCallDialog(WorkerItem w) {
        String type = w.operator ? "Intercom" : "Radio";
        new MaterialAlertDialogBuilder(this)
                .setTitle(type + " • " + w.name)
                .setMessage("Connecting on channel 3...\n\n(Demo only: voice is not connected yet.)")
                .setPositiveButton("End call", null)
                .show();
    }

    // ---------- Add / Edit worker ----------

    private void showWorkerForm(final WorkerItem existing) {
        String digits = "";
        if (existing != null) {
            digits = existing.phone.replaceAll("[^0-9]", "");
            if (digits.startsWith("63")) {
                digits = digits.substring(2);
            }
        }

        WorkerDialogs.showWorkerForm(this, existing == null,
                existing == null ? "" : existing.name,
                existing == null ? "" : existing.empId,
                digits,
                (name, emp, phoneDigits, op) -> {
                    for (WorkerItem other : workers) {
                        if (other != existing && other.empId.equalsIgnoreCase(emp)) {
                            return "This ID is already used";
                        }
                    }

                    String phone = "+63 " + phoneDigits.substring(0, 3) + " "
                            + phoneDigits.substring(3, 6) + " " + phoneDigits.substring(6);

                    if (existing == null) {
                        workers.add(new WorkerItem(name, emp, "OFF DUTY", op,
                                "Unassigned", op ? "Not set" : "Unassigned", phone));
                        Toast.makeText(this, name + " added", Toast.LENGTH_SHORT).show();
                    } else {
                        existing.name = name;
                        existing.empId = emp;
                        existing.phone = phone;
                        Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show();
                    }

                    updateSummary();
                    renderList();
                    return null;
                });
    }

    // ---------- Bell and account menu ----------

    private void showNotifications() {
        viewDot.setVisibility(View.GONE);

        String[] items = {
                "Unit SH-1024 reached Terminal 3 Bay A",
                "Marco Santos clocked out",
                "Shift change at 14:00",
                "Lea Ramos started a break"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("Notifications")
                .setItems(items, null)
                .setPositiveButton("Close", null)
                .show();
    }

    private void showAccountMenu() {
        PopupMenu menu = new PopupMenu(this, tvHeaderAvatar);
        menu.getMenu().add("My Profile");
        menu.getMenu().add("Sign out");

        menu.setOnMenuItemClickListener(item -> {
            if (item.getTitle().toString().equals("Sign out")) {
                signOut();
            } else {
                comingSoon("Profile");
            }
            return true;
        });

        menu.show();
    }

    private void signOut() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    // ---------- Helpers ----------

    private void styleBadge(TextView badge, String status) {
        int bg;
        int dot;
        int text;

        if (status.equals("OFF DUTY")) {
            bg = R.color.badge_off_bg;
            dot = R.color.text_secondary;
            text = R.color.text_secondary;
        } else if (status.equals("ON BREAK")) {
            bg = R.color.badge_break_bg;
            dot = R.color.break_dot;
            text = R.color.break_text;
        } else {
            // ON SHIFT and ACTIVE
            bg = R.color.badge_on_bg;
            dot = R.color.brand_600;
            text = R.color.brand_600;
        }

        SpannableString s = new SpannableString("● " + status);
        s.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, dot)),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, text)),
                1, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        badge.setText(s);
        badge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, bg)));
    }

    private String initials(String name) {
        String[] parts = name.trim().split("\\s+");
        String result = "";
        for (int i = 0; i < parts.length && i < 2; i++) {
            result += parts[i].substring(0, 1).toUpperCase();
        }
        return result;
    }

    private void comingSoon(String what) {
        Toast.makeText(this, what + " coming soon", Toast.LENGTH_SHORT).show();
    }
}