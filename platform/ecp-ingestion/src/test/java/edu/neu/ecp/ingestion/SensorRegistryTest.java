package edu.neu.ecp.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Device keys are stored encrypted and looked up through a 30-second cache that admins can evict. */
class SensorRegistryTest {

    private final SensorSecretCipher cipher = new SensorSecretCipher("test-only-key", "5c0744940b5c369b");

    @Test
    void cipherRoundTripsAndNeverStoresThePlainKey() {
        String secret = SensorSecretCipher.newSecret();
        String stored = cipher.encrypt(secret);
        assertThat(stored).isNotEqualTo(secret).doesNotContain(secret);
        assertThat(cipher.decrypt(stored)).isEqualTo(secret);
        assertThat(SensorSecretCipher.newSecret()).isNotEqualTo(secret).hasSize(43); // 256 bits, URL-safe Base64
    }

    @Test
    @SuppressWarnings("unchecked")
    void cachesLookupsForThirtySecondsAndEvictReloads() {
        Instant[] now = {Instant.parse("2026-11-20T12:00:00Z")};
        Clock clock = new Clock() {
            @Override public Instant instant() { return now[0]; }
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
        };
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var sensor = new SensorRegistry.RegisteredSensor(7, "SIM-001", 3, true, "k");
        when(jdbc.query(anyString(), any(RowMapper.class), eq("SIM-001"))).thenReturn(List.of(sensor));
        when(jdbc.query(anyString(), any(RowMapper.class), eq("NOPE"))).thenReturn(List.of());
        SensorRegistry registry = new SensorRegistry(jdbc, cipher, clock);

        assertThat(registry.find("SIM-001")).contains(sensor);
        now[0] = now[0].plus(Duration.ofSeconds(29));
        assertThat(registry.find("SIM-001")).contains(sensor);                 // cached
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class), eq("SIM-001"));

        now[0] = now[0].plus(Duration.ofSeconds(2));                           // 31 s: expired
        registry.find("SIM-001");
        registry.evict("SIM-001");                                             // e.g. key rotated
        registry.find("SIM-001");
        verify(jdbc, times(3)).query(anyString(), any(RowMapper.class), eq("SIM-001"));

        assertThat(registry.find("NOPE")).isEmpty();                           // unknown devices are cached too
        assertThat(registry.find("NOPE")).isEmpty();
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class), eq("NOPE"));
    }
}
