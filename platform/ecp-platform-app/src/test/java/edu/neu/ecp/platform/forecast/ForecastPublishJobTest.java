package edu.neu.ecp.platform.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.neu.ecp.common.ops.Heartbeats;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The publishing job is on unless ecp.forecast.publish.enabled=false, and reports to the service watchdog. */
class ForecastPublishJobTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(Heartbeats.class, () -> mock(Heartbeats.class))
            .withBean(ForecastPublisher.class, () -> mock(ForecastPublisher.class))
            .withUserConfiguration(ForecastPublishJob.class);

    @Test
    void onByDefault() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(ForecastPublishJob.class));
        runner.withPropertyValues("ecp.forecast.publish.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(ForecastPublishJob.class));
    }

    @Test
    void canBeSwitchedOff() {
        runner.withPropertyValues("ecp.forecast.publish.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ForecastPublishJob.class));
    }

    @Test
    void recordsAHeartbeatForEachRun() {
        Heartbeats heartbeats = mock(Heartbeats.class);
        ForecastPublisher publisher = mock(ForecastPublisher.class);
        new ForecastPublishJob(heartbeats, publisher).scheduled();
        verify(publisher).publishAll();
        verify(heartbeats).success("forecast-publisher");
    }

    @Test
    void aFailedRunIsRecordedNotThrown() {
        Heartbeats heartbeats = mock(Heartbeats.class);
        ForecastPublisher publisher = mock(ForecastPublisher.class);
        when(publisher.publishAll()).thenThrow(new IllegalStateException("database down"));
        new ForecastPublishJob(heartbeats, publisher).scheduled();
        verify(heartbeats).failure(eq("forecast-publisher"), any(IllegalStateException.class));
    }
}
