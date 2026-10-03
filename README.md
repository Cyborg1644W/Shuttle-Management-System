# Shuttle Management System

A highly resilient, offline-capable mobile tracking and shift management system built entirely in **Pure Java**. 

This system tracks shuttle units, manages driver shifts, and provides live telemetry to dispatchers. It features a strict **3-module architecture**, pairing a native Android mobile client with an ultra-lightweight, database-free pure JDK backend. It strictly avoids heavy frameworks (no Spring, Hibernate, Retrofit, or Google Maps SDK) to ensure a minimal footprint, zero API costs, and absolute control over data flow.

## 🏗️ System Architecture

The project is divided into three isolated Gradle modules (using Groovy, zero Kotlin):

1. **`:common`**: Shared domain models (Workers, Shifts, GPS Coordinates) used by both the server and the app.
2. **`:server-backend`**: A pure JDK 17 HTTP server utilizing `com.sun.net.httpserver`. Data is stored purely in local CSV files using thread-safe atomic file-swapping.
3. **`:app`**: A native Android client (Java/XML) featuring background GPS tracking, programmatic canvas map rendering, and an offline-first queue manager for cellular dead zones.

    Driver Phone (Android) -------\
                                   +--HTTP(S)--> Shuttle Server --reads---> import/  <-- Company DB
    Dispatcher Phone (Android) ---/              (Java, files)  --writes--> export/  --> Company DB

## ✨ Key Features

### Mobile Client (Android)
* **Offline-First Resilience (`OfflineQueueManager`):** Safely caches GPS coordinates locally during cellular dead spots and automatically flushes them to the server when 4G/5G connectivity returns.
* **Automated Background Tracking:** Uses an Android Foreground Service to continuously pull real hardware GPS coordinates without being killed by the OS.
* **Custom Map Rendering:** Bypasses paid third-party APIs (like Google Maps) by mathematically plotting live fleet telemetry onto a pure Java `Canvas`.
* **Role-Based Routing:** Dedicated mobile interfaces for Drivers, Dispatchers, and Administrators using standard Android XML layouts controlled by pure Java activities.

### Server Backend (Pure JDK)
* **Thread-Safe CSV Persistence:** Zero external database requirement. Data is persisted across structured CSV files using OS-level file-locking and atomic file-swapping (`AtomicFileWriter.java`) to prevent concurrent race conditions.
* **Smart Shift Management:** Automated server daemon threads track active shifts and safely time out/close shifts if a driver's mobile connection permanently drops.
* **GPS Anti-Spoofing:** Server-side distance-time vector calculations instantly reject impossible teleportation jumps or faked location pings.
* **Secure Invites & Auth:** Native cryptographic salted password hashing (PBKDF2-SHA256). Admin registration invites feature strict 48-hour expirations and single-use validation.
* **Immutable Audit Logging:** Secure transaction logs append every data creation, modification, and administrative action with timestamps.

