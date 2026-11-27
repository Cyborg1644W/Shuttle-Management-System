package com.shuttle.server.handler;

import com.shuttle.server.ApiException;
import com.shuttle.server.Repositories;
import com.shuttle.server.service.DatedShiftService;

public final class DatedShiftHandler extends BaseHandler {
    @Override protected Response process(Request request) throws Exception {
        if (!"scheduled-shifts".equals(request.seg(0)) || request.seg(2) != null) {
            throw new ApiException(404, "Endpoint Not Found");
        }
        synchronized (Repositories.LOCK) {
            if (request.seg(1) != null) {
                if (!request.is("DELETE")) throw new ApiException(405, "Method Not Allowed");
                request.requireOperator();
                return ok(DatedShiftService.remove(request.worker(), request.seg(1)));
            }
            if (request.is("GET")) {
                return ok(DatedShiftService.list(request.worker(), request.query.get("from"), request.query.get("to")));
            }
            if (request.is("POST")) {
                request.requireOperator();
                if (request.rows.size() != 1) throw new ApiException(400, "Send one scheduled assignment at a time");
                return created(DatedShiftService.create(request.worker(), request.body));
            }
            throw new ApiException(405, "Method Not Allowed");
        }
    }
}
