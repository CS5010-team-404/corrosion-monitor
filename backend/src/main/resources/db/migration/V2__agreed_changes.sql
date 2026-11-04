-- Agreed changes (docs/DECISIONS.md, decisions 2, 4 and 5). Table and column names elsewhere are unchanged.

-- Decision 4: four roles.
ALTER TABLE app_user DROP CHECK chk_user_role;
ALTER TABLE app_user ADD CONSTRAINT chk_user_role CHECK (role IN ('TECHNICIAN','OPERATOR','MANAGER','ADMIN'));

-- Decision 5: a location has many equipment items, each with its own equipment_profile.
CREATE TABLE equipment (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    location_id  BIGINT NOT NULL,
    profile_id   BIGINT NOT NULL,
    name         VARCHAR(100) NOT NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (location_id) REFERENCES location(id),
    FOREIGN KEY (profile_id) REFERENCES equipment_profile(id)
) ENGINE=InnoDB;

-- Decision 2: the platform's sensors and readings tables replace these. Only reading.sensor_id and
-- used_nonce.sensor_id referred to them, and both tables go too, so no column is left pointing at sensor.
-- Code that needs a sensor uses a plain BIGINT sensor_id (= sensors.id, owned by the platform, no foreign key).
DROP TABLE used_nonce;
DROP TABLE reading;
DROP TABLE sensor;

-- Contract tables shared with the platform (docs/CONTRACTS.md). Identical to the platform's
-- db/platform/V1__platform_tables.sql, so whichever service starts first creates them.
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
