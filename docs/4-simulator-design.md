# Sensor Simulator Design

Goal: produce realistic temperature/humidity streams with **known ground truth**, so you can measure warning lead time, false alarms, and fault detection exactly.

## 1. Physical model (what makes it realistic)

Do not generate temperature and humidity as independent random numbers. Real rooms behave like this: **the amount of water in the air stays roughly constant while temperature changes, so relative humidity rises when the air cools.** That is exactly how overnight condensation happens.

Per tick:

1. **Temperature** = baseline + daily sinusoid + HVAC sawtooth + noise
   - baseline e.g. 22 C, daily swing +/- 2 C
   - HVAC cycling: temp drifts up, compressor kicks in, drifts down (period 10-20 min, amplitude about 1 C)
   - Gaussian noise, sigma about 0.1 C
2. **Moisture** is tracked as vapor pressure `e` (or dew point), which changes slowly:
   - `dewPoint = baseDewPoint + humiditySource(t) + noise`
3. **RH is derived**, not simulated: `RH = 100 * e_actual / e_saturation(T)`, using the Magnus formula (same constants as `DewPointCalculator`, inverted).
4. Clamp RH to 0-100. Add sensor noise (about 0.5% RH) and optional slow drift.

This gives you correct physics for free: a cooling drop raises RH automatically.

## 2. Time control

- `--speed 1` real time, `--speed 60` one simulated minute per second, `--speed 720` a 24 h day in 2 minutes.
- Use a simulated clock for the `ts` field, not `System.currentTimeMillis()`, so replays are deterministic.
- `--seed 42` makes runs repeatable (use `java.util.Random(seed)`). Essential for fair model comparison.

## 3. Scenario library

Each scenario has an injected start time, so ground truth is exact.

| ID | Scenario | What happens | Ground-truth event |
|---|---|---|---|
| S0 | Normal day | Baseline only | No alert should fire (false-alarm test) |
| S1 | Overnight cooling | HVAC setback drops temp 6 C over 2 h, dew point constant | Margin hits 0 at known time |
| S2 | Rainy-day humidity rise | Dew point ramps up 5 C over 4 h | RH crosses critical limit at known time |
| S3 | HVAC failure | Temp climbs, then humidity source added | Warm and humid exposure (risk index test) |
| S4 | Door/vent left open | Step change in dew point, partial recovery after closure | Excursion start and end |
| S5 | Dehumidifier failure | Slow RH creep, 0.5 %/hour | Slow-drift detection |
| S6 | Brief spike (false-alarm bait) | RH jumps 15% for 2 min then returns | Should NOT trigger CRITICAL |
| S7 | Sensor stuck | Last value repeated indefinitely | Fault start time |
| S8 | Sensor dropout | No messages for N minutes | Silent start time |
| S9 | Sensor drift | Slow RH offset grows +0.1%/hour | Compare against twin sensor |
| S10 | Impossible values | RH = 130, temp = -90 | Must be rejected (400) |
| S11 | Network outage | Sensor buffers locally, replays with `buffered: true` and original `seq` | Zero data loss check |
| S12 | Replay/forgery attack | Resend old message, wrong HMAC key | Must be rejected (401) |

## 4. Scenario file format

```json
{
  "name": "overnight-cooling",
  "seed": 42,
  "durationHours": 24,
  "sensors": [
    {"id": "6f1c2a9e-...", "location": "Telecom Cabinet B",
     "intervalSec": 30, "baseTempC": 22, "baseDewPointC": 12}
  ],
  "events": [
    {"atHour": 2.0, "type": "TEMP_RAMP", "deltaC": -6, "overHours": 2.0},
    {"atHour": 9.0, "type": "TEMP_RAMP", "deltaC": 6,  "overHours": 2.0}
  ],
  "groundTruth": [
    {"type": "CONDENSATION_START", "expectedAtHour": 3.4}
  ]
}
```

Event types to support: `TEMP_RAMP`, `DEWPOINT_RAMP`, `DEWPOINT_STEP`, `STUCK`, `DROPOUT`, `DRIFT`, `SPIKE`, `NETWORK_OUTAGE`, `BAD_VALUES`.

The `groundTruth` block lets your evaluation harness automatically compute lead time (ground-truth time minus alert time) for every model.

## 5. Code structure (Java)

```java
interface Scenario { void apply(SimState state, double simHours); }

class SimState {
    double tempC, dewPointC;     // true physical state
    boolean stuck, silent, networkDown;
    double rhSensorBias;         // drift
    double lastTempC, lastRh;    // for STUCK
}

class SensorSimulator {
    Reading next(SimClock clock) {
        scenarios.forEach(s -> s.apply(state, clock.hours()));
        double temp = state.tempC + noise(0.1);
        double rh = clamp(rhFromDewPoint(state.tempC, state.dewPointC) + state.rhSensorBias + noise(0.5), 0, 100);
        if (state.stuck) { temp = state.lastTempC; rh = state.lastRh; }
        return new Reading(sensorId, seq++, clock.now(), temp, rh);
    }
}

class Publisher {
    // Signs with HMAC, POSTs to /api/v1/readings
    // If networkDown: store in local queue; on recovery POST /readings/batch with buffered=true
}
```

`rhFromDewPoint(T, Td) = 100 * exp(B*Td/(C+Td)) / exp(B*T/(C+T))` (same B and C as the Magnus code).

## 6. Using it for evaluation

For each scenario and each model (threshold rule, linear forecast, Holt smoothing):

| Metric | How to compute |
|---|---|
| Lead time | groundTruth time minus first alert time (negative = late, 0 = no warning) |
| False alarms | Alerts on S0 and S6 across 30 simulated days with different seeds |
| Forecast MAE/RMSE | Compare forecast made at time t with the actual value at t + horizon |
| Fault detection rate | Injected faults flagged within N minutes |
| Outage loss | Count of `seq` values in DB vs. sent after S11 |
| Security | S12 messages all rejected |

Run each scenario with 10+ different seeds and report mean and spread, not a single lucky run.

## 7. Optional real-hardware check

One Raspberry Pi with an SHT31 sensor (Pi4J over I2C) sending the same JSON payload to the same endpoint. Run it for a few days next to a second, trusted sensor (or a handheld hygrometer) and report the difference. Label every chart clearly as **simulated** or **real**.

## 8. Pitfalls

- Do not simulate RH independently of temperature (it breaks the condensation story).
- Always use seeds, or your results can't be reproduced.
- Keep simulator output format identical to the real API, so the Pi is a drop-in replacement.
- Cap risk accumulation across outages (see `RiskAccumulator`), or a dropout will look like a huge exposure.
