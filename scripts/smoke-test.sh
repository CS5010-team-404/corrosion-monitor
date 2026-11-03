#!/usr/bin/env bash
# Checks a deployment end to end. Exit code 0 means every check passed.
#   scripts/smoke-test.sh https://corrosion-watch-platform.onrender.com https://corrosion-watch-web.onrender.com [https://corrosion-watch-api.onrender.com]
set -uo pipefail
PLATFORM="${1:-http://localhost:8081}"; WEB="${2:-http://localhost:5173}"; API="${3:-}"
fail=0
check() { local name="$1" want="$2" got; got="$(curl -s -o /dev/null -w '%{http_code}' "${@:3}")"
  if [[ "$got" == "$want" ]]; then echo "ok    $name ($got)"; else echo "FAIL  $name: expected $want, got $got"; fail=1; fi; }
check "platform health"                 200 "$PLATFORM/actuator/health"
check "ingestion rejects unsigned"      401 -X POST "$PLATFORM/api/ingest/readings" -H 'Content-Type: application/json' -H 'X-Device-Id: nobody' -H "X-Timestamp: $(date +%s)" -H 'X-Signature: 00' -d '{"readings":[]}'
check "sensor API needs a token"        401 "$PLATFORM/api/health/sensors"
check "frontend serves index"           200 "$WEB/"
check "frontend deep link (SPA rewrite)" 200 "$WEB/alerts"
[[ -n "$API" ]] && check "backend health" 200 "$API/actuator/health"
exit $fail
