package com.shuttle.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.shuttle.common.Role;

public class LoginActivity extends AppCompatActivity {

    private EditText etEmail;
    private EditText etPassword;
    private TextView tvError;
    private Button btnLogin;
    private TextView tvGoRegister;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        tvError = findViewById(R.id.tvError);
        btnLogin = findViewById(R.id.btnLogin);
        tvGoRegister = findViewById(R.id.tvGoRegister);

        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attemptLogin();
            }
        });

        tvGoRegister.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(LoginActivity.this, RegisterActivity.class));
            }
        });
    }

    private void attemptLogin() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();

        if (email.isEmpty() || password.isEmpty()) {
            showError("Please enter your email and password.");
            return;
        }

        Role role = checkCredentials(email, password);

        if (role == null) {
            showError("Wrong email or password.");
            return;
        }

        tvError.setVisibility(View.GONE);
        openHomeScreen(role, email);
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

    private void openHomeScreen(Role role, String email) {
        Intent intent;
        if (role == Role.DRIVER) {
            intent = new Intent(this, DriverActivity.class);
        } else {
            intent = new Intent(this, OperatorActivity.class);
        }
        intent.putExtra("email", email);
        startActivity(intent);
        finish();
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }
}