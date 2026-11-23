package com.shuttle.server.handler;

import com.shuttle.common.Role;
import com.shuttle.common.ShiftRecord;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Csv;
import com.shuttle.server.Repositories;
import com.shuttle.server.service.ShiftService;
import com.shuttle.server.service.TerminalService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static com.shuttle.server.Csv.num;
import static com.shuttle.server.Csv.str;

public class ShiftHandler extends BaseHandler {

    @Override
    protected Response process(Request r) throws Exception {
        synchronized (Repositories.LOCK) {
            Worker me = r.worker();
            String action = r.seg(1);

            if (r.is("POST") && "start".equals(action)) {
                requireDriver(me);
                return created(ShiftService.toRow(ShiftService.start(me)));
            }

            if (r.is("POST") && "end".equals(action)) {
                requireDriver(me);
                ShiftRecord done = ShiftService.end(me, num(r.body, "distanceKm"),
                        (int) Csv.lng(r.body, "stops"), str(r.body, "route"));
                return ok(ShiftService.toRow(done));
            }

            if (r.is("GET") && "active".equals(action)) {
                String who = r.isOperator() && r.query.containsKey("driverId")
                        ? r.query.get("driverId") : me.getEmployeeID();
                TerminalService.requireWorker(me, who);
                Optional<ShiftRecord> active = ShiftService.active(who);
                return ok(active.isPresent() && canRead(me, active.get())
                        ? Collections.singletonList(ShiftService.toRow(active.get()))
                        : Collections.emptyList());
            }

            if (r.is("GET") && action == null) {
                String who = r.isOperator() ? r.query.get("driverId") : me.getEmployeeID();
                if (who != null && !who.isEmpty()) {
                    TerminalService.requireWorker(me, who);
                }
                List<ShiftRecord> visible = new ArrayList<>();
                for (ShiftRecord shift : ShiftService.history(who)) {
                    if (canRead(me, shift)) {
                        visible.add(shift);
                    }
                }
                return ok(ShiftService.toRow(visible));
            }

            if (r.is("GET") && action != null) {
                ShiftRecord shift = ShiftService.get(action);
                if (!canRead(me, shift)) {
                    throw new ApiException(403, "That shift belongs to another terminal or driver");
                }
                return ok(ShiftService.toRow(shift));
            }

            throw new ApiException(405, "Method Not Allowed");
        }
    }

    private static boolean canRead(Worker actor, ShiftRecord shift) {
        if (actor.getRole() == Role.DRIVER) {
            return shift.getDriverId().equals(actor.getEmployeeID());
        }
        return TerminalService.canAccessWorker(actor, shift.getDriverId())
                && TerminalService.canAccessShuttle(actor, shift.getShuttleId());
    }

    private static void requireDriver(Worker w) {
        if (w.getRole() != Role.DRIVER) {
            throw new ApiException(403, "Only drivers can do this");
        }
    }
}
