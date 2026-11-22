# Condensation forecaster (`platform/ecp-forecast`)

Goal from the proposal: forecast condensation risk 15 to 60 minutes ahead so operators act before water forms.
Owner of the analytics that uses it: Aayushee. This module is a working, evaluated starting point she can extend.

## What it predicts
1. **Margin forecast.** Surface temperature minus dew point at +15, +30 and +60 minutes. One regression model per
   horizon predicts the *change* in margin from now.
2. **Early warning.** The probability that the margin falls to the critical level (1 °C) within 30 and within
   60 minutes. One logistic classifier per horizon, with a threshold tuned on validation data.

## Features (last 30 minutes, one-minute averages)
Margin now; margin slope over 2, 5, 15 and 30 minutes; humidity now and its 15-minute slope; temperature slope;
margin variability; time of day (sin/cos); and three saturation-aware terms (slope × remaining humidity headroom,
slope × margin, margin floored at 3 °C). With less than 31 minutes of history it falls back to a linear trend.

## Training and evaluation (`Trainer`)
- Data: 240 simulated 8-hour rooms with random temperatures, humidity, surface offsets and episodes (humid nights,
  cold surfaces) unfolding at **realistic speed** (6× slower than the live demo). 60 unseen rooms for testing.
- Regression: ridge with a Huber loss (robust to sudden onsets), λ chosen on a validation split.
- Classifier: L2 logistic regression by Newton's method, positives weighted 3×, F1-optimal threshold.
- Baselines: "no change" (persistence) and the 30-minute linear trend (what the UI showed before the model).

## Results on unseen runs (from `ml-training-report.md`)

| | 15 min | 30 min | 60 min |
|---|---|---|---|
| Error while conditions are changing, model | 0.40 °C | 0.74 °C | 1.00 °C |
| Same, "no change" baseline | 0.95 °C | 1.55 °C | 2.07 °C |

| Early warning | Precision | Recall | F1 |
|---|---|---|---|
| Within 30 min | 0.86 | 0.67 | 0.75 |
| Within 60 min | 0.86 | 0.52 | 0.65 |

| Warning lead time before the margin hits 1 °C | Median | False-alarm episodes per day |
|---|---|---|
| Model | **35 min** | 4.2 |
| Rule "margin ≤ 3 °C" | 17 min | 4.6 |

**Honest limits.** Trained and tested on the project simulator, so these numbers show the method works, not
real-world accuracy. Sudden failures (a dehumidifier dying) cannot be predicted from sensor history; recall
below 100% is expected. Use the model *together with* the threshold rule, never instead of it.

## Check on real data
The UCI "Appliances Energy Prediction" dataset (doi:10.24432/C5VC8G) has real room temperature and humidity every
10 minutes for 4.5 months. Download `energydata_complete.csv`, then `scripts/train-model.sh energydata_complete.csv`
adds a per-room comparison against the "no change" baseline to the report (surface assumed 2 °C below air).

## Using it from analytics
```java
MarginForecaster model = ModelIO.loadBundled();               // once, at startup
Forecast f = model.forecast(lastFortyFiveMinutesPerMinute);   // List<MarginPoint>, oldest first
// store f.margin15C/30C/60C and f.predictedCondensationAt in risk_assessments;
// when f.warn30() || f.warn60(), insert alert_signals CONDENSATION_FORECAST (WARNING), clear when it stops
```
Or read the shared table `sensor_forecasts` (simplest; see CONTRACTS.md): the platform writes the latest forecast of
every trusted sensor there every minute, with `method` `ML` or `TREND`. Or call the platform's
`GET /api/v1/sensors/{id}/forecast`. Retrain with `scripts/train-model.sh`; the report and
model file are regenerated in `ecp-forecast/src/main/resources/models/`.

## Real-data evaluation protocol
Written and committed **before** any real-data experiment was run (git history shows this section landing first).
Every number in "Real-data evaluation" below follows it; any deviation is listed there with the reason.

**Data.** UCI "Appliances Energy Prediction" (Candanedo, Feldheim & Deramaix 2017, UCI ML Repository,
doi:10.24432/C5VC8G, CC BY 4.0), file `energydata_complete.csv`, kept in `data/` and never committed. Columns
`T1..T9` (°C) and `RH_1..RH_9` (%) from wireless sensors in one house in Belgium, a reading every 10 minutes,
January to May 2016. Location 6 (`T6`/`RH_6`) is **outside** the building (north side), not a room; it stays in
the nine folds as asked, is marked in every table, and summaries are also given over the eight indoor rooms.
Timestamps are used as written (local time).

**Cleaning (drop, never fill; every count reported).** Per location:
1. rows whose timestamp or that location's T/RH does not parse: dropped;
2. duplicate timestamps: later copies dropped;
3. physically impossible values: T outside −40..60 °C or RH outside (0, 100] %: dropped;
4. gaps: the series is cut into segments wherever two consecutive readings are not exactly 10 minutes apart.
   Nothing is interpolated across a cut; no feature or target spans one. Segments too short to give 30 minutes of
   history and 60 minutes of future produce no examples (counted).

