package edu.neu.ecp.health.infra;

import edu.neu.ecp.common.audit.AuditEvent;
import edu.neu.ecp.common.audit.AuditRecorder;
import edu.neu.ecp.common.ops.GuardedJob;
import edu.neu.ecp.common.ops.Heartbeats;
import edu.neu.ecp.common.signal.AlertSignal;
import edu.neu.ecp.common.signal.Severity;
import edu.neu.ecp.common.signal.SignalPublisher;
import edu.neu.ecp.common.signal.SignalSource;
import edu.neu.ecp.common.signal.SignalState;
import edu.neu.ecp.common.signal.SignalTypes;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Watches the other background services through their heartbeats and raises SERVICE_DOWN when one stops
 * succeeding (CPS reliability: the monitoring system monitors itself).
 */
@Component
public class ServiceWatchdog extends GuardedJob {

    private final HealthQueries queries;
    private final SignalPublisher signals;
    private final AuditRecorder audit;
    private final HealthProperties properties;
    private final Clock clock;
    private final Set<String> down = ConcurrentHashMap.newKeySet();

    public ServiceWatchdog(Heartbeats heartbeats, HealthQueries queries, SignalPublisher signals, AuditRecorder audit,
                           HealthProperties properties, Clock clock) {
        super(heartbeats);
        this.queries = queries;
        this.signals = signals;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT60S")
    public void scheduled() {
        run();
    }

    @Override
    protected String serviceName() {
        return "service-watchdog";
    }

    @Override
    protected void runOnce() {
        Instant now = clock.instant();
        for (HealthQueries.ServiceView s : queries.services(now, properties.getServiceStaleAfter())) {
            if (s.service().equals(serviceName())) {
                continue;
            }
            String key = "SYSTEM:" + SignalTypes.SERVICE_DOWN + ":" + s.service();
            if (!"UP".equals(s.state()) && down.add(s.service())) {
                signals.publish(new AlertSignal(SignalSource.SYSTEM, SignalTypes.SERVICE_DOWN, Severity.CRITICAL,
                        SignalState.RAISED, null, null, key, "Service not running: " + s.service(),
                        "Last success " + s.lastSuccessAt() + (s.lastError() == null ? "" : "; last error: " + s.lastError()),
                        Map.of("service", s.service())));
                audit.record(AuditEvent.system(serviceName(), "SERVICE_DOWN", "service", s.service(), Map.of()));
            } else if ("UP".equals(s.state()) && down.remove(s.service())) {
                signals.publish(new AlertSignal(SignalSource.SYSTEM, SignalTypes.SERVICE_DOWN, Severity.CRITICAL,
                        SignalState.CLEARED, null, null, key, "Service recovered: " + s.service(), null,
                        Map.of("service", s.service())));
                audit.record(AuditEvent.system(serviceName(), "SERVICE_RECOVERED", "service", s.service(), Map.of()));
            }
        }
    }
}
