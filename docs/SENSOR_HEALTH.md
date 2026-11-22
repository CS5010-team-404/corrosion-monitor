# Sensor health service

Owner: Joshua. Code: `ecp-health`. Tests: `ecp-health/src/test` and `ecp-platform-app/src/test/.../SimulatorToHealthTest`.

## Why it exists

Analytics is only as good as its readings. A sensor that stops reporting, freezes on one value, or reports impossible numbers can hide a real condensation event or raise a false one. This service decides, every 5 seconds, whether each sensor can be trusted, and tells everyone else.

## How it works

1. `SensorHealthJob` runs every 5 seconds (`ecp.health.evaluate-every`).
2. `HealthRepository` loads, in four queries for all sensors at once: active sensors, readings received in the last 15 minutes, rejected signatures, and open issue episodes.
3. `HealthEvaluator` (pure Java, no Spring) applies the rules below and returns the issues detected right now, with evidence.
4. `EpisodeTracker` adds hysteresis: an issue opens on first detection and closes after 3 clean evaluations in a row (OFFLINE closes as soon as data arrives).
5. `HealthAssessment` derives the status, score and trusted flag.
6. Changes are saved, signalled to the alert service, audited, and pushed to the dashboard.

## Rules

| Issue | Detected when (defaults) | Severity | Analytics keeps using the data? |
|---|---|---|---|
| Offline | No reading for more than 6 reporting intervals (90 s at 15 s), or a new sensor never reported | Critical | No |
| Stuck | The last 20 readings are identical | Critical | No |
| Impossible values | 2 or more readings outside the sensor's range (SHT31: −40 to 125 °C, 0 to 100 % RH) | Warning | No |
| Erratic | 3 or more jumps above 5 °C or 20 % RH between consecutive readings | Warning | No |
| Losing readings | 25 % or more of sequence numbers missing (restarts handled) | Warning | Yes |
| Bad signatures | 3 or more rejected signatures in 15 minutes | Warning | Yes |
| Clock off | Median device-to-server clock difference above 2 minutes | Warning | Yes |

A sensor is **late** (shown as Degraded, no alert) after 2 missed intervals.

**Startup grace.** For 3 minutes after the platform starts (`ecp.health.startup-grace`, env
`ECP_HEALTH_STARTUP_GRACE`, `PT0S` turns it off), a sensor that would be Offline is shown as late instead, because the
silence may only be the platform's own downtime (the embedded simulator restarts with it, and devices retry). No new
Offline issue opens and no alert is raised during the grace, and an Offline episode that was already open before the
restart is kept as it is: it is neither cleared nor raised again. Every other rule applies as usual. Once the grace is
over, a sensor that is still silent goes Offline at once.

**Reporting interval.** Each sensor's interval (`sensors.expected_interval_s`, 5-3600 s) is set when it is
registered and can be changed by an admin in Administration › Sensors (`PATCH /api/v1/admin/sensors/{id}`). Late and
Offline are judged against the new value from the next evaluation; the embedded simulator switches to it at once.

Status, worst first: Offline, Stuck, Faulty (impossible or erratic), Degraded (any other issue, or late), Healthy. A sensor that has never reported is Unknown.

Score: 100 minus a penalty per open issue (Offline 100, Stuck 70, Impossible 50, Erratic 40, Losing 25, Signatures 20, Clock 15, Late 10), never below 0.

Every threshold is configurable under `ecp.health` in `application.yml`.

## What it publishes

- `sensor_health`: one row per sensor (status, connectivity, trusted, score, open issues, window statistics).
- `sensor_health_events`: one row per issue episode (opened, closed, evidence).
- `alert_signals`: RAISED when an issue opens and CLEARED when it closes, key `HEALTH:<SIGNAL_TYPE>:<sensorId>`.
- `audit_outbox`: `SENSOR_ISSUE_OPENED` and `SENSOR_ISSUE_CLOSED`.
- `/topic/health`: the updated sensor view when anything changes.
- The service watchdog raises `SERVICE_DOWN` when another background service has not succeeded for 2 minutes.
- Its `trusted` flag also decides which sensors get a row in `sensor_forecasts` (the forecast publisher skips
  untrusted sensors and removes their rows).

## Ideas for the remaining weeks

- Compare two sensors on the same equipment and flag one that drifts away from the other.
- For the Raspberry Pi: report supply voltage and flag brown-outs.
- Availability per sensor for the reports page (percentage of the period online and trusted).
- Per-model physical limits instead of one global SHT31 range.
