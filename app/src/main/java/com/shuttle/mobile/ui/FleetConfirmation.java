package com.shuttle.mobile.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.shuttle.mobile.R;

final class FleetConfirmation {
    private final BottomSheetDialog dialog;
    private final MaterialButton cancel;
    private final MaterialButton confirm;
    private final TextView error;
    private final LinearLayout details;
    private final String action;

    FleetConfirmation(Activity activity, String title, String message, String action, boolean destructive) {
        this.action = action;
        View view = LayoutInflater.from(activity).inflate(R.layout.sheet_action_confirmation, null);
        dialog = new BottomSheetDialog(activity);
        dialog.setContentView(view);
        ((TextView) view.findViewById(R.id.confirmationTitle)).setText(title);
        ((TextView) view.findViewById(R.id.confirmationMessage)).setText(message);
        details = view.findViewById(R.id.confirmationDetails);
        error = view.findViewById(R.id.confirmationError);
        cancel = view.findViewById(R.id.confirmationCancel);
        confirm = view.findViewById(R.id.confirmationConfirm);
        confirm.setText(action);
        if (destructive) {
            confirm.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.error)));
            cancel.setCornerRadius(confirm.getCornerRadius());
            cancel.setInsetTop(confirm.getInsetTop());
            cancel.setInsetBottom(confirm.getInsetBottom());

            LinearLayout.LayoutParams confirmLayout = (LinearLayout.LayoutParams) confirm.getLayoutParams();
            confirmLayout.weight = 1.4f;
            confirm.setLayoutParams(confirmLayout);
        }
        cancel.setOnClickListener(v -> dialog.dismiss());
    }

    void addDetail(String label, String value) {
        details.setVisibility(View.VISIBLE);
        WorkerDialogs.informationRow(details.getContext(), details, label, value);
    }

    void show(Runnable confirmed) {
        confirm.setOnClickListener(v -> confirmed.run());
        dialog.show();
    }

    void setBusy(boolean busy, String busyLabel) {
        confirm.setEnabled(!busy);
        cancel.setEnabled(!busy);
        dialog.setCancelable(!busy);
        confirm.setText(busy ? busyLabel : action);
        if (busy) error.setVisibility(View.GONE);
    }

    void showError(String message) {
        setBusy(false, "");
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }

    void dismiss() {
        dialog.dismiss();
    }
}
