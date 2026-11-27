# HTTPS hosting from a Windows PC

The Java backend can run on your PC and receive HTTPS requests through a Cloudflare Quick Tunnel. The Android app connects to the public tunnel address. No Dockerfile, router port forwarding, domain, or Cloudflare account is needed for a Quick Tunnel.

For an explanation of every launcher, PowerShell script, and generated runtime file, read [HTTPS_TUNNEL_EXPLAINED.md](HTTPS_TUNNEL_EXPLAINED.md).

For the application server's actual startup, authentication, request handling, GPS, and storage behavior, read [SERVER_PROCESS.md](SERVER_PROCESS.md). That guide also documents the current Android/server CSV-versus-JSON mismatch.

## If the site cannot be reached

Start by opening **http://127.0.0.1:8080/api/health on the server PC**. A working backend returns HTTP 200 and:

```text
status
ok
```

The Java backend serves an API, not a browser dashboard. `http://127.0.0.1:8080/` has no home page and returns HTTP 404 when the server is running. A connection-refused error means nothing is accepting connections at that address and port; the backend must be started first.

The initial investigation found no listener on port 8080, no running Java backend or cloudflared connector, and no tunnel URL or startup logs. Having the launcher files and cloudflared executable on disk does not start the services.

For the full public setup, double-click `start-https-tunnel.bat` and wait for **Public health check PASSED** and a successful APK build. The local backend check must pass before an HTTPS tunnel can work. The public address comes from the setup output or `tunnel-url.txt`; `https://localhost:8080` is not the tunnel address.

If you want to diagnose only the backend first, open PowerShell and run:

```powershell
cd "C:\Users\User\StudioProjects\Shuttle-Management-System"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --no-daemon :server-backend:run
```

Keep that terminal running and test the health URL in another window. Press Ctrl+C to stop this diagnostic server before switching to the full starter. If Android Studio is installed elsewhere, set JAVA_HOME to your installed JDK instead. The Gradle run task uses the repository root as its working directory so it reads the same CSV files as the hosting scripts.

Follow-up verification on November 17, 2026 confirmed HTTP 200 with the expected response at `/api/health`, and HTTP 404 at `/`. The backend was then started independently of Gradle so a long-running Gradle diagnostic task does not remain active during normal hosting. These are verification results from that session, not a guarantee that the PC's processes remain running after shutdown or restart. A public tunnel must still be started and verified separately.

### Use the address for the correct device

| Where you are connecting from | Address |
| --- | --- |
| Browser on the server PC | `http://127.0.0.1:8080/api/health` |
| Android emulator on that PC | `http://10.0.2.2:8080/api/health` |
| Phone on the same router/network | `http://<PC-LAN-IP>:8080/api/health` |
| Phone over mobile data or another network | The generated `https://...trycloudflare.com` URL plus `/api/health` |

`localhost` and `127.0.0.1` refer to the device making the request. On a phone, they refer to the phone. Find your PC's current LAN IPv4 address with `ipconfig`. Direct LAN access also requires an inbound Windows Firewall rule for TCP 8080 on your trusted network; the Cloudflare connector connects locally and does not require that inbound LAN rule.

### Troubleshooting

| Symptom | What to check |
| --- | --- |
| Local connection refused | Start the backend. Check for a listener with `Get-NetTCPConnection -State Listen -LocalPort 8080`. |
| HTTP 404 at `/` | Use `/api/health` to test the API. Use the Android app for the user interface. |
| TLS/SSL error at `https://localhost:8080` | The local Java server uses HTTP. Use `http://127.0.0.1:8080/api/health` locally and the generated HTTPS address publicly. |
| Public tunnel reports 502 | Recheck local `/api/health`; the connector must be able to reach the backend. |
| Old public hostname stops working | Recreate the tunnel, rebuild the app with the new URL, and reinstall the APK. |
| Phone fails but PC health check passes | Verify the phone's URL, its network, the installed APK version, and the LAN firewall if using a local IP. |
| Java/JDK/SDK or Gradle error | Read the launcher output; verify JAVA_HOME, the Android SDK path in local.properties, and available internet access for dependency downloads. |
| APK build fails after public health passes | The tunnel can still be running. Correct the build failure and rerun the starter, or stop public access with the stop script. |

The launcher output identifies the failure stage. Its background process logs, when created, are in `%LOCALAPPDATA%\Shuttle-Management-System`. A foreground Gradle server prints its output in that terminal instead.

## Start

Double-click `start-https-tunnel.bat` in the project root. Alternatively, run this from PowerShell in the project directory:

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\start-https-tunnel.ps1 -BuildApk
```

The script:

1. Locates Java using `JAVA_HOME`, Android Studio's bundled JDK, or `PATH`.
2. Builds and starts the Java backend on port 8080, or reuses a healthy existing backend.
3. Uses the official Windows x64 cloudflared 2026.11.0 binary, checking its SHA256 digest against Cloudflare's GitHub release metadata.
4. Starts an HTTPS Quick Tunnel and verifies `/api/health` through its public address.
5. Updates `ApiClient.BASE_URL` to the verified HTTPS address plus `/api`.
6. Builds `app/build/outputs/apk/debug/app-debug.apk`. Install this APK on each phone to use the new address.

The backend and tunnel run in the background. Closing the setup window leaves them running. Keep the PC awake, powered on, and connected to the internet.

The first backend and APK build require the existing Android SDK/Gradle setup and internet access to download dependencies. Java 17 or newer is required.

## Address and logs

Runtime files are stored outside the repository in:

```text
%LOCALAPPDATA%\Shuttle-Management-System
```

`tunnel-url.txt` contains the public URL. Append `/api/health` to test it in a phone browser, including over mobile data. The `server.*.log` and `tunnel.*.log` files contain startup output.

A Quick Tunnel hostname changes when the tunnel is recreated. Run the starter and install the rebuilt APK again after a hostname change. For a stable address, configure a named Cloudflare Tunnel with a domain you control.

CSV files remain in the project's configured data directory, by default `sample-data/import/`. Back up these files separately. The backend keeps login sessions in memory, so users must sign in again after a backend restart.

## Stop

Double-click `stop-https-tunnel.bat` to stop public access while leaving the local backend running.

To stop both processes started by this setup:

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\stop-https-tunnel.ps1 -StopServer
```

The stopper verifies executable identity and command arguments before stopping a saved process. It does not terminate an unrelated application merely because it uses port 8080.

## Before using real records publicly

The current public registration endpoint accepts the `OPERATOR` role. Restrict Operator account creation before using this public deployment with real worker or tracking records. HTTPS encrypts connections; it does not change registration permissions.

Quick Tunnels are intended for testing and development and have no uptime guarantee. See [Cloudflare Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/), [official downloads](https://developers.cloudflare.com/tunnel/downloads/), and [stable tunnel setup](https://developers.cloudflare.com/tunnel/get-started/).
