-- =====================================================================================================
-- Contract table: the latest condensation-margin forecast per trusted sensor (FR2). The platform's
-- forecast publisher rewrites it every minute; analytics (team backend) reads it. Documented in
-- docs/CONTRACTS.md. MySQL 8, valid under the production sql_mode (ANSI, strict). All timestamps UTC.
-- A row exists only for a sensor that was active, trusted and had recent readings at computed_at;
-- rows for sensors that stop qualifying are deleted on the next run.
-- =====================================================================================================

CREATE TABLE IF NOT EXISTS sensor_forecasts (
    sensor_id                 BIGINT       NOT NULL PRIMARY KEY,  -- sensors.id
    margin_15_c               DECIMAL(6,2) NOT NULL,              -- predicted surface temp minus dew point, °C
    margin_30_c               DECIMAL(6,2) NOT NULL,
    margin_60_c               DECIMAL(6,2) NOT NULL,
    warning_probability_30    DECIMAL(5,4) NOT NULL,              -- 0..1: margin reaches 1 °C within 30 min
    warning_probability_60    DECIMAL(5,4) NOT NULL,              -- 0..1: ... within 60 min
    method                    VARCHAR(8)   NOT NULL,              -- ML | TREND (too little history for the model)
    predicted_condensation_at DATETIME(3)  NULL,                  -- margin reaches 0 °C; NULL if not within the hour
    computed_at               DATETIME(3)  NOT NULL
);
