package com.shuttle.server.handler;

import com.shuttle.server.ApiException;
import com.shuttle.server.service.ReportService;


public class DashboardHandler extends BaseHandler {

    @Override
    protected Response process(Request r) {
        if (!r.is("GET")) {
            throw new ApiException(405, "Method Not Allowed");
        }
        r.requireOperator();
        return ok(ReportService.dashboard());
    }
}
