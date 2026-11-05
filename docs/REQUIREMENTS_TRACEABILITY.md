# Proposal requirements → where they are met

| Requirement (proposal) | Component | Owner | Status in this kit |
|---|---|---|---|
| Read sensors: multiple sensors, adjustable intervals | platform ingestion, device registry (`expected_interval_s`), simulator | Joshua | Built, tested |
| Signed JSON every 10-15 s, verify, validate, store | `ecp-ingestion`, `Hmac` | Joshua | Built, signature verified across Java/bash/Python |
| Sensor health (silent, stuck, impossible, erratic, lossy, bad signatures, clock skew) | `ecp-health` | Joshua | Built, 23 tests |
| Forecast condensation 15-60 min ahead | `ecp-forecast` + forecast API; analytics stores it | Joshua / Aayushee | Model trained and evaluated; analytics wiring open |
| Risk index, time of wetness | analytics | Aayushee | Open (UI ready) |
| Alerts: warning and critical, escalation | alerts, consuming `alert_signals` | Dumebi | Signals produced; lifecycle open (UI ready) |
| Respond: acknowledge, corrective actions | alerts API | Dumebi | Open (UI ready) |
| Control access: four roles | team auth + platform JWT validation | team / Joshua | Platform done; backend roles to confirm |
| Tamper-evident audit log | audit chain from `audit_outbox` | Anela | Events produced; chain + verify open (UI ready) |
| Export reports, CSV or PDF (OpenPDF) | reports API | Aayushee + Dumebi | Open (UI ready, CSV works in mock) |
| Configure profiles and devices | device registry (platform), profiles (backend) | Joshua / team | Devices done |
| 500 readings/s ingest | batch insert, 500 per request, rewriteBatchedStatements | Joshua | To measure (load test idea in DEMO.md) |
| Alert within 5 s | 5 s health evaluation, signal polling | Joshua / Dumebi | Health side done |
| Dew point ±0.5 °C | `Psychrometrics` (Magnus, Alduchov-Eskridge constants) | Joshua | Tested |
| HTTPS, BCrypt, account lockout | Render TLS; backend auth | team | Lockout UI ready |
| JUnit 5, 70% coverage | JaCoCo in `platform/pom.xml` | all | Platform tests: 43 passing |
| Simulator + ML; optional Raspberry Pi + SHT31 | `ecp-simulator`, `ecp-forecast`, `devices/raspberry-pi` | Joshua | Built |
| Out of scope: measuring corrosion, HVAC control, gas sensing | n/a | n/a | Not built, by design |
