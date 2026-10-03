# Shuttle Management System

Manage shuttle units, drivers and shifts, with live tracking.

**Pure Java 17, JDK only.** No Maven, no Gradle, no Spring, no third-party libraries. It is a **server** that
owns the data and a **Swing desktop client** for drivers and dispatchers. A dispatcher is also the admin, so there are only two roles.

Storage is **files only** (CSV). The company's database exchanges data with the system through a shared folder,
so the app never connects to a database. See [`docs/FILE_FORMATS.md`](docs/FILE_FORMATS.md).

| Login screen | Driver | Dispatcher (admin) |
|---|---|---|
| ![login](docs/screenshots/login.png) | ![driver](docs/screenshots/driver.png) | ![dispatcher](docs/screenshots/admin.png) |

## Contents

1. [How it fits together](#how-it-fits-together)
2. [Roles](#roles)
3. [Project structure](#project-structure)
4. [Quick start](#quick-start-needs-jdk-17)
5. [Configuration](#configuration)
6. [Pure Java: what that means here](#pure-java-what-that-means-here)
7. [Security built in](#security-built-in)
8. [Before real drivers use it](#before-real-drivers-use-it-not-done-for-you)
9. [Known limits](#known-limits-honest-list)
10. [Tests](#tests)
11. [Troubleshooting](#troubleshooting)
12. [Roadmap](#roadmap)
13. [Documentation and license](#documentation-and-license)

## How it fits together

```
Driver app (Swing) -----\
                         +--HTTP(S)--> Shuttle server --reads---> import/  <-- company database exports
Dispatcher app (Swing) -/                (Java, files)  --writes--> export/ --> company database imports
```

Every device talks to one server, so there is nothing to synchronise. The server is the only program that reads
and writes the data files.

## Roles

| Role | What they do |
|---|---|
| **Dispatcher (admin)** | Invites workers and picks their role, watches live drivers, time on shift and positions on the map. |
| **Driver** | Registers with an invite code, picks a shuttle, starts and ends a shift. |

There is no separate admin role: the dispatcher is the admin. The role is checked by the server on every
request, never trusted from the client.

## Project structure

```
shuttle-management-system/
|-- scripts/                        build / run-server / run-client / test / screenshots (.sh and .bat)
|-- config/server.properties.example
|-- sample-data/import/             example workers.csv and shuttles.csv from the "company database"
|-- docs/                           FILE_FORMATS.md, API.md, screenshots/
`-- src/
    |-- main/java/com/shuttle/
    |   |-- common/                 shared: Worker (abstract) > Driver, Dispatcher; ShiftRecord,
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
    |                               MapPanel, ShiftSummaryDialog, Theme, Async
    `-- test/java/com/shuttle/      UnitChecks, ApiChecks, UiPreview, Check (JDK only, no JUnit needed)
```

Runtime folders created next to the project: `data/` (the server's CSV files), `import/` (files from the company
database), `export/` (files for the company database), `out/` (compiled classes).

## Quick start (needs JDK 17+)

You need a **JDK**, not just a JRE. Check with `java -version` and `javac -version`.

```
scripts/build.sh                                   (Windows: scripts\build.bat)
scripts/run-server.sh --bootstrap-admin you@example.com
```

The server prints a one-time invite code for the first dispatcher (the admin). Then, in a second terminal:

```
scripts/run-client.sh                              (Windows: scripts\run-client.bat)
```

1. Click **Register with invite code**, enter your email, the code, your name and a password.
2. Log in. As dispatcher, click **Invite worker...**, pick a role (dispatcher or driver) and give the code to that person.
3. A driver registers the same way, picks a shuttle, and taps **Start shift**. The dispatcher sees the
   driver, the time on shift and the position on the map within 5 seconds.
4. **End shift** shows the summary (time, distance) and writes `export/shifts_<id>.csv` and `gps_<id>.csv`.

To try the company-list check, copy `sample-data/import/*` into `import/` before starting the server. Without
those files the server uses 3 demo shuttles and accepts any invited email.

## Configuration

Defaults are safe for a single-machine trial. Everything below is optional.

| Setting | How | Purpose |
|---|---|---|
| `--bootstrap-admin <email>` | server flag | Creates the first dispatcher (admin) invite and prints the code. |
| `--bind <address>` | server flag | Address to listen on. Default `127.0.0.1` (this machine only). |
| `--port <number>` | server flag | Port to listen on. |
| `-Dshuttle.ssl.keystore=<file>` | JVM property | Turns on HTTPS using this PKCS12 keystore. |
| `SSL_PASSWORD` | environment variable | Keystore password (kept out of the command line and shell history). |
| `config/server.properties.example` | file | Copy to `config/server.properties` for the full list of server options. |

The client takes the server address as its first argument, for example
`java -cp out com.shuttle.client.ClientMain https://your-server-name:8443`.

## Pure Java: what that means here

* **Language and runtime:** Java 17, compiled with plain `javac`. The build scripts need nothing but the JDK.
* **Server:** the JDK's built-in HTTP(S) server (`com.sun.net.httpserver`), `java.security` / `javax.crypto` for
  PBKDF2, SHA-256 and random tokens, and `java.nio.file` for atomic file writes.
* **Client:** Swing (`java.desktop`) for the screens and `java.net.http.HttpClient` for calls to the server.
* **Data:** hand-written CSV reading and writing (`CsvUtil`, `CsvTable`). JSON, if any, is also hand-written.
* **Tests:** a tiny `Check` helper instead of JUnit.

You can prove it yourself at any time. Any import outside the JDK would show up here:

```
# every import in main code must be JDK or this project
grep -rhE "^import " src/main | sed 's/import static /import /' \
  | grep -vE "^import (java|javax|com\.sun\.net\.httpserver|com\.shuttle)\." | sort -u
# expected output: nothing

# which JDK modules the program needs
jdeps --print-module-deps --ignore-missing-deps out
```

If you only need the server on a machine, a headless JDK is enough; only the client needs `java.desktop`.

## Security built in

* **Passwords:** salted PBKDF2-SHA256 (210,000 rounds). Invite codes are random, single use, expire in 48 h,
  are stored only as a SHA-256 hash, and are tied to one email. If the company's worker list is present, it must
  contain the email and role.
* **Login:** random 256-bit session tokens that expire (12 h). 5 wrong tries lock the email for 5 minutes. Unknown
  email and wrong password give the same error with equal timing.
* **The server decides everything:** role checked on every request, shift start and end times come from the
  server clock, one active shift per driver and per shuttle, GPS points must be valid and inside the shift, and a
  disabled worker is cut off on the next request.
* **Inputs:** validated and size-limited. Imported files are never trusted (bad rows are skipped and logged).
  Exports are written atomically and are safe to open in Excel (formula injection blocked).
* **Network:** the server listens on `127.0.0.1` only unless you choose otherwise, and warns when traffic is not
  encrypted.

## Before real drivers use it (not done for you)

1. **HTTPS.** Without it, passwords and tokens travel in clear text. For a first test with a self-signed
   certificate:

   ```
   # 1. Server keystore (key + certificate)
   keytool -genkeypair -alias shuttle -keyalg RSA -keysize 2048 -validity 365 -storetype PKCS12 \
           -keystore shuttle.p12 -storepass CHANGE-ME -dname "CN=your-server-name" -ext SAN=dns:your-server-name

   # 2. Client truststore (certificate only)
   keytool -exportcert -alias shuttle -keystore shuttle.p12 -storepass CHANGE-ME -file shuttle.cer
   keytool -importcert -alias shuttle -file shuttle.cer -keystore client-truststore.p12 \
           -storetype PKCS12 -storepass CHANGE-ME -noprompt

   # 3. Start the server (Windows: set SSL_PASSWORD=CHANGE-ME, then run the java line)
   SSL_PASSWORD=CHANGE-ME java -Dshuttle.ssl.keystore=shuttle.p12 -cp out \
       com.shuttle.server.ServerMain --bind 0.0.0.0 --port 8443

   # 4. Start the client
   java -Djavax.net.ssl.trustStore=client-truststore.p12 -Djavax.net.ssl.trustStorePassword=CHANGE-ME \
        -cp out com.shuttle.client.ClientMain https://your-server-name:8443
   ```

   Change `CHANGE-ME` to your own password and give clients only the truststore, never `shuttle.p12`. For
   production use a certificate from a real authority, and ask company IT before exposing any port.
2. **Backups** of the `data/` folder, and a company-agreed schedule for the import/export jobs.
3. **Privacy.** Drivers are being tracked: tell them, get consent, and set how long GPS files are kept
   (Philippine Data Privacy Act of 2012, Republic Act 10173; ask a lawyer).
4. Read the limits below.

## Known limits (honest list)

* **GPS is simulated** on desktop (`SimulatedGpsSource`). Real phone GPS needs a mobile client that calls the same
  API; the server does not change.
* **No Google sign-in yet:** login is email + password after an invite. Google sign-in needs an OAuth client and
  server-side verification of Google's ID token.
* Sessions live in memory, so everyone logs in again after a server restart. No two-factor login.
* CSV files suit a few dozen drivers and a few thousand shifts for **one company**. `shifts.csv` is rewritten on
  each start and end. If you sell this to several companies you need a real database.
* One server is a single point of failure. There is no audit log file yet (events go to the console).
* Not on Google Play: Play only distributes Android apps (it would need the Android app above plus the Play fee
  and policy steps).

## Tests

```
scripts/test.sh          (Windows: scripts\test.bat)
```

* `UnitChecks` (30 checks): CSV, GPS maths, validation, password hashing and shift logic.
* `ApiChecks` (40 checks): starts a real server on a free port and checks registration, roles, shift rules, GPS
  validation, exports, formula injection, lockout and restart persistence.
* `scripts/screenshots.sh` redraws the screenshots in `docs/screenshots/`.

## Troubleshooting

| Problem | Likely cause and fix |
|---|---|
| `javac: command not found` | Only a JRE is installed. Install a JDK 17+ and reopen the terminal. |
| Client says it cannot connect | Server is bound to `127.0.0.1` but the client is on another machine. Start the server with `--bind 0.0.0.0` (over HTTPS) and check the firewall. |
| `PKIX path building failed` | The client does not trust the certificate. Use the truststore from the HTTPS steps and make the certificate name match the address you type. |
| Port already in use | Another program has it. Pick another with `--port`. |
| "Too many attempts" at login | 5 wrong passwords lock that email for 5 minutes. Wait, then try again. |
| Everyone was logged out | The server restarted. Sessions are in memory by design; log in again. |
| Registration refused | The invite code expired (48 h), was already used, was issued for a different email, or the email and role are missing from `import/workers.csv`. |
| Some imported rows missing | Bad rows are skipped on purpose. The server log lists each one and why. |

## Roadmap

Everything stays plain Java.

1. **Real GPS.** Add a driver app for phones that calls the existing API (an Android app is Java too, but it uses
   the Android SDK, so it lives in its own module and does not change the JDK-only server and desktop client).
2. **Google sign-in.** Verify Google's ID token on the server using `java.net.http` and the JDK's signature APIs.
3. **Durability.** Persistent sessions, an audit log file, and scheduled backups.
4. **Scale.** Keep the repository interfaces as they are and add a JDBC-backed implementation (JDBC is part of the
   JDK; only the vendor driver would be extra) when the company outgrows CSV files.

## Documentation and license

* [`docs/API.md`](docs/API.md): every endpoint, its role requirement and its errors.
* [`docs/FILE_FORMATS.md`](docs/FILE_FORMATS.md): columns of every import and export file.
* **License:** add a `LICENSE` file before sharing the code, so others know what they may do with it.
