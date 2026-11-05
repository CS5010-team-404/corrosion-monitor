# Corrosion Watch web app

React 18 + TypeScript + Vite. No UI library: `src/styles/design-system.css` implements `../docs/ui-design.html`.

```
npm install
npm run dev:mock     # whole app on the in-browser mock backend (sign in as tech, operator, manager or admin)
npm run dev          # against local services: backend :8080, platform :8081 (see .env.example)
npm run lint && npm test && npm run build
```

| Folder | |
|---|---|
| `src/api/` | `types.ts` (the contract pages rely on) and `endpoints.ts` (every URL, one file) |
| `src/lib/` | config, fetch client with JWT, STOMP client, router, auth, toasts, `useAsync` |
| `src/components/` | Dropdown, DateRangePicker, DataTable, Drawer, Modal, Badge, Button, Skeleton, MarginRail, LineChart |
| `src/layout/` | sidebar, top bar (search, notifications, account), phone tab bar |
| `src/pages/` | Login, Overview, Equipment, Alerts, Health, Reports, Audit, Admin |
| `src/mock/` | simulation and mock API; only loaded when `VITE_USE_MOCKS=true` |

Deployed by `../render.yaml` as a static site. `VITE_API_URL` and `VITE_PLATFORM_URL` are the public
`https://...onrender.com` URLs of the two services, entered by hand in Render (`../docs/DEPLOYMENT.md`).

## Build-time flags

`VITE_*` values are built into the bundle: change them, then rebuild (on Render: "Clear build cache & deploy").

| Flag | Default | Effect |
|---|---|---|
| `VITE_BACKEND_LIVE` | `false` | Connect to the team backend's WebSocket (`/topic/risk`, `/topic/alerts`). Turn on once the backend serves `/ws`. The platform WebSocket always connects. |
| `VITE_MOCK_FALLBACK` | `false` | Demo only. Endpoints listed in `PENDING` (`src/api/endpoints.ts`) that answer 404/501 are served by the mock backend instead; pages using that data show "Sample data: this part isn't built yet" and the sidebar lists what is sample. Real endpoints are never replaced. |
| `VITE_USE_MOCKS` | `false` | Whole UI on the mock backend (`npm run dev:mock`). |

## Endpoints that aren't built yet

`PENDING` in `src/api/endpoints.ts` lists each teammate endpoint with its owner. When one returns 404/501 the page
shows a calm "Not built yet" panel naming the endpoint and owner, polling for it stops, and it isn't requested again
until the page is reloaded. Remove an entry once its endpoint ships.

Live updates: any WebSocket that fails reconnects after 3 s, doubling to 60 s, and gives up after 5 failed attempts
in a row until the page is reloaded (pages keep polling).
