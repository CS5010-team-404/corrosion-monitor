# Deployment (Render + Aiven MySQL)

Three services from one Blueprint (`render.yaml`): team backend and data platform as Docker web services on the
Starter plan (paid from the $100 Render credit), the React frontend as a free static site.

## 1. Database: Aiven for MySQL (free tier)
Create a free MySQL service at aiven.io. From its overview copy host, port, user and password. JDBC URL:
```
jdbc:mysql://HOST:PORT/defaultdb?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&rewriteBatchedStatements=true
```
The free tier allows few connections: keep the pools at 4-5 per service (`DB_POOL` backend, `ECP_DB_POOL` platform).

**Version: Aiven runs MySQL 8.4.** Local MySQL (`docker-compose.yml`) and the backend tests (`AivenMySql`) use
`mysql:8.4` to match. The migrations were verified there on 2026-11-04: `scripts/check-aiven.sh` applied V1-V3.
Flyway 10.20 logs "Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway ... latest supported
version of MySQL is 8.1". That warning is expected and harmless: the migrations, the backend tests and the platform all
run on 8.4.

**Production settings (checked by hand on our Aiven service).** Local MySQL (`docker-compose.yml`) and every backend
Testcontainers MySQL (`backend/src/test/.../AivenMySql.java`) run with the same values, so SQL that passes locally passes
in production:
```
sql_mode=REAL_AS_FLOAT,PIPES_AS_CONCAT,ANSI_QUOTES,IGNORE_SPACE,ONLY_FULL_GROUP_BY,ANSI,STRICT_ALL_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION
sql_require_primary_key=ON
log_bin_trust_function_creators=ON      (a test trigger was created successfully, so V1's audit_log triggers work)
```
What that means for SQL: string literals in single quotes only (`"x"` is an identifier under ANSI_QUOTES); `||` is
string concatenation, not OR; every selected column must be grouped or aggregated; every table needs a primary key;
values that do not fit are errors, not silent truncation.

**Schema.** The backend creates the team schema with Flyway on startup (`backend/src/main/resources/db/migration`,
history in `flyway_schema_history`); the platform adds its tables with its own history (`flyway_platform_history`).
Either may start first.

## 2. Secrets
`scripts/gen-secrets.sh` prints values for `ECP_SECRET_SALT` (and others if needed). `ECP_JWT_SECRET` is generated
once by Render in the `corrosion-watch-shared` env group, so both services use the same value. The backend signs
HS256 tokens with it (`sub` = email, `roles` = [role], `name`, 8 hours) and refuses to start if it is missing or
shorter than 32 bytes; the platform validates the same token.
`ECP_DEMO_PASSWORD` sets the password of the four demo users (tech@, operator@, manager@, admin@corrosionwatch.app)
that the backend creates on an empty `app_user` table; without it no demo users are created.

## 3. Blueprint
Render → New → Blueprint → choose the repository. Render reads `render.yaml`, then asks for every `sync: false`
variable: backend `DB_URL`, `MYSQL_USER`, `MYSQL_PASSWORD`, `ECP_DEMO_PASSWORD`; platform `ECP_DB_URL`, `ECP_DB_USER`,
`ECP_DB_PASSWORD`, `ECP_SECRET_SALT`. First builds take a few minutes.

**Service addresses are entered by hand.** `fromService ... property: host` gives Render's internal service name
(for example `corrosion-watch-api`), which browsers can't resolve, so these four values are `sync: false`. Enter the
public addresses shown at the top of each service's page in the Render dashboard, with no trailing slash:

| Service | Variable | Value |
|---|---|---|
| corrosion-watch-web | `VITE_API_URL` | `https://corrosion-watch-api.onrender.com` |
| corrosion-watch-web | `VITE_PLATFORM_URL` | `https://corrosion-watch-platform.onrender.com` |
| corrosion-watch-api | `FRONTEND_HOST` | `corrosion-watch-web.onrender.com` (host only; `https://` is added) |
| corrosion-watch-platform | `FRONTEND_HOST` | `corrosion-watch-web.onrender.com` |

The `VITE_*` values are built into the static site, so after changing them run Manual Deploy → "Clear build cache &
deploy" on corrosion-watch-web. A wrong `FRONTEND_HOST` shows up in the browser as a CORS error on sign-in.

**Optional frontend flags** (corrosion-watch-web → Environment; both default to `false`, rebuild after changing):
- `VITE_BACKEND_LIVE=true` once the team backend serves `/ws`; until then only the platform WebSocket connects.
- `VITE_MOCK_FALLBACK=true` for a demo before teammates' endpoints exist: those that answer 404/501 are filled with
  sample data from the built-in mock, every page using it shows "Sample data: this part isn't built yet", and the
  sidebar lists what is sample. Real endpoints are never replaced. Leave it off otherwise, so missing parts show as
  "Not built yet" with the endpoint and its owner (`frontend/README.md`).

