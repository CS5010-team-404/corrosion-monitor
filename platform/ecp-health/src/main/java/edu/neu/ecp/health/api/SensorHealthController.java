package edu.neu.ecp.health.api;

import edu.neu.ecp.health.infra.HealthProperties;
import edu.neu.ecp.health.infra.HealthQueries;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read API for the Sensor Health page. Any signed-in role may read it. */
@RestController
@RequestMapping("/api/v1/health")
public class SensorHealthController {

    private final HealthQueries queries;
    private final HealthProperties properties;
    private final Clock clock;

    public SensorHealthController(HealthQueries queries, HealthProperties properties, Clock clock) {
        this.queries = queries;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/sensors")
    public List<HealthQueries.SensorHealthView> sensors() {
        return queries.sensors();
    }

    public record SensorDetail(HealthQueries.SensorHealthView sensor, List<HealthQueries.HealthEventView> events,
                               List<HealthQueries.ReadingPoint> readings) {
    }

    @GetMapping("/sensors/{sensorId}")
    public SensorDetail sensor(@PathVariable long sensorId, @RequestParam(defaultValue = "60") int minutes) {
        Instant now = clock.instant();
        var view = queries.sensor(sensorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No active sensor " + sensorId));
        int window = Math.max(5, Math.min(minutes, 24 * 60));
        return new SensorDetail(view, queries.events(sensorId, false, 50, now),
                queries.recentReadings(sensorId, now.minus(Duration.ofMinutes(window))));
    }

    @GetMapping("/events")
    public List<HealthQueries.HealthEventView> events(@RequestParam(defaultValue = "false") boolean openOnly,
                                                      @RequestParam(defaultValue = "100") int limit) {
        return queries.events(null, openOnly, limit, clock.instant());
    }

    public record Summary(int total, Map<String, Long> byStatus, Map<String, Long> openIssues, double trustedPct) {
    }

    @GetMapping("/summary")
    public Summary summary() {
        List<HealthQueries.SensorHealthView> all = queries.sensors();
        Map<String, Long> byStatus = all.stream()
                .collect(Collectors.groupingBy(HealthQueries.SensorHealthView::status, TreeMap::new, Collectors.counting()));
        Map<String, Long> openIssues = all.stream().flatMap(v -> v.issues().stream())
                .collect(Collectors.groupingBy(s -> s, TreeMap::new, Collectors.counting()));
        long trusted = all.stream().filter(HealthQueries.SensorHealthView::trusted).count();
        double pct = all.isEmpty() ? 0.0 : Math.round(1000.0 * trusted / all.size()) / 10.0;
        return new Summary(all.size(), byStatus, openIssues, pct);
    }

    @GetMapping("/services")
    public List<HealthQueries.ServiceView> services() {
        return queries.services(clock.instant(), properties.getServiceStaleAfter());
    }
}
