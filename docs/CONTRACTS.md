# Contracts between services and with the frontend

If an existing endpoint in the team backend already does the job with a different shape, keep the backend as it
is and adapt `frontend/src/api/endpoints.ts`. The shapes the pages need are in `frontend/src/api/types.ts`.

All paths use the `/api/v1` prefix (decision 3 in `DECISIONS.md`).

## Authentication
- `POST /api/v1/auth/login` `{email, password}` → `{token, expiresAt, email, fullName, role}`, on the team's `app_user`
  table (decision 1). Passwords are BCrypt, and 5 failures lock the account for 15 minutes (`failed_logins`, `locked_until`).
- JWT HS256 signed with `ECP_JWT_SECRET` (shared via the Render env group). The platform validates the same token.
  Subject: the user's email. Roles claim: `roles` (array, e.g. `["TECHNICIAN"]`) unless `ECP_JWT_ROLES_CLAIM` says otherwise.
- Roles: `TECHNICIAN`, `OPERATOR`, `MANAGER`, `ADMIN` (decision 4; `app_user.role` allows all four).

## Equipment (decision 5)
`location` 1 — n `equipment`. Each equipment item has one `equipment_profile` (thresholds). `equipmentId` in the API and
`sensors.equipment_id` refer to `equipment.id`. A sensor belongs to one equipment item.

## REST API used by the UI

| Method and path | Service, owner | Returns |
|---|---|---|
| `GET /api/v1/equipment/status` | backend, analytics | `EquipmentStatus[]` latest assessment per equipment, with thresholds |
| `GET /api/v1/equipment/{id}/assessments?minutes=` | backend, analytics | `AssessmentPoint[]` |
| `GET /api/v1/alerts?status=ALL\|ACTIVE\|OPEN\|ACKNOWLEDGED\|RESOLVED&limit=` | backend, alerts | `Alert[]` |
| `GET /api/v1/alerts/{id}` | backend, alerts | `{alert, actions}` |
| `POST /api/v1/alerts/{id}/acknowledge` · `/resolve {notes}` · `/actions {actionType, notes}` | backend, alerts | technician, admin |
| `GET /api/v1/audit?size=&page=&actor=&action=` · `POST /api/v1/audit/verify` | backend, audit | `AuditEntry[]` · `{intact, checked, firstBrokenId}` |
| `GET /api/v1/reports/summary?from=&to=&location=` and `.csv` / `.pdf` | backend, analytics + alerts | `ReportSummary`, files (OpenPDF) |
| `GET /api/v1/profiles` | backend | config |
| `GET /api/v1/admin/users` · `POST /api/v1/admin/users/{id}/unlock` · `PATCH /api/v1/admin/users/{id} {role}` | backend, auth (decision 1) | `User[]` · `User`; admins only. 409 `ROLE_CHANGE_REFUSED` for self-demotion or the last admin. Audited as `ACCOUNT_UNLOCKED`, `USER_ROLE_CHANGED` (`oldRole`, `newRole`) |
| `GET /api/v1/health/sensors` · `/api/v1/health/sensors/{id}?minutes=` · `/api/v1/health/events` · `/api/v1/health/services` | platform | sensor health |
| `GET /api/v1/sensors` · `POST /api/v1/admin/sensors` · `POST /api/v1/admin/sensors/{deviceId}/rotate-secret` | platform | device registry; secret returned once |
| `PATCH /api/v1/admin/sensors/{id} {expectedIntervalS}` | platform | admin only; `id` is `sensors.id`; 5-3600 s, else 400; returns the `Device`; audited `SENSOR_INTERVAL_CHANGED` (actor = admin, entity `sensor`/deviceId, details `{sensorId, fromSeconds, toSeconds}`); sensor health and the embedded simulator use the new value at once |
| `GET /api/v1/sensors/{id}/forecast` · `GET /api/v1/forecast/model` | platform | `ForecastView`, model metrics |
| `GET /api/v1/admin/simulator` · `/scenarios` · `POST /api/v1/admin/simulator/{deviceId}/scenario {scenario}` | platform | demo control |