## 📂 Detailed Project Structure

    shuttle-management-system/
    |-- build.gradle                 # Root Gradle build (Groovy, zero Kotlin)
    |-- settings.gradle              # Defines: include ':common', ':server-backend', ':app'
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
    |   |-- build.gradle             # Imports project(':common')
    |   `-- src/main/java/com/shuttle/server/
    |       |-- ServerMain.java      # Bootstraps the pure JDK com.sun HTTP server
    |       |-- ShuttleServer.java   # Core HTTP server wrapper and thread manager
    |       |-- ServerConfig.java
    |       |-- Auth.java            # RBAC and session token validation
    |       |-- Crypto.java          # PBKDF2 / SHA-256 password hashing
    |       |-- RateLimiter.java     # Brute-force prevention
    |       |-- Log.java             # Immutable audit logging
    |       |-- ApiException.java    # Standardized API error responses
    |       |
    |       |-- handler/             # API routing endpoints
    |       |   |-- ApiHandler.java, AuthHandler.java, ShiftHandler.java
    |       |   `-- LocationHandler.java, LiveHandler.java, InviteHandler.java
    |       |
    |       |-- service/             # Business logic & Heartbeat threads
    |       |   |-- ShiftService.java, TrackingService.java
    |       |   `-- InviteService.java, RegistrationService.java
    |       |
    |       |-- repository/          # In-memory caches + CSV syncing
    |       |   |-- WorkerRepository.java, ShiftRepository.java
    |       |   `-- InviteRepository.java, ShuttleRepository.java
    |       |
    |       `-- io/                  # File operations
    |           |-- CsvTable.java
    |           |-- AtomicFileWriter.java # Thread-safe OS-level file locking
    |           `-- ImportService.java, ExportService.java
    |
    |-- app/                         # 3. ANDROID CLIENT MODULE
    |   |-- build.gradle             # Imports project(':common')
    |   `-- src/main/
    |       |-- AndroidManifest.xml  # GPS, Internet, and Foreground Service permissions
    |       |-- java/com/shuttle/mobile/
    |       |   |-- client/          # Networking & Syncing
    |       |   |   |-- ApiClient.java
    |       |   |   `-- OfflineQueueManager.java # Offline-first local GPS caching
    |       |   |
    |       |   |-- gps/             # Hardware Location
    |       |   |   |-- GpsTrackerService.java   # Android Foreground Service
    |       |   |   `-- SimulatedGpsSource.java
    |       |   |
    |       |   `-- ui/              # Screen Controllers (Pure Java UI logic)
    |       |       |-- MainActivity.java
    |       |       |-- LoginActivity.java
    |       |       |-- RegisterActivity.java
    |       |       |-- DriverActivity.java
    |       |       |-- DispatcherActivity.java
    |       |       |-- AdminActivity.java
    |       |       |-- PureJavaMapCanvas.java   # Custom Canvas rendering
    |       |       `-- ShiftSummaryActivity.java
    |       |
    |       `-- res/
    |           |-- layout/          # Standard XML Layout files
    |           `-- values/          # Strings, colors, styles
    |
    |-- sample-data/import/          # Seed data for testing
    |   |-- workers.csv
    |   `-- shuttles.csv
    `-- docs/                        # API and Schema Documentation
        |-- FILE_FORMATS.md
        `-- API.md

## ⚡ Quick Start

You need **JDK 17+**, the **Android SDK**, and **Git** installed on your system.

**0. Clone the Repository**
    git clone https://github.com/your-username/shuttle-management-system.git
    cd shuttle-management-system

**1. Build the Project**
    ./gradlew build

**2. Start the Server**
    ./gradlew :server-backend:run --args="--bootstrap-admin you@example.com"
*(The server binds to `0.0.0.0:8443` by default. It will print a one-time admin invite code to the console.)*

**3. Install the Android App**
Connect your Android device (or emulator) via USB and run:
    ./gradlew :app:installDebug

**4. First Login Workflow**
1. Open the Android app and tap **Register with invite code**.
2. Enter your email and the invite code from the server console.
3. Log in as an Admin/Dispatcher and use the dashboard to generate an invite code for a Driver.
4. On a second phone, register the Driver, select a shuttle, and tap **Start Shift**.
5. The Dispatcher's `PureJavaMapCanvas` will begin reflecting the Driver's real-time hardware GPS location.

## 🔒 Security & Production Readiness

Before real drivers use this system in production:
1. **Enable HTTPS:** Passwords and session tokens currently travel over HTTP. You must provide a PKCS12 keystore to the server via JVM arguments (`-Dshuttle.ssl.keystore=...`).
2. **Data Privacy (RA 10173/GDPR):** Drivers are being actively tracked. You must secure explicit consent and configure automated data-retention lifecycles for the GPS CSV files.
3. **Network Accessibility:** To sync phones out on public roads, the server must be exposed to the internet via port-forwarding or a secure tunnel (e.g., Cloudflare Tunnel or Tailscale).

## 🚧 Known Limitations

* **Session Volatility:** User sessions currently live in RAM. A server restart requires all active mobile clients to log in again.
* **Storage Scale:** The CSV backend performs brilliantly for dozens of drivers and thousands of shifts. If scaling to thousands of concurrent active drivers, the `Repository` interfaces must be backed by JDBC.
* **Map Detail:** `PureJavaMapCanvas` plots coordinates accurately but does not render street-level map tiles (to avoid Google Maps API fees). It acts as a radar-style telemetry display.

---
*This software is open-source and free to use under the MIT License. See `LICENSE` for details.*
