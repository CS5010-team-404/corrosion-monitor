# Integration gaps

One line per missing or mismatched item, comparing what the UI and platform need (`frontend/src/api/endpoints.ts`, `types.ts`,
`CONTRACTS.md`, platform `V1__platform_tables.sql`) with the team backend. Started 2026-11-03 (`REPO_MAP.md` has that
snapshot); updated as items close. All paths use `/api/v1` (decision 3). Resolved items move to "Done" with their commit.
Owners: Aayushee (analytics), Dumebi (alerts), Anela (audit), Joshua (platform/frontend, and auth under decision 1), team (config).

## Open

### Team (users and configuration)
- Missing: `GET /api/v1/admin/users` (`User`: username, fullName, enabled, lockedUntil, lastLoginAt; there are no `enabled`/`last_login_at` columns). Owner: team
- Missing: `GET /api/v1/profiles`. `equipment_profile` has no `tempMinC`/`tempMaxC`/`surfaceOffsetC` and names its columns `rh_warn_pct`/`dew_margin_warn_c`. Owner: team
- Missing: `/topic/risk` messages (`/ws` now exists, see Done; analytics publishes through `SimpMessagingTemplate`). Owner: Aayushee
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
- Mismatch: `docs/1-api-contract.md` still lists `/alerts/{id}/ack` and Operator as the acting role; the code follows the UI and CONTRACTS.md (`/acknowledge`, TECHNICIAN and ADMIN). Owner: Dumebi

### Anela: audit
- Missing: `GET /api/v1/audit` entries don't include `prevHash` and `hash` yet; the Audit page shows "–" in the Hash column until they do. Owner: Anela
- Mismatch: the UI asks for `size=500`, the backend caps a page at 200, so the page shows the newest 200 entries. Owner: Anela + Joshua

### Joshua: platform
- Duplicate: team `forecast` table vs platform forecast API (`/api/v1/sensors/{id}/forecast`); decide which analytics uses. Owner: Joshua + Aayushee
- Duplicate: team `simulator/` module vs `platform/ecp-simulator` (different signing scheme). Owner: Joshua + team

### No action
- `alert` + `alert_signals` and `audit_log` + `audit_outbox` have different roles (state vs inbox); keep both.

