package com.shuttle.mobile.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;

import com.shuttle.mobile.R;

public class FieldUi {

    public static final int NORMAL = 0;
    public static final int VALID = 1;
    public static final int ERROR = 2;

    public static void bindPasswordToggle(EditText field, ImageView toggle) {
        bindPasswordToggle(field, toggle, field.getContext().getString(R.string.show_password),
                field.getContext().getString(R.string.hide_password));
    }

    static void bindPasswordToggle(EditText field, ImageView toggle, String showLabel, String hideLabel) {
        toggle.setOnClickListener(view -> {
            int start = Math.max(0, field.getSelectionStart());
            int end = Math.max(0, field.getSelectionEnd());
            boolean show = field.getTransformationMethod() instanceof PasswordTransformationMethod;
            field.setTransformationMethod(show ? null : PasswordTransformationMethod.getInstance());
            field.setSelection(Math.min(start, field.length()), Math.min(end, field.length()));
            toggle.setImageResource(show ? R.drawable.ic_eye_off : R.drawable.ic_eye);
            toggle.setContentDescription(show ? hideLabel : showLabel);
        });
    }

    public static void set(View field, int state) {
        if (state == VALID) {
            field.setBackgroundResource(R.drawable.input_bg_valid);
        } else if (state == ERROR) {
            field.setBackgroundResource(R.drawable.input_bg_error);
        } else {
            field.setBackgroundResource(R.drawable.input_bg);
        }
    }

    public static void onChange(EditText field, final Runnable action) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                action.run();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }
}
