package com.shuttle.mobile.ui;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.shuttle.mobile.R;
import com.shuttle.mobile.client.ApiClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Dashboard {
    private Dashboard() { }

    public static View create(Context context, ViewGroup parent) {
        View root = LayoutInflater.from(context).inflate(R.layout.activity_operator_dashboard, parent, false);
        Controller controller = new Controller(root);
        root.setTag(controller);
        root.findViewById(R.id.btnDashboardRetry).setOnClickListener(view -> controller.refresh());
        controller.refresh();
        return root;
    }

    public static void refresh(View root) {
        if (root != null && root.getTag() instanceof Controller) {
            ((Controller) root.getTag()).refresh();
        }
    }

    private static final class Controller {
        private final View root;
        private int generation;
        private int pending;
        private boolean failed;

        Controller(View root) { this.root = root; }

        private boolean accepts(int request) {
            Context context = root.getContext();
            return request == generation && (!(context instanceof Activity)
                    || (!((Activity) context).isFinishing() && !((Activity) context).isDestroyed()));
        }

        void refresh() {
            final int request = ++generation;
            pending = 2;
            failed = false;
            String name = Session.getName();
            name = name == null ? "" : name.trim();
            ((TextView) root.findViewById(R.id.tvDashboardGreeting)).setText(name.isEmpty()
                    ? "Welcome!" : root.getContext().getString(R.string.welcome_name, name.split("\\s+")[0]));
            ((TextView) root.findViewById(R.id.tvDashboardDate)).setText(
                    new SimpleDateFormat("'As of' EEEE, MMM d, yyyy", Locale.getDefault()).format(new Date()));
            TextView state = root.findViewById(R.id.tvDashboardLoadState);
            state.setText(R.string.dashboard_loading);
            state.setVisibility(View.VISIBLE);
            root.findViewById(R.id.btnDashboardRetry).setVisibility(View.GONE);
            ApiClient.getInstance().getDashboard(new ApiClient.Callback<JSONObject>() {
                @Override public void onSuccess(JSONObject data) {
                    if (!accepts(request)) return;
                    if (!data.has("activeShifts") || !data.has("driversActive")) {
                        finish(request, false);
                        return;
                    }
                    number(R.id.tvDashboardActiveShifts, data.optInt("activeShifts"));
                    number(R.id.tvDashboardDriversOnDuty, data.optInt("driversActive"));
                    finish(request, true);
                }
                @Override public void onError(String message) { finish(request, false); }
            });
            // AVAILABLE is distinct from OFFLINE. The old dashboard grouped both as "inactive".
            ApiClient.getInstance().getShuttles(new ApiClient.Callback<JSONArray>() {
                @Override public void onSuccess(JSONArray data) {
                    if (!accepts(request)) return;
                    int operating = 0;
                    int available = 0;
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject unit = data.optJSONObject(i);
                        if (unit == null) continue;
                        String status = unit.optString("status");
                        if ("IN_SERVICE".equals(status)) operating++;
                        if ("AVAILABLE".equals(status)) available++;
                    }
                    int total = data.length();
                    ((TextView) root.findViewById(R.id.tvDashboardTotalUnits)).setText(root.getResources()
                            .getQuantityString(R.plurals.dashboard_units_total, total, total));
                    number(R.id.tvDashboardInService, operating);
                    number(R.id.tvDashboardAvailable, available);
                    weight(R.id.barDashboardInService, operating);
                    weight(R.id.barDashboardAvailable, available);
                    weight(R.id.barDashboardOther, data.length() - operating - available);
                    finish(request, true);
                }
                @Override public void onError(String message) { finish(request, false); }
            });
        }

        private void finish(int request, boolean success) {
            if (!accepts(request)) return;
            failed |= !success;
            pending--;
            if (pending == 0) {
                TextView state = root.findViewById(R.id.tvDashboardLoadState);
                if (failed) state.setText(R.string.dashboard_error);
                state.setVisibility(failed ? View.VISIBLE : View.GONE);
                root.findViewById(R.id.btnDashboardRetry).setVisibility(failed ? View.VISIBLE : View.GONE);
            }
        }

        private void number(int id, int value) {
            ((TextView) root.findViewById(id)).setText(String.valueOf(Math.max(0, value)));
        }

        private void weight(int id, int value) {
            View bar = root.findViewById(id);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) bar.getLayoutParams();
            params.weight = Math.max(0, value);
            bar.setLayoutParams(params);
        }
    }
}
