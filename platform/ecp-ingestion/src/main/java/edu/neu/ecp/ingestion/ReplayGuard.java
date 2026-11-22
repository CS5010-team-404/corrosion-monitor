package edu.neu.ecp.ingestion;

import edu.neu.ecp.common.domain.Hmac;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Remembers every accepted signed request for as long as its timestamp is still inside the accepted window, so the
 * same request cannot be accepted twice (table {@code ingest_replay_guard}, platform migration V3). The key is the
 * request's nonce when it has one, otherwise its signature (the signature covers timestamp and body, so a byte-for-byte
 * resend has the same one). Only a hash of the key is stored. Expired rows are pruned every minute.
 */
@Component
public class ReplayGuard {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayGuard.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReplayGuard(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The stored key: SHA-256 of the nonce (v2) or of the lower-case signature (legacy requests). */
    public static String key(String nonce, String signature) {
        return Hmac.sha256Hex(nonce != null ? "nonce:" + nonce : "sig:" + signature.trim().toLowerCase());
    }

    /**
     * Claims a request for one sensor. Returns false when the same key is already held and not yet expired (a
     * replay). The primary key makes two simultaneous copies race safely: exactly one wins.
     */
    public boolean claim(long sensorId, String key, Instant expiresAt, Instant now) {
        try {
            jdbc.update("INSERT INTO ingest_replay_guard (sensor_id, request_key, expires_at) VALUES (?, ?, ?)",
                    sensorId, key, Timestamp.from(expiresAt));
            return true;
        } catch (DuplicateKeyException e) {
            // an expired row that was not pruned yet does not count: take it over
            return jdbc.update("""
                    UPDATE ingest_replay_guard SET expires_at = ?
                    WHERE sensor_id = ? AND request_key = ? AND expires_at < ?
                    """, Timestamp.from(expiresAt), sensorId, key, Timestamp.from(now)) == 1;
        }
    }

    /** Deletes expired keys. Returns how many. */
    public int prune() {
        int n = jdbc.update("DELETE FROM ingest_replay_guard WHERE expires_at < ? LIMIT 50000",
                Timestamp.from(clock.instant()));
        if (n > 0) {
            LOG.debug("pruned {} expired replay-guard keys", n);
        }
        return n;
    }

    @Scheduled(fixedDelayString = "${ecp.ingestion.replay-guard-prune-every:PT1M}", initialDelayString = "PT1M")
    public void scheduledPrune() {
        try {
            prune();
        } catch (RuntimeException e) {
            LOG.warn("replay-guard prune failed, will retry: {}", e.toString());
        }
    }
}
