package com.shuttle.server.handler;

import com.shuttle.common.Role;
import com.shuttle.common.Worker;
import com.shuttle.server.ApiException;
import com.shuttle.server.Json;
import com.shuttle.server.service.InviteService;
import com.shuttle.server.service.WorkerService;

import static com.shuttle.server.Json.bool;
import static com.shuttle.server.Json.has;
import static com.shuttle.server.Json.str;
public class WorkerHandler extends BaseHandler {

    @Override
    protected Response process(Request r) throws Exception {
        String id = r.seg(1);
        String action = r.seg(2);

        if (id == null) {
            r.requireOperator();
            if (r.is("GET")) {
                return ok(Json.obj("workers", WorkerService.listAll()));
            }
            if (r.is("POST")) {
                String name = has(r.body, "fullName") ? str(r.body, "fullName") : str(r.body, "name");
                Role role = parseRole(r);
                Worker w = WorkerService.createPending(name, str(r.body, "phone"), role, str(r.body, "employeeId"));
                String code = InviteService.createInvite(role.name(), w.getEmployeeID());
                return created(Json.obj("worker", WorkerService.toPublic(w), "inviteCode", code));
            }
        } else if (action == null) {
            if (r.is("GET")) {
                requireOperatorOrSelf(r, id);
                return ok(Json.obj("worker", WorkerService.toPublic(WorkerService.get(id))));
            }
            if (r.is("PUT")) {
                requireOperatorOrSelf(r, id);
                Worker w = WorkerService.update(id, r.body, r.isOperator());
                return ok(Json.obj("worker", WorkerService.toPublic(w)));
            }
            if (r.is("DELETE")) {
                r.requireOperator();
                if (r.isSelf(id)) {
                    throw new ApiException(400, "You can't remove your own account");
                }
                WorkerService.remove(id);
                return ok(Json.obj("removed", true));
            }
        } else if (action.equals("status") && r.is("PUT")) {
            requireOperatorOrSelf(r, id);
            Worker w = WorkerService.setStatus(id, str(r.body, "status"));
            return ok(Json.obj("worker", WorkerService.toPublic(w)));
        } else if (action.equals("assign") && r.is("PUT")) {
            r.requireOperator();
            Worker w = WorkerService.assign(id, r.body);
            return ok(Json.obj("worker", WorkerService.toPublic(w)));
        } else if (action.equals("invite") && r.is("POST")) {
            r.requireOperator();
            Worker w = WorkerService.get(id);
            if (!WorkerService.PENDING_HASH.equals(w.getPasswordHash())) {
                throw new ApiException(409, "This worker has already registered");
            }
            return created(Json.obj("inviteCode", InviteService.createInvite(w.getRole().name(), w.getEmployeeID())));
        }

        throw new ApiException(404, "Endpoint Not Found");
    }

    private static void requireOperatorOrSelf(Request r, String id) {
        if (!r.isOperator() && !r.isSelf(id)) {
            throw new ApiException(403, "You can only access your own profile");
        }
    }

    /** Accepts {"role": "DRIVER"} or the app's {"operator": true/false}. */
    private static Role parseRole(Request r) {
        String role = str(r.body, "role");
        if (role.isEmpty()) {
            return bool(r.body, "operator") ? Role.OPERATOR : Role.DRIVER;
        }
        try {
            return Role.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Role must be DRIVER or OPERATOR");
        }
    }
}
