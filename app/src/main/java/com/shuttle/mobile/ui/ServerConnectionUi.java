package com.shuttle.mobile.ui;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shuttle.mobile.client.ApiClient;
import com.shuttle.mobile.client.ServerConnection;

/** A shared address setting for sign-in and registration. */
final class ServerConnectionUi {
    private ServerConnectionUi() { }

    static void show(AppCompatActivity activity) {
        if (Session.isSignedIn()) {
            Toast.makeText(activity, "Sign out before changing servers.", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, 0);
        TextView help = new TextView(activity);
        help.setText("Both phones must use the same server. Paste the address shared by your operator. For different locations, use its online HTTPS address.");
        content.addView(help);
        EditText address = new EditText(activity);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSingleLine(true);
        address.setHint("https://your-server.example");
        address.setText(ApiClient.getBaseUrl());
        address.setSelectAllOnFocus(true);
        content.addView(address);
        TextView error = new TextView(activity);
        error.setTextColor(0xFFB3261E);
        error.setVisibility(View.GONE);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(error);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("Server connection")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        ServerConnection.save(activity, address.getText().toString());
                        ApiClient.getInstance().setAuthToken(null);
                        Toast.makeText(activity, "Server address saved.", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } catch (IllegalArgumentException e) {
                        error.setText(e.getMessage());
                        error.setVisibility(View.VISIBLE);
                    }
                }));
        dialog.show();
    }
}
