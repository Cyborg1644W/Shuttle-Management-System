package com.shuttle.mobile.ui;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static com.shuttle.mobile.ui.FleetStatusMapUiState.*;

public final class FleetStatusMapUiStateTest {
    public static void main(String[] args) {
        long time = 1000;
        FleetStatusMapUiState state = new FleetStatusMapUiState();
        check(!state.loaded && state.camera == Camera.OVERVIEW);
        Totals totals = new Totals(5, 3, 2, 3);
        check(Point.append(null, time, 0, 0) == null);
        check(Point.append(null, time, 0, 121) != null);
        Point first = Point.append(null, time, 14.5, 121);
        check(Point.append(first, time, 1, 2) == first);
        Point decoded = Point.appendTrail(first, "1001,0,0;1002,,121;1003,NaN,121;999,14,121;bad;1004,14.5,121");
        check(decoded.time == 1004 && decoded.previous == first);
        check(Point.appendTrail(decoded, null) == decoded);
        FleetStatusMapUiState inspecting = state.camera(Camera.MANUAL, null);
        check(inspecting.snapshot(Map.of(), "Central", "Central", 14.5, 121, totals).camera == Camera.MANUAL);
        check(Point.append(first, time+1, Double.NaN, 121) == first);
        Point last = first;
        for (int i=1; i<=10000; i++) last = Point.append(last, time+i, 14.5+i/1000000.0, 121);
        check(last.after(first).size() == 10000 && last.after(last).isEmpty());
        Unit a = new Unit("shiftA", "A", "AA", "Driver A", "Central", 1, first, 0, 90);
        Unit b = new Unit("shiftB", "B", "BB", "Driver B", "Central", 2, last, 25, 45);
        Unit waiting = new Unit("shiftC", "C", "", "", "", 3, null, 0, Double.NaN);
        Map<String,Unit> units = new LinkedHashMap<>();
        for (Unit unit : new Unit[]{a,b,waiting}) units.put(unit.shift, unit);
        state = state.snapshot(units, "Central", "Central", 14.5, 121, totals);
        check(state.signal(a, time) == Signal.AT_TERMINAL);
        check(state.signal(b, last.time) == Signal.MOVING && state.signal(waiting, time) == Signal.WAITING);
        state = state.camera(Camera.FOLLOW, a.shift).connection(Connection.OFFLINE);
        check(state.units.get(a.shift).last == first && state.signal(a, time+30_000) == Signal.STALE);
        state = state.camera(Camera.MANUAL, a.shift).snapshot(units, "Central", "Central", Double.NaN, Double.NaN, totals);
        check(state.camera == Camera.MANUAL && a.shift.equals(state.selected) && state.signal(a, time) == Signal.IDLE);
        state = state.camera(Camera.FOLLOW, b.shift).snapshot(Map.of(a.shift,a), "Central", "Central", 14.5, 121, totals);
        check(state.camera == Camera.OVERVIEW && state.selected == null && state.units.size() == 1);
        state = state.snapshot(Map.of(), "Central", "Central", 14.5, 121, totals);
        check(state.totals == totals && state.connection(Connection.RECONNECTING).totals == totals);
        check(state.loaded && state.units.isEmpty() && state.terminalLat == 14.5);
        Set<Integer> colors = new HashSet<>();
        for (int i=0; i<40; i++) check(colors.add(assignColor("S-"+i, colors)));
        System.out.println("Fleet selection, offline retention, signal transitions, color assignment and 10,000-fix deltas passed.");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
