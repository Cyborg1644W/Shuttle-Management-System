"""R2 persistence integration tests with a local S3-compatible test double.

Run after :server-backend:installDist with:
    python3 -m unittest discover -s server-backend/tests -p test_r2_storage.py -v

Set SHUTTLE_SERVER_LIB_DIR when the built distribution lives outside the checkout.
All storage credentials are synthetic; these tests never contact a cloud account.
"""

import concurrent.futures
import csv
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import zipfile


TEST_ACCESS_KEY = "fake-test-id"
TEST_SECRET_KEY = "fake-test-secret"
OBJECT_PATH = "/test-bucket/shuttle-state.zip"
DATASET_FILES = {"workers.txt", "shuttles.txt", "shifts.txt", "schedule.txt", "terminals.txt"}


def csv_bytes(rows, fields=None):
    stream = io.StringIO(newline="")
    fields = fields or list(rows[0])
    writer = csv.DictWriter(stream, fields, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return stream.getvalue().encode("utf-8")


def read_dataset(payload):
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        names = set(archive.namelist())
        if not DATASET_FILES.issubset(names):
            raise AssertionError("Cloud object must contain all five CSV tables")
        return {
            name: list(csv.DictReader(io.StringIO(archive.read(name).decode("utf-8"))))
            for name in DATASET_FILES
        }


def rename_first_worker(payload, name):
    """Simulate a different server saving a valid concurrent revision."""
    target = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(payload)) as source, zipfile.ZipFile(target, "w") as output:
        for member in source.infolist():
            content = source.read(member.filename)
            if member.filename == "workers.txt":
                reader = csv.DictReader(io.StringIO(content.decode("utf-8")))
                fields, rows = reader.fieldnames, list(reader)
                rows[0]["fullName"] = name
                content = csv_bytes(rows, fields)
            output.writestr(member, content)
    return target.getvalue()


