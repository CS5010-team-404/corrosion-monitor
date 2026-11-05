-- Demo reference data: four profiles, four locations and the twelve equipment items the platform's demo devices
-- point at (platform/ecp-platform-app/.../demo/DemoDevices.java registers SIM-001..SIM-012 with equipment_id 1..12).
-- Each block inserts only when its table is empty, so a database that already holds real data is left alone.
-- Ids are explicit so the links hold; AUTO_INCREMENT continues after them.
--
-- Team rule (V1, equipment_profile): threshold values must come from cited sources. The profile values below are the
-- ones the UI already shows and are NOT yet cited, except where a source is named. Peck constants use the defaults.

INSERT INTO equipment_profile (id, name, rh_warn_pct, rh_critical_pct, dew_margin_warn_c, dew_margin_critical_c, risk_budget)
SELECT * FROM (
    -- IT: rh_critical_pct 70 cites ASHRAE TC 9.9, Thermal Guidelines for Data Processing Environments, 5th edition
    -- (2021): recommended upper limit for low-pollutant environments.
    -- rh_warn_pct, dew margins and risk_budget: DEMO VALUE, not yet from a cited source. Aayushee to confirm or replace
    SELECT 1 AS id, 'IT equipment (ASHRAE)' AS name, 60.00 AS rh_warn_pct, 70.00 AS rh_critical_pct,
           3.00 AS dew_margin_warn_c, 1.00 AS dew_margin_critical_c, 100.00 AS risk_budget
    UNION ALL
    -- DEMO VALUE, not yet from a cited source. Aayushee to confirm or replace
    SELECT 2, 'Telecom cabinet', 60.00, 75.00, 3.00, 1.00, 100.00
    UNION ALL
    -- DEMO VALUE, not yet from a cited source. Aayushee to confirm or replace
    SELECT 3, 'Industrial control panel', 65.00, 80.00, 4.00, 1.50, 100.00
    UNION ALL
    -- DEMO VALUE, not yet from a cited source. Aayushee to confirm or replace
    SELECT 4, 'Outdoor enclosure', 70.00, 85.00, 4.00, 1.50, 100.00
) AS demo
WHERE NOT EXISTS (SELECT 1 FROM equipment_profile);

-- location.profile_id is required (V1); each demo location uses the profile of the equipment it holds.
INSERT INTO location (id, name, description, profile_id)
SELECT * FROM (
    SELECT 1 AS id, 'Server Room A' AS name, 'Demo location' AS description, 1 AS profile_id
    UNION ALL SELECT 2, 'Telecom Closet 2', 'Demo location', 2
    UNION ALL SELECT 3, 'Factory Floor, Line 3', 'Demo location', 3
    UNION ALL SELECT 4, 'North Gate', 'Demo location', 4
) AS demo
WHERE NOT EXISTS (SELECT 1 FROM location);

-- Names and locations match DemoDevices.java exactly; equipment.id = sensors.equipment_id for SIM-001..SIM-012.
INSERT INTO equipment (id, location_id, profile_id, name)
SELECT * FROM (
    SELECT 1 AS id, 1 AS location_id, 1 AS profile_id, 'Rack A1' AS name
    UNION ALL SELECT 2, 1, 1, 'Rack A2'
    UNION ALL SELECT 3, 1, 1, 'Rack A3'
    UNION ALL SELECT 4, 2, 2, 'Core switch cabinet'
    UNION ALL SELECT 5, 2, 2, 'Patch panel C2'
    UNION ALL SELECT 6, 2, 2, 'Router cabinet'
    UNION ALL SELECT 7, 3, 3, 'PLC panel L3-01'
    UNION ALL SELECT 8, 3, 3, 'Drive panel L3-02'
    UNION ALL SELECT 9, 3, 3, 'PLC panel L3-03'
    UNION ALL SELECT 10, 4, 4, 'Kiosk N1'
    UNION ALL SELECT 11, 4, 4, 'Kiosk N2'
    UNION ALL SELECT 12, 4, 4, 'Barrier controller'
) AS demo
WHERE NOT EXISTS (SELECT 1 FROM equipment);
