package com.shuttle.mobile.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.shuttle.mobile.R;
import com.shuttle.mobile.client.ApiClient;

import org.json.JSONObject;

public final class Dashboard {

    private Dashboard() {
    }

    public static View create(Context context, ViewGroup parent) {
        View root = LayoutInflater.from(context)
                .inflate(R.layout.activity_operator_dashboard, parent, false);

        // Show zeros right away, then fill in the real numbers from the server.
        show(root, 0, 0, 0, 0);

        final Context appContext = context.getApplicationContext();
        ApiClient.getInstance().getDashboard(new ApiClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject data) {
                show(root,
                        data.optInt("totalShifts"),
                        data.optInt("activeUnits"),
                        data.optInt("inactiveUnits"),
                        data.optInt("maintenanceUnits"));
            }

            @Override
            public void onError(String message) {
                Toast.makeText(appContext,
                        "Could not load dashboard: " + message, Toast.LENGTH_SHORT).show();
            }
        });

        return root;
    }

    private static void show(View root, int totalShifts, int active, int inactive, int maintenance) {
        int totalUnits = active + inactive + maintenance;

        ((TextView) root.findViewById(R.id.tvTotalShifts)).setText(String.valueOf(totalShifts));
        ((TextView) root.findViewById(R.id.tvTotalUnits)).setText(String.valueOf(totalUnits));

        setStatus(root, R.id.tvActiveCount, R.id.tvActivePct, active, totalUnits);
        setStatus(root, R.id.tvInactiveCount, R.id.tvInactivePct, inactive, totalUnits);
        setStatus(root, R.id.tvMaintCount, R.id.tvMaintPct, maintenance, totalUnits);

        setBarWeight(root.findViewById(R.id.barActive), active);
        setBarWeight(root.findViewById(R.id.barInactive), inactive);
        setBarWeight(root.findViewById(R.id.barMaint), maintenance);
    }

    private static void setStatus(View root, int countId, int pctId, int count, int total) {
        int pct = total > 0 ? Math.round(100f * count / total) : 0;
        ((TextView) root.findViewById(countId)).setText(String.valueOf(count));
        ((TextView) root.findViewById(pctId)).setText(pct + "%");
    }

    private static void setBarWeight(View bar, int weight) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
        lp.weight = weight;
        bar.setLayoutParams(lp);
    }
}