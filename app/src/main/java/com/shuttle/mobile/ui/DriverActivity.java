package com.shuttle.mobile.ui;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class DriverActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView t = new TextView(this);
        t.setText("Driver screen (coming soon)");
        t.setPadding(48, 96, 48, 48);
        setContentView(t);
    }
}