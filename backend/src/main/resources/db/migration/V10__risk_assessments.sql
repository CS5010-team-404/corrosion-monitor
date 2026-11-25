-- Analytics, phase 3 (docs/GAPS.md): every evaluation of an equipment item that had a trusted reading, kept for 7 days.
-- The analytics job writes it (it also prunes rows older than 7 days); GET /api/v1/equipment/{id}/assessments and the
-- report's risk figures read it. All timestamps are UTC. Valid under Aiven's ANSI sql_mode (no double-quoted
-- strings), with a primary key because sql_require_primary_key is ON.
--
-- One row per evaluation and equipment item. Items without a trusted reading (level UNKNOWN) have nothing to store,
-- so the level here is NORMAL, WARNING or CRITICAL. measured_at is the time of the reading that was assessed: the job
-- skips an evaluation whose reading it has already stored, so a silent sensor does not add duplicate points.

CREATE TABLE risk_assessments (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    equipment_id   BIGINT        NOT NULL,
    assessed_at    DATETIME(3)   NOT NULL,   -- when the job evaluated
    measured_at    DATETIME(3)   NOT NULL,   -- time of the reading that was assessed
    temperature_c  DECIMAL(6,2)  NOT NULL,
    surface_temp_c DECIMAL(6,2)  NOT NULL,   -- measured, or air temperature minus the profile's cold-spot offset
    margin_c       DECIMAL(6,2)  NOT NULL,   -- surface temperature minus dew point
    dew_point_c    DECIMAL(6,2)  NOT NULL,
    rh_pct         DECIMAL(5,2)  NOT NULL,
    risk_index     DECIMAL(10,4) NOT NULL,   -- Peck damage / profile budget; can exceed 1
    level          VARCHAR(8)    NOT NULL,
    CONSTRAINT fk_risk_assessment_equipment FOREIGN KEY (equipment_id) REFERENCES equipment (id) ON DELETE CASCADE,
    CONSTRAINT chk_risk_assessment_level CHECK (level IN ('NORMAL', 'WARNING', 'CRITICAL')),
    KEY ix_risk_assessments_equipment_time (equipment_id, assessed_at),
    KEY ix_risk_assessments_time (assessed_at)
);

-- Contract table written by the platform (platform/.../db/platform/V2__sensor_forecasts.sql, docs/CONTRACTS.md) and
-- read by analytics. Identical to the platform's definition and CREATE TABLE IF NOT EXISTS, like the other shared tables
-- in V2, so the backend works whichever service migrates first.
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
