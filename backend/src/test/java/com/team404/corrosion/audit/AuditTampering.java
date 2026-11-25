package com.team404.corrosion.audit;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Simulates someone with direct database access, for tests of the verifier against a modified audit_log. */
final class AuditTampering {

    private AuditTampering() {
    }

    /** Removes the triggers that guard audit_log, runs the tampering, then puts the triggers back. */
    static void whileTriggersAreOff(JdbcTemplate jdbc, Runnable tampering) {
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
