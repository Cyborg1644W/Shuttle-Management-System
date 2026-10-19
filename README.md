# Shuttle Management System

A resilient, offline-capable mobile tracking and shift management system built entirely in **Pure Java**.

This system tracks shuttle units, manages driver shifts, and provides live telemetry to dispatchers. It pairs a native Android client with a lightweight, database-free backend that uses only the JDK. It deliberately avoids heavy frameworks (no Spring, Hibernate, Retrofit, or Google Maps SDK) to keep the footprint small, API costs at zero, and data flow fully under your control.

## 🏗️ System Architecture

The project is divided into three Gradle modules (Groovy build scripts, no Kotlin):

1. **`:common`**: Shared domain models (Workers, Shifts, GPS Coordinates) used by both the server and the app.
2. **`:server-backend`**: A JDK 17 HTTP server built on `com.sun.net.httpserver`. Data is stored in local CSV files using thread-safe atomic file swapping.
3. **`:app`**: A native Android client (Java/XML) with background GPS tracking, programmatic canvas map rendering, and an offline-first queue for cellular dead zones.

```text
Driver Phone (Android) -------\
                               +--HTTP(S)--> Shuttle Server --reads---> import/  <-- Company DB
Dispatcher Phone (Android) ---/              (Java, files)  --writes--> export/  --> Company DB
```

## ✨ Key Features

### Mobile Client (Android)
* **Offline-First Resilience (`OfflineQueueManager`):** Caches GPS coordinates locally during cellular dead spots and flushes them to the server when connectivity returns.
* **Background Tracking:** Uses an Android Foreground Service to keep collecting hardware GPS coordinates while the app is in the background. (Aggressive manufacturer battery optimization can still stop it; see Known Limitations.)
* **Custom Map Rendering:** Avoids paid third-party map APIs by plotting live fleet telemetry onto a pure Java `Canvas`.
* **Role-Based Routing:** Separate interfaces for Drivers, Dispatchers, and Administrators using standard Android XML layouts and plain Java activities.

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
|       |       |-- PureJavaMapCanvas.java    # Custom Canvas rendering
|       |       `-- ShiftSummaryActivity.java
|       |
|       `-- res/
|           |-- layout/          # XML layout files
|           `-- values/          # Strings, colors, styles
|
|-- sample-data/import/          # Seed data for testing
|   |-- workers.csv
|   `-- shuttles.csv
`-- docs/                        # API and schema documentation
    |-- FILE_FORMATS.md
    `-- API.md
```

## 📷 Screenshot

<img width="1102" height="552" alt="Dispatcher map view showing live shuttle positions" src="https://github.com/user-attachments/assets/d07912a9-52c9-4b2b-876a-9c623ad43d08" />

## ⚡ Quick Start

You need **JDK 17+**, the **Android SDK** (with `ANDROID_HOME` set or `sdk.dir` in `local.properties`), and **Git**.

**0. Clone the repository**

```bash
git clone https://github.com/your-username/shuttle-management-system.git
cd shuttle-management-system
```

**1. Build the project**

```bash
./gradlew build
```

**2. Load the sample data (optional)**

Copy the files in `sample-data/import/` into the server's `import/` directory so there are workers and shuttles to work with. See `docs/FILE_FORMATS.md` for the column layout.

**3. Start the server**

```bash
./gradlew :server-backend:run --args="--bootstrap-admin you@example.com"
```

The server binds to `0.0.0.0:8443` by default and prints a one-time admin invite code to the console. Without a keystore configured (see Security below) it serves plain HTTP, so use it for local testing only.

**4. Install the Android app**

Connect an Android device (or start an emulator) and run:

```bash
./gradlew :app:installDebug
```

The app must be able to reach the server. A physical phone needs your computer's LAN IP; an emulator reaches the host machine at `10.0.2.2`. Android 9+ blocks cleartext HTTP by default, so a debug build talking to a non-TLS server needs a network security config that allows it.

**5. First login workflow**

1. Open the Android app and tap **Register with invite code**.
2. Enter your email and the invite code from the server console.
3. Log in as the Admin and use the dashboard to generate an invite code for a Driver.
4. On a second phone, register the Driver, select a shuttle, and tap **Start Shift**.
5. The Dispatcher's `PureJavaMapCanvas` will begin showing the Driver's real-time GPS location.

## 🔒 Security & Production Readiness

Before real drivers use this system in production:

1. **Enable HTTPS:** Passwords and session tokens currently travel over plain HTTP. Provide a PKCS12 keystore to the server via JVM arguments (`-Dshuttle.ssl.keystore=...`).
2. **Data privacy (Philippine Data Privacy Act, RA 10173, and GDPR where applicable):** Drivers are being actively tracked. Obtain explicit consent and set up automated data-retention rules for the GPS CSV files.
3. **Network accessibility:** For phones on public roads to sync, the server must be reachable from the internet, for example through port forwarding or a secure tunnel such as Cloudflare Tunnel or Tailscale.

## 🚧 Known Limitations

* **Session volatility:** Sessions live in RAM, so a server restart forces every mobile client to log in again.
* **Storage scale:** The CSV backend handles dozens of drivers and thousands of shifts well. For thousands of concurrent active drivers, back the `Repository` interfaces with JDBC.
* **Map detail:** `PureJavaMapCanvas` plots coordinates accurately but renders no street-level tiles. It works as a radar-style telemetry display.
* **Background tracking on Android:** Some manufacturers' battery optimizers kill foreground services anyway. Drivers may need to exempt the app from battery optimization.

---
*This software is open-source and free to use under the MIT License. See `LICENSE` for details.*
