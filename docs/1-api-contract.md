# API Contract (v1)

Agree on this in week 1 and change it only via pull request.
All timestamps are ISO-8601 UTC. Temperatures in °C, humidity in % RH.

## 1. Sensor ingestion

### POST /api/v1/readings
Single reading from a sensor.

**Headers**
| Header | Meaning |
|---|---|
| `X-Sensor-Id` | Sensor UUID |
| `X-Nonce` | Random unique string per message (replay protection) |
| `X-Signature` | Hex HMAC-SHA256 over `sensorId + "." + nonce + "." + rawBody`, keyed with the sensor's secret |

**Body**
```json
{
  "sensorId": "6f1c2a9e-3b7d-4c1e-9a55-0d2f7c8e1b10",
  "seq": 10452,
  "ts": "2026-11-03T02:15:30Z",
  "temperatureC": 21.4,
  "humidityPct": 58.2,
  "buffered": false,
  "fwVersion": "sim-1.0"
}
```
- `seq` increases by 1 per message; gaps reveal lost data.
- `buffered: true` means the reading was stored locally during an outage and is being replayed.

**Responses**
| Code | Meaning |
|---|---|
| 202 | Accepted |
| 400 | Malformed or impossible values (RH outside 0-100, temp outside -40..125) |
| 401 | Bad signature, unknown sensor, or replayed nonce |
| 409 | Duplicate `seq` for this sensor |
| 429 | Rate limited |

### POST /api/v1/readings/batch
Replay of buffered readings after an outage. Body: `{"sensorId": "...", "readings": [ ... up to 500 items ... ]}`. Same headers; signature covers the whole raw body. Response lists accepted and rejected `seq` values.

## 2. Dashboard and operations (JWT or session, role-checked)

| Method | Path | Roles | Purpose |
|---|---|---|---|
| POST | /api/v1/auth/login | all | Login, returns token |
| GET | /api/v1/locations | Admin, Operator, Auditor | List monitored locations with current status |
| GET | /api/v1/locations/{id} | same | Detail: latest reading, dew-point margin, risk score, forecast |
| GET | /api/v1/locations/{id}/readings?from=&to=&resolution= | same | Time series for charts |
| GET | /api/v1/locations/{id}/forecast | same | Predicted values for 15/30/60 min |
| GET | /api/v1/alerts?status=&severity= | same | List alerts |
| POST | /api/v1/alerts/{id}/ack | Admin, Operator | Acknowledge, body: `{"note": "..."}` |
| POST | /api/v1/alerts/{id}/actions | Admin, Operator | Log corrective action |
| GET | /api/v1/sensors | Admin | List sensors and health |
| POST | /api/v1/sensors | Admin | Register sensor, returns secret once |
| PUT | /api/v1/profiles/{id} | Admin | Change thresholds |
| GET | /api/v1/audit?from=&to=&user=&type= | Admin, Auditor | Query audit log |
| POST | /api/v1/audit/verify | Admin, Auditor | Verify hash chain |
| GET | /api/v1/reports?from=&to=&format=csv\|pdf | Admin, Auditor | Export report |

## 3. Object shapes

**Location status**
```json
{
  "id": 3,
  "name": "Telecom Cabinet B",
  "status": "WARNING",
  "latest": {"ts": "2026-11-03T02:15:30Z", "temperatureC": 21.4, "humidityPct": 58.2,
             "dewPointC": 12.9, "dewPointMarginC": 8.5},
  "riskScore": 0.42,
  "riskLevel": "ELEVATED",
  "forecast": {"horizonMin": 30, "dewPointMarginC": 2.1, "minutesToCondensationRisk": 24},
  "sensorHealth": "OK"
}
```

**Alert**
```json
{
  "id": 77,
  "locationId": 3,
  "type": "CONDENSATION_FORECAST",
  "severity": "WARNING",
  "openedAt": "2026-11-03T02:16:00Z",
  "message": "Dew-point margin is 2.1°C at +30 min and shrinking",
  "status": "OPEN",
  "acknowledgedBy": null
}
```
Alert types: `HUMIDITY_HIGH`, `CONDENSATION_RISK`, `CONDENSATION_FORECAST`, `RISK_BUDGET_EXCEEDED`, `SENSOR_SILENT`, `SENSOR_STUCK`, `SENSOR_IMPLAUSIBLE`.

**Audit entry**
```json
{
  "id": 412,
  "ts": "2026-11-03T02:20:11Z",
  "eventType": "ALERT_ACK",
  "userId": 5,
  "sourceIp": "10.0.0.8",
  "resource": "alert/77",
  "outcome": "SUCCESS",
  "details": "Dehumidifier switched on",
  "prevHash": "ab12...",
  "hash": "cd34..."
}
```

**Error format (all endpoints)**
```json
{"timestamp": "...", "status": 401, "error": "INVALID_SIGNATURE", "message": "..."}
```

## 4. Audit events to log (AU-2)
`LOGIN_SUCCESS`, `LOGIN_FAILURE`, `ACCOUNT_LOCKED`, `ALERT_ACK`, `CORRECTIVE_ACTION`, `PROFILE_CHANGED`, `USER_CREATED`, `ROLE_CHANGED`, `SENSOR_REGISTERED`, `SENSOR_REJECTED_SIGNATURE`, `REPORT_EXPORTED`, `AUDIT_VERIFIED`.
