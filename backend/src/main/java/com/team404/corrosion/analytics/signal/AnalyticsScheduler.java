package com.team404.corrosion.analytics.signal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link AnalyticsJob} on a timer. Off with ecp.analytics.signals.enabled=false (the tests set that for every
 * test, and call the job themselves); the job stays an ordinary bean either way.
 *
 * <p>ecp.analytics.signals.every is how often it runs (default one minute, the platform's forecast rate). The
 * first run waits a little so the application, Flyway and the platform's tables are ready.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ecp.analytics.signals.enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsScheduler {

    private final AnalyticsJob job;

    public AnalyticsScheduler(AnalyticsJob job) {
        this.job = job;
    }

    @Scheduled(fixedDelayString = "${ecp.analytics.signals.every:PT1M}",
            initialDelayString = "${ecp.analytics.signals.initial-delay:PT20S}")
    public void evaluate() {
        job.run();
    }
}
