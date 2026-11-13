package com.shuttle.server.handler;

import com.shuttle.common.Role;
import com.shuttle.common.ShiftRecord;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Csv;
import com.shuttle.server.service.ShiftService;

import java.util.Collections;
import java.util.Optional;

import static com.shuttle.server.Csv.num;
import static com.shuttle.server.Csv.str;

public class ShiftHandler extends BaseHandler {

    @Override
    protected Response process(Request r) throws Exception {
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
            String who = r.isOperator() && r.query.containsKey("driverId") ? r.query.get("driverId") : me.getEmployeeID();
            Optional<ShiftRecord> active = ShiftService.active(who);
            // one row if a shift is running, otherwise an empty body
            return ok(active.isPresent()
                    ? Collections.singletonList(ShiftService.toRow(active.get()))
                    : Collections.emptyList());
        }

        if (r.is("GET") && action == null) {
            String who = r.isOperator() ? r.query.get("driverId") : me.getEmployeeID();
            return ok(ShiftService.toRow(ShiftService.history(who)));
        }

        if (r.is("GET") && action != null) {
            ShiftRecord shift = ShiftService.get(action);
            if (!r.isOperator() && !shift.getDriverId().equals(me.getEmployeeID())) {
                throw new ApiException(403, "That shift belongs to someone else");
            }
            return ok(ShiftService.toRow(shift));
        }

        throw new ApiException(405, "Method Not Allowed");
    }

    private static void requireDriver(Worker w) {
        if (w.getRole() != Role.DRIVER) {
            throw new ApiException(403, "Only drivers can do this");
        }
    }
}
