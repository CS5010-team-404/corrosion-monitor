package com.team404.corrosion.analytics;

import com.team404.corrosion.analytics.risk.RiskClassifier;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * Reads the shared MySQL database: the team schema (equipment, location, equipment_profile) and the platform's tables
 * (sensors, sensor_health, readings), see docs/CONTRACTS.md. Readings of sensors that sensor health has not marked
 * trusted are never loaded.
 */
@Repository
public class JdbcAnalyticsRepo implements AnalyticsRepo {

    private static final String READINGS_SQL = """
            SELECT r.sensor_id, r.measured_at, r.temperature_c, r.humidity_pct, r.surface_temp_c
            FROM readings r
            JOIN sensor_health h ON h.sensor_id = r.sensor_id AND h.trusted = TRUE
            WHERE r.measured_at >= ?
            ORDER BY r.sensor_id, r.measured_at
            """;

    /**
     * One row per active sensor of each equipment item. Within an item the trusted sensors come first (a sensor
     * without a health row counts as not trusted), so the first row of an item is the one to use.
     */
    private static final String EQUIPMENT_SQL = """
            SELECT e.id, e.name, l.name AS location_name,
                   p.id AS profile_id, p.name AS profile_name,
                   p.kind, p.temp_min_c, p.temp_max_c, p.surface_offset_c,
                   p.dew_margin_warn_c, p.dew_margin_critical_c, p.rh_warn_pct, p.rh_critical_pct,
                   p.risk_budget, p.peck_n, p.peck_ea_ev, p.ref_temp_c, p.ref_rh_pct,
                   s.id AS sensor_id, s.device_id, h.trusted
            FROM equipment e
            JOIN location l ON l.id = e.location_id
            JOIN equipment_profile p ON p.id = e.profile_id
            LEFT JOIN sensors s ON s.equipment_id = e.id AND s.active = TRUE
            LEFT JOIN sensor_health h ON h.sensor_id = s.id
            ORDER BY e.id, COALESCE(h.trusted, 0) DESC, s.id
            """;

    private final JdbcTemplate jdbc;

    public JdbcAnalyticsRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<EquipmentSnap> loadSnapshots(Instant since) {
        Map<Long, EquipmentSnap> byEquipment = new LinkedHashMap<>();
        Map<Long, List<Reading>> readingsBySensor = loadTrustedReads(since);
        jdbc.query(EQUIPMENT_SQL, (RowCallbackHandler) rs -> {
            long equipmentId = rs.getLong("id");
            if (byEquipment.containsKey(equipmentId)) {
                return;   // the first row is the preferred sensor (trusted first); ignore the others
            }
            long sensorIdValue = rs.getLong("sensor_id");
            Long sensorId = rs.wasNull() ? null : sensorIdValue;
            boolean trusted = rs.getBoolean("trusted");   // no sensor_health row yet counts as not trusted

            RiskClassifier.Thresholds thresholds = new RiskClassifier.Thresholds(rs.getDouble("dew_margin_warn_c"),
                    rs.getDouble("dew_margin_critical_c"), rs.getDouble("rh_warn_pct"), rs.getDouble("rh_critical_pct"));
            List<Reading> readings = (sensorId != null && trusted)
                    ? readingsBySensor.getOrDefault(sensorId, List.of())
                    : List.of();
            byEquipment.put(equipmentId, new EquipmentSnap(equipmentId, rs.getString("name"),
                    rs.getString("location_name"), rs.getLong("profile_id"), rs.getString("profile_name"),
                    rs.getString("kind"), rs.getDouble("temp_min_c"), rs.getDouble("temp_max_c"),
                    rs.getDouble("surface_offset_c"), thresholds, rs.getDouble("risk_budget"),
                    rs.getDouble("peck_n"), rs.getDouble("peck_ea_ev"), rs.getDouble("ref_temp_c"),
                    rs.getDouble("ref_rh_pct"), sensorId, rs.getString("device_id"), trusted, readings));
        });
        return new ArrayList<>(byEquipment.values());
    }

    private Map<Long, List<Reading>> loadTrustedReads(Instant since) {
        Map<Long, List<Reading>> bySensor = new LinkedHashMap<>();
        jdbc.query(READINGS_SQL, (RowCallbackHandler) rs -> {
            double surface = rs.getDouble("surface_temp_c");
            Double surfaceTempC = rs.wasNull() ? null : surface;
            // measured_at is stored in UTC without a zone, so read it as a LocalDateTime and mark it UTC
            Instant measuredAt = rs.getObject("measured_at", LocalDateTime.class).toInstant(ZoneOffset.UTC);
            bySensor.computeIfAbsent(rs.getLong("sensor_id"), id -> new ArrayList<>())
                    .add(new Reading(measuredAt, rs.getDouble("temperature_c"), rs.getDouble("humidity_pct"),
                            surfaceTempC));
        }, LocalDateTime.ofInstant(since, ZoneOffset.UTC));
        return bySensor;
    }
}
