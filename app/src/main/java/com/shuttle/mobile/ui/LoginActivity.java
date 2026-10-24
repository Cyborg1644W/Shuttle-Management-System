package com.shuttle.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
    private TextView tvCountryCode;
    private ImageView ivPhoneCheck;
    private EditText etPhone;
    private EditText etEmail;
    private EditText etPassword;
    private TextView tvError;
    private Button btnContinue;
    private Button btnApple;
    private Button btnGoogle;
    private TextView tvGoRegister;

    private boolean mobileMode = true;

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
        ((ViewGroup) etPhone.getParent()).setAddStatesFromChildren(true);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        tvError = findViewById(R.id.tvError);
        btnContinue = findViewById(R.id.btnContinue);
        btnApple = findViewById(R.id.btnApple);
        btnGoogle = findViewById(R.id.btnGoogle);
        tvGoRegister = findViewById(R.id.tvGoRegister);

        tvTabMobile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(true);
            }
        });

        tvTabEmail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(false);
            }
        });

        layoutCountry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCountryMenu();
            }
        });

        // Show the check only when the number has the right amount of digits
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

        btnContinue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attemptLogin();
            }
        });

        btnApple.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(LoginActivity.this, "Apple sign-in coming soon", Toast.LENGTH_SHORT).show();
            }
        });

        btnGoogle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(LoginActivity.this, "Google sign-in coming soon", Toast.LENGTH_SHORT).show();
            }
        });

        tvGoRegister.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(LoginActivity.this, RegisterActivity.class));
            }
        });

        updateCheck();
    }

    // Digits only, and drops a leading 0 for +63 and +81 (e.g. 0961... -> 961...)
    private String getCleanDigits() {
        String digits = etPhone.getText().toString().replaceAll("[^0-9]", "");
        String code = tvCountryCode.getText().toString();

        if ((code.equals("+63") || code.equals("+81")) && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits;
    }

    // How many digits a number needs for each country code
    private int getRequiredDigits() {
        String code = tvCountryCode.getText().toString();

        if (code.equals("+65")) {
            return 8;   // Singapore
        }
        return 10;      // Philippines, United States, Japan
    }

    private boolean isPhoneValid() {
        return getCleanDigits().length() == getRequiredDigits();
    }

    private void updateCheck() {
        if (isPhoneValid()) {
            ivPhoneCheck.setVisibility(View.VISIBLE);
        } else {
            ivPhoneCheck.setVisibility(View.INVISIBLE);
        }
    }

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
    }

    private void attemptLogin() {
        Role role;
        String userId;

        if (mobileMode) {
            String phone = getCleanDigits();

            if (phone.isEmpty()) {
                showError("Please enter your mobile number.");
                return;
            }

            if (!isPhoneValid()) {
                showError("Please enter a complete mobile number.");
                return;
            }

            role = checkPhone(phone);
            userId = tvCountryCode.getText().toString() + phone;

            if (role == null) {
                showError("This mobile number is not registered.");
                return;
            }
        } else {
            String email = etEmail.getText().toString().trim();
            String password = etPassword.getText().toString();

            if (email.isEmpty() || password.isEmpty()) {
                showError("Please enter your email and password.");
                return;
            }

            role = checkCredentials(email, password);
            userId = email;

            if (role == null) {
                showError("Wrong email or password.");
                return;
            }
        }

        tvError.setVisibility(View.GONE);
        openHomeScreen(role, userId);
    }

    // TEMPORARY STUB: no real OTP yet.
    // Test numbers: 9618447872 = Driver, 9618447873 = Operator.
    private Role checkPhone(String phone) {
        if (phone.equals("9618447872")) {
            return Role.DRIVER;
        }
        if (phone.equals("9618447873")) {
            return Role.OPERATOR;
        }
        return null;
    }

    // TEMPORARY STUB: replace with a call to Jim's backend when it's ready.
    private Role checkCredentials(String email, String password) {
        if (email.equals("driver@shuttle.com") && password.equals("1234")) {
            return Role.DRIVER;
        }
        if (email.equals("operator@shuttle.com") && password.equals("1234")) {
            return Role.OPERATOR;
        }
        return null;
    }

    private void openHomeScreen(Role role, String userId) {
        Intent intent;
        if (role == Role.DRIVER) {
            intent = new Intent(this, DriverActivity.class);
        } else {
            intent = new Intent(this, OperatorActivity.class);
        }
        intent.putExtra("user", userId);
        startActivity(intent);
        finish();
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }
}