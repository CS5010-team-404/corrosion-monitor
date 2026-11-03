# Deployment (Render + Aiven MySQL)

Three services from one Blueprint (`render.yaml`): team backend and data platform as Docker web services on the
Starter plan (paid from the $100 Render credit), the React frontend as a free static site.

## 1. Database: Aiven for MySQL (free tier)
Create a free MySQL service at aiven.io. From its overview copy host, port, user and password. JDBC URL:
```
jdbc:mysql://HOST:PORT/defaultdb?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&rewriteBatchedStatements=true
```
The free tier allows few connections: keep `ECP_DB_POOL` at 4-5 per service.

## 2. Secrets
`scripts/gen-secrets.sh` prints values for `ECP_SECRET_SALT` (and others if needed). `ECP_JWT_SECRET` is generated
once by Render in the `corrosion-watch-shared` env group, so both backends use the same value.
**The team backend must sign JWTs with HS256 using `ECP_JWT_SECRET`** and put roles in a `roles` claim. If it uses
something else, change `SecurityConfig` in the platform (or `ECP_JWT_ROLES_CLAIM`), not the backend.

## 3. Blueprint
Render → New → Blueprint → choose the repository. Render reads `render.yaml`, then asks for every `sync: false`
variable: the three `ECP_DB_*` values for both backends, and `ECP_SECRET_SALT`. First builds take a few minutes.

## 4. Check
```
scripts/smoke-test.sh https://corrosion-watch-platform.onrender.com https://corrosion-watch-web.onrender.com https://corrosion-watch-api.onrender.com
scripts/send-reading.sh SIM-001 '<secret>' https://corrosion-watch-platform.onrender.com
```
Open the web URL, sign in, and watch the simulated devices appear within a minute.

## Notes
- Free web services sleep after 15 idle minutes; the platform must not (simulator, health checks), hence Starter.
- Render's Free and Starter plans block outbound SMTP ports; send alert e-mails through an HTTPS e-mail API, or keep
  notifications in the app.
- The demo devices link to equipment ids 1-12. Point them at real rows with
  `UPDATE sensors SET equipment_id = <id>, equipment_name = '<name>', location_name = '<loc>' WHERE device_id = 'SIM-001';`
- Local: `docker compose -f docker-compose.platform.yml up --build`, then `cd frontend && npm run dev`.
  Without Java: `cd frontend && npm run dev:mock`.
