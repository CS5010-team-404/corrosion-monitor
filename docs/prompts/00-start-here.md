# Working with Claude Code on Corrosion Watch

1. Copy the kit into the repo root on a branch (`git checkout -b feature/platform-kit`), commit.
2. In a terminal at the repo root run `claude`. Press Shift+Tab until the mode shows plan mode, so Claude Code
   proposes a plan before editing.
3. Paste the prompts in order. Each one ends with a stop; read the summary, then paste the next.

| Prompt | Who | What happens |
|---|---|---|
| `01-discovery.md` | Joshua | Read-only map of the backend, API inventory, gaps by owner |
| `02-platform.md` | Joshua | Platform compiles, tests pass, runs locally, JWT aligned with the backend |
| `03-frontend.md` | Joshua | Frontend builds and talks to the real API; mock fills the gaps |
| `04-deploy.md` | Joshua | Render Blueprint reconciled, deployed, smoke-tested |
| `05-demo.md` | Joshua | Issues per owner, polish, demo script rehearsal |
| `teammate-analytics.md` | Aayushee | Analytics endpoints the UI uses, forecaster wired in |
| `teammate-alerts.md` | Dumebi | Alert lifecycle from `alert_signals`, escalation, actions |
| `teammate-audit.md` | Anela | Hash chain from `audit_outbox`, verify endpoint |

Tip: start each new session with "Read CLAUDE.md and docs/GAPS.md, then continue with <prompt>."
