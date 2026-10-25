package com.shuttle.mobile.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;

import com.shuttle.mobile.R;

public class FieldUi {

    public static final int NORMAL = 0;   // plain (turns green while you type in it)
    public static final int VALID = 1;    // green: correct
    public static final int ERROR = 2;    // red: wrong

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