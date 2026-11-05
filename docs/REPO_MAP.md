# Repository map

Snapshot of `feature/platform-kit` on 2026-11-03 (the team backend is the same as on `origin/dev`, 0231091).
Integration gaps found while making this map are listed in `GAPS.md`.

## Structure and owners

| Path | What | Owner |
|---|---|---|
| `pom.xml` | Maven parent (modules `backend`, `simulator`) | team |
| `backend/` | Team backend: Spring Boot skeleton | team (analytics Aayushee, alerts Dumebi, audit Anela) |
| `simulator/` | Team's plain-Java sensor simulator (7 classes, no tests) | team |
| `db/schema.sql` | Team MySQL schema, run by Docker on first start | team |
| `docker-compose.yml` | MySQL 8.0 + MailHog for the team backend | team |
| `.github/workflows/ci.yml` | Team CI: `mvn -B verify`, JDK 17 | team |
| `docs/1-api-contract.md` | Team's planned API (v1), not implemented | team |
| `platform/` | Data platform: ingestion, sensor health, simulator, forecaster, runnable app | Joshua |
| `frontend/` | React 18 + TypeScript UI | Joshua |
| `deploy/`, `render.yaml`, `docker-compose.platform.yml`, `scripts/`, `devices/` | Deployment, tooling, Raspberry Pi client | Joshua |
| `.github/workflows/platform.yml`, `frontend.yml` | Platform and frontend CI | Joshua |

## Team backend

| Item | Value |
|---|---|
| Build | Maven multi-module, root `pom.xml` imports `spring-boot-dependencies` |
| Java / Spring Boot | **17** / **3.4.1** (platform: 21 / 3.5.6) |
| Base package | `com.team404.corrosion` |
| Source | Only `Application.java` (`@SpringBootApplication`) and `application.properties` |
| Starters | web, security, data-jpa, validation, thymeleaf, mail; `mysql-connector-j`; test: spring-boot-starter-test, spring-security-test, Testcontainers MySQL |
| Not present | actuator, JWT library, websocket, Flyway/Liquibase, OpenPDF |
| Controllers, entities, services | none |
| Tests | none (`No tests to run`) |
| Dockerfile | none |
| Port | 8080 |

### Configuration (`backend/src/main/resources/application.properties`)

| Property | Env var | Default |
|---|---|---|
| `spring.datasource.url` | `DB_URL` | `jdbc:mysql://localhost:3306/corrosion_monitor?serverTimezone=UTC` |
| `spring.datasource.username` | `MYSQL_USER` | `app_rw` |
| `spring.datasource.password` | `MYSQL_PASSWORD` | empty |
| `spring.jpa.hibernate.ddl-auto` | none | `validate` |
| `spring.mail.host/port` | none | `localhost:1025` (MailHog) |

`.env.example` (team) lists `MYSQL_ROOT_PASSWORD`, `MYSQL_USER`, `MYSQL_PASSWORD` and `SENSOR_SECRET`.

### Schema

`db/schema.sql` is mounted into `/docker-entrypoint-initdb.d`, so it runs only when the data volume is empty.
Hibernate only validates the schema. No migration tool is used. The script starts with
`CREATE DATABASE corrosion_monitor; USE corrosion_monitor;` and uses `DELIMITER $$` for the audit triggers.

| Table | Key columns |
|---|---|
| `app_user` | id, name, email (unique), password_hash (BCrypt), role ∈ ADMIN/OPERATOR/MANAGER, failed_logins, locked_until |
| `equipment_profile` | rh_warn_pct, rh_critical_pct, dew_margin_warn_c, dew_margin_critical_c, risk_budget, Peck constants |
| `location` | name, description, profile_id → equipment_profile |
| `sensor` | id CHAR(36) UUID, location_id, secret_enc, last_seq, last_seen, health ∈ OK/SILENT/STUCK/IMPLAUSIBLE/UNKNOWN |
| `reading` | PK (sensor_id, seq), ts, temperature_c, humidity_pct, dew_point_c, buffered |
| `used_nonce` | (sensor_id, nonce), seen_at: replay protection |
| `risk_state` | per location: cumulative_damage, window_start, risk_score |
| `forecast` | location_id, made_at, horizon_min, model, pred_temp_c, pred_rh_pct, pred_margin_c |
| `alert` | location_id, type, severity ∈ WARNING/CRITICAL, status ∈ OPEN/ACKNOWLEDGED/CLOSED, escalated_at, acknowledged_by/at, ack_note |
| `corrective_action` | alert_id, user_id, action_text |
| `audit_log` | ts, event_type, user_id, source_ip, resource, outcome, details, prev_hash, hash; UPDATE/DELETE blocked by triggers |

