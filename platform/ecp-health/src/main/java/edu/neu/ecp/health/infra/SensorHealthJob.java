package edu.neu.ecp.health.infra;

import edu.neu.ecp.common.ops.GuardedJob;
import edu.neu.ecp.common.ops.Heartbeats;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SensorHealthJob extends GuardedJob {

    private final SensorHealthService service;

    public SensorHealthJob(Heartbeats heartbeats, SensorHealthService service) {
        super(heartbeats);
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${ecp.health.evaluate-every:PT5S}", initialDelayString = "PT10S")
    public void scheduled() {
        run();
    }

    @Override
    protected String serviceName() {
        return SensorHealthService.SERVICE;
    }

    @Override
    protected void runOnce() {
        service.evaluateAll();
    }
}
