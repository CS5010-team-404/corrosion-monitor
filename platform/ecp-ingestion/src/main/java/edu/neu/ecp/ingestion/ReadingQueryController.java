package edu.neu.ecp.ingestion;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Raw readings for charts. Analytics exposes the derived values (dew point, margin, forecast). */
@RestController
public class ReadingQueryController {

    public record ReadingView(long id, long seqNo, Instant measuredAt, Instant receivedAt, double temperatureC,
                              double humidityPct, Double surfaceTempC) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReadingQueryController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/api/sensors/{sensorId}/readings")
    public List<ReadingView> readings(@PathVariable long sensorId, @RequestParam(defaultValue = "60") int minutes) {
        Instant since = clock.instant().minus(Duration.ofMinutes(Math.max(1, Math.min(minutes, 7 * 24 * 60))));
        return jdbc.query("""
                SELECT id, seq_no, measured_at, received_at, temperature_c, humidity_pct, surface_temp_c
                FROM readings WHERE sensor_id = ? AND measured_at > ? ORDER BY measured_at LIMIT 5000
                """, (rs, i) -> new ReadingView(rs.getLong("id"), rs.getLong("seq_no"),
                rs.getTimestamp("measured_at").toInstant(), rs.getTimestamp("received_at").toInstant(),
                rs.getDouble("temperature_c"), rs.getDouble("humidity_pct"),
                rs.getObject("surface_temp_c") == null ? null : rs.getDouble("surface_temp_c")),
                sensorId, Timestamp.from(since));
    }
}
