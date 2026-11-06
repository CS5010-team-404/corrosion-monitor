package com.team404.corrosion.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team404.corrosion.AivenMySql;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the real SQL against MySQL 8.4 with the production settings (see AivenMySql), after applying the team's real
 * Flyway migrations from src/main/resources/db/migration. Needs Docker running. It builds the audit services by hand
 * instead of starting Spring.
 *
 * Because it uses the real schema, it also checks that the audit migration fits with the others (V1 and V2 are
 * applied first) and that the real audit_outbox accepts the rows other services write (the columns LoginAudit uses).
 */
@Testcontainers
class AuditChainIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = AivenMySql.container().withUrlParam("connectionTimeZone", "UTC");

    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;
    static AuditStore store;
    static AuditChainWorker worker;
    static AuditService service;

    @BeforeAll
    static void migrateAndCreateServices() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        Clock clock = Clock.systemUTC();
        store = new AuditStore(jdbc);
        worker = new AuditChainWorker(store, new DataSourceTransactionManager(dataSource), clock);
        service = new AuditService(store, new ObjectMapper(), clock);
    }

    /** TRUNCATE does not fire the append-only delete trigger, and it also resets the auto-increment counters. */
    @BeforeEach
    void emptyEverything() {
        jdbc.execute("TRUNCATE TABLE audit_log");
        jdbc.execute("TRUNCATE TABLE audit_outbox");
        jdbc.execute("TRUNCATE TABLE audit_checkpoint");
        jdbc.execute("TRUNCATE TABLE audit_verification");
        jdbc.update("UPDATE audit_chain_head SET last_id = 0, last_hash = ? WHERE id = 1", HashChain.GENESIS_HASH);
    }

    @Test
    void chainsOutboxRowsInOrderAndVerifies() {
        outbox(null, "dana@corrosionwatch.app", "USER_LOGIN");
        outbox(null, "dana@corrosionwatch.app", "ALERT_ACKNOWLEDGED");
        outbox(null, "lee@corrosionwatch.app", "USER_LOGIN_FAILED");

        assertEquals(3, worker.drainAll());

        List<AuditEntry> entries = store.readRange(0, 10, 10);
        assertEquals(3, entries.size());
        assertEquals("USER_LOGIN", entries.get(0).action());
        assertEquals("USER_LOGIN_FAILED", entries.get(2).action());
        assertEquals(HashChain.GENESIS_HASH, entries.get(0).prevHash());
        assertEquals(entries.get(0).entryHash(), entries.get(1).prevHash());

        VerificationResult result = service.verify("admin@corrosionwatch.app");
        assertTrue(result.valid());
        assertEquals(3L, result.entriesChecked());
    }

    @Test
    void chainedOutboxRowsAreMarkedAndNotPickedUpAgain() {
        outbox(null, "a", "A");
        outbox(null, "b", "B");

        assertEquals(2, worker.drainAll());

        Long stillPending = jdbc.queryForObject("SELECT COUNT(*) FROM audit_outbox WHERE chained_at IS NULL",
                Long.class);
        assertEquals(Long.valueOf(0), stillPending);

        outbox(null, "c", "C");
        Long pendingNow = jdbc.queryForObject("SELECT COUNT(*) FROM audit_outbox WHERE chained_at IS NULL",
                Long.class);
        assertEquals(Long.valueOf(1), pendingNow);
        assertEquals(1, worker.drainAll());
    }

    @Test
    void drainingTwiceDoesNotDuplicateEntries() {
        outbox(null, "a", "A");
        outbox(null, "b", "B");

        assertEquals(2, worker.drainAll());
        assertEquals(0, worker.drainAll());
        assertEquals(2L, store.count(AuditStore.Filter.NONE));
    }

    @Test
    void rowThatCommitsLateWithALowerIdIsStillChained() {
        outbox(10L, "first", "A");
        assertEquals(1, worker.drainAll());

        outbox(9L, "late", "B");
        assertEquals(1, worker.drainAll());

        assertEquals(2L, store.count(AuditStore.Filter.NONE));
        assertTrue(service.verify("admin@corrosionwatch.app").valid());
    }

    @Test
    void editedRowIsDetected() {
        chainThree();
        whileTriggersAreOff(() -> jdbc.update("UPDATE audit_log SET actor = 'mallory' WHERE id = 2"));

        VerificationResult result = service.verify("admin@corrosionwatch.app");
        assertFalse(result.valid());
        assertEquals(Long.valueOf(2), result.firstBrokenId());
    }

    @Test
    void deletedRowIsDetected() {
        chainThree();
        whileTriggersAreOff(() -> jdbc.update("DELETE FROM audit_log WHERE id = 2"));

        VerificationResult result = service.verify("admin@corrosionwatch.app");
        assertFalse(result.valid());
        assertEquals(Long.valueOf(2), result.firstBrokenId());
    }

    @Test
    void rowsRemovedFromTheEndAreDetectedAgainstTheHead() {
        chainThree();
        whileTriggersAreOff(() -> jdbc.update("DELETE FROM audit_log WHERE id = 3"));

        assertFalse(service.verify("admin@corrosionwatch.app").valid());
    }

    @Test
    void completelyRewrittenChainIsCaughtByACheckpoint() {
        chainThree();
        service.saveCheckpoint();

        // An attacker edits entry 2 and recomputes every hash after it, so the chain alone looks perfect.
        List<AuditEntry> entries = store.readRange(0, 3, 10);
        AuditEntry second = entries.get(1);
        AuditEntry editedSecondUnsigned = new AuditEntry(second.id(), second.outboxId(), second.occurredAt(),
                second.recordedAt(), second.actorType(), "mallory", second.action(), second.entityType(),
                second.entityId(), second.details(), second.prevHash(), null);
        AuditEntry editedSecond = editedSecondUnsigned.withEntryHash(HashChain.computeHash(editedSecondUnsigned));
        AuditEntry third = entries.get(2);
        AuditEntry editedThirdUnsigned = new AuditEntry(third.id(), third.outboxId(), third.occurredAt(),
                third.recordedAt(), third.actorType(), third.actor(), third.action(), third.entityType(),
                third.entityId(), third.details(), editedSecond.entryHash(), null);
        AuditEntry editedThird = editedThirdUnsigned.withEntryHash(HashChain.computeHash(editedThirdUnsigned));

        whileTriggersAreOff(() -> {
            jdbc.update("UPDATE audit_log SET actor = ?, entry_hash = ? WHERE id = 2", "mallory",
                    editedSecond.entryHash());
            jdbc.update("UPDATE audit_log SET prev_hash = ?, entry_hash = ? WHERE id = 3",
                    editedThird.prevHash(), editedThird.entryHash());
            jdbc.update("UPDATE audit_chain_head SET last_hash = ? WHERE id = 1", editedThird.entryHash());
        });

        VerificationResult result = service.verify("admin@corrosionwatch.app");
        assertFalse(result.valid());
        assertTrue(result.message().contains("checkpoint"));
    }

    @Test
    void verifyRecordsItsOwnEventAndTheRun() {
        chainThree();

        service.verify("admin@corrosionwatch.app");

        Long events = jdbc.queryForObject("SELECT COUNT(*) FROM audit_outbox WHERE action = 'AUDIT_VERIFIED'",
                Long.class);
        assertEquals(Long.valueOf(1), events);
        assertEquals(1, service.recentVerifications().size());

        assertEquals(1, worker.drainAll());
        assertEquals(4L, store.count(AuditStore.Filter.NONE));
        assertTrue(service.verify("admin@corrosionwatch.app").valid());
    }

    @Test
    void searchFiltersByActorAndAction() {
        outbox(null, "dana@corrosionwatch.app", "USER_LOGIN");
        outbox(null, "dana@corrosionwatch.app", "ALERT_ACKNOWLEDGED");
        outbox(null, "lee@corrosionwatch.app", "USER_LOGIN");
        worker.drainAll();

        AuditService.PageResult<AuditEntry> byActor =
                service.search(new AuditStore.Filter("dana@corrosionwatch.app", null, null, null, null), 0, 50);
        assertEquals(2L, byActor.total());

        AuditService.PageResult<AuditEntry> both =
                service.search(new AuditStore.Filter("dana@corrosionwatch.app", "USER_LOGIN", null, null, null), 0, 50);
        assertEquals(1L, both.total());
        assertEquals(1, both.items().size());

        AuditService.PageResult<AuditEntry> newestFirst = service.search(AuditStore.Filter.NONE, 0, 2);
        assertEquals(3L, newestFirst.total());
        assertEquals(3L, newestFirst.items().get(0).id());
        assertEquals(2, newestFirst.items().size());
    }

    /** Needs the append-only triggers migration (or an equivalent one in the team's schema) to be present. */
    @Test
    void appendOnlyTriggersRejectUpdateAndDelete() {
        chainThree();

        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE audit_log SET actor = 'x' WHERE id = 1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM audit_log WHERE id = 1"));
        assertTrue(service.verify("admin@corrosionwatch.app").valid());
    }

    // ---- helpers ----

    private void chainThree() {
        outbox(null, "dana@corrosionwatch.app", "USER_LOGIN");
        outbox(null, "dana@corrosionwatch.app", "ALERT_ACKNOWLEDGED");
        outbox(null, "lee@corrosionwatch.app", "CORRECTIVE_ACTION_LOGGED");
        assertEquals(3, worker.drainAll());
    }

    /** Inserts one outbox row the way another service would. A null id lets MySQL pick the next one. */
    private static void outbox(Long id, String actor, String action) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("INSERT INTO audit_outbox (id, occurred_at, actor_type, actor, action, entity_type, entity_id, "
                + "details) VALUES (?, ?, 'USER', ?, ?, 'user', '1', '{\"k\":1}')", id, now, actor, action);
    }

    /**
     * Simulates someone with database access: removes whatever triggers guard audit_log, runs the tampering, then puts
     * the triggers back, so the verifier can be tested against a modified table.
     */
    private static void whileTriggersAreOff(Runnable tampering) {
        List<String> names = jdbc.queryForList("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS "
                + "WHERE EVENT_OBJECT_SCHEMA = DATABASE() AND EVENT_OBJECT_TABLE = 'audit_log'", String.class);
        List<String> definitions = names.stream()
                .map(name -> String.valueOf(jdbc.queryForMap("SHOW CREATE TRIGGER " + name)
                        .get("SQL Original Statement")))
                .toList();
        names.forEach(name -> jdbc.execute("DROP TRIGGER " + name));
        try {
            tampering.run();
        } finally {
            definitions.forEach(jdbc::execute);
        }
    }
}
