# Integration gaps

One line per missing or mismatched item, comparing what the UI and platform need (`frontend/src/api/endpoints.ts`, `types.ts`,
`CONTRACTS.md`, platform `V1__platform_tables.sql`) with the team backend. Started 2026-11-03 (`REPO_MAP.md` has that
snapshot); updated as items close. All paths use `/api/v1` (decision 3). Resolved items move to "Done" with their commit.
Owners: Aayushee (analytics), Dumebi (alerts), Anela (audit), Joshua (platform/frontend, and auth under decision 1), team (config).

## Open

### Joshua: authentication and deployment (decision 1)
- Missing: `POST /api/v1/auth/login {email, password}` → `{token, expiresAt, email, fullName, role}` and `GET /api/v1/auth/me`. Owner: Joshua
- Missing: JWT issuing (HS256, key from `ECP_JWT_SECRET` ≥ 32 bytes, `sub` = email, `roles` array claim). The platform rejects anything else. Owner: Joshua
- Missing: lockout logic (5 failures → 15 min) over `app_user.failed_logins`/`locked_until`. Owner: Joshua
- Mismatch: the UI signs in with `username`; decision 1 says email. Owner: Joshua
- Risk: starter-security with no config means every request gets a generated Basic-auth password. Owner: Joshua
- Missing: CORS for the frontend origin (`FRONTEND_HOST`). Owner: Joshua
- Missing: backend `Dockerfile`, a runnable (repackaged) jar, and the actuator dependency for `/actuator/health`. Owner: Joshua
- Mismatch: the `render.yaml` api block (Dockerfile path, health path, env names) does not match the backend; the backend reads `DB_URL`/`MYSQL_USER`/`MYSQL_PASSWORD`. Owner: Joshua
- Missing: on Aiven, `log_bin_trust_function_creators` must be ON, or V1 fails to create the `audit_log` triggers (MySQL error 1419). Owner: team

### Team (users and configuration)
- Missing: `GET /api/v1/admin/users` (`User`: username, fullName, enabled, lockedUntil, lastLoginAt; there are no `enabled`/`last_login_at` columns). Owner: team
- Missing: `GET /api/v1/profiles`. `equipment_profile` has no `tempMinC`/`tempMaxC`/`surfaceOffsetC` and names its columns `rh_warn_pct`/`dew_margin_warn_c`. Owner: team
- Missing: STOMP `/ws` with `/topic/risk` and `/topic/alerts` (optional, because the UI polls). Owner: team
- Mismatch: `docs/1-api-contract.md` still mentions an Auditor role; decision 4 has four roles (TECHNICIAN, OPERATOR, MANAGER, ADMIN). Owner: team

### Aayushee: analytics
- Missing: `GET /api/v1/equipment/status` → `EquipmentStatus[]`. Owner: Aayushee
- Missing: `GET /api/v1/equipment/{id}/assessments?minutes=` → `AssessmentPoint[]`. Owner: Aayushee
- Confirm or replace demo profile thresholds and risk_budget in V3 with cited values. Owner: Aayushee
- Missing: a `risk_assessments` table (ARCHITECTURE.md); the schema has `risk_state` and `forecast`, both keyed by location rather than equipment. Owner: Aayushee
- Missing: reading platform `readings` per equipment (`sensors.equipment_id`) and skipping `sensor_health.trusted = false`. Owner: Aayushee
- Missing: producing `alert_signals` (`ANALYTICS:CONDENSATION_RISK:<id>`, `ANALYTICS:CONDENSATION_FORECAST:<id>`). Owner: Aayushee
- Missing: `GET /api/v1/reports/summary` and `.csv` / `.pdf` (no OpenPDF dependency). Owner: Aayushee with Dumebi

### Dumebi: alerts
- Missing: `GET /api/v1/alerts?status=&limit=` and `GET /api/v1/alerts/{id}` → `{alert, actions}`. Owner: Dumebi
- Missing: `POST /api/v1/alerts/{id}/acknowledge`, `/resolve {notes}`, `/actions {actionType, notes}`. Owner: Dumebi
- Mismatch: `alert.status` uses CLOSED, while the UI uses RESOLVED. The v1 contract uses `/ack`, the UI uses `/acknowledge`. Owner: Dumebi
- Mismatch: `alert` lacks source, title, escalation level, sensor id, resolved_at/by and condition_cleared_at, and it is keyed by `location_id`, not equipment. Owner: Dumebi
- Mismatch: `corrective_action` has `action_text`, while the UI sends `actionType` + `notes`. Owner: Dumebi
- Missing: consumer of `alert_signals` (in id order, sets `processed_at`, dedup by `dedup_key`). Owner: Dumebi

### Anela: audit
- Missing: `GET /api/v1/audit?size=&page=&actor=&action=` → `AuditEntry[]`. Owner: Anela
- Missing: `POST /api/v1/audit/verify` → `{intact, checked, firstBrokenId}`. Owner: Anela
- Mismatch: `audit_log` has event_type/user_id/resource/outcome, while the UI expects occurredAt/actorType/actor/action/entityType/entityId. Owner: Anela
- Missing: consumer of `audit_outbox` that writes the hash chain and sets `chained_at`. Owner: Anela

### Joshua: platform
- Duplicate: team `forecast` table vs platform forecast API (`/api/v1/sensors/{id}/forecast`); decide which analytics uses. Owner: Joshua + Aayushee
- Duplicate: team `simulator/` module vs `platform/ecp-simulator` (different signing scheme). Owner: Joshua + team

### No action
- `alert` + `alert_signals` and `audit_log` + `audit_outbox` have different roles (state vs inbox); keep both.

## Done
- TECHNICIAN role: `chk_user_role` allows TECHNICIAN, OPERATOR, MANAGER, ADMIN (V2). 7a32f4b
- Equipment table: `equipment(location_id, profile_id, name)`, so a location has many items (V2); demo items 1–12 in V3 72462e2. 7a32f4b
- Dropped `sensor`, `reading`, `used_nonce`; the platform's `sensors`/`readings` (timestamp + HMAC ingestion) are the only copies. This also closes the `sensor.health` vs `sensor_health.status` mismatch. 7a32f4b
- Flyway: the team schema runs from `backend/src/main/resources/db/migration` (V1 = old `db/schema.sql` without `CREATE DATABASE`/`USE`/`DELIMITER`). c439a1b
- Database name `corrosion_monitor` for the platform too. 577973f
- One shared MySQL 8.0 for backend and platform locally (`docker-compose.platform.yml` includes `docker-compose.yml`). 73a72ab
- `/api/v1` prefix on every platform and frontend path. daa4e41
- Java 17 / Spring Boot 3.4.1 for the platform (decision 7). 75875d8
