package com.shuttle.mobile.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.shuttle.mobile.R;

public final class Dashboard {

    private static final int TOTAL_SHIFTS = 128;
    private static final int ACTIVE_UNITS = 6;
    private static final int INACTIVE_UNITS = 3;
    private static final int MAINTENANCE_UNITS = 2;

    private Dashboard() {
    }

    public static View create(Context context, ViewGroup parent) {
        View root = LayoutInflater.from(context)
                .inflate(R.layout.tab_operator_dashboard, parent, false);

        int totalUnits = ACTIVE_UNITS + INACTIVE_UNITS + MAINTENANCE_UNITS;

        ((TextView) root.findViewById(R.id.tvTotalShifts)).setText(String.valueOf(TOTAL_SHIFTS));
        ((TextView) root.findViewById(R.id.tvTotalUnits)).setText(String.valueOf(totalUnits));

        setStatus(root, R.id.tvActiveCount, R.id.tvActivePct, ACTIVE_UNITS, totalUnits);
        setStatus(root, R.id.tvInactiveCount, R.id.tvInactivePct, INACTIVE_UNITS, totalUnits);
        setStatus(root, R.id.tvMaintCount, R.id.tvMaintPct, MAINTENANCE_UNITS, totalUnits);

        setBarWeight(root.findViewById(R.id.barActive), ACTIVE_UNITS);
        setBarWeight(root.findViewById(R.id.barInactive), INACTIVE_UNITS);
        setBarWeight(root.findViewById(R.id.barMaint), MAINTENANCE_UNITS);

        return root;
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