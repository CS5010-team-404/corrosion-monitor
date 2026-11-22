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
real-world accuracy; on real rooms the bundled model is worse than "no change" (see "Real-data evaluation"). Sudden failures (a dehumidifier dying) cannot be predicted from sensor history; recall
below 100% is expected. Use the model *together with* the threshold rule, never instead of it.

## Check on real data
Done: see "Real-data evaluation" below (protocol written first, then the results). In short, the bundled model does
**not** transfer to real rooms, retrained models do better, and no candidate met the acceptance rule, so the bundled
model is unchanged.

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

## Real-data evaluation
Run: `scripts/evaluate-real-data.sh` (about 30 minutes; deterministic). Generated tables:
[`docs/results/real-data-results.md`](results/real-data-results.md) (every table), `real-data-metrics.csv` (all
numbers, per location), `chosen-hyperparameters.csv` (every validation choice). Full write-up with limits:
[`ml-training-report.md`](ml-training-report.md). Model card: [`MODEL_CARD.md`](MODEL_CARD.md).

**Data and cleaning.** `energydata_complete.csv`, SHA-256 `2820bf71…a25d`, 19,735 rows, 2016-01-11 17:00 to
2016-05-27 18:00. No cleaning rule removed anything: no unparsable rows, duplicate times or impossible values, and every
location is one gap-free 10-minute segment.

**Margin forecast, MAE (°C), leave-one-location-out, all 9 locations pooled, offset 1.5 °C** (\* +15 is interpolated):

| Model | +15\* | +30 | +60 | +60 while moving |
|---|---|---|---|---|
| Bundled model (simulated data only) | 0.157 | 0.277 | 0.931 | 2.790 |
| Persistence ("no change") | 0.131 | 0.246 | 0.441 | 2.829 |
| Linear trend | 0.123 | 0.250 | 0.509 | 3.863 |
| Ridge, real data | 0.112 | 0.234 | 0.459 | 2.500 |
| Ridge, simulated + real | 0.108 | **0.211** | **0.398** | **2.422** |
| GBT, real data | **0.107** | 0.215 | 0.421 | 2.550 |
| GBT, simulated + real | **0.107** | 0.218 | 0.427 | 2.458 |

Chronological split (train 11 Jan to mid-April, test the last 30 %, to 27 May): bundled 0.231 / 0.403 / 1.109, persistence 0.190 / 0.362 /
0.659, best candidate GBT real 0.142 / 0.276 / 0.503. Eight indoor rooms only: bundled 0.099 / 0.167 / 0.795,
persistence 0.082 / 0.154 / 0.271, GBT real 0.063 / 0.124 / 0.235.

**Early warning, LOLO pooled** (523 events at 60 min, 54 crossings of 1 °C, almost all in the bathroom and outdoors):

| | F1 30 | F1 60 | Median lead | False alarms / day |
|---|---|---|---|---|
| Bundled model | 0.15 | 0.06 | 5 min | 0.26 |
| GBT, simulated + real | 0.22 | 0.18 | 10 min | 0.22 |
| Linear trend | **0.28** | **0.30** | 20 min | 0.20 |
| Rule "margin ≤ 3 °C" | 0.14 | 0.21 | 15 min | 0.16 |

**What it shows.**
1. The bundled model does not transfer: on real rooms it is worse than "no change" at every horizon (60 min: 0.93 vs
   0.44 °C). A likely reason (not tested separately): real rooms change more slowly and smoothly than the simulator's
   episodes, so weights learned for "a change is coming" overshoot, most at 60 minutes.
2. Retraining on real data helps: every retrained model beats the bundled one everywhere, and GBT and ridge sim+real
   beat persistence at every horizon (ridge on real data alone is slightly worse than persistence at 60 min: 0.459 vs
   0.441). The gain over persistence is modest (about 5–10 % at 60 min), which is honest for 10-minute house data
   with few events.
3. On real data, early warning is hard for every learned model; the simple trend extrapolation warns best. Events are
   rare and concentrated in two locations, so these F1 values rest on a handful of episodes.
4. The surface offset barely changes the regression (with a fixed offset the *change* in margin does not depend on it;
   only the level-based features do), but it changes how many events exist (304 / 523 / 801 at 0.5 / 1.5 / 3.0 °C)
   and the warning numbers move with it. The bundled model's warnings are poor at every offset.

**Decision: the bundled model stays** (`ridge-2026-11-03-s240`). Acceptance rule results (every check must pass):

| Candidate | (a) real MAE lower | (b) sim MAE no worse | (c) F1 / false alarms | (d) speed / size |
|---|---|---|---|---|
| Ridge, real | pass | **fail** (1.05 vs 0.33 at +15) | **fail** (sim F1 0.01; 63.8 false alarms/day) | pass |
| Ridge, sim + real | pass | **fail** (0.364 vs 0.331 at +15) | **fail** (sim F1 60: 0.28 vs 0.56) | pass |
| GBT, real | pass | **fail** | **fail** | **fail** (1.8 MB) |
| GBT, sim + real | pass | pass (0.245 / 0.455 / 1.076) | **fail**: sim false alarms 8.27 vs 5.60 per day | **fail**: 1.84 MB (inference 0.08 ms is fine) |

The closest candidate, GBT trained on simulated + real data, is better on real rooms *and* on the simulator's MAE
and F1, but raises more false alarms on simulated sensors and its file is too big. Both are fixable (fewer, shallower
trees; a tuned warning threshold), but changing them after seeing the test results would break the protocol, so this
is left as a next step with a fresh evaluation. Note also that check (a) turned out to be weak, because the bundled
model is worse than persistence on real data; a future rule should also require beating persistence.

**Deviations from the protocol.** None in data, splits, metrics or the rule. Clarifications: the rule was applied to
each of the four recipes (none passed, so choosing between passing ones never arose); the simulated-test replay uses
the protocol's one-reading bridge and "within the horizon" label, so its numbers differ slightly from the original
simulated report (bundled false alarms 5.60/day here vs 4.2 there; MAEs are identical); the runs used Java 23 with the
code compiled for Java 17; median inference times are measurements and vary slightly between runs, every other number
is reproduced exactly.

