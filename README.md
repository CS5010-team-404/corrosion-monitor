# Electronics Corrosion Prevention

Monitors temperature and humidity around electronic equipment, calculates dew point and a corrosion risk index, forecasts condensation, and alerts operators before damage occurs.

**Course:** CS5010
**Presentation:** December 11
**Team:** see table below

## Architecture

Sensor simulator (or optional Raspberry Pi + SHT31) → Ingestion API (Spring Boot) → MySQL → Analytics, Sensor health, Alert service, Audit service → REST API and web dashboard → Operator, Technician, Manager, Admin.

Details: [docs/](docs/)

## Repository layout

| Folder | Contents |
|---|---|
| `backend/` | Spring Boot application (API, analytics, alerts, audit, security) |
| `simulator/` | Java sensor simulator and scenario files |
| `db/` | MySQL schema and seed data |
| `docs/` | API contract, design notes, NIST mapping, evaluation results |
| `.github/` | CI workflow, PR and issue templates |

## Quick start

Requirements: Java 17+, Maven, Docker.

```bash
cp .env.example .env          # then edit the passwords
docker compose up -d          # starts MySQL and MailHog (test email inbox at http://localhost:8025)
```

Backend and simulator run instructions will be added here as they are built.

## Team

| Members | Role | GitHub |
|---|---|---|
| (Aayushee Bhat) | TBD | @aayushee-bhat |
| (Anela Quiroz) | TBD | @anelajoy |
| (Dumebi Onyebuchi) | TBD| @dumebi2003 |
| (Joshua Dominick) | TBD | bartazarjoshua@gmail.com |

## NIST alignment

This project is designed *in alignment with* selected NIST SP 800-53 controls and the NIST CPS Framework. It is not certified or compliant. See `docs/` for the control mapping.

## Notes

- Results from the simulator are labeled as simulated everywhere they appear.
- Never commit passwords, sensor secrets, or `.env` files.
