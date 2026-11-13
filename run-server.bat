@echo off
setlocal
set "ROOT=%~dp0"
set "WRAPPER=%ROOT%gradlew.bat"
set "SERVER_LAUNCHER=%ROOT%server-backend\build\install\server-backend\bin\server-backend.bat"
set "JAVA_BIN="

if not exist "%WRAPPER%" (
  echo ERROR: Gradle wrapper not found at "%WRAPPER%".
  exit /b 1
)

for /f "delims=" %%J in ('where java 2^>nul') do if not defined JAVA_BIN set "JAVA_BIN=%%J"
if not defined JAVA_BIN (
  echo ERROR: Java 17 or newer is required. Install it and set JAVA_HOME before running this script.
  exit /b 1
)
for %%J in ("%JAVA_BIN%\..\..") do set "JAVA_HOME=%%~fJ"

echo Preparing backend distribution...
call "%WRAPPER%" -p "%ROOT%" :server-backend:installDist
if errorlevel 1 exit /b %errorlevel%

if not exist "%SERVER_LAUNCHER%" (
  echo ERROR: Backend launcher was not created: "%SERVER_LAUNCHER%".
  exit /b 1
)

echo Starting server. Health check: http://127.0.0.1:8080/health
call "%SERVER_LAUNCHER%" %*
exit /b %errorlevel%
