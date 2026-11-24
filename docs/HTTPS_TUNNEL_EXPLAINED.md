# Explanation of the HTTPS tunnel files

These five files automate running the Java backend on a Windows PC and making it reachable through a public HTTPS address. The Android app remains the user interface. The backend and its CSV files remain on the PC.

For the exact startup commands and connection troubleshooting, see [PC_HOSTING.md](PC_HOSTING.md).

## How a request reaches the backend

```text
Android app
    | HTTPS
    v
Cloudflare public HTTPS endpoint
    | encrypted tunnel
    v
cloudflared connector on the PC
    | HTTP to 127.0.0.1:8080 on the same PC
    v
Java backend
    | reads and writes
    v
CSV files on the PC
```

Cloudflare handles public HTTPS. The connector initiates the tunnel connection from the PC and forwards requests to the local Java server. The local server itself still speaks HTTP on port 8080. The application logic remains Java; the PowerShell and batch files automate its startup, while cloudflared is a separate networking program.

The backend serves API endpoints rather than a browser dashboard. Test it locally at `http://127.0.0.1:8080/api/health`. Its root address, `http://127.0.0.1:8080/`, has no web page and returns 404 when the server is running.

## 1. start-https-tunnel.bat

This is the file to double-click for the full public setup. It is a small Windows launcher for the main PowerShell script.

```bat
@echo off
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0start-https-tunnel.ps1" -BuildApk
pause
```

| Part | Explanation |
| --- | --- |
| `@echo off` | Suppresses the display of each command before execution. Progress messages and errors still appear. |
| `powershell.exe` | Runs Windows PowerShell. |
| `-NoProfile` | Skips personal PowerShell startup configuration so it does not affect setup. |
| `-ExecutionPolicy RemoteSigned` | Selects an execution policy for this child PowerShell process. It does not permanently change machine settings. Organizational policy can override it. |
| `-File` | Specifies the PowerShell script to execute. |
| `%~dp0` | Expands to the drive and folder containing this batch file, allowing it to locate the adjacent script. Quotes protect paths containing spaces. |
| `-BuildApk` | Requests that the Android debug APK be built after HTTPS verification. |
| `pause` | Leaves the window open after setup succeeds or fails so the result can be read. |

The batch file delegates the work to start-https-tunnel.ps1. Closing its window after setup completes leaves the background backend and connector running.

## 2. start-https-tunnel.ps1

This script coordinates Java, Gradle, cloudflared, the Android API address, and the optional APK build.

### Parameters and configuration

`[CmdletBinding()]` enables standard PowerShell script parameter behavior. `param([switch]$BuildApk)` defines an optional on/off switch. The batch launcher always supplies this switch. Running the PowerShell script without it starts and configures hosting but skips the Android build.

`$ErrorActionPreference = 'Stop'` makes PowerShell errors interrupt setup. Native programs such as Gradle have their exit codes checked separately.

The script derives the project directory from `$PSScriptRoot`. It stores runtime files in `%LOCALAPPDATA%\Shuttle-Management-System`, outside the repository. Its local backend address is fixed at `http://127.0.0.1:8080`.

The cloudflared version is pinned to 2026.11.0. The script stores the expected SHA256 checksum as a constant. It does not automatically select a newer release.

### Health check function

`Test-ShuttleHealth` appends `/api/health` to an address and makes a web request with a five-second timeout. It requires HTTP 200 and the expected response body:

```text
status
ok
```

Connection failures, unexpected status codes, and unexpected content return false. The function is used for both local and public checks. Standard HTTPS certificate validation applies to public requests.

A successful health check establishes that the endpoint is reachable. Login, tracking, schedules, and other application behavior still require functional testing.

### Existing tunnel detection

`Get-OwnedTunnel` reads the recorded process ID from tunnel.pid and verifies that it is numeric. It looks up that process through Windows and checks its executable path and command arguments.

If the connector belongs to this setup and a saved URL exists, the starter reuses it and checks its public health again. If the connector exists but its saved URL is missing, setup asks you to stop it before retrying. These checks reduce the chance of mistaking a recycled process ID for this tunnel.

### Finding Java

Java is located in this order:

1. A valid java.exe under JAVA_HOME.
2. Android Studio's bundled runtime under Program Files.
3. java.exe found through PATH.

The selected JAVA_HOME is set for the script process and its child programs. It does not permanently change the computer's environment variables. The script locates Java but does not inspect its major version; use a JDK compatible with the project, at least Java 17.

### Building and starting the backend

The script first tests the local health endpoint. A healthy existing backend is reused without rebuilding it. If port 8080 is occupied by a service that fails that health check, setup stops instead of terminating the other service.

When a new backend is needed, the script runs:

```powershell
.\gradlew.bat --no-daemon :server-backend:installDist
```

The installDist task builds a server distribution containing the backend and the common module. The script launches Java with the distribution's lib directory on the classpath and `com.shuttle.server.ServerMain` as the entry point.

`-Dfile.encoding=UTF-8` selects the process's default text encoding. The working directory is the repository root so relative CSV paths resolve to the intended data directory. Standard output and standard error are redirected to separate log files. The Java process ID is saved in server.pid.

The script then waits for the local health check. A build failure or startup failure stops setup before a public tunnel is created. If an existing healthy backend is reused, Java source changes are not rebuilt; restart the backend when applying such changes.

### Preparing cloudflared

If cloudflared.exe is missing, the script downloads the Windows x64 executable from Cloudflare's official GitHub release. It first saves a temporary download file and calculates its SHA256 checksum.

