
# Shuttle Management System

Manage shuttle units, drivers and shifts, with live tracking. Written in plain Java 17 (JDK only, no
libraries): a **server** that owns the data and a **Swing desktop client** for drivers, dispatchers and admins.

Storage is **files only** (CSV), and the company's database exchanges data with the system through a shared
folder, so the app never connects to a database. See `docs/FILE_FORMATS.md`.

| Login screen | Driver | Admin / dispatcher |
|---|---|---|
| ![login](docs/screenshots/login.png) | ![driver](docs/screenshots/driver.png) | ![admin](docs/screenshots/admin.png) |

## How it fits together

```
Driver app (Swing) ----\
Dispatcher (Swing) -----+--HTTP(S)--> Shuttle server --reads---> import/  <-- company database exports
Admin (Swing) ---------/                 (Java, files)  --writes--> export/ --> company database imports
```

Every device talks to one server, so there is nothing to synchronise. The server is the only program that
reads and writes the data files.

## Project structure

```
shuttle-management-system/
|-- pom.xml                         Maven build (optional, no dependencies)
|-- scripts/                        build / run-server / run-client / test / screenshots (.sh and .bat)
|-- config/server.properties.example
|-- sample-data/import/             example workers.csv and shuttles.csv from the "company database"
|-- docs/                           FILE_FORMATS.md, API.md, screenshots/
`-- src/
    |-- main/java/com/shuttle/
    |   |-- common/                 shared: Worker (abstract) > Driver, Admin, Dispatcher; ShiftRecord,
    |   |                           ShuttleUnit, GpsCoordinate, Role, CsvUtil, ValidationUtil, DurationFormatter
    |   |-- server/
    |   |   |-- ServerMain, ShuttleServer, ServerConfig, Auth, Crypto, RateLimiter, Log, ApiException
    |   |   |-- handler/            one class per feature: Auth, Shift, Location, Live, Invite (+ ApiHandler)
    |   |   |-- service/            rules: Shift, Tracking, Invite, Registration
    |   |   |-- repository/         file-backed: Worker, Shift, Invite, Shuttle
    |   |   `-- io/                 CsvTable, AtomicFileWriter, ImportService, ExportService
    |   `-- client/
    |       |-- ClientMain, ApiClient
    |       |-- gps/                GpsSource, SimulatedGpsSource
    |       `-- view/               MainFrame, LoginPanel, RegisterDialog, DriverPanel, DispatcherPanel,
    |                               AdminPanel, MapPanel, ShiftSummaryDialog, Theme, Async
    `-- test/java/com/shuttle/      UnitChecks, ApiChecks, UiPreview, Check (JDK only, no JUnit needed)
```

## Quick start (needs JDK 17+)

```
scripts/build.sh                                   (Windows: scripts\build.bat)
scripts/run-server.sh --bootstrap-admin you@example.com
```
The server prints a one-time invite code for the first admin. Then, in a second terminal:
```
scripts/run-client.sh                              (Windows: scripts\run-client.bat)
```
1. Click **Register with invite code**, enter your email, the code, your name and a password.
2. Log in. As admin, click **Invite worker...**, pick a role and give the code to that person.
3. A driver registers the same way, picks a shuttle, and taps **Start shift**. The dispatcher and admin see
   the driver, the time on shift and the position on the map within 5 seconds.
4. **End shift** shows the summary (time, distance) and writes `export/shifts_<id>.csv` and `gps_<id>.csv`.

To try the company-list check, copy `sample-data/import/*` into `import/` before starting the server.
Without those files the server uses 3 demo shuttles and accepts any invited email.

## What is built in for security

* Passwords: salted PBKDF2-SHA256 (210,000 rounds); invite codes: random, single use, expire in 48 h, stored
  only as a SHA-256 hash, tied to one email; the company's worker list (if present) must contain the email and role.
* Login: random 256-bit session tokens that expire (12 h), 5 wrong tries lock the email for 5 minutes, the
  same error for unknown email and wrong password, equal timing for both.
* **The server decides everything**: role checked on every request, shift start/end times come from the
  server clock, one active shift per driver and per shuttle, GPS points must be valid and inside the shift,
  a disabled worker is cut off on the next request.
* Inputs validated and size-limited; imported files are never trusted (bad rows skipped and logged);
  exports are written atomically and are safe to open in Excel (formula injection blocked).
* Server listens on `127.0.0.1` only unless you choose otherwise, and warns when traffic is not encrypted.

## Before real drivers use it (not done for you)

1. **HTTPS.** Without it passwords and tokens travel in clear text. For a first test with a self-signed
   certificate:
   ```
   keytool -genkeypair -alias shuttle -keyalg RSA -keysize 2048 -validity 365 -storetype PKCS12 \
           -keystore shuttle.p12 -storepass CHANGE-ME -dname "CN=your-server-name" -ext SAN=dns:your-server-name
   SSL_PASSWORD=CHANGE-ME java -Dshuttle.ssl.keystore=shuttle.p12 -cp out com.shuttle.server.ServerMain --bind 0.0.0.0 --port 8443
   java -Djavax.net.ssl.trustStore=shuttle.p12 -Djavax.net.ssl.trustStorePassword=CHANGE-ME -cp out com.shuttle.client.ClientMain https://your-server-name:8443
   ```
   For production use a certificate from a real authority, and ask company IT before exposing any port.
2. **Backups** of the `data/` folder, and a company-agreed schedule for the import/export jobs.
3. **Privacy.** Drivers are being tracked: tell them, get consent, set how long GPS files are kept
   (Philippine Data Privacy Act of 2012; ask a lawyer).
4. Know the limits below.

## Known limits (honest list)

* **GPS is simulated** on desktop (`SimulatedGpsSource`). Real phone GPS needs an Android client that calls the
  same API; the server does not change.
* **No Google sign-in yet**: login is email + password after an invite. Google sign-in needs an OAuth client
  and server-side verification of Google's ID token.
* Sessions live in memory, so everyone logs in again after a server restart. No two-factor login.
* CSV files suit a few dozen drivers and a few thousand shifts for **one company**. `shifts.csv` is rewritten on
  each start/end. If you sell this to several companies you need a real database.
* One server is a single point of failure; there is no audit log file yet (events go to the console).
* Not on Google Play: Play only distributes Android apps (needs the Android app above plus the Play fee
  and policy steps).

## Tests

```
scripts/test.sh          (Windows: scripts\test.bat)
```
`UnitChecks` (30 checks) covers CSV, GPS maths, validation, password hashing and shift logic. `ApiChecks`
(40 checks) starts a real server on a free port and checks registration, roles, shift rules, GPS validation,
exports, formula injection, lockout and restart persistence. `scripts/screenshots.sh` redraws the screenshots.

## Next steps

Port `server/` to **Spring Boot** (handlers become `@RestController`s, `ApiHandler` checks become Spring
Security rules; services and repositories carry over unchanged), add the Android driver app, then Google sign-in.
