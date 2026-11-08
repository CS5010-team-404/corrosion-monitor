# Corrosion Monitor 

Corrosion Monitor watches the temperature and humidity around electronic equipment and warns people before moisture can damage it. When warm, humid air meets a cooler surface, water condenses on circuit boards and connectors, and over time that leads to corrosion, short circuits and equipment failure that is expensive and hard to trace. The system collects readings from sensors (simulated SHT31 sensors, or an optional Raspberry Pi with a real SHT31), calculates the dew point and a corrosion risk index, forecasts condensation 15 to 60 minutes ahead, and raises warning and critical alerts that escalate if nobody responds. It is built for the people who look after that equipment: operators who watch conditions, technicians who respond to alerts and record what they fixed, managers who need to know when problems go unhandled, and administrators who manage users and devices. Every important action is recorded in an audit log.
## Team

| Member           | GitHub Username | Role          |
|------------------|-----------------|---------------|
| Aayushee Bhat    | @aayushee-bhat  | Analytics     |
| Anela Quiroz     | @anelajoy       | Audit         |
| Dumebi Onyebuchi | @dumebi2003     | Alerts        |
| Joshua Dominick  | @Joshuadommy    | Data Platform |

## Repository Structure

The layout follows the Phase 1 design: the team backend holds sign-in, alerts and the shared web layer; the data platform (ingestion, sensor health, simulator and forecaster) runs as its own service; the two meet only through the shared database tables described in `docs/CONTRACTS.md`.

```
corrosion-monitor/
├── .github/                      CI workflows
├── backend/                      Team Spring Boot app (Java 17)
│   ├── src/main/java/com/team404/corrosion/
│   │   ├── auth/                 Sign-in: login, lockout, JWT issuing, roles, demo users
│   │   ├── audit/                Audit service: hash-chain worker, verification, checkpoints, audit log API
│   │   ├── analytics/            Analytics service: equipment status (dew point, corrosion risk, time of wetness, level)
│   │   ├── alert/                Alert service: signal consumer, lifecycle API, escalation and email, live push
│   │   ├── web/                  Shared REST error responses, security rules, WebSocket (STOMP at /ws)
│   │   └── tools/                Runs the backend's Flyway migrations
│   ├── src/main/resources/db/migration/   Flyway migrations (V<n>__description.sql)
│   ├── src/test/                 Backend tests
│   └── Dockerfile
├── platform/                     Data platform, runs as its own service
│   ├── ecp-common                Dew-point maths, HMAC signing, alert-signal and audit-event publishers
│   ├── ecp-simulator             Simulated SHT31 sensors, 10 failure scenarios, standalone CLI
│   ├── ecp-forecast              ML condensation forecaster and early warning, trainer, bundled model
│   ├── ecp-ingestion             Signed device ingestion API
│   ├── ecp-health                Sensor health service
│   └── ecp-platform-app          Runnable service: security, CORS, WebSocket, device registry, simulator, forecast API
├── frontend/                     React + TypeScript dashboard (8 pages, built-in mock backend)
├── simulator/                    Original Java sensor simulator and scenario files
├── devices/raspberry-pi/         Optional real-sensor client (sender.py, systemd unit, README)
├── db/                           db/schema.sql kept for reference only; Flyway creates the schema
├── deploy/                       platform.Dockerfile
├── scripts/                      run-local.sh, secret generation, signed test reading, smoke test, model training
├── docs/                         Architecture, contracts, decisions, gaps, requirements, ML, deployment, demo
├── docker-compose.yml            Local MySQL 8.4 and MailHog (test email inbox)
├── docker-compose.platform.yml   Local MySQL and the data platform
├── render.yaml                   Render deployment blueprint
├── pom.xml                       Parent Maven project (the platform has its own pom in platform/)
├── .env.example                  Template for environment variables (copy to .env, never commit)
├── CLAUDE.md                     Rules for AI-assisted work in this repo
├── CONTRIBUTING.md               Branch model and workflow
└── KIT_README.md                 Notes from the base-project kit
```
## Setup and Run

**Live demo:** https://corrosion-watch-web.onrender.com (deployed on Render with an Aiven MySQL database; demo logins are shared privately with the instructor).

**Quickest way to run everything locally:** `scripts/run-local.sh` starts MySQL, the backend on :8080, the platform on :8081 and the website on :5173, and prints the demo logins. Stop it with `scripts/stop-local.sh`. The step-by-step commands below do the same thing by hand.

**Prerequisites:** JDK 17 or newer (CI and the platform use 17), Maven 3.x, Docker Desktop, Git, and Node.js 20 or newer for the frontend.

