# Integration gaps

One line per missing or mismatched item, comparing what the UI and platform need (`frontend/src/api/endpoints.ts`, `types.ts`,
`CONTRACTS.md`, platform `V1__platform_tables.sql`) with the team backend as of 2026-11-03. Details are in `REPO_MAP.md`.
Owners: Aayushee (analytics), Dumebi (alerts), Anela (audit), Joshua (platform/frontend), team (auth, config).

## Auth and users (team)
- Missing: `POST /api/auth/login` returning `{token, expiresAt, username, fullName, role}`. Owner: team
- Missing: JWT issuing (HS256, key from `ECP_JWT_SECRET` ≥ 32 bytes, `sub` = username, `roles` array claim). The platform rejects anything else. Owner: team
- Missing: lockout logic (5 failures → 15 min) over `app_user.failed_logins`/`locked_until`. Owner: team
- Mismatch: `app_user` has `email` but no `username`, while the UI logs in with `username`. Owner: team
- Mismatch: the role check allows ADMIN/OPERATOR/MANAGER. The UI uses TECHNICIAN too, and the v1 contract mentions Auditor. Owner: team
- Missing: `GET /api/admin/users` (`User`: username, fullName, enabled, lockedUntil, lastLoginAt; there are no `enabled`/`last_login_at` columns). Owner: team
- Risk: starter-security with no config means every request gets a generated Basic-auth password. Owner: team

## Configuration and deployment (team)
- Missing: backend `Dockerfile` (render.yaml points to `./Dockerfile`). Owner: team
- Missing: actuator dependency for `/actuator/health` (render.yaml health check). Owner: team
- Mismatch: backend reads `DB_URL`/`MYSQL_USER`/`MYSQL_PASSWORD`, while render.yaml sets `ECP_DB_URL`/`ECP_DB_USER`/`ECP_DB_PASSWORD`/`ECP_DB_POOL`. Owner: team
- Missing: CORS for the frontend origin (`FRONTEND_HOST`). Owner: team
- Missing: STOMP `/ws` with `/topic/risk` and `/topic/alerts` (optional, because the UI polls). Owner: team
- Mismatch: path prefix `/api/v1/...` (docs/1-api-contract.md) vs `/api/...` (UI, CONTRACTS.md). Owner: team
- Mismatch: Java 17 / Boot 3.4.1 (backend, ci.yml) vs Java 21 / Boot 3.5.6 (platform, CLAUDE.md). Owner: team
- Risk: `db/schema.sql` runs `CREATE DATABASE corrosion_monitor; USE …` and uses `DELIMITER`, so it cannot run on Aiven `defaultdb` or through Flyway as is. Owner: team
- Missing: a migration tool for the team schema (only a Docker init script, which runs once on an empty volume). Owner: team
- Missing: `GET /api/profiles`. `equipment_profile` has no `tempMinC`/`tempMaxC`/`surfaceOffsetC` and names its columns `rh_warn_pct`/`dew_margin_warn_c`. Owner: team
- Mismatch: the UI is built around "equipment" (`equipmentId`). The schema only has `location` + `equipment_profile`, and there is no `equipment` table. Owner: team

## Analytics (Aayushee)
- Missing: `GET /api/equipment/status` → `EquipmentStatus[]`. Owner: Aayushee
- Missing: `GET /api/equipment/{id}/assessments?minutes=` → `AssessmentPoint[]`. Owner: Aayushee
- Missing: a `risk_assessments` table (ARCHITECTURE.md); the schema has `risk_state` (per-location score) and `forecast`. Owner: Aayushee
- Mismatch: analytics must read platform `readings` and skip `sensor_health.trusted = false`, but the schema has its own `reading` table. Owner: Aayushee
- Missing: producing `alert_signals` (`ANALYTICS:CONDENSATION_RISK:<id>`, `ANALYTICS:CONDENSATION_FORECAST:<id>`). Owner: Aayushee
- Missing: `GET /api/reports/summary` and `.csv` / `.pdf` (no OpenPDF dependency). Owner: Aayushee with Dumebi

## Alerts (Dumebi)
- Missing: `GET /api/alerts?status=&limit=` and `GET /api/alerts/{id}` → `{alert, actions}`. Owner: Dumebi
- Missing: `POST /api/alerts/{id}/acknowledge`, `/resolve {notes}`, `/actions {actionType, notes}`. Owner: Dumebi
- Mismatch: `alert.status` uses CLOSED, while the UI uses RESOLVED. The v1 contract uses `/ack`, the UI uses `/acknowledge`. Owner: Dumebi
- Mismatch: `alert` lacks source, title, escalation level, sensor id, resolved_at/by and condition_cleared_at, and it is keyed by `location_id`, not equipment. Owner: Dumebi
- Mismatch: `corrective_action` has `action_text`, while the UI sends `actionType` + `notes`. Owner: Dumebi
- Missing: consumer of `alert_signals` (in id order, sets `processed_at`, dedup by `dedup_key`). Owner: Dumebi

## Audit (Anela)
- Missing: `GET /api/audit?size=&page=&actor=&action=` → `AuditEntry[]`. Owner: Anela
- Missing: `POST /api/audit/verify` → `{intact, checked, firstBrokenId}`. Owner: Anela
- Mismatch: `audit_log` has event_type/user_id/resource/outcome, while the UI expects occurredAt/actorType/actor/action/entityType/entityId. Owner: Anela
- Missing: consumer of `audit_outbox` that writes the hash chain and sets `chained_at`. Owner: Anela

## Platform and frontend (Joshua)
- Duplicate: team `sensor` (UUID, location_id) vs platform `sensors` (BIGINT, device_id, equipment_id); decide which one is the source of truth. Owner: Joshua + team
- Duplicate: team `reading` (PK sensor_id+seq, dew_point_c, buffered) vs platform `readings` (seq_no, measured_at/received_at, surface_temp_c). Owner: Joshua + team
- Duplicate: team `used_nonce` (nonce replay protection, `/api/v1/readings`) vs platform timestamp+signature on `/api/ingest/readings`. Owner: Joshua + team
- Duplicate: team `forecast` table vs platform forecast API (`/api/sensors/{id}/forecast`). Owner: Joshua + Aayushee
- Duplicate: team `simulator/` module vs `platform/ecp-simulator` (different signing scheme). Owner: Joshua + team
- Mismatch: `sensor.health` (OK/SILENT/STUCK/IMPLAUSIBLE) vs `sensor_health.status` (HEALTHY/DEGRADED/FAULTY/STUCK/OFFLINE). Owner: Joshua
- Mismatch: the platform's default DB is `ecp`, while the team schema uses `corrosion_monitor`. Owner: Joshua
- Mismatch: `render.yaml` api block (Dockerfile, health path, env names) does not match the backend yet. Owner: Joshua with team
- No clash: `alert` + `alert_signals` and `audit_log` + `audit_outbox` have different roles (state vs inbox); keep both. Owner: none