## Device ingestion
`POST /api/v1/ingest/readings` (platform, no JWT). Headers `X-Device-Id`, `X-Timestamp` (unix seconds, ±5 min),
`X-Signature` = lowercase hex HMAC-SHA256 of `"<X-Timestamp>.<raw body>"` with the device key.
Body `{"readings":[{"seq":1,"measuredAt":"2026-11-03T01:00:00Z","temperatureC":22.4,"humidityPct":55.0,"surfaceTempC":20.9?}]}`,
up to 500 readings. Responses: 202 with `{accepted, duplicates}` (a repeated seq is counted, not stored twice), 401
`UNKNOWN_DEVICE` / `STALE_TIMESTAMP` / `BAD_SIGNATURE`, 403 `INACTIVE_SENSOR`, 400 `INVALID_PAYLOAD`. Every rejection
is recorded in `ingest_rejections`, which sensor health uses to flag bad signatures.
Reference clients: `scripts/send-reading.sh`, `devices/raspberry-pi/sender.py`, `ecp-simulator`.

## Shared tables (created by the platform, `CREATE TABLE IF NOT EXISTS`)
- `sensors`: device registry (platform writes). `equipment_id` → `equipment.id`. It replaces the team's `sensor` (decision 2).
- `readings`: raw readings (platform writes; analytics reads). They replace the team's `reading` and `used_nonce` (decision 2).
- `sensor_health(sensor_id, status, trusted, issues, …)`: analytics MUST skip sensors with `trusted = false`.
- `alert_signals(source, signal_type, severity, state RAISED|CLEARED, equipment_id, sensor_id, dedup_key, …)`:
  producers insert; the alert service consumes in id order and sets `processed_at`.
  Dedup keys: `HEALTH:<TYPE>:<sensorId>`, `ANALYTICS:CONDENSATION_RISK:<equipmentId>`,
  `ANALYTICS:CONDENSATION_FORECAST:<equipmentId>`, `SYSTEM:SERVICE_DOWN:<service>`.
- `audit_outbox(actor_type, actor, action, entity_type, entity_id, details, chained_at)`: every service inserts;
  only the audit service reads it and writes the chain (`audit_log`), setting `chained_at`.
- `service_heartbeats`: each background job records runs; sensor health raises `SERVICE_DOWN` if one stalls.
- `sensor_forecasts`: the latest condensation-margin forecast per sensor (FR2). Platform writes (migration
  `V2__sensor_forecasts.sql`), analytics reads. Columns: `sensor_id` (PK, `sensors.id`), `margin_15_c`,
  `margin_30_c`, `margin_60_c` (predicted surface temperature minus dew point, °C, `DECIMAL(6,2)`),
  `warning_probability_30`, `warning_probability_60` (0..1, `DECIMAL(5,4)`: probability the margin reaches the 1 °C
  critical level within 30 / 60 minutes), `method` (`ML` = trained model, `TREND` = linear trend while a sensor has
  under 31 minutes of history), `predicted_condensation_at` (margin reaches 0 °C; NULL if not within the hour),
  `computed_at`. All times UTC. Rewritten every minute (`ecp.forecast.publish.enabled`, default true;
  `ecp.forecast.publish.every`, default PT1M) from the same model as `GET /api/v1/sensors/{id}/forecast`.
  A row exists only for a sensor that was active, `sensor_health.trusted = TRUE` and had readings in the last
  45 minutes at `computed_at`; rows of sensors that stop qualifying are deleted on the next run. Readers join on
  `sensor_id` (for example `sensors.equipment_id` for the equipment item) and should treat a row with `computed_at`
  older than about 3 minutes as stale (the publisher stopped; the watchdog raises `SERVICE_DOWN` for
  `forecast-publisher`). Example: `SELECT f.* FROM sensor_forecasts f JOIN sensors s ON s.id = f.sensor_id WHERE
  s.equipment_id = ? AND f.computed_at > UTC_TIMESTAMP(3) - INTERVAL 3 MINUTE`.

The team schema (Flyway, decision 6) owns `app_user`, `location`, `equipment`, `equipment_profile`, `alert`,
`corrective_action` and `audit_log`. All services use one database, `corrosion_monitor`.

## Live updates (STOMP over WebSocket at `/ws`)
Platform: `/topic/readings`, `/topic/health`. Backend: `/topic/risk`, `/topic/alerts`. Payloads are small change
notices; the UI refetches. Pages also poll, so the UI works without WebSockets.
