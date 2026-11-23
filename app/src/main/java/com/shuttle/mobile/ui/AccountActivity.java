package com.shuttle.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shuttle.common.Role;
import com.shuttle.mobile.R;
import com.shuttle.mobile.client.ApiClient;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared profile presentation and notification empty state for both roles. */
public final class AccountActivity extends AppCompatActivity {
    private EditText firstName, lastName, email, phone;
    private TextView error;
    private boolean editing;
    private boolean saving;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Session.isSignedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_account);
        findViewById(R.id.btnAccountBack).setOnClickListener(v -> onBackPressed());
        if (getIntent().getBooleanExtra("notifications", false)) {
            ((TextView) findViewById(R.id.tvAccountTitle)).setText(R.string.notifications_title);
            findViewById(R.id.layoutAccountProfile).setVisibility(View.GONE);
            findViewById(R.id.layoutAccountNotifications).setVisibility(View.VISIBLE);
            findViewById(R.id.btnNotificationsAll).setOnClickListener(v -> filterNotifications(false));
            findViewById(R.id.btnNotificationsUnread).setOnClickListener(v -> filterNotifications(true));
            filterNotifications(false);
            return;
        }
        firstName = findViewById(R.id.etAccountFirstName);
        lastName = findViewById(R.id.etAccountLastName);
        email = findViewById(R.id.etAccountEmail);
        phone = findViewById(R.id.etAccountPhone);
        error = findViewById(R.id.tvAccountError);
        findViewById(R.id.btnAccountEdit).setOnClickListener(v -> setEditing(true));
        findViewById(R.id.btnAccountCancel).setOnClickListener(v -> { fill(); setEditing(false); });
        findViewById(R.id.btnAccountSave).setOnClickListener(v -> save());
        findViewById(R.id.tvAccountSaved).setOnClickListener(v -> v.setVisibility(View.GONE));
        fill();
        setEditing(false);
    }

    private String roleName() { return Session.getRole() == Role.OPERATOR ? "Operator" : "Driver"; }

    private void fill() {
        String name = Session.getName() == null ? "" : Session.getName().trim();
        ((TextView) findViewById(R.id.tvAccountName)).setText(name);
        ((TextView) findViewById(R.id.tvAccountRole)).setText("Fleet " + roleName());
        ((TextView) findViewById(R.id.tvAccountFormRole)).setText(roleName());
        // Keep every part of a stored name; an unchanged edit round-trips the original full name.
        int split = name.lastIndexOf(' ');
        firstName.setText(split < 0 ? name : name.substring(0, split));
        lastName.setText(split < 0 ? "" : name.substring(split + 1));
        email.setText(Session.getEmail());
        phone.setText(Session.getPhone());
        LinearLayout rows = findViewById(R.id.layoutProfileRows);
        rows.removeAllViews();
        informationRow(rows, "Full Name", name);
        informationRow(rows, "Email Address", Session.getEmail());
        informationRow(rows, "Mobile Number", Session.getPhone());
        informationRow(rows, "Account ID", Session.getEmployeeId());
        ((TextView) findViewById(R.id.tvAccountFormId)).setText(Session.getEmployeeId());
        informationRow(rows, "Account Role", roleName());
    }

    private void informationRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(13);
        caption.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        row.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        TextView text = new TextView(this);
        text.setText(value == null || value.isEmpty() ? "Not provided" : value);
        text.setTextSize(14);
        text.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        text.setGravity(Gravity.END);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1.5f));
        parent.addView(row);
    }

    private void setEditing(boolean value) {
        editing = value;
        ((TextView) findViewById(R.id.tvAccountTitle)).setText(value ? "Edit Profile" : "Profile Details");
        findViewById(R.id.layoutProfileDetails).setVisibility(value ? View.GONE : View.VISIBLE);
        findViewById(R.id.layoutProfileIdentity).setVisibility(value ? View.GONE : View.VISIBLE);
        findViewById(R.id.layoutProfileForm).setVisibility(value ? View.VISIBLE : View.GONE);
        error.setVisibility(View.GONE);
        if (value) {
            findViewById(R.id.tvAccountSaved).setVisibility(View.GONE);
            firstName.requestFocus();
        }
    }

    private void save() {
        if (saving) return;
        String n = (firstName.getText().toString().trim() + " " + lastName.getText().toString().trim()).trim();
        String e = email.getText().toString().trim();
        String p = phone.getText().toString().trim();
        if (n.length() < 2) { showError("Please enter your full name"); return; }
        if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) { showError("Enter a valid email address"); return; }
        JSONObject body = new JSONObject();
        try { body.put("fullName", n).put("email", e).put("phone", p); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        String token = Session.getToken();
        String id = Session.getEmployeeId();
        Role role = Session.getRole();
        saving = true;
        findViewById(R.id.btnAccountSave).setEnabled(false);
        findViewById(R.id.btnAccountCancel).setEnabled(false);
        error.setVisibility(View.GONE);
        FleetApi.put("/workers/" + FleetApi.encode(id), body, new FleetApi.Callback() {
            private boolean active() {
                return !isFinishing() && !isDestroyed() && java.util.Objects.equals(token, Session.getToken());
            }
            private void complete() {
                saving = false;
                findViewById(R.id.btnAccountSave).setEnabled(true);
                findViewById(R.id.btnAccountCancel).setEnabled(true);
            }
            @Override public void onSuccess(JSONObject result) {
                if (!active()) return;
                complete();
                Session.start(token, id, result.optString("fullName", n),
                        result.optString("email", e), result.optString("phone", p), role);
                fill();
                setEditing(false);
                findViewById(R.id.tvAccountSaved).setVisibility(View.VISIBLE);
                android.view.inputmethod.InputMethodManager keyboard =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(firstName.getWindowToken(), 0);
            }
            @Override public void onError(int status, String message) {
                if (!active()) return;
                complete();
                showError(message);
            }
        });
    }

    private void filterNotifications(boolean unread) {
        for (int id : new int[] {R.id.btnNotificationsAll, R.id.btnNotificationsUnread}) {
            boolean selected = (id == R.id.btnNotificationsUnread) == unread;
            TextView chip = findViewById(id);
            chip.setBackgroundResource(selected ? R.drawable.tab_selected : R.drawable.notification_filter_outline);
            chip.setTextColor(ContextCompat.getColor(this, selected ? R.color.white : R.color.primary));
        }
        // No notification endpoint exists yet. Never present demonstration events as user data.
        ((TextView) findViewById(R.id.tvNotificationsEmpty)).setText(
                unread ? "No unread notifications" : getString(R.string.no_notifications));
        ((TextView) findViewById(R.id.tvNotificationsDescription)).setText(
                unread ? "New notifications will appear here." : getString(R.string.no_notifications_detail));
    }

    static void confirmSignOut(AppCompatActivity activity, Runnable signOut) {
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_sign_out, null);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(content)
                .create();
        content.findViewById(R.id.btnSignOutCancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.btnSignOutConfirm).setOnClickListener(v -> {
            dialog.dismiss();
            signOut.run();
        });
        dialog.show();
    }

    private void showError(String message) { error.setText(message); error.setVisibility(View.VISIBLE); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onBackPressed() {
        if (saving) return;
        if (editing) { fill(); setEditing(false); } else super.onBackPressed();
    }
}
