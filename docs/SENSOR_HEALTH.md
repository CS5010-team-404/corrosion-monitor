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

## Real-data check: Intel Berkeley Research Lab

**Question.** Do the rules catch real sensor faults, not only the simulator's? There are no official fault labels for
real sensor data, so this is a check against a stated, heuristic reference, not a measurement of true accuracy.

**Data.** Intel Berkeley Research Lab sensor data (P. Bodik, W. Hong, C. Guestrin, S. Madden, M. Paskin, R. Thibaux,
2004; http://db.csail.mit.edu/labdata/labdata.html): 54 Mica2Dot motes in one office lab, temperature, humidity,
light and battery voltage every 31-second epoch, 28 Feb to 3 Apr 2004 (after cleaning). Known faults: as batteries die, motes report
temperatures above 100 °C (122.153 °C again and again) and negative humidity; the radio loses many messages. The page
states no licence; the files are used for evaluation only and are **not committed** (`data/intel/`, git-ignored).
`data.txt.gz` SHA-256 `d99288c8…c7225`, `mote_locs.txt` SHA-256 `3865c026…6bf04` (full hashes in the results file).

**Method** (`edu.neu.ecp.health.realdata`, `scripts/evaluate-health-intel-lab.sh`, about 90 s):
- Loading drops and counts lines without all eight fields (93,879), mote ids not in `mote_locs.txt` (9,719) and
  repeated (mote, epoch) pairs (107,958, first kept); 2,102,126 readings from 52 motes remain. Values are not cleaned:
  faulty values are exactly what the rules must see.
- Each mote is replayed through the production `HealthEvaluator` and `EpisodeTracker` with the production settings:
  an evaluation every 5 s, a 15-minute window, 31 s expected interval, 3 clean evaluations to close an issue, from the
  mote's first reading to the end of the data. The epoch is the sequence number; readings arrive when measured.
  CLOCK_SKEW and SIGNATURE_FAILURES cannot fire (no receive times, no signatures).
- **Reference (heuristic, stated before scoring, independent of the rules' thresholds):** *implausible* = a reading in
  the window outside 0–50 °C or 0–100 % RH (an indoor office); *low battery* = newest reading below 2.3 V; *flat* =
  newest reading is part of an exactly repeated run lasting at least an hour; *faulty* = any of the three;
  *silent* = no reading for more than the offline threshold (the OFFLINE rule's own definition, so it only checks the
  replay). Scored per evaluation tick (5 s of one mote): precision and recall of each rule against each reference.
- Comparison detector (B3): a robust z-score (reading more than 6 robust SDs from the median of the mote's previous
  60 readings), same reference.

**Results, production defaults** (full tables and examples: `docs/results/intel-lab-results.md`):

| Rule | Motes | Episodes | Median episode | Precision / recall vs *faulty* |
|---|---|---|---|---|
| OUT_OF_RANGE | 50 | 2,800 | 19 min | 1.00 / 0.68 |
| STUCK | 41 | 4,270 | 1 min | 1.00 / 0.07 |
| ERRATIC | 10 | 26 | 14 min | 1.00 / 0.00 |
| Value rules together (STUCK, OUT_OF_RANGE, ERRATIC) | | | | **1.00 / 0.68** |
| OFFLINE | 52 | 66,102 | 1 min | (silence, not bad values) |
| LOSSY | 52 | 46,402 | 5 min | (loss, not bad values) |
| Robust z-score (comparison) | | | | 0.19 / 0.02 |

**What it shows.**
- When a value rule fires, the data really is bad: precision 1.00 against *implausible* and *faulty*, 0.99 against
  *low battery*, 0.89 against *flat*. The rules are safe to act on.
- Recall is the weak point, for three data-specific reasons:
  1. The dying-battery value 122.153 °C is *inside* the SHT31 range (−40 to 125 °C), so OUT_OF_RANGE only catches it
     through the negative humidity that often comes with it. With the room's plausible range instead
     (`ecp.health.temp-min-c=0`, `temp-max-c=60`), recall against *faulty* rises to **0.90** at precision 1.00
     (variant *indoor-range*).
  2. STUCK needs 20 identical readings inside the 15-minute window. At 31 s a window holds at most 29 readings and,
     with this radio's 35 % loss, usually fewer than 20, so frozen motes are rarely called stuck (recall 0.07).
     `stuck-min-readings=10` raises it to 0.30 but makes STUCK flap (10,334 short episodes).
  3. ERRATIC is designed for jumps between healthy readings; real failures here are long plateaus, not jumps.
- OFFLINE and LOSSY are right by definition (35.2 % of epochs are missing), but on a lossy radio at 31 s, OFFLINE
  flaps: 66,102 episodes, median 1 minute. An offline factor of 20 (about 10 minutes) cuts that to 6,012.
- The robust z-score catches only the moment a value jumps, not the long faulty plateau that follows, so it adds little.

**Changes.** None to the defaults. They are tuned for this project's SHT31 sensors on Wi-Fi every 15 s, and this lab is a
different deployment (slow, lossy radio; failure mode at 122 °C). Every threshold involved is already configurable
under `ecp.health` (no code change was needed). Proposed, for the team to decide:
- deployments should set `temp-min-c`/`temp-max-c` to the *plausible* range of the room or enclosure, not the sensor's
  physical range; this is the one change the data clearly supports (recall 0.68 → 0.90, precision unchanged);
- for slow or lossy links, raise `offline-factor` and judge STUCK over a time span rather than a reading count (a code
  change, not made here; listed under ideas).

**Limits.** One building, one network, 2004 hardware. The reference is heuristic: a frozen value can be a real steady
room, a 2.3 V reading can still be right, and "implausible" uses an office's range. Mote-time after a mote died counts
as silent, which inflates OFFLINE hours. Supply voltage is not something our devices report today.

## Ideas for the remaining weeks

- Compare two sensors on the same equipment and flag one that drifts away from the other.
- For the Raspberry Pi: report supply voltage and flag brown-outs.
- Availability per sensor for the reports page (percentage of the period online and trusted).
- Per-model physical limits instead of one global SHT31 range, and per-deployment plausible ranges (see the real-data check).
- Judge STUCK over a time span (for example 10 minutes without any change) instead of a reading count, so slow or lossy
  sensors are covered too.
