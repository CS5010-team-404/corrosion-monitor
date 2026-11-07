package edu.neu.ecp.common.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template Method for every background job. A job that throws records the failure and the scheduler keeps
 * running the other services: this is how "one failure never stops the others" from the proposal is enforced.
 *
 * <p>Subclasses add {@code @Scheduled} to a method that calls {@link #run()}.
 */
public abstract class GuardedJob {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final Heartbeats heartbeats;

    protected GuardedJob(Heartbeats heartbeats) {
        this.heartbeats = heartbeats;
    }

    /** Stable service name shown on the System panel, e.g. "sensor-health". */
    protected abstract String serviceName();

    /** One unit of work. Throw freely; it is caught and recorded here. */
    protected abstract void runOnce() throws Exception;

    public final void run() {
        try {
            runOnce();
            heartbeats.success(serviceName());
        } catch (Exception e) {
            log.error("{} run failed", serviceName(), e);
            try {
                heartbeats.failure(serviceName(), e);
            } catch (RuntimeException heartbeatError) {
                log.error("could not record heartbeat failure for {}", serviceName(), heartbeatError);
            }
        }
    }
}
