package com.team404.corrosion.report;

import com.team404.corrosion.report.ReportCalculator.AlertFacts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the facts a report needs from alert and corrective_action. Plain SQL that runs in Aiven's ANSI sql_mode. */
@Repository
public class ReportRepository {

    private static final String FROM_ALERT = " FROM alert a LEFT JOIN location l ON l.id = a.location_id";

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Alerts opened in the period (at the location, if one is given). */
    public List<AlertFacts> alertsOpened(ReportPeriod period) {
        var where = new Where(period, "a.opened_at");
        return jdbc.query("SELECT a.severity, a.opened_at, a.acknowledged_at, a.resolved_at" + FROM_ALERT + where.sql(),
                (rs, row) -> new AlertFacts(rs.getString("severity"), instant(rs, "opened_at"),
                        instant(rs, "acknowledged_at"), instant(rs, "resolved_at")),
                where.args());
    }

    /** Alerts escalated to the managers in the period. */
    public long escalations(ReportPeriod period) {
        var where = new Where(period, "a.escalated_at");
        Long n = jdbc.queryForObject("SELECT COUNT(*)" + FROM_ALERT + where.sql() + " AND a.escalated_at IS NOT NULL",
                Long.class, where.args());
        return n == null ? 0 : n;
    }

    /** The type of every corrective action logged in the period, on alerts at the location if one is given. */
    public List<String> actionTypes(ReportPeriod period) {
        var where = new Where(period, "c.created_at");
        return jdbc.queryForList("SELECT c.action_type FROM corrective_action c JOIN alert a ON a.id = c.alert_id"
                + " LEFT JOIN location l ON l.id = a.location_id" + where.sql(), String.class, where.args());
    }

    /** "WHERE 1 = 1 AND column >= ? AND column < ? AND l.name = ?" with only the parts the period uses. */
    private static final class Where {
        private final StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        private final List<Object> args = new ArrayList<>();

        Where(ReportPeriod period, String column) {
            if (period.from() != null) {
                sql.append(" AND ").append(column).append(" >= ?");
                args.add(Timestamp.from(period.from()));
            }
            if (period.to() != null) {
                sql.append(" AND ").append(column).append(" < ?");
                args.add(Timestamp.from(period.to()));
            }
            if (period.location() != null) {
                sql.append(" AND l.name = ?");
                args.add(period.location());
            }
        }

        String sql() {
            return sql.toString();
        }

        Object[] args() {
            return args.toArray();
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
