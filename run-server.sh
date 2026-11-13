#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER="$ROOT/gradlew"
SERVER_LAUNCHER="$ROOT/server-backend/build/install/server-backend/bin/server-backend"

if [ ! -x "$WRAPPER" ]; then
  echo "ERROR: Executable Gradle wrapper not found at $WRAPPER." >&2
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java 17 or newer is required. Install it and set JAVA_HOME before running this script." >&2
  exit 1
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  JAVA_HOME=$(CDPATH= cd -- "$(dirname -- "$(command -v java)")/.." && pwd)
  export JAVA_HOME
fi

echo "Preparing backend distribution..."
"$WRAPPER" -p "$ROOT" :server-backend:installDist

if [ ! -x "$SERVER_LAUNCHER" ]; then
  echo "ERROR: Backend launcher was not created: $SERVER_LAUNCHER." >&2
  exit 1
fi

echo "Starting server. Health check: http://127.0.0.1:8080/health"
exec "$SERVER_LAUNCHER" "$@"
