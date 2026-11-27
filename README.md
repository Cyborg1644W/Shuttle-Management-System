# Shuttle Management System

A resilient, offline-capable mobile tracking and shift management system built entirely in **Pure Java**.

This system tracks shuttle units, manages driver shifts, and provides live telemetry to dispatchers. It pairs a native Android client with a lightweight, database-free backend that uses only the JDK. It deliberately avoids heavy frameworks (no Spring, Hibernate, Retrofit, or Google Maps SDK) to keep the footprint small, API costs at zero, and data flow fully under your control.

## 🏗️ System Architecture

The project is divided into three Gradle modules (Groovy build scripts, no Kotlin):

1. **`:common`**: Shared domain models (Workers, Shifts, GPS Coordinates) used by both the server and the app.
2. **`:server-backend`**: A JDK 17 HTTP server built on `com.sun.net.httpserver`. Data is stored in local CSV files using thread-safe atomic file swapping.
3. **`:app`**: A native Android client (Java/XML) with background GPS tracking, an interactive OpenStreetMap driver map, and an offline-first queue for cellular dead zones.

```text
Driver Phone (Android) -------\
                               +--HTTP(S)--> Shuttle Server --reads---> import/  <-- Company DB
Dispatcher Phone (Android) ---/              (Java, files)  --writes--> export/  --> Company DB
```

## ✨ Key Features

### Mobile Client (Android)
* **Offline-First Resilience (`OfflineQueueManager`):** Caches GPS coordinates locally during cellular dead spots and flushes them to the server when connectivity returns.
* **Background Tracking:** Uses an Android Foreground Service to keep collecting hardware GPS coordinates while the app is in the background. (Aggressive manufacturer battery optimization can still stop it; see Known Limitations.)
* **Driver Live Route** follows the shuttle on OpenStreetMap, records the path locally during a shift, and fits the completed trip to the card.
* **Operator Fleet Status** shows active shifts on Home. Select a unit in the map or list to follow it, use two fingers to pan or zoom, or tap **Fit All** to return to the overview. **Completed trips** opens a saved shift's route.
* **Role-Based Routing:** Separate interfaces for Drivers, Dispatchers, and Administrators using standard Android XML layouts and plain Java activities.

#### Fleet monitoring

`FleetMonitorViewModel` polls the operator-scoped `GET /api/live/fleet` feed five seconds after each response while Home is visible. Each shift sends only fixes newer than the operator's last timestamp. The feed includes shifts awaiting a first fix. It retains live trails in server memory until shift end. The driver's saved trip supplies the completed geometry.

`FleetStatusMapUiState` retains positions, selection and camera mode across connectivity changes and rotation. A fix becomes stale after 30 seconds. Speed below 3 km/h indicates idle, and an idle unit within 50 metres of its configured terminal is shown as **At terminal**. Both roles share osmdroid street tiles, the tile cache, shuttle icon, segmented polylines and camera fitting through `StreetMapCard`.

Each operator manages one terminal. Its optional `terminalLatitude` and `terminalLongitude` fields can be supplied together on terminal creation. Older records still load. The map uses a Philippines overview when both terminal and shuttle positions are unavailable.

### Server Backend (Pure JDK)
* **Thread-Safe CSV Persistence:** No external database. Data lives in structured CSV files, written through atomic file swapping and file locking (`AtomicFileWriter.java`) to prevent race conditions.
* **Smart Shift Management:** Server daemon threads track active shifts and close them automatically if a driver's connection drops permanently.
* **GPS Anti-Spoofing:** Server-side distance-over-time checks reject impossible "teleportation" jumps and faked location pings.
* **Secure Invites & Auth:** Salted password hashing (PBKDF2-SHA256). Admin registration invites expire after 48 hours and are single-use.
* **Append-Only Audit Logging:** Every data creation, modification, and administrative action is appended to a timestamped transaction log.

## 📂 Project Structure

