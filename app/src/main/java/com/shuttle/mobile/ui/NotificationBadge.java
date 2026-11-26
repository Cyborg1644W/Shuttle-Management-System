package com.shuttle.mobile.ui;

import android.view.View;

import org.json.JSONObject;

import java.util.Objects;

/** Shows the signed-in account's unread state in either role's header. */
final class NotificationBadge {
    private final View dot;
    private String token;
    private boolean loading;
    private int generation;

    NotificationBadge(View dot) {
        this.dot = dot;
    }

    void refresh() {
        String accountToken = Session.getToken();
        if (!Objects.equals(token, accountToken)) {
            pause();
            token = accountToken;
            dot.setVisibility(View.GONE);
        }
        if (loading || accountToken == null) return;

        loading = true;
        int request = ++generation;
        FleetApi.get("/notifications/unread", new FleetApi.Callback() {
            @Override public void onSuccess(JSONObject reply) {
                if (request != generation || !Objects.equals(accountToken, Session.getToken())) return;
                loading = false;
                int unread = reply.optInt("unread", -1);
                if (unread >= 0) dot.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
            }

            @Override public void onError(int status, String message) {
                if (request == generation) loading = false;
            }
        });
    }

    void pause() {
        generation++;
        loading = false;
    }
}
