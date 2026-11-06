package com.team404.corrosion.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The hash that links audit entries. Pure functions with no Spring or database, so they are easy to test.
 *
 * <p>entry_hash = SHA-256 of a canonical text made of every field of the entry, including prev_hash. Each field is
 * written as {@code <length>:<value>;} and a null as {@code ~;}. The length prefix means a ';' or ':' inside a value
 * can never shift a field boundary, and null differs from an empty string. The text starts with a version tag so
 * the format can change later.
 */
public final class HashChain {

    /** prev_hash of the very first entry. */
    public static final String GENESIS_HASH = "0".repeat(64);

    // Always three fractional digits (milliseconds, the precision of the DATETIME(3) columns), so the same instant
    // always gives the same text.
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT);

    private HashChain() {
    }

    /** The exact text that is hashed. Public so tests can look at it. */
    public static String canonical(AuditEntry e) {
        StringBuilder sb = new StringBuilder("v1;");
        field(sb, Long.toString(e.id()));
        field(sb, Long.toString(e.outboxId()));
        field(sb, e.occurredAt().format(TS));
        field(sb, e.recordedAt().format(TS));
        field(sb, e.actorType());
        field(sb, e.actor());
        field(sb, e.action());
        field(sb, e.entityType());
        field(sb, e.entityId());
        field(sb, e.details());
        field(sb, e.prevHash());
        return sb.toString();
    }

    /** Lower-case hex SHA-256 (64 characters) of {@link #canonical}. */
    public static String computeHash(AuditEntry e) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(canonical(e).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static void field(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("~;");
        } else {
            sb.append(value.length()).append(':').append(value).append(';');
        }
    }
}
