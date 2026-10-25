package com.shuttle.mobile.ui;

import android.os.Bundle;
import android.util.Patterns;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.shuttle.common.Role;
import com.shuttle.mobile.R;

public class RegisterActivity extends AppCompatActivity {

    private static final String EMPTY_MESSAGE = "Please fill in all the fields.";

    private View layoutChooseRole;
    private View layoutForm;
    private View cardDriver;
    private View cardOperator;
    private View layoutPhoneBox;
    private View layoutCountry;
    private TextView tvCountryCode;
    private TextView tvGoLoginChoose;
    private TextView tvRoleLabel;
    private TextView tvChangeRole;
    private EditText etFullName;
    private EditText etEmployeeId;
    private EditText etPhone;
    private EditText etEmail;
    private EditText etPassword;
    private EditText etConfirmPassword;
    private ImageView ivPhoneCheck;
    private ImageView ivBack;
    private TextView tvError;
    private TextView tvGoLogin;
    private Button btnRegister;

    private Role selectedRole = null;
    private AccountStore store;
    private String firstMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        store = new AccountStore(this);

        layoutChooseRole = findViewById(R.id.layoutChooseRole);
        layoutForm = findViewById(R.id.layoutForm);
        cardDriver = findViewById(R.id.cardDriver);
        cardOperator = findViewById(R.id.cardOperator);
        layoutPhoneBox = findViewById(R.id.layoutPhoneBox);
        layoutCountry = findViewById(R.id.layoutCountry);
        tvCountryCode = findViewById(R.id.tvCountryCode);
        tvGoLoginChoose = findViewById(R.id.tvGoLoginChoose);
        tvRoleLabel = findViewById(R.id.tvRoleLabel);
        tvChangeRole = findViewById(R.id.tvChangeRole);
        etFullName = findViewById(R.id.etFullName);
        etEmployeeId = findViewById(R.id.etEmployeeId);
        etPhone = findViewById(R.id.etPhone);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        ivPhoneCheck = findViewById(R.id.ivPhoneCheck);
        ivBack = findViewById(R.id.ivBack);
        tvError = findViewById(R.id.tvError);
        tvGoLogin = findViewById(R.id.tvGoLogin);
        btnRegister = findViewById(R.id.btnRegister);

        // Step 1: pick a role
        cardDriver.setOnClickListener(v -> showForm(Role.DRIVER));
        cardOperator.setOnClickListener(v -> showForm(Role.OPERATOR));
        tvChangeRole.setOnClickListener(v -> showChooser());
        layoutCountry.setOnClickListener(v -> showCountryMenu());

