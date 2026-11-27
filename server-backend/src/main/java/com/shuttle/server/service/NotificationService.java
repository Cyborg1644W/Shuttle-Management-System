package com.shuttle.server.service;

import com.shuttle.common.Driver;
import com.shuttle.common.Driver.VehicleNotice;
import com.shuttle.common.ShiftTime;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Csv;
import com.shuttle.server.Repositories;
import com.shuttle.server.repository.DatedShiftRepository.Assignment;
import com.shuttle.server.repository.DatedShiftRepository.StoredAssignment;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The driver sees events committed alongside their vehicle and schedule changes. */
public final class NotificationService {
    private static final String VEHICLE = "vehicle-";
    private static final String SHIFT = "shift-";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private NotificationService() { }

    private record Notice(String id, String title, String message, long createdAt, boolean read) {
        Map<String, Object> toRow() {
            return Csv.obj("id", id, "title", title, "message", message, "createdAt", createdAt, "read", read);
        }
    }

    public static List<Map<String, Object>> list(Worker actor) {
        return notices(actor).stream().sorted(Comparator.comparingLong(Notice::createdAt).reversed())
                .map(Notice::toRow).toList();
    }

    public static Map<String, Object> unread(Worker actor) {
        long vehicle = actor instanceof Driver driver
                ? driver.getVehicleNotices().stream().filter(notice -> !notice.read()).count() : 0;
        long shifts = actor instanceof Driver ? Repositories.datedShifts().notifications(actor.getEmployeeID()).stream()
                .filter(entry -> entry.readAt() == 0).count() : 0;
        return Csv.obj("unread", vehicle + shifts);
    }

    public static Map<String, Object> markRead(Worker actor, String id) throws IOException {
        if (!(actor instanceof Driver driver)) throw new ApiException(403, "Only drivers receive assignment notifications");
        if (id.startsWith(VEHICLE)) {
            String noticeId = id.substring(VEHICLE.length());
            List<VehicleNotice> history = new ArrayList<>(driver.getVehicleNotices());
            for (int i = 0; i < history.size(); i++) {
                VehicleNotice notice = history.get(i);
                if (!noticeId.equals(notice.id())) continue;
                if (!notice.read()) {
                    history.set(i, new VehicleNotice(notice.id(), notice.shuttleId(), notice.createdAt(), true));
                    driver.setVehicleNotices(history);
                    Repositories.workers().saveWorker(driver);
                }
                return Csv.obj("id", id, "read", true);
            }
        } else if (id.startsWith(SHIFT)) {
            StoredAssignment stored = Repositories.datedShifts().find(id.substring(SHIFT.length()));
            if (stored != null && stored.notifiedAt() > 0 && driver.getEmployeeID().equals(stored.assignment().employeeId())) {
                Repositories.datedShifts().markRead(stored);
                return Csv.obj("id", id, "read", true);
            }
        }
        throw new ApiException(404, "Notification not found");
    }

    private static List<Notice> notices(Worker actor) {
        if (!(actor instanceof Driver driver)) return List.of();
        List<Notice> result = new ArrayList<>();
        for (VehicleNotice notice : driver.getVehicleNotices()) {
            result.add(new Notice(VEHICLE + notice.id(), "Vehicle Assigned",
                    "You have been assigned to Shuttle " + notice.shuttleId() + ".",
                    notice.createdAt(), notice.read()));
        }
        for (StoredAssignment stored : Repositories.datedShifts().notifications(driver.getEmployeeID())) {
            Assignment entry = stored.assignment();
            String message = "You have been scheduled for a shift on " + DAY.format(entry.shiftDate())
                    + ", from " + ShiftTime.display(entry.startTime().toString())
                    + " to " + ShiftTime.display(entry.endTime().toString())
                    + ", using Shuttle " + entry.shuttleId() + ".";
            if (stored.removed()) message += " This schedule was removed.";
            result.add(new Notice(SHIFT + entry.id(), "New Shift Assigned", message,
                    stored.notifiedAt(), stored.readAt() > 0));
        }
        return result;
    }
}
