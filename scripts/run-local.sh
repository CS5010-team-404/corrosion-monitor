#!/usr/bin/env bash
# Runs the real system locally so it can be tried in a browser:
#   MySQL (docker compose, Aiven settings) -> backend :8080 -> platform :8081 (JWT auth) -> frontend :5173 (npm run dev)
# The backend and platform share a local-only ECP_JWT_SECRET and ECP_DEMO_PASSWORD, generated once into .env.local
# (gitignored). Logs and process ids go to .run/. Stop everything with scripts/stop-local.sh.
#
#   scripts/run-local.sh            # builds the jars only if they are missing
#   REBUILD=1 scripts/run-local.sh  # rebuild both jars first (after code changes)
#
# Needs Java 17, Maven, Docker and Node/npm. Database user and password come from .env if present (as for
# docker compose), otherwise the docker-compose.yml defaults.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
RUN="$ROOT/.run"
mkdir -p "$RUN"

BACKEND_JAR="backend/target/backend-0.1.0-SNAPSHOT.jar"
PLATFORM_JAR="platform/ecp-platform-app/target/ecp-platform.jar"

# 0. Nothing of ours may already be running, and the ports must be free.
for name in backend platform frontend; do
  if [ -f "$RUN/$name.pid" ] && kill -0 "$(cat "$RUN/$name.pid")" 2>/dev/null; then
    echo "The $name is already running (pid $(cat "$RUN/$name.pid")). Run scripts/stop-local.sh first." >&2
    exit 1
  fi
done
for port in 8080 8081 5173; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Port $port is already in use; stop whatever is listening there first." >&2
    exit 1
  fi
done

# 1. Local-only secrets, generated once. Never used anywhere but this machine.
if [ ! -f .env.local ]; then
  umask 077
  {
    echo "# Local-only values for scripts/run-local.sh (gitignored). Not used in production."
    echo "ECP_JWT_SECRET=$(openssl rand -hex 32)"
    echo "ECP_DEMO_PASSWORD=demo-$(openssl rand -hex 6)"
  } > .env.local
  umask 022
  echo "Created .env.local with a local JWT secret and demo password."
fi
ECP_JWT_SECRET="$(grep '^ECP_JWT_SECRET=' .env.local | cut -d= -f2-)"
ECP_DEMO_PASSWORD="$(grep '^ECP_DEMO_PASSWORD=' .env.local | cut -d= -f2-)"
if [ -z "$ECP_JWT_SECRET" ] || [ -z "$ECP_DEMO_PASSWORD" ]; then
  echo ".env.local must define ECP_JWT_SECRET and ECP_DEMO_PASSWORD (delete it to regenerate)." >&2
  exit 1
fi

# Database account: same source as docker compose (.env, else the compose defaults).
env_value() { [ -f .env ] && grep "^$1=" .env | tail -n 1 | cut -d= -f2- || true; }
DB_USER="$(env_value MYSQL_USER)"; DB_USER="${DB_USER:-app_rw}"
DB_PASS="$(env_value MYSQL_PASSWORD)"; DB_PASS="${DB_PASS:-change-me-app}"
DB_URL="jdbc:mysql://localhost:3306/corrosion_monitor?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true&useSSL=false"

# 2. Jars.
if [ "${REBUILD:-0}" = "1" ] || [ ! -f "$BACKEND_JAR" ]; then
  echo "Building the backend jar..."
  mvn -q -B -pl backend -am -DskipTests package
fi
if [ "${REBUILD:-0}" = "1" ] || [ ! -f "$PLATFORM_JAR" ]; then
  echo "Building the platform jar..."
  mvn -q -B -f platform/pom.xml -DskipTests package
fi

# 3. MySQL.
echo "Starting MySQL (docker compose)..."
docker compose up -d db >/dev/null
for _ in $(seq 1 60); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' corrosion-db 2>/dev/null)" = "healthy" ] && break
  sleep 2
