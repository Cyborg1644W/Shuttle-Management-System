package com.shuttle.mobile.ui;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Regression for the fleet format served before /live/fleet was introduced. */
public final class LegacyFleetSnapshotTest {
    public static void main(String[] args) throws Exception {
        LegacyFleetSnapshot legacy = new LegacyFleetSnapshot();
        JSONObject counts = new JSONObject().put("driversActive", "0").put("totalUnits", "0")
                .put("activeShifts", "0").put("activeUnits", "0");
        JSONArray vehicles = new JSONArray(), shifts = new JSONArray();
        legacy.replies[0] = new JSONObject().put("terminalId", "T").put("terminalName", "Central");
        legacy.replies[1] = counts;
        legacy.replies[2] = new JSONObject().put("shuttles", vehicles);
        legacy.replies[3] = new JSONObject().put("shifts", shifts);
        if (legacy.snapshot().getJSONArray("fleet").length() != 1) throw new AssertionError("Expected empty fleet");
        counts.put("totalUnits", 1).put("activeShifts", 1).put("activeUnits", 1).put("driversActive", 1);
        JSONObject vehicle = new JSONObject().put("shuttleId", "A").put("status", "IN_SERVICE")
                .put("driverId", "D").put("plateNumber", "ABC").put("positionTime", 999)
                .put("latitude", "14.5").put("longitude", "121");
        vehicles.put(vehicle);
        shifts.put(new JSONObject().put("shiftId", "S").put("shuttleId", "A").put("driverId", "D")
                .put("driverName", "Ana").put("status", "ACTIVE").put("startTime", 1000)
                .put("endTime", 0).put("terminal", "Central"));
        if (legacy.snapshot().getJSONArray("fleet").getJSONObject(1).has("trail")) throw new AssertionError("Prior shift fix");
        vehicle.put("positionTime", 1010);
        if (!legacy.snapshot().getJSONArray("fleet").getJSONObject(1).getString("trail").equals("1010,14.5,121.0"))
            throw new AssertionError("Missing current fix");
        counts.put("activeShifts", 0);
        try {
            legacy.snapshot();
            throw new AssertionError("Accepted shift transition between reads");
        } catch (JSONException expected) { }
        System.out.println("Legacy empty fleet, active shuttle, previous-shift GPS and inconsistent reads passed.");
    }
}
