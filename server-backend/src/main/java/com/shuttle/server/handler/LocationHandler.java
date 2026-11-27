package com.shuttle.server.handler;

import com.shuttle.common.Driver;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Repositories;
import com.shuttle.server.service.TerminalService;
import com.shuttle.server.Csv;
import com.shuttle.server.service.TrackingService;

import static com.shuttle.server.Csv.str;

public class LocationHandler extends BaseHandler {

    @Override
    protected Response process(Request r) throws Exception {
        synchronized (Repositories.LOCK) {
            return processScoped(r);
        }
    }

    private Response processScoped(Request r) throws Exception {
        if (!"location".equals(r.seg(0)) || r.seg(1) != null) throw new ApiException(404, "Endpoint Not Found");
        if (!r.is("POST")) {
            throw new ApiException(405, "Method Not Allowed");
        }
        Worker me = r.worker();

        String shuttleId;
        if (me instanceof Driver) {
            shuttleId = ((Driver) me).getAssignedShuttleId();
            if (shuttleId == null || shuttleId.isEmpty()) {
                throw new ApiException(409, "No vehicle assigned to you");
            }
        } else {
            shuttleId = str(r.body, "shuttleId");
        }

        TerminalService.requireShuttle(me, shuttleId);
        boolean accepted = TrackingService.report(shuttleId, r.body);
        return ok(Csv.obj("status", accepted ? "Location updated" : "Location already superseded"));
    }
}
