package com.shuttle.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
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
import org.json.JSONException;
import org.json.JSONObject;

import java.util.function.ToIntFunction;

/** Shared profile and assignment notifications. */
public final class AccountActivity extends AppCompatActivity {
    private EditText firstName, lastName, email, phone;
    private TextView firstNameMessage, lastNameMessage, emailMessage, error;
    private boolean editing;
    private boolean validationStarted;
    private boolean saving;
    private NotificationsPage notificationsPage;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Session.isSignedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        if (getIntent().getBooleanExtra("notifications", false)) {
            setContentView(R.layout.activity_notifications);
            findViewById(R.id.btnNotificationsBack).setOnClickListener(v -> finish());
            notificationsPage = new NotificationsPage(this);
            return;
        }
        setContentView(R.layout.activity_account);
        findViewById(R.id.btnAccountBack).setOnClickListener(v -> onBackPressed());
        firstName = findViewById(R.id.etAccountFirstName);
        lastName = findViewById(R.id.etAccountLastName);
        email = findViewById(R.id.etAccountEmail);
        phone = findViewById(R.id.etAccountPhone);
        firstNameMessage = findViewById(R.id.tvAccountFirstNameError);
        lastNameMessage = findViewById(R.id.tvAccountLastNameError);
        emailMessage = findViewById(R.id.tvAccountEmailError);
        error = findViewById(R.id.tvAccountError);
        watchProfileField(firstName, firstNameMessage, AccountActivity::firstNameError);
        watchProfileField(lastName, lastNameMessage, AccountActivity::lastNameError);
        watchProfileField(email, emailMessage, AccountActivity::emailError);
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
        validationStarted = false;
        showFieldError(firstName, firstNameMessage, 0);
        showFieldError(lastName, lastNameMessage, 0);
        showFieldError(email, emailMessage, 0);
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

    private void watchProfileField(EditText field, TextView message, ToIntFunction<String> validate) {
        FieldUi.onChange(field, () -> {
            if (validationStarted) showFieldError(field, message, validate.applyAsInt(field.getText().toString().trim()));
        });
    }

    private boolean showFieldError(EditText field, TextView message, int errorRes) {
        return FieldUi.showInlineError(field, message, errorRes == 0 ? null : getString(errorRes));
    }

    private static int firstNameError(String value) {
        return FieldUi.isBlank(value) ? R.string.profile_first_name_required : 0;
    }

    private static int lastNameError(String value) {
        return FieldUi.isBlank(value) ? R.string.profile_last_name_required : 0;
    }

    private static int emailError(String value) {
        if (FieldUi.isBlank(value)) return R.string.profile_email_required;
        return FieldUi.isGmailAddress(value) ? 0 : R.string.profile_email_invalid;
    }

    private void save() {
        if (saving) return;
        validationStarted = true;
        error.setVisibility(View.GONE);
        String first = firstName.getText().toString().trim();
        String last = lastName.getText().toString().trim();
        String e = email.getText().toString().trim();
        boolean firstValid = showFieldError(firstName, firstNameMessage, firstNameError(first));
        boolean lastValid = showFieldError(lastName, lastNameMessage, lastNameError(last));
        boolean emailValid = showFieldError(email, emailMessage, emailError(e));
        if (!firstValid || !lastValid || !emailValid) return;
        String n = first + " " + last;
        String p = phone.getText().toString().trim();
        JSONObject body = new JSONObject();
        try { body.put("fullName", n).put("email", e).put("phone", p); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        String token = Session.getToken();
        String id = Session.getEmployeeId();
        Role role = Session.getRole();
        saving = true;
        findViewById(R.id.btnAccountSave).setEnabled(false);
        findViewById(R.id.btnAccountCancel).setEnabled(false);
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

    @Override protected void onResume() {
        super.onResume();
        if (notificationsPage != null) notificationsPage.resume();
    }

    @Override protected void onPause() {
        if (notificationsPage != null) notificationsPage.pause();
        super.onPause();
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
