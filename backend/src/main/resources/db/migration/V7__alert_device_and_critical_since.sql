-- Two more alert columns. Owner: alerts.
ALTER TABLE alert
    -- The device that raised the alert, copied from the signal payload. The platform's sensors table may not exist
    -- when the backend runs first, so the alert list must not join it.
    ADD COLUMN device_id      VARCHAR(64) NULL,
    -- When the alert last became CRITICAL. Escalation counts from here, so a WARNING that turns CRITICAL gets the
    -- full grace period. NULL while WARNING; rows from before this column fall back to opened_at.
    ADD COLUMN critical_since DATETIME(3) NULL;
UPDATE alert SET critical_since = opened_at WHERE severity = 'CRITICAL';