```text
shuttle-management-system/
|-- build.gradle                 # Root Gradle build (Groovy, zero Kotlin)
|-- settings.gradle              # include ':common', ':server-backend', ':app'
|
|-- common/                      # 1. SHARED MODULE
|   |-- build.gradle
|   `-- src/main/java/com/shuttle/common/
|       |-- Worker.java          # Shared domain models (Driver, Admin, Dispatcher)
|       |-- Role.java
|       |-- ShiftRecord.java
|       |-- ShuttleUnit.java
|       |-- GpsCoordinate.java
|       |-- CsvUtil.java         # CSV formatting and parsing tools
|       |-- ValidationUtil.java
|       `-- DurationFormatter.java
|
|-- server-backend/              # 2. SERVER MODULE
|   |-- build.gradle             # Depends on project(':common')
|   `-- src/main/java/com/shuttle/server/
|       |-- ServerMain.java      # Bootstraps the com.sun HTTP server
|       |-- ShuttleServer.java   # HTTP server wrapper and thread manager
|       |-- ServerConfig.java
|       |-- Auth.java            # RBAC and session token validation
|       |-- Crypto.java          # PBKDF2 / SHA-256 password hashing
|       |-- RateLimiter.java     # Brute-force prevention
|       |-- Log.java             # Append-only audit logging
|       |-- ApiException.java    # Standardized API error responses
|       |
|       |-- handler/             # API routing endpoints
|       |   |-- ApiHandler.java, AuthHandler.java, ShiftHandler.java
|       |   `-- LocationHandler.java, LiveHandler.java, InviteHandler.java
|       |
|       |-- service/             # Business logic & heartbeat threads
|       |   |-- ShiftService.java, TrackingService.java
|       |   `-- InviteService.java, RegistrationService.java
|       |
|       |-- repository/          # In-memory caches + CSV syncing
|       |   |-- WorkerRepository.java, ShiftRepository.java
|       |   `-- InviteRepository.java, ShuttleRepository.java
|       |
|       `-- io/                  # File operations
|           |-- CsvTable.java
|           |-- AtomicFileWriter.java  # Atomic writes + file locking
|           `-- ImportService.java, ExportService.java
|
|-- app/                         # 3. ANDROID CLIENT MODULE
|   |-- build.gradle             # Depends on project(':common')
|   `-- src/main/
|       |-- AndroidManifest.xml  # GPS, Internet, and Foreground Service permissions
|       |-- java/com/shuttle/mobile/
|       |   |-- client/          # Networking & syncing
|       |   |   |-- ApiClient.java
|       |   |   `-- OfflineQueueManager.java  # Offline-first GPS caching
|       |   |
|       |   |-- gps/             # Hardware location
|       |   |   |-- GpsTrackerService.java    # Android Foreground Service
|       |   |   `-- SimulatedGpsSource.java
|       |   |
|       |   `-- ui/              # Screen controllers
|       |       |-- MainActivity.java
|       |       |-- LoginActivity.java
|       |       |-- RegisterActivity.java
|       |       |-- DriverActivity.java
|       |       |-- DispatcherActivity.java
|       |       |-- AdminActivity.java
|       |       |-- LiveRouteMap.java         # Driver street map and trip geometry
|       |       `-- ShiftSummaryActivity.java
|       |
|       `-- res/
|           |-- layout/          # XML layout files
|           `-- values/          # Strings, colors, styles
|
|-- sample-data/import/          # Seed data for testing
|   |-- workers.txt
|   `-- shuttles.txt
`-- docs/                        # API and schema documentation
    |-- FILE_FORMATS.md
    `-- API.md
```


## 📷 Screenshot

<img width="1102" height="552" alt="Dispatcher map view showing live shuttle positions" src="https://github.com/user-attachments/assets/d07912a9-52c9-4b2b-876a-9c623ad43d08" />

## ⚡ Quick Start

You need **JDK 17–21**, the **Android SDK** (with `ANDROID_HOME` set or `sdk.dir` in `local.properties`), and **Git**. Ensure `JAVA_HOME` points to that JDK before running Gradle. Android Studio should use the same Gradle JDK to avoid separate incompatible daemons.

**0. Clone the repository**

```bash
git clone https://github.com/your-username/shuttle-management-system.git
cd shuttle-management-system
```

**1. Build the project**

```bash
bash ./gradlew :server-backend:installDist :app:assembleDebug
```

**2. Load the sample data (optional)**

Copy the files in `sample-data/import/` into the server's `import/` directory so there are workers and shuttles to work with. See `docs/FILE_FORMATS.md` for the column layout.

**3. Start the server**

```bash
./run-server.sh
```

On Windows, use `run-server.bat`. The launcher builds an up-to-date installable backend distribution, then starts Java directly rather than leaving a Gradle `run` task attached to Android Studio. The server binds to `0.0.0.0:8080` by default; check `http://127.0.0.1:8080/health`. `./gradlew :server-backend:run` is also valid, but it intentionally remains active while the HTTP server is running.

By default the server stores comma-separated tables in local `.txt` files. On upgrade, stop the old server before starting this version. Startup renames existing local `.csv` tables byte for byte. If both extensions exist for one table, resolve the duplicate before startup. The Render + R2 deployment below uses private cloud storage.

**4. Install the Android app**

Connect an Android device (or start an emulator) and run:

```bash
bash ./gradlew :app:installDebug
```

Fresh installations and upgrades from 1.0 start in **Automatic**. The sign-in screen shows the active address. The release app uses `https://shuttle-server-wh1z.onrender.com/api`. Sign in directly. An upgrade preserves a previously entered address in **Server connection → Custom server**. Select **Custom server** and **Save** to use that address again, or choose **Automatic → Save** to return to the default.

