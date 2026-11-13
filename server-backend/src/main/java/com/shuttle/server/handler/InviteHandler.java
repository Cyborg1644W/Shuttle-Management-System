package com.shuttle.server.handler;

import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Json;
import com.shuttle.server.service.InviteService;
import com.shuttle.server.service.RegistrationService;

import static com.shuttle.server.Json.str;
public class InviteHandler extends BaseHandler {

    @Override
    protected boolean isPublic(Request r) {
        return "register".equals(r.seg(1));
    }

    @Override
    protected Response process(Request r) throws Exception {
        String action = r.seg(1);

        if ("generate".equals(action)) {
            if (!r.is("POST")) {
                throw new ApiException(405, "Method Not Allowed");
            }
            r.requireOperator();
            String role = str(r.body, "role");
            if (role.isEmpty() && r.header("X-Role") != null) {
                role = r.header("X-Role");
            }
            String employeeId = str(r.body, "employeeId");
            String code = InviteService.createInvite(role, employeeId.isEmpty() ? null : employeeId);
            return created(Json.obj("invite_code", code, "expiresInHours", 48));
        }

        if ("register".equals(action)) {
            if (!r.is("POST")) {
                throw new ApiException(405, "Method Not Allowed");
            }
            String code = str(r.body, "inviteCode");
            String email = str(r.body, "email");
            String password = str(r.body, "password");
            String name = str(r.body, "name");
            if (code.isEmpty() && email.isEmpty()) { // older clients sent headers
                code = nz(r.header("X-Invite-Code"));
                email = nz(r.header("X-Email"));
                password = nz(r.header("X-Password"));
                name = nz(r.header("X-Name"));
                if (name.isEmpty() && email.contains("@")) {
                    name = email.substring(0, email.indexOf('@'));
                }
            }
            Worker w = RegistrationService.registerUser(str(r.body, "role"), name, email,
                    str(r.body, "phone"), password, str(r.body, "licenseNumber"),
                    str(r.body, "employeeId"));
            return created(Json.obj("status", "Registration Successful",
                    "employeeId", w.getEmployeeID(), "role", w.getRole().name()));
        }

        throw new ApiException(404, "Endpoint Not Found");
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}