package com.team404.corrosion.profile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL for equipment_profile (existing columns from V1 and V8, no new migration) and the PROFILE_UPDATED row in
 * audit_outbox. Plain SQL that works in Aiven's ANSI sql_mode: single-quoted strings, no backticks.
 */
@Repository
public class ProfileStore {

    private static final String COLUMNS = "id, name, rh_warn_pct, rh_critical_pct, dew_margin_warn_c, "
            + "dew_margin_critical_c, temp_min_c, temp_max_c, surface_offset_c";

    private static final RowMapper<Profile> MAPPER = (rs, i) -> new Profile(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getBigDecimal("rh_warn_pct"),
            rs.getBigDecimal("rh_critical_pct"),
            rs.getBigDecimal("dew_margin_warn_c"),
            rs.getBigDecimal("dew_margin_critical_c"),
            rs.getBigDecimal("temp_min_c"),
            rs.getBigDecimal("temp_max_c"),
            rs.getBigDecimal("surface_offset_c"));

    private final JdbcTemplate jdbc;

    public ProfileStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Profile> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM equipment_profile ORDER BY id", MAPPER);
    }

    /** Reads one profile and locks its row until the transaction ends, so two admins cannot interleave edits. */
    public Optional<Profile> lock(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM equipment_profile WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public boolean nameTakenByAnother(String name, long id) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM equipment_profile WHERE name = ? AND id <> ?",
                Long.class, name, id);
        return n != null && n > 0;
    }

    public void update(Profile p) {
        jdbc.update("UPDATE equipment_profile SET name = ?, rh_warn_pct = ?, rh_critical_pct = ?, "
                        + "dew_margin_warn_c = ?, dew_margin_critical_c = ?, temp_min_c = ?, temp_max_c = ?, "
                        + "surface_offset_c = ? WHERE id = ?",
                p.name(), p.rhWarningPct(), p.rhCriticalPct(), p.marginWarningC(), p.marginCriticalC(),
                p.tempMinC(), p.tempMaxC(), p.surfaceOffsetC(), p.id());
    }

    /** Same columns as LoginAudit and the alert events (docs/CONTRACTS.md); the audit worker chains it. */
    public void insertOutbox(LocalDateTime occurredAt, String actor, String action, long profileId,
                             String detailsJson) {
        jdbc.update("INSERT INTO audit_outbox (occurred_at, actor_type, actor, action, entity_type, entity_id, details) "
                + "VALUES (?, 'USER', ?, ?, 'equipment_profile', ?, ?)",
                occurredAt, actor, action, Long.toString(profileId), detailsJson);
    }
}
