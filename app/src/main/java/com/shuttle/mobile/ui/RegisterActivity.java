package com.shuttle.mobile.ui;

import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.shuttle.common.Role;
import com.shuttle.mobile.R;
import com.shuttle.mobile.client.ApiClient;

public class RegisterActivity extends AppCompatActivity {

    private static final String EMPTY_MESSAGE = "Please fill in all the fields.";

    private View layoutChooseRole;
    private View layoutForm;
    private View cardDriver;
    private View cardOperator;
    private View layoutPhoneBox;
    private TextView tvGoLoginChoose;
    private TextView tvRoleLabel;
    private TextView tvChangeRole;
    private EditText etFullName;
    private EditText etPhone;
    private EditText etEmail;
    private EditText etPassword;
    private EditText etConfirmPassword;
    private ImageView ivPhoneCheck;
    private TextView tvError;
    private TextView tvGoLogin;
    private Button btnRegister;

    private Role selectedRole = null;
    private String firstMessage;
    private boolean registering = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ApiClient.initialize(this);
        setContentView(R.layout.activity_register);
        layoutChooseRole = findViewById(R.id.layoutChooseRole);
        layoutForm = findViewById(R.id.layoutForm);
        cardDriver = findViewById(R.id.cardDriver);
        cardOperator = findViewById(R.id.cardOperator);
        layoutPhoneBox = findViewById(R.id.layoutPhoneBox);
        tvGoLoginChoose = findViewById(R.id.tvGoLoginChoose);
        tvRoleLabel = findViewById(R.id.tvRoleLabel);
        tvChangeRole = findViewById(R.id.tvChangeRole);
        etFullName = findViewById(R.id.etFullName);
        etPhone = findViewById(R.id.etPhone);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        FieldUi.bindPasswordToggle(etPassword, findViewById(R.id.btnPasswordVisibility));
        FieldUi.bindPasswordToggle(etConfirmPassword, findViewById(R.id.btnConfirmPasswordVisibility));
        ivPhoneCheck = findViewById(R.id.ivPhoneCheck);
        tvError = findViewById(R.id.tvError);
        tvGoLogin = findViewById(R.id.tvGoLogin);
        btnRegister = findViewById(R.id.btnRegister);

        cardDriver.setOnClickListener(v -> showForm(Role.DRIVER));
        cardOperator.setOnClickListener(v -> showForm(Role.OPERATOR));
        tvChangeRole.setOnClickListener(v -> showChooser());

        FieldUi.onChange(etFullName, () -> {
            hideError();
            applyName(false);
        });
        FieldUi.onChange(etPhone, () -> {
            hideError();
            String phone = getCleanPhone();
            if (isValidPhone(phone) && !phone.contentEquals(etPhone.getText())) {
                etPhone.setText(phone);
                etPhone.setSelection(phone.length());
                return;
            }
            applyPhone(false);
        });
        FieldUi.onChange(etEmail, () -> {
            hideError();
            applyEmail(false);
        });
        FieldUi.onChange(etPassword, () -> {
            hideError();
            applyPassword(false);
            applyConfirm(false);
        });
        FieldUi.onChange(etConfirmPassword, () -> {
            hideError();
            applyConfirm(false);
        });

