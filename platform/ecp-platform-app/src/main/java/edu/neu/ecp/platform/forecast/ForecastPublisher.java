package edu.neu.ecp.platform.forecast;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the latest forecast of every active, trusted sensor into {@code sensor_forecasts}, the table analytics reads
 * (FR2), so the team backend never has to call this service. Uses the same {@link ForecastService} as
 * {@code GET /api/v1/sensors/{id}/forecast}, so the table and the API agree. Rows of sensors that are no longer
 * trusted (or have no recent readings) are deleted in the same transaction.
 */
@Service
public class ForecastPublisher {

    private final JdbcTemplate jdbc;
    private final ForecastService forecasts;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ForecastPublisher(JdbcTemplate jdbc, ForecastService forecasts, Clock clock, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.forecasts = forecasts;
        this.clock = clock;
        this.tx = tx;
    }

    /** One pass. Returns the rows written. */
    public List<PublishedForecast> publishAll() {
        // DATETIME(3): whole milliseconds, so the stale-row delete below compares exactly
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        List<Long> trusted = jdbc.queryForList("""
                SELECT s.id FROM sensors s JOIN sensor_health h ON h.sensor_id = s.id
                WHERE s.active = TRUE AND h.trusted = TRUE ORDER BY s.id
                """, Long.class);
        List<PublishedForecast> rows = new ArrayList<>(trusted.size());
        for (long sensorId : trusted) {
            forecasts.forSensor(sensorId, null)
                    .flatMap(f -> PublishedForecast.of(sensorId, f, now))
                    .ifPresent(rows::add);
        }
        tx.executeWithoutResult(status -> {
            if (!rows.isEmpty()) {
                jdbc.batchUpdate("""
                        INSERT INTO sensor_forecasts (sensor_id, margin_15_c, margin_30_c, margin_60_c,
                            warning_probability_30, warning_probability_60, method, predicted_condensation_at, computed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE margin_15_c = VALUES(margin_15_c), margin_30_c = VALUES(margin_30_c),
                            margin_60_c = VALUES(margin_60_c), warning_probability_30 = VALUES(warning_probability_30),
                            warning_probability_60 = VALUES(warning_probability_60), method = VALUES(method),
                            predicted_condensation_at = VALUES(predicted_condensation_at), computed_at = VALUES(computed_at)
                        """, rows, rows.size(), (ps, r) -> {
                    ps.setLong(1, r.sensorId());
                    ps.setDouble(2, r.margin15C());
                    ps.setDouble(3, r.margin30C());
                    ps.setDouble(4, r.margin60C());
                    ps.setDouble(5, r.warningProbability30());
                    ps.setDouble(6, r.warningProbability60());
                    ps.setString(7, r.method());
                    ps.setTimestamp(8, r.predictedCondensationAt() == null ? null : Timestamp.from(r.predictedCondensationAt()));
                    ps.setTimestamp(9, Timestamp.from(r.computedAt()));
                });
            }
            // every row written in this pass has computed_at = now; anything older no longer qualifies
            jdbc.update("DELETE FROM sensor_forecasts WHERE computed_at < ?", Timestamp.from(now));
        });
        return rows;
    }
}
