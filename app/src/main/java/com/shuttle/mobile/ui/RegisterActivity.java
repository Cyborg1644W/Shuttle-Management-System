package com.shuttle.mobile.ui;

import android.os.Bundle;
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

import java.util.Locale;
import java.util.function.ToIntFunction;

public class RegisterActivity extends AppCompatActivity {
    private static final int MIN_PASSWORD_LENGTH = 6;

    private record RegistrationField(EditText input, View outline, TextView error,
                                     ToIntFunction<String> validate) {
        String value() {
            return input.getText().toString();
        }

        boolean showError(CharSequence message) {
            return FieldUi.showInlineError(outline, error, message);
        }
    }

    private View layoutChooseRole;
    private View layoutForm;
    private TextView tvRoleLabel;
    private ImageView ivPhoneCheck;
    private TextView tvError;
    private Button btnRegister;

    private RegistrationField firstName, lastName, phone, email, password, confirmPassword;
    private RegistrationField[] fields;
    private Role selectedRole;
    private boolean validationStarted;
    private boolean registering;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ApiClient.initialize(this);
        setContentView(R.layout.activity_register);
        layoutChooseRole = findViewById(R.id.layoutChooseRole);
        layoutForm = findViewById(R.id.layoutForm);
        tvRoleLabel = findViewById(R.id.tvRoleLabel);
        ivPhoneCheck = findViewById(R.id.ivPhoneCheck);
        tvError = findViewById(R.id.tvError);
        btnRegister = findViewById(R.id.btnRegister);

        firstName = field(R.id.etFirstName, R.id.tvRegistrationFirstNameError,
                value -> FieldUi.isBlank(value) ? R.string.profile_first_name_required : 0);
        lastName = field(R.id.etLastName, R.id.tvRegistrationLastNameError,
                value -> FieldUi.isBlank(value) ? R.string.profile_last_name_required : 0);
        phone = new RegistrationField(findViewById(R.id.etPhone), findViewById(R.id.layoutPhoneBox),
                findViewById(R.id.tvRegistrationPhoneError), RegisterActivity::phoneError);
        email = field(R.id.etEmail, R.id.tvRegistrationEmailError,
                value -> FieldUi.isGmailAddress(value.trim()) ? 0 : R.string.profile_email_invalid);
        password = field(R.id.etPassword, R.id.tvRegistrationPasswordError, RegisterActivity::passwordError);
        confirmPassword = field(R.id.etConfirmPassword, R.id.tvRegistrationConfirmError,
                value -> !value.isEmpty() && value.equals(password.value()) ? 0 : R.string.registration_password_mismatch);
        fields = new RegistrationField[]{firstName, lastName, phone, email, password, confirmPassword};

        FieldUi.bindPasswordToggle(password.input(), findViewById(R.id.btnPasswordVisibility));
        FieldUi.bindPasswordToggle(confirmPassword.input(), findViewById(R.id.btnConfirmPasswordVisibility));
        for (RegistrationField field : fields) {
            FieldUi.onChange(field.input(), () -> onInputChanged(field));
        }

