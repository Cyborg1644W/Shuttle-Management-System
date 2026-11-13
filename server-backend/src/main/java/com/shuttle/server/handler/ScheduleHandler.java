package com.shuttle.server.handler;

import com.shuttle.server.ApiException;
import com.shuttle.server.service.ReportService;

public class ScheduleHandler extends BaseHandler {

    @Override
    protected Response process(Request r) throws Exception {
        if (r.is("GET")) {
            return ok(ReportService.schedule());
        }
        if (r.is("PUT")) {
            r.requireOperator();
            if (r.rows.isEmpty()) {
                throw new ApiException(400, "Send a CSV table with the columns employeeId,shift,days");
            }
            return ok(ReportService.replaceSchedule(r.rows));
        }
        throw new ApiException(405, "Method Not Allowed");
    }
}
