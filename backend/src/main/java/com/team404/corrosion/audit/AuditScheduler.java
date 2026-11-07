package com.team404.corrosion.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the chaining worker about once a second and saves a checkpoint every night at midnight UTC.
 * Settings (all optional): ecp.audit.drain-interval-ms, ecp.audit.drain-initial-delay-ms, ecp.audit.checkpoint-cron.
 * Set ecp.audit.scheduler.enabled=false to switch both off (tests that call the worker directly do this).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ecp.audit.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AuditScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(AuditScheduler.class);

    private final AuditChainWorker worker;
    private final AuditService service;

    public AuditScheduler(AuditChainWorker worker, AuditService service) {
        this.worker = worker;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${ecp.audit.drain-interval-ms:1000}",
            initialDelayString = "${ecp.audit.drain-initial-delay-ms:5000}")
    public void drain() {
        try {
            worker.drainAll();
        } catch (RuntimeException e) {
            // The outbox is untouched, so the next run simply tries again.
            LOG.error("Audit chaining failed; will retry", e);
        }
    }

    @Scheduled(cron = "${ecp.audit.checkpoint-cron:0 0 0 * * *}", zone = "UTC")
    public void checkpoint() {
        try {
            service.saveCheckpoint();
        } catch (RuntimeException e) {
            LOG.error("Saving the audit checkpoint failed", e);
        }
    }
}
