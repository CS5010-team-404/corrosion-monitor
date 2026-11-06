package com.team404.corrosion.alert.lifecycle;

import com.team404.corrosion.alert.AlertEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Escalates OPEN CRITICAL alerts that nobody acknowledged within {@code ecp.alerts.escalate-after} (5 minutes) to
 * the managers, once. The flag and its audit event are saved together before the email goes out, so a mail failure never sends twice; the email is
 * best effort.
 */
@Component
public class EscalationJob {

    private static final Logger log = LoggerFactory.getLogger(EscalationJob.class);

    private final AlertRepository alerts;
    private final ManagerNotifier notifier;
    private final AlertEvents events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration after;

    public EscalationJob(AlertRepository alerts, ManagerNotifier notifier, AlertEvents events, TransactionTemplate tx,
                         Clock clock,
                         @Value("${ecp.alerts.escalate-after:PT5M}") Duration after) {
        this.alerts = alerts;
        this.notifier = notifier;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
        this.after = after;
    }

    @Scheduled(fixedDelayString = "${ecp.alerts.escalation-interval:PT30S}")
    public void poll() {
        try {
            escalateDue();
        } catch (RuntimeException e) {
            log.warn("Escalation check failed, will retry: {}", e.toString());
        }
    }

    /** Returns how many alerts this call escalated. */
    int escalateDue() {
        Instant now = clock.instant();
        int escalated = 0;
        for (long id : alerts.escalationCandidates(now.minus(after))) {
            boolean done = Boolean.TRUE.equals(tx.execute(status -> {
                if (!alerts.escalate(id, now)) {
                    return false;
                }
                events.record(AlertEvents.ESCALATED, id, null, Map.of("level", 1));
                return true;
            }));
            if (done) {
                escalated++;
                alerts.find(id).ifPresent(a -> notifier.escalated(a, alerts.managerEmails()));
            }
        }
        return escalated;
    }
}