class FakeS3:
    """Path-style S3 object with SigV4 verification and conditional writes."""

    def __init__(self):
        self.lock = threading.Lock()
        self.payload = None
        self.etag = None
        self.put_attempts = 0
        self.accepted_puts = 0
        self.gets = 0
        self.signature_errors = []
        self.next_put = None
        self.conflict_payload = None
        self.get_barrier = None
        self.barrier_remaining = 0
        state = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *_args):
                pass

            def respond(self, status, payload=b"", etag=None):
                self.send_response(status)
                self.send_header("Content-Length", str(len(payload)))
                if etag:
                    self.send_header("ETag", etag)
                self.end_headers()
                if payload:
                    self.wfile.write(payload)

            def authenticated(self, payload):
                try:
                    state.verify_signature(self.command, self.path, self.headers, payload)
                    if urllib.parse.urlsplit(self.path).path != OBJECT_PATH:
                        raise ValueError("Unexpected bucket/object path")
                    return True
                except ValueError as error:
                    with state.lock:
                        state.signature_errors.append(str(error))
                    self.respond(403)
                    return False

            def do_GET(self):
                if not self.authenticated(b""):
                    return
                with state.lock:
                    state.gets += 1
                    payload, etag = state.payload, state.etag
                    barrier = state.get_barrier if state.barrier_remaining else None
                    if barrier:
                        state.barrier_remaining -= 1
                if barrier:
                    try:
                        barrier.wait(timeout=5)
                    except threading.BrokenBarrierError:
                        pass
                self.respond(404 if payload is None else 200, payload or b"", etag)

            def do_PUT(self):
                payload = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                if not self.authenticated(payload):
                    return
                with state.lock:
                    state.put_attempts += 1
                    mode, state.next_put = state.next_put, None
                    if mode == "fail":
                        self.respond(503)
                        return
                    if mode == "conflict":
                        state.set_object_unlocked(state.conflict_payload)
                        self.respond(412)
                        return
                    if state.payload is None:
                        condition_matches = self.headers.get("If-None-Match") == "*"
                    else:
                        condition_matches = self.headers.get("If-Match") == state.etag
                    if not condition_matches:
                        self.respond(412)
                        return
                    state.set_object_unlocked(payload)
                    state.accepted_puts += 1
                    etag = state.etag
                    if mode == "drop_after_accept_change":
                        state.set_object_unlocked(rename_first_worker(payload, "External After Commit"))
                if mode in ("drop_after_accept", "drop_after_accept_change"):
                    # The client cannot know whether the write was accepted until
                    # it reads the object back. Avoid an HTTP error response.
                    self.close_connection = True
                    try:
                        self.connection.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    self.connection.close()
                    return
                self.respond(200, etag=etag)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.endpoint = f"http://127.0.0.1:{self.server.server_port}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def set_object_unlocked(self, payload):
        self.payload = payload
        self.etag = '"' + hashlib.md5(payload).hexdigest() + '"'

    def snapshot(self):
        with self.lock:
            return self.payload

    @staticmethod
    def verify_signature(method, raw_path, headers, payload):
        authorization = headers.get("Authorization", "")
        if not authorization.startswith("AWS4-HMAC-SHA256 "):
            raise ValueError("Missing AWS SigV4 authorization")
        try:
            values = dict(item.strip().split("=", 1) for item in authorization.split(" ", 1)[1].split(","))
            access_key, date, region, service, terminator = values["Credential"].split("/")
            signed_headers = values["SignedHeaders"]
            signature = values["Signature"]
        except (ValueError, KeyError):
            raise ValueError("Malformed SigV4 authorization") from None
        if access_key != TEST_ACCESS_KEY or service != "s3" or terminator != "aws4_request":
            raise ValueError("Unexpected SigV4 credential scope")
        actual_hash = hashlib.sha256(payload).hexdigest()
        if headers.get("x-amz-content-sha256") != actual_hash:
            raise ValueError("Payload SHA256 does not match the signed content")
        stamp = headers.get("x-amz-date", "")
        if not stamp.startswith(date):
            raise ValueError("SigV4 date and credential date differ")
        names = signed_headers.split(";")
        if names != sorted(names) or not {"host", "x-amz-date", "x-amz-content-sha256"}.issubset(names):
            raise ValueError("Required canonical headers are not signed")
        canonical_headers = ""
        for name in names:
            value = headers.get(name)
            if value is None:
                raise ValueError("A signed header is missing")
            canonical_headers += name + ":" + re.sub(r"\s+", " ", value.strip()) + "\n"
        parsed = urllib.parse.urlsplit(raw_path)
        encode = lambda value: urllib.parse.quote(value, safe="-_.~")
        query = "&".join(
            encode(key) + "=" + encode(value)
            for key, value in sorted(urllib.parse.parse_qsl(parsed.query, keep_blank_values=True))
        )
        canonical = "\n".join((method, parsed.path, query, canonical_headers, signed_headers, actual_hash))
        scope = "/".join((date, region, service, terminator))
        to_sign = "\n".join(("AWS4-HMAC-SHA256", stamp, scope, hashlib.sha256(canonical.encode()).hexdigest()))
        key = ("AWS4" + TEST_SECRET_KEY).encode()
        for part in (date, region, service, terminator):
            key = hmac.new(key, part.encode(), hashlib.sha256).digest()
        expected = hmac.new(key, to_sign.encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, signature):
            raise ValueError("SigV4 signature does not match the canonical request")


class Backend:
    def __init__(self, parent, root, extra_env=None):
        self.parent = parent
        self.root = root
        root.mkdir(parents=True, exist_ok=True)
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            self.port = reservation.getsockname()[1]
        self.base_url = f"http://127.0.0.1:{self.port}/api"
        # Do not pass cloud credentials from the developer's environment into tests.
        environment = {name: os.environ[name] for name in ("PATH", "JAVA_HOME", "LANG", "LC_ALL") if name in os.environ}
        environment.update({
            "STORAGE_MODE": "r2", "R2_ENDPOINT": parent.s3.endpoint,
            "R2_ALLOW_HTTP_LOCALHOST": "true", "R2_BUCKET": "test-bucket",
            "R2_ACCESS_KEY_ID": TEST_ACCESS_KEY, "R2_SECRET_ACCESS_KEY": TEST_SECRET_KEY,
            "R2_OBJECT_KEY": "shuttle-state.zip", "DATA_DIR": str(root / "data"), "PORT": str(self.port),
        })
        if extra_env:
            environment.update(extra_env)
        self.log = (root / "backend-output.log").open("wb")
        self.process = subprocess.Popen(
            [parent.java, "-cp", str(parent.lib_dir / "*"), "com.shuttle.server.ServerMain"],
            cwd=root, env=environment, stdout=self.log, stderr=subprocess.STDOUT,
        )

    def await_ready(self):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                self.parent.fail("R2 backend exited before HTTP readiness")
            try:
                with self.parent.opener.open(self.base_url + "/health", timeout=1) as response:
                    if response.status == 200 and response.read().decode() == "status\nok\n":
                        return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(0.05)
        self.parent.fail("R2 backend did not pass HTTP readiness")

    def assert_startup_rejected(self):
        try:
            code = self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.parent.fail("Invalid storage configuration left a backend process running")
        self.parent.assertNotEqual(code, 0, "Invalid cloud state/configuration must fail startup")
        try:
            self.parent.opener.open(self.base_url + "/health", timeout=0.5)
        except (OSError, urllib.error.URLError):
            return
        self.parent.fail("Invalid cloud state/configuration exposed a healthy backend")

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        self.log.close()


