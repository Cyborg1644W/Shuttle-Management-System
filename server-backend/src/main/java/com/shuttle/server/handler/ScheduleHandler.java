package com.shuttle.server.handler;

import com.shuttle.server.ApiException;
import com.shuttle.server.Repositories;
import com.shuttle.server.service.TerminalService;
import com.shuttle.server.service.ReportService;

public class ScheduleHandler extends BaseHandler {

    @Override
    protected Response process(Request r) throws Exception {
        synchronized (Repositories.LOCK) {
            return processScoped(r);
        }
    }

    private Response processScoped(Request r) throws Exception {
        if (!"schedule".equals(r.seg(0)) || r.seg(2) != null) {
            throw new ApiException(404, "Endpoint Not Found");
        }
        if (r.seg(1) != null) {
            if (!r.is("DELETE")) throw new ApiException(405, "Method Not Allowed");
            r.requireOperator();
            return ok(ReportService.removeSchedule(r.worker(), r.seg(1),
                    Integer.parseInt(r.query.get("shift")), r.query.get("days"), r.query.get("date")));
        }
        if (r.is("GET")) {
            return ok(ReportService.schedule(r.worker()));
        }
        if (r.is("PUT")) {
            r.requireOperator();
            if (r.rows.isEmpty()) {
                throw new ApiException(400, "Send a CSV table with the columns employeeId,shift,days");
            }
            return ok(ReportService.replaceSchedule(r.worker(), r.rows));
        }
        throw new ApiException(405, "Method Not Allowed");
    }
}
