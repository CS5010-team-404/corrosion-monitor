-- =====================================================================================================
-- Corrosion Watch data platform tables (owner: Joshua). MySQL 8.
-- Every statement is CREATE TABLE IF NOT EXISTS and Flyway keeps its own history table
-- (flyway_platform_history), so this never touches or conflicts with the team backend's schema.
-- Contract tables (alert_signals, audit_outbox) are shared: the platform writes them, the team's
-- alert and audit services read them. If the team already has equivalents, map them in docs/CONTRACTS.md.
-- All timestamps are UTC.
-- =====================================================================================================

CREATE TABLE IF NOT EXISTS sensors (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id            VARCHAR(64)  NOT NULL UNIQUE,
    equipment_id         BIGINT       NULL,          -- the team backend's equipment id (no FK on purpose)
    equipment_name       VARCHAR(128) NULL,          -- copied for display; the team's table stays the source of truth
    location_name        VARCHAR(128) NULL,
    kind                 VARCHAR(16)  NOT NULL DEFAULT 'SIMULATED',  -- SIMULATED | PHYSICAL
    model                VARCHAR(32)  NOT NULL DEFAULT 'SHT31',
    secret_enc           VARCHAR(512) NOT NULL,      -- HMAC key, AES-GCM encrypted with ECP_SECRET_KEY
    expected_interval_s  INT          NOT NULL DEFAULT 15,
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    sim_base_temp_c      DECIMAL(5,2) NULL,          -- simulated devices only: starting room conditions
    sim_base_rh_pct      DECIMAL(5,2) NULL,
    sim_surface_offset_c DECIMAL(5,2) NULL,
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS readings (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    sensor_id      BIGINT        NOT NULL,
    seq_no         BIGINT        NOT NULL,           -- per-device counter: gaps = lost readings
    measured_at    DATETIME(3)   NOT NULL,           -- device clock
    received_at    DATETIME(3)   NOT NULL,           -- server clock
    temperature_c  DECIMAL(6,2)  NOT NULL,
    humidity_pct   DECIMAL(6,2)  NOT NULL,
    surface_temp_c DECIMAL(6,2)  NULL,
    UNIQUE KEY uq_readings_sensor_seq (sensor_id, seq_no),
    KEY ix_readings_sensor_time (sensor_id, measured_at),
    KEY ix_readings_received (received_at)
);

CREATE TABLE IF NOT EXISTS ingest_rejections (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id   VARCHAR(64)  NOT NULL,
    sensor_id   BIGINT       NULL,
    reason      VARCHAR(32)  NOT NULL,   -- UNKNOWN_DEVICE | INACTIVE_SENSOR | STALE_TIMESTAMP | BAD_SIGNATURE | INVALID_PAYLOAD
    detail      VARCHAR(255) NULL,
    received_at DATETIME(3)  NOT NULL,
    KEY ix_rejections_device_time (device_id, received_at),
    KEY ix_rejections_time (received_at)
);

CREATE TABLE IF NOT EXISTS sensor_health (
    sensor_id          BIGINT PRIMARY KEY,
    status             VARCHAR(16)  NOT NULL,   -- HEALTHY | DEGRADED | FAULTY | STUCK | OFFLINE | UNKNOWN
    connectivity       VARCHAR(16)  NOT NULL,   -- ONLINE | LATE | OFFLINE | NEVER_SEEN
    trusted            BOOLEAN      NOT NULL,   -- analytics: ignore readings while FALSE
    health_score       SMALLINT     NOT NULL,
    issues             JSON         NOT NULL,
    last_seen_at       DATETIME(3)  NULL,
    last_measured_at   DATETIME(3)  NULL,
    last_seq_no        BIGINT       NULL,
    window_readings    INT          NOT NULL DEFAULT 0,
    window_missing_pct DECIMAL(5,2) NULL,
    signature_failures INT          NOT NULL DEFAULT 0,
    status_since       DATETIME(3)  NOT NULL,
    evaluated_at       DATETIME(3)  NOT NULL
);

CREATE TABLE IF NOT EXISTS sensor_health_events (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sensor_id  BIGINT      NOT NULL,
    issue      VARCHAR(32) NOT NULL,
    severity   VARCHAR(16) NOT NULL,
    started_at DATETIME(3) NOT NULL,
    ended_at   DATETIME(3) NULL,
    details    JSON        NOT NULL,
    KEY ix_health_events_sensor (sensor_id, started_at),
    KEY ix_health_events_open (ended_at)
);

-- Contract: conditions raised and cleared by analytics and sensor health, consumed by the alert service.
CREATE TABLE IF NOT EXISTS alert_signals (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    source       VARCHAR(16)  NOT NULL,   -- ANALYTICS | SENSOR_HEALTH | SYSTEM
    signal_type  VARCHAR(48)  NOT NULL,
    severity     VARCHAR(16)  NOT NULL,   -- INFO | WARNING | CRITICAL
    state        VARCHAR(16)  NOT NULL,   -- RAISED | CLEARED
    equipment_id BIGINT       NULL,
    sensor_id    BIGINT       NULL,
    dedup_key    VARCHAR(128) NOT NULL,
    title        VARCHAR(160) NULL,
    message      VARCHAR(500) NULL,
    payload      JSON         NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    processed_at DATETIME(3)  NULL,
    KEY ix_signals_unprocessed (processed_at, id),
    KEY ix_signals_dedup (dedup_key)
);

-- Contract: events for the tamper-evident audit log, chained by the audit service.
CREATE TABLE IF NOT EXISTS audit_outbox (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    occurred_at DATETIME(3) NOT NULL,
    actor_type  VARCHAR(16) NOT NULL,     -- USER | SYSTEM | DEVICE
    actor       VARCHAR(64) NOT NULL,
    action      VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NULL,
    entity_id   VARCHAR(64) NULL,
    details     JSON        NOT NULL,
    chained_at  DATETIME(3) NULL,
    KEY ix_outbox_pending (chained_at, id)
);

CREATE TABLE IF NOT EXISTS service_heartbeats (
    service         VARCHAR(32) PRIMARY KEY,
    last_run_at     DATETIME(3)  NOT NULL,
    last_success_at DATETIME(3)  NULL,
    last_error      VARCHAR(500) NULL,
    last_error_at   DATETIME(3)  NULL,
    run_count       BIGINT       NOT NULL DEFAULT 0,
    error_count     BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS job_cursors (
    job_name   VARCHAR(64) PRIMARY KEY,
    last_id    BIGINT      NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL
);
