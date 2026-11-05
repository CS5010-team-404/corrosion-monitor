Continue from docs/REPO_MAP.md. Work only inside platform/, deploy/ and docker-compose.platform.yml.

1. Run `mvn -B -f platform/pom.xml verify`. Fix compile errors and failing tests in platform/ (the Spring layers
   were written without a compiler; the pure-Java domain code and its 43 tests are known to pass).
2. Align platform security with the backend's JWT from REPO_MAP.md: algorithm, key env var, roles claim and role
   prefix. Prefer configuration (ECP_JWT_ROLES_CLAIM, ECP_JWT_SECRET) over code changes. If the backend does not
   issue JWTs at all, stop and explain the options.
3. If REPO_MAP.md shows a clash with an existing table, adapt the platform's queries or migration, never the
   backend's tables.
4. Start `docker compose -f docker-compose.platform.yml up --build` and prove, with commands and output:
   demo devices are seeded; readings arrive (`SELECT COUNT(*) FROM readings` grows); switching SIM-010 to STUCK
   through POST /api/admin/simulator/SIM-010/scenario makes /api/health/sensors report STUCK and trusted=false
   within about 5 minutes; GET /api/sensors/{id}/forecast returns a forecast; scripts/send-reading.sh with a wrong
   secret gives 401 and shows up as a BAD_SIGNATURE rejection.
5. Commit on feature/platform and stop with a summary.
