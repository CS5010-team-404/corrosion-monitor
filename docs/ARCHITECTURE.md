# Corrosion Watch: architecture

Electronics Corrosion Prevention, Programming Design Paradigms team project (Northeastern).
Early warning for humidity and condensation risk on electronics: racks, telecom cabinets, PLC panels and outdoor
enclosures.

## Who owns what

| Area | Owner | Where |
|---|---|---|
| Analytics: risk index, time of wetness, forecasts in risk assessments, reports | Aayushee Bhat | team backend (can use `platform/ecp-forecast`) |
| Alerts: lifecycle, escalation, corrective actions, notifications | Dumebi Onyebuchi | team backend |
| Audit: SHA-256 hash chain, verification, audit API | Anela Quiroz | team backend |
| Sensor health, device ingestion, simulator, forecaster, frontend, deployment | Joshua Dominick | `platform/`, `frontend/`, `render.yaml` |
| Auth and users, equipment, locations, profiles | team (as already built) | team backend |

## System

```
 SHT31 devices / simulator                    Browser (React, frontend/)
   │ signed JSON every 10-15 s                   │ REST + STOMP/WebSocket, one JWT
   ▼                                             ▼
 ┌────────────────────────────┐   ┌──────────────────────────────────────────┐
 │ corrosion-watch-platform   │   │ corrosion-watch-api (team backend)        │
 │  ingestion  (HMAC, validate)│   │  auth, users, equipment, profiles         │
 │  sensor health (7 rules)    │   │  analytics  → risk_assessments            │
 │  simulator (10 scenarios)   │   │  alerts     ← alert_signals               │
 │  forecast API (ML model)    │   │  audit      ← audit_outbox → audit_log    │
 └──────────────┬─────────────┘   └────────────────────┬─────────────────────┘
                └──────────── one MySQL 8 database ────┘
     platform writes: sensors, readings, ingest_rejections, sensor_health, sensor_health_events,
                      alert_signals, audit_outbox, service_heartbeats
     team backend reads them; it never needs to call the platform, and vice versa
```

The services never call each other. They meet only in the shared tables listed in `CONTRACTS.md`, which is what
the proposal describes ("services share data only through the database").

## Two ways to run the platform

**A. Separate service (default, in `render.yaml`).** `platform/ecp-platform-app` runs on its own and shares the
database and the JWT secret. Nothing in the team backend changes except CORS. Costs one more Starter instance.

**B. Embedded.** Add the platform modules to the team backend's build (or copy the packages in) and drop the
`corrosion-watch-platform` service. Cheaper, but it touches the team's build. Do this only if the team agrees.

The frontend supports both: `VITE_PLATFORM_URL` defaults to `VITE_API_URL`.

## Platform modules (`platform/`)

| Module | What it is | Spring? |
|---|---|---|
| `ecp-common` | Magnus dew point (±0.5 °C target), HMAC signing, alert-signal and audit-event publishers, job heartbeats | JDBC only |
| `ecp-simulator` | Simulated SHT31 sensors, 10 scenarios, scenario speed (demo 1.0, realistic 1/6), standalone CLI | no |
| `ecp-forecast` | Ridge (Huber) margin forecaster + logistic early warning, trainer, bundled model | no |
| `ecp-ingestion` | `POST /api/ingest/readings`: signature, timestamp, validation, batch insert, retention | yes |
| `ecp-health` | Health rules, episodes with hysteresis, service watchdog, `/api/health/**` | yes |
| `ecp-platform-app` | Runnable service: security (JWT validation), CORS, WebSocket, device registry, demo devices, embedded simulator, forecast API, Flyway (own history table) | yes |

Pure-Java domain code (rules, simulator, forecaster, maths) has no framework dependency, so it is easy to test and
easy to move.

## Frontend (`frontend/`)

React 18 + TypeScript + Vite, no UI library: `src/styles/design-system.css` implements `docs/ui-design.html`.
`src/api/endpoints.ts` is the only file that knows URLs; `src/api/types.ts` is the contract the pages use.
`npm run dev:mock` runs everything on an in-browser simulation (`src/mock/`), so pages can be built and demoed
before each endpoint exists.

## Key decisions
- **Trust before analysis.** Analytics skips readings while `sensor_health.trusted = false`, so a stuck sensor can
  neither hide a real problem nor raise a false one.
- **Signals, not calls.** Producers write `alert_signals` (RAISED/CLEARED with a dedup key); the alert service
  owns alert state. Producers write `audit_outbox`; only the audit service writes the hash chain.
- **Forecasts that explain themselves.** Linear models with named features, evaluated against baselines;
  see `ML_FORECAST.md`.
