#!/usr/bin/env bash
# Stops what scripts/run-local.sh started: the frontend, the platform, the backend, then the docker compose services.
# The database volume is kept (docker compose down without -v); wipe it with `docker compose down -v`.
set -uo pipefail
cd "$(dirname "$0")/.."
RUN=".run"

# A process and all its descendants (npm starts sh, which starts node/vite), children first.
tree() {
  local child
  for child in $(pgrep -P "$1" 2>/dev/null); do tree "$child"; done
  echo "$1"
}

stop() { # name
  local pidfile="$RUN/$1.pid" pid pids
  [ -f "$pidfile" ] || return 0
  pid="$(cat "$pidfile")"
  if kill -0 "$pid" 2>/dev/null; then
    echo "Stopping the $1 (pid $pid)..."
    pids="$(tree "$pid")"
    kill -TERM $pids 2>/dev/null
    for _ in $(seq 1 20); do kill -0 $pids 2>/dev/null || break; sleep 1; done
    kill -KILL $pids 2>/dev/null
  fi
  rm -f "$pidfile"
}

stop frontend
stop platform
stop backend

echo "Stopping docker compose services (data kept)..."
docker compose down
