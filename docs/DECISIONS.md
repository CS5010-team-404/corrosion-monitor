# Team decisions

Agreed by the team. Follow these until a later entry replaces them. Gaps they close or open are in `GAPS.md`.

| # | Date | Decision | Reason |
|---|---|---|---|
| 1 | 2026-11-03 | Joshua builds authentication in `backend/` on the team's `app_user` design: email login, BCrypt, lockout (5 failures, 15 min). | Nothing issues tokens yet, and both the platform and the UI depend on a JWT. Building it on the existing schema keeps the team's design. |
| 2 | 2026-11-03 | The platform's `sensors` and `readings` tables replace `sensor`, `reading` and `used_nonce` from `db/schema.sql`. | Ingestion, sensor health and the forecaster already run on them. Keeping two copies would split the data. |
| 3 | 2026-11-03 | All APIs use the `/api/v1` prefix (`docs/1-api-contract.md`). | It is the team's agreed contract and makes later versions possible. |
| 4 | 2026-11-03 | There are four roles: `TECHNICIAN`, `OPERATOR`, `MANAGER`, `ADMIN`. | The UI's alert workflow needs a technician role. |
| 5 | 2026-11-03 | Add an `equipment` table: a location has many equipment items, and each item has an `equipment_profile`. | The UI and the risk model work per equipment item, not per room. |
| 6 | 2026-11-03 | Joshua moves `db/schema.sql` to Flyway migrations so it runs on Aiven. | A Docker init script runs once and cannot run on Aiven's `defaultdb`. |
| 7 | 2026-11-03 | Java 17 and Spring Boot 3.4.1 everywhere (match the backend). | One toolchain for CI, Docker images and every teammate's machine. |
| 8 | 2026-11-03 | The platform runs as its own service and shares the database. | It keeps the team's build untouched. The services meet only in the shared tables. |