```bash
# Clone
git clone https://github.com/CS5010-team-404/corrosion-monitor.git
cd corrosion-monitor
git checkout dev
 
# Configure (never commit the real .env)
cp .env.example .env          # then edit the passwords; scripts/ has a secret generator
 
# Start MySQL and MailHog (test inbox at http://localhost:8025)
docker compose up -d
 
# Install dependencies, build and run the tests
mvn -B verify                              # backend and simulator
mvn -B -f platform/pom.xml verify          # data platform
cd frontend && npm install && cd ..
 
# Run the application
export MYSQL_USER=app_rw
export MYSQL_PASSWORD=<value from your .env>
mvn -pl backend spring-boot:run            # backend on http://localhost:8080 (Flyway creates the schema on startup)
docker compose -f docker-compose.platform.yml up --build   # data platform on http://localhost:8081
cd frontend && npm run dev                 # dashboard on http://localhost:5173, proxies to 8080 and 8081
 
# Run the tests
mvn -B verify
mvn -B -f platform/pom.xml verify
cd frontend && npm run lint && npm test && npm run build
```
No Java? `cd frontend && npm install && npm run dev:mock` runs the dashboard against a built-in mock backend. Sign in with a demo email (`tech@corrosionwatch.app`, `operator@corrosionwatch.app`, `manager@corrosionwatch.app` or `admin@corrosionwatch.app`) and any password.

**Environment variables** (copy `.env.example`; never commit real values):

| Variable                       | Purpose                                                                                                                                                                                                                                                                                                                                       |
|--------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `MYSQL_USER`, `MYSQL_PASSWORD` | Database account for the backend (`app_rw`).                                                                                                                                                                                                                                                                                                  |
| `SENSOR_SECRET`                | Signs readings sent by the simulator and devices.                                                                                                                                                                                                                                                                                             |
| `ECP_JWT_SECRET`               | Signs and verifies sign-in tokens (HS256). At least 32 bytes and identical on the backend and the platform. The backend will not start without it.                                                                                                                                                                                            |
| `ECP_DEMO_PASSWORD`            | Password for the seeded demo users. If it is not set, no demo users are created.                                                                                                                                                                                                                                                              |
| `ECP_DEMO_SEED`                | Set to `false` to turn off demo-user seeding.                                                                                                                                                                                                                                                                                                 |
| `ECP_ALERTS_*` (optional)      | Alert service settings, each with a default: `ECP_ALERTS_ESCALATE_AFTER` (`PT5M`), `ECP_ALERTS_QUIET_PERIOD` (`PT10M`), `ECP_ALERTS_POLL_INTERVAL` (`PT5S`), `ECP_ALERTS_ESCALATION_INTERVAL` (`PT30S`), `ECP_ALERTS_BATCH_SIZE` (`100`), `ECP_ALERTS_MAIL_FROM`, and `ECP_ALERTS_SCHEDULER_ENABLED` (`false` turns off the background jobs). |

**Demo accounts.** On an empty `app_user` table the backend creates one user per role, all with the password from `ECP_DEMO_PASSWORD`: `tech@`, `operator@`, `manager@` and `admin@corrosionwatch.app` (Technician, Operator, Manager, Admin).

## Progress So Far

