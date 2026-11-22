# Model card: condensation-margin forecaster

| | |
|---|---|
| Model | `ridge-2026-11-03-s240` (bundled: `platform/ecp-forecast/src/main/resources/models/margin-forecast.json`, 5 KB) |
| Type | Per horizon, a Huber-loss ridge regression of the *change* in condensation margin (+15, +30, +60 min); per warning horizon, an L2 logistic regression for "margin reaches 1 °C within 30 / 60 min" with an F1-tuned threshold. 15 features from the last 30 minutes (`Features`). With under 31 minutes of history it falls back to a linear trend (`method` `TREND`). |
| Owner | Joshua Dominick (platform). Used by the platform's forecast API and the `sensor_forecasts` publisher; read by analytics (Aayushee). |
| Version history | Trained 2026-11-03 on simulated data. Evaluated on real data 2026-11-22; kept unchanged because no retrained candidate met the acceptance rule (`docs/ML_FORECAST.md`). |

## Intended use
- Give operators an **earlier hint** that a monitored equipment surface is approaching its dew point, 15–60 minutes
  ahead, **alongside** the threshold rule (margin ≤ 3 °C warning, ≤ 1 °C critical), never instead of it.
- Inputs: one-minute averages of air temperature, relative humidity and surface temperature (measured, or air minus the
  equipment profile's surface offset) from a sensor that sensor health marks as trusted.
- Not for: safety-critical shutdown decisions, sensors other than room-air temperature/humidity probes, or any use
  where a missed warning is unacceptable.

## Training data
240 simulated 8-hour runs from the project simulator (`SimulatedHistory`, seeds 1000–1239): random room temperature
and humidity, surface offsets 0.8–3.2 °C, humid nights and cold-surface episodes at a realistic speed. No real data.

## Evaluation
| Test set | MAE +15 / +30 / +60 min | Warning F1 30 / 60 | False alarms per day |
|---|---|---|---|
| 60 unseen simulated runs | 0.331 / 0.638 / 1.335 °C ("no change": 0.452 / 0.796 / 1.528) | 0.64 / 0.56 | 5.6 |
| Real rooms, UCI Appliances Energy (9 locations, leave-one-out, offset 1.5 °C) | 0.157 / 0.277 / 0.931 °C ("no change": 0.131 / 0.246 / 0.441) | 0.15 / 0.06 | 0.26 |
| Real rooms, chronological (last 30 % of Jan–May 2016) | 0.231 / 0.403 / 1.109 °C ("no change": 0.190 / 0.362 / 0.659) | 0.04 / 0.05 | 0.20 |

The real-data +15-minute target is interpolated from 10-minute readings. Warning numbers on real data rest on few
events (54 crossings, nearly all in a bathroom and an outdoor sensor). Full tables: `docs/ml-training-report.md`.

## Known limitations
- **Does not transfer to real rooms**: on the UCI house it is worse than predicting "no change" at every horizon.
  A model retrained on simulated + real data (GBT) was better on both real and simulated data but raised more false
  alarms on simulated sensors and its file was 1.8 MB, so it was not adopted (see `docs/ML_FORECAST.md`).
- Simulated training data; real evaluation data is room air in one Belgian house in one season, at 10-minute
  resolution, with an assumed surface offset. No real equipment condensation has ever been observed by this model.
- Sudden failures (a dehumidifier stopping) cannot be predicted from history; recall below 100 % is expected.
- Time-of-day features use the timestamps' hour, so a site in another time zone or climate may behave differently.

## Ethical and operational considerations
A false sense of safety is the main risk: a missing forecast warning must not be read as "no risk". The UI and
analytics keep the threshold rule as the primary signal. The model makes no decisions about people and uses no
personal data.

## Performance
Median inference about 0.001 ms per forecast (features plus all models, single thread; the GBT candidates took about
0.08 ms). Model file 5,293 bytes.
