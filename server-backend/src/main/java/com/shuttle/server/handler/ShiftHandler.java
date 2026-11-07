package com.shuttle.server.handler;

import com.shuttle.common.Role;
import com.shuttle.common.ShiftRecord;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Json;
import com.shuttle.server.service.ShiftService;

import java.util.Optional;

import static com.shuttle.server.Json.num;
import static com.shuttle.server.Json.str;
public class ShiftHandler extends BaseHandler {

    @Override
    protected Response process(Request r) throws Exception {
        Worker me = r.worker();
        String action = r.seg(1);

        if (r.is("POST") && "start".equals(action)) {
            requireDriver(me);
            return created(ShiftService.toJson(ShiftService.start(me)));
        }

        if (r.is("POST") && "end".equals(action)) {
            requireDriver(me);
            ShiftRecord done = ShiftService.end(me, num(r.body, "distanceKm"),
                    (int) Json.lng(r.body, "stops"), str(r.body, "route"));
            return ok(ShiftService.toJson(done));
        }

        if (r.is("GET") && "active".equals(action)) {
            String who = r.isOperator() && r.query.containsKey("driverId") ? r.query.get("driverId") : me.getEmployeeID();
            Optional<ShiftRecord> active = ShiftService.active(who);
            return ok(active.isPresent()
                    ? Json.obj("active", true, "shift", ShiftService.toJson(active.get()))
                    : Json.obj("active", false));
        }

        if (r.is("GET") && action == null) {
            String who = r.isOperator() ? r.query.get("driverId") : me.getEmployeeID();
            return ok(Json.obj("shifts", ShiftService.toJson(ShiftService.history(who))));
        }

        if (r.is("GET") && action != null) {
            ShiftRecord shift = ShiftService.get(action);
            if (!r.isOperator() && !shift.getDriverId().equals(me.getEmployeeID())) {
                throw new ApiException(403, "That shift belongs to someone else");
            }
            return ok(ShiftService.toJson(shift));
        }

        throw new ApiException(405, "Method Not Allowed");
    }

    private static void requireDriver(Worker w) {
        if (w.getRole() != Role.DRIVER) {
            throw new ApiException(403, "Only drivers can do this");
        }
    }
}
