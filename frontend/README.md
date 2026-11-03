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

Deployed by `../render.yaml` as a static site; `VITE_API_URL` and `VITE_PLATFORM_URL` come from the two services.