class R2StorageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        default = Path(__file__).resolve().parents[1] / "build/install/server-backend/lib"
        cls.lib_dir = Path(os.environ.get("SHUTTLE_SERVER_LIB_DIR", default)).resolve()
        if not list(cls.lib_dir.glob("*.jar")):
            raise RuntimeError("Build :server-backend:installDist or set SHUTTLE_SERVER_LIB_DIR first")
        java_home = os.environ.get("JAVA_HOME")
        cls.java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
        if not cls.java:
            raise RuntimeError("Java is required for R2 integration tests")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="shuttle-r2-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.s3 = FakeS3()
        self.addCleanup(self.s3.close)
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.backends = []
        self.users = []
        self.password = secrets.token_urlsafe(18)

    def tearDown(self):
        self.assertEqual(self.s3.signature_errors, [], "All R2 requests must use valid SigV4 signing")

    def backend(self, ready=True, extra_env=None):
        backend = Backend(self, self.root / f"instance-{len(self.backends)}", extra_env)
        self.backends.append(backend)
        self.addCleanup(backend.close)
        if ready:
            backend.await_ready()
        return backend

    def request(self, backend, method, path, user=None, body=None, expected=200):
        headers = {"Accept": "text/csv"}
        if user:
            headers["Authorization"] = "Bearer " + user["token"]
        data = None
        if body is not None:
            rows = body if isinstance(body, list) else [body]
            data = csv_bytes(rows) if rows and rows[0] else b""
            headers["Content-Type"] = "text/csv; charset=utf-8"
        request = urllib.request.Request(backend.base_url + path, data=data, method=method, headers=headers)
        try:
            response = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status = response.status
            raw = response.read().decode("utf-8")
            content_type = response.headers.get("Content-Type", "")
        allowed = expected if isinstance(expected, tuple) else (expected,)
        self.assertIn(status, allowed, f"{method} {path}: unexpected HTTP {status}")
        self.assertIn("text/csv", content_type)
        rows = list(csv.DictReader(io.StringIO(raw))) if raw.strip() else []
        if status >= 400:
            self.assertTrue(rows and rows[0].get("error"), "Errors must include a CSV error message")
            self.assertNotIn("token", rows[0], "Uncommitted operations must not expose a session")
        return rows

    def one(self, rows):
        self.assertEqual(len(rows), 1)
        return rows[0]

    def registration(self, role, index):
        return {"role": role, "name": f"Storage Test {index}", "email": f"storage{index}@example.test",
                "phone": f"+639180000{index:03d}", "password": self.password}

    def register(self, backend, role="DRIVER"):
        index = len(self.users) + 1
        body = self.registration(role, index)
        user = self.one(self.request(backend, "POST", "/auth/register", body=body, expected=201))
        self.assertTrue(user.get("token"))
        user["login_email"] = body["email"]
        self.users.append(user)
        return user

    def login(self, backend, user):
        result = self.one(self.request(backend, "POST", "/auth/login", body={
            "email": user["login_email"], "password": self.password,
        }))
        self.assertEqual(result["employeeId"], user["employeeId"])
        return {**user, "token": result["token"]}

    def connected_accounts(self, backend):
        operator = self.register(backend, "OPERATOR")
        driver = self.register(backend)
        self.request(backend, "POST", "/fleet", operator, {"terminalName": "Cloud Terminal"}, expected=201)
        invitation = self.one(self.request(backend, "POST", "/fleet/invites", operator, {}, expected=201))
        return operator, driver, invitation["inviteCode"]

    def test_signed_snapshots_restore_accounts_membership_and_used_invites_to_empty_instance(self):
        first = self.backend()
        operator, driver, used_code = self.connected_accounts(first)
        self.request(first, "POST", "/fleet/join", driver, {"inviteCode": used_code})
        unused_code = self.one(self.request(first, "POST", "/fleet/invites", operator, {}, expected=201))["inviteCode"]
        self.assertTrue(read_dataset(self.s3.snapshot())["workers.txt"])
        first.close()
        restored = self.backend()
        driver = self.login(restored, driver)
        self.assertEqual(self.one(self.request(restored, "GET", "/fleet", driver))["terminalName"], "Cloud Terminal")
        other = self.register(restored)
        self.request(restored, "POST", "/fleet/join", other, {"inviteCode": used_code}, expected=403)
        self.request(restored, "POST", "/fleet/join", other, {"inviteCode": unused_code})
        self.assertEqual(self.one(self.request(restored, "GET", "/fleet", other))["joined"], "true")

    def test_legacy_snapshot_is_read_and_next_write_uses_txt(self):
        first = self.backend()
        operator = self.register(first, "OPERATOR")
        first.close()
        legacy = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(self.s3.snapshot())) as source, zipfile.ZipFile(legacy, "w") as target:
            for name in source.namelist():
                old_name = Path(name).with_suffix(".csv").name if name in DATASET_FILES else name
                target.writestr(old_name, source.read(name))
        with self.s3.lock:
            self.s3.set_object_unlocked(legacy.getvalue())
        restored = self.backend()
        operator = self.login(restored, operator)
        self.request(restored, "POST", "/fleet", operator, {"terminalName": "Upgraded Fleet"}, expected=201)
        with zipfile.ZipFile(io.BytesIO(self.s3.snapshot())) as archive:
            self.assertEqual(set(archive.namelist()), DATASET_FILES | {"commit.txt"})
        workers = read_dataset(self.s3.snapshot())["workers.txt"]
        self.assertEqual([row["employeeID"] for row in workers], [operator["employeeId"]])

    def test_failed_signup_is_not_acknowledged_or_persisted_and_can_be_retried(self):
        backend = self.backend()
        before = self.s3.snapshot()
        self.s3.next_put = "fail"
        body = self.registration("DRIVER", 1)
        self.request(backend, "POST", "/auth/register", body=body, expected=503)
        self.assertEqual(self.s3.snapshot(), before)
        self.request(backend, "POST", "/auth/login", body={
            "email": body["email"], "password": self.password,
        }, expected=401)
        self.register(backend)
        self.assertEqual(len(read_dataset(self.s3.snapshot())["workers.txt"]), 1)

    def test_failed_join_rolls_back_membership_and_code_consumption(self):
        backend = self.backend()
        _, driver, code = self.connected_accounts(backend)
        before = self.s3.snapshot()
        self.s3.next_put = "fail"
        self.request(backend, "POST", "/fleet/join", driver, {"inviteCode": code}, expected=503)
        self.assertEqual(self.s3.snapshot(), before)
        self.assertEqual(self.one(self.request(backend, "GET", "/fleet", driver))["joined"], "false")
        self.request(backend, "POST", "/fleet/join", driver, {"inviteCode": code})
        state = read_dataset(self.s3.snapshot())["terminals.txt"]
        invite = next(row for row in state if row["type"] == "INVITE" and row["id"] == code)
        self.assertEqual(invite["usedBy"], driver["employeeId"])

    def test_shift_commits_all_tables_once_and_failed_commit_leaves_no_partial_shift(self):
        backend = self.backend()
        operator, driver, code = self.connected_accounts(backend)
        self.request(backend, "POST", "/fleet/join", driver, {"inviteCode": code})
        self.request(backend, "POST", "/shuttles", operator, {
            "shuttleId": "CLOUD-1", "plateNumber": "CLOUD-PLATE", "capacity": 12,
        }, expected=201)
        self.request(backend, "PUT", "/workers/" + driver["employeeId"] + "/assign", operator,
                     {"shuttleId": "CLOUD-1"})
        before = self.s3.snapshot()
        self.s3.next_put = "fail"
        self.request(backend, "POST", "/shift/start", driver, {}, expected=503)
        self.assertEqual(self.s3.snapshot(), before)
        self.assertEqual(self.request(backend, "GET", "/shift/active", driver), [])
        profile = self.one(self.request(backend, "GET", "/workers/" + driver["employeeId"], driver))
        self.assertEqual(profile["activeShiftId"], "")
        self.assertEqual(self.one(self.request(backend, "GET", "/shuttles/CLOUD-1", operator))["status"], "AVAILABLE")
        accepted_before = self.s3.accepted_puts
        self.request(backend, "POST", "/shift/start", driver, {}, expected=201)
        self.assertEqual(self.s3.accepted_puts - accepted_before, 1, "A multi-table mutation needs one conditional upload")
        tables = read_dataset(self.s3.snapshot())
        self.assertEqual(len(tables["shifts.txt"]), 1)
        self.assertEqual(tables["shifts.txt"][0]["status"], "ACTIVE")
        worker = next(row for row in tables["workers.txt"] if row["employeeID"] == driver["employeeId"])
        self.assertTrue(worker["activeShiftId"])
        self.assertEqual(tables["shuttles.txt"][0]["currentStatus"], "IN_SERVICE")

    def test_conditional_conflict_does_not_overwrite_external_revision(self):
        backend = self.backend()
        operator = self.register(backend, "OPERATOR")
        external = rename_first_worker(self.s3.snapshot(), "External Writer")
        self.s3.conflict_payload = external
        self.s3.next_put = "conflict"
        self.request(backend, "POST", "/fleet", operator, {"terminalName": "Uncommitted Terminal"}, expected=(409, 503))
        self.assertEqual(self.s3.snapshot(), external)
        self.assertEqual(self.one(self.request(backend, "GET", "/auth/me", operator))["fullName"], "External Writer")
        self.assertEqual(self.one(self.request(backend, "GET", "/fleet", operator))["joined"], "false")

    def test_accepted_write_with_dropped_response_is_reconciled_before_success(self):
        backend = self.backend()
        operator = self.register(backend, "OPERATOR")
        gets_before = self.s3.gets
        self.s3.next_put = "drop_after_accept"
        terminal = self.one(self.request(backend, "POST", "/fleet", operator, {"terminalName": "Reconciled Terminal"}, expected=201))
        self.assertEqual(terminal["terminalName"], "Reconciled Terminal")
        self.assertGreaterEqual(self.s3.gets - gets_before, 2, "An ambiguous write needs a verification GET")
        terminals = read_dataset(self.s3.snapshot())["terminals.txt"]
        self.assertEqual([row["name"] for row in terminals if row["type"] == "TERMINAL"], ["Reconciled Terminal"])

    def test_two_instances_redeeming_one_code_commit_only_one_membership(self):
        first = self.backend()
        _, driver, code = self.connected_accounts(first)
        other = self.register(first)
        second = self.backend()
        other = self.login(second, other)
        self.s3.get_barrier = threading.Barrier(2)
        self.s3.barrier_remaining = 2
        def redeem(args):
            backend, user = args
            return self.request(backend, "POST", "/fleet/join", user, {"inviteCode": code}, expected=(200, 403, 409, 503))
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            outcomes = list(executor.map(redeem, ((first, driver), (second, other))))
        self.assertEqual(sum(bool(rows and rows[0].get("joined") == "true") for rows in outcomes), 1)
        records = read_dataset(self.s3.snapshot())["terminals.txt"]
        self.assertEqual(sum(row["type"] == "MEMBER" for row in records), 1)
        invitation = next(row for row in records if row["type"] == "INVITE" and row["id"] == code)
        self.assertIn(invitation["usedBy"], (driver["employeeId"], other["employeeId"]))

    def test_ambiguous_write_with_different_verification_object_does_not_acknowledge_success(self):
        backend = self.backend()
        operator = self.register(backend, "OPERATOR")
        self.s3.next_put = "drop_after_accept_change"
        self.request(backend, "POST", "/fleet", operator, {"terminalName": "Unverified Terminal"}, expected=503)
        persisted = read_dataset(self.s3.snapshot())
        self.assertEqual(persisted["workers.txt"][0]["fullName"], "External After Commit")
        # The accepted write may exist, but the caller was not promised a save
        # that could not be verified. Later reads must use the external revision.
        self.assertEqual(self.one(self.request(backend, "GET", "/auth/me", operator))["fullName"], "External After Commit")

    def test_corrupt_archive_prevents_backend_startup(self):
        with self.s3.lock:
            self.s3.set_object_unlocked(b"not a valid ZIP archive")
        self.backend(ready=False).assert_startup_rejected()

    def test_incomplete_archive_prevents_backend_startup(self):
        partial = io.BytesIO()
        with zipfile.ZipFile(partial, "w") as archive:
            archive.writestr("workers.txt", "employeeID,fullName\n")
        with self.s3.lock:
            self.s3.set_object_unlocked(partial.getvalue())
        self.backend(ready=False).assert_startup_rejected()

    def test_missing_secret_prevents_backend_startup(self):
        self.backend(ready=False, extra_env={"R2_SECRET_ACCESS_KEY": ""}).assert_startup_rejected()
        self.assertEqual(self.s3.accepted_puts, 0)


if __name__ == "__main__":
    unittest.main()
