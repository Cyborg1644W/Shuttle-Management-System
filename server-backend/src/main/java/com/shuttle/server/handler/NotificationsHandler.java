package com.shuttle.server.handler;

import com.shuttle.server.ApiException;
import com.shuttle.server.service.NotificationService;

public final class NotificationsHandler extends BaseHandler {
    @Override protected Response process(Request request) throws Exception {
        String id = request.seg(1);
        if ("notifications".equals(request.seg(0)) && request.seg(3) == null) {
            if (request.is("GET") && request.seg(2) == null) {
                if (id == null) return ok(NotificationService.list(request.worker()));
                if ("unread".equals(id)) return ok(NotificationService.unread(request.worker()));
            }
            if (request.is("PUT") && id != null && "read".equals(request.seg(2))) {
                return ok(NotificationService.markRead(request.worker(), id));
            }
        }
        throw new ApiException(404, "Endpoint Not Found");
    }
}
