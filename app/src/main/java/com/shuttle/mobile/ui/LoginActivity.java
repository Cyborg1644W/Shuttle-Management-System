package com.shuttle.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.shuttle.common.Role;
import com.shuttle.mobile.R;
import com.shuttle.mobile.client.ApiClient;
import com.shuttle.mobile.client.ServerConnection;

public class LoginActivity extends AppCompatActivity {

    private EditText etEmail;
    private EditText etPassword;
    private TextView tvError;
    private Button btnContinue;
    private Button btnGoogle;
    private TextView tvGoRegister;
    private TextView serverConnection;

    private boolean loggingIn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ApiClient.initialize(this);
        setContentView(R.layout.activity_login);
        serverConnection = findViewById(R.id.btnServerConnection);
        showSelectedServer();
        serverConnection.setOnClickListener(v -> {
            if (!loggingIn) ServerConnectionUi.show(this, () -> {
                showSelectedServer();
                tvError.setVisibility(View.GONE);
            });
        });

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        FieldUi.bindPasswordToggle(etPassword, findViewById(R.id.btnPasswordVisibility));
        tvError = findViewById(R.id.tvError);
        btnContinue = findViewById(R.id.btnContinue);
        btnGoogle = findViewById(R.id.btnGoogle);
        tvGoRegister = findViewById(R.id.tvGoRegister);

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

    }

    private boolean isEmailValid() {
        return Patterns.EMAIL_ADDRESS.matcher(etEmail.getText().toString().trim()).matches();
    }

    private void showSelectedServer() {
        String url = ApiClient.getBaseUrl();
        int mode = url.equals(ServerConnection.DEFAULT_URL) ? R.string.server_automatic : R.string.server_custom;
        serverConnection.setText(getString(R.string.server_connection_status, getString(mode), url));
    }

    private void resetEmailAndPassword() {
        FieldUi.set(etEmail, isEmailValid() ? FieldUi.VALID : FieldUi.NORMAL);
        FieldUi.set(etPassword, FieldUi.NORMAL);
    }

    private void attemptLogin() {
        if (loggingIn) {
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

        AuthApi.login(email, password, loginCallback());
    }

    private AuthApi.LoginCallback loginCallback() {
        return new AuthApi.LoginCallback() {
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
        };
    }

    private void setLoggingIn(boolean busy) {
        loggingIn = busy;
        btnContinue.setEnabled(!busy);
        findViewById(R.id.btnServerConnection).setEnabled(!busy);
        tvGoRegister.setEnabled(!busy);
        btnContinue.setText(busy ? "Signing in..." : getString(R.string.sign_in));
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