Debug builds can override the automatic address with the `shuttleApiBaseUrl` Gradle property, `SHUTTLE_API_BASE_URL` environment variable, or `shuttleApiBaseUrl` in `local.properties`, in that order. A custom server explicitly selected on the phone takes precedence on subsequent launches. For local HTTP development, a phone on the same Wi-Fi can use an address such as `http://192.168.1.10:8080`. The emulator can use `http://10.0.2.2:8080`. Use HTTPS for custom servers in release builds.

**5. First login workflow**

1. Register independent **Operator** and **Driver** accounts. A driver can sign in before belonging to any terminal.
2. On the operator's phone, tap **Create your terminal** and enter its name.
3. Tap **Invite drivers**, then **Invite a driver**. Copy the code or share it through a messaging app.
4. On the driver's phone, tap **Join terminal**, enter the code, check the terminal and operator, then confirm.
5. The driver appears in that operator's staff list. Vehicle assignments use the terminal's own shuttles, and shifts require terminal membership and an assigned vehicle.

Each terminal has one current, single-use invitation. Operators can disable or re-enable an unused code. There is no time limit. Codes and memberships survive backend restarts; sign in again after restarting because sessions are kept in memory. A driver belongs to one terminal at a time. Accepting another terminal's invitation requires explicit confirmation and finishing any active shift; it clears the old vehicle assignment and schedule while retaining the account and personal trip history. Removing a driver from the staff list disconnects the terminal membership without deleting the driver's account.

Terminal membership, vehicle ownership and invitations are persisted together in `terminals.txt`, next to `workers.txt`. Workers, vehicles, schedules, shift reports and locations are filtered by terminal. Existing CSV records without terminal ownership are not automatically assigned to a newly created terminal; existing drivers should accept invitations, and vehicle ownership needs an explicit data migration. Deleted shuttle IDs remain reserved to their original terminal so historical tracking cannot be exposed through ID reuse.

The invitation feature uses no paid messaging or QR service. It does not provide hosting: both phones still require internet access to the shared backend for joining and live updates.

### Free prototype deployment: Render + R2

See [the deployment guide](docs/DEPLOY_RENDER_R2.md) for the private bucket, server settings, Docker deployment, and two-phone restart checks. The included `render.yaml` selects one Free Docker service and R2 storage; it does not provision services or contain credentials.

Set `STORAGE_MODE=r2` and the documented R2 environment settings on the hosted server. All five CSV tables, including persistent fleet invitations in `terminals.txt`, commit together in a private ZIP object. Conditional writes detect conflicting versions. A failed upload rolls back the request's repository/session changes, and an uncertain upload is checked against the exact saved snapshot before reporting success. Local development still defaults to file storage.

The sign-in, registration and fleet clients check readiness before submitting an action, allowing up to 90 seconds for a sleeping host. Only the health GET is retried; account creation, joining and shift actions are never automatically repeated.

### Integration checks

After building `:server-backend:installDist`, run:

```bash
python3 -B -m unittest discover -s server-backend/tests -v
```

The tests launch isolated temporary Java servers and exercise registration, invitations, terminal isolation, switching, concurrent redemption, expiration, and restart persistence. The R2 tests use a local S3-compatible test service that verifies request signatures and injects storage failures, version conflicts and uncertain uploads. They do not use a real bucket or incur cloud charges. For a custom build output location, set `SHUTTLE_SERVER_LIB_DIR` to the generated distribution's `lib` directory.

The Android readiness helper also has standalone JVM tests:

```bash
mkdir -p /tmp/shuttle-readiness-tests
javac -d /tmp/shuttle-readiness-tests app/src/main/java/com/shuttle/mobile/client/ServerReadiness.java app/tests/ServerReadinessTest.java
java -cp /tmp/shuttle-readiness-tests com.shuttle.mobile.client.ServerReadinessTest
```

## 🔒 Security & Production Readiness

Before real drivers use this system in production:

1. **Enable HTTPS:** Use the hosting provider's HTTPS address for phones. Render terminates HTTPS and forwards to the Java server's internal HTTP port. The local development server serves plain HTTP.
2. **Data privacy (Philippine Data Privacy Act, RA 10173, and GDPR where applicable):** Drivers are being actively tracked. Obtain explicit consent and set up automated data-retention rules for the GPS CSV files.
3. **Network accessibility:** For phones on public roads to sync, the server must be reachable from the internet, for example through port forwarding or a secure tunnel such as Cloudflare Tunnel or Tailscale.

## 🚧 Known Limitations

* **Session volatility:** Sessions live in RAM, so a server restart forces every mobile client to log in again.
* **Storage scale:** The CSV backend handles dozens of drivers and thousands of shifts well. For thousands of concurrent active drivers, back the `Repository` interfaces with JDBC.
* **Offline map coverage** includes tiles previously viewed on this phone at the cached zoom levels. The GPS trail continues across areas without cached streets.
* **Background tracking on Android:** Some manufacturers' battery optimizers kill foreground services anyway. Drivers may need to exempt the app from battery optimization.

---
*This software is open-source and free to use under the MIT License. See `LICENSE` for details.*
