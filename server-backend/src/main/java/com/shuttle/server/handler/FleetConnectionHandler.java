package com.shuttle.server.handler;

import com.shuttle.server.ApiException;
import com.shuttle.server.Csv;
import com.shuttle.server.service.TerminalService;

public final class FleetConnectionHandler extends BaseHandler {
    @Override
    protected Response process(Request r) throws Exception {
        String action = r.seg(1);
        if (r.seg(2) != null) throw new ApiException(404, "Endpoint Not Found");
        if (action == null && r.is("GET")) return ok(TerminalService.getState(r.worker()));
        if (action == null && r.is("POST")) {
            return created(TerminalService.create(r.worker(), Csv.str(r.body, "terminalName")));
        }
        if ("invites".equals(action) && r.is("GET")) return ok(TerminalService.invitationState(r.worker()));
        if ("invites".equals(action) && r.is("PUT")) {
            String enabled = Csv.str(r.body, "enabled");
            if (!"true".equalsIgnoreCase(enabled) && !"false".equalsIgnoreCase(enabled)) {
                throw new ApiException(400, "Specify whether the invitation is enabled");
            }
            return ok(TerminalService.setInviteEnabled(r.worker(), Csv.str(r.body, "inviteCode"),
                    Boolean.parseBoolean(enabled)));
        }
        if (r.is("POST")) {
            if ("invites".equals(action)) return created(TerminalService.invite(r.worker()));
            if ("preview".equals(action)) return ok(TerminalService.preview(r.worker(), Csv.str(r.body, "inviteCode")));
            if ("join".equals(action)) return ok(TerminalService.join(r.worker(), Csv.str(r.body, "inviteCode"),
                    Csv.bool(r.body, "switchConfirmed")));
        }
        throw new ApiException(405, "Method Not Allowed");
    }
}
