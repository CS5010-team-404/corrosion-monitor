# Electronics Corrosion Prevention

Monitors temperature and humidity around electronic equipment, calculates dew point and a corrosion risk index, forecasts condensation, and alerts operators before damage occurs.

**Course:** CS5010
**Presentation:** December 11
**Team:** see table below

## Architecture

Sensor simulator (or optional Raspberry Pi + SHT31) → Ingestion API (Spring Boot) → MySQL → Analytics, Sensor health, Alert service, Audit service → REST API and web dashboard → Operator, Technician, Manager, Admin.

Details: [docs/](docs/) | [Project proposal (Google Doc)](https://docs.google.com/document/d/1L7SXx4X53GcaBaeJO9PHYQX_N1n1vKO_3BM5HEUOgcg/edit?usp=sharing)

## Repository layout

| Folder | Contents |
|---|---|
| `backend/` | Spring Boot application (API, analytics, alerts, audit, security) |
| `simulator/` | Java sensor simulator and scenario files |
| `db/` | MySQL schema and seed data |
| `docs/` | API contract, design notes, NIST mapping, evaluation results |
| `.github/` | CI workflow, PR and issue templates |

## Quick start

Requirements: Java 17+ (matches CI), Maven, Docker Desktop, Git.

```bash
git clone https://github.com/CS5010-team-404/corrosion-monitor.git
cd corrosion-monitor
git checkout dev
cp .env.example .env          # then edit the passwords
docker compose up -d          # starts MySQL and MailHog (test email inbox at http://localhost:8025)
mvn -B verify                 # builds both modules and runs tests
```

Run the backend (export the database values from your `.env` first):

```bash
export MYSQL_USER=app_rw
export MYSQL_PASSWORD=<value from your .env>
mvn -pl backend spring-boot:run      # http://localhost:8080
```

Run the sensor simulator (needs the backend running):

```bash
export SENSOR_SECRET=<value from your .env>
mvn -pl simulator exec:java
```

The database schema in `db/schema.sql` loads automatically the first time MySQL starts. To reload it, run `docker compose down -v` and start again.

## Branching

- `main`: stable and demo-ready. Updated only by the weekly release from `dev`.
- `dev`: where finished work is integrated. This is the default branch.
- `feature/<name>-<topic>`: one branch per task, merged into `dev` by pull request.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full workflow.

## Team

| Member | Role | GitHub |
|---|---|---|
| Aayushee Bhat | TBD | @aayushee-bhat |
| Anela Quiroz | TBD | @anelajoy |
| Dumebi Onyebuchi | TBD | @dumebi2003 |
| Joshua Dominick | TBD | @Joshuadommy |

## NIST alignment

This project is designed *in alignment with* selected NIST SP 800-53 controls and the NIST CPS Framework. It is not certified or compliant. See `docs/` for the control mapping.

## Notes

- Results from the simulator are labeled as simulated everywhere they appear.
- Never commit passwords, sensor secrets, or `.env` files.
