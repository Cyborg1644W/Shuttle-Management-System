package com.shuttle.mobile.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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

        // Show the check once the phone number is complete
        etPhone.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateCheck();
            }

            @Override
            public void afterTextChanged(Editable s) {
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
        tvError.setVisibility(View.GONE);
        clearErrors();

        if (role == Role.DRIVER) {
            tvRoleLabel.setText("Registering as Driver");
        } else {
            tvRoleLabel.setText("Registering as Operator");
        }

        layoutChooseRole.setVisibility(View.GONE);
        layoutForm.setVisibility(View.VISIBLE);
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

    // ---------- Phone ----------

    private void showCountryMenu() {
        PopupMenu menu = new PopupMenu(this, layoutCountry);
        menu.getMenu().add("+63 Philippines");
        menu.getMenu().add("+1 United States");
        menu.getMenu().add("+81 Japan");
        menu.getMenu().add("+65 Singapore");

        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                String code = item.getTitle().toString().split(" ")[0];
                tvCountryCode.setText(code);
                updateCheck();
                return true;
            }
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

    // ---------- Errors (red border on the wrong field) ----------

    private void clearErrors() {
        etFullName.setBackgroundResource(R.drawable.input_bg);
        etEmployeeId.setBackgroundResource(R.drawable.input_bg);
        layoutPhoneBox.setBackgroundResource(R.drawable.input_bg);
        etEmail.setBackgroundResource(R.drawable.input_bg);
        etPassword.setBackgroundResource(R.drawable.input_bg);
        etConfirmPassword.setBackgroundResource(R.drawable.input_bg);
    }

    private void markError(View field) {
        field.setBackgroundResource(R.drawable.input_bg_error);
    }

    // ---------- Register ----------

    private void attemptRegister() {
        clearErrors();

        String fullName = etFullName.getText().toString().trim();
        String employeeId = etEmployeeId.getText().toString().trim();
        String phone = getCleanPhone();
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();
        String confirm = etConfirmPassword.getText().toString();

        boolean missing = false;
        if (fullName.isEmpty()) {
            markError(etFullName);
            missing = true;
        }
        if (employeeId.isEmpty()) {
            markError(etEmployeeId);
            missing = true;
        }
        if (phone.isEmpty()) {
            markError(layoutPhoneBox);
            missing = true;
        }
        if (email.isEmpty()) {
            markError(etEmail);
            missing = true;
        }
        if (password.isEmpty()) {
            markError(etPassword);
            missing = true;
        }
        if (confirm.isEmpty()) {
            markError(etConfirmPassword);
            missing = true;
        }
        if (missing) {
            showError("Please fill in all the fields.");
            return;
        }

        if (phone.length() != getRequiredDigits()) {
            markError(layoutPhoneBox);
            showError("Please enter a complete mobile number.");
            return;
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            markError(etEmail);
            showError("Please enter a valid email address.");
            return;
        }

        if (password.length() < 6) {
            markError(etPassword);
            showError("Password must be at least 6 characters.");
            return;
        }

        if (!password.equals(confirm)) {
            markError(etConfirmPassword);
            showError("Passwords do not match.");
            return;
        }

        String fullPhone = tvCountryCode.getText().toString() + phone;

        if (store.phoneExists(fullPhone)) {
            markError(layoutPhoneBox);
            showError("This mobile number is already registered.");
            return;
        }

        if (store.emailExists(email)) {
            markError(etEmail);
            showError("This email is already registered.");
            return;
        }

        tvError.setVisibility(View.GONE);

        // TEMPORARY: saved on this phone only. Replace with the backend later.
        store.save(fullName, employeeId, fullPhone, email, password, selectedRole);

        Toast.makeText(this, "Account created! You can log in now.", Toast.LENGTH_LONG).show();
        finish();
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }
}