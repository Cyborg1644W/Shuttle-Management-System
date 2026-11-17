package com.shuttle.mobile.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shuttle.mobile.client.ApiClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

/** Account connections are explicit: registration alone never joins a terminal. */
public final class FleetConnectionUi {
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
        if (current != null && current.joined) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(current.terminalName)
                    .setMessage("Managed by " + current.operatorName
                            + ".\n\nYou belong to one terminal at a time. Joining another terminal replaces this connection. End your shift before switching.")
                    .setPositiveButton("Join another terminal", (d, w) -> enterCode(activity, current, changed))
                    .setNegativeButton("Close", null).show();
        } else {
            enterCode(activity, current, changed);
        }
    }

    private static void enterCode(Activity activity, Membership current, Runnable changed) {
        EditText input = field(activity, "Invitation code");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("Join a terminal")
                .setMessage("Ask your terminal operator to share a fleet invitation code. Enter it here to see which terminal you will join.")
                .setView(input)
                .setPositiveButton("Check invitation", null)
                .setNegativeButton("Later", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code = input.getText().toString().trim();
            if (code.isEmpty()) {
                input.setError("Enter the code your operator shared");
                return;
            }
            setBusy(dialog, input, true, "Checking…", "Check invitation");
            FleetApi.post("/fleet/preview", row("inviteCode", code), new FleetApi.Callback() {
                @Override public void onSuccess(JSONObject reply) {
                    if (!alive(activity)) return;
                    dialog.dismiss();
                    confirmJoin(activity, current, new Membership(reply), code, changed);
                }
                @Override public void onError(int status, String message) {
                    if (!alive(activity)) return;
                    setBusy(dialog, input, false, "Checking…", "Check invitation");
                    input.setError(message);
                }
            });
        }));
        dialog.show();
    }

    private static void confirmJoin(Activity activity, Membership current, Membership target,
                                    String code, Runnable changed) {
        boolean switching = current != null && current.joined && !current.terminalId.equals(target.terminalId);
        String message = "Terminal: " + target.terminalName + "\nOperator: " + target.operatorName;
        message += switching
                ? "\n\nThis replaces your connection to " + current.terminalName
                    + ". Your old vehicle assignment will be cleared. You must be off shift."
                : "\n\nYour operator will see your account in this terminal's driver list.";
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(switching ? "Switch terminal?" : "Join this terminal?")
                .setMessage(message)
                .setPositiveButton(switching ? "Switch terminal" : "Join terminal", null)
                .setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            dialog.setCancelable(false);
            JSONObject body = row("inviteCode", code);
            try { body.put("switchConfirmed", switching); } catch (JSONException impossible) { throw new AssertionError(impossible); }
            FleetApi.post("/fleet/join", body, new FleetApi.Callback() {
                @Override public void onSuccess(JSONObject reply) {
                    if (!alive(activity)) return;
                    dialog.dismiss();
                    Toast.makeText(activity, "Joined " + FleetApi.text(reply, "terminalName"), Toast.LENGTH_LONG).show();
                    changed.run();
                }
                @Override public void onError(int status, String error) {
                    if (!alive(activity)) return;
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                    dialog.setCancelable(true);
                    dialog.setMessage(messageForError(target, error));
                }
            });
        }));
        dialog.show();
    }

    private static String messageForError(Membership target, String error) {
        return "Terminal: " + target.terminalName + "\nOperator: " + target.operatorName + "\n\n" + error;
    }

    public static void showOperator(Activity activity, Membership current, Runnable changed) {
        if (current == null || !current.joined) {
            createTerminal(activity, changed);
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(current.terminalName)
                .setMessage("Share an invitation with a driver who already has an account. They can join from their own phone using Join terminal.\n\nEach code works once and expires after 48 hours.")
                .setPositiveButton("Invite a driver", (d, w) -> generateInvite(activity))
                .setNegativeButton("Close", null).show();
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
        String name = FleetApi.text(reply, "terminalName");
        long expiresAt = reply.optLong("expiresAt", 0L);
        String expiration = expiresAt > 0
                ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(expiresAt))
                : "48 hours after creation";
        String share = "Join " + name + " on FleetFlow.\n\nInvitation code: " + code
                + "\nServer address: " + ApiClient.getBaseUrl()
                + "\nUse this address in Server connection on the sign-in screen."
                + "\n\nSign in as a driver, tap Join terminal, and enter this code."
                + "\nValid for one driver. Expires: " + expiration + ".";
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("Invite a driver")
                .setMessage(name + "\n\n" + code + "\n\nValid once. Expires: " + expiration
                        + ".\n\nBoth phones must use the same fleet server.")
                .setNeutralButton("Copy code", null)
                .setPositiveButton("Share", null)
                .setNegativeButton("Done", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Fleet invitation", code));
                Toast.makeText(activity, "Invitation code copied", Toast.LENGTH_SHORT).show();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, share);
                activity.startActivity(Intent.createChooser(intent, "Send fleet invitation"));
            });
        });
        dialog.show();
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
