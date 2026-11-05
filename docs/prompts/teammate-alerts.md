Read CLAUDE.md, docs/DECISIONS.md and docs/CONTRACTS.md. I am Dumebi and I own alerts. Change only the alerts code.
All paths start with /api/v1. Alerts belong to an equipment item (equipment table, alert_signals.equipment_id).

1. Consume alert_signals in id order (set processed_at): RAISED opens or updates one alert per dedup key, CLEARED
   records conditionClearedAt (auto-resolve only after a quiet period, so flapping does not spam).
2. Lifecycle OPEN → ACKNOWLEDGED → RESOLVED; escalate unacknowledged CRITICAL alerts to the manager after
   5 minutes; corrective actions with type and notes.
3. Endpoints exactly as the frontend calls them (frontend/src/api/endpoints.ts): list with status filter, detail with
   actions, acknowledge, resolve, actions. Roles: TECHNICIAN and ADMIN act; OPERATOR and MANAGER read.
4. Write an audit_outbox event for every state change. Publish a STOMP message on /topic/alerts when anything changes.
5. JUnit 5 tests for the state machine and escalation. Close my items in docs/GAPS.md and open a pull request.
