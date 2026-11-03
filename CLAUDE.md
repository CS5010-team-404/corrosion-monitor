# Corrosion Watch: rules for Claude Code

Programming Design Paradigms team project (Northeastern). Team: Aayushee Bhat (analytics), Dumebi Onyebuchi
(alerts), Anela Quiroz (audit), Joshua Dominick (sensor health, ingestion, simulator, forecaster, frontend,
deployment). Read `docs/ARCHITECTURE.md` and `docs/CONTRACTS.md` before changing anything.

## Ground rules
- The team backend belongs to everyone. Do not refactor, reformat, rename, move or delete its code. Change it only
  when the person running this session owns that area, or when a prompt explicitly says so and the diff has been shown
  and approved.
- Joshua's code: `platform/`, `frontend/`, `deploy/`, `scripts/`, `devices/`, `render.yaml`, `docker-compose.platform.yml`.
- Exception (team decision, `docs/DECISIONS.md`): Joshua may change `backend/` and `db/` only for decisions 1
  (authentication), 2 (platform sensors/readings replace sensor/reading/used_nonce), 4 (TECHNICIAN role),
  5 (equipment table) and 6 (Flyway migrations). Everything else there stays with its owners.
- Follow `docs/DECISIONS.md`: `/api/v1` prefix, four roles, Java 17 / Spring Boot 3.4.1, platform as its own service.
- Services meet only in the shared tables in `docs/CONTRACTS.md`. Never call another service directly.
- When something the UI needs does not exist, record it in `docs/GAPS.md` with its owner; do not build other
  people's areas without them.
- Branch per task (`feature/<area>-<topic>`), small commits, pull requests with a short description and test results.
- Never commit secrets, `.env` files or real passwords.

## Commands
```
mvn -B -f platform/pom.xml verify                 # platform build + tests (43 tests), JaCoCo report
docker compose -f docker-compose.platform.yml up --build
cd frontend && npm install && npm run dev        # UI on :5173, proxies to :8080 (backend) and :8081 (platform)
cd frontend && npm run dev:mock                  # UI on the built-in mock backend, no Java needed
cd frontend && npm run lint && npm test && npm run build
scripts/smoke-test.sh <platform-url> <web-url> [<api-url>]
```

## Code style
- Java 21, Spring Boot 3.5. Domain logic in plain Java classes with unit tests; Spring only at the edges.
- JUnit 5, aim for 70% coverage (course requirement).
- Frontend: React 18 + TypeScript strict, no UI library. Use classes from `src/styles/design-system.css` and the
  components in `src/components/`. URLs live only in `src/api/endpoints.ts`.
- Design (docs/ui-design.html): white surfaces, generous space, one blue accent, colour only for status, Cabin font.
  Every data view has a loading skeleton, an empty state and an error state; busy buttons show a spinner.
