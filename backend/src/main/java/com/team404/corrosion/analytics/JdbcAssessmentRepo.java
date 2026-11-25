package com.team404.corrosion.analytics;

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
 * risk_assessments in MySQL. Plain SQL that runs in Aiven's ANSI sql_mode. The database stores UTC times without a
 * zone, so every time goes in and out as a LocalDateTime in UTC, never as a java.sql.Timestamp (which would depend on
 * the JVM's time zone).
 */
@Repository
public class JdbcAssessmentRepo implements AssessmentRepo {

    private final JdbcTemplate jdbc;

    public JdbcAssessmentRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, Instant> lastMeasuredAt() {
        Map<Long, Instant> last = new LinkedHashMap<>();
        jdbc.query("SELECT equipment_id, MAX(measured_at) AS last_measured FROM risk_assessments GROUP BY equipment_id",
                (RowCallbackHandler) rs -> last.put(rs.getLong("equipment_id"), utc(rs.getObject("last_measured",
                        LocalDateTime.class))));
        return last;
    }

    @Override
    public void insert(List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO risk_assessments (equipment_id, assessed_at, measured_at, temperature_c, surface_temp_c,
                                              margin_c, dew_point_c, rh_pct, risk_index, level)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows, rows.size(), (ps, r) -> {
            ps.setLong(1, r.equipmentId());
            ps.setObject(2, local(r.assessedAt()));
            ps.setObject(3, local(r.measuredAt()));
            ps.setDouble(4, r.temperatureC());
            ps.setDouble(5, r.surfaceTempC());
            ps.setDouble(6, r.marginC());
            ps.setDouble(7, r.dewPointC());
            ps.setDouble(8, r.rhPct());
            ps.setDouble(9, r.riskIndex());
            ps.setString(10, r.level());
        });
    }

    @Override
    public int deleteBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM risk_assessments WHERE assessed_at < ?", local(cutoff));
    }

    @Override
    public boolean equipmentExists(long equipmentId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM equipment WHERE id = ?", Integer.class, equipmentId);
        return n != null && n > 0;
    }

    @Override
    public List<AssessmentPoint> points(long equipmentId, Instant since) {
        return jdbc.query("""
                SELECT measured_at, temperature_c, rh_pct, surface_temp_c, dew_point_c, margin_c
                FROM risk_assessments
                WHERE equipment_id = ? AND assessed_at >= ?
                ORDER BY assessed_at, id
                """, (rs, row) -> new AssessmentPoint(utc(rs.getObject("measured_at", LocalDateTime.class)).toString(),
                rs.getDouble("temperature_c"), rs.getDouble("rh_pct"), rs.getDouble("surface_temp_c"),
                rs.getDouble("dew_point_c"), rs.getDouble("margin_c")), equipmentId, local(since));
    }

    @Override
    public List<SummaryRow> summaryRows(Instant from, Instant to, String location) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.equipment_id, e.name AS equipment_name, l.name AS location_name, a.assessed_at, a.level,
                       a.risk_index, a.temperature_c, a.rh_pct
                FROM risk_assessments a
                JOIN equipment e ON e.id = a.equipment_id
                JOIN location l ON l.id = e.location_id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (from != null) {
            sql.append(" AND a.assessed_at >= ?");
            args.add(local(from));
        }
        if (to != null) {
            sql.append(" AND a.assessed_at < ?");
            args.add(local(to));
        }
        if (location != null) {
            sql.append(" AND l.name = ?");
            args.add(location);
        }
        sql.append(" ORDER BY a.equipment_id, a.assessed_at, a.id");
        return jdbc.query(sql.toString(), (rs, row) -> new SummaryRow(rs.getLong("equipment_id"),
                rs.getString("equipment_name"), rs.getString("location_name"),
                utc(rs.getObject("assessed_at", LocalDateTime.class)), rs.getString("level"),
                rs.getDouble("risk_index"), rs.getDouble("temperature_c"), rs.getDouble("rh_pct")), args.toArray());
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant utc(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC);
    }
}
