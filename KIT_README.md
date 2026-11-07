# Corrosion Watch base project

Everything the team needs to build on, added next to the existing backend without changing it.

```
frontend/                       React app: 8 pages, design system, API client, live updates, built-in mock backend
platform/                       Data platform (Java 21, Spring Boot 3.5), runs as its own service
  ecp-common                    dew point maths, HMAC signing, alert-signal and audit-event publishers
  ecp-simulator                 simulated SHT31 sensors, 10 scenarios, standalone CLI
  ecp-forecast                  ML forecaster + early warning, trainer, bundled model (no Spring)
  ecp-ingestion                 signed device ingestion API
  ecp-health                    sensor health service
  ecp-platform-app              runnable service: security, CORS, WebSocket, device registry, simulator, forecast API
deploy/platform.Dockerfile      container for the platform
render.yaml                     Render Blueprint: backend + platform (Docker) + frontend (static, free)
docker-compose.platform.yml     local MySQL + platform
scripts/                        gen-secrets, send-reading (signed), smoke-test, train-model
devices/raspberry-pi/           optional SHT31 client + systemd unit
.github/workflows/              platform.yml, frontend.yml (run only when their folders change)
CLAUDE.md                       rules for Claude Code in this repo
docs/                           architecture, contracts, requirements, ML, deployment, demo, prompts, UI design
```

## Add it to the repo
1. On a new branch, copy everything into the repository root. Nothing here replaces the team's files. If the repo
   already has a `CLAUDE.md` or `render.yaml`, merge rather than overwrite.
2. Commit, then follow `docs/prompts/00-start-here.md`.

## Quick start without Java
`cd frontend && npm install && npm run dev:mock`, open http://localhost:5173, sign in as `tech`, `operator`,
`manager` or `admin` with any password.

## Status
- Verified here: 43 platform unit and end-to-end tests pass (domain code and simulator); the forecaster was trained
  and evaluated (`docs/ML_FORECAST.md`); the React app was bundled and every page exercised in a browser against the
  mock backend on desktop and phone sizes; device signatures match across Java, bash and Python.
- Not yet compiled: the Spring Boot layers and the TypeScript type check (no internet where this was built). The first
  Claude Code prompt runs `mvn verify` and `npm run build` and fixes what comes up.