**Time resolution and horizons.** The model's features are defined on one-minute points. Inside a segment the
10-minute readings are linearly interpolated to one-minute points *only to compute features*, and forecasts are
made **only at real reading times**, so every point a feature uses is built from readings at or before the forecast
time (a unit test proves it). Horizons are the production ones: **+30 and +60 minutes are real readings; +15 minutes
is interpolated** halfway between the real readings at +10 and +20 and is flagged as such in every table.

**Surface temperature.** The house has no surface sensors, so surface = air − offset. Main run: 1.5 °C (the
equipment-profile default `surface_offset_c`). Sensitivity runs: 0.5 °C and 3.0 °C. Margin = surface − dew point
(Magnus, same code as production).

**Features.** Exactly the production feature set (`Features.at`, 15 features from the last 30 minutes), computed
only from past points.

**Risk label.** For the early warning at horizon h: margin above the critical level (1.0 °C) now and at or below
1.0 °C at any real reading in (t, t + h]. Points already at or below 1.0 °C are not scored.

**Splits.**
- *Leave-one-location-out (LOLO)*: nine folds; train on all segments of eight locations, test on the ninth.
- *Chronological*: the first 70% of the time span (all locations) trains, the last 30% tests. Training examples whose
  60-minute target window reaches past the cut are removed (purge), so no label overlaps the test period.
- *Validation* (for every hyperparameter, early-stopping round and warning threshold): the last 20% of the training
  period of the training locations, purged the same way. Test locations and test periods are never used for any
  choice. Final models are refitted on the whole training part with the chosen settings.
- *Simulated test set*: the 60 unseen simulator runs of the production report (seeds 900000+), never trained on.

**Models compared.**
1. Production model as bundled (trained on simulated data only), unchanged: does it transfer?
2. Same model type (Huber ridge + logistic warning) retrained on real data, LOLO and chronological.
3. Same model type trained on the production simulated runs plus the real training data (rows simply pooled).
4. Gradient-boosted regression trees (GBT) on the same features: Tribuo/XGBoost if it runs on Apple Silicon and in
   the linux/amd64 Docker image, otherwise a small tested plain-Java learner in `ecp-forecast` (Huber loss,
   shrinkage, max depth, min leaf size, early stopping on validation). Grid: depth {3, 5} × min leaf {20, 100},
   learning rate 0.05, up to 500 trees, stop after 30 rounds without validation improvement. Warning classifiers
   use the same learner with logistic loss. Trained on real data, and on real + simulated.
5. Surface-offset sensitivity: models 1, 2 and 4 and the baselines at 0.5 / 1.5 / 3.0 °C (LOLO).

**Baselines.** Persistence ("no change"); linear trend (least-squares slope of the last 30 minutes, extrapolated);
for warnings, the plain rule "1 °C < margin ≤ 3 °C" (what analytics does without ML).

**Metrics.** MAE and RMSE of the predicted margin per horizon, over all test points and over "moving" points
(|30-minute trend| > 0.02 °C/min, the definition used for the simulated report). Early warning at 30 and 60
minutes: precision, recall, F1. Replay per held-out location at each real reading, warning = 60-minute classifier
or 60-minute forecast ≤ 1 °C (as in production), a single non-warning reading between two warnings is bridged:
false-alarm episodes per day (an episode with no crossing of 1 °C within 60 minutes of its start) and median lead
time before each crossing (a crossing counts after the margin has been back at ≥ 3 °C; lead capped at 60 minutes,
in 10-minute steps). LOLO results are given per location and pooled over all held-out points.
Randomness: none in the learners; simulated data uses the fixed production seeds. Re-running gives identical numbers.

**Acceptance rule for replacing the bundled model** (main offset 1.5 °C; "no worse" allows the reporting precision:
0.005 °C of MAE, 0.005 of F1, 0.05 false alarms per day). The candidate is the recipe trained on all real locations
(plus simulated data if the recipe uses it). It replaces the production model only if **all** hold:
- (a) its MAE is lower than the production model's at **every** horizon, pooled over the LOLO held-out locations
  **and** on the chronological test period;
- (b) on the simulated test set its MAE is no worse at every horizon (the demo runs on simulated sensors);
- (c) its early-warning F1 (30 and 60 min) is no worse and its false alarms per day are no higher, on real LOLO and
  on the simulated test set;
- (d) median inference under 5 ms per sensor forecast (feature computation included, single thread, after warm-up)
  and a model file under 1 MB.
If it passes, it is bundled with a version and training metadata, ridge stays bundled as the fallback, and the API
and `sensor_forecasts.method` say which model made each forecast (`ML_GBT`, `ML_RIDGE`, `TREND`). If not, the
production model stays and this document says so.
