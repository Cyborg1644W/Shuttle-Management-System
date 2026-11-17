package com.shuttle.server.handler;

import com.shuttle.common.Driver;
import com.shuttle.common.ShuttleUnit;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Repositories;
import com.shuttle.server.service.TerminalService;
import com.shuttle.server.Csv;
import com.shuttle.server.service.FleetService;
import com.shuttle.server.service.TrackingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.shuttle.server.Csv.filled;
import static com.shuttle.server.Csv.lng;
import static com.shuttle.server.Csv.num;
import static com.shuttle.server.Csv.str;

public class ShuttleLocationHandler extends BaseHandler {

    @Override
    protected Response process(Request r) throws Exception {
        synchronized (Repositories.LOCK) {
            return processScoped(r);
        }
    }

    private Response processScoped(Request r) throws Exception {
        String id = r.seg(1);
        String action = r.seg(2);
        if (id != null) {
            TerminalService.requireShuttle(r.worker(), id);
        }

        if (id == null) {
            if (r.is("GET")) {
                List<Map<String, Object>> shuttles = new ArrayList<>();
                for (ShuttleUnit shuttle : Repositories.shuttles().getAllShuttles()) {
                    if (TerminalService.canAccessShuttle(r.worker(), shuttle.getShuttleId())) {
                        shuttles.add(FleetService.toRow(shuttle));
                    }
                }
                return ok(shuttles);
            }
            if (r.is("POST")) {
                r.requireOperator();
                TerminalService.requireJoined(r.worker());
                TerminalService.requireShuttleCreationAllowed(r.worker(), str(r.body, "shuttleId").trim());
                ShuttleUnit shuttle = FleetService.add(r.body);
                TerminalService.attachShuttle(r.worker(), shuttle.getShuttleId());
                return created(FleetService.toRow(shuttle));
            }
        } else if (action == null) {
            if (r.is("GET")) {
                return ok(FleetService.get(id));
            }
            if (r.is("DELETE")) {
                r.requireOperator();
                FleetService.remove(id);
                TerminalService.detachShuttle(r.worker(), id);
                return ok(Csv.obj("removed", true));
            }
        } else if (action.equals("location") && r.is("POST")) {
            Worker me = r.worker();
            if (me instanceof Driver && !id.equalsIgnoreCase(nz(((Driver) me).getAssignedShuttleId()))) {
                throw new ApiException(403, "You can only report the position of your own vehicle");
            }
            if (!filled(r.body, "latitude") || !filled(r.body, "longitude")) {
                throw new ApiException(400, "latitude and longitude are required");
            }
            TrackingService.updateLocation(id, num(r.body, "latitude"), num(r.body, "longitude"),
                    num(r.body, "speed"), lng(r.body, "timestamp"));
            return ok(Csv.obj("status", "Location updated"));
        } else if (action.equals("status") && r.is("PUT")) {
            r.requireOperator();
            return ok(FleetService.toRow(FleetService.setStatus(id, str(r.body, "status"))));
        }

        throw new ApiException(404, "Endpoint Not Found");
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
