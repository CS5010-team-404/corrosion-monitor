-- Corrective actions have a type and optional notes (the UI sends {actionType, notes}). Owner: alerts.
-- Nothing reads or writes corrective_action yet, so existing rows (none expected) keep their text as notes.
ALTER TABLE corrective_action
    ADD COLUMN action_type VARCHAR(40) NOT NULL DEFAULT 'NOTE',
    CHANGE COLUMN action_text notes VARCHAR(1000) NULL;

-- The device that raised the alert, copied from the signal payload. The platform's sensors table may not exist
-- when the backend runs first, so the alert list must not join it.
ALTER TABLE alert ADD COLUMN device_id VARCHAR(64) NULL;
