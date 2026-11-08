Read CLAUDE.md, docs/DECISIONS.md, docs/CONTRACTS.md and docs/ML_FORECAST.md. All paths start with /api/v1.
Risk is computed per equipment item (equipment table: a location has many items, each with an equipment_profile),
and readings come from the platform's sensors/readings tables (sensors.equipment_id). I am Aayushee and I own analytics. Change only the
analytics code (and the reports part I share with Dumebi). Do not modify platform/ or other teammates' areas.

1. Implement or adapt GET /api/v1/equipment/status and GET /api/v1/equipment/{id}/assessments so they return the
   shapes in frontend/src/api/types.ts (EquipmentStatus, AssessmentPoint), including the profile thresholds.
2. Read readings only from sensors whose sensor_health.trusted is true.
3. Use platform/ecp-forecast (MarginForecaster, ModelIO.loadBundled()) for the 15/30/60-minute margins and
   predictedCondensationAt; raise and clear CONDENSATION_RISK and CONDENSATION_FORECAST signals in alert_signals
   with the dedup keys in CONTRACTS.md.
4. Add the risk index and time of wetness, with unit tests (JUnit 5).
5. Reports: GET /api/v1/reports/summary plus .csv and .pdf (OpenPDF), shapes as in ReportSummary.
Close my items in docs/GAPS.md and open a pull request.