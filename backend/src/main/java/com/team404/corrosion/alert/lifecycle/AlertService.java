package com.team404.corrosion.alert.lifecycle;

import com.team404.corrosion.alert.lifecycle.AlertViews.ActionView;
import com.team404.corrosion.alert.lifecycle.AlertViews.AlertDetail;
import com.team404.corrosion.alert.lifecycle.AlertViews.AlertView;
import com.team404.corrosion.alert.AlertEvents;
import java.time.Clock;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The alert lifecycle behind /api/v1/alerts. {@code actor} is the signed-in user's email (the JWT subject). */
@Service
public class AlertService {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;
    /** Corrective action type recorded for the notes given when an alert is resolved. */
    public static final String RESOLVED_ACTION = "RESOLVED";

    private final AlertRepository alerts;
    private final AlertEvents events;
    private final Clock clock;

    public AlertService(AlertRepository alerts, AlertEvents events, Clock clock) {
        this.alerts = alerts;
        this.events = events;
        this.clock = clock;
    }

    public List<AlertView> list(String status, Integer limit) {
        return alerts.list(statuses(status), limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT));
    }

    public AlertDetail detail(long id) {
        AlertView alert = alerts.find(id).orElseThrow(() -> AlertException.notFound(id));
        return new AlertDetail(alert, alerts.actions(id));
    }

    @Transactional
    public AlertView acknowledge(long id, String actor) {
        long userId = userId(actor);
        if (!alerts.acknowledge(id, userId, clock.instant())) {
            throw refused(id, "acknowledged");
        }
        events.record(AlertEvents.ACKNOWLEDGED, id, actor, Map.of());
        return alerts.find(id).orElseThrow();
    }

    /** Resolves an OPEN or ACKNOWLEDGED alert. Non-blank notes are kept as a corrective action. */
    @Transactional
    public AlertView resolve(long id, String actor, String notes) {
        long userId = userId(actor);
        // The action goes in first because addAction refuses a resolved alert. If the resolve then loses a race,
        // the exception rolls the action back.
        if (notes != null && !notes.isBlank()
                && alerts.addAction(id, userId, RESOLVED_ACTION, notes.trim(), clock.instant()).isEmpty()) {
            throw refused(id, "resolved");
        }
        if (!alerts.resolve(id, userId, clock.instant())) {
            throw refused(id, "resolved");
        }
        Map<String, Object> details = new HashMap<>();
        if (notes != null && !notes.isBlank()) {
            details.put("notes", notes.trim());
        }
        events.record(AlertEvents.RESOLVED, id, actor, details);
        return alerts.find(id).orElseThrow();
    }

    @Transactional
    public ActionView addAction(long id, String actor, String actionType, String notes) {
        long userId = userId(actor);
        long actionId = alerts.addAction(id, userId, actionType.trim(), blankToNull(notes), clock.instant())
                .orElseThrow(() -> refused(id, "given a corrective action"));
        Map<String, Object> details = new HashMap<>();
        details.put("actionId", actionId);
        details.put("actionType", actionType.trim());
        if (blankToNull(notes) != null) {
            details.put("notes", blankToNull(notes));
        }
        events.record(AlertEvents.ACTION_LOGGED, id, actor, details);
        return alerts.action(actionId).orElseThrow();
    }

    private long userId(String email) {
        return alerts.userIdByEmail(email).orElseThrow(() -> new AlertException(HttpStatus.FORBIDDEN, "UNKNOWN_USER",
                "Your account no longer exists."));
    }

    /** Why a conditional update changed nothing: the alert is gone, or it is in the wrong state. */
    private AlertException refused(long id, String wanted) {
        return alerts.statusOf(id).map(s -> AlertException.wrongState(id, s, wanted))
                .orElseGet(() -> AlertException.notFound(id));
    }

    static List<AlertStatus> statuses(String filter) {
        String f = filter == null ? "ALL" : filter.trim().toUpperCase(Locale.ROOT);
        return switch (f) {
            case "ALL", "" -> List.of();
            case "ACTIVE" -> List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED);
            case "OPEN" -> List.of(AlertStatus.OPEN);
            case "ACKNOWLEDGED" -> List.of(AlertStatus.ACKNOWLEDGED);
            case "RESOLVED" -> List.of(AlertStatus.RESOLVED);
            default -> throw new AlertException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "status must be ALL, ACTIVE, OPEN, ACKNOWLEDGED or RESOLVED.");
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
