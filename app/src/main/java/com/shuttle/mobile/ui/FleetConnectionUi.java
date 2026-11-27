package com.shuttle.mobile.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.shuttle.mobile.R;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;
import org.json.JSONObject;


/** Account connections are explicit: registration alone never joins a terminal. */
public final class FleetConnectionUi {
    private static final String INVALID_INVITE =
            "This invite code is invalid, disabled, or already used. Ask the operator for a new one.";

    private FleetConnectionUi() { }

    public static final class Membership {
        public final boolean joined;
        public final String terminalId;
        public final String terminalName;
        public final String operatorName;

        public Membership(JSONObject row) {
            joined = row.optBoolean("joined", false);
            terminalId = FleetApi.text(row, "terminalId");
            terminalName = FleetApi.text(row, "terminalName");
            operatorName = FleetApi.text(row, "operatorName");
        }
    }

    public static void showDriver(Activity activity, Membership current, Runnable changed) {
        if (current == null || !current.joined) {
            enterCode(activity, current, changed);
            return;
        }
        android.widget.LinearLayout content = WorkerDialogs.detailContent(activity);
        android.widget.LinearLayout card = WorkerDialogs.detailSection(activity, content, "Current Terminal");
        WorkerDialogs.informationRow(activity, card, "Terminal", current.terminalName);
        WorkerDialogs.informationRow(activity, card, "Operator", current.operatorName);
        android.widget.TextView duty = WorkerDialogs.informationRow(activity, card, "Duty status", "Loading…");
        android.widget.TextView vehicle = WorkerDialogs.informationRow(activity, card, "Vehicle assignment", "Loading…");
        com.google.android.material.button.MaterialButton switchTerminal =
                new com.google.android.material.button.MaterialButton(activity);
        switchTerminal.setText("Join another terminal");
        content.addView(switchTerminal, new android.widget.LinearLayout.LayoutParams(-1, -2));
        switchTerminal.setOnClickListener(v -> enterCode(activity, current, changed));
        com.google.android.material.button.MaterialButton leave =
                new com.google.android.material.button.MaterialButton(activity);
        leave.setText("Leave Terminal");
        leave.setEnabled(false);
        content.addView(leave, new android.widget.LinearLayout.LayoutParams(-1, -2));
        content.addView(WorkerDialogs.detailText(activity,
                "Ask your operator to remove your terminal connection.", 12, false));
        android.app.Dialog page = WorkerDialogs.showDetailPage(activity, "Terminal Details", content);
        FleetApi.get("/workers/" + FleetApi.encode(Session.getEmployeeId()), new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject reply) {
                if (!alive(activity) || !page.isShowing()) return;
                JSONObject worker = reply.optJSONObject("worker");
                if (worker == null) worker = reply;
                String status = FleetApi.text(worker, "shiftStatus");
                boolean active = "ON_SHIFT".equals(status);
                duty.setText(active ? "On Duty" : "Off Duty");
                String assigned = FleetApi.text(worker, "shuttleId");
                vehicle.setText(assigned.isEmpty() ? "Unassigned" : assigned);
                switchTerminal.setEnabled(!active);
            }
            @Override public void onError(int status, String message) {
                if (!alive(activity) || !page.isShowing()) return;
                duty.setText("Unavailable");
                vehicle.setText("Unavailable");
            }
        });
    }

    private static void enterCode(Activity activity, Membership current, Runnable changed) {
        enterCode(activity, current, changed, "", "");
    }

    private static void enterCode(Activity activity, Membership current, Runnable changed,
                                  String previousCode, String previousError) {
        android.view.View view = android.view.LayoutInflater.from(activity).inflate(com.shuttle.mobile.R.layout.sheet_invitation, null);
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(activity);
        sheet.setContentView(view);
        ((android.widget.TextView) view.findViewById(com.shuttle.mobile.R.id.tvInviteSheetTitle)).setText("Join a terminal");
        ((android.widget.TextView) view.findViewById(com.shuttle.mobile.R.id.tvInviteSheetDescription))
                .setText("Enter the invitation code from your operator.");
        EditText input = view.findViewById(com.shuttle.mobile.R.id.etInviteSheetCode);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(64)});
        view.findViewById(com.shuttle.mobile.R.id.btnInviteSheetReveal).setVisibility(android.view.View.GONE);
        view.findViewById(com.shuttle.mobile.R.id.btnInviteSheetCopy).setVisibility(android.view.View.GONE);
        android.widget.TextView error = view.findViewById(com.shuttle.mobile.R.id.tvInviteSheetError);
        com.google.android.material.button.MaterialButton action = view.findViewById(com.shuttle.mobile.R.id.btnInviteSheetAction);
        View codeField = view.findViewById(R.id.inviteCodeField);
        input.setText(previousCode);
        action.setEnabled(!previousCode.isEmpty());
        if (!previousError.isEmpty()) showCodeError(codeField, error, previousError, true);
        final boolean[] busy = {false};
        FieldUi.onChange(input, () -> {
            FieldUi.set(codeField, FieldUi.NORMAL);
            error.setVisibility(android.view.View.GONE);
            action.setEnabled(!busy[0] && !input.getText().toString().trim().isEmpty());
        });
        view.findViewById(com.shuttle.mobile.R.id.btnInviteSheetClose).setOnClickListener(v -> sheet.dismiss());
        action.setOnClickListener(v -> {
            String code = input.getText().toString().trim();
            if (code.isEmpty() || busy[0]) return;
            busy[0] = true;
            action.setEnabled(false);
            action.setText("Checking…");
            input.setEnabled(false);
            view.findViewById(com.shuttle.mobile.R.id.btnInviteSheetClose).setEnabled(false);
            sheet.setCancelable(false);
            FleetApi.post("/fleet/preview", row("inviteCode", code), new FleetApi.Callback() {
                @Override public void onSuccess(JSONObject reply) {
                    if (!alive(activity)) return;
                    sheet.dismiss();
                    confirmJoin(activity, current, new Membership(reply), code, changed);
                }
                @Override public void onError(int status, String message) {
                    if (!alive(activity)) return;
                    busy[0] = false;
                    input.setEnabled(true);
                    action.setEnabled(true);
                    action.setText("Join");
                    view.findViewById(com.shuttle.mobile.R.id.btnInviteSheetClose).setEnabled(true);
                    sheet.setCancelable(true);
                    showCodeError(codeField, error, status == 403 ? INVALID_INVITE : message, status == 403);
                }
            });
        });
        sheet.show();
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private static void showCodeError(View field, TextView error, String message, boolean invalid) {
        FieldUi.set(field, invalid ? FieldUi.ERROR : FieldUi.NORMAL);
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }

    private static void confirmJoin(Activity activity, Membership current, Membership target,
                                    String code, Runnable changed) {
        boolean switching = current != null && current.joined && !current.terminalId.equals(target.terminalId);
        String message = switching
                ? "This replaces your connection to " + current.terminalName
                    + ". Your old vehicle assignment will be cleared. You must be off shift."
                : "Your operator will see your account in this terminal's driver list.";
        FleetConfirmation confirmation = new FleetConfirmation(activity,
                switching ? "Switch terminal?" : "Join this terminal?", message,
                switching ? "Switch Terminal" : "Join Terminal", false);
        confirmation.addDetail("Terminal", target.terminalName);
        confirmation.addDetail("Operator", target.operatorName);
        confirmation.show(() -> {
            confirmation.setBusy(true, "Joining…");
            JSONObject body = row("inviteCode", code);
            try { body.put("switchConfirmed", switching); } catch (JSONException impossible) { throw new AssertionError(impossible); }
            FleetApi.post("/fleet/join", body, new FleetApi.Callback() {
                @Override public void onSuccess(JSONObject reply) {
                    if (!alive(activity)) return;
                    confirmation.dismiss();
                    Toast.makeText(activity, "Joined " + FleetApi.text(reply, "terminalName"), Toast.LENGTH_LONG).show();
                    changed.run();
                }
                @Override public void onError(int status, String error) {
                    if (!alive(activity)) return;
                    if (status == 403 && INVALID_INVITE.equals(error)) {
                        confirmation.dismiss();
                        enterCode(activity, current, changed, code, INVALID_INVITE);
                    } else {
                        confirmation.showError(error);
                    }
                }
            });
        });
    }

    public static void showOperator(Activity activity, Membership current, Runnable changed) {
        if (current == null || !current.joined) {
            createTerminal(activity, changed);
            return;
        }
        generateInvite(activity);
    }

    private static void createTerminal(Activity activity, Runnable changed) {
        EditText input = field(activity, "Terminal name");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("Create your terminal")
                .setMessage("Choose the terminal name your drivers will see when they receive your invitation.")
                .setView(input)
                .setPositiveButton("Create terminal", null)
                .setNegativeButton("Later", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.length() < 2) {
                input.setError("Enter a terminal name with at least two characters");
                return;
            }
            setBusy(dialog, input, true, "Creating…", "Create terminal");
            FleetApi.post("/fleet", row("terminalName", name), new FleetApi.Callback() {
                @Override public void onSuccess(JSONObject reply) {
                    if (!alive(activity)) return;
                    dialog.dismiss();
                    changed.run();
                    showOperator(activity, new Membership(reply), changed);
                }
                @Override public void onError(int status, String error) {
                    if (!alive(activity)) return;
                    setBusy(dialog, input, false, "Creating…", "Create terminal");
                    input.setError(error);
                }
            });
        }));
        dialog.show();
    }

    private static void generateInvite(Activity activity) {
        AlertDialog progress = new MaterialAlertDialogBuilder(activity)
                .setTitle("Creating invitation")
                .setMessage("Please wait…").setCancelable(false).show();
        FleetApi.post("/fleet/invites", null, new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject reply) {
                if (!alive(activity)) return;
                progress.dismiss();
                showInvitation(activity, reply);
            }
            @Override public void onError(int status, String error) {
                if (!alive(activity)) return;
                progress.dismiss();
                new MaterialAlertDialogBuilder(activity).setTitle("Could not create invitation")
                        .setMessage(error).setPositiveButton("Close", null).show();
            }
        });
    }

    private static void showInvitation(Activity activity, JSONObject reply) {
        String code = FleetApi.text(reply, "inviteCode");
        android.view.View view = android.view.LayoutInflater.from(activity).inflate(com.shuttle.mobile.R.layout.sheet_invitation, null);
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(activity);
        sheet.setContentView(view);
        ((android.widget.TextView) view.findViewById(com.shuttle.mobile.R.id.tvInviteSheetTitle)).setText("Invite Worker");
        ((android.widget.TextView) view.findViewById(com.shuttle.mobile.R.id.tvInviteSheetDescription))
                .setVisibility(View.GONE);
        view.findViewById(R.id.inviteSettings).setVisibility(View.VISIBLE);
        EditText input = view.findViewById(com.shuttle.mobile.R.id.etInviteSheetCode);
        input.setText(code);
        input.setFocusable(false);
        input.setCursorVisible(false);
        input.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        FieldUi.bindPasswordToggle(input, view.findViewById(com.shuttle.mobile.R.id.btnInviteSheetReveal),
                "Show invitation code", "Hide invitation code");
        view.findViewById(com.shuttle.mobile.R.id.btnInviteSheetCopy).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Fleet invitation", code));
            Toast.makeText(activity, "Invitation code copied", Toast.LENGTH_SHORT).show();
        });
        view.findViewById(com.shuttle.mobile.R.id.btnInviteSheetAction).setVisibility(android.view.View.GONE);
        view.findViewById(com.shuttle.mobile.R.id.btnInviteSheetClose).setOnClickListener(v -> sheet.dismiss());
        bindInviteToggle(activity, view, sheet, reply);
        sheet.show();
    }

    private static void bindInviteToggle(Activity activity, View view, BottomSheetDialog sheet, JSONObject state) {
        SwitchMaterial toggle = view.findViewById(R.id.switchInviteEnabled);
        TextView error = view.findViewById(R.id.tvInviteSheetError);
        String code = FleetApi.text(state, "inviteCode");
        boolean used = state.optBoolean("used", false);
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(state.optBoolean("enabled", false));
        toggle.setEnabled(!used && !code.isEmpty());
        view.findViewById(R.id.btnInviteSheetCopy).setEnabled(!used && !code.isEmpty());
        if (used) {
            error.setText("This code has been used. Reopen Invite Worker for a new code.");
            error.setVisibility(View.VISIBLE);
        }
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            toggle.setEnabled(false);
            error.setVisibility(View.GONE);
            JSONObject body = row("inviteCode", code);
            try { body.put("enabled", enabled); } catch (JSONException impossible) { throw new AssertionError(impossible); }
            FleetApi.put("/fleet/invites", body, new FleetApi.Callback() {
                @Override public void onSuccess(JSONObject reply) {
                    if (alive(activity) && sheet.isShowing()) bindInviteToggle(activity, view, sheet, reply);
                }
                @Override public void onError(int status, String message) {
                    if (!alive(activity) || !sheet.isShowing()) return;
                    error.setText(message);
                    error.setVisibility(View.VISIBLE);
                    FleetApi.get("/fleet/invites", new FleetApi.Callback() {
                        @Override public void onSuccess(JSONObject reply) {
                            if (!alive(activity) || !sheet.isShowing()) return;
                            if (!code.equals(FleetApi.text(reply, "inviteCode"))) {
                                sheet.dismiss();
                                showInvitation(activity, reply);
                                return;
                            }
                            bindInviteToggle(activity, view, sheet, reply);
                        }
                        @Override public void onError(int status, String message) {
                            if (!alive(activity) || !sheet.isShowing()) return;
                            toggle.setOnCheckedChangeListener(null);
                            toggle.setChecked(state.optBoolean("enabled", false));
                            error.setText("Could not check the current code. Reopen Invite Worker to refresh.");
                        }
                    });
                }
            });
        });
    }

    private static EditText field(Activity activity, String hint) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        field.setSingleLine(true);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        field.setPadding(pad, pad / 2, pad, pad / 2);
        return field;
    }

    private static void setBusy(AlertDialog dialog, EditText field, boolean busy,
                                String busyText, String readyText) {
        field.setEnabled(!busy);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(busy ? busyText : readyText);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
        dialog.setCancelable(!busy);
    }

    private static JSONObject row(String key, String value) {
        JSONObject row = new JSONObject();
        try { row.put(key, value); } catch (JSONException impossible) { throw new AssertionError(impossible); }
        return row;
    }

    private static boolean alive(Activity activity) {
        return !activity.isFinishing() && !activity.isDestroyed();
    }
}
