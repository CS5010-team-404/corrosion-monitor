# Corrosion Monitor 

> Phase 2 README template. Replace everything in [brackets] and delete these
guidance notes before submitting.
[One paragraph: what your project does, who it's for, and the problem it solves.]
## Team

| Member           | GitHub Username | Role                          |
|------------------|-----------------|-------------------------------|
| Aayushee Bhat    | @aayushee-bhat  | Analytics |
| Anela Qurioz     | @anelajoy       | [Main area of responsibility] |
| Dumebi Onyebuchi | @dumebi2003     | Alerts |
| Joshua Dominick  | @Joshuadommy    | [Main area of responsibility] |

## Repository Structure

[Show your main folders and say what lives in each. The structure should match the
system design in your Phase 1 document.]
```
corrosion-monitor/
├── .github/                      CI workflows
├── backend/                      Team Spring Boot app (Java 17)
│   ├── src/main/java/com/team404/corrosion/
│   │   ├── auth/                 Sign-in: login, lockout, JWT issuing, roles, demo users
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
├── db/                           [TODO: confirm what remains here now that the schema is created by Flyway]
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

**Prerequisites:** JDK 17 or newer (CI and the platform use 17), Maven 3.x, Docker Desktop, Git, and Node.js [TODO: version] for the frontend.

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
No Java? `cd frontend && npm install && npm run dev:mock` runs the dashboard against a built-in mock backend. Sign in as `tech`, `operator`, `manager` or `admin` with any password.

**Environment variables** (copy `.env.example`; never commit real values):

| Variable                       | Purpose                                                                                                                                            |
|--------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| `MYSQL_USER`, `MYSQL_PASSWORD` | Database account for the backend (`app_rw`).                                                                                                       |
| `SENSOR_SECRET`                | Signs readings sent by the simulator and devices.                                                                                                  |
| `ECP_JWT_SECRET`               | Signs and verifies sign-in tokens (HS256). At least 32 bytes and identical on the backend and the platform. The backend will not start without it. |
| `ECP_DEMO_PASSWORD`            | Password for the seeded demo users. If it is not set, no demo users are created.                                                                   |
| `ECP_DEMO_SEED`                | Set to `false` to turn off demo-user seeding.                                                                                                      |
| `ECP_ALERTS_*` (optional)      | Alert service settings, each with a default: `ECP_ALERTS_ESCALATE_AFTER` (`PT5M`), `ECP_ALERTS_QUIET_PERIOD` (`PT10M`), `ECP_ALERTS_POLL_INTERVAL` (`PT5S`), `ECP_ALERTS_ESCALATION_INTERVAL` (`PT30S`), `ECP_ALERTS_BATCH_SIZE` (`100`), `ECP_ALERTS_MAIL_FROM`, and `ECP_ALERTS_SCHEDULER_ENABLED` (`false` turns off the background jobs). |

**Demo accounts.** On an empty `app_user` table the backend creates one user per role, all with the password from `ECP_DEMO_PASSWORD`: `tech@`, `operator@`, `manager@` and `admin@corrosionwatch.app` (Technician, Operator, Manager, Admin).

## Progress So Far

| Functional Requirement (from Phase 1)                                            | Status  | Notes                                                                                                                                                                                                                                                                                                                          |
|----------------------------------------------------------------------------------|---------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| FR1: Read temperature and humidity from multiple sensors at adjustable intervals | Partial | Simulated SHT31 sensors (10 scenarios), a Raspberry Pi client and a signed ingestion API are built. [TODO: confirm running end to end, and adjustable intervals]                                                                                                                                                               |
| FR2: Forecast condensation 15-60 minutes ahead                                   | Partial | ML forecaster trained and evaluated (`docs/ML_FORECAST.md`), with a forecast API in the platform. [TODO: confirm it feeds the dashboard and alerts]                                                                                                                                                                            |
| FR3: Warning and critical alerts, with escalation                                | Partial | Sensor health writes `alert_signals`; the alert service turns them into WARNING/CRITICAL alerts (one per dedup key), auto-resolves them after a 10-minute quiet period, escalates a CRITICAL alert nobody acknowledged within 5 minutes and emails the managers (MailHog locally). Changes are pushed live on `/topic/alerts`. |
| FR4: Acknowledge alerts and record corrective actions                            | Done    | `POST /api/v1/alerts/{id}/acknowledge`, `/resolve` and `/actions` (OPEN → ACKNOWLEDGED → RESOLVED; wrong-state requests get 409). Technicians and admins act, everyone else signed in reads only. Corrective actions have a type and notes.                                                                                    |
| FR5: Roles with enforced access levels                                           | Partial | Sign-in, BCrypt hashing, 5-failure lockout and role-bearing tokens (four roles) are done; the platform accepts the same token. [TODO: which endpoints enforce roles]                                                                                                                                                           |
| FR6: Accurate audit log                                                          | Partial | Sign-in events and platform events are written to `audit_outbox`. [TODO: status of chaining into `audit_log`, and of verification]                                                                                                                                                                                             |
| FR7: Export reports (CSV/PDF)                                                    | [TODO]  | [TODO]                                                                                                                                                                                                                                                                                                                         |
| FR8: Administrators configure equipment profiles and devices                     | [TODO]  | Device registry exists in the platform. [TODO: equipment profiles and admin screens]                                                                                                                                                                                                                                           |

The Phase 1 proposal lists three roles in FR5. The implementation has four (Technician, Operator, Manager, Admin), as agreed in `docs/DECISIONS.md`. Anything the dashboard needs that does not exist yet is tracked in `docs/GAPS.md`.

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

   [TODO: confirm and describe the sensor-to-dashboard path on the current `dev` branch, step by step: a simulated sensor sends a signed reading, the ingestion API validates and stores it, and what appears on the dashboard.]

## Testing So Far

- Data platform: 43 unit and end-to-end tests covering the domain code and the simulator, with a JaCoCo coverage report. [TODO: confirm by running `mvn -B -f platform/pom.xml verify` and paste the "Tests run" line]
- Forecaster: trained and evaluated, results in `docs/ML_FORECAST.md`.
- Frontend: lint, test and build scripts, and a mock backend. [TODO: confirm `npm run lint`, `npm test` and `npm run build` pass, including the TypeScript type check]
- Backend: [TODO: test count and whether they pass, including the sign-in and lockout tests]
- Tests are run from IntelliJ IDEA (run the test folder or a single class from the gutter icon) and from the command line with mvn -B verify, which is also what CI runs. Tests that use a database need Docker Desktop running.

## Next Steps (Phase 3)

- Alert service: consume the analytics condensation risk and forecast signals, consider more escalation levels, and set up real email delivery for the deployed service
- Chain `audit_outbox` into the tamper-evident `audit_log` and add the Admin verification screen
- Role enforcement on every endpoint, and administrator screens for users, devices and equipment profiles
- CSV and PDF report exports
- Close the open items in `docs/GAPS.md`
- Security, sensor-failure and performance testing, then deployment on Render
- Final documentation, NIST control mapping and evaluation results, and the December 11 presentation

## Team Contributions

| Member           | Contributions This Phase                                                                                                                                                                                                                      |
|------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Aayushee Bhat    | [TODO: features, classes or files built; tests written]                                                                                                                                                                                       |
| Anela Quiroz     | [TODO: features, classes or files built; tests written]                                                                                                                                                                                       |
| Dumebi Onyebuchi | Created the repository and its layout: the Maven project with backend and simulator modules, the Spring Boot skeleton, the Docker Compose setup, .env.example, and CI that runs on dev and main. Wrote CONTRIBUTING.md with the branch model. Built the alert service (`backend/.../alert/`)|
| Joshua Dominick  | Data platform (`platform/`: ingestion, sensor health, simulator, forecaster, shared code), React frontend, deployment files, Raspberry Pi client, sign-in in `backend/` (`auth` package), Flyway migrations. [TODO: tests written]            |

## Notes

- Work happens on `feature/<name>-<topic>` branches merged into `dev` by pull request; `main` is updated only by the weekly release. See `CONTRIBUTING.md`.
- This project is designed in alignment with selected NIST SP 800-53 controls and the NIST CPS Framework. It is not certified or compliant. See `docs/` for the control mapping.
- Results from the simulator are labelled as simulated everywhere they appear.
- Never commit passwords, sensor secrets, or `.env` files.