done
if [ "$(docker inspect -f '{{.State.Health.Status}}' corrosion-db 2>/dev/null)" != "healthy" ]; then
  echo "MySQL did not become healthy; see: docker compose logs db" >&2
  exit 1
fi

# 4. Backend and platform in the background. Secrets reach them through the environment, not the command line.
echo "Starting the backend on :8080 (log: .run/backend.log)..."
DB_URL="$DB_URL" MYSQL_USER="$DB_USER" MYSQL_PASSWORD="$DB_PASS" \
  ECP_JWT_SECRET="$ECP_JWT_SECRET" ECP_DEMO_PASSWORD="$ECP_DEMO_PASSWORD" PORT=8080 \
  nohup java -jar "$BACKEND_JAR" < /dev/null > "$RUN/backend.log" 2>&1 &
echo $! > "$RUN/backend.pid"

echo "Starting the platform on :8081 (log: .run/platform.log)..."
ECP_DB_URL="$DB_URL" ECP_DB_USER="$DB_USER" ECP_DB_PASSWORD="$DB_PASS" \
  ECP_PLATFORM_AUTH=jwt ECP_JWT_SECRET="$ECP_JWT_SECRET" PORT=8081 \
  nohup java -jar "$PLATFORM_JAR" < /dev/null > "$RUN/platform.log" 2>&1 &
echo $! > "$RUN/platform.pid"

wait_up() { # name port
  for _ in $(seq 1 90); do
    if ! kill -0 "$(cat "$RUN/$1.pid")" 2>/dev/null; then
      echo "The $1 stopped while starting. Last lines of .run/$1.log:" >&2
      tail -n 30 "$RUN/$1.log" >&2
      return 1
    fi
    if curl -fsS "http://localhost:$2/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      echo "The $1 is UP."
      return 0
    fi
    sleep 2
  done
  echo "The $1 was not UP after 3 minutes; see .run/$1.log" >&2
  return 1
}
wait_up backend 8080 || { scripts/stop-local.sh; exit 1; }
wait_up platform 8081 || { scripts/stop-local.sh; exit 1; }

# 5. Frontend (Vite dev server, proxies /api to :8080 and the platform paths to :8081).
if [ ! -d frontend/node_modules ]; then
  echo "Installing frontend dependencies..."
  (cd frontend && npm install --silent)
fi
echo "Starting the frontend on :5173 (log: .run/frontend.log)..."
(cd frontend && exec nohup npm run dev -- --strictPort < /dev/null > "$RUN/frontend.log" 2>&1) &
echo $! > "$RUN/frontend.pid"
for _ in $(seq 1 30); do
  curl -fsS -o /dev/null http://localhost:5173/ 2>/dev/null && break
  sleep 1
done
if ! curl -fsS -o /dev/null http://localhost:5173/ 2>/dev/null; then
  echo "The frontend did not start; see .run/frontend.log" >&2
  scripts/stop-local.sh
  exit 1
fi

cat <<EOF

Corrosion Watch is running locally.

  Open:      http://localhost:5173
  Password:  $ECP_DEMO_PASSWORD   (all four users; stored in .env.local)
  Users:     tech@corrosionwatch.app       Technician
             operator@corrosionwatch.app   Operator
             manager@corrosionwatch.app    Manager
             admin@corrosionwatch.app      Admin

  Works now (backend + platform):
    Sign-in, Sensor health, Admin > simulator and devices, sensor forecasts,
    Audit log (managers and admins; Verify chain is admin-only).
  Waiting for teammates' endpoints (these show an error state until they exist; docs/GAPS.md):
    Overview, Equipment       equipment status and assessments   (Aayushee); alerts (Dumebi)
    Alerts                    alert list, detail and actions     (Dumebi)
    Reports                   report summary, CSV and PDF        (Aayushee with Dumebi)
    Admin > users, profiles   /api/v1/admin/users, /api/v1/profiles (team)

  Logs: .run/backend.log  .run/platform.log  .run/frontend.log
  Stop: scripts/stop-local.sh   (keeps the database; docker compose down -v to wipe it)
EOF