        findViewById(R.id.cardDriver).setOnClickListener(v -> showForm(Role.DRIVER));
        findViewById(R.id.cardOperator).setOnClickListener(v -> showForm(Role.OPERATOR));
        findViewById(R.id.tvChangeRole).setOnClickListener(v -> showChooser());
        findViewById(R.id.tvGoLogin).setOnClickListener(v -> finish());
        findViewById(R.id.tvGoLoginChoose).setOnClickListener(v -> finish());
        btnRegister.setOnClickListener(v -> attemptRegister());
        showChooser();
    }

    private RegistrationField field(int inputId, int messageId, ToIntFunction<String> validate) {
        EditText input = findViewById(inputId);
        return new RegistrationField(input, input, findViewById(messageId), validate);
    }

    private void showChooser() {
        if (registering) return;
        selectedRole = null;
        layoutForm.setVisibility(View.GONE);
        layoutChooseRole.setVisibility(View.VISIBLE);
    }

    private void showForm(Role role) {
        selectedRole = role;
        validationStarted = false;
        hideError();
        for (RegistrationField field : fields) {
            field.showError(null);
        }
        updatePhoneCheck();
        tvRoleLabel.setText(role == Role.DRIVER ? "Registering as Driver" : "Registering as Operator");
        layoutChooseRole.setVisibility(View.GONE);
        layoutForm.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (selectedRole != null) {
            showChooser();
        } else {
            super.onBackPressed();
        }
    }

    private void onInputChanged(RegistrationField field) {
        hideError();
        if (field == phone) {
            String digits = cleanPhone(field.value());
            if (isValidPhone(digits) && !digits.equals(field.value())) {
                field.input().setText(digits);
                field.input().setSelection(digits.length());
                return;
            }
        }
        if (validationStarted) {
            validate(field);
            if (field == password) validate(confirmPassword);
        }
        if (field == phone) updatePhoneCheck();
    }

    private static String cleanPhone(String value) {
        String digits = value.replaceAll("[\\s().-]", "");
        if (digits.matches("\\+?639[0-9]{9}")) return digits.substring(digits.length() - 10);
        if (digits.matches("09[0-9]{9}")) return digits.substring(1);
        return digits;
    }

    private static boolean isValidPhone(String digits) {
        return digits.matches("9[0-9]{9}");
    }

    private static int phoneError(String value) {
        String digits = cleanPhone(value);
        if (FieldUi.isBlank(digits)) return R.string.registration_phone_required;
        return isValidPhone(digits) ? 0 : R.string.registration_phone_invalid;
    }

    private static int passwordError(String value) {
        if (value.isEmpty()) return R.string.registration_password_required;
        return value.length() < MIN_PASSWORD_LENGTH ? R.string.registration_password_short : 0;
    }

    private boolean validate(RegistrationField field) {
        int error = field.validate().applyAsInt(field.value());
        if (error == 0) return field.showError(null);
        String message = error == R.string.registration_password_short
                ? getString(error, MIN_PASSWORD_LENGTH) : getString(error);
        return field.showError(message);
    }

    private void updatePhoneCheck() {
        boolean valid = isValidPhone(cleanPhone(phone.value())) && phone.error().getVisibility() != View.VISIBLE;
        ivPhoneCheck.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
    }

    private void attemptRegister() {
        if (registering || selectedRole == null) return;
        hideError();
        validationStarted = true;
        boolean valid = true;
        for (RegistrationField field : fields) {
            if (!validate(field)) valid = false;
        }
        updatePhoneCheck();
        if (!valid) return;

        String fullName = firstName.value().trim() + " " + lastName.value().trim();
        String fullPhone = "+63" + cleanPhone(phone.value());
        setRegistering(true);

        AuthApi.register(selectedRole, fullName, email.value().trim(), fullPhone, password.value(),
                new AuthApi.RegisterCallback() {
                    @Override
                    public void onSuccess(Role role) {
                        if (isFinishing() || isDestroyed()) return;
                        Toast.makeText(RegisterActivity.this,
                                "Account created! You can log in now.", Toast.LENGTH_LONG).show();
                        finish();
                    }

                    @Override
                    public void onError(int status, String message) {
                        if (isFinishing() || isDestroyed()) return;
                        setRegistering(false);
                        showServerError(status, message);
                    }
                });
    }

    private void showServerError(int status, String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (status == 400 || status == 409) {
            if (lower.contains("email")) {
                email.showError(message);
                return;
            }
            if (lower.contains("mobile")) {
                phone.showError(message);
                updatePhoneCheck();
                return;
            }
            if (lower.contains("password")) {
                password.showError(message);
                return;
            }
        }
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }

    private void setRegistering(boolean busy) {
        registering = busy;
        btnRegister.setEnabled(!busy);
        for (RegistrationField field : fields) {
            field.input().setEnabled(!busy);
        }
        btnRegister.setText(busy ? R.string.registration_creating : R.string.create_account);
    }

    private void hideError() {
        tvError.setVisibility(View.GONE);
    }
}
