# Contributing

## Branch model

```
main  <- weekly release from dev (stable, demo-ready)
 └── dev  <- all finished work is integrated here first
      └── feature/<name>-<topic>  <- one branch per task
```

- **`main`** holds the most trusted code. It only changes through the weekly release PR from `dev`. Every release is tagged.
- **`dev`** is the shared integration branch and the repo's default branch. It should always build.
- **Task branches** are short-lived, branch off `dev`, and merge back into `dev`.
- **Nobody commits directly to `main` or `dev`.** (GitHub does not enforce this on our plan, so we enforce it ourselves.)

Branch names: `feature/<name>-<topic>`, `fix/<name>-<topic>`, `docs/<name>-<topic>`.
Example: `feature/sam-dewpoint-calculator`

## Daily workflow

```bash
git checkout dev
git pull
git checkout -b feature/<name>-<topic>
# ...work and commit...
git push -u origin feature/<name>-<topic>
```

To pick up teammates' work while your branch is open:

```bash
git fetch
git merge origin/dev
```

## Pull requests into dev

1. Open a PR with **base: `dev`** (check the dropdown) and fill in the template.
2. Link the issue it closes (`Closes #12`).
3. At least **one teammate** must review and approve. Do not merge your own PR without one.
4. The CI check must be **green** before merging.
5. Choose **Squash and merge**, then delete the branch.

Review within 24 hours so nobody is blocked. Review for correctness, tests, and security, not style preferences.

## Weekly release: dev into main

Every Friday (or the day the team agrees):

1. Confirm the latest commit on `dev` has a green check and the app runs from a clean `docker compose up -d`.
2. Open a PR with **base: `main`**, **compare: `dev`**, titled "Week N release".
3. Another teammate (not the person who opened it) reviews and approves.
4. Merge using **Create a merge commit**. Do **not** squash or rebase, or `main` and `dev` will drift apart.
5. Tag the release:
   ```bash
   git checkout main
   git pull
   git tag -a week-N -m "Week N release"
   git push origin week-N
   ```
6. Never delete `dev`.

If `dev` is red, fixing it comes first and the release waits.

If `main` and `dev` have drifted (for example after an accidental squash):

```bash
git checkout dev
git pull
git merge origin/main
git push
```

## Emergency fix to main

Only when `main` is broken before a demo:

1. Branch off `main`: `fix/<name>-<topic>`.
2. Open a PR into `main`, get one approval and a green check, merge.
3. **Immediately** merge `main` back into `dev` so the fix is not lost and the branches stay aligned.

## Commits

Short, present-tense messages that say what changed:
- `Add dew point calculator and unit tests`
- `Reject replayed nonces in ingestion API`

## Rules

- **No secrets in the repo**: passwords, sensor keys, tokens, `.env`. If one is committed by mistake, tell the team immediately and change it. Deleting it in a later commit is not enough.
- Agree API changes in a PR to `docs/1-api-contract.md` **before** changing code that depends on them.
- The backend creates the schema with Flyway on startup (`backend/src/main/resources/db/migration`). Schema changes are a new `V<n>__description.sql` file in a PR and are announced to the team.
- Every new feature needs at least one unit test.
- Label simulated data and results as simulated in code, charts, and slides.
- Use Java 17 locally to match CI.

## Definition of done

- Merged into `dev` through a reviewed PR with green CI
- Tests written and passing
- Documentation or API contract updated if behavior changed
- Works from a clean `docker compose up`

## Key dates

- Thin end-to-end version working on `dev`: early November
- Weekly releases to `main`: every Friday from early November
- Feature freeze: Dec 2 (only bug fixes after this)
- Final release to `main`: by Dec 9, tagged `final`
- Presentation: Dec 13 (demo from `main` only)
