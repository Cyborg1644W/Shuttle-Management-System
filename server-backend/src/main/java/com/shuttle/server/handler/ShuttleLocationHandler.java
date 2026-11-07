package com.shuttle.server.handler;

import com.shuttle.common.Driver;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Json;
import com.shuttle.server.service.FleetService;
import com.shuttle.server.service.TrackingService;

import static com.shuttle.server.Json.has;
import static com.shuttle.server.Json.lng;
import static com.shuttle.server.Json.num;
import static com.shuttle.server.Json.str;
public class ShuttleLocationHandler extends BaseHandler {

    @Override
    protected Response process(Request r) throws Exception {
        String id = r.seg(1);
        String action = r.seg(2);

        if (id == null) {
            if (r.is("GET")) {
                return ok(Json.obj("shuttles", FleetService.list()));
            }
            if (r.is("POST")) {
                r.requireOperator();
                return created(FleetService.toJson(FleetService.add(r.body)));
            }
        } else if (action == null) {
            if (r.is("GET")) {
                return ok(FleetService.get(id));
            }
            if (r.is("DELETE")) {
                r.requireOperator();
                FleetService.remove(id);
                return ok(Json.obj("removed", true));
            }
        } else if (action.equals("location") && r.is("POST")) {
            Worker me = r.worker();
            if (me instanceof Driver && !id.equalsIgnoreCase(nz(((Driver) me).getAssignedShuttleId()))) {
                throw new ApiException(403, "You can only report the position of your own vehicle");
            }
            if (!has(r.body, "latitude") || !has(r.body, "longitude")) {
                throw new ApiException(400, "latitude and longitude are required");
            }
            TrackingService.updateLocation(id, num(r.body, "latitude"), num(r.body, "longitude"),
                    num(r.body, "speed"), lng(r.body, "timestamp"));
            return ok(Json.obj("status", "Location updated"));
        } else if (action.equals("status") && r.is("PUT")) {
            r.requireOperator();
            return ok(FleetService.toJson(FleetService.setStatus(id, str(r.body, "status"))));
        }

        throw new ApiException(404, "Endpoint Not Found");
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
