package com.shuttle.mobile.ui;

import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shuttle.mobile.R;
import com.shuttle.mobile.client.ApiClient;
import com.shuttle.mobile.client.ServerConnection;

/** Edits the saved server address from the sign-in screen. */
final class ServerConnectionUi {
    private ServerConnectionUi() { }

    static void show(AppCompatActivity activity) {
        if (Session.isSignedIn()) {
            Toast.makeText(activity, "Sign out before changing servers.", Toast.LENGTH_SHORT).show();
            return;
        }

        View content = activity.getLayoutInflater().inflate(R.layout.dialog_server_connection, null);
        EditText address = content.findViewById(R.id.etServerAddress);
        address.setText(ApiClient.getBaseUrl());
        TextView error = content.findViewById(R.id.tvServerAddressError);
        FieldUi.onChange(address, () -> {
            error.setVisibility(View.GONE);
            FieldUi.set(address, FieldUi.NORMAL);
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setView(content).create();
        View save = content.findViewById(R.id.btnSaveServerAddress);
        content.findViewById(R.id.btnCancelServerAddress).setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            try {
                ServerConnection.save(activity, address.getText().toString());
                ApiClient.getInstance().setAuthToken(null);
                Toast.makeText(activity, R.string.server_address_saved, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } catch (IllegalArgumentException e) {
                FieldUi.set(address, FieldUi.ERROR);
                error.setText(e.getMessage());
                error.setVisibility(View.VISIBLE);
            }
        });
        address.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            save.performClick();
            return true;
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
    }
}
