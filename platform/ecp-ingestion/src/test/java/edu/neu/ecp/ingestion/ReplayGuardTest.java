package edu.neu.ecp.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The claim logic without a database; the SQL runs on MySQL in IngestionApiIntegrationTest. */
class ReplayGuardTest {

    static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final ReplayGuard guard = new ReplayGuard(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aNewKeyIsClaimed() {
        when(jdbc.update(startsWith("INSERT INTO ingest_replay_guard"), anyLong(), anyString(), any())).thenReturn(1);
        assertThat(guard.claim(7, "k", NOW.plusSeconds(360), NOW)).isTrue();
        verify(jdbc, never()).update(startsWith("UPDATE"), any(), anyLong(), anyString(), any());
    }

    @Test
    void aKeyStillHeldIsAReplay() {
        when(jdbc.update(startsWith("INSERT INTO ingest_replay_guard"), anyLong(), anyString(), any()))
                .thenThrow(new DuplicateKeyException("dup"));
        when(jdbc.update(startsWith("UPDATE ingest_replay_guard"), any(), anyLong(), anyString(), any())).thenReturn(0);
        assertThat(guard.claim(7, "k", NOW.plusSeconds(360), NOW)).isFalse();
    }

    @Test
    void anExpiredKeyNotYetPrunedIsTakenOver() {
        when(jdbc.update(startsWith("INSERT INTO ingest_replay_guard"), anyLong(), anyString(), any()))
                .thenThrow(new DuplicateKeyException("dup"));
        when(jdbc.update(startsWith("UPDATE ingest_replay_guard"), any(), anyLong(), anyString(), any())).thenReturn(1);
        assertThat(guard.claim(7, "k", NOW.plusSeconds(360), NOW)).isTrue();
    }

    @Test
    void keysAreHashesThatSeparateNoncesFromSignatures() {
        assertThat(ReplayGuard.key("abc", "SIG")).hasSize(64).isNotEqualTo(ReplayGuard.key(null, "abc"));
        assertThat(ReplayGuard.key(null, " ABCdef ")).isEqualTo(ReplayGuard.key(null, "abcdef"));
    }

    @Test
    void pruneDeletesExpiredRowsByTheClock() {
        when(jdbc.update(startsWith("DELETE FROM ingest_replay_guard"), any(Object.class))).thenReturn(3);
        assertThat(guard.prune()).isEqualTo(3);
        guard.scheduledPrune();
        verify(jdbc, org.mockito.Mockito.times(2)).update(startsWith("DELETE FROM ingest_replay_guard"), eq(java.sql.Timestamp.from(NOW)));
    }
}