        etFullName.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                applyName(true);
            }
        });
        etPhone.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                applyPhone(true);
            }
        });
        etEmail.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                applyEmail(true);
            }
        });
        etPassword.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                applyPassword(true);
            }
        });
        etConfirmPassword.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                applyConfirm(true);
            }
        });

        btnRegister.setOnClickListener(v -> attemptRegister());
        tvGoLogin.setOnClickListener(v -> finish());
        tvGoLoginChoose.setOnClickListener(v -> finish());

        showChooser();
    }

    private void showChooser() {
        selectedRole = null;
        layoutForm.setVisibility(View.GONE);
        layoutChooseRole.setVisibility(View.VISIBLE);
    }

    private void showForm(Role role) {
        selectedRole = role;
        hideError();

        if (role == Role.DRIVER) {
            tvRoleLabel.setText("Registering as Driver");
        } else {
            tvRoleLabel.setText("Registering as Operator");
        }

        layoutChooseRole.setVisibility(View.GONE);
        layoutForm.setVisibility(View.VISIBLE);
        refreshAll();
    }

    @Override
    public void onBackPressed() {
        if (selectedRole != null) {
            showChooser();
        } else {
            super.onBackPressed();
        }
    }

    private int stateName(boolean strict) {
        String s = etFullName.getText().toString().trim();
        if (s.length() >= 2) {
            return FieldUi.VALID;
        }
        return (strict && !s.isEmpty()) ? FieldUi.ERROR : FieldUi.NORMAL;
    }

    private int statePhone(boolean strict) {
        String p = getCleanPhone();
        if (isValidPhone(p)) {
            return FieldUi.VALID;
        }
        return (strict && !p.isEmpty()) ? FieldUi.ERROR : FieldUi.NORMAL;
    }

    private int stateEmail(boolean strict) {
        String s = etEmail.getText().toString().trim();
        if (s.isEmpty()) {
            return FieldUi.NORMAL;
        }
        if (Patterns.EMAIL_ADDRESS.matcher(s).matches()) {
            return FieldUi.VALID;
        }
        return strict ? FieldUi.ERROR : FieldUi.NORMAL;
    }

    private int statePassword(boolean strict) {
        String s = etPassword.getText().toString();
        if (s.length() >= 6) {
            return FieldUi.VALID;
        }
        return (strict && !s.isEmpty()) ? FieldUi.ERROR : FieldUi.NORMAL;
    }

    private int stateConfirm(boolean strict) {
        String c = etConfirmPassword.getText().toString();
        String p = etPassword.getText().toString();
        if (c.isEmpty()) {
            return FieldUi.NORMAL;
        }
        if (c.equals(p) && p.length() >= 6) {
            return FieldUi.VALID;
        }
        return strict ? FieldUi.ERROR : FieldUi.NORMAL;
    }

    private void applyName(boolean strict) {
        FieldUi.set(etFullName, stateName(strict));
    }

    private void applyPhone(boolean strict) {
        FieldUi.set(layoutPhoneBox, statePhone(strict));
        updateCheck();
    }

    private void applyEmail(boolean strict) {
        FieldUi.set(etEmail, stateEmail(strict));
    }

    private void applyPassword(boolean strict) {
        FieldUi.set(etPassword, statePassword(strict));
    }

    private void applyConfirm(boolean strict) {
        FieldUi.set(etConfirmPassword, stateConfirm(strict));
    }

    private void refreshAll() {
        applyName(false);
        FieldUi.set(layoutPhoneBox, statePhone(false));
        updateCheck();
        applyEmail(false);
        applyPassword(false);
        applyConfirm(false);
    }

    private String getCleanPhone() {
        String digits = etPhone.getText().toString().replaceAll("[\\s().-]", "");
        if (digits.matches("\\+?639[0-9]{9}")) return digits.substring(digits.length() - 10);
        if (digits.matches("09[0-9]{9}")) return digits.substring(1);
        return digits;
    }

    private boolean isValidPhone(String digits) {
        return digits.matches("9[0-9]{9}");
    }

    private void updateCheck() {
        if (isValidPhone(getCleanPhone())) {
            ivPhoneCheck.setVisibility(View.VISIBLE);
        } else {
            ivPhoneCheck.setVisibility(View.INVISIBLE);
        }
    }

    private void fail(View field, String message) {
        FieldUi.set(field, FieldUi.ERROR);
        if (firstMessage == null) {
            firstMessage = message;
        }
    }

    private void attemptRegister() {
        if (registering) {
            return;
        }
        firstMessage = null;

        String fullName = etFullName.getText().toString().trim();
        String phone = getCleanPhone();
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();
        String confirm = etConfirmPassword.getText().toString();
        String fullPhone = "+63" + phone;

        boolean anyEmpty = fullName.isEmpty() || phone.isEmpty()
                || email.isEmpty() || password.isEmpty() || confirm.isEmpty();

        if (fullName.length() < 2) {
            fail(etFullName, "Please enter your full name.");
        }

        if (!isValidPhone(phone)) {
            fail(layoutPhoneBox, "Enter 10 digits starting with 9 after +63.");
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            fail(etEmail, "Please enter a valid email address.");
        }

        if (password.length() < 6) {
            fail(etPassword, "Password must be at least 6 characters.");
        }

        if (confirm.isEmpty() || !password.equals(confirm)) {
            fail(etConfirmPassword, "Passwords do not match.");
        }

        if (firstMessage != null) {
            showError(anyEmpty ? EMPTY_MESSAGE : firstMessage);
            return;
        }

        if (selectedRole == null) {
            showChooser();
            return;
        }

        hideError();
        setRegistering(true);

        AuthApi.register(selectedRole, fullName, email, fullPhone, password,
                new AuthApi.RegisterCallback() {
                    @Override
                    public void onSuccess(Role role) {
                        if (isFinishing()) {
                            return;
                        }
                        Toast.makeText(RegisterActivity.this,
                                "Account created! You can log in now.", Toast.LENGTH_LONG).show();
                        finish();
                    }

                    @Override
                    public void onError(int status, String message) {
                        if (isFinishing()) {
                            return;
                        }
                        setRegistering(false);
                        showServerError(status, message);
                    }
                });
    }

    private void showServerError(int status, String message) {
        String lower = message.toLowerCase();

        if (lower.contains("email")) {
            FieldUi.set(etEmail, FieldUi.ERROR);
        } else if (lower.contains("mobile")) {
            FieldUi.set(layoutPhoneBox, FieldUi.ERROR);
        } else if (lower.contains("password")) {
            FieldUi.set(etPassword, FieldUi.ERROR);
        } else if (lower.contains("name")) {
            FieldUi.set(etFullName, FieldUi.ERROR);
        }
        showError(message);
    }

    private void setRegistering(boolean busy) {
        registering = busy;
        btnRegister.setEnabled(!busy);
        btnRegister.setText(busy ? "Creating account..." : "Create Account");
    }

    private void hideError() {
        tvError.setVisibility(View.GONE);
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }
}
