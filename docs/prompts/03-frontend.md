Work in frontend/. Read frontend/README.md and docs/GAPS.md.

1. `npm install`, commit package-lock.json, then make `npm run lint`, `npm test` and `npm run build` pass
   (type errors are expected: the code was bundled and exercised in a browser but never type-checked).
   Also keep `npm run build:mock` passing.
2. Connect to the real services: for each function in src/api/endpoints.ts, call the real endpoint from
   REPO_MAP.md. Where a response differs from src/api/types.ts, convert it in endpoints.ts; do not change the pages.
   Where an endpoint does not exist yet, leave the call in place, make sure the page shows its error state cleanly,
   and confirm the item is in docs/GAPS.md.
3. Show me the diff for the only backend change needed (CORS for FRONTEND_HOST and, if the backend has WebSockets,
   allowed origins), adapted to its real config classes. Apply only after I approve.
4. Run backend (8080) and platform (8081) locally with `npm run dev` and click through every page as each role.
   List what works and what is blocked by gaps.
5. Add Vitest + Testing Library tests for DataTable (sorting, paging), DateRangePicker (range selection) and
   Dropdown (keyboard). Commit on feature/frontend and stop.
