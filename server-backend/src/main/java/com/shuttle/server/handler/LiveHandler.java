package com.shuttle.server.handler;

import com.shuttle.common.ShuttleUnit;
import com.shuttle.server.ApiException;
import com.shuttle.server.Repositories;
import com.shuttle.server.repository.ShuttleRepository;
import com.shuttle.server.service.FleetService;
import com.shuttle.server.service.TrackingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LiveHandler extends BaseHandler {

    @Override
    protected Response process(Request r) {
        if (!r.is("GET")) {
            throw new ApiException(405, "Method Not Allowed");
        }
        boolean all = "true".equalsIgnoreCase(r.query.get("all"));

        List<Map<String, Object>> out = new ArrayList<>();
        for (ShuttleUnit s : Repositories.shuttles().getAllShuttles()) {
            boolean inService = ShuttleRepository.STATUS_IN_SERVICE.equals(s.getCurrentStatus());
            if (TrackingService.get(s.getShuttleId()) != null && (all || inService)) {
                out.add(FleetService.toRow(s));
            }
        }
        return ok(out);
    }
}
