package com.shuttle.mobile.gps;

import java.util.Deque;

/** Standalone JVM regression coverage for fix ordering, missed updates and offline completion. */
public final class LiveMapUiStateTest {
    public static void main(String[] args) {
        long start = 1_000;
        LiveMapUiState trip = new LiveMapUiState("shift-1", "unit-1", start, 0, false, null);
        check(LiveMapUiState.EMPTY.phase(start) == LiveMapUiState.Phase.PRE_SHIFT);
        check(trip.phase(start) == LiveMapUiState.Phase.WAITING_FOR_GPS);
        check(trip.append(0, 0, start - 1, 0, 0) == trip);
        check(trip.append(Double.NaN, 0, start, 0, 0) == trip);
        check(trip.append(0, 181, start, 0, 0) == trip);
        trip = trip.append(0, 0, start, 0, Float.NaN);
        check(trip.phase(start + 29_999) == LiveMapUiState.Phase.TRACKING);
        check(trip.phase(start + 30_000) == LiveMapUiState.Phase.WEAK_SIGNAL);
        LiveMapUiState.Fix first = trip.last;
        LiveMapUiState jitter = trip.append(0, 0.00001, start + 5000, 0, 0);
        check(jitter.last.distanceKm == 0 && jitter.last.lon != first.lon);
        trip = trip.append(0, 0, start + 30_001, 0, Float.NaN);
        check(trip.phase(trip.last.time) == LiveMapUiState.Phase.TRACKING);
        check(trip.append(1, 1, start, 0, 0) == trip);
        check(trip.routeString().equals("0.0,0.0"));
        for (int i = 1; i <= 10_000; i++) {
            trip = trip.append(0, i / 100_000.0, trip.last.time + 5000, 1, 90);
        }
        Deque<LiveMapUiState.Fix> missed = trip.fixesAfter(first);
        check(missed.size() == 10_001 && missed.getFirst().previous == first && missed.getLast() == trip.last);
        check(trip.fixesAfter(trip.last).isEmpty());
        check(trip.last.distanceKm > 11 && trip.last.distanceKm < 11.2);
        LiveMapUiState ended = trip.endAt(trip.last.time + 1, true);
        check(ended.phase(ended.end) == LiveMapUiState.Phase.ENDED && ended.pendingUpload);
        check(ended.append(0, 1, ended.end + 1, 0, 0) == ended);
        String route = ended.routeString();
        check(route.split(";").length == 10_001 && route.endsWith("0.0,0.1"));
        check(ended.endAt(ended.end, false).last == trip.last);
        System.out.println("Live map state, stale GPS, incremental rendering and 10,000-point completion passed.");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
