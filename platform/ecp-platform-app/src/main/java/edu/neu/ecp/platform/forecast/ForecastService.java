package edu.neu.ecp.platform.forecast;

import edu.neu.ecp.forecast.model.Forecast;
import edu.neu.ecp.forecast.model.MarginForecaster;
import edu.neu.ecp.forecast.model.MarginPoint;
import edu.neu.ecp.forecast.model.ModelIO;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Serves forecasts from the bundled model (ecp-forecast). Readings are averaged into one-minute points; the
 * surface temperature is the measured one when a device reports it, otherwise air minus the offset.
 * The analytics service (Aayushee) can use {@link MarginForecaster} directly instead of this endpoint.
 */
@Service
public class ForecastService {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final MarginForecaster model = ModelIO.loadBundled();

    public ForecastService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public MarginForecaster model() {
        return model;
    }

    public Optional<Forecast> forSensor(long sensorId, Double offsetOverride) {
        Instant since = clock.instant().minus(Duration.ofMinutes(45));
        Double offset = offsetOverride != null ? offsetOverride : jdbc.query(
                "SELECT COALESCE(sim_surface_offset_c, 1.5) FROM sensors WHERE id = ?", (rs, i) -> rs.getDouble(1), sensorId)
                .stream().findFirst().orElse(1.5);
        TreeMap<Long, double[]> minutes = new TreeMap<>(); // minute -> {sumT, sumRh, sumSurface, n}
        jdbc.query("""
                SELECT measured_at, temperature_c, humidity_pct, surface_temp_c FROM readings
                WHERE sensor_id = ? AND measured_at > ? ORDER BY measured_at
                """, rs -> {
            long minute = rs.getTimestamp(1).toInstant().getEpochSecond() / 60;
            double t = rs.getDouble(2);
            double surface = rs.getObject(4) == null ? t - offset : rs.getDouble(4);
            double[] acc = minutes.computeIfAbsent(minute, k -> new double[4]);
            acc[0] += t;
            acc[1] += rs.getDouble(3);
            acc[2] += surface;
            acc[3]++;
        }, sensorId, Timestamp.from(since));
        if (minutes.isEmpty()) {
            return Optional.empty();
        }
        List<MarginPoint> series = new ArrayList<>();
        minutes.forEach((m, a) -> series.add(new MarginPoint(Instant.ofEpochSecond(m * 60), a[0] / a[3], a[1] / a[3], a[2] / a[3])));
        return Optional.of(model.forecast(series));
    }
}
