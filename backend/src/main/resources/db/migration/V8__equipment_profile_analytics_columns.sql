-- Profile data the analytics service used to hard-code in Java (see docs/GAPS.md): the equipment kind (the UI picks
-- its artwork from it), the allowed air-temperature range, and the cold-spot offset used for the surface temperature
-- when a sensor does not measure it.
--
-- The values are the DEMO values that were in AnalyticsService, matched on the demo profile ids from V3
-- (1 IT, 2 telecom, 3 industrial control, 4 outdoor). They are NOT yet from a cited source. A profile that is not
-- listed keeps the column defaults, which are the IT values, exactly what the Java code did for an unknown profile.
--
-- Written for Aiven's sql_mode (ANSI_QUOTES, STRICT_ALL_TABLES): strings use single quotes only.

ALTER TABLE equipment_profile
    ADD COLUMN kind             VARCHAR(8)   NOT NULL DEFAULT 'it',
    ADD COLUMN temp_min_c       DECIMAL(5,2) NOT NULL DEFAULT 18.00,
    ADD COLUMN temp_max_c       DECIMAL(5,2) NOT NULL DEFAULT 27.00,
    ADD COLUMN surface_offset_c DECIMAL(4,2) NOT NULL DEFAULT 1.50;

-- A separate statement, so no clause depends on a column added in the same ALTER.
ALTER TABLE equipment_profile
    ADD CONSTRAINT chk_profile_kind CHECK (kind IN ('it', 'tel', 'plc', 'out')),
    ADD CONSTRAINT chk_profile_temp_range CHECK (temp_min_c < temp_max_c);

UPDATE equipment_profile SET kind = 'tel', temp_min_c = 5.00,   temp_max_c = 40.00 WHERE id = 2;
UPDATE equipment_profile SET kind = 'plc', temp_min_c = 0.00,   temp_max_c = 55.00 WHERE id = 3;
UPDATE equipment_profile SET kind = 'out', temp_min_c = -20.00, temp_max_c = 50.00 WHERE id = 4;
