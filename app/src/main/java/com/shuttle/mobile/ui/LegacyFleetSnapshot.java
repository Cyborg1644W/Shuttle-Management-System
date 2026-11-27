package com.shuttle.mobile.ui;

import com.shuttle.common.ValidationUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** Adapts servers that expose only the most recent GPS fix for each shuttle. */
final class LegacyFleetSnapshot {
    static final String[] PATHS = {"/fleet", "/dashboard", "/shuttles", "/shift?summary=true"};
    final JSONObject[] replies = new JSONObject[PATHS.length];

    JSONObject snapshot() throws JSONException {
        JSONObject terminal = replies[0], counts = replies[1];
        JSONArray fleet = replies[2].getJSONArray("shuttles"), shifts = replies[3].getJSONArray("shifts");
        JSONObject header = new JSONObject().put("type", "fleet")
                .put("terminalId", terminal.getString("terminalId")).put("terminal", terminal.getString("terminalName"))
                .put("terminalLatitude", terminal.opt("terminalLatitude")).put("terminalLongitude", terminal.opt("terminalLongitude"))
                .put("driversActive", counts.getInt("driversActive")).put("totalUnits", fleet.length());
        Map<String, JSONObject> vehicles = new HashMap<>();
        int available = 0, operating = 0;
        for (int i = 0; i < fleet.length(); i++) {
            JSONObject unit = fleet.getJSONObject(i);
            vehicles.put(unit.getString("shuttleId"), unit);
            String status = unit.getString("status");
            if ("AVAILABLE".equals(status)) available++;
            if ("IN_SERVICE".equals(status)) operating++;
        }
        JSONArray rows = new JSONArray().put(header);
        for (int i = 0; i < shifts.length(); i++) {
            JSONObject shift = shifts.getJSONObject(i);
            if (!"ACTIVE".equals(shift.getString("status")) || shift.getLong("endTime") != 0) continue;
            String id = shift.getString("shuttleId");
            JSONObject unit = vehicles.get(id);
            if (unit == null || !"IN_SERVICE".equals(unit.getString("status"))
                    || !shift.getString("driverId").equals(unit.getString("driverId"))) throw new JSONException("Shift changed");
            JSONObject row = new JSONObject().put("type", "unit").put("shuttleId", id)
                    .put("shiftId", shift.getString("shiftId")).put("driverName", shift.getString("driverName"))
                    .put("plateNumber", unit.getString("plateNumber")).put("terminal", shift.getString("terminal"));
            long time = unit.optLong("positionTime", 0);
            double lat = unit.optDouble("latitude", Double.NaN), lon = unit.optDouble("longitude", Double.NaN);
            if (time > 0 && time >= shift.getLong("startTime") && ValidationUtil.isTrackingFix(lat, lon)) {
                row.put("positionTime", time).put("speedKmh", unit.optDouble("speedKmh", 0))
                        .put("trail", time + "," + lat + "," + lon);
            }
            rows.put(row);
        }
        int active = rows.length() - 1;
        if (active != counts.getInt("activeShifts") || fleet.length() != counts.getInt("totalUnits")
                || operating != counts.getInt("activeUnits")) throw new JSONException("Fleet changed");
        header.put("availableUnits", available).put("operatingUnits", operating).put("activeShifts", active);
        return new JSONObject().put("fleet", rows);
    }
}
