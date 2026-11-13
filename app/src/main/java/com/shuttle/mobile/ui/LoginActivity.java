package com.shuttle.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.shuttle.common.Role;
import com.shuttle.mobile.R;

public class LoginActivity extends AppCompatActivity {

    private TextView tvTabMobile;
    private TextView tvTabEmail;
    private View layoutMobile;
    private View layoutEmail;
    private View layoutCountry;
    private View phoneBox;
    private TextView tvCountryCode;
    private ImageView ivPhoneCheck;
    private EditText etPhone;
    private EditText etEmail;
    private EditText etPassword;
    private TextView tvError;
    private Button btnContinue;
    private Button btnGoogle;
    private TextView tvGoRegister;

    private boolean mobileMode = true;
    private boolean loggingIn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        tvTabMobile = findViewById(R.id.tvTabMobile);
        tvTabEmail = findViewById(R.id.tvTabEmail);
        layoutMobile = findViewById(R.id.layoutMobile);
        layoutEmail = findViewById(R.id.layoutEmail);
        layoutCountry = findViewById(R.id.layoutCountry);
        tvCountryCode = findViewById(R.id.tvCountryCode);
        ivPhoneCheck = findViewById(R.id.ivPhoneCheck);
        etPhone = findViewById(R.id.etPhone);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        tvError = findViewById(R.id.tvError);
        btnContinue = findViewById(R.id.btnContinue);
        btnGoogle = findViewById(R.id.btnGoogle);
        tvGoRegister = findViewById(R.id.tvGoRegister);

        phoneBox = (View) etPhone.getParent();
        ((ViewGroup) phoneBox).setAddStatesFromChildren(true);

        tvTabMobile.setOnClickListener(v -> selectTab(true));
        tvTabEmail.setOnClickListener(v -> selectTab(false));
        layoutCountry.setOnClickListener(v -> showCountryMenu());

        FieldUi.onChange(etPhone, () -> {
            tvError.setVisibility(View.GONE);
            updatePhone();
        });
        FieldUi.onChange(etEmail, () -> {
            tvError.setVisibility(View.GONE);
            resetEmailAndPassword();
        });
        FieldUi.onChange(etPassword, () -> {
            tvError.setVisibility(View.GONE);
            resetEmailAndPassword();
        });

        btnContinue.setOnClickListener(v -> attemptLogin());

        btnGoogle.setOnClickListener(v ->
                Toast.makeText(this, "Google sign-in coming soon", Toast.LENGTH_SHORT).show());

        tvGoRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));

        updatePhone();
        resetEmailAndPassword();
    }

    private String getCleanDigits() {
        String digits = etPhone.getText().toString().replaceAll("[^0-9]", "");
        String code = tvCountryCode.getText().toString();

        if ((code.equals("+63") || code.equals("+81")) && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits;
    }

    private int getRequiredDigits() {
        if (tvCountryCode.getText().toString().equals("+65")) {
            return 8;
        }
        return 10;
    }

    private boolean isPhoneValid() {
        return getCleanDigits().length() == getRequiredDigits();
    }

    private void updatePhone() {
        boolean valid = isPhoneValid();
        ivPhoneCheck.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
        FieldUi.set(phoneBox, valid ? FieldUi.VALID : FieldUi.NORMAL);
    }

    private void showCountryMenu() {
        PopupMenu menu = new PopupMenu(this, layoutCountry);
        menu.getMenu().add("+63 Philippines");
        menu.getMenu().add("+1 United States");
        menu.getMenu().add("+81 Japan");
        menu.getMenu().add("+65 Singapore");

        menu.setOnMenuItemClickListener((MenuItem item) -> {
            String code = item.getTitle().toString().split(" ")[0];
            tvCountryCode.setText(code);
            updatePhone();
            return true;
        });

        menu.show();
    }

    private boolean isEmailValid() {
        return Patterns.EMAIL_ADDRESS.matcher(etEmail.getText().toString().trim()).matches();
    }

    private void resetEmailAndPassword() {
        FieldUi.set(etEmail, isEmailValid() ? FieldUi.VALID : FieldUi.NORMAL);
        FieldUi.set(etPassword, FieldUi.NORMAL);
    }

    private void selectTab(boolean mobile) {
        mobileMode = mobile;
        tvError.setVisibility(View.GONE);

        if (mobile) {
            layoutMobile.setVisibility(View.VISIBLE);
            layoutEmail.setVisibility(View.GONE);
            tvTabMobile.setBackgroundResource(R.drawable.tab_selected);
            tvTabMobile.setTextColor(getResources().getColor(R.color.white));
            tvTabEmail.setBackgroundResource(R.drawable.tab_unselected);
            tvTabEmail.setTextColor(getResources().getColor(R.color.text_secondary));
            btnContinue.setText("Continue with Mobile OTP");
        } else {
            layoutMobile.setVisibility(View.GONE);
            layoutEmail.setVisibility(View.VISIBLE);
            tvTabEmail.setBackgroundResource(R.drawable.tab_selected);
            tvTabEmail.setTextColor(getResources().getColor(R.color.white));
            tvTabMobile.setBackgroundResource(R.drawable.tab_unselected);
            tvTabMobile.setTextColor(getResources().getColor(R.color.text_secondary));
            btnContinue.setText("Continue");
        }

        updatePhone();
        resetEmailAndPassword();
    }

    private void attemptLogin() {
        if (loggingIn) {
            return;
        }

        if (mobileMode) {
            showError("Mobile sign-in is not available yet. Please use the Email tab.");
            return;
        }

        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();

        if (email.isEmpty() || password.isEmpty()) {
            if (email.isEmpty()) {
                FieldUi.set(etEmail, FieldUi.ERROR);
            }
            if (password.isEmpty()) {
                FieldUi.set(etPassword, FieldUi.ERROR);
            }
            showError("Please enter your email and password.");
            return;
        }

        if (!isEmailValid()) {
            FieldUi.set(etEmail, FieldUi.ERROR);
            showError("Please enter a valid email address.");
            return;
        }

        tvError.setVisibility(View.GONE);
        setLoggingIn(true);

        AuthApi.login(email, password, new AuthApi.LoginCallback() {
            @Override
            public void onSuccess(Role role) {
                if (isFinishing()) {
                    return;
                }
                openHomeScreen(role);
            }

            @Override
            public void onError(int status, String message) {
                if (isFinishing()) {
                    return;
                }
                setLoggingIn(false);
                if (status == 401) {
                    FieldUi.set(etEmail, FieldUi.ERROR);
                    FieldUi.set(etPassword, FieldUi.ERROR);
                    showError("Wrong email or password.");
                } else {
                    showError(message);
                }
            }
        });
    }

    private void setLoggingIn(boolean busy) {
        loggingIn = busy;
        btnContinue.setEnabled(!busy);
        btnContinue.setText(busy ? "Signing in..." : "Continue");
    }

    private void openHomeScreen(Role role) {
        Intent intent;
        if (role == Role.DRIVER) {
            intent = new Intent(this, DriverActivity.class);
        } else {
            intent = new Intent(this, OperatorActivity.class);
        }
        intent.putExtra("user", Session.getEmail());
        startActivity(intent);
        finish();
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }
}