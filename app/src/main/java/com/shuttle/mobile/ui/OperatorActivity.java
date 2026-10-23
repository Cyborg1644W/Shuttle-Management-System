package com.shuttle.mobile.ui;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class OperatorActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView t = new TextView(this);
        t.setText("Operator screen (coming soon)");
        t.setPadding(48, 96, 48, 48);
        setContentView(t);
    }
}