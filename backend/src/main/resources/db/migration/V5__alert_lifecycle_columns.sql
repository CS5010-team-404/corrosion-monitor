-- Alerts belong to an equipment item and are opened from alert_signals (docs/CONTRACTS.md, decision 5).
-- Only the alert table changes. Owner: alerts.

-- The UI says RESOLVED where V1 said CLOSED.
ALTER TABLE alert DROP CHECK chk_alert_status;
UPDATE alert SET status = 'RESOLVED' WHERE status = 'CLOSED';
ALTER TABLE alert ADD CONSTRAINT chk_alert_status CHECK (status IN ('OPEN','ACKNOWLEDGED','RESOLVED'));

-- Signals about the platform itself (SYSTEM:SERVICE_DOWN) have no equipment, so no location either.
ALTER TABLE alert MODIFY location_id BIGINT NULL;
ALTER TABLE alert MODIFY type VARCHAR(48) NOT NULL;

ALTER TABLE alert
    ADD COLUMN equipment_id         BIGINT       NULL,
    ADD COLUMN sensor_id            BIGINT       NULL,           -- sensors.id (platform, no foreign key)
    ADD COLUMN source               VARCHAR(16)  NOT NULL DEFAULT 'SYSTEM',
    ADD COLUMN title                VARCHAR(160) NULL,
    ADD COLUMN dedup_key            VARCHAR(128) NULL,           -- alert_signals.dedup_key; NULL on alerts that predate signals
    ADD COLUMN escalation_level     INT          NOT NULL DEFAULT 0,
    ADD COLUMN resolved_at          DATETIME(3)  NULL,
    ADD COLUMN resolved_by          BIGINT       NULL,           -- NULL when the alert resolved itself after the quiet period
    ADD COLUMN condition_cleared_at DATETIME(3)  NULL,           -- a CLEARED signal arrived; auto-resolve waits for a quiet period
    -- One unresolved alert per dedup key. MySQL has no partial unique index, so a generated column is NULL
    -- (and so exempt) once the alert is resolved.
    ADD COLUMN active_dedup_key     VARCHAR(128) GENERATED ALWAYS AS
        (CASE WHEN status <> 'RESOLVED' THEN dedup_key END) VIRTUAL,
    ADD CONSTRAINT fk_alert_equipment   FOREIGN KEY (equipment_id) REFERENCES equipment(id),
    ADD CONSTRAINT fk_alert_resolved_by FOREIGN KEY (resolved_by)  REFERENCES app_user(id),
    ADD UNIQUE INDEX ux_alert_active_dedup (active_dedup_key),
    ADD INDEX idx_alert_quiet (condition_cleared_at);