| Functional Requirement (from Phase 1)                                            | Status  | Notes                                                                                                                                                                                                                                                                                                                                                                                                        |
|----------------------------------------------------------------------------------|---------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| FR1: Read temperature and humidity from multiple sensors at adjustable intervals | Partial | Simulated SHT31 sensors (10 scenarios), a Raspberry Pi client and a signed ingestion API are built. Sensor health checks 7 rules (offline, stuck, out of range, erratic, losing readings, bad signatures, clock skew) and marks each sensor trusted or untrusted. Runs end to end on the live demo. Each device has its own reporting interval (`expected_interval_s`, set when it is registered, default 15 s), which sensor health uses to judge late and missing readings.                                                                               |
| FR2: Forecast condensation 15-60 minutes ahead                                   | Partial | ML forecaster trained and evaluated (`docs/ML_FORECAST.md`), with a forecast API in the platform. Still to do: connect the Overview and Equipment pages to the analytics endpoints as they land, and retrain on a real dataset.                                                                                                                                                                              |
| FR3: Warning and critical alerts, with escalation                                | Partial | Sensor health writes `alert_signals`; the alert service turns them into WARNING/CRITICAL alerts (one per dedup key), auto-resolves them after a 10-minute quiet period, escalates a CRITICAL alert nobody acknowledged within 5 minutes and emails the managers (MailHog locally). Changes are pushed live on `/topic/alerts`.                                                                               |
| FR4: Acknowledge alerts and record corrective actions                            | Done    | `POST /api/v1/alerts/{id}/acknowledge`, `/resolve` and `/actions` (OPEN → ACKNOWLEDGED → RESOLVED; wrong-state requests get 409). Technicians and admins act, everyone else signed in reads only. Corrective actions have a type and notes.                                                                                                                                                                  |
| FR5: Roles with enforced access levels                                           | Partial | Sign-in, BCrypt hashing, 5-failure lockout and role-bearing tokens (four roles) are done; the platform accepts the same token. The alert endpoints let only technicians and admins act; the audit endpoints check the role on the server (log: managers and admins; verify and checkpoints: admins only) and record refused requests as `ACCESS_DENIED`. Role rules are enforced centrally in the backend's security configuration for all `/api/v1` paths, and by the platform for its own endpoints. |
| FR6: Accurate audit log                                                          | Done    | Sign-in, alert and platform events go to `audit_outbox`. The audit service chains them into a tamper-evident `audit_log` (SHA-256 hash chain), verifies the whole chain on demand, saves a checkpoint every night, and serves the log to managers and admins through `/api/v1/audit`. Details below.                                                                                                         |
| FR7: Export reports (CSV/PDF)                                                    | Not started | Not started; planned for Phase 3.                                                                                                                                                                                                                                                                                                                                                                                                       |
| FR8: Administrators configure equipment profiles and devices                     | Partial | Device registry exists in the platform, and Administration → Simulator lets an admin change how each simulated sensor behaves. Equipment profiles live in the database with warning and critical thresholds, and migration V8 adds kind, operating temperature range and surface offset so the UI reads them from there. [TODO: admin screens for editing users, devices and equipment profiles]             |

The Phase 1 proposal lists three roles in FR5. The implementation has four (Technician, Operator, Manager, Admin), as agreed in `docs/DECISIONS.md`. Anything the dashboard needs that does not exist yet is tracked in `docs/GAPS.md`.

The core monitoring calculations sit in the analytics service (`backend/.../analytics/`). `GET /api/v1/equipment/status` returns one status per piece of equipment, worked out like this:

1. `JdbcAnalyticsRepo` loads each item's equipment profile and its active sensors, putting trusted sensors first, and the last 24 hours of readings, but only from sensors that sensor health has marked trusted. A sensor with no health record yet counts as not trusted.
2. If there is no trusted reading, or the latest humidity is impossible (0% or below, or above 100%), the level is UNKNOWN and no figures are shown, rather than pretending the equipment is fine.
3. Otherwise `AnalyticsService` takes the latest reading. It uses the measured surface temperature when the sensor has one, and otherwise the air temperature minus the profile's surface offset. It computes the dew point with the team's shared Magnus formula (`alert.calculation.DewPoint`, constants 17.62 and 243.12 °C, the same one the alert service uses) and the condensation margin (surface temperature minus dew point).
4. The level (NORMAL, WARNING or CRITICAL) comes from comparing the margin and the relative humidity with that profile's warning and critical thresholds.
5. The corrosion risk index adds up Peck-model damage over every reading in the window, using the profile's own Peck parameters and reference conditions, and divides it by the profile's risk budget. Time of wetness is the number of wet minutes in the same 24 hours.

The response also carries the thresholds, operating temperature range, surface offset, sensor and device ids and whether the sensor is trusted, so the dashboard reads all of it from the database. The forecast fields (`forecastMargin15C`, `30C`, `60C` and `predictedCondensationAt`) are in the response already but stay empty until the forecast work lands.

**Working end to end:** Sign-in works today:

1. A user sends their email and password to `POST /api/v1/auth/login`.
2. The backend checks the password against the Bcrypt hash in `app_user`. The row is locked, so two simultaneous attempts cannot both read the same failure count.
3. On success, it returns an 8-hour token containing the email, role and name. The platform accepts the same token.
4. `GET /api/v1/auth/me` returns the signed-in user's email, name and role.
5. Five wrong passwords in a row lock the account for 15 minutes. While locked, every attempt is refused, even with the right password. An unknown email and a wrong password give the same error message.
6. Each sign-in success, failure and lockout is written to `audit_outbox`, with no passwords or tokens in the details.

**Working end to end:** Alerts, from signal to acknowledgement:

1. A producer (today, the platform's sensor health service) writes a RAISED or CLEARED row to `alert_signals`.
2. Every 5 seconds the alert service reads unprocessed signals in id order and marks each processed in the same transaction, so a crash never loses or repeats one. A signal that can never succeed is logged and skipped, so it cannot block the queue.
3. A RAISED signal opens an alert, or refreshes the unresolved alert with the same dedup key, so repeated signals never add rows. A CLEARED signal records when the condition cleared. The alert resolves itself once the condition has stayed clear for 10 minutes, so a sensor that flaps keeps one alert.
4. A technician or admin acknowledges the alert, logs corrective actions and resolves it through `/api/v1/alerts`.
5. Every 30 seconds the service escalates any CRITICAL alert still OPEN 5 minutes after it became critical. It raises the alert to escalation level 1, once, and emails every manager.
6. Every state change (opened, acknowledged, resolved, escalated, action logged) writes an `ALERT_*` or `CORRECTIVE_ACTION_LOGGED` event to `audit_outbox` in the same transaction. After the commit, a `{alertId, action}` notice goes to `/topic/alerts` so open dashboards refetch.

**Working end to end:** A failing sensor, from simulator to resolved alert (try it on the live demo):

1. In Administration → Simulator, set a sensor to "Silent" so it stops sending readings.
2. Within about 90 seconds, sensor health's offline rule flags it as untrusted and writes a RAISED signal to `alert_signals`.
3. The alert service opens an alert, and it appears on the Alerts page without a reload.
4. A technician acknowledges the alert, logs a corrective action and resolves it.
5. Every step is recorded in the tamper-evident audit log.

**Working end to end:** The audit log, from event to verification:

1. Any service writes an event (sign-in, alert change, platform event) to `audit_outbox` in the same transaction as the change it records.
2. About once a second the chain worker picks up rows not yet chained, in id order, in batches of 200. Each entry stores the previous entry's hash and its own SHA-256 hash over every field. The entries, the new chain head and the "chained" marks on the outbox rows commit together while the chain head is locked, so a crash never leaves a half-written chain and running the worker twice never duplicates an entry.
3. Managers and admins browse the log newest first with `GET /api/v1/audit`, filtering by actor, action, entity type and time range.
4. An admin calls `POST /api/v1/audit/verify`. It walks the whole chain and catches deleted or reordered entries (ids must be consecutive), broken links (each `prev_hash` must match the previous entry), edited rows (each hash is recomputed), and entries removed from the end (the chain must end at the recorded head). It also checks the chain still matches the saved checkpoints, and returns whether the chain is intact, how many entries were checked and the first broken entry.
5. Each verification run is stored (`GET /api/v1/audit/verifications`) and is itself written to the audit log as `AUDIT_VERIFIED`. A checkpoint of the chain head is saved every night at midnight UTC, or on demand with `POST /api/v1/audit/checkpoints`.

## Testing So Far

- Data platform: 47 unit and end-to-end tests covering the domain code and the simulator, with a JaCoCo coverage report. `mvn -B -f platform/pom.xml verify` passes, one "Tests run" line per module: ecp-common `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`, ecp-simulator `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`, ecp-forecast `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`, ecp-health `Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`, ecp-platform-app `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` (ecp-ingestion has no tests yet).
- Forecaster: trained and evaluated, results in `docs/ML_FORECAST.md`.
- Analytics: `DewPointTest` checks the shared dew point calculation against the platform's four reference values, so the backend and the platform give the same answer.
- Frontend: lint, test and build scripts, and a mock backend. `npm run lint` (the TypeScript type check, `tsc --noEmit`), `npm test` (`Tests  14 passed (14)`, 2 test files) and `npm run build` (`tsc -b && vite build`) all pass.
- Backend: `mvn -B verify` passes with `Tests run: 181, Failures: 0, Errors: 0, Skipped: 0`. That includes the sign-in and lockout tests (`AuthControllerTest` 11, `LoginPolicyTest` 6, `TokenServiceTest` 6, `AuthServiceLockoutTest` 3, `TokenFixtureTest` 2), the alert, audit and analytics tests, and `MigrationChecksumTest`. Tests that use MySQL run against MySQL 8.4 with the production (Aiven) SQL settings.
- Tests are run from IntelliJ IDEA (run the test folder or a single class from the gutter icon) and from the command line with mvn -B verify, which is also what CI runs. Tests that use a database need Docker Desktop running.

## Next Steps (Phase 3)

- Alert service: consume the analytics condensation risk and forecast signals, consider more escalation levels, and set up real email delivery for the deployed service
- Audit: copy checkpoints somewhere the application cannot write, so a full rewrite of the database can also be detected
- Role enforcement on every endpoint, and administrator screens for users, devices and equipment profiles
- CSV and PDF report exports
- Close the open items in `docs/GAPS.md`
- Analytics: the assessments endpoint, the 15, 30 and 60 minute forecast fields, and condensation alert signals (`ANALYTICS:CONDENSATION_RISK:<id>`) for the alert service
- Connect the Overview and Equipment pages to the analytics endpoints as they land
- Retrain the forecaster on a real dataset
- First weekly release from `dev` to `main`
- Security, sensor-failure and performance testing, keeping the Render deployment up to date
- Final documentation, NIST control mapping and evaluation results, and the December 11 presentation

## Team Contributions

| Member           | Contributions This Phase                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
|------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Aayushee Bhat    | Built the analytics service (`backend/.../analytics/`) and its first endpoint, `GET /api/v1/equipment/status` (`EquipmentStatusController`, `AnalyticsService`, `EquipmentStatus`): dew point and condensation margin, Peck corrosion risk index against each profile's risk budget, 24-hour time of wetness, and a status level from the profile's margin and humidity thresholds. The data layer (`AnalyticsRepo`, `JdbcAnalyticsRepo`, `EquipmentSnap`, `Reading`) loads only readings from trusted sensors and prefers a trusted sensor when an item has several. Risk model classes in `analytics/risk/` (`PeckRiskIndex`, `RiskAccumulator`, `RiskClassifier`, `TimeOfWetness`). Wrote migration V8 (equipment profile kind, temperature range and surface offset). Added the platform's dew point reference values to `DewPointTest`. Kept her items in `docs/GAPS.md` up to date. |
| Anela Quiroz     | Built the audit service (`backend/.../audit/`): the chain worker that turns `audit_outbox` events into the SHA-256 hash-chained `audit_log` (`AuditChainWorker`, `HashChain`, `AuditEntry`), chain verification against the head and saved checkpoints (`ChainVerifier`, `VerificationResult`), nightly and on-demand checkpoints (`AuditScheduler`), the SQL layer (`AuditStore`), and the role-checked REST API with the shapes the dashboard expects (`AuditController`, `AuditService`, `AuditViews`).                                                                                                                                                                                                                                                                                                                                                                                |
| Dumebi Onyebuchi | Created the repository and its layout: the Maven project with backend and simulator modules, the Spring Boot skeleton, the Docker Compose setup, .env.example, and CI that runs on dev and main. Wrote CONTRIBUTING.md with the branch model. Built the alert service (`backend/.../alert/`): the signal consumer that turns `alert_signals` into alerts (`SignalConsumer`, `SignalProcessor`, `JdbcAlertStore`); the alert lifecycle endpoints for listing, detail, acknowledge, resolve and corrective actions (`AlertController`, `AlertService`, `AlertStatus`); escalation of unacknowledged critical alerts with an email to the managers (`EscalationJob`, `MailManagerNotifier`); corrective actions with a type and notes; audit events and live updates on `/topic/alerts` (`OutboxAlertEvents`, `StompAlertBroadcaster`, `WebSocketConfig`); the shared dew point calculation (`DewPoint`); and migrations V5–V7 (alert lifecycle columns, corrective action type, alert device and critical-since).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| Joshua Dominick  | Data platform (`platform/`): signed device ingestion API; sensor health service with 7 rules that marks sensors trusted or untrusted; simulator with 12 devices and 10 failure scenarios; condensation forecaster (ridge regression and logistic early warning); device registry. Sign-in (`backend/.../auth`): BCrypt passwords, 15-minute lockout after 5 failures, HS256 JWT shared by backend and platform, four roles, demo users. Moved the schema to Flyway (V1 team schema, V2 agreed changes, V3 demo data) with a guard test against changing applied migrations. Web app (`frontend/`): React + TypeScript, all 8 pages, role-based navigation, live updates, mock mode for demos. Deployment on Render with Aiven MySQL: Dockerfiles, Render Blueprint, CI workflows, run and test scripts.                                                                                   |

## Notes

- Work happens on `feature/<name>-<topic>` branches merged into `dev` by pull request; `main` is updated only by the weekly release. See `CONTRIBUTING.md`.
- This project is designed in alignment with selected NIST SP 800-53 controls and the NIST CPS Framework. It is not certified or compliant. See `docs/` for the control mapping.
- Results from the simulator are labelled as simulated everywhere they appear.
- Never commit passwords, sensor secrets, or `.env` files.