        // Borders react while typing (green = correct) and when you leave a field (red = wrong)
        FieldUi.onChange(etFullName, () -> {
            hideError();
            applyName(false);
        });
        FieldUi.onChange(etEmployeeId, () -> {
            hideError();
            applyEmp();
        });
        FieldUi.onChange(etPhone, () -> {
            hideError();
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
        ivBack.setOnClickListener(v -> goBack());
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

    // Back arrow and phone back button: form goes back to the chooser, chooser leaves the screen
    private void goBack() {
        if (selectedRole != null) {
            showChooser();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    // ---------- What is correct / wrong for each field ----------
    // NORMAL = still typing, VALID = correct (green), ERROR = wrong (red)
    // "strict" is true when you leave the field: unfinished or wrong input turns red.

    private int stateName(boolean strict) {
        String s = etFullName.getText().toString().trim();
        if (s.length() >= 2) {
            return FieldUi.VALID;
        }
        return (strict && !s.isEmpty()) ? FieldUi.ERROR : FieldUi.NORMAL;
    }

    private int stateEmp() {
        String s = etEmployeeId.getText().toString().trim();
        if (s.isEmpty()) {
            return FieldUi.NORMAL;
        }
        return WorkerStore.empIdExists(this, s) ? FieldUi.ERROR : FieldUi.VALID;
    }

    private int statePhone(boolean strict) {
        String p = getCleanPhone();
        if (p.length() == getRequiredDigits()) {
            String full = tvCountryCode.getText().toString() + p;
            return store.phoneExists(full) ? FieldUi.ERROR : FieldUi.VALID;
        }
        return (strict && !p.isEmpty()) ? FieldUi.ERROR : FieldUi.NORMAL;
    }

    private int stateEmail(boolean strict) {
        String s = etEmail.getText().toString().trim();
        if (s.isEmpty()) {
            return FieldUi.NORMAL;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(s).matches()) {
            return strict ? FieldUi.ERROR : FieldUi.NORMAL;
        }
        return store.emailExists(s) ? FieldUi.ERROR : FieldUi.VALID;
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

    private void applyEmp() {
        FieldUi.set(etEmployeeId, stateEmp());
        if (stateEmp() == FieldUi.ERROR) {
            showError("This employee ID is already registered.");
        }
    }

    private void applyPhone(boolean strict) {
        FieldUi.set(layoutPhoneBox, statePhone(strict));
        updateCheck();
        if (statePhone(false) == FieldUi.ERROR && getCleanPhone().length() == getRequiredDigits()) {
            showError("This mobile number is already registered.");
        }
    }

    private void applyEmail(boolean strict) {
        FieldUi.set(etEmail, stateEmail(strict));
        if (stateEmail(false) == FieldUi.ERROR) {
            showError("This email is already registered.");
        }
    }

    private void applyPassword(boolean strict) {
        FieldUi.set(etPassword, statePassword(strict));
    }

    private void applyConfirm(boolean strict) {
        FieldUi.set(etConfirmPassword, stateConfirm(strict));
    }

    private void refreshAll() {
        applyName(false);
        FieldUi.set(etEmployeeId, stateEmp());
        FieldUi.set(layoutPhoneBox, statePhone(false));
        updateCheck();
        FieldUi.set(etEmail, stateEmail(false));
        applyPassword(false);
        applyConfirm(false);
    }

    // ---------- Phone ----------

    private void showCountryMenu() {
        PopupMenu menu = new PopupMenu(this, layoutCountry);
        menu.getMenu().add("+63 Philippines");
        menu.getMenu().add("+1 United States");
        menu.getMenu().add("+81 Japan");
        menu.getMenu().add("+65 Singapore");

        menu.setOnMenuItemClickListener((MenuItem item) -> {
            String code = item.getTitle().toString().split(" ")[0];
            tvCountryCode.setText(code);
            applyPhone(false);
            return true;
        });

        menu.show();
    }

    // Digits only, and drops a leading 0 for +63 and +81 (0961... -> 961...)
    private String getCleanPhone() {
        String digits = etPhone.getText().toString().replaceAll("[^0-9]", "");
        String code = tvCountryCode.getText().toString();

        if ((code.equals("+63") || code.equals("+81")) && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits;
    }

    private int getRequiredDigits() {
        if (tvCountryCode.getText().toString().equals("+65")) {
            return 8;   // Singapore
        }
        return 10;      // Philippines, United States, Japan
    }

    private void updateCheck() {
        if (getCleanPhone().length() == getRequiredDigits()) {
            ivPhoneCheck.setVisibility(View.VISIBLE);
        } else {
            ivPhoneCheck.setVisibility(View.INVISIBLE);
        }
    }

    // ---------- Register ----------

    // Marks a field red and remembers the first message to show
    private void fail(View field, String message) {
        FieldUi.set(field, FieldUi.ERROR);
        if (firstMessage == null) {
            firstMessage = message;
        }
    }

    private void attemptRegister() {
        firstMessage = null;

        String fullName = etFullName.getText().toString().trim();
        String employeeId = etEmployeeId.getText().toString().trim();
        String phone = getCleanPhone();
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();
        String confirm = etConfirmPassword.getText().toString();
        String fullPhone = tvCountryCode.getText().toString() + phone;

        boolean anyEmpty = fullName.isEmpty() || employeeId.isEmpty() || phone.isEmpty()
                || email.isEmpty() || password.isEmpty() || confirm.isEmpty();

        if (fullName.length() < 2) {
            fail(etFullName, "Please enter your full name.");
        }

        if (employeeId.isEmpty()) {
            fail(etEmployeeId, "Please enter your employee ID.");
        } else if (WorkerStore.empIdExists(this, employeeId)) {
            fail(etEmployeeId, "This employee ID is already registered.");
        }

        if (phone.length() != getRequiredDigits()) {
            fail(layoutPhoneBox, "Please enter a complete mobile number.");
        } else if (store.phoneExists(fullPhone)) {
            fail(layoutPhoneBox, "This mobile number is already registered.");
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            fail(etEmail, "Please enter a valid email address.");
        } else if (store.emailExists(email)) {
            fail(etEmail, "This email is already registered.");
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

        hideError();

        // TEMPORARY: saved on this phone only. Replace with the backend later.
        store.save(fullName, employeeId, fullPhone, email, password, selectedRole);

        WorkerStore.addRegistered(
                this,
                fullName,
                employeeId,
                WorkerStore.formatPhone(tvCountryCode.getText().toString(), phone),
                selectedRole == Role.OPERATOR);

        Toast.makeText(this, "Account created! You can log in now.", Toast.LENGTH_LONG).show();
        finish();
    }

    private void hideError() {
        tvError.setVisibility(View.GONE);
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }
}