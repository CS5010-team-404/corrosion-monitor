# Margin forecaster: training report

Model: robust (Huber) ridge regression per horizon on 15 features, predicting the change in margin.
Data: 240 simulated 8-hour runs for training, 60 unseen runs for testing (23400 test points).

| Horizon | λ | MAE model | MAE trend baseline | MAE no-change baseline | Improvement vs no-change |
|---|---|---|---|---|---|
| 15 min | 1e-04 | 0.331 °C | 0.424 °C | 0.452 °C | 27% |
| 30 min | 1e-03 | 0.638 °C | 0.865 °C | 0.796 °C | 20% |
| 60 min | 1e-03 | 1.335 °C | 2.067 °C | 1.528 °C | 13% |

On the minutes where conditions are actually changing (|30-min trend| > 0.02 °C/min, the cases
an operator cares about), the gap to the baselines is larger:

| Horizon | Points | MAE model | MAE no-change |
|---|---|---|---|
| 15 min | 5654 | 0.401 °C | 0.946 °C |
| 30 min | 5654 | 0.737 °C | 1.553 °C |
| 60 min | 5654 | 0.998 °C | 2.071 °C |

## Early warning classifier: will the margin reach 1.0 °C within the horizon?

Logistic regression, positives weighted 3×, threshold chosen on validation data to maximise F1.
Counted only where the margin is above 1.0 °C now. Many events start with a sudden,
unpredictable failure, so recall can never reach 100% from sensor data alone.

| Horizon | Threshold | Precision | Recall | F1 | Events | Regression-only recall |
|---|---|---|---|---|---|---|
| 30 min | 0.55 | 0.86 | 0.67 | 0.75 | 1262 | 0.62 |
| 60 min | 0.50 | 0.86 | 0.52 | 0.65 | 2330 | 0.36 |

## Warning lead time (what operators feel)

For every moment the margin crosses 1.0 °C in the test runs (43 crossings), how long before it did a warning start?
Rule baseline: warn when the margin is at or below the 3 °C warning threshold (what analytics does without ML).

| | Median lead time | Warned at least 5 min ahead | False-alarm episodes per day |
|---|---|---|---|
| Model (60-min classifier or forecast) | 35 min | 88% | 4.2 |
| Rule: margin ≤ 3 °C | 17 min | 100% | 4.6 |

Weights (standardised features) are in the model file. Simulated data cannot prove real-world
accuracy; run with `--uci energydata_complete.csv` to check on real room humidity as well.
