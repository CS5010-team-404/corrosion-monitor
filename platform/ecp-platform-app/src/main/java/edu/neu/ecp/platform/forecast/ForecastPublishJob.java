package edu.neu.ecp.platform.forecast;

import edu.neu.ecp.common.ops.GuardedJob;
import edu.neu.ecp.common.ops.Heartbeats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes forecasts every minute ({@code ecp.forecast.publish.every}). Switch off with
 * {@code ecp.forecast.publish.enabled=false} (the tests do). Its heartbeat appears on the System panel as
 * "forecast-publisher", and the service watchdog raises SERVICE_DOWN if it stalls.
 */
@Component
@ConditionalOnProperty(name = "ecp.forecast.publish.enabled", havingValue = "true", matchIfMissing = true)
public class ForecastPublishJob extends GuardedJob {

    static final String SERVICE = "forecast-publisher";

    private final ForecastPublisher publisher;

    public ForecastPublishJob(Heartbeats heartbeats, ForecastPublisher publisher) {
        super(heartbeats);
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${ecp.forecast.publish.every:PT1M}", initialDelayString = "PT30S")
    public void scheduled() {
        run();
    }

    @Override
    protected String serviceName() {
        return SERVICE;
    }

    @Override
    protected void runOnce() {
        publisher.publishAll();
    }
}
