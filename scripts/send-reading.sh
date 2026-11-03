#!/usr/bin/env bash
# Sends one signed reading, exactly like a device. Useful to test a deployment or a new device key.
#   scripts/send-reading.sh PI-001 'the-device-secret' [https://corrosion-watch-platform.onrender.com] [22.4] [61.0]
set -euo pipefail
DEVICE="${1:?device id}"; SECRET="${2:?device secret}"; BASE="${3:-http://localhost:8081}"
TEMP="${4:-22.4}"; RH="${5:-55.0}"
SEQ="${SEQ:-$(date +%s)}"
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; TS="$(date -u +%s)"
BODY="{\"readings\":[{\"seq\":${SEQ},\"measuredAt\":\"${NOW}\",\"temperatureC\":${TEMP},\"humidityPct\":${RH}}]}"
SIG="$(printf '%s' "${TS}.${BODY}" | openssl dgst -sha256 -hmac "${SECRET}" -hex | sed 's/^.* //')"
curl -sS -w '\nHTTP %{http_code}\n' -X POST "${BASE}/api/ingest/readings" \
  -H 'Content-Type: application/json' -H "X-Device-Id: ${DEVICE}" -H "X-Timestamp: ${TS}" -H "X-Signature: ${SIG}" \
  --data-binary "${BODY}"
