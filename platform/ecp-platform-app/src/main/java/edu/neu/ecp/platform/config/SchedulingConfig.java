package edu.neu.ecp.platform.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs (sensor health, watchdog, retention, forecast publishing). On by default;
 * {@code ecp.scheduling.enabled=false} lets integration tests run each job by hand, deterministically.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ecp.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