## Done
- Alert audit and live updates: every alert state change (opened, acknowledged, resolved by a user or by the quiet period, escalated, corrective action logged) writes `audit_outbox` in the same transaction (`ALERT_OPENED`, `ALERT_ACKNOWLEDGED`, `ALERT_RESOLVED`, `ALERT_ESCALATED`, `CORRECTIVE_ACTION_LOGGED`; entity `alert`), and after commit a notice `{alertId, action}` goes to `/topic/alerts`. Severity refreshes and CLEARED signals push `ALERT_UPDATED` but write no audit event, because they are not state changes.
- Backend STOMP at `/ws` (simple broker `/topic`, origins from `ecp.ws.allowed-origin-patterns`). The handshake is open, so topics carry change notices only. Approved for the alerts work; the shared edits were `backend/pom.xml` and `SecurityConfig`.
- Alert endpoints: `GET /api/v1/alerts?status=ALL|ACTIVE|OPEN|ACKNOWLEDGED|RESOLVED&limit=`, `GET /api/v1/alerts/{id}` → `{alert, actions}`, `POST …/acknowledge`, `…/resolve {notes}`, `…/actions {actionType, notes}`. OPEN → ACKNOWLEDGED → RESOLVED (an OPEN alert may be resolved directly), wrong-state requests get 409, TECHNICIAN and ADMIN act, everyone signed in reads. `corrective_action` has `action_type` and `notes` (V6).
- UI on the alert endpoints: the Alerts page, the bell and the Overview's open-alerts count call them directly (no longer in `PENDING`; the Overview itself still shows "Not built yet" until equipment status exists), and with `VITE_BACKEND_LIVE=true` (set by `scripts/run-local.sh`) they refetch on `/topic/alerts`. V5–V7 are in `applied-migrations.txt`, so `MigrationChecksumTest` guards them.
- Escalation: an OPEN CRITICAL alert not acknowledged within 5 minutes (`ecp.alerts.escalate-after`) gets `escalation_level` 1 once and the managers are emailed (best effort; MailHog locally).
- Alerts: `alert` has equipment, sensor, source, title, dedup key, escalation level, resolved_at/by and condition_cleared_at, and RESOLVED replaces CLOSED (V5); `device_id` and `critical_since` (escalation counts from it) in V7. The consumer (`alert/signal`) reads `alert_signals` in id order and sets `processed_at`: RAISED opens or updates one alert per dedup key, CLEARED records `condition_cleared_at`, and an alert auto-resolves after a quiet period (`ecp.alerts.quiet-period`, default 10 minutes).
- Login: `POST /api/v1/auth/login {email, password}` → `{token, expiresAt, email, fullName, role}` and `GET /api/v1/auth/me`, on `app_user` (BCrypt, same message for unknown email and wrong password). a4c64b0, 07c7480
- Lockout: 5 consecutive failures lock for 15 minutes (`failed_logins`, `locked_until`), refused even with the right password, success resets; times stored in UTC. a4c64b0, 2035586
- JWT: HS256 with `ECP_JWT_SECRET` (startup fails if missing or < 32 bytes), `sub` = email, `roles` = [role], `name`, 8 hours; validated exactly like the platform. 07c7480
- Security: stateless, CSRF off, role rules per CONTRACTS, CORS for `FRONTEND_HOST` + localhost:5173, health-only actuator (replaces the generated Basic-auth password). d19a992
- Audit: `USER_LOGIN`, `USER_LOGIN_FAILED`, `ACCOUNT_LOCKED` written to `audit_outbox`. e689d86
- Audit: `GET /api/v1/audit` (managers and admins), `POST /api/v1/audit/verify` (admins; others get 403 "Administrator access required.") and the outbox-to-chain worker, from PR #5 and #6. The UI uses them directly.
- Demo users (one per role) from `ECP_DEMO_PASSWORD`. 62c7cff
- UI signs in with email. 9578a2a
- Runnable backend jar, `backend/Dockerfile`, and the `render.yaml` api block with the env vars the backend reads. ed345f2, 081e0ff
- TECHNICIAN role: `chk_user_role` allows TECHNICIAN, OPERATOR, MANAGER, ADMIN (V2). 7a32f4b
- Equipment table: `equipment(location_id, profile_id, name)`, so a location has many items (V2); demo items 1–12 in V3 72462e2. 7a32f4b
- Dropped `sensor`, `reading`, `used_nonce`; the platform's `sensors`/`readings` (timestamp + HMAC ingestion) are the only copies. This also closes the `sensor.health` vs `sensor_health.status` mismatch. 7a32f4b
- Flyway: the team schema runs from `backend/src/main/resources/db/migration` (V1 = old `db/schema.sql` without `CREATE DATABASE`/`USE`/`DELIMITER`). c439a1b
- Database name `corrosion_monitor` for the platform too. 577973f
- One shared MySQL for backend and platform locally (`docker-compose.platform.yml` includes `docker-compose.yml`). 73a72ab
- `/api/v1` prefix on every platform and frontend path. daa4e41
- Java 17 / Spring Boot 3.4.1 for the platform (decision 7). 75875d8
- Aiven `log_bin_trust_function_creators` is already ON (checked by hand: a test trigger was created), so V1's `audit_log` triggers run there. Production `sql_mode` and `sql_require_primary_key=ON` are recorded in DEPLOYMENT.md and applied locally and in backend tests; all migrations, backend tests and every platform endpoint pass under them (no SQL changes were needed).
- Aiven production check: `scripts/check-aiven.sh` applied V1-V3 on Aiven (MySQL 8.4) on 2026-11-04. Local MySQL and the
  backend tests now use `mysql:8.4` to match; the Flyway "upgrade recommended" warning for 8.4 is expected (DEPLOYMENT.md).