### Platform tables (`platform/ecp-platform-app/.../db/platform/V1__platform_tables.sql`)

`sensors`, `readings`, `ingest_rejections`, `sensor_health`, `sensor_health_events`, `alert_signals`, `audit_outbox`,
`service_heartbeats`, `job_cursors`. All of them use `CREATE TABLE IF NOT EXISTS`, and Flyway uses its own history table `flyway_platform_history`
(baseline-on-migrate, baseline 0). No name is identical to a team table. The near-duplicates are `sensor`/`sensors` and
`reading`/`readings` (see `GAPS.md`).

## API inventory (team backend)

**No endpoints are implemented.** With `spring-boot-starter-security` on the classpath and no security config, Spring
Boot protects every path with HTTP Basic/form login and prints a generated password at startup.

The team's *planned* API is in `docs/1-api-contract.md`. It uses the `/api/v1/...` prefix, roles Admin/Operator/Auditor, and
the error body `{timestamp, status, error, message}`. The UI expects `/api/...` paths and the shapes in
`frontend/src/api/types.ts` (summarised in `CONTRACTS.md`).

## Authentication

| Item | Designed (schema / v1 contract) | Implemented | Platform and UI expect |
|---|---|---|---|
| Login endpoint | `POST /api/v1/auth/login` | none | `POST /api/auth/login {username, password}` → `{token, expiresAt, username, fullName, role}` |
| Login identifier | `app_user.email` (no username column) | none | `username` |
| Password hashing | BCrypt (`password_hash`) | none | BCrypt |
| Lockout | `failed_logins`, `locked_until` columns | none | 5 failures → 15 min |
| Token | "JWT or session" | none | JWT HS256, key from `ECP_JWT_SECRET` (≥ 32 bytes) |
| Claims | not specified | none | `sub` = username, roles array in `roles` (`ECP_JWT_ROLES_CLAIM`), platform adds `ROLE_` prefix |
| Lifetime | not specified | none | `expiresAt` returned to the UI |
| Roles | ADMIN, OPERATOR, MANAGER (+ "Auditor" in the contract) | none | TECHNICIAN, OPERATOR, MANAGER, ADMIN |

**WebSocket/STOMP:** the team backend has none. The platform serves STOMP at `/ws` (topics `/topic/readings`, `/topic/health`). The UI
also subscribes to `/topic/risk` and `/topic/alerts` on the backend, and it falls back to polling.

## CI and deployment
- `ci.yml` (team): `mvn -B verify` at the root on JDK 17. This builds `backend` and `simulator` only, and runs Testcontainers if tests exist.
- `platform.yml` / `frontend.yml` (Joshua): platform `mvn verify` and frontend lint/test/build.
- `render.yaml`: three services. The `corrosion-watch-api` block assumes `./Dockerfile`, `/actuator/health` and env vars
  `ECP_DB_URL`/`ECP_DB_USER`/`ECP_DB_PASSWORD`/`ECP_DB_POOL`/`ECP_JWT_SECRET`/`FRONTEND_HOST`. None of these exist in the backend
  yet (it reads `DB_URL`/`MYSQL_USER`/`MYSQL_PASSWORD`).
- `docker-compose.yml` (team): MySQL 8.0 on 3306 + MailHog on 1025/8025. `docker-compose.platform.yml` (Joshua): platform stack.

## How to run

```
cp .env.example .env                                   # set passwords
docker compose up -d                                   # MySQL (runs db/schema.sql once) + MailHog
mvn -B verify                                          # team backend + simulator
mvn -pl backend spring-boot:run                        # backend on :8080 (no endpoints yet; needs MYSQL_PASSWORD)
mvn -B -f platform/pom.xml verify                      # platform build + tests
docker compose -f docker-compose.platform.yml up --build
cd frontend && npm install && npm run dev              # UI on :5173 → :8080 and :8081
cd frontend && npm run dev:mock                        # UI with in-browser mock backend
```

Note: the platform defaults to database `ecp`, and the team schema creates `corrosion_monitor`. To share one database, point
`ECP_DB_URL` at `corrosion_monitor`.

## Build result (2026-11-03)

`mvn -B verify` at the root on local JDK 23 (compiling with `release 17`) gave **BUILD SUCCESS**: backend and simulator compiled and packaged,
with **0 tests** ("No tests to run" in both modules).
