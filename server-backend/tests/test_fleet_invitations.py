"""HTTP integration tests for terminal invitations, using only Python's standard library.

Build :server-backend:installDist first. If build outputs live outside the checkout,
set SHUTTLE_SERVER_LIB_DIR to the distribution's lib directory before running:

    python3 -m unittest discover -s server-backend/tests -v

Every test starts a real Java server with isolated temporary CSV data. No account
or running development server is reused, and all child processes are stopped.
"""

import concurrent.futures
import csv
from datetime import date, timedelta
import io
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request
import uuid


class FleetInvitationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        default_lib = Path(__file__).resolve().parents[1] / "build/install/server-backend/lib"
        cls.lib_dir = Path(os.environ.get("SHUTTLE_SERVER_LIB_DIR", default_lib)).resolve()
        if not list(cls.lib_dir.glob("*.jar")):
            raise RuntimeError(
                "Build :server-backend:installDist first, or set "
                "SHUTTLE_SERVER_LIB_DIR to its lib directory."
            )
        java_home = os.environ.get("JAVA_HOME")
        cls.java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
        if not cls.java:
            raise RuntimeError("Java is required to execute the integration tests.")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="shuttle-invite-test-")
        self.addCleanup(self.temp.cleanup)
        self.workdir = Path(self.temp.name)
        self.log = None
        self.process = None
        self.addCleanup(self.stop_server)
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            self.port = reservation.getsockname()[1]
        (self.workdir / "config.properties").write_text(
            f"server.port={self.port}\ncsv.workers=data/workers.txt\n"
            "csv.shuttles=data/shuttles.txt\n",
            encoding="utf-8",
        )
        self.base_url = f"http://127.0.0.1:{self.port}/api"
        # Local test traffic must not pass through a machine's outbound proxy.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.start_server()
        self.users = {}
        for index, (label, role) in enumerate(
            (("operator_a", "OPERATOR"), ("operator_b", "OPERATOR"),
             ("driver_a", "DRIVER"), ("driver_b", "DRIVER")), start=1
        ):
            password = secrets.token_urlsafe(18)
            email = f"{label}@example.test"
            user = self.one(self.request("POST", "/auth/register", body={
                "role": role,
                "name": label.replace("_", " ").title(),
                "email": email,
                "phone": f"+63917000000{index}",
                "password": password,
            }, expected=201))
            self.assertTrue(user.get("token"), "Registration must return a session")
            self.users[label] = {**user, "login_email": email, "login_password": password}

    def start_server(self):
        self.log = (self.workdir / "java-output.log").open("ab")
        classpath = os.pathsep.join((str(self.workdir), str(self.lib_dir / "*")))
        self.process = subprocess.Popen(
            [self.java, "-cp", classpath, "com.shuttle.server.ServerMain"],
            cwd=self.workdir, stdout=self.log, stderr=subprocess.STDOUT,
        )
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                self.fail("Temporary backend exited before becoming ready")
            try:
                with self.opener.open(self.base_url + "/health", timeout=1) as response:
                    if response.status == 200 and response.read().decode() == "status\nok\n":
                        return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(0.05)
        self.fail("Temporary backend did not pass its HTTP readiness check")

    def stop_server(self):
        if self.process is not None:
            if self.process.poll() is None:
                self.process.terminate()
                try:
                    self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=5)
            self.process = None
        if self.log is not None:
            self.log.close()
            self.log = None

    def restart_server(self):
        self.stop_server()
        self.start_server()
        # Runtime sessions intentionally do not survive a server restart.
        for user in self.users.values():
            login = self.one(self.request("POST", "/auth/login", body={
                "email": user["login_email"], "password": user["login_password"],
            }))
            self.assertEqual(login["employeeId"], user["employeeId"])
            user["token"] = login["token"]

    def request(self, method, path, user=None, body=None, expected=200):
        headers = {"Accept": "text/csv"}
        if user is not None:
            headers["Authorization"] = "Bearer " + user["token"]
        data = None
        if body is not None:
            rows = body if isinstance(body, list) else [body]
            text = io.StringIO(newline="")
            if rows:
                writer = csv.DictWriter(text, fieldnames=list(rows[0]), lineterminator="\n")
                writer.writeheader()
                writer.writerows(rows)
            data = text.getvalue().encode("utf-8")
            headers["Content-Type"] = "text/csv; charset=utf-8"
        req = urllib.request.Request(self.base_url + path, data=data, method=method, headers=headers)
        try:
            response = self.opener.open(req, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status = response.status
            content_type = response.headers.get("Content-Type", "")
            text = response.read().decode("utf-8")
        allowed = expected if isinstance(expected, tuple) else (expected,)
        # Do not include request bodies or auth responses in assertion output.
        self.assertIn(status, allowed, f"{method} {path}: unexpected HTTP {status}")
        self.assertIn("text/csv", content_type)
        rows = list(csv.DictReader(io.StringIO(text))) if text.strip() else []
        if status >= 400:
            self.assertTrue(rows and rows[0].get("error"), "Errors must include a CSV error message")
        return rows

    def one(self, rows):
        self.assertEqual(len(rows), 1, "Expected one CSV response row")
        return rows[0]

    def terminal(self, operator, name):
        return self.one(self.request("POST", "/fleet", operator, {"terminalName": name}, expected=201))

    def invite(self, operator):
        invitation = self.one(self.request("POST", "/fleet/invites", operator, {}, expected=201))
        self.assertTrue(invitation.get("inviteCode"))
        self.assertEqual(invitation["expiresAt"], "0")
        return invitation

    def join(self, driver, code, switch=False, expected=200):
        return self.request("POST", "/fleet/join", driver,
                            {"inviteCode": code, "switchConfirmed": str(switch).lower()}, expected)

    def linked_fleets(self):
        a, b = self.users["operator_a"], self.users["operator_b"]
        d, e = self.users["driver_a"], self.users["driver_b"]
        self.terminal(a, "Central Terminal")
        self.terminal(b, "North Terminal")
        self.join(d, self.invite(a)["inviteCode"])
        self.join(e, self.invite(b)["inviteCode"])
        return a, b, d, e

    def shuttle(self, operator, shuttle_id):
        return self.one(self.request("POST", "/shuttles", operator, {
            "shuttleId": shuttle_id, "plateNumber": shuttle_id + "-PLATE", "capacity": 12,
        }, expected=201))

    def test_accounts_start_independent_and_only_operators_create_terminals(self):
        a, d = self.users["operator_a"], self.users["driver_a"]
        self.request("GET", "/fleet", expected=401)
        for user in (a, d):
            self.assertEqual(self.one(self.request("GET", "/fleet", user))["joined"], "false")
        self.request("POST", "/fleet", d, {"terminalName": "Invalid"}, expected=403)
        self.request("POST", "/fleet/invites", d, {}, expected=403)
        self.request("POST", "/fleet", a, {"terminalName": " "}, expected=400)
        self.request("POST", "/shift/start", d, {}, expected=(403, 409))
        self.assertEqual(self.request("GET", "/shuttles", d), [])
        self.assertEqual(self.one(self.request("GET", "/workers/" + d["employeeId"], d))["employeeId"],
                         d["employeeId"])
        created = self.terminal(a, "Central Terminal")
        self.assertEqual(created["joined"], "true")
        self.assertEqual(created["terminalName"], "Central Terminal")
        self.request("POST", "/fleet", a, {"terminalName": "Duplicate"}, expected=409)

    def test_preview_does_not_join_then_confirmed_join_updates_operator_list(self):
        a, b, d = self.users["operator_a"], self.users["operator_b"], self.users["driver_a"]
        terminal = self.terminal(a, 'Central, "Main" Terminal')
        self.terminal(b, "North Terminal")
        invite = self.invite(a)
        preview = self.one(self.request("POST", "/fleet/preview", d, {"inviteCode": invite["inviteCode"]}))
        self.assertEqual(preview["terminalName"], terminal["terminalName"])
        self.assertEqual(preview["operatorName"], a["fullName"])
        self.assertEqual(self.one(self.request("GET", "/fleet", d))["joined"], "false")
        joined = self.one(self.join(d, invite["inviteCode"]))
        self.assertEqual(joined["terminalId"], terminal["terminalId"])
        self.assertEqual(joined["joined"], "true")
        ids_a = {row["employeeId"] for row in self.request("GET", "/workers", a)}
        ids_b = {row["employeeId"] for row in self.request("GET", "/workers", b)}
        self.assertIn(d["employeeId"], ids_a)
        self.assertNotIn(d["employeeId"], ids_b)
        self.assertNotIn(b["employeeId"], ids_a)

    def test_invites_reject_wrong_roles_invalid_codes_and_reuse(self):
        a, d, e = self.users["operator_a"], self.users["driver_a"], self.users["driver_b"]
        self.terminal(a, "Central Terminal")
        invite = self.invite(a)
        self.request("POST", "/fleet/join", body={"inviteCode": invite["inviteCode"]}, expected=401)
        self.join(a, invite["inviteCode"], expected=403)
        self.join(d, "NOT-A-VALID-CODE", expected=(400, 403, 404))
        self.assertEqual(self.one(self.request("GET", "/fleet", d))["joined"], "false")
        self.join(d, invite["inviteCode"])
        self.join(e, invite["inviteCode"], expected=(403, 409))
        self.assertEqual(self.one(self.request("GET", "/fleet", e))["joined"], "false")

    def test_terminal_isolation_covers_workers_vehicles_and_dashboard(self):
        a, b, d, e = self.linked_fleets()
        self.shuttle(a, "CENTRAL-1")
        self.shuttle(b, "NORTH-1")
        self.assertEqual({row["shuttleId"] for row in self.request("GET", "/shuttles", a)}, {"CENTRAL-1"})
        self.assertEqual({row["shuttleId"] for row in self.request("GET", "/shuttles", d)}, {"CENTRAL-1"})
        self.request("GET", "/shuttles/NORTH-1", a, expected=403)
        self.request("PUT", "/shuttles/NORTH-1/status", a, {"status": "OFFLINE"}, expected=403)
        self.request("GET", "/workers/" + e["employeeId"], a, expected=403)
        self.request("PUT", "/workers/" + e["employeeId"], a, {"fullName": "Changed"}, expected=403)
        self.request("PUT", "/workers/" + d["employeeId"] + "/assign", a,
                     {"shuttleId": "NORTH-1"}, expected=403)
        self.request("POST", "/location", a,
                     {"shuttleId": "NORTH-1", "latitude": 14.5, "longitude": 121.0}, expected=403)
        self.request("DELETE", "/workers/" + e["employeeId"], a, expected=403)
        for operator in (a, b):
            dashboard = self.one(self.request("GET", "/dashboard", operator))
            self.assertEqual(int(dashboard["totalUnits"]), 1)
            self.assertEqual(int(dashboard["drivers"]), 1)

    def test_switch_requires_confirmation_and_completed_shift_and_clears_assignment(self):
        a, b, d, e = self.linked_fleets()
        self.shuttle(a, "CENTRAL-1")
        path = "/workers/" + d["employeeId"]
        self.request("PUT", path + "/assign", a, {"shuttleId": "CENTRAL-1"})
        self.request("PUT", "/schedule", a, [{"employeeId": d["employeeId"], "shift": 0, "days": "1111100"}])
        self.request("PUT", "/schedule", b, [{"employeeId": e["employeeId"], "shift": 1, "days": "0011111"}])
        code = self.invite(b)["inviteCode"]
        self.join(d, code, expected=409)
        self.request("POST", "/shift/start", d, {}, expected=201)
        self.join(d, code, switch=True, expected=409)
        self.assertEqual(self.one(self.request("GET", "/fleet", d))["terminalName"], "Central Terminal")
        self.request("POST", "/shift/end", d, {"distanceKm": 1, "stops": 1})
        self.join(d, code, switch=True)
        state = self.one(self.request("GET", "/fleet", d))
        self.assertEqual(state["terminalName"], "North Terminal")
        profile = self.one(self.request("GET", path, d))
        self.assertEqual(profile["shuttleId"], "")
        self.assertEqual(self.request("GET", "/schedule", d), [])
        self.assertEqual({r["employeeId"] for r in self.request("GET", "/schedule", b)}, {e["employeeId"]})
        self.request("GET", path, a, expected=403)
        self.assertIn(d["employeeId"], {r["employeeId"] for r in self.request("GET", "/workers", b)})
        # The new operator must not inherit the old terminal's shift history.
        self.assertEqual(self.request("GET", "/shift?driverId=" + d["employeeId"], b), [])
        self.assertEqual(len(self.request("GET", "/shift", d)), 1)

    def test_operator_fleet_snapshot_and_delta_follow_shift_lifecycle(self):
        a, b = self.users["operator_a"], self.users["operator_b"]
        d = self.users["driver_a"]
        self.request("POST", "/fleet", a, {"terminalName": "Central", "terminalLatitude": 14.5,
                                           "terminalLongitude": 121.0}, expected=201)
        self.terminal(b, "North")
        self.join(d, self.invite(a)["inviteCode"])
        header = self.one(self.request("GET", "/live/fleet", a))
        self.assertEqual((header["type"], float(header["terminalLatitude"])), ("fleet", 14.5))
        self.request("GET", "/live/fleet", d, expected=403)
        self.shuttle(a, "MAP-1")
        self.request("PUT", "/workers/" + d["employeeId"] + "/assign", a, {"shuttleId": "MAP-1"})
        shift = self.one(self.request("POST", "/shift/start", d, {}, expected=201))
        sid, start = shift["shiftId"], int(shift["startTime"])
        self.assertEqual(self.request("GET", "/live/fleet", a)[1]["positionTime"], "")
        for offset in (100, 200):
            self.request("POST", "/shuttles/MAP-1/location", d, {"shiftId": sid, "latitude": 14.5,
                         "longitude": 121 + offset/10000000, "speed": 4, "bearing": 90, "timestamp": start+offset})
        current = self.request("GET", "/live/fleet", a)[1]
        self.assertEqual((current["shiftId"], float(current["bearing"]), len(current["trail"].split(";"))),
                         (sid, 90, 2))
        delta = self.request("GET", f"/live/fleet?{sid}={start+100}", a)[1]["trail"]
        self.assertEqual(delta, current["trail"].split(";")[1])
        self.assertEqual(len(self.request("GET", "/live/fleet", b)), 1)
        self.request("POST", "/shift/end", d, {"shiftId": sid, "route": "14.5,121;14.5,121.001"})
        self.assertEqual(len(self.request("GET", "/live/fleet", a)), 1)
        self.assertEqual(self.one(self.request("GET", "/shift/" + sid, a))["route"], "14.5,121;14.5,121.001")

    def test_concurrent_telemetry_and_invalid_fixes(self):
        op = self.users["operator_a"]
        self.terminal(op, "Central")
        shifts = []
        for index, key in enumerate(("driver_a", "driver_b")):
            d = self.users[key]
            unit = f"MAP-{index}"
            self.join(d, self.invite(op)["inviteCode"])
            self.shuttle(op, unit)
            self.request("PUT", f"/workers/{d['employeeId']}/assign", op, {"shuttleId": unit})
            shift = self.one(self.request("POST", "/shift/start", d, {}, expected=201))
            shifts.append((d, shift))

        def send(driver, shift, t):
            self.request("POST", "/location", driver, {"shiftId": shift["shiftId"], "latitude": 14.5,
                         "longitude": 121, "timestamp": int(shift["startTime"]) + t})

        def inspect():
            rows = self.request("GET", "/live/fleet", op)
            self.assertEqual(int(rows[0]["operatingUnits"]), len(rows) - 1)
            self.assertEqual(int(rows[0]["activeShifts"]), len(rows) - 1)
            self.assertEqual(int(rows[0]["driversActive"]), len(rows) - 1)
            for row in rows[1:]:
                if row["trail"]:
                    times = [int(p.split(",")[0]) for p in row["trail"].split(";")]
                    self.assertEqual(times, sorted(set(times)))
                    self.assertEqual(times[-1], int(row["positionTime"]))

        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            jobs = []
            for tick in range(1, 13):
                jobs.extend(pool.submit(send, d, shift, tick) for d, shift in shifts)
                jobs.append(pool.submit(inspect))
            for job in jobs:
                job.result()

        d, shift = shifts[0]
        self.request("POST", "/live/fleet", op, {}, expected=405)
        self.request("GET", "/live/unknown", op, expected=404)
        for endpoint in ("/location", "/shuttles/MAP-0/location"):
            for lat, lon in ((None, 121), (0, 0), ("north", 121), (14.5, "NaN")):
                self.request("POST", endpoint, d, {"latitude": lat, "longitude": lon}, expected=400)
        send(d, shift, 1)
        rows = self.request("GET", "/live/fleet", op)
        self.assertEqual({int(r["positionTime"]) for r in rows[1:]},
                         {int(s["startTime"]) + 12 for _, s in shifts})
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            closing = pool.submit(self.request, "POST", "/shift/end", d, {"shiftId": shift["shiftId"]})
            racing = pool.submit(send, d, shift, 13)
            inspect()
            closing.result()
            racing.result()
        self.assertEqual([r["shiftId"] for r in self.request("GET", "/live/fleet", op)[1:]],
                         [shifts[1][1]["shiftId"]])

    def test_interrupted_local_shift_commit_recovers_before_poll(self):
        op, _, driver, _ = self.linked_fleets()
        self.shuttle(op, "MAP-1")
        self.request("PUT", f"/workers/{driver['employeeId']}/assign", op, {"shuttleId": "MAP-1"})
        data = self.workdir / "data"
        # A write failure before the journal is published must leave all three tables unchanged.
        def block(name):
            folder = data / name
            folder.mkdir()
            (folder / "busy").touch()
            return folder
        blocked = block("pending-tables.properties.tmp")
        self.request("POST", "/shift/start", driver, {}, expected=503)
        shutil.rmtree(blocked)
        self.assertEqual(len(self.request("GET", "/live/fleet", op)), 1)
        shift = self.one(self.request("POST", "/shift/start", driver, {}, expected=201))
        # After journal publication a reader must wait for recovery or get a retryable error.
        blocked = block("shuttles.txt.tmp")
        self.request("POST", "/shift/end", driver, {"shiftId": shift["shiftId"]}, expected=503)
        self.request("GET", "/live/fleet", op, expected=503)
        shutil.rmtree(blocked)
        self.restart_server()
        snapshot = self.one(self.request("GET", "/live/fleet", op))
        self.assertEqual((snapshot["operatingUnits"], snapshot["availableUnits"]), ("0", "1"))
        self.assertEqual(self.request("GET", "/shift/active", driver), [])
        self.assertEqual(self.one(self.request("POST", "/shift/end", driver,
                                              {"shiftId": shift["shiftId"]}))["status"], "COMPLETED")

    def test_large_offline_route_and_idempotent_end(self):
        operator, _, driver, other = self.linked_fleets()
        self.shuttle(operator, "MAP-1")
        self.request("PUT", "/workers/" + driver["employeeId"] + "/assign", operator, {"shuttleId": "MAP-1"})
        shift = self.one(self.request("POST", "/shift/start", driver, {}, expected=201))
        trail = ";".join(f"14.{i:05d},120.98" for i in range(5000))
        body = {"shiftId": shift["shiftId"], "endTime": shift["startTime"], "route": trail, "distanceKm": 6}
        self.request("POST", "/shift/end", other, body, expected=403)
        ended = self.one(self.request("POST", "/shift/end", driver, body))
        self.assertEqual(ended["endTime"], shift["startTime"])
        self.assertEqual(ended["route"], trail)
        next_shift = self.one(self.request("POST", "/shift/start", driver, {}, expected=201))
        retried = self.one(self.request("POST", "/shift/end", driver, body))
        self.assertEqual(retried["shiftId"], shift["shiftId"])
        self.assertEqual(self.one(self.request("GET", "/shift/active", driver))["shiftId"], next_shift["shiftId"])
        self.assertEqual(self.one(self.request("GET", "/shift/" + shift["shiftId"], driver))["route"], trail)
        self.assertTrue(all("route" not in row for row in self.request("GET", "/shift?summary=true", driver)))

    def test_unassign_clears_vehicle_and_preserves_membership_after_restart(self):
        operator, _, driver, _ = self.linked_fleets()
        self.shuttle(operator, "CENTRAL-1")
        profile = "/workers/" + driver["employeeId"]
        assign = profile + "/assign"
        self.request("PUT", assign, operator, {"shuttleId": "CENTRAL-1"})
        self.request("POST", "/shift/start", driver, {}, expected=201)
        self.request("PUT", assign, operator, {"shuttleId": ""}, expected=409)
        self.request("POST", "/shift/end", driver, {"distanceKm": 0, "stops": 0})
        self.request("PUT", assign, operator, expected=400)
        cleared = self.one(self.request("PUT", assign, operator, {"shuttleId": ""}))
        self.assertEqual(cleared["shuttleId"], "")
        self.restart_server()
        self.assertEqual(self.one(self.request("GET", profile, driver))["shuttleId"], "")
        self.assertEqual(self.one(self.request("GET", "/fleet", driver))["joined"], "true")
        self.request("POST", "/shift/start", driver, {}, expected=409)
        self.request("PUT", assign, operator, {"shuttleId": "CENTRAL-1"})

    def test_add_shift_survives_legacy_file_upgrade_and_retry(self):
        operator, _, driver, _ = self.linked_fleets()
        self.shuttle(operator, "CENTRAL-1")
        day = (date.today() + timedelta(days=7)).isoformat()
        shift = {"id": str(uuid.uuid4()), "shiftDate": day, "employeeId": driver["employeeId"],
                 "shuttleId": "CENTRAL-1", "startTime": "08:00", "endTime": "10:00", "notesBase64": ""}
        self.request("POST", "/scheduled-shifts", driver, shift, expected=403)
        saved = self.one(self.request("POST", "/scheduled-shifts", operator, shift, expected=201))
        self.assertEqual(saved["id"], shift["id"])
        self.stop_server()
        data = self.workdir / "data"
        tables = {p.name: p.read_bytes() for p in data.glob("*.txt")}
        self.assertIn("dated-shifts.txt", tables)
        for name in tables:
            (data / name).rename((data / name).with_suffix(".csv"))
        config = self.workdir / "config.properties"
        config.write_text(config.read_text().replace(".txt", ".csv"), encoding="utf-8")
        self.restart_server()
        self.assertEqual({p.name: p.read_bytes() for p in data.glob("*.txt")}, tables)
        self.assertFalse(list(data.glob("*.csv")))
        self.request("POST", "/scheduled-shifts", operator, shift, expected=201)
        self.assertEqual(self.request("GET", f"/scheduled-shifts?from={day}&to={day}", driver),
                         [dict(saved, canRemove="false")])
        self.assertEqual(self.request("GET", "/schedule", driver), [])

    def test_removing_driver_detaches_membership_without_deleting_account(self):
        a, _, d, _ = self.linked_fleets()
        self.request("DELETE", "/workers/" + d["employeeId"], a)
        self.assertEqual(self.one(self.request("GET", "/fleet", d))["joined"], "false")
        self.assertEqual(self.one(self.request("GET", "/workers/" + d["employeeId"], d))["employeeId"],
                         d["employeeId"])
        self.request("GET", "/workers/" + d["employeeId"], a, expected=403)
        login = self.one(self.request("POST", "/auth/login", body={
            "email": d["login_email"], "password": d["login_password"],
        }))
        self.assertEqual(login["employeeId"], d["employeeId"])

    def test_deleted_vehicle_identity_cannot_be_reused_by_another_terminal(self):
        a, b, _, _ = self.linked_fleets()
        self.shuttle(a, "CENTRAL-ARCHIVED")
        self.request("DELETE", "/shuttles/CENTRAL-ARCHIVED", a)
        self.request("POST", "/shuttles", b, {
            "shuttleId": "CENTRAL-ARCHIVED", "plateNumber": "NEW-PLATE", "capacity": 12,
        }, expected=409)
        self.assertEqual(self.request("GET", "/shuttles", b), [])
        self.request("GET", "/shuttles/CENTRAL-ARCHIVED", b, expected=403)

    def test_memberships_and_single_use_codes_survive_restart(self):
        a, d, e = self.users["operator_a"], self.users["driver_a"], self.users["driver_b"]
        self.terminal(a, "Central Terminal")
        used = self.invite(a)["inviteCode"]
        self.join(d, used)
        unused = self.invite(a)["inviteCode"]
        self.restart_server()
        self.assertEqual(self.one(self.request("GET", "/fleet", d))["terminalName"], "Central Terminal")
        self.join(e, used, expected=(403, 409))
        self.join(e, unused)
        self.assertEqual(self.one(self.request("GET", "/fleet", e))["terminalName"], "Central Terminal")

    def test_concurrent_redemption_has_exactly_one_winner(self):
        a, d, e = self.users["operator_a"], self.users["driver_a"], self.users["driver_b"]
        self.terminal(a, "Central Terminal")
        code = self.invite(a)["inviteCode"]
        def redeem(user):
            return self.join(user, code, expected=(200, 403, 409))
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(redeem, (d, e)))
        winners = sum(bool(rows and rows[0].get("joined") == "true") for rows in results)
        self.assertEqual(winners, 1, "A single-use invitation must admit exactly one driver")
        memberships = [self.one(self.request("GET", "/fleet", user))["joined"] for user in (d, e)]
        self.assertEqual(sorted(memberships), ["false", "true"])

    def test_vehicle_model_and_assignment_notice_survive_restart(self):
        a, _, d, other = self.linked_fleets()
        point = "/shuttles/MODEL-1"
        body = {"shuttleId": "MODEL-1", "plateNumber": "ABC 1234", "vehicleModel": 'Toyota, "Coaster"'}
        self.request("POST", "/shuttles", a, dict(body, vehicleModel=""), expected=400)
        unit = self.one(self.request("POST", "/shuttles", a, body, expected=201))
        self.assertEqual(unit["vehicleModel"], body["vehicleModel"])
        self.shuttle(a, "LEGACY-1")
        assignment = "/workers/" + d["employeeId"] + "/assign"
        for _ in range(2):
            self.request("PUT", assignment, a, {"shuttleId": unit["shuttleId"]})
        notice = self.one(self.request("GET", "/notifications", d))
        self.assertEqual(notice["title"], "Vehicle Assigned")
        self.assertEqual(notice["message"], "You have been assigned to Shuttle MODEL-1.")
        self.assertEqual(notice["read"], "false")
        self.assertEqual(self.request("GET", "/notifications", other), [])
        self.assertEqual(self.request("GET", "/notifications", a), [])
        mark = "/notifications/" + notice["id"] + "/read"
        self.request("PUT", mark, other, expected=404)
        self.request("PUT", mark, a, expected=403)
        self.request("PUT", mark, d)
        self.request("PUT", mark, d)
        self.restart_server()
        self.assertEqual(self.one(self.request("GET", point, a))["plateNumber"], body["plateNumber"])
        self.assertEqual(self.one(self.request("GET", point, a))["vehicleModel"], body["vehicleModel"])
        self.assertEqual(self.one(self.request("GET", "/shuttles/LEGACY-1", a))["capacity"], "12")
        self.assertEqual(self.one(self.request("GET", "/notifications", d))["read"], "true")
        self.assertEqual(self.one(self.request("GET", "/notifications/unread", d))["unread"], "0")

    def test_dated_removal_permissions_and_persistent_notification(self):
        a, b, d, other = self.linked_fleets()
        self.shuttle(a, "CENTRAL-1")
        day = (date.today() + timedelta(days=7)).isoformat()
        shift = {"id": str(uuid.uuid4()), "shiftDate": day, "employeeId": d["employeeId"],
                 "shuttleId": "CENTRAL-1", "startTime": "00:00", "endTime": "12:00", "notesBase64": "Tm90ZXM="}
        for _ in range(2):
            self.request("POST", "/scheduled-shifts", a, shift, expected=201)
        route = "/scheduled-shifts/" + shift["id"]
        span = f"/scheduled-shifts?from={day}&to={day}"
        row = self.one(self.request("GET", span, a))
        self.assertEqual((row["name"], row["shuttleId"], row["notesBase64"], row["canRemove"]),
                         (d["fullName"], "CENTRAL-1", "Tm90ZXM=", "true"))
        notice = self.one(self.request("GET", "/notifications", d))
        self.assertEqual(notice["title"], "New Shift Assigned")
        self.assertIn("12:00 AM to 12:00 PM", notice["message"])
        self.assertIn("Shuttle CENTRAL-1", notice["message"])
        self.assertEqual(self.request("GET", "/notifications", other), [])
        self.assertEqual(self.one(self.request("GET", "/notifications/unread", d))["unread"], "1")
        self.assertEqual(self.one(self.request("GET", span, d))["canRemove"], "false")
        self.request("DELETE", route, expected=401)
        self.request("DELETE", route, d, expected=403)
        self.request("DELETE", route, b, expected=404)
        self.assertEqual(self.one(self.request("GET", span, a))["id"], shift["id"])
        self.request("PUT", "/notifications/" + notice["id"] + "/read", d)
        self.request("DELETE", route, a)
        self.request("DELETE", route, a)
        self.restart_server()
        self.request("POST", "/scheduled-shifts", a, shift, expected=409)
        self.assertEqual(self.request("GET", span, a), [])
        self.assertEqual(self.request("GET", span, d), [])
        history = self.one(self.request("GET", "/notifications", d))
        self.assertEqual((history["id"], history["read"]), (notice["id"], "true"))
        self.assertIn("schedule was removed", history["message"])
        self.request("GET", "/shuttles/CENTRAL-1", a)
        self.request("GET", "/workers/" + d["employeeId"], a)

    def test_started_schedules_cannot_be_removed(self):
        a, _, d, _ = self.linked_fleets()
        self.shuttle(a, "CENTRAL-1")
        shift_id = str(uuid.uuid4())
        self.stop_server()
        (self.workdir / "data/dated-shifts.txt").write_text(
            "id,terminalId,employeeId,shuttleId,shiftDate,startTime,endTime,notesBase64\n"
            + f"{shift_id},{a['employeeId']},{d['employeeId']},CENTRAL-1,2020-01-01,10:00,11:00,\n",
            encoding="utf-8",
        )
        self.restart_server()
        self.request("DELETE", "/scheduled-shifts/" + shift_id, a, expected=409)
        row = self.one(self.request("GET", "/scheduled-shifts?from=2020-01-01&to=2020-01-01", a))
        self.assertEqual((row["canRemove"], row["status"]), ("false", "Past assignment"))

    def test_weekly_removal_is_scoped_to_one_rule(self):
        a, b, d, other = self.linked_fleets()
        day = (date.today() + timedelta(days=7)).isoformat()
        current = {"employeeId": d["employeeId"], "shift": "2", "days": "1111111"}
        retained = dict(current, shift="1")
        self.request("PUT", "/schedule", a, [current, retained])
        self.request("PUT", "/schedule", b, dict(current, employeeId=other["employeeId"]))
        path = f"/schedule/{d['employeeId']}?shift=2&days=1111111&date={day}"
        self.assertTrue(all(row["canRemove"] == "false" for row in self.request("GET", "/schedule", d)))
        self.request("DELETE", path, expected=401)
        self.request("DELETE", path, d, expected=403)
        self.request("PUT", "/schedule", d, retained, expected=403)
        self.request("DELETE", path, b, expected=403)
        self.request("DELETE", path.replace(day, "2020-01-01"), a, expected=409)

        self.shuttle(a, "CENTRAL-1")
        self.request("PUT", f"/workers/{d['employeeId']}/assign", a, {"shuttleId": "CENTRAL-1"})
        self.request("POST", "/shift/start", d, {}, expected=201)
        self.assertTrue(all(row["canRemove"] == "false" for row in self.request("GET", "/schedule", a)))
        self.request("DELETE", path, a, expected=409)
        self.request("POST", "/shift/end", d, {"distanceKm": 0, "stops": 0})
        history = self.request("GET", "/shift", d)
        self.assertTrue(history)
        self.assertTrue(all(row["canRemove"] == "true" for row in self.request("GET", "/schedule", a)))

        reply = self.one(self.request("DELETE", path, a))
        self.assertEqual(reply["removed"], "true")
        self.restart_server()
        self.assertEqual(self.one(self.request("GET", "/schedule", a))["shift"], retained["shift"])
        self.assertEqual(self.one(self.request("GET", "/schedule", b))["employeeId"], other["employeeId"])
        self.assertEqual(self.request("GET", "/shift", d), history)

    def test_unjoined_operator_can_view_but_cannot_remove_legacy_rule(self):
        operator = self.users["operator_a"]
        employee_id = operator["employeeId"]
        self.stop_server()
        (self.workdir / "data/schedule.txt").write_text(
            f"employeeID,shift,days\n{employee_id},2,1111111\n",
            encoding="utf-8",
        )
        self.restart_server()
        day = (date.today() + timedelta(days=7)).isoformat()
        url = f"/schedule/{employee_id}?shift=2&days=1111111&date={day}"
        rule = self.one(self.request("GET", "/schedule", operator))
        self.assertEqual(rule["canRemove"], "false")
        self.request("DELETE", url, operator, expected=409)
        self.assertEqual(self.one(self.request("GET", "/schedule", operator)), rule)

    def test_legacy_expiry_does_not_disable_unused_code(self):
        a, d = self.users["operator_a"], self.users["driver_a"]
        self.terminal(a, "Central Terminal")
        code = self.invite(a)["inviteCode"]
        self.stop_server()
        path = self.workdir / "data/terminals.txt"
        with path.open(newline="", encoding="utf-8") as stream:
            reader = csv.DictReader(stream)
            fields, rows = list(reader.fieldnames), list(reader)
        fields.remove("enabled")
        for row in rows:
            row.pop("enabled")
            if row["id"] == code:
                row["expiresAt"] = "1"
        with path.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fields, lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)
        self.restart_server()
        self.join(d, code)

    def test_invitation_toggle_persists_and_only_controls_unused_code(self):
        a, b, d, e = (self.users[key] for key in ("operator_a", "operator_b", "driver_a", "driver_b"))
        self.terminal(a, "Central Terminal")
        self.terminal(b, "North Terminal")
        self.join(d, self.invite(a)["inviteCode"])
        code = self.invite(a)["inviteCode"]
        self.assertEqual(self.invite(a)["inviteCode"], code)
        payload = {"inviteCode": code, "enabled": "false"}
        self.request("PUT", "/fleet/invites", d, payload, expected=403)
        self.request("PUT", "/fleet/invites", b, payload, expected=409)
        self.request("PUT", "/fleet/invites", a, payload)
        message = "This invite code is invalid, disabled, or already used. Ask the operator for a new one."
        rejected = self.one(self.request("POST", "/fleet/preview", e, {"inviteCode": code}, expected=403))
        self.assertEqual(rejected["error"], message)
        self.join(e, code, expected=403)
        self.restart_server()
        current = self.one(self.request("GET", "/fleet/invites", a))
        self.assertEqual((current["inviteCode"], current["enabled"]), (code, "false"))
        self.assertEqual(self.one(self.request("GET", "/fleet", d))["joined"], "true")
        payload["enabled"] = "true"
        self.request("PUT", "/fleet/invites", a, payload)
        self.join(e, code)
        self.request("PUT", "/fleet/invites", a, payload, expected=409)
        current = self.one(self.request("GET", "/fleet/invites", a))
        self.assertEqual((current["used"], current["enabled"]), ("true", "false"))


if __name__ == "__main__":
    unittest.main()
