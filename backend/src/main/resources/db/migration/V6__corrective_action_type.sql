-- Corrective actions have a type and optional notes (the UI sends {actionType, notes}). Owner: alerts.
-- Nothing reads or writes corrective_action yet, so existing rows (none expected) keep their text as notes.
ALTER TABLE corrective_action
    ADD COLUMN action_type VARCHAR(40) NOT NULL DEFAULT 'NOTE',
    CHANGE COLUMN action_text notes VARCHAR(1000) NULL;