## 4. Check the database migrations (optional, before or after the first deploy)
`scripts/check-aiven.sh` runs the backend's Flyway migrations once against the database in `DB_URL` and prints the
applied versions and the tables. It reads `DB_URL`, `MYSQL_USER` and `MYSQL_PASSWORD` from the environment only (never
from files), never prints the password, and refuses a `DB_URL` that contains one. It needs Java 17 and Maven, builds the
backend jar, then runs `com.team404.corrosion.tools.MigrateOnly` without starting the web application:
```
 DB_URL='jdbc:mysql://HOST:PORT/defaultdb?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' \
 MYSQL_USER=avnadmin MYSQL_PASSWORD='<from Aiven>' scripts/check-aiven.sh
```
(The leading space keeps the line out of shell history when `HISTCONTROL=ignorespace`.) Running it twice is safe:
the second run reports `Migrations run now: 0`. The backend runs the same migrations on startup anyway.

## 5. Check the services
```
scripts/smoke-test.sh https://corrosion-watch-platform.onrender.com https://corrosion-watch-web.onrender.com https://corrosion-watch-api.onrender.com
scripts/send-reading.sh SIM-001 '<secret>' https://corrosion-watch-platform.onrender.com
```
Open the web URL, sign in, and watch the simulated devices appear within a minute.

## 6. Escalation e-mail (SMTP)
When a CRITICAL alert stays unacknowledged for 5 minutes the backend emails every `MANAGER` user. Mail is best effort:
the escalation and its audit event are saved first, a mail failure is only logged, and the timeouts below keep a slow
or unreachable server from holding up the escalation job. Locally the defaults send to MailHog (`localhost:1025`,
inbox at http://localhost:8025), so nothing needs setting.

Set these on corrosion-watch-api → Environment (all `sync: false`; never commit them):

| Variable | Example | Notes |
|---|---|---|
| `SPRING_MAIL_HOST` | `smtp-relay.brevo.com` | default `localhost` (MailHog) |
| `SPRING_MAIL_PORT` | `587` | default `1025`; use 587 (or 2525), not 25 or 465 |
| `SPRING_MAIL_USERNAME` | the provider's SMTP login | empty = no sign-in (MailHog) |
| `SPRING_MAIL_PASSWORD` | the provider's SMTP key / app password | |
| `ECP_ALERTS_MAIL_FROM` | `alerts@yourdomain` | must be a sender the provider has verified; default `alerts@corrosionwatch.app` |
| `SPRING_MAIL_STARTTLS` | `true` | optional; default `true` (STARTTLS whenever the server offers it) |

Fixed in `application.properties`: connection timeout 5 s, read and write timeouts 10 s.

Free SMTP services that work (pick one):
- **Brevo** (300 emails/day free): host `smtp-relay.brevo.com`, port 587 (or 2525), username = the SMTP login shown
  under SMTP & API, password = an SMTP key. Verify the sender address first.
- **Mailtrap**: the Sandbox (`sandbox.smtp.mailtrap.io`, port 2525 or 587) catches every message in a test inbox,
  which is ideal for the demo since no real mail goes out; Email Sending (`live.smtp.mailtrap.io`, 587) needs a
  verified domain.
- **Gmail app password**: host `smtp.gmail.com`, port 587, username = the Gmail address, password = a 16-character
  app password (Google account → Security → 2-Step Verification → App passwords). `ECP_ALERTS_MAIL_FROM` must be that
  Gmail address. Gmail does not offer 2525.

Check it: make a manager user with a real inbox (or use the Mailtrap sandbox), raise a CRITICAL alert (on the Simulator page,
set a device to `SILENT`: sensor health reports it OFFLINE, which is CRITICAL), leave it unacknowledged for 5 minutes, and look for "Emailed 1 manager(s) about
escalated alert N" in the backend log (or "Could not email managers about alert N: ..." with the reason).

## Notes
- Free web services sleep after 15 idle minutes; the platform must not (simulator, health checks), hence Starter.
- Render blocks outbound SMTP on Free instances (ports 25, 465, 587) and port 25 everywhere. The backend runs on
  Starter, where 587 works; 2525 is a fallback for providers that offer it (section 6).
- The demo devices link to equipment ids 1-12, which V3 creates with the same names. To point a device at another
  item: `UPDATE sensors SET equipment_id = <id>, equipment_name = '<name>', location_name = '<loc>' WHERE device_id = 'SIM-001';`
- Local: `docker compose -f docker-compose.platform.yml up --build`, then `cd frontend && npm run dev`.
  Without Java: `cd frontend && npm run dev:mock`.
