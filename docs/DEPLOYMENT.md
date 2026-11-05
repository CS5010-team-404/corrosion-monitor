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

## Notes
- Free web services sleep after 15 idle minutes; the platform must not (simulator, health checks), hence Starter.
- Render's Free and Starter plans block outbound SMTP ports; send alert e-mails through an HTTPS e-mail API, or keep
  notifications in the app.
- The demo devices link to equipment ids 1-12, which V3 creates with the same names. To point a device at another
  item: `UPDATE sensors SET equipment_id = <id>, equipment_name = '<name>', location_name = '<loc>' WHERE device_id = 'SIM-001';`
- Local: `docker compose -f docker-compose.platform.yml up --build`, then `cd frontend && npm run dev`.
  Without Java: `cd frontend && npm run dev:mock`.
