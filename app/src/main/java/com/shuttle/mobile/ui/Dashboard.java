package com.shuttle.mobile.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.shuttle.mobile.R;
import java.text.DateFormat;
import java.util.Date;

final class Dashboard {
    private Dashboard() { }

    static View create(Context context, ViewGroup parent) {
        View root = LayoutInflater.from(context).inflate(R.layout.activity_operator_dashboard, parent, false);
        String name = Session.getName();
        TextView greeting = root.findViewById(R.id.tvDashboardGreeting);
        if (name != null && !name.trim().isEmpty()) {
            greeting.setText(context.getString(R.string.welcome_name, name.trim().split("\\s+")[0]));
        }
        return root;
    }

    static void render(View root, FleetStatusMapUiState state) {
        String shifts = state.loaded ? String.valueOf(state.units.size()) : "—";
        text(root, R.id.tvDashboardActiveShifts, shifts);
        FleetStatusMapUiState.Totals counts = state.totals;
        text(root, R.id.tvDashboardDriversOnDuty, counts == null ? "—" : String.valueOf(counts.onDuty));
        text(root, R.id.tvDashboardTotalUnits, counts == null ? "—" :
                root.getResources().getQuantityString(R.plurals.dashboard_units_total, counts.units, counts.units));
        text(root, R.id.tvDashboardInService, counts == null ? "—" : String.valueOf(counts.operating));
        text(root, R.id.tvDashboardAvailable, counts == null ? "—" : String.valueOf(counts.available));
        weight(root, R.id.barDashboardInService, counts == null ? 0 : counts.operating);
        weight(root, R.id.barDashboardAvailable, counts == null ? 0 : counts.available);
        weight(root, R.id.barDashboardOther, counts == null ? 0 : counts.units - counts.operating - counts.available);
        if (state.connection == FleetStatusMapUiState.Connection.ONLINE) {
            text(root, R.id.tvDashboardDate, DateFormat.getDateInstance(DateFormat.FULL).format(new Date()));
        }
    }

    private static void text(View root, int id, String value) {
        TextView label = root.findViewById(id);
        if (!value.contentEquals(label.getText())) label.setText(value);
    }

    private static void weight(View root, int id, int value) {
        View bar = root.findViewById(id);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) bar.getLayoutParams();
        if (params.weight != value) {
            params.weight = value;
            bar.setLayoutParams(params);
        }
    }
}
