package edu.neu.ecp.platform.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the background jobs (sensor health, service watchdog, retention). On by default; tests set
 * {@code ecp.scheduling.enabled=false} and call the jobs directly, so nothing runs on its own while they check results.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ecp.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
