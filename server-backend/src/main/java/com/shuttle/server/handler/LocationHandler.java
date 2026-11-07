package com.shuttle.server.handler;

import com.shuttle.common.Driver;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Json;
import com.shuttle.server.service.TrackingService;

import static com.shuttle.server.Json.has;
import static com.shuttle.server.Json.lng;
import static com.shuttle.server.Json.num;
import static com.shuttle.server.Json.str;
public class LocationHandler extends BaseHandler {

    @Override
    protected Response process(Request r) {
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

        if (!has(r.body, "latitude") || !has(r.body, "longitude")) {
            throw new ApiException(400, "latitude and longitude are required");
        }
        TrackingService.updateLocation(shuttleId, num(r.body, "latitude"), num(r.body, "longitude"),
                num(r.body, "speed"), lng(r.body, "timestamp"));
        return ok(Json.obj("status", "Location updated"));
    }
}
