package com.team404.corrosion.alert;

import com.team404.corrosion.alert.lifecycle.EscalationJob;
import com.team404.corrosion.alert.signal.SignalConsumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The alert service's background jobs. Off with ecp.alerts.scheduler.enabled=false, for tests that call the jobs
 * themselves; the jobs stay ordinary beans either way.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ecp.alerts.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AlertScheduler {

    private final SignalConsumer signals;
    private final EscalationJob escalation;

    public AlertScheduler(SignalConsumer signals, EscalationJob escalation) {
        this.signals = signals;
        this.escalation = escalation;
    }

    @Scheduled(fixedDelayString = "${ecp.alerts.poll-interval:PT5S}")
    public void consumeSignals() {
        signals.poll();
    }

    @Scheduled(fixedDelayString = "${ecp.alerts.escalation-interval:PT30S}")
    public void escalate() {
        escalation.poll();
    }
}
