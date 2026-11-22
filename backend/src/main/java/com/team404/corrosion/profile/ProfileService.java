package com.team404.corrosion.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists and edits equipment profiles (FR8). An edit validates the new values, updates the row and writes
 * PROFILE_UPDATED (details {old, new}) to audit_outbox in the same transaction, so a change is never stored without
 * its audit event, and an event never describes a change that was rolled back. Analytics reads the profile on every
 * request, so new thresholds apply at once.
 */
@Service
public class ProfileService {

    public static final String PROFILE_UPDATED = "PROFILE_UPDATED";
    /** audit_outbox.actor is VARCHAR(64). */
    private static final int ACTOR_MAX = 64;

    /** The profile does not exist (404). */
    public static class NotFound extends RuntimeException {
        public NotFound(long id) {
            super("Equipment profile " + id + " was not found.");
        }
    }

    /** The new values break the rules in {@link ProfileValidator} (400). */
    public static class Invalid extends RuntimeException {
        private final List<String> problems;

        public Invalid(List<String> problems) {
            super(String.join(" ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    /** Another profile already has that name (409). */
    public static class NameTaken extends RuntimeException {
        public NameTaken(String name) {
            super("Another profile is already called \"" + name + "\".");
        }
    }

    private final ProfileStore store;
    private final ObjectMapper json;
    private final Clock clock;

    public ProfileService(ProfileStore store, ObjectMapper json, Clock clock) {
        this.store = store;
        this.json = json;
        this.clock = clock;
    }

    public List<Profile> list() {
        return store.findAll();
    }

    /** Returns the stored profile. Writes nothing (and no audit event) when the values do not change. */
    @Transactional
    public Profile update(long id, ProfileUpdate update, String actor) {
        Profile current = store.lock(id).orElseThrow(() -> new NotFound(id));
        Profile next = update.applyTo(current);
        List<String> problems = ProfileValidator.validate(next);
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        if (next.equals(current)) {
            return current;
        }
        if (!next.name().equals(current.name()) && store.nameTakenByAnother(next.name(), id)) {
            throw new NameTaken(next.name());
        }
        store.update(next);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("profileId", id);
        details.put("old", current.values());
        details.put("new", next.values());
        store.insertOutbox(now(), actor(actor), PROFILE_UPDATED, id, toJson(details));
        return next;
    }

    private static String actor(String actor) {
        String who = actor == null || actor.isBlank() ? "unknown" : actor;
        return who.length() > ACTOR_MAX ? who.substring(0, ACTOR_MAX) : who;
    }

    private String toJson(Map<String, Object> details) {
        try {
            return json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit details are not serialisable", e);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }
}