Only a matching download is moved into place as cloudflared.exe. Existing binaries are also checked before use. A checksum mismatch stops setup. The expected checksum was obtained from the publisher's release metadata when the script was prepared; the script compares against that stored value on each run.

### Starting a Quick Tunnel

When no matching existing connector is available, the script launches the equivalent of:

```powershell
cloudflared --no-autoupdate tunnel --url http://127.0.0.1:8080
```

The connector runs in the background. Automatic updates are disabled so the executable remains consistent with the pinned checksum. Its process ID and output are saved.

The script reads its log, waiting up to 60 one-second iterations for a generated HTTPS address ending in trycloudflare.com. It checks that the extracted URL has the expected format.

### Public verification and the Android address

The script tests the public address's `/api/health` endpoint, allowing up to 15 attempts. Each request has its own timeout, and failed attempts have a two-second delay.

After a successful public health check, it saves the public origin in tunnel-url.txt. It then locates the single BASE_URL declaration in:

```text
app/src/main/java/com/shuttle/mobile/client/ApiClient.java
```

It requires exactly one matching declaration before replacing it. The new value is the public URL followed by `/api`. This is an intentional edit to a source file in the repository. The rest of the file is preserved and the updated text is written as UTF-8.

The generated address is temporary. When the tunnel is recreated with a different hostname, the app needs a new build containing that address.

### APK build

When BuildApk is present, the script runs:

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug
```

The resulting test APK is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install this APK manually on each phone. Updating the source file does not update an app already installed on a phone.

### Error handling and lifetime

If public verification fails, the script attempts to stop the new tunnel it created. A backend it already started can remain available locally. Once public verification succeeds, a later source-edit or APK-build error leaves the verified tunnel running; use the stop script if you want to close public access.

The script restores its previous working directory when finished. Backend and connector processes run in the background. The setup does not register Windows services or configure startup after a reboot. Keep the PC powered on, awake, and connected to the internet while hosting.

## 3. stop-https-tunnel.bat

This is the double-click launcher for stopping public access.

```bat
@echo off
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0stop-https-tunnel.ps1" %*
pause
```

It uses the same PowerShell options as the start launcher. `%*` forwards any supplied command-line arguments to the stopping script.

Double-clicking it supplies no arguments, so it stops the connector while leaving the local backend available. To request that both recorded processes stop, run:

```powershell
.\stop-https-tunnel.bat -StopServer
```

## 4. stop-https-tunnel.ps1

This script accepts the optional StopServer switch and uses its Stop-OwnedProcess function to stop recorded processes.

Before stopping a process, it checks:

1. That the saved PID exists and is numeric.
2. That the process still exists.
3. That the executable name and expected arguments match.
4. That cloudflared comes from this setup's runtime folder.
5. For Java, that the command references this project and ServerMain.

These checks matter because Windows can reuse process IDs. A mismatch produces an error rather than stopping an unrelated process.

The default action stops the recorded cloudflared connector. StopServer also attempts to stop the recorded Java backend. A server started independently may have no matching saved PID and may need to be stopped separately.

Stopping preserves CSV records, logs, the saved URL, PID files, and the app's configured address. Later runs check whether saved process IDs still identify live matching processes. Stopping a Quick Tunnel ends access through that public URL.

## 5. docs/PC_HOSTING.md

This is the operating guide for people running the setup. It explains prerequisites, startup commands, correct addresses for different devices, troubleshooting, APK installation, runtime files, and stopping.

It is a Markdown document. The launcher scripts do not execute it.

The guide also records the current registration limitation: the public signup endpoint accepts the OPERATOR role. Restrict privileged account creation before exposing real worker or location records. HTTPS encrypts traffic but does not alter application permissions.

## Generated runtime files

The following files are created or used under `%LOCALAPPDATA%\Shuttle-Management-System`:

| File | Purpose |
| --- | --- |
| cloudflared.exe | The downloaded Cloudflare connector program. |
| cloudflared.download.exe | Temporary file used during a new download before checksum verification. |
| server.pid | Process ID of the backend recorded by the setup. |
| tunnel.pid | Process ID of the connector recorded by the setup. |
| tunnel-url.txt | Verified public origin, without `/api`. |
| server.stdout.log | Normal Java console output. |
| server.stderr.log | Java error output. |
| tunnel.stdout.log | Connector standard output. |
| tunnel.stderr.log | Connector status messages and errors; routine messages can appear here. |

These files are separate from application data. The backend's default CSV directory is `sample-data/import/` under the repository root. Back up those CSV files separately. Login sessions are stored in memory and require users to sign in again after a backend restart.

## Verification limits

The backend distribution build and Windows PowerShell syntax checks passed when the scripts were prepared. The initial combined background server/tunnel launch was rejected by automatic approval review with only the reason "blocked by policy". That attempt did not create a live public address.

During follow-up diagnosis on November 17, 2026, the backend was started locally and `/api/health` returned HTTP 200 with the expected body. The root path `/` returned HTTP 404, as expected for this API-only server. The Gradle run task's working directory was corrected to the repository root so it uses the intended CSV files. The backend was then run independently of Gradle. Public tunnel connectivity remains unverified until the full starter completes its public health check.

Quick Tunnels are intended for testing and development. A stable hostname requires a separately configured tunnel and domain. See the official [Quick Tunnel guide](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/), [downloads](https://developers.cloudflare.com/tunnel/downloads/), and [stable tunnel setup](https://developers.cloudflare.com/tunnel/get-started/).
