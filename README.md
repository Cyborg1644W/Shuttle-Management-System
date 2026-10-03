# Shuttle Management System

A highly resilient, offline-capable mobile tracking and shift management system built entirely in **Pure Java**. 

This system tracks shuttle units, manages driver shifts, and provides live telemetry to dispatchers. It features a strict **3-module architecture**, pairing a native Android mobile client with an ultra-lightweight, database-free pure JDK backend. It strictly avoids heavy frameworks (no Spring, Hibernate, Retrofit, or Google Maps SDK) to ensure a minimal footprint, zero API costs, and absolute control over data flow.

## 🏗️ System Architecture

The project is divided into three isolated Gradle modules (using Groovy, zero Kotlin):

1. **`:common`**: Shared domain models (Workers, Shifts, GPS Coordinates) used by both the server and the app.
2. **`:server-backend`**: A pure JDK 17 HTTP server utilizing `com.sun.net.httpserver`. Data is stored purely in local CSV files using thread-safe atomic file-swapping.
3. **`:app`**: A native Android client (Java/XML) featuring background GPS tracking, programmatic canvas map rendering, and an offline-first queue manager for cellular dead zones.

```text
Driver Phone (Android) -------\
                               +--HTTP(S)--> Shuttle Server --reads---> import/  <-- Company DB
Dispatcher Phone (Android) ---/              (Java, files)  --writes--> export/  --> Company DB
✨ Key Features
Mobile Client (Android)
Offline-First Resilience (OfflineQueueManager): Safely caches GPS coordinates locally during cellular dead spots and automatically flushes them to the server when 4G/5G connectivity returns.

Automated Background Tracking: Uses an Android Foreground Service to continuously pull real hardware GPS coordinates without being killed by the OS.

Custom Map Rendering: Bypasses paid third-party APIs (like Google Maps) by mathematically plotting live fleet telemetry onto a pure Java Canvas.

Role-Based Routing: Dedicated mobile interfaces for Drivers, Dispatchers, and Administrators using standard Android XML layouts controlled by pure Java activities.

Server Backend (Pure JDK)
Thread-Safe CSV Persistence: Zero external database requirement. Data is persisted across structured CSV files using OS-level file-locking and atomic file-swapping (AtomicFileWriter.java) to prevent concurrent race conditions.

Smart Shift Management: Automated server daemon threads track active shifts and safely time out/close shifts if a driver's mobile connection permanently drops.

GPS Anti-Spoofing: Server-side distance-time vector calculations instantly reject impossible teleportation jumps or faked location pings.

Secure Invites & Auth: Native cryptographic salted password hashing (PBKDF2-SHA256). Admin registration invites feature strict 48-hour expirations and single-use validation.

Immutable Audit Logging: Secure transaction logs append every data creation, modification, and administrative action with timestamps.

📂 Project Structure
Plaintext
shuttle-management-system/
|-- build.gradle                 # Root Gradle build (Groovy)
|-- settings.gradle              # Defines: include ':common', ':server-backend', ':app'
|
|-- common/                      # 1. SHARED MODELS
|   `-- src/main/java/com/shuttle/common/
|       |-- Worker.java, Role.java, ShiftRecord.java, GpsCoordinate.java
|       `-- CsvUtil.java, ValidationUtil.java, DurationFormatter.java
|
|-- server-backend/              # 2. PURE JDK SERVER (Imports :common)
|   `-- src/main/java/com/shuttle/server/
|       |-- ServerMain.java      # Bootstraps the com.sun HTTP server
|       |-- Auth.java, Crypto.java, RateLimiter.java, Log.java, ApiException.java
|       |-- handler/             # API routing (Shift, Location, Live, Invite)
|       |-- service/             # Business logic & Heartbeat threads
|       |-- repository/          # In-memory caches + CSV syncing
|       `-- io/                  # CsvTable, AtomicFileWriter
|
`-- app/                         # 3. ANDROID CLIENT (Imports :common)
    `-- src/main/
        |-- AndroidManifest.xml
        |-- java/com/shuttle/mobile/
        |   |-- client/          # ApiClient, OfflineQueueManager
        |   |-- gps/             # GpsTrackerService, SimulatedGpsSource
        |   `-- ui/              # MainActivity, PureJavaMapCanvas, DriverActivity, etc.
        `-- res/layout/          # Native Android XML UI files
⚡ Quick Start
You need JDK 17+ and the Android SDK.

1. Build the Project

Bash
./gradlew build
2. Start the Server

Bash
./gradlew :server-backend:run --args="--bootstrap-admin you@example.com"
The server binds to 0.0.0.0:8443 by default. It will print a one-time admin invite code to the console.

3. Install the Android App
Connect your Android device (or emulator) and run:

Bash
./gradlew :app:installDebug
4. First Login Workflow
Open the Android app and tap Register with invite code.
Enter your email and the invite code from the server console.
Log in as an Admin/Dispatcher and use the dashboard to generate an invite code for a Driver.
On a second phone, register the Driver, select a shuttle, and tap Start Shift.
The Dispatcher's PureJavaMapCanvas will begin reflecting the Driver's real-time hardware GPS location.

🔒 Security & Production Readiness
Before real drivers use this system in production:
Enable HTTPS: Passwords and session tokens currently travel over HTTP. You must provide a PKCS12 keystore to the server via JVM arguments (-Dshuttle.ssl.keystore=...).
Data Privacy (RA 10173/GDPR): Drivers are being actively tracked. You must secure explicit consent and configure automated data-retention lifecycles for the GPS CSV files.
Network Accessibility: To sync phones out on public roads, the server must be exposed to the internet via port-forwarding or a secure tunnel (e.g., Cloudflare Tunnel or Tailscale).

🚧 Known Limitations
Session Volatility: User sessions currently live in RAM. A server restart requires all active mobile clients to log in again.
Storage Scale: The CSV backend performs brilliantly for dozens of drivers and thousands of shifts. If scaling to thousands of concurrent active drivers, the Repository interfaces must be backed by JDBC.

Map Detail: PureJavaMapCanvas plots coordinates accurately but does not render street-level map tiles (to avoid Google Maps API fees). It acts as a radar-style telemetry display.

This software is open-source and free to use under the MIT License. See LICENSE for details.
