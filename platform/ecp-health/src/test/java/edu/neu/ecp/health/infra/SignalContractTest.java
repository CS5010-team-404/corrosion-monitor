package edu.neu.ecp.health.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.neu.ecp.common.signal.JdbcSignalPublisher;
import edu.neu.ecp.common.signal.SignalState;
import edu.neu.ecp.health.domain.HealthIssue;
import edu.neu.ecp.health.domain.SensorRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Contract with the team backend's alert service: the exact alert_signals rows sensor health writes when SIM-010 goes
 * offline and comes back. They are committed in src/test/resources/fixtures/alert-signals.json, and the backend's
 * SignalContractTest feeds a byte-identical copy (backend/src/test/resources/fixtures/) to its SignalConsumer.
 * If this test fails, the platform changed what it writes: update both copies with -DregenerateSignalFixture=true
 * and check the backend test still passes.
 */
class SignalContractTest {

    static final Path FIXTURE = Path.of("src/test/resources/fixtures/alert-signals.json");
    static final Path BACKEND_COPY = Path.of("../../backend/src/test/resources/fixtures/alert-signals.json");
    /** Column order of JdbcSignalPublisher's INSERT. */
    static final String[] COLUMNS = {"source", "signal_type", "severity", "state", "equipment_id", "sensor_id", "dedup_key",
            "title", "message", "payload", "created_at"};

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sensorHealthWritesTheRowsInTheFixture() throws IOException {
        String produced = render(produce());
        if (Boolean.getBoolean("regenerateSignalFixture")) {
            Files.writeString(FIXTURE, produced, StandardCharsets.UTF_8);
            Files.writeString(BACKEND_COPY, produced, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(FIXTURE, StandardCharsets.UTF_8)).isEqualTo(produced);
    }

    @Test
    void theBackendHasTheSameFixture() throws IOException {
        assertThat(Files.readAllBytes(BACKEND_COPY)).isEqualTo(Files.readAllBytes(FIXTURE));
    }

    /** Runs the real signal building and publishing and captures the INSERT arguments, one row per signal. */
    private List<Map<String, Object>> produce() throws IOException {
        SensorRef sim10 = new SensorRef(10, "SIM-010", 10L, Duration.ofSeconds(15), Instant.parse("2026-11-01T00:00:00Z"));
        Map<String, Object> evidence = new LinkedHashMap<>();   // what HealthEvaluator records for OFFLINE
        evidence.put("silentSeconds", 120L);
        evidence.put("neverReported", false);
        evidence.put("offlineAfterSeconds", 90L);

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(publish(SensorHealthService.signal(sim10, HealthIssue.OFFLINE, SignalState.RAISED, evidence), "2026-11-20T12:00:00Z"));
        rows.add(publish(SensorHealthService.signal(sim10, HealthIssue.OFFLINE, SignalState.CLEARED, Map.of()), "2026-11-20T12:05:00Z"));
        return rows;
    }

    private Map<String, Object> publish(edu.neu.ecp.common.signal.AlertSignal signal, String at) throws IOException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new JdbcSignalPublisher(jdbc, json, Clock.fixed(Instant.parse(at), ZoneOffset.UTC)).publish(signal);
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbc, times(1)).update(anyString(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture());
        List<Object> v = args.getAllValues();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < COLUMNS.length; i++) {
            Object value = v.get(i);
            if ("payload".equals(COLUMNS[i])) {
                value = json.readValue((String) value, LinkedHashMap.class);   // stored as JSON text
            } else if (value instanceof Timestamp t) {
                value = t.toInstant().toString();
            }
            row.put(COLUMNS[i], value);
        }
        return row;
    }

    private String render(List<Map<String, Object>> rows) throws IOException {
        DefaultPrettyPrinter pretty = new DefaultPrettyPrinter().withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE.withLinefeed("\n"))
                .withObjectIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE.withLinefeed("\n"));
        // Keys sorted: the payload map has no stable order (AlertSignal copies it with Map.copyOf), and MySQL's JSON
        // column does not keep key order either, so only the content is part of the contract.
        return json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).writer(pretty)
                .writeValueAsString(rows) + "\n";
    }
}
