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
Or call the platform's `GET /api/sensors/{id}/forecast`. Retrain with `scripts/train-model.sh`; the report and
model file are regenerated in `ecp-forecast/src/main/resources/models/`.
