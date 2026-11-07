package com.shuttle.server.handler;

import com.shuttle.server.ApiException;
import com.shuttle.server.Json;
import com.shuttle.server.service.ReportService;

import java.util.List;
public class ScheduleHandler extends BaseHandler {

    @Override
    @SuppressWarnings("unchecked")
    protected Response process(Request r) throws Exception {
        if (r.is("GET")) {
            return ok(Json.obj("entries", ReportService.schedule()));
        }
        if (r.is("PUT")) {
            r.requireOperator();
            Object entries = r.body.get("entries");
            if (!(entries instanceof List)) {
                throw new ApiException(400, "Send {\"entries\": [ ... ]}");
            }
            return ok(Json.obj("entries", ReportService.replaceSchedule((List<Object>) entries)));
        }
        throw new ApiException(405, "Method Not Allowed");
    }
}
