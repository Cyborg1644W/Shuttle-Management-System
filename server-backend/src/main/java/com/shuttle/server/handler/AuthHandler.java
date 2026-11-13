package com.shuttle.server.handler;

import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Auth;
import com.shuttle.server.Csv;
import com.shuttle.server.service.RegistrationService;
import com.shuttle.server.service.WorkerService;

import java.util.Map;

import static com.shuttle.server.Csv.str;

public class AuthHandler extends BaseHandler {

    @Override
    protected boolean isPublic(Request r) {
        String action = r.seg(1);
        return r.is("POST") && (action == null || action.equals("login") || action.equals("register"));
    }

    @Override
    protected Response process(Request r) throws Exception {
        String action = r.seg(1);

        if (r.is("POST") && (action == null || action.equals("login"))) {
            String email = str(r.body, "email");
            String phone = str(r.body, "phone");
            String password = str(r.body, "password");
            if (email.isEmpty() && phone.isEmpty() && password.isEmpty()) { // older clients sent headers
                email = nz(r.header("X-Email"));
                password = nz(r.header("X-Password"));
            }
            boolean byPhone = email.trim().isEmpty() && !phone.trim().isEmpty();
            if ((email.trim().isEmpty() && phone.trim().isEmpty()) || password.isEmpty()) {
                throw new ApiException(400, "Email (or mobile number) and password are required");
            }
            String token = byPhone
                    ? Auth.authenticateByPhone(phone, password)
                    : Auth.authenticateUser(email, password);
            if (token == null) {
                throw new ApiException(401, "Invalid credentials");
            }
            Worker worker = Auth.getWorkerByToken(token);
            Map<String, Object> row = Csv.obj("token", token);
            row.putAll(WorkerService.toPublic(worker));
            return ok(row);
        }

        if (r.is("POST") && "register".equals(action)) {
            Worker w = RegistrationService.registerUser(str(r.body, "role"), str(r.body, "name"),
                    str(r.body, "email"), str(r.body, "phone"), str(r.body, "password"),
                    str(r.body, "licenseNumber"), str(r.body, "employeeId"));
            String token = Auth.authenticateUser(w.getEmail(), str(r.body, "password"));
            Map<String, Object> row = Csv.obj("token", token);
            row.putAll(WorkerService.toPublic(w));
            return created(row);
        }

        if (r.is("POST") && "logout".equals(action)) {
            Auth.logout(r.token);
            return ok(Csv.obj("status", "Signed out"));
        }

        if (r.is("GET") && "me".equals(action)) {
            return ok(WorkerService.toPublic(r.worker()));
        }

        throw new ApiException(404, "Endpoint Not Found");
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}