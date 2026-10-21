# Contributing

## Branches
- `main` is always working and demo-ready. Nobody pushes to it directly.
- Create a branch per task: `feature/<name>-<topic>`, `fix/<name>-<topic>`, or `docs/<name>-<topic>`.
  Example: `feature/sam-dewpoint-calculator`
- Keep branches short-lived (a few days at most) and rebase or merge `main` into them often.

## Pull requests
1. Open a PR into `main` and fill in the template.
2. Link the issue it closes (`Closes #12`).
3. At least **one teammate** must review and approve.
4. CI must pass (build and tests).
5. Squash and merge, then delete the branch.

Review within 24 hours so nobody is blocked. Review for correctness, tests, and security, not style preferences.

## Commits
Short, present-tense messages that say what changed:
- `Add dew point calculator and unit tests`
- `Reject replayed nonces in ingestion API`

## Rules
- **No secrets in the repo**: passwords, sensor keys, tokens, `.env`. If one is committed by mistake, tell the team immediately and change it. Deleting it in a later commit is not enough.
- Agree API changes in a PR to `docs/1-api-contract.md` **before** changing code that depends on them.
- Schema changes go through `db/schema.sql` in a PR and are announced to the team.
- Every new feature needs at least one unit test.
- Label simulated data and results as simulated in code, charts, and slides.

## Definition of done
- Code merged to `main` through a reviewed PR
- Tests written and passing
- Documentation or API contract updated if behavior changed
- Works from a clean `docker compose up`

## Key dates
- Thin end-to-end version working: early November
- Feature freeze: Dec 2
- Presentation: Dec 13
